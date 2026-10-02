package dev.piexel.codex

import ai.grazie.model.cloud.ContentType
import ai.grazie.model.llm.prompt.LLMPromptID
import ai.grazie.model.llm.chat.v5.LLMChat
import ai.grazie.model.llm.chat.v5.LLMChatUserMessage
import ai.grazie.utils.attributes.Attributes
import com.intellij.ml.llm.core.models.api.*
import com.intellij.ml.llm.core.models.PreferableModelProvider
import com.intellij.ml.llm.core.models.LlmProfileService
import com.intellij.ml.llm.core.models.LlmProfileRulesLoader
import com.intellij.ml.llm.core.providers.ThirdPartyAIProvider
import com.intellij.ml.llm.core.providers.AiaThirdPartyLlmProviderClient
import com.intellij.ml.llm.core.providers.ThirdPartyAIProvidersSettingsManager
import com.intellij.ml.llm.core.providers.ThirdPartyLLMProfileId
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.UsefulTestCase
import com.intellij.openapi.application.ApplicationManager
import dev.piexel.codex.jetbrains.CodexProvider
import dev.piexel.codex.jetbrains.CodexProviderBackend
import dev.piexel.codex.jetbrains.CodexFeatureModelPreference
import dev.piexel.codex.jetbrains.CodexFeatures
import dev.piexel.codex.jetbrains.CodexSettings
import dev.piexel.codex.protocol.CodexModel
import dev.piexel.codex.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.nio.file.Path

/** Calls the AI Assistant provider contract inside the selected IntelliJ test platform. */
class CompatibilitySmokeTest : UsefulTestCase() {
    fun testProviderContract() {
        TestApplicationManager.getInstance()
        runBlocking { verifyProviderContract() }
    }
}

