package dev.piexel.codex

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal val gson = Gson()
internal fun obj(vararg fields: Pair<String, Any?>): JsonObject = gson.toJsonTree(mapOf(*fields)).asJsonObject
internal fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

/** Codex app-server's documented JSONL protocol. No HTTP or account tokens here. */
internal class JsonRpc(private val process: Process) : Closeable {
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val pending = ConcurrentHashMap<Long, (Result<JsonObject>) -> Unit>()
    private val requestMetadata = ConcurrentHashMap<Long, Pair<String, Long>>()
    private val listeners = CopyOnWriteArrayList<(String, JsonObject) -> Unit>()
    val isAlive: Boolean get() = !closed.get() && process.isAlive

    init {
        thread(name = "Codex provider events", isDaemon = true) {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = readBoundedLine(reader) ?: break
                        if (line.isBlank()) continue
                        dispatch(JsonParser.parseString(line).asJsonObject)
                    }
                }
            } catch (_: Exception) {
                // Do not log protocol frames: they contain user code and login URLs.
            } finally { close() }
        }
        thread(name = "Codex provider stderr", isDaemon = true) {
            try { process.errorStream.use { input -> input.copyTo(java.io.OutputStream.nullOutputStream()) } }
            catch (_: Exception) { }
        }
    }

    suspend fun request(method: String, params: JsonObject = obj(), timeoutMs: Long = 30_000): JsonObject =
        withTimeout(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val id = sequence.incrementAndGet()
                requestMetadata[id] = method to System.nanoTime()
                CodexTrace.info("rpc-request-sent", "rpcId" to id, "method" to method)
                pending[id] = { result ->
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
                continuation.invokeOnCancellation {
                    pending.remove(id)
                    requestMetadata.remove(id)
                    CodexTrace.info("rpc-request-cancelled", "rpcId" to id, "method" to method)
                }
                try { send(obj("id" to id, "method" to method, "params" to params)) }
                catch (_: Exception) {
                    pending.remove(id)?.invoke(Result.failure(IllegalStateException("Codex connection is closed.")))
                    requestMetadata.remove(id)
                }
            }
        }

    fun notify(method: String, params: JsonObject = obj()) = send(obj("method" to method, "params" to params))

    fun subscribe(listener: (String, JsonObject) -> Unit): Closeable {
        listeners.add(listener)
        return Closeable { listeners.remove(listener) }
    }

    @Synchronized
    private fun send(message: JsonObject) {
        check(isAlive) { "Codex connection is closed." }
        val line = gson.toJson(message)
        require(line.length <= MAX_FRAME) { "Codex request is too large." }
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    private fun dispatch(message: JsonObject) {
        if (message.has("id") && message.has("method")) {
            // This provider returns text only. It never grants requests to run
            // commands, change files, or use external tools on the client's behalf.
            send(obj("id" to message.get("id"), "error" to obj("code" to -32601, "message" to "Client tools are not available in this text provider.")))
        } else if (message.has("id")) {
            val id = message.get("id").asLong
            val receiver = pending.remove(id) ?: return
            val metadata = requestMetadata.remove(id)
            CodexTrace.info(
                "rpc-request-finished", "rpcId" to id, "method" to metadata?.first,
                "success" to !message.has("error"), "durationMs" to metadata?.let { (System.nanoTime() - it.second) / 1_000_000 },
            )
            if (message.has("error")) {
                receiver(Result.failure(IllegalStateException("Codex rejected the request. Check sign-in, model access, and subscription limits.")))
            } else receiver(Result.success(message.getAsJsonObject("result") ?: obj()))
        } else {
            val method = message.text("method") ?: return
            if (method in TRACE_NOTIFICATIONS) CodexTrace.info("rpc-notification", "method" to method)
            val params = message.getAsJsonObject("params") ?: obj()
            listeners.forEach { listener -> runCatching { listener(method, params) } }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        val error = IllegalStateException("Codex stopped. Retry to start a new connection.")
        pending.keys.toList().forEach { pending.remove(it)?.invoke(Result.failure(error)) }
        requestMetadata.clear()
        CodexTrace.info("rpc-process-closed")
        listeners.forEach { runCatching { it("connection/closed", obj()) } }
        listeners.clear()
    }

    companion object {
        private const val MAX_FRAME = 8 * 1024 * 1024
        private val TRACE_NOTIFICATIONS = setOf(
            "account/updated", "account/login/completed", "account/rateLimits/updated", "thread/started", "turn/started",
            "turn/completed", "connection/closed",
        )
        private fun readBoundedLine(reader: BufferedReader): String? {
            val line = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1) return if (line.isEmpty()) null else line.toString()
                if (c == '\n'.code) return line.toString()
                check(line.length < MAX_FRAME) { "Codex frame exceeded the size limit." }
                line.append(c.toChar())
            }
        }
    }
}
