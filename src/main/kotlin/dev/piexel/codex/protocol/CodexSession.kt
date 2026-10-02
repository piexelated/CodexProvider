package dev.piexel.codex.protocol

import dev.piexel.codex.CodexTrace

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.nio.file.Path

internal data class CodexModel(val id: String, val name: String)
internal data class Message(val role: String, val content: String)
internal data class CodexQuota(val usageAllowed: Boolean, val usedPercent: Int, val resetsAtEpochSeconds: Long?)

internal class CodexSession(private val rpc: JsonRpc, private val workDir: Path) : Closeable by rpc {
    private data class TurnProgress(
        var turnId: String? = null,
        var completed: Boolean = false,
        var answer: String? = null,
    )

    private companion object {
        val TURN_EVENTS = setOf("item/completed", "turn/completed")
    }

    val isAlive get() = rpc.isAlive

    suspend fun initialize() {
        rpc.request("initialize", obj(
            "clientInfo" to obj("name" to "codex_subscription_jetbrains", "title" to "Codex Provider for JetBrains", "version" to "1.0"),
            "capabilities" to obj("experimentalApi" to true),
        ))
        rpc.notify("initialized")
    }

    fun onAccountChange(callback: () -> Unit): Closeable = rpc.subscribe { method, _ ->
        if (method == "account/updated" || method == "account/login/completed") callback()
    }

    fun onQuotaChange(callback: () -> Unit): Closeable = rpc.subscribe { method, _ ->
        if (method == "account/rateLimits/updated") callback()
    }

