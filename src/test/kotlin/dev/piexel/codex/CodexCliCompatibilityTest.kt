package dev.piexel.codex

import dev.piexel.codex.install.CodexRuntimeInstaller
import dev.piexel.codex.protocol.CodexSession
import dev.piexel.codex.protocol.JsonRpc
import dev.piexel.codex.protocol.Message
import dev.piexel.codex.protocol.obj
import dev.piexel.codex.runtime.codexAppServerCommand
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Exercises a real released Codex binary without account credentials or inference. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 1 && Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(args[0]))
    val version = args.single()
    val root = Files.createTempDirectory("codex-cli-compat-")
    try {
        val cache = Path.of(System.getenv("GRADLE_USER_HOME") ?: System.getProperty("user.home"), "codex-cli-runtimes")
        val installed = CodexRuntimeInstaller(cache).install(version)
        check(installed.version == version)
        val versionProcess = ProcessBuilder(installed.executable.toString(), "--version").start()
        check(versionProcess.waitFor(10, TimeUnit.SECONDS) && versionProcess.exitValue() == 0)
        check(versionProcess.inputStream.bufferedReader().readText().trim() == "codex-cli $version")
        println("PASS Codex CLI $version binary and installer")

        val builder = ProcessBuilder(codexAppServerCommand(installed.executable)).directory(root.toFile())
        builder.environment().clear()
        builder.environment()["HOME"] = root.toString()
        builder.environment()["CODEX_HOME"] = root.toString()
        builder.environment()["PATH"] = System.getenv("PATH").orEmpty()
        builder.environment()["LANG"] = "C.UTF-8"
        val rpc = JsonRpc(builder.start())
        val session = CodexSession(rpc, root)
        try {
            withTimeout(30_000) {
                session.initialize()
                check(session.account() == null)
                val models = rpc.request("model/list", obj("limit" to 20, "includeHidden" to false))
                check(models.get("data")?.isJsonArray == true)
                val threadId = session.startThread("compatibility-probe", listOf(Message("user", "probe")))
                check(threadId.isNotBlank())
            }
            println("PASS Codex CLI $version app-server initialize, account/read, model/list, and thread/start")
        } finally {
            session.close()
        }
    } finally {
        root.toFile().deleteRecursively()
    }
}
