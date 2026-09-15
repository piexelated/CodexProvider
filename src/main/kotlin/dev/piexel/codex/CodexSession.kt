package dev.piexel.codex

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

internal class CodexSession(private val rpc: JsonRpc, private val workDir: Path) : Closeable {
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

    suspend fun generate(model: String, messages: List<Message>, timeoutMs: Long = 120_000): String = withTimeout(timeoutMs) {
        check(account() != null) { "Sign in with ChatGPT in the Codex provider settings." }
        require(messages.isNotEmpty()) { "No messages were supplied." }
        require(messages.sumOf { it.content.length.toLong() } <= 2 * 1024 * 1024) { "The supplied context is too large." }
        val transcript = JsonArray()
        messages.filter { it.role != "system" }.forEach { transcript.add(obj("role" to it.role, "content" to it.content)) }
        val instructions = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val thread = rpc.request("thread/start", obj(
            "model" to model, "modelProvider" to "openai", "cwd" to workDir.toString(),
            "approvalPolicy" to "never", "sandbox" to "read-only", "ephemeral" to true,
            "baseInstructions" to "You provide text for an IDE. Follow the supplied task and its exact output format. Return the final answer only. Do not inspect or modify files, use tools, or send progress reports.",
            "developerInstructions" to instructions,
        )).getAsJsonObject("thread").text("id") ?: error("Codex returned no thread ID.")
        val events = Channel<Pair<String, JsonObject>>(64)
        val listener = rpc.subscribe { method, params ->
            if (method == "connection/closed") events.close(IllegalStateException("Codex connection was lost."))
            else if (params.text("threadId") == thread && method in setOf("item/completed", "turn/completed")) {
                if (events.trySend(method to params).isFailure) events.close(IllegalStateException("Too many Codex events."))
            }
        }
        var turnId: String? = null
        var completed = false
        try {
            val turn = rpc.request("turn/start", obj(
                "threadId" to thread,
                "input" to listOf(obj("type" to "text", "text" to "Answer the final user message in this ordered conversation transcript:\n${gson.toJson(transcript)}")),
            ))
            turnId = turn.getAsJsonObject("turn").text("id")
            var answer: String? = null
            while (true) {
                val (method, event) = events.receive()
                if (method == "item/completed") {
                    val item = event.getAsJsonObject("item")
                    if (item.text("type") == "agentMessage" && item.text("phase") != "commentary") {
                        answer = item.text("text")
                        check((answer?.length ?: 0) <= 4 * 1024 * 1024) { "Codex output is too large." }
                    }
                } else {
                    check(event.getAsJsonObject("turn").text("status") == "completed") {
                        "Codex did not complete the request. Check your model access and subscription limits."
                    }
                    completed = true
                    return@withTimeout answer?.takeIf { it.isNotEmpty() } ?: error("Codex returned no answer.")
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } finally {
            listener.close()
            events.close()
            // If turn/start was cancelled before its reply, no turn ID is available
            // to interrupt. Closing the process prevents orphaned inference.
            if (!completed && turnId == null) rpc.close()
            withContext(NonCancellable) {
                runCatching {
                    withTimeout(2_000) {
                        if (!completed && turnId != null) rpc.request("turn/interrupt", obj("threadId" to thread, "turnId" to turnId))
                        rpc.request("thread/unsubscribe", obj("threadId" to thread))
                    }
                }
            }
        }
    }

    override fun close() = rpc.close()
}
