package dev.piexel.codex.jetbrains

import dev.piexel.codex.CodexTrace
import dev.piexel.codex.runCatchingUnlessCancelled
import dev.piexel.codex.protocol.Message
import dev.piexel.codex.protocol.CodexModel
import dev.piexel.codex.runtime.CodexRuntime

import ai.grazie.model.cloud.ContentType
import ai.grazie.utils.attributes.Attributes
import com.intellij.ml.llm.core.models.api.*
import com.intellij.ml.llm.core.providers.*
import com.intellij.ml.llm.smartChat.endpoints.LlmFunctionDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Files
import java.nio.file.Path

internal interface CodexProviderBackend {
    suspend fun refreshAccount(): Boolean
    fun models(): List<CodexModel>
    suspend fun generate(model: String, messages: List<Message>, projectDir: Path?): String
    fun settings(): CodexSettings
}

private object LiveCodexBackend : CodexProviderBackend {
    override suspend fun refreshAccount() = CodexRuntime.instance.refreshAccount()
    override fun models() = CodexRuntime.instance.models()
    override suspend fun generate(model: String, messages: List<Message>, projectDir: Path?) =
        CodexRuntime.instance.generate(model, messages, projectDir)
    override fun settings() = CodexSettings.instance
}

class CodexProvider : ThirdPartyAIProvider {
    @Volatile
    private var backend: CodexProviderBackend = LiveCodexBackend

    internal fun useBackendForTests(replacement: CodexProviderBackend): () -> Unit {
        val original = backend
        backend = replacement
        return { backend = original }
    }

    override val providerId = ID
    override val name = "Codex Provider"
    override val isModelsSelectedExplicitly = true

    override suspend fun testConnection(): ThirdPartyAIProvider.ConnectionError? = runCatchingUnlessCancelled {
        CodexTrace.info("connection-test-started")
        val signedIn = backend.refreshAccount()
        CodexTrace.info("connection-test-completed", "signedIn" to signedIn)
        if (signedIn) null else ThirdPartyAIProvider.ConnectionError.GenericError("Sign in with ChatGPT to use your Codex provider.")
    }.getOrElse { error ->
        CodexTrace.warn("connection-test-failed", error)
        ThirdPartyAIProvider.ConnectionError.GenericError(error.message ?: "Cannot start Codex.")
    }

    override suspend fun getAvailableLLMs(): List<AiaThirdPartyProfileInfo> {
        val models = backend.models()
        CodexTrace.info("ide-model-discovery", "count" to models.size, "modelIds" to models.joinToString { it.id })
        return models.map { CodexProfile(it) }
    }

    // ThirdPartyAIProvider declares this override as suspending, although it returns a cold Flow.
    @Suppress("kotlin:S6309")
    override suspend fun streamingChatCompletion(
        profileId: ThirdPartyLLMProfileId,
        history: List<AiaLlmChatMessage>,
        functions: List<LlmFunctionDescriptor>,
        attributes: Attributes,
    ): Flow<AiaLlmStreamData> = flow {
        val requestId = CodexTrace.nextRequestId()
        val started = System.nanoTime()
        CodexTrace.info(
            "ide-chat-request-started", "requestId" to requestId, "modelId" to profileId.profileId,
            "messages" to history.size, "characters" to history.sumOf { (it as? AiaLlmTextMessage)?.content?.length ?: 0 },
            "functions" to functions.size,
        )
        try {
            require(profileId.providerId == ID) { "The selected model does not belong to Codex Provider." }
            require(functions.isEmpty()) { "Tool calling is not supported by the Codex Provider text provider." }
            val answer = backend.generate(profileId.profileId, toCodexTranscript(history), activeProjectDirectory())
            if (backend.settings().debugMode) logResponseBody(requestId, answer)
            emit(object : AiaLlmStreamText {
                override val content = answer
                override val thoughtSignature: String? = null
            })
            emit(object : AiaLlmStreamFinishMetaData {})
            CodexTrace.info(
                "ide-chat-request-completed", "requestId" to requestId, "outputCharacters" to answer.length,
                "durationMs" to CodexTrace.elapsedMs(started),
            )
        } catch (error: Throwable) {
            CodexTrace.warn("ide-chat-request-failed", error, "requestId" to requestId, "durationMs" to CodexTrace.elapsedMs(started))
            throw error
        }
    }.flowOn(Dispatchers.IO)

    private fun logResponseBody(requestId: Long, answer: String) = CodexTrace.info(
        "ide-chat-response-body", "requestId" to requestId, "outputCharacters" to answer.length,
        "response" to CodexTrace.escapeLogValue(answer),
    )

    // ThirdPartyAIProvider declares this override as suspending, although it returns a cold Flow.
    @Suppress("kotlin:S6309")
    override suspend fun streamingCompletion(
        profileId: ThirdPartyLLMProfileId, prompt: String, suffix: String?, stopTokens: List<String>?,
    ): Flow<AiaLlmStreamData> = flow {
        CodexTrace.info("ide-completion-request-rejected", "modelId" to profileId.profileId)
        error("Automatic completion and next-edit prediction are not supported by Codex Provider.")
    }

    override fun getSettingsUIBuilder(scope: CoroutineScope): ThirdPartyAIProvider.SettingsUIBuilder = CodexSettingsPanel(this, scope)
    override fun getSettings(): ThirdPartyAISettingsManager = backend.settings()

    companion object { const val ID = "codex-subscription" }
}

private fun toCodexTranscript(history: List<AiaLlmChatMessage>): List<Message> = history.map { message ->
    require(message is AiaLlmTextMessage && message !is AiaLlmMediaMessage) { "This provider supports text messages only." }
    val role = when (message) {
        is AiaLlmSystemMessage -> "system"
        is AiaLlmUserMessage -> "user"
        is AiaLlmAssistantMessage -> "assistant"
        else -> error("Tool messages are not supported by this provider.")
    }
    Message(role, message.content)
}

private fun activeProjectDirectory(): Path? {
    val projects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed || it.isDefault }
    val project = projects.singleOrNull()
        ?: projects.singleOrNull { WindowManager.getInstance().getFrame(it)?.isActive == true }
        ?: return null
    return project.basePath?.let(Path::of)?.takeIf(Files::isDirectory)
}

private class CodexProfile(private val model: CodexModel) : AiaThirdPartyProfileInfo(
    // OpenAI and Anthropic are treated as BYOK providers and are excluded from non-chat prompts.
    ThirdPartyLLMProfileId(CodexProvider.ID, model.id), codexProfileProviderType(),
) {
    override val modelName get() = model.name
    // JetBrains filters non-chat actions through known profile IDs. This is the
    // shared compatibility profile; requests still use the real Codex model ID.
    override val profileIdInJBAIFormat = "qwen-max"
    override fun supportsToolCalling() = false
    override fun supportsResponses() = false
    override fun supportsSystemMessage() = true
    override fun supportsContentType(type: ContentType) = codexSupportsContentType(type)
}

internal fun codexProfileProviderType(): AiaLlmProviderType = AiaLlmProviderType.Unknown

// ContentType.toString() is a MIME type (e.g. text/plain), never just "text".
internal fun codexSupportsContentType(type: ContentType): Boolean = type.type.equals("text", ignoreCase = true)
