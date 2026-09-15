package dev.piexel.codex

import ai.grazie.model.cloud.ContentType
import ai.grazie.utils.attributes.Attributes
import com.intellij.ml.llm.core.models.api.*
import com.intellij.ml.llm.core.providers.*
import com.intellij.ml.llm.smartChat.endpoints.LlmFunctionDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class CodexProvider : ThirdPartyAIProvider {
    override val providerId = ID
    override val name = "Codex Provider"
    override val isModelsSelectedExplicitly = true

    override suspend fun testConnection(): ThirdPartyAIProvider.ConnectionError? = try {
        CodexTrace.info("connection-test-started")
        val signedIn = CodexRuntime.instance.refreshAccount()
        CodexTrace.info("connection-test-completed", "signedIn" to signedIn)
        if (signedIn) null else ThirdPartyAIProvider.ConnectionError.GenericError("Sign in with ChatGPT to use your Codex provider.")
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        CodexTrace.warn("connection-test-failed", error)
        ThirdPartyAIProvider.ConnectionError.GenericError(error.message ?: "Cannot start Codex.")
    }

    override suspend fun getAvailableLLMs(): List<AiaThirdPartyProfileInfo> {
        val models = CodexRuntime.instance.models()
        CodexTrace.info("ide-model-discovery", "count" to models.size, "modelIds" to models.joinToString { it.id })
        return models.map { CodexProfile(it) }
    }

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
        val transcript = history.map { message ->
            require(message is AiaLlmTextMessage && message !is AiaLlmMediaMessage) { "This provider supports text messages only." }
            val role = when (message) {
                is AiaLlmSystemMessage -> "system"
                is AiaLlmUserMessage -> "user"
                is AiaLlmAssistantMessage -> "assistant"
                else -> error("Tool messages are not supported by this provider.")
            }
            Message(role, message.content)
        }
        val answer = CodexRuntime.instance.generate(profileId.profileId, transcript)
        emit(object : AiaLlmStreamText {
            override val content = answer
            override val thoughtSignature: String? = null
        })
        emit(object : AiaLlmStreamFinishMetaData {})
        CodexTrace.info(
            "ide-chat-request-completed", "requestId" to requestId, "outputCharacters" to answer.length,
            "durationMs" to (System.nanoTime() - started) / 1_000_000,
        )
        } catch (error: Throwable) {
            CodexTrace.warn(
                "ide-chat-request-failed", error, "requestId" to requestId,
                "durationMs" to (System.nanoTime() - started) / 1_000_000,
            )
            throw error
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun streamingCompletion(
        profileId: ThirdPartyLLMProfileId, prompt: String, suffix: String?, stopTokens: List<String>?,
    ): Flow<AiaLlmStreamData> = flow {
        CodexTrace.info("ide-completion-request-rejected", "modelId" to profileId.profileId)
        error("Automatic completion and next-edit prediction are not supported by Codex Provider.")
    }

    override fun getSettingsUIBuilder(scope: CoroutineScope): ThirdPartyAIProvider.SettingsUIBuilder = CodexSettingsPanel(this, scope)
    override fun getSettings(): ThirdPartyAISettingsManager = CodexSettings.instance

    companion object { const val ID = "codex-subscription" }
}

private class CodexProfile(private val model: CodexModel) : AiaThirdPartyProfileInfo(
    ThirdPartyLLMProfileId(CodexProvider.ID, model.id), AiaLlmProviderType.OpenAI,
) {
    override val modelName get() = model.name
    // JetBrains filters non-chat actions through known profile IDs. This is the
    // shared compatibility profile; requests still use the real Codex model ID.
    override val profileIdInJBAIFormat = "qwen-max"
    override fun supportsToolCalling() = false
    override fun supportsResponses() = false
    override fun supportsSystemMessage() = true
    override fun supportsContentType(type: ContentType) = type.toString().equals("text", ignoreCase = true)
}
