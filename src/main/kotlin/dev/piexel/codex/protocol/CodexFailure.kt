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
            val info = error.get("codexErrorInfo") ?: error.objectOrNull("data")?.get("codexErrorInfo")
            val tag = when {
                info?.isJsonPrimitive == true -> info.asString
                info?.isJsonObject == true -> info.asJsonObject.keySet().singleOrNull()
                else -> null
            }
            val details = tag?.let { (info as? JsonObject)?.objectOrNull(it) }
            val http = runCatching { details?.intOrNull("httpStatusCode") }.getOrNull()
            val kind = when {
                tag == "unauthorized" || http == HTTP_UNAUTHORIZED -> CodexFailureKind.AUTHENTICATION
                tag in QUOTA_TAGS || http == HTTP_TOO_MANY_REQUESTS -> CodexFailureKind.QUOTA
                tag == "contextWindowExceeded" -> CodexFailureKind.CONTEXT
                // Some runtime versions return model validation only as unstructured text.
                isUnavailableModel(error) -> CodexFailureKind.MODEL
                tag in NETWORK_TAGS -> CodexFailureKind.NETWORK
                code in JSON_RPC_PROTOCOL_ERRORS -> CodexFailureKind.PROTOCOL
                else -> CodexFailureKind.REJECTED
            }
            return CodexFailure(kind, code, http)
        }

        private fun isUnavailableModel(error: JsonObject): Boolean {
            val message = runCatching { error.text("message")?.lowercase() }.getOrNull() ?: return false
            return "model" in message && UNAVAILABLE_MODEL_PHRASES.any { it in message }
        }

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val QUOTA_TAGS = setOf("usageLimitExceeded", "rateLimitExceeded", "sessionBudgetExceeded")
        private val NETWORK_TAGS = setOf(
            "httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected", "responseTooManyFailedAttempts",
        )
        /** Parse error, invalid request, method not found, and invalid params. */
        private val JSON_RPC_PROTOCOL_ERRORS = setOf(-32700, -32600, -32601, -32602)
        private val UNAVAILABLE_MODEL_PHRASES = listOf(
            "not found", "does not exist", "not supported", "unsupported", "not available", "do not have access",
        )
    }
}
