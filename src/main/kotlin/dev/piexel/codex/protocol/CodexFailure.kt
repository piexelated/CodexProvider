package dev.piexel.codex.protocol

import com.google.gson.JsonObject

internal enum class CodexFailureKind(val guidance: String) {
    AUTHENTICATION("Codex sign-in has expired or is unavailable. Sign in with ChatGPT again."),
    QUOTA("Your Codex usage limit was reached. Wait for your allowance to reset or check your subscription."),
    MODEL("The selected Codex model is unavailable for this account. Refresh models and choose another model."),
    CONTEXT("The supplied context exceeds the model's limit. Reduce the selected code or conversation."),
    NETWORK("Codex could not reach its server. Check your network connection and retry."),
    PROTOCOL("The Codex runtime rejected the protocol request. Update Codex or select a compatible version."),
    PROCESS("The Codex process stopped. Retry to start a new connection."),
    MALFORMED_PROTOCOL("Codex sent an invalid protocol response. Retry, or select another runtime version."),
    REJECTED("Codex rejected the request. Retry, or check your model access and subscription."),
}

/** Only fixed guidance and numeric codes escape the protocol; upstream text can contain user code. */
internal class CodexFailure(
    val kind: CodexFailureKind,
    val rpcCode: Int? = null,
    val httpStatusCode: Int? = null,
) : IllegalStateException(kind.guidance) {
    companion object {
        fun fromError(error: JsonObject): CodexFailure {
            val code = runCatching { error.get("code")?.asInt }.getOrNull()
            val data = error.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            val info = error.get("codexErrorInfo") ?: data?.get("codexErrorInfo")
            val tag = when {
                info?.isJsonPrimitive == true -> info.asString
                info?.isJsonObject == true -> info.asJsonObject.keySet().singleOrNull()
                else -> null
            }
            val details = info?.takeIf { it.isJsonObject }?.asJsonObject?.get(tag)
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val http = runCatching { details?.get("httpStatusCode")?.takeUnless { it.isJsonNull }?.asInt }.getOrNull()
            val kind = when {
                tag == "unauthorized" || http == 401 -> CodexFailureKind.AUTHENTICATION
                tag in setOf("usageLimitExceeded", "rateLimitExceeded", "sessionBudgetExceeded") || http == 429 -> CodexFailureKind.QUOTA
                tag == "contextWindowExceeded" -> CodexFailureKind.CONTEXT
                // Some runtime versions return model validation only as unstructured text.
                isUnavailableModel(error) -> CodexFailureKind.MODEL
                tag in setOf("httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected", "responseTooManyFailedAttempts") -> CodexFailureKind.NETWORK
                code in setOf(-32700, -32600, -32601, -32602) -> CodexFailureKind.PROTOCOL
                else -> CodexFailureKind.REJECTED
            }
            return CodexFailure(kind, code, http)
        }

        private fun isUnavailableModel(error: JsonObject): Boolean {
            val message = runCatching { error.text("message")?.lowercase() }.getOrNull() ?: return false
            return "model" in message && listOf("not found", "does not exist", "not supported", "unsupported", "not available", "do not have access")
                .any { it in message }
        }
    }
}
