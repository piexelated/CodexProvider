package dev.piexel.codex

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicLong

/** Operational traces. Response bodies are included only when debug mode is enabled. */
internal object CodexTrace {
    private val enabled = !java.lang.Boolean.getBoolean("codex.trace.disabled")
    private val log by lazy { Logger.getInstance("dev.piexel.codex.subscription") }
    private val sequence = AtomicLong()
    private const val NANOS_PER_MILLI = 1_000_000L

    fun nextRequestId(): Long = sequence.incrementAndGet()

    fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    fun info(event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        log.info(format(event, fields.asList()))
    }

    fun warn(event: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        log.warn(format(event, fields.asList() + ("errorType" to error.javaClass.simpleName)))
    }

    internal fun format(event: String, fields: List<Pair<String, Any?>>): String = buildString {
        append("[Codex Provider] ").append(event)
        fields.forEach { (key, value) ->
            append(' ').append(key).append('=').append(value)
        }
    }

    /** Keep response bodies on one idea.log line while preserving their exact text. */
    internal fun escapeLogValue(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (Character.isISOControl(character)) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
