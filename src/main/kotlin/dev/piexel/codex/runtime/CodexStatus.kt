package dev.piexel.codex.runtime

import com.google.gson.JsonObject
import dev.piexel.codex.protocol.text

/** How a status line should read at a glance; the settings panel maps each tone to an icon. */
internal enum class CodexStatusTone { NEUTRAL, SUCCESS, BUSY, ERROR }

internal enum class CodexRuntimeAction(val label: String) {
    INSTALL("Install Codex"),
    UPDATE("Update"),
    RETRY("Try Again"),
}

/** [text] is a short summary that fits beside the controls; [details] holds the full explanation, if any. */
internal data class CodexRuntimeStatus(
    val text: String,
    val tone: CodexStatusTone,
    val action: CodexRuntimeAction? = null,
    val details: String? = null,
)

internal enum class CodexAccountAction(val label: String) {
    SIGN_IN("Sign In with ChatGPT"),
    SIGN_OUT("Sign Out"),
}

internal data class CodexAccountStatus(
    val text: String,
    val tone: CodexStatusTone,
    val action: CodexAccountAction,
)

internal fun codexAccountStatus(account: JsonObject?): CodexAccountStatus {
    if (account == null) return CodexAccountStatus("Not signed in", CodexStatusTone.NEUTRAL, CodexAccountAction.SIGN_IN)
    val email = account.text("email")?.takeIf(String::isNotBlank) ?: "ChatGPT account"
    val plan = account.text("planType")?.takeIf(String::isNotBlank)?.let(::codexPlanLabel) ?: "Subscription"
    return CodexAccountStatus("$email · $plan", CodexStatusTone.SUCCESS, CodexAccountAction.SIGN_OUT)
}

private fun codexPlanLabel(value: String): String = when (value.lowercase()) {
    "prolite", "pro-lite", "pro_lite" -> "Pro Lite"
    else -> value.replace('-', ' ').replace('_', ' ').split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
}