private suspend fun verifyProviderContract() {
    val backend = FixtureBackend()
    val provider = CodexProvider(backend)
    val profileId = ThirdPartyLLMProfileId(CodexProvider.ID, "fixture-model")
    var passed = 0
    suspend fun verify(name: String, test: suspend () -> Unit) {
        test()
        println("PASS $name")
        passed++
    }

    verify("provider identity") {
        check(provider.providerId == CodexProvider.ID)
        check(provider.name.isNotBlank())
        check(provider.isModelsSelectedExplicitly)
    }
    verify("AI Assistant extension registration") {
        check(ThirdPartyAIProvider.findById(CodexProvider.ID) is CodexProvider)
        check(PreferableModelProvider.EP_NAME.extensionList.any {
            it.javaClass.name == "dev.piexel.codex.jetbrains.CodexFeatureModelPreference"
        })
    }
    verify("connection test") {
        check(provider.testConnection() == null)
        backend.signedIn = false
        check(provider.testConnection() != null)
        backend.signedIn = true
    }
    verify("model discovery and capabilities") {
        val profiles = provider.getAvailableLLMs()
        check(profiles.size == 1)
        val profile = profiles.single()
        check(profile.id == profileId)
        check(profile.modelName == "Fixture model")
        check(profile.supportsChat())
        check(profile.supportsSystemMessage())
        check(profile.supportsContentType(ContentType("text", "plain")))
        check(!profile.supportsContentType(ContentType("image", "png")))
        check(!profile.supportsToolCalling())
    }
    verify("chat stream and role translation") {
        val history = listOf<AiaLlmChatMessage>(
            object : AiaLlmSystemMessage { override val content = "Answer briefly" },
            object : AiaLlmUserMessage { override val content = "Say hello" },
            object : AiaLlmAssistantMessage { override val content = "Previous answer" },
        )
        val output = provider.streamingChatCompletion(profileId, history, emptyList(), Attributes()).toList()
        check((output.first() as AiaLlmStreamText).content == "fixture response")
        check(output.last() is AiaLlmStreamFinishMetaData)
        check(backend.lastModel == "fixture-model")
        check(backend.lastMessages == listOf(
            Message("system", "Answer briefly"), Message("user", "Say hello"),
            Message("assistant", "Previous answer"),
        ))
    }
    verify("foreign model is rejected") {
        val foreign = ThirdPartyLLMProfileId("another-provider", "fixture-model")
        check(runCatching {
            provider.streamingChatCompletion(foreign, emptyList(), emptyList(), Attributes()).toList()
        }.isFailure)
    }
    verify("unsupported automatic completion is rejected") {
        check(runCatching { provider.streamingCompletion(profileId, "prefix", "suffix", emptyList()).toList() }.isFailure)
    }
    verify("feature model preference") {
        val settings = CodexSettings.instance
        settings.setFeatureModel("rename", "fixture-model")
        try {
            val preference = CodexFeatureModelPreference()
            val prompt = LLMPromptID(CodexFeatures.all.single { it.id == "rename" }.promptIds.single())
            check(preference.isPreferableModel(prompt, profileId))
            check(!preference.isPreferableModel(prompt, ThirdPartyLLMProfileId(CodexProvider.ID, "other-model")))
            check(!preference.isPreferableModel(LLMPromptID("unknown.feature"), profileId))
        } finally { settings.setFeatureModel("rename", null) }
    }
    verify("AI Assistant routes every supported prompt to Codex") {
        val application = ApplicationManager.getApplication()
        val registered = ThirdPartyAIProvider.findById(CodexProvider.ID) as CodexProvider
        val restoreBackend = registered.useBackendForTests(backend)
        val providerSettings = application.getService(ThirdPartyAIProvidersSettingsManager::class.java)
        val wasEnabled = providerSettings.isProviderEnabled(CodexProvider.ID)
        val acknowledgementWasAccepted = ThirdPartyAIProvidersSettingsManager.isThirdPartyAIServicesAcknowledgementAccepted()
        val settings = CodexSettings.instance
        val previousModels = CodexFeatures.all.associate { it.id to settings.featureModel(it.id) }
        try {
            ThirdPartyAIProvidersSettingsManager.setThirdPartyAIServicesAcknowledgementAccepted(true)
            providerSettings.enableProvider(CodexProvider.ID)
            check(providerSettings.isProviderEnabled(CodexProvider.ID)) {
                "AI Assistant refused to enable the Codex provider"
            }
            val upstreamClient = AiaThirdPartyLlmProviderClient()
            val profiles = withTimeout(10_000) { upstreamClient.getAvailableProfiles() }
            check(profiles.any { it.id == profileId }) {
                "AI Assistant did not discover Codex through its enabled third-party provider client; profiles: ${profiles.map { it.id }}"
            }
            LlmProfileService.setProfilesForTests(profiles.toList())
            val rulesLoader = application.getService(LlmProfileRulesLoader::class.java)
            rulesLoader.initiateLoadingForTesting("http://127.0.0.1:9").await()
            withTimeout(10_000) {
                while (!rulesLoader.areRulesLoaded()) delay(20)
            }
            val upstreamPromptIds = checkNotNull(rulesLoader.getRulesIfLoaded()).promptRules.map { it.promptId }.toSet()
            CodexFeatures.all.forEach { settings.setFeatureModel(it.id, "fixture-model") }
            val service = LlmProfileService.getInstance()
            val chatProfiles = withTimeout(10_000) { service.getAvailableChatProfiles() }
            check(profileId in chatProfiles) {
                "AI Assistant excluded Codex from its chat model selector; candidates: $chatProfiles"
            }
            val routeFailures = mutableListOf<String>()
            for (feature in CodexFeatures.all) for (promptId in feature.promptIds) {
                try {
                    check(promptId in upstreamPromptIds) {
                        "AI Assistant has no bundled routing rule for ${feature.id} ($promptId)"
                    }
                    val prompt = LLMPromptID(promptId)
                    val candidates = withTimeout(10_000) { service.getAvailableProfilesForPromptId(prompt) }
                    check(profileId in candidates) {
                        "AI Assistant excluded Codex from ${feature.id} ($promptId); candidates: $candidates"
                    }
                    val selected = withTimeout(10_000) { service.getLlmProfileIdIfAvailable(prompt) }
                    check(selected == profileId) {
                        "AI Assistant routed ${feature.id} ($promptId) to $selected instead of $profileId"
                    }
                    val requestText = "compatibility probe: $promptId"
                    val requestCount = backend.generationCount
                    val stream = withTimeout(10_000) {
                        upstreamClient.sendChatRequestPrivacyUnsafe(
                            prompt, LLMChat(arrayOf(LLMChatUserMessage(requestText))), emptyList(), Attributes(),
                        )?.toList()
                    }
                    check(stream?.firstOrNull() is AiaLlmStreamText)
                    check((stream.first() as AiaLlmStreamText).content == "fixture response")
                    check(backend.generationCount == requestCount + 1)
                    check(backend.lastMessages == listOf(Message("user", requestText)))
                    println("PASS route $promptId")
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val failure = "${feature.id} ($promptId): ${error.message ?: error.javaClass.simpleName}"
                    routeFailures += failure
                    println("FAIL route $failure")
                }
            }
            check(routeFailures.isEmpty()) { "AI Assistant routing failed for ${routeFailures.size} prompts:\n${routeFailures.joinToString("\n")}" }
        } finally {
            previousModels.forEach { (featureId, modelId) -> settings.setFeatureModel(featureId, modelId) }
            if (!wasEnabled) providerSettings.disableProvider(CodexProvider.ID)
            ThirdPartyAIProvidersSettingsManager.setThirdPartyAIServicesAcknowledgementAccepted(acknowledgementWasAccepted)
            restoreBackend()
        }
    }
    verify("settings entry points") {
        check(provider.getSettings() === backend.fixtureSettings)
        provider.setBaseUrl("https://example.invalid")
        check(provider.getSettings().baseUrl.isEmpty())
        check(provider.getSettings().getApiKey().isEmpty())
        provider.getSettings().setApiKey("")
        check(runCatching { provider.getSettings().setApiKey("fixture-secret") }.isFailure)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { provider.getSettingsUIBuilder(scope) }
        finally { scope.cancel() }
    }
    println("$passed AI Assistant provider contract checks passed against the selected binaries.")
}

private class FixtureBackend : CodexProviderBackend {
    var signedIn = true
    var lastModel: String? = null
    var lastMessages: List<Message>? = null
    var generationCount = 0
    val fixtureSettings = CodexSettings()

    override suspend fun refreshAccount() = signedIn
    override fun models() = listOf(CodexModel("fixture-model", "Fixture model"))
    override suspend fun generate(model: String, messages: List<Message>, projectDir: Path?): String {
        generationCount++
        lastModel = model
        lastMessages = messages
        return "fixture response"
    }
    override fun settings() = fixtureSettings
}
