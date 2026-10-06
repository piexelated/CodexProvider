package dev.piexel.codex

import dev.piexel.codex.install.*
import dev.piexel.codex.jetbrains.*
import dev.piexel.codex.protocol.*
import dev.piexel.codex.runtime.*

import com.google.gson.JsonParser
import ai.grazie.model.cloud.ContentType
import com.intellij.ml.llm.core.providers.AiaLlmProviderType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/** Executable tests use the JDK and IDE-bundled libraries; no extra test framework. */
fun main() = runBlocking {
    var passed = 0
    suspend fun verify(name: String, block: suspend () -> Unit) {
        withTimeout(15_000) { block() }
        passed++
        println("PASS $name")
    }
    suspend fun fixture(block: suspend (JsonRpc, CodexSession) -> Unit) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-cp", System.getProperty("java.class.path"), "dev.piexel.codex.FakeServer",
        ).start()
        val rpc = JsonRpc(process)
        val work = Files.createTempDirectory("codex-session-test-")
        val session = CodexSession(rpc, work)
        try { session.initialize(); block(rpc, session) }
        finally { session.close(); work.toFile().deleteRecursively() }
        check(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
    }
    verify("debug trace format contains fields without adding payload data") {
        val trace = CodexTrace.format("test-event", listOf("requestId" to 7, "messages" to 2))
        check(trace == "[Codex Provider] test-event requestId=7 messages=2")
        check(!trace.contains("prompt", ignoreCase = true))
    }
    verify("diagnostic response logging preserves multiline model text") {
        check(CodexTrace.escapeLogValue("1. first_name\n2. secondName\t") == "1. first_name\\n2. secondName\\t")
    }
    verify("account status exposes only the action that fits the session") {
        val signedOut = codexAccountStatus(null)
        check(signedOut.text == "Not signed in")
        check(signedOut.action == CodexAccountAction.SIGN_IN)
        val signedIn = codexAccountStatus(obj("email" to "user@example.invalid", "planType" to "prolite"))
        check(signedIn.text == "user@example.invalid · Pro Lite")
        check(signedIn.action == CodexAccountAction.SIGN_OUT)
    }
    verify("debug mode is opt-in and survives settings persistence") {
        val settings = CodexSettings()
        check(!settings.debugMode)
        settings.debugMode = true
        val restored = CodexSettings()
        restored.loadState(settings.state)
        check(restored.debugMode)
        restored.debugMode = false
        check(!restored.state.debugMode)
    }
    verify("protocol failures retain safe codes and actionable guidance") {
        val auth = CodexFailure.fromError(obj("code" to -32000, "message" to "private-source", "data" to obj("codexErrorInfo" to "unauthorized")))
        check(auth.kind == CodexFailureKind.AUTHENTICATION && auth.rpcCode == -32000)
        val quota = CodexFailure.fromError(obj("codexErrorInfo" to "usageLimitExceeded"))
        check(quota.kind == CodexFailureKind.QUOTA)
        val network = CodexFailure.fromError(obj("codexErrorInfo" to obj("httpConnectionFailed" to obj("httpStatusCode" to 502))))
        check(network.kind == CodexFailureKind.NETWORK && network.httpStatusCode == 502)
        val rateLimit = CodexFailure.fromError(obj("codexErrorInfo" to obj("httpConnectionFailed" to obj("httpStatusCode" to 429))))
        check(rateLimit.kind == CodexFailureKind.QUOTA)
        val model = CodexFailure.fromError(obj("message" to "The private-source model is not supported"))
        check(model.kind == CodexFailureKind.MODEL)
        check(CodexFailure.fromError(obj("code" to -32601)).kind == CodexFailureKind.PROTOCOL)
        check(listOf(auth, quota, network, rateLimit, model).none { it.message.orEmpty().contains("private-source") })
    }
    verify("runtime swap drains generation and releases permits after cancellation") {
        val gate = CodexGenerationGate()
        val active = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.generate { active.complete(Unit); finish.await() }
        }
        active.await()
        val swapping = CompletableDeferred<Unit>()
        val releaseSwap = CompletableDeferred<Unit>()
        val swap = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.pause { swapping.complete(Unit); releaseSwap.await() }
        }
        check(!swapping.isCompleted)
        val nextStarted = CompletableDeferred<Unit>()
        val next = launch(start = CoroutineStart.UNDISPATCHED) { gate.generate { nextStarted.complete(Unit) } }
        check(!nextStarted.isCompleted)
        finish.complete(Unit)
        swapping.await()
        check(!nextStarted.isCompleted)
        releaseSwap.complete(Unit)
        joinAll(request, swap, next)
        val held = CompletableDeferred<Unit>()
        val releaseHeld = CompletableDeferred<Unit>()
        val blocked = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.generate { held.complete(Unit); releaseHeld.await() }
        }
        held.await()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.pause { error("Still active") } }
        cancelled.cancelAndJoin()
        releaseHeld.complete(Unit)
        blocked.join()
        coroutineScope {
            val both = CompletableDeferred<Unit>()
            var entered = 0
            val jobs = List(2) { launch { gate.generate { if (++entered == 2) both.complete(Unit); both.await() } } }
            jobs.joinAll()
        }
    }
    verify("failed and cancelled runtime replacements preserve the working connection") {
        val work = Files.createTempDirectory("codex-replacement-test-")
        val processes = mutableListOf<Process>()
        val manager = CodexSessionManager(Dispatchers.IO, {}, {}, launchProcess = {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), "dev.piexel.codex.FakeServer",
            ).start()
            processes.add(process)
            process to work
        })
        try {
            val original = manager.getOrStart { Path.of("original") }
            val failed = runCatching { manager.replace(Path.of("invalid")) { error("Invalid catalog") } }
            check(failed.isFailure)
            check(manager.isCurrent(original) && original.isAlive && processes.last().waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
            val cancelled = runCatching {
                withTimeout(300) { manager.replace(Path.of("cancelled")) { delay(5_000) } }
            }
            check(cancelled.exceptionOrNull() is TimeoutCancellationException)
            check(manager.isCurrent(original) && original.isAlive && processes.last().waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
            check(original.generate("test-model", listOf(Message("user", "first"))) == "first-result")
            val replacement = manager.replace(Path.of("replacement")) { it.also { check(it.models().isNotEmpty()) } }
            check(manager.isCurrent(replacement) && replacement.isAlive && !original.isAlive)
            check(manager.getOrStart { error("Should reuse replacement") } === replacement)
        } finally {
            manager.close()
            work.toFile().deleteRecursively()
        }
    }
    verify("feature catalog maps prompt IDs to independent model slots") {
        check(CodexFeatures.forPrompt("ij.refactorings.name-suggestion")?.id == "rename")
        check(CodexFeatures.forPrompt("ij.editor.edit-request")?.id == "editor_edit")
        check(CodexFeatures.forPrompt("ij.editor.generate-unit-tests")?.id == "tests")
        check(CodexFeatures.forPrompt("unknown.feature") == null)
        check(CodexFeatures.forPrompt("ij.fast.chat.title-generation")?.default == CodexFeatureDefault.INSTANT)
        check(CodexFeatures.all.filterNot { it.id == "chat_helpers" }.all { it.default == CodexFeatureDefault.CORE })
        check(CodexFeatures.all.flatMap { it.promptIds }.distinct().size == CodexFeatures.all.sumOf { it.promptIds.size })
    }
    verify("unassigned feature models keep Codex models available") {
        check(matchesFeatureModel(null, "codex-model-a"))
        check(matchesFeatureModel(null, "codex-model-b"))
        check(matchesFeatureModel("codex-model-b", "codex-model-b"))
        check(!matchesFeatureModel("codex-model-b", "codex-model-a"))
    }
    verify("subscription profiles are not classified as BYOK OpenAI") {
        check(codexProfileProviderType() == AiaLlmProviderType.Unknown)
    }
    verify("text MIME types are supported without advertising media support") {
        check(codexSupportsContentType(ContentType("text", "plain")))
        check(codexSupportsContentType(ContentType("text", "markdown")))
        check(!codexSupportsContentType(ContentType("image", "png")))
        check(!codexSupportsContentType(ContentType("audio", "wav")))
        check(!codexSupportsContentType(ContentType("application", "pdf")))
    }
    verify("repeated account refreshes do not publish model-setting changes") {
        val catalog = CodexModelCatalog()
        val model = CodexModel("test-model", "Test model")
        check(catalog.replace(listOf(model)))
        repeat(3) { check(!catalog.replace(listOf(model))) }
        check(catalog.changes.value == listOf(model))
        val helper = CodexModel("helper-model", "Helper")
        check(catalog.replace(listOf(model, helper)))
        check(!catalog.replace(listOf(helper, model)))
        check(catalog.replace(emptyList()))
        check(!catalog.replace(emptyList()))
    }
    verify("model discovery invalidation survives a late IDE subscription") {
        val settings = CodexSettings()
        val catalog = CodexModelCatalog()
        val models = listOf(CodexModel("rename-model", "Rename model"))
        // The IDE has already observed the initial empty catalog. Startup
        // finishes before its provider-state listener begins collecting.
        check(catalog.replace(models))
        settings.changed()
        withTimeout(500) { settings.connectionSettingsUpdatedFlow.first() }
        check(catalog.changes.value == models)
        // Re-subscribing after provider enablement must also see the change.
        withTimeout(500) { settings.connectionSettingsUpdatedFlow.first() }
    }
    verify("slow IDE listeners do not lose the latest settings invalidation") {
        val settings = CodexSettings()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var received = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            settings.connectionSettingsUpdatedFlow.collect {
                received++
                if (received == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            settings.changed()
            entered.await()
            repeat(10) { settings.changed() }
            release.complete(Unit)
            yield()
            check(received == 2)
        } finally { collector.cancelAndJoin() }
    }
    verify("IDE discovery returns immediately while a single background fetch fills its cache") {
        val catalog = CodexModelCatalog()
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val expected = listOf(CodexModel("account-model", "Account model"))
        val fetch: suspend () -> Unit = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            catalog.replace(expected)
            finished.complete(Unit)
        }
        try {
            withTimeout(500) { check(catalog.getOrRefresh(background, fetch).isEmpty()) }
            started.await()
            repeat(5) { check(catalog.getOrRefresh(background, fetch).isEmpty()) }
            check(calls.get() == 1)
            release.complete(Unit)
            finished.await()
            check(catalog.getOrRefresh(background, fetch) == expected)
            check(calls.get() == 1)
        } finally { background.cancel() }
    }
    verify("catalog observers receive changes but not repeated snapshots") {
        val catalog = CodexModelCatalog()
        val observed = mutableListOf<List<CodexModel>?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            catalog.changes.collect { observed.add(it) }
        }
        try {
            val models = listOf(CodexModel("test-model", "Test model"))
            catalog.replace(models)
            yield()
            catalog.replace(models)
            yield()
            catalog.replace(emptyList())
            yield()
            check(observed == listOf(null, models, emptyList<CodexModel>()))
        } finally { collector.cancelAndJoin() }
    }
    verify("subscription account, login URL, and paginated models") {
        fixture { _, session ->
            check(session.account()?.text("type") == "chatgpt")
            check(session.login() == "https://auth.openai.com/test-login")
            check(session.models() == listOf(CodexModel("test-model", "Test model"), CodexModel("helper-model", "Helper model")))
        }
    }
    verify("subscription quota is read from the authoritative Codex usage response") {
        fixture { _, session ->
            check(session.quota() == CodexQuota(usageAllowed = true, usedPercent = 17, resetsAtEpochSeconds = 1_800_000_000))
        }
    }
    verify("native message translation and final answer without commentary") {
        fixture { _, session ->
            val answer = session.generate("test-model", listOf(Message("system", "Return code only"), Message("user", "rename")))
            check(answer == "regionCount")
        }
    }
    verify("project instructions are enabled and project directory reaches Codex") {
        check("project_doc_max_bytes=32768" in codexAppServerCommand(Path.of("codex")))
        fixture { rpc, session ->
            val project = Files.createTempDirectory("codex-project-test-")
            try {
                Files.writeString(project.resolve("AGENTS.md"), "Use project conventions.")
                session.startThread("test-model", listOf(Message("user", "probe")), project)
                check(rpc.request("test/threadCwd").text("cwd") == project.toString())
            } finally { project.toFile().deleteRecursively() }
        }
    }
    verify("concurrent requests stay on their own threads") {
        fixture { _, session ->
            coroutineScope {
                val first = async { session.generate("test-model", listOf(Message("user", "first"))) }
                val second = async { session.generate("test-model", listOf(Message("user", "second"))) }
                check(first.await() == "first-result")
                check(second.await() == "second-result")
            }
        }
    }
    verify("cancellation interrupts its Codex turn") {
        fixture { rpc, session ->
            val timedOut = runCatching { session.generate("test-model", listOf(Message("user", "wait")), timeoutMs = 300) }
            check(timedOut.exceptionOrNull() is TimeoutCancellationException)
            check(rpc.request("test/interruptCount").get("count").asInt == 1)
        }
    }
    verify("cancellation before a turn ID arrives stops the process") {
        fixture { _, session ->
            val timedOut = runCatching { session.generate("test-model", listOf(Message("user", "slow-start")), timeoutMs = 300) }
            check(timedOut.exceptionOrNull() is TimeoutCancellationException)
            check(!session.isAlive)
        }
    }
    verify("failed and empty turns are not returned as edits") {
        fixture { _, session ->
            check(runCatching { session.generate("test-model", listOf(Message("user", "fail"))) }.isFailure)
            check(runCatching { session.generate("test-model", listOf(Message("user", "empty"))) }.isFailure)
        }
    }
    verify("raw protocol errors are not exposed to the IDE") {
        fixture { rpc, _ ->
            val failure = runCatching { rpc.request("test/error") }.exceptionOrNull()
            check(failure != null && !failure.message.orEmpty().contains("private-source"))
        }
    }
    verify("malformed frames fail waiting requests with safe protocol guidance") {
        fixture { rpc, _ ->
            val failure = runCatching { rpc.request("test/malformed") }.exceptionOrNull()
            check(failure is CodexFailure && failure.kind == CodexFailureKind.MALFORMED_PROTOCOL)
            check(!failure.message.orEmpty().contains("private-source"))
        }
    }
    verify("malformed response bodies do not strand the matching request") {
        fixture { rpc, _ ->
            val failure = runCatching { rpc.request("test/malformed-result", timeoutMs = 500) }.exceptionOrNull()
            check(failure is CodexFailure && failure.kind == CodexFailureKind.MALFORMED_PROTOCOL)
        }
    }
    verify("runtime targets cover Linux, macOS, and Windows on x64 and ARM64") {
        fun target(os: String, arch: String, archiveExecutable: String, installedExecutable: String) {
            val target = CodexRuntimeInstaller.targetFor(os, arch)
            check(target.assetName == "$archiveExecutable.tar.gz")
            check(target.archiveExecutableName == archiveExecutable)
            check(target.installedExecutableName == installedExecutable)
            check(target.requiresExecutablePermission == !installedExecutable.endsWith(".exe"))
        }
        target("Linux", "amd64", "codex-x86_64-unknown-linux-musl", "codex")
        target("Linux", "aarch64", "codex-aarch64-unknown-linux-musl", "codex")
        target("Mac OS X", "x86_64", "codex-x86_64-apple-darwin", "codex")
        target("Darwin", "arm64", "codex-aarch64-apple-darwin", "codex")
        target("Windows 11", "x64", "codex-x86_64-pc-windows-msvc.exe", "codex.exe")
        target("Windows 11", "aarch64", "codex-aarch64-pc-windows-msvc.exe", "codex.exe")
        check(runCatching { CodexRuntimeInstaller.targetFor("FreeBSD", "amd64") }.isFailure)
        check(runCatching { CodexRuntimeInstaller.targetFor("Linux", "riscv64") }.isFailure)
    }
    verify("runtime installer resolves latest, verifies it, and reuses the cached version") {
        val runtimeTarget = CodexRuntimeInstaller.targetFor(
            System.getProperty("os.name"), System.getProperty("os.arch"),
        )
        val archive = codexArchive(runtimeTarget.archiveExecutableName, "#!/bin/sh\necho codex-cli 9.8.7\n".toByteArray())
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val downloads = AtomicInteger()
        val releaseRequests = AtomicInteger()
        val versionListRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val root = Files.createTempDirectory("codex-installer-test-")
        try {
            val base = "http://127.0.0.1:${server.address.port}/"
            server.createContext("/releases/latest") { exchange ->
                if (releaseRequests.incrementAndGet() > 3) {
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                    return@createContext
                }
                val json = """{"tag_name":"rust-v9.8.7","assets":[{"name":"${runtimeTarget.assetName}","browser_download_url":"${base}download","digest":"sha256:$digest"}]}"""
                exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(json.toByteArray()) }
            }
            server.createContext("/git/matching-refs/tags/rust-v") { exchange ->
                versionListRequests.incrementAndGet()
                check(exchange.requestURI.rawPath == "/git/matching-refs/tags/rust-v")
                check(exchange.requestURI.query == null)
                val json = """[{"ref":"refs/tags/rust-v9.7.0"},{"ref":"refs/tags/rust-v9.8.7"},{"ref":"refs/tags/rust-v9.9.0-alpha.1"},{"ref":"refs/tags/not-codex"},{"ref":"refs/tags/rust-vbroken"},{"ref":"refs/tags/rust-v9.7.0"}]"""
                exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(json.toByteArray()) }
            }
            server.createContext("/download") { exchange ->
                downloads.incrementAndGet()
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }
            }
            server.start()
            val installer = CodexRuntimeInstaller(
                root, java.net.URI("${base}releases/"), "127.0.0.1", target = runtimeTarget,
            )
            check(installer.availableVersions() == listOf("9.8.7", "9.7.0"))
            check(versionListRequests.get() == 1)
            val first = installer.install("latest")
            check(first.version == "9.8.7")
            check(first.isLatest)
            check(!Files.exists(root.resolve(".latest")))
            check(installer.install("latest").version == "9.8.7")
            check(!Files.exists(root.resolve(".latest")))
            installer.rememberLatestVersion("9.8.7")
            check(Files.readString(root.resolve(".latest")) == "9.8.7")
            check(Files.readString(first.executable).contains("codex-cli 9.8.7"))
            check(installer.latestVersion() == "9.8.7")
            check(!installer.install("9.8.7").isLatest)
            val fallback = installer.install("latest")
            check(fallback.executable == first.executable)
            check(!fallback.isLatest)
            check(releaseRequests.get() == 4)
            check(downloads.get() == 1)
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }
    verify("logout removes account and model availability") {
        fixture { _, session ->
            session.logout()
            check(session.account() == null)
            check(session.models().isEmpty())
        }
    }
    verify("EOF fails waiting calls and closes the process") {
        fixture { rpc, _ ->
            val failure = runCatching { rpc.request("test/exit") }.exceptionOrNull()
            check(failure is CodexFailure && failure.kind == CodexFailureKind.PROCESS)
        }
    }
    println("$passed protocol checks passed. No model inference or browser login was performed.")
}

