package dev.piexel.codex

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ml.llm.core.quota.QuotaManager2
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val CODEX_PROVIDER_DISPOSED = "The Codex provider has been disposed."
private val RUNTIME_ENVIRONMENT_KEYS = setOf(
    "HOME", "USER", "LOGNAME", "PATH", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP",
    "SSL_CERT_FILE", "SSL_CERT_DIR", "SYSTEMROOT", "WINDIR", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
    "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PATHEXT", "COMSPEC",
)

internal enum class CodexRuntimeAction(val label: String) {
    INSTALL("Install Codex"),
    UPDATE("Update"),
    RETRY("Try again"),
}

internal data class CodexRuntimeStatus(
    val text: String,
    val action: CodexRuntimeAction? = null,
)

internal enum class CodexAccountAction(val label: String) {
    SIGN_IN("Sign in with ChatGPT"),
    SIGN_OUT("Sign out"),
}

internal data class CodexAccountStatus(
    val text: String,
    val action: CodexAccountAction,
)

internal fun codexAccountStatus(account: JsonObject?): CodexAccountStatus = if (account == null) {
    CodexAccountStatus("Not signed in", CodexAccountAction.SIGN_IN)
} else {
    val email = account.text("email")?.takeIf(String::isNotBlank) ?: "ChatGPT account"
    val plan = account.text("planType")?.takeIf(String::isNotBlank)?.let(::codexPlanLabel) ?: "Subscription"
    CodexAccountStatus("$email · $plan", CodexAccountAction.SIGN_OUT)
}

private fun codexPlanLabel(value: String): String = when (value.lowercase()) {
    "prolite", "pro-lite", "pro_lite" -> "Pro Lite"
    else -> value.replace('-', ' ').replace('_', ' ').split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
}

