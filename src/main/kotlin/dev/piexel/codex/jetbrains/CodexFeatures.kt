package dev.piexel.codex.jetbrains

import ai.grazie.model.llm.prompt.LLMPromptID
import com.intellij.ml.llm.core.models.PreferableModelProvider
import com.intellij.ml.llm.core.models.api.AiaLlmProfileID
import com.intellij.ml.llm.core.providers.LlmCustomModelsSettingsManager
import com.intellij.ml.llm.core.providers.ThirdPartyLLMProfileId

internal enum class CodexFeatureDefault { CORE, INSTANT }

/** Reads which model AI Assistant's Model Assignment gives each default, when that model comes from Codex. */
internal object AssistantDefaultModels {
    fun assignedCodexModel(default: CodexFeatureDefault): String? {
        val assignments = LlmCustomModelsSettingsManager.getInstance()
        val profile: ThirdPartyLLMProfileId? = when (default) {
            CodexFeatureDefault.CORE -> assignments.smartModelId
            CodexFeatureDefault.INSTANT -> assignments.quickModelId
        }
        return profile?.takeIf { it.providerId == CodexProvider.ID }?.profileId
    }
}

internal data class CodexFeature(
    val id: String,
    val label: String,
    val description: String,
    val promptIds: Set<String>,
    val default: CodexFeatureDefault = CodexFeatureDefault.CORE,
)

internal object CodexFeatures {
    val all = listOf(
        CodexFeature("rename", "Rename suggestions", "Names proposed during Rename refactoring.", setOf("ij.refactorings.name-suggestion")),
        CodexFeature(
            "chat_helpers", "Chat helpers", "Chat titles, classification, and context summaries.",
            setOf(
                "ij.fast.chat.title-generation", "ij.fast.chat.classification",
                "ij.fast.chat.file-name-generation", "ij.fast.chat.classify-users-prompt",
                "ij.fast.chat.summarize-context-item", "ij.fast.chat.classify-context-item",
                "ij.fast.chat.classify-context-items", "ij.fast.chat.simplify-and-decompose-request",
                "ij.fast.chat.simplify-and-decompose-request-with-history",
            ),
            CodexFeatureDefault.INSTANT,
        ),
        CodexFeature("chat_code", "Chat code changes", "Code generation and applying snippets from AI Chat.", setOf("ij.code-generation-from-chat", "ij.apply.snippet")),
        CodexFeature("editor_edit", "Edit selected code", "Prompt-based edits to the current selection.", setOf("ij.editor.edit-request")),
        CodexFeature(
            "editor_generate", "Generate code in editor", "In-place and inline-prompt code generation.",
            setOf("ij.editor.generate-code-inplace", "ij.editor.generate-code-inline-prompt", "ij.editor.classify-user-prompt"),
        ),
        CodexFeature(
            "tests", "Test generation", "Unit tests, integration tests, and related test paths.",
            setOf(
                "ij.editor.generate-unit-tests", "ij.testing.integration-tests",
                "rm.testing.unit-test-directory-path-generation", "rm.testing.unit-test-file-name-generation",
            ),
        ),
        CodexFeature("documentation", "Generate documentation", "Documentation generated from selected code.", setOf("ij.editor.generate-documentation")),
        CodexFeature("extract_component", "Extract component", "AI-assisted component extraction.", setOf("ij.editor.extract-component")),
        CodexFeature("groovy_to_kotlin", "Convert Groovy to Kotlin", "AI-assisted Groovy-to-Kotlin conversion.", setOf("ij.editor.convert-groovy-to-kotlin")),
        CodexFeature("sql_scripts", "Generate SQL scripts", "SQL aggregator and extractor generation.", setOf("sql.script-generator.generate-aggregator", "sql.script-generator.generate-extractor")),
        CodexFeature("merge_conflicts", "Resolve merge conflicts", "AI-assisted merge conflict resolution.", setOf("ij.vcs.merge.resolve-conflicts")),
        CodexFeature("self_review", "Self-review", "AI review of the current changes.", setOf("ij.vcs.self-review")),
    )

    private val byPrompt = all.flatMap { feature -> feature.promptIds.map { it to feature } }.toMap()

    fun forPrompt(promptId: String): CodexFeature? = byPrompt[promptId]
}

class CodexFeatureModelPreference : PreferableModelProvider {
    override fun isPreferableModel(promptId: LLMPromptID, profileId: AiaLlmProfileID): Boolean {
        val codexProfile = profileId as? ThirdPartyLLMProfileId ?: return false
        if (codexProfile.providerId != CodexProvider.ID) return false
        val feature = CodexFeatures.forPrompt(promptId.id) ?: return false
        return matchesFeatureModel(CodexSettings.instance.featureModel(feature.id), codexProfile.profileId)
    }
}

internal fun matchesFeatureModel(configuredModelId: String?, candidateModelId: String): Boolean =
    configuredModelId == null || configuredModelId == candidateModelId
