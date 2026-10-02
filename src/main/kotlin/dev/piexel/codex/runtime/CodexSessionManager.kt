package dev.piexel.codex.runtime

import com.intellij.openapi.application.PathManager
import dev.piexel.codex.CodexTrace
import dev.piexel.codex.protocol.CodexSession
import dev.piexel.codex.protocol.JsonRpc
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/** Owns the local app-server process, its session, and the temporary fallback working directory. */
internal class CodexSessionManager(
    private val ioDispatcher: CoroutineDispatcher,
    private val onAccountChange: () -> Unit,
    private val onQuotaChange: (CodexSession) -> Unit,
    private val launchProcess: (suspend (Path) -> Pair<Process, Path>)? = null,
) : Closeable {
    private val mutex = Mutex()
    private val lifecycle = Any()
    @Volatile private var disposed = false
    @Volatile private var session: CodexSession? = null
    private var workingDirectory: Path? = null

    suspend fun getOrStart(executable: suspend () -> Path): CodexSession = mutex.withLock {
        check(!disposed) { "The Codex provider has been disposed." }
        session?.takeIf { it.isAlive }?.let {
            CodexTrace.info("runtime-session-reused")
            return@withLock it
        }
        CodexTrace.info("runtime-session-starting")
        session?.close()
        session = null
        val connection = startConnection(executable())
        try {
            currentCoroutineContext().ensureActive()
            publish(connection)
            connection
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    /** The caller drains active generation before swapping. Validation never closes the working session. */
    suspend fun <T> replace(executable: Path, validate: suspend (CodexSession) -> T): T = mutex.withLock {
        check(!disposed) { "The Codex provider has been disposed." }
        val candidate = startConnection(executable)
        try {
            val result = validate(candidate)
            currentCoroutineContext().ensureActive()
            publish(candidate)
            result
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    private suspend fun startConnection(executable: Path): CodexSession {
        val (process, work) = launchProcess?.invoke(executable) ?: startProcess(executable)
        val connection = CodexSession(JsonRpc(process), work)
        try {
            connection.initialize()
            connection.onAccountChange(onAccountChange)
            connection.onQuotaChange { onQuotaChange(connection) }
            return connection
        } catch (error: Throwable) {
            CodexTrace.warn("runtime-session-failed", error)
            connection.close()
            throw error
        }
    }

    private fun publish(connection: CodexSession) {
        synchronized(lifecycle) {
            if (disposed) {
                connection.close()
                error("The Codex provider has been disposed.")
            }
            val previous = session
            session = connection
            previous?.close()
        }
        CodexTrace.info("runtime-session-ready")
    }

    private suspend fun startProcess(executable: Path): Pair<Process, Path> {
        val home = Path.of(PathManager.getConfigPath(), "codex-subscription")
        Files.createDirectories(home)
        val work = synchronized(lifecycle) {
            check(!disposed) { "The Codex provider has been disposed." }
            workingDirectory ?: Files.createTempDirectory("codex-ide-").also { workingDirectory = it }
        }
        val builder = ProcessBuilder(codexAppServerCommand(executable)).directory(work.toFile())
        val original = builder.environment().toMap()
        builder.environment().clear()
        original.forEach { (key, value) ->
            if (key.uppercase() in RUNTIME_ENVIRONMENT_KEYS) builder.environment()[key] = value
        }
        if (original.keys.none { it.equals("PATH", ignoreCase = true) }) builder.environment()["PATH"] = ""
        builder.environment()["CODEX_HOME"] = home.toString()
        var started: Process? = null
        try {
            return withContext(ioDispatcher) { builder.start().also { started = it } } to work
        } catch (error: Throwable) {
            started?.destroyForcibly()
            throw error
        }
    }

    fun isCurrent(connection: CodexSession): Boolean = synchronized(lifecycle) { session === connection }

    override fun close() {
        synchronized(lifecycle) {
            disposed = true
            session?.close()
            workingDirectory?.toFile()?.deleteRecursively()
        }
    }

    private companion object {
        val RUNTIME_ENVIRONMENT_KEYS = setOf(
            "HOME", "USER", "LOGNAME", "PATH", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "SYSTEMROOT", "WINDIR", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PATHEXT", "COMSPEC",
        )
    }
}

/** Shared by the IDE launcher and the released-CLI compatibility probe. */
internal fun codexAppServerCommand(executable: Path): List<String> = buildList {
    add(executable.toString())
    listOf(
        "forced_login_method=\"chatgpt\"", "model_provider=\"openai\"",
        "project_doc_max_bytes=32768", "history.persistence=\"none\"",
        "web_search=\"disabled\"", "features.shell_tool=false",
        "features.unified_exec=false", "features.shell_snapshot=false",
        "features.multi_agent=false", "features.apps=false", "features.hooks=false",
        "features.browser_use=false", "features.computer_use=false", "features.image_generation=false",
    ).forEach { addAll(listOf("-c", it)) }
    addAll(listOf("app-server", "--listen", "stdio://"))
}