@Service(Service.Level.APP)
internal class CodexRuntime : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val accountRefresh = Mutex()
    private val catalog = CodexModelCatalog()
    val modelChanges = catalog.changes
    private val installer = CodexRuntimeInstaller(Path.of(PathManager.getSystemPath(), "codex-subscription"))
    private val slots = Semaphore(2)
    private val lifecycle = Any()
    @Volatile private var disposed = false
    @Volatile private var installedRuntimeVersion: String? = null
    @Volatile private var session: CodexSession? = null
    private var workingDirectory: Path? = null
    private val mutableStatus = MutableStateFlow(codexAccountStatus(null))
    val status: StateFlow<CodexAccountStatus> = mutableStatus
    private val mutableRuntimeStatus = MutableStateFlow(CodexRuntimeStatus("Not installed", CodexRuntimeAction.INSTALL))
    val runtimeStatus: StateFlow<CodexRuntimeStatus> = mutableRuntimeStatus

    init { CodexTrace.info("runtime-service-created", "version" to "1.0") }

    private suspend fun session(): CodexSession = mutex.withLock {
        check(!disposed) { CODEX_PROVIDER_DISPOSED }
        session?.takeIf { it.isAlive }?.let {
            CodexTrace.info("runtime-session-reused")
            return@withLock it
        }
        CodexTrace.info("runtime-session-starting")
        session?.close()
        val selection = CodexSettings.instance.runtimeVersion
        mutableRuntimeStatus.value = CodexRuntimeStatus(if (installedRuntimeVersion == null) "Installing Codex…" else "Starting Codex…")
        val runtime = try {
            installer.install(selection)
        } catch (error: Exception) {
            mutableRuntimeStatus.value = CodexRuntimeStatus(error.message ?: "Could not install Codex", CodexRuntimeAction.RETRY)
            throw error
        }
        installedRuntimeVersion = runtime.version
        mutableRuntimeStatus.value = CodexRuntimeStatus(
            when {
                selection != CodexRuntimeInstaller.LATEST -> "${runtime.version} · Pinned"
                runtime.isLatest -> "${runtime.version} · Up to date"
                else -> "${runtime.version} installed"
            },
        )
        val home = Path.of(PathManager.getConfigPath(), "codex-subscription")
        Files.createDirectories(home)
        val work = synchronized(lifecycle) {
            check(!disposed) { CODEX_PROVIDER_DISPOSED }
            workingDirectory ?: Files.createTempDirectory("codex-ide-").also { workingDirectory = it }
        }
        val command = mutableListOf(runtime.executable.toString())
        listOf(
            "forced_login_method=\"chatgpt\"", "model_provider=\"openai\"",
            "project_doc_max_bytes=0", "history.persistence=\"none\"",
            "web_search=\"disabled\"", "features.shell_tool=false",
            "features.unified_exec=false", "features.shell_snapshot=false",
            "features.multi_agent=false", "features.apps=false", "features.hooks=false",
            "features.browser_use=false", "features.computer_use=false", "features.image_generation=false",
        ).forEach { command.addAll(listOf("-c", it)) }
        command.addAll(listOf("app-server", "--listen", "stdio://"))
        val builder = ProcessBuilder(command).directory(work.toFile())
        val original = builder.environment().toMap()
        builder.environment().clear()
        original.forEach { (key, value) ->
            if (key.uppercase() in RUNTIME_ENVIRONMENT_KEYS) builder.environment()[key] = value
        }
        if (original.keys.none { it.equals("PATH", ignoreCase = true) }) builder.environment()["PATH"] = ""
        builder.environment()["CODEX_HOME"] = home.toString()
        val connection = CodexSession(JsonRpc(builder.start()), work)
        try {
            connection.initialize()
            connection.onAccountChange { scope.launch { runCatching { refreshAccount() } } }
            connection.onQuotaChange {
                scope.launch {
                    runCatching { refreshQuota(connection) }
                        .onFailure { CodexTrace.warn("account-quota-refresh-failed", it) }
                }
            }
            synchronized(lifecycle) {
                check(!disposed) { CODEX_PROVIDER_DISPOSED }
                session = connection
            }
            CodexTrace.info("runtime-session-ready")
            connection
        } catch (error: Throwable) {
            CodexTrace.warn("runtime-session-failed", error)
            connection.close()
            throw error
        }
    }

    suspend fun refreshAccount(): Boolean = withContext(Dispatchers.IO) {
        accountRefresh.withLock {
            val started = System.nanoTime()
            CodexTrace.info("account-model-refresh-started")
            try {
                val connection = session()
                val account = connection.account()
                mutableStatus.value = codexAccountStatus(account)
                val models = modelsFor(account, connection)
                refreshQuotaFor(account, connection)
                val catalogChanged = publishCatalog(models)
                CodexTrace.info(
                    "account-model-refresh-completed", "signedIn" to (account != null), "models" to models.size,
                    "catalogChanged" to catalogChanged, "durationMs" to (System.nanoTime() - started) / 1_000_000,
                )
                account != null
            } catch (error: CancellationException) {
                CodexTrace.warn("account-model-refresh-cancelled", error)
                throw error
            } catch (error: Exception) {
                CodexTrace.warn("account-model-refresh-failed", error)
                throw error
            }
        }
    }

    private suspend fun modelsFor(account: JsonObject?, connection: CodexSession): List<CodexModel> =
        if (account == null) emptyList() else connection.models()

    private suspend fun refreshQuotaFor(account: JsonObject?, connection: CodexSession) {
        if (account == null) publishQuota(CodexQuota(false, 100, null))
        else runCatching { refreshQuota(connection) }
            .onFailure { CodexTrace.warn("account-quota-refresh-failed", it) }
    }

    private fun publishCatalog(models: List<CodexModel>): Boolean {
        val catalogChanged = catalog.replace(models)
        if (catalogChanged) CodexSettings.instance.changed()
        return catalogChanged
    }

    suspend fun login(): String = withContext(Dispatchers.IO) {
        val url = session().login()
        mutableStatus.value = CodexAccountStatus("Complete sign-in in your browser", CodexAccountAction.SIGN_IN)
        url
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        session().logout()
        refreshAccount()
        Unit
    }

    suspend fun runtimeVersions(): List<String> = withContext(Dispatchers.IO) {
        installer.availableVersions()
    }

    suspend fun checkForRuntimeUpdate() = withContext(Dispatchers.IO) {
        val current = installedRuntimeVersion ?: return@withContext
        if (CodexSettings.instance.runtimeVersion != CodexRuntimeInstaller.LATEST) {
            mutableRuntimeStatus.value = CodexRuntimeStatus("$current · Pinned")
            return@withContext
        }
        val latest = try {
            installer.latestVersion()
        } catch (error: Exception) {
            CodexTrace.warn("runtime-update-check-failed", error)
            mutableRuntimeStatus.value = CodexRuntimeStatus("$current installed")
            return@withContext
        }
        mutableRuntimeStatus.value = if (current == latest) {
            CodexRuntimeStatus("$current · Up to date")
        } else {
            CodexRuntimeStatus("$current installed · $latest available", CodexRuntimeAction.UPDATE)
        }
    }

    suspend fun installRuntime(selection: String) = withContext(Dispatchers.IO) {
        val normalized = CodexRuntimeInstaller.normalizeSelection(selection)
        mutableRuntimeStatus.value = CodexRuntimeStatus(
            if (installedRuntimeVersion == null) "Installing Codex…" else "Switching Codex…",
        )
        val settings = CodexSettings.instance
        if (settings.runtimeVersion != normalized) {
            settings.runtimeVersion = normalized
            settings.changed()
        }
        mutex.withLock {
            session?.close()
            session = null
        }
        refreshAccount()
    }

    fun models(): List<CodexModel> = catalog.getOrRefresh(scope) {
        try { refreshAccount() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* refreshAccount exposes the failure in the provider panel. */ }
    }

    suspend fun generate(model: String, messages: List<Message>): String = withContext(Dispatchers.IO) {
        slots.withPermit { session().generate(model, messages) }
    }

    private suspend fun refreshQuota(connection: CodexSession) {
        val quota = connection.quota() ?: run {
            CodexTrace.info("account-quota-unavailable")
            return
        }
        if (synchronized(lifecycle) { session === connection }) publishQuota(quota)
    }

    private fun publishQuota(quota: CodexQuota) {
        val usedPercent = if (quota.usageAllowed) quota.usedPercent.coerceIn(0, 99) else 100
        val quotaUpdate = QuotaManager2::class.java.methods.first { method ->
            method.name == "updateFromExternal" && method.parameterTypes.size == 4 &&
                method.parameterTypes[0] == BigDecimal::class.java
        }
        val resetEpochSeconds = quota.resetsAtEpochSeconds
            ?.takeIf { it > Instant.now().epochSecond }
        val reset = resetEpochSeconds?.let { quotaInstant(quotaUpdate.parameterTypes[2], it) }
        quotaUpdate.invoke(
            QuotaManager2.getInstance(),
            BigDecimal.valueOf(usedPercent.toLong()),
            BigDecimal.valueOf(100),
            reset,
            null,
        )
        CodexTrace.info(
            "account-quota-synced",
            "usageAllowed" to quota.usageAllowed,
            "usedPercent" to usedPercent,
            "hasResetTime" to (resetEpochSeconds != null),
        )
    }

    /**
     * QuotaManager2 changed its timestamp parameter from kotlinx.datetime to
     * kotlin.time between the 253 and 262 AI Assistant branches. Construct the
     * branch-owned type without making the provider source branch-specific.
     */
    private fun quotaInstant(type: Class<*>, epochSeconds: Long): Any {
        val companion = type.getField("Companion").get(null)
        val factory = companion.javaClass.getMethod("fromEpochSeconds", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        return factory.invoke(companion, epochSeconds, 0)
    }

    override fun dispose() {
        CodexTrace.info("runtime-service-disposed")
        synchronized(lifecycle) {
            disposed = true
            scope.cancel()
            session?.close()
            workingDirectory?.toFile()?.deleteRecursively()
        }
    }

    companion object { val instance: CodexRuntime get() = service() }
}