    suspend fun account(): JsonObject? = rpc.request("account/read", obj("refreshToken" to false))
        .get("account")?.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.text("type") == "chatgpt" }

    suspend fun quota(): CodexQuota? {
        val response = rpc.request("account/rateLimits/read", obj("skipRateLimitResetCredits" to true))
        val allowed = response.get("ordinaryUsageAllowed")
            ?.takeUnless { it.isJsonNull }
            ?.asBoolean
            ?: return null
        val snapshot = response.get("rateLimits")?.takeIf { it.isJsonObject }?.asJsonObject
        val window = snapshot?.get("primary")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: snapshot?.get("secondary")?.takeIf { it.isJsonObject }?.asJsonObject
        return CodexQuota(
            usageAllowed = allowed,
            usedPercent = window?.get("usedPercent")?.takeUnless { it.isJsonNull }?.asInt ?: if (allowed) 0 else 100,
            resetsAtEpochSeconds = window?.get("resetsAt")?.takeUnless { it.isJsonNull }?.asLong,
        )
    }

    suspend fun login(): String = rpc.request("account/login/start", obj("type" to "chatgpt"))
        .text("authUrl") ?: error("Codex did not return a sign-in URL.")

    suspend fun logout() { rpc.request("account/logout") }

    suspend fun models(): List<CodexModel> {
        if (account() == null) return emptyList()
        val models = mutableListOf<CodexModel>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = rpc.request("model/list", obj("cursor" to cursor, "limit" to 100, "includeHidden" to false))
            page.getAsJsonArray("data")?.forEach { entry ->
                val model = entry.asJsonObject
                if (model.get("hidden")?.asBoolean != true) {
                    val id = model.text("model") ?: model.text("id") ?: return@forEach
                    models.add(CodexModel(id, model.text("displayName") ?: id))
                }
            }
            cursor = page.text("nextCursor")
            check(cursor == null || seen.add(cursor)) { "Codex returned a repeated model cursor." }
        } while (cursor != null)
        return models.distinctBy { it.id }
    }

    suspend fun generate(model: String, messages: List<Message>, timeoutMs: Long = 120_000, projectDir: Path? = null): String = withTimeout(timeoutMs) {
        validateMessages(messages)
        val thread = startThread(model, messages, projectDir)
        receiveTurn(thread, messages)
    }

    private suspend fun validateMessages(messages: List<Message>) {
        check(account() != null) { "Sign in with ChatGPT in the Codex provider settings." }
        require(messages.isNotEmpty()) { "No messages were supplied." }
        require(messages.sumOf { it.content.length.toLong() } <= 2 * 1024 * 1024) { "The supplied context is too large." }
    }

    internal suspend fun startThread(model: String, messages: List<Message>, projectDir: Path? = null): String {
        val instructions = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        return rpc.request("thread/start", obj(
            "model" to model, "modelProvider" to "openai", "cwd" to (projectDir ?: workDir).toString(),
            "approvalPolicy" to "never", "sandbox" to "read-only", "ephemeral" to true,
            "baseInstructions" to "You provide text for an IDE. Follow the supplied task and its exact output format. Return the final answer only. Do not inspect or modify files, use tools, or send progress reports.",
            "developerInstructions" to instructions,
        )).getAsJsonObject("thread").text("id") ?: error("Codex returned no thread ID.")
    }

    private suspend fun receiveTurn(thread: String, messages: List<Message>): String {
        val events = Channel<Pair<String, JsonObject>>(64)
        val listener = subscribeToTurn(thread, events)
        val progress = TurnProgress()
        try {
            progress.turnId = startTurn(thread, messages)
            return awaitTurnCompletion(events, progress)
        } finally {
            listener.close()
            events.close()
            // If turn/start was cancelled before its reply, no turn ID is available
            // to interrupt. Closing the process prevents orphaned inference.
            if (!progress.completed && progress.turnId == null) rpc.close()
            cleanupTurn(thread, progress)
        }
    }

    private fun subscribeToTurn(thread: String, events: Channel<Pair<String, JsonObject>>): Closeable =
        rpc.subscribe { method, params ->
            when {
                method == "connection/closed" -> events.close(CodexFailure(
                    runCatching { CodexFailureKind.valueOf(params.text("kind").orEmpty()) }.getOrDefault(CodexFailureKind.PROCESS),
                ))
                params.text("threadId") == thread && method in TURN_EVENTS && events.trySend(method to params).isFailure ->
                    events.close(IllegalStateException("Too many Codex events."))
            }
        }

    private suspend fun startTurn(thread: String, messages: List<Message>): String? {
        val transcript = JsonArray().apply {
            messages.filter { it.role != "system" }
                .forEach { add(obj("role" to it.role, "content" to it.content)) }
        }
        val response = rpc.request("turn/start", obj(
            "threadId" to thread,
            "input" to listOf(obj(
                "type" to "text",
                "text" to "Answer the final user message in this ordered conversation transcript:\n${gson.toJson(transcript)}",
            )),
        ))
        return response.getAsJsonObject("turn").text("id")
    }

    private suspend fun awaitTurnCompletion(
        events: Channel<Pair<String, JsonObject>>,
        progress: TurnProgress,
    ): String {
        while (true) {
            val (method, event) = events.receive()
            when (method) {
                "item/completed" -> updateAnswer(event, progress)
                "turn/completed" -> {
                    val turn = event.getAsJsonObject("turn")
                    progress.completed = true
                    if (turn.text("status") != "completed") {
                        val error = turn.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj()
                        throw CodexFailure.fromError(error)
                    }
                    return progress.answer?.takeIf(String::isNotEmpty) ?: error("Codex returned no answer.")
                }
            }
        }
    }

    private fun updateAnswer(event: JsonObject, progress: TurnProgress) {
        val item = event.getAsJsonObject("item")
        if (item.text("type") != "agentMessage" || item.text("phase") == "commentary") return
        progress.answer = item.text("text")
        check((progress.answer?.length ?: 0) <= 4 * 1024 * 1024) { "Codex output is too large." }
    }

    private suspend fun cleanupTurn(thread: String, progress: TurnProgress) {
        withContext(NonCancellable) {
            runCatching {
                withTimeout(2_000) {
                    if (!progress.completed) progress.turnId?.let { turnId ->
                        rpc.request("turn/interrupt", obj("threadId" to thread, "turnId" to turnId))
                    }
                    rpc.request("thread/unsubscribe", obj("threadId" to thread))
                }
            }
        }
    }
}