private fun codexArchive(executableName: String, executable: ByteArray): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    GZIPOutputStream(output).use { gzip ->
        val header = ByteArray(512)
        executableName.toByteArray().copyInto(header)
        "0000755\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)
        executable.size.toString(8).padStart(11, '0').plus('\u0000').toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        repeat(8) { header[148 + it] = ' '.code.toByte() }
        header[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray().copyInto(header, 257)
        val checksum = header.sumOf { it.toUByte().toInt() }
        checksum.toString(8).padStart(6, '0').plus("\u0000 ").toByteArray().copyInto(header, 148)
        gzip.write(header)
        gzip.write(executable)
        gzip.write(ByteArray((512 - executable.size % 512) % 512))
        gzip.write(ByteArray(1024))
    }
    return output.toByteArray()
}

internal object FakeServer {
    @JvmStatic fun main(args: Array<String>) {
        var loggedIn = true
        val threads = AtomicInteger()
        val instructions = mutableMapOf<String, String?>()
        var lastThreadCwd: String? = null
        var interrupts = 0
        fun send(value: Any) { synchronized(System.out) { println(gson.toJson(value)) } }
        System.`in`.bufferedReader().forEachLine { line ->
            val request = JsonParser.parseString(line).asJsonObject
            val method = request.text("method")
            val id = request.get("id")
            val params = request.getAsJsonObject("params") ?: obj()
            var result = obj()
            when (method) {
                "initialized" -> return@forEachLine
                "initialize" -> check(params.getAsJsonObject("clientInfo").text("name") == "codex_subscription_jetbrains")
                "account/read" -> result = obj("account" to if (loggedIn) obj("type" to "chatgpt", "email" to "test@example.invalid", "planType" to "pro") else null)
                "account/rateLimits/read" -> result = obj(
                    "ordinaryUsageAllowed" to loggedIn,
                    "rateLimits" to obj(
                        "limitId" to "codex",
                        "primary" to obj("usedPercent" to if (loggedIn) 17 else 100, "resetsAt" to 1_800_000_000L),
                    ),
                )
                "account/login/start" -> {
                    check(params.text("type") == "chatgpt")
                    result = obj("type" to "chatgpt", "authUrl" to "https://auth.openai.com/test-login", "loginId" to "test-login")
                }
                "account/logout" -> loggedIn = false
                "model/list" -> result = if (params.text("cursor") == null) obj(
                    "data" to listOf(obj("model" to "test-model", "displayName" to "Test model", "hidden" to false)), "nextCursor" to "page2",
                ) else obj("data" to listOf(obj("model" to "helper-model", "displayName" to "Helper model", "hidden" to false)), "nextCursor" to null)
                "thread/start" -> {
                    check(params.text("sandbox") == "read-only")
                    check(params.text("approvalPolicy") == "never")
                    check(params.get("ephemeral").asBoolean)
                    check(params.text("modelProvider") == "openai")
                    val threadId = "thread-${threads.incrementAndGet()}"
                    lastThreadCwd = params.text("cwd")
                    instructions[threadId] = params.text("developerInstructions")
                    result = obj("thread" to obj("id" to threadId))
                }
                "turn/start" -> {
                    val threadId = params.text("threadId")!!
                    val prompt = params.getAsJsonArray("input")[0].asJsonObject.text("text")!!
                    if (prompt.contains("rename")) check(instructions[threadId] == "Return code only")
                    if (prompt.contains("slow-start")) Thread.sleep(1_000)
                    result = obj("turn" to obj("id" to "turn-$threadId"))
                    if (!prompt.contains("wait")) {
                        thread(isDaemon = true) {
                            Thread.sleep(if (prompt.contains("first")) 80 else 10)
                            fun event(method: String, fields: com.google.gson.JsonObject) {
                                fields.addProperty("threadId", threadId)
                                send(obj("method" to method, "params" to fields))
                            }
                            event("item/completed", obj("item" to obj("type" to "agentMessage", "phase" to "commentary", "text" to "Working...")))
                            if (!prompt.contains("empty")) {
                                val answer = when { prompt.contains("first") -> "first-result"; prompt.contains("second") -> "second-result"; else -> "regionCount" }
                                event("item/completed", obj("item" to obj("type" to "agentMessage", "phase" to "final_answer", "text" to answer)))
                            }
                            event("turn/completed", obj("turn" to obj("id" to "turn-$threadId", "status" to if (prompt.contains("fail")) "failed" else "completed")))
                        }
                    }
                }
                "turn/interrupt" -> interrupts++
                "thread/unsubscribe" -> Unit
                "test/interruptCount" -> result = obj("count" to interrupts)
                "test/threadCwd" -> result = obj("cwd" to lastThreadCwd)
                "test/error" -> { send(obj("id" to id, "error" to obj("message" to "private-source"))); return@forEachLine }
                "test/malformed-result" -> { send(obj("id" to id, "result" to "private-source")); return@forEachLine }
                "test/malformed" -> { println("private-source-invalid-json"); return@forEachLine }
                "test/exit" -> kotlin.system.exitProcess(0)
                else -> error("Unexpected method: $method")
            }
            send(obj("id" to id, "result" to result))
        }
    }
}
