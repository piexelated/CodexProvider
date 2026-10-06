package dev.piexel.codex.protocol

import dev.piexel.codex.CodexTrace

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.Closeable
import java.io.OutputStream
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
internal fun JsonObject.text(key: String): String? = valueOrNull(key)?.asString
internal fun JsonObject.intOrNull(key: String): Int? = valueOrNull(key)?.asInt
internal fun JsonObject.longOrNull(key: String): Long? = valueOrNull(key)?.asLong
internal fun JsonObject.booleanOrNull(key: String): Boolean? = valueOrNull(key)?.asBoolean
internal fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.valueOrNull(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

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
            } catch (error: Exception) {
                CodexTrace.warn("rpc-reader-failed", error)
                close(CodexFailure(CodexFailureKind.MALFORMED_PROTOCOL))
            } finally { close() }
        }
        thread(name = "Codex provider stderr", isDaemon = true) {
            try { process.errorStream.use { input -> input.copyTo(OutputStream.nullOutputStream()) } }
            catch (_: Exception) { CodexTrace.info("rpc-stderr-reader-terminated") }
        }
    }

    suspend fun request(method: String, params: JsonObject = obj(), timeoutMs: Long = REQUEST_TIMEOUT_MS): JsonObject =
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
                    pending.remove(id)?.invoke(Result.failure(CodexFailure(CodexFailureKind.PROCESS)))
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
            if (!pending.containsKey(id)) return
            // Decode before removing the waiter so a malformed response cannot strand it until timeout.
            val response = if (message.has("error")) {
                Result.failure(CodexFailure.fromError(message.getAsJsonObject("error")))
            } else {
                Result.success(message.getAsJsonObject("result") ?: obj())
            }
            val receiver = pending.remove(id) ?: return
            val metadata = requestMetadata.remove(id)
            CodexTrace.info(
                "rpc-request-finished", "rpcId" to id, "method" to metadata?.first,
                "success" to response.isSuccess, "durationMs" to metadata?.let { CodexTrace.elapsedMs(it.second) },
            )
            (response.exceptionOrNull() as? CodexFailure)?.let { failure ->
                CodexTrace.warn("rpc-request-rejected", failure, "kind" to failure.kind, "rpcCode" to failure.rpcCode,
                    "httpStatusCode" to failure.httpStatusCode)
            }
            receiver(response)
        } else {
            val method = message.text("method") ?: return
            if (method in TRACE_NOTIFICATIONS) CodexTrace.info("rpc-notification", "method" to method)
            val params = message.getAsJsonObject("params") ?: obj()
            listeners.forEach { listener -> runCatching { listener(method, params) } }
        }
    }

    override fun close() = close(CodexFailure(CodexFailureKind.PROCESS))

    private fun close(error: CodexFailure) {
        if (!closed.compareAndSet(false, true)) return
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        pending.keys.toList().forEach { pending.remove(it)?.invoke(Result.failure(error)) }
        requestMetadata.clear()
        CodexTrace.info("rpc-process-closed", "kind" to error.kind)
        listeners.forEach { runCatching { it("connection/closed", obj("kind" to error.kind.name)) } }
        listeners.clear()
    }

    companion object {
        private const val MAX_FRAME = 8 * 1024 * 1024
        private const val REQUEST_TIMEOUT_MS = 30_000L
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
