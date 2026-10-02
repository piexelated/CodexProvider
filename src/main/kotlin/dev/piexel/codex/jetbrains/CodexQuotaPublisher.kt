package dev.piexel.codex.jetbrains

import com.intellij.ml.llm.core.quota.QuotaManager2
import dev.piexel.codex.CodexTrace
import dev.piexel.codex.protocol.CodexQuota
import java.math.BigDecimal
import java.time.Instant

/** Translates Codex quota into the AI Assistant quota interface. */
internal object CodexQuotaPublisher {
    fun publish(quota: CodexQuota) {
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

    /** The quota timestamp type differs across supported AI Assistant branches. */
    private fun quotaInstant(type: Class<*>, epochSeconds: Long): Any {
        val companion = type.getField("Companion").get(null)
        val factory = companion.javaClass.getMethod("fromEpochSeconds", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        return factory.invoke(companion, epochSeconds, 0)
    }
}
