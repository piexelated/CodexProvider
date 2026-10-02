package dev.piexel.codex.runtime

import dev.piexel.codex.CodexTrace
import dev.piexel.codex.install.InstalledCodex
import dev.piexel.codex.install.CodexRuntimeInstaller
import dev.piexel.codex.jetbrains.CodexSettings
import dev.piexel.codex.jetbrains.CodexQuotaPublisher
import dev.piexel.codex.protocol.CodexModel
import dev.piexel.codex.protocol.CodexQuota
import dev.piexel.codex.protocol.CodexSession
import dev.piexel.codex.protocol.Message
import dev.piexel.codex.protocol.text

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

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
internal class CodexRuntime(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val accountRefresh = Mutex()
    private val catalog = CodexModelCatalog()
    val modelChanges = catalog.changes
    private val installer = CodexRuntimeInstaller(Path.of(PathManager.getSystemPath(), "codex-subscription"))
    private val generation = CodexGenerationGate()
    private val sessionStartup = Mutex()
    @Volatile private var installedRuntime: InstalledCodex? = null
    @Volatile private var pendingSelection: String? = null
    private val mutableStatus = MutableStateFlow(codexAccountStatus(null))
    val status: StateFlow<CodexAccountStatus> = mutableStatus
    private val mutableRuntimeStatus = MutableStateFlow(CodexRuntimeStatus("Not installed", CodexRuntimeAction.INSTALL))
    val runtimeStatus: StateFlow<CodexRuntimeStatus> = mutableRuntimeStatus

    init { CodexTrace.info("runtime-service-created", "version" to "1.0") }

    private val sessions = CodexSessionManager(
        ioDispatcher = ioDispatcher,
        onAccountChange = { scope.launch { runCatching { refreshAccount() } } },
        onQuotaChange = { connection ->
            scope.launch {
                runCatching { refreshQuota(connection) }
                    .onFailure { CodexTrace.warn("account-quota-refresh-failed", it) }
            }
        },
    )

    private suspend fun session(): CodexSession = sessionStartup.withLock {
        var installed: InstalledCodex? = null
        try {
            val selection = CodexSettings.instance.runtimeVersion
            val connection = sessions.getOrStart {
                mutableRuntimeStatus.value = CodexRuntimeStatus(if (installedRuntime == null) "Installing Codex…" else "Starting Codex…")
                withContext(ioDispatcher) { installer.install(installedRuntime?.version ?: selection, rememberLatest = false) }.also { installed = it }.executable
            }
            installed?.let { publishRuntime(it, selection) }
            connection
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableRuntimeStatus.value = CodexRuntimeStatus(error.message ?: "Could not start Codex", CodexRuntimeAction.RETRY)
            throw error
        }
    }

    private fun publishRuntime(runtime: InstalledCodex, selection: String) {
        installedRuntime = runtime
        if (selection == CodexRuntimeInstaller.LATEST) {
            runCatching { installer.rememberLatestVersion(runtime.version) }
                .onFailure { CodexTrace.warn("runtime-fallback-save-failed", it) }
        }
        mutableRuntimeStatus.value = CodexRuntimeStatus(
            when {
                selection != CodexRuntimeInstaller.LATEST -> "${runtime.version} · Pinned"
                runtime.isLatest -> "${runtime.version} · Up to date"
                else -> "${runtime.version} installed"
            },
        )
    }

    suspend fun refreshAccount(): Boolean = accountRefresh.withLock {
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

    suspend fun login(): String {
        val url = session().login()
        mutableStatus.value = CodexAccountStatus("Complete sign-in in your browser", CodexAccountAction.SIGN_IN)
        return url
    }

    suspend fun logout() {
        session().logout()
        refreshAccount()
    }

    suspend fun runtimeVersions(): List<String> = withContext(ioDispatcher) {
        installer.availableVersions()
    }

    suspend fun checkForRuntimeUpdate() = accountRefresh.withLock {
        withContext(ioDispatcher) {
            val current = installedRuntime?.version ?: return@withContext
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
    }

    suspend fun retryRuntime() = installRuntime(pendingSelection ?: CodexSettings.instance.runtimeVersion)

    suspend fun installRuntime(selection: String) = accountRefresh.withLock {
        val normalized = CodexRuntimeInstaller.normalizeSelection(selection)
        val previousStatus = mutableRuntimeStatus.value
        pendingSelection = normalized
        mutableRuntimeStatus.value = CodexRuntimeStatus(
            if (installedRuntime == null) "Installing Codex…" else "Preparing Codex…",
        )
        try {
            val runtime = withContext(ioDispatcher) { installer.install(normalized, rememberLatest = false) }
            generation.pause {
                val snapshot = sessions.replace(runtime.executable, ::validateSession)
                commitRuntime(runtime, normalized, snapshot)
            }
        } catch (error: CancellationException) {
            mutableRuntimeStatus.value = previousStatus
            pendingSelection = null
            throw error
        } catch (error: Exception) {
            val current = installedRuntime?.version
            mutableRuntimeStatus.value = CodexRuntimeStatus(
                (error.message ?: "Could not switch Codex") + (current?.let { " · $it remains selected" } ?: ""),
                CodexRuntimeAction.RETRY,
            )
            CodexTrace.warn("runtime-switch-failed", error)
            throw error
        }
    }

    private data class AccountSnapshot(
        val account: JsonObject?,
        val models: List<CodexModel>,
        val quota: CodexQuota?,
    )

    private suspend fun validateSession(candidate: CodexSession): AccountSnapshot {
        val account = candidate.account()
        val models = modelsFor(account, candidate)
        val quota = if (account == null) CodexQuota(false, 100, null) else try {
            candidate.quota()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CodexTrace.warn("account-quota-refresh-failed", error)
            null
        }
        return AccountSnapshot(account, models, quota)
    }

    private fun commitRuntime(runtime: InstalledCodex, selection: String, snapshot: AccountSnapshot) {
        // No suspension between publishing the session and committing its related state.
        val settings = CodexSettings.instance
        settings.runtimeVersion = selection
        publishRuntime(runtime, selection)
        mutableStatus.value = codexAccountStatus(snapshot.account)
        publishCatalog(snapshot.models)
        settings.changed()
        pendingSelection = null
        snapshot.quota?.let {
            runCatching { publishQuota(it) }.onFailure { error -> CodexTrace.warn("account-quota-refresh-failed", error) }
        }
    }

    fun models(): List<CodexModel> = catalog.getOrRefresh(scope) {
        try { refreshAccount() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* refreshAccount exposes the failure in the provider panel. */ }
    }

    /** ACP chat must resolve the selected runtime before starting its adapter. */
    suspend fun executable(): Path = accountRefresh.withLock {
        session()
        checkNotNull(installedRuntime).executable
    }

    suspend fun generate(model: String, messages: List<Message>, projectDir: Path? = null): String =
        generation.generate { session().generate(model, messages, projectDir = projectDir) }

    private suspend fun refreshQuota(connection: CodexSession) {
        val quota = connection.quota() ?: run {
            CodexTrace.info("account-quota-unavailable")
            return
        }
        if (sessions.isCurrent(connection)) publishQuota(quota)
    }

    private fun publishQuota(quota: CodexQuota) = CodexQuotaPublisher.publish(quota)

    override fun dispose() {
        CodexTrace.info("runtime-service-disposed")
        scope.cancel()
        sessions.close()
    }

    companion object { val instance: CodexRuntime get() = service() }
}
