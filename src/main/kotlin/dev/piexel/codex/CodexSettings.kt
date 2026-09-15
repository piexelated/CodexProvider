package dev.piexel.codex

import com.intellij.ml.llm.core.providers.ThirdPartyAISettingsManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** JetBrains stores provider/model selection; Codex owns the account credentials. */
@Service(Service.Level.APP)
@State(name = "CodexSubscriptionSettings", storages = [Storage("codex.subscription.xml")])
internal class CodexSettings : ThirdPartyAISettingsManager, PersistentStateComponent<CodexSettings.StoredState> {
    data class StoredState(
        var featureModels: MutableMap<String, String> = mutableMapOf(),
        var runtimeVersion: String = CodexRuntimeInstaller.LATEST,
    )

    private var storedState = StoredState()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val connectionSettingsUpdatedFlow: SharedFlow<Unit> = changes
    override var baseUrl: String
        get() = ""
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    override var isToolEnabled: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    override suspend fun getApiKey(): String = ""
    override suspend fun setApiKey(apiKey: String) {
        require(apiKey.isBlank()) { "Use ChatGPT sign-in for this provider." }
    }
    override fun getState(): StoredState = storedState
    override fun loadState(state: StoredState) { storedState = state }
    var runtimeVersion: String
        get() = CodexRuntimeInstaller.normalizeSelection(storedState.runtimeVersion)
        set(value) { storedState.runtimeVersion = CodexRuntimeInstaller.normalizeSelection(value) }
    fun featureModel(featureId: String): String? = storedState.featureModels[featureId]
    fun setFeatureModel(featureId: String, modelId: String?) {
        if (modelId == null) storedState.featureModels.remove(featureId)
        else storedState.featureModels[featureId] = modelId
        CodexTrace.info("feature-model-assigned", "feature" to featureId, "modelId" to (modelId ?: "default"))
    }
    fun changed() { changes.tryEmit(Unit) }
    companion object { val instance: CodexSettings get() = service() }
}
