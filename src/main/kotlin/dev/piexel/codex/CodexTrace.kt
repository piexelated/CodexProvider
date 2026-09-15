package dev.piexel.codex

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicLong

/** Privacy-safe operational traces. Never pass prompts, code, credentials, URLs, or account details. */
internal object CodexTrace {
    private val enabled = !java.lang.Boolean.getBoolean("codex.trace.disabled")
    private val log by lazy { Logger.getInstance("dev.piexel.codex.subscription") }
    private val sequence = AtomicLong()

    fun nextRequestId(): Long = sequence.incrementAndGet()

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
}
