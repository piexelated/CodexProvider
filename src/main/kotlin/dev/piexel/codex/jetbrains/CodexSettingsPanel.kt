package dev.piexel.codex.jetbrains

import dev.piexel.codex.CodexTrace
import dev.piexel.codex.install.CodexRuntimeInstaller
import dev.piexel.codex.protocol.CodexModel
import dev.piexel.codex.runCatchingUnlessCancelled
import dev.piexel.codex.runtime.CodexAccountAction
import dev.piexel.codex.runtime.CodexAccountStatus
import dev.piexel.codex.runtime.CodexRuntime
import dev.piexel.codex.runtime.CodexRuntimeAction
import dev.piexel.codex.runtime.CodexRuntimeStatus
import dev.piexel.codex.runtime.CodexStatusTone

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ml.llm.core.providers.ThirdPartyAIProvider
import com.intellij.ml.llm.core.providers.ThirdPartyAISettingsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.InplaceButton
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindSelected
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.SwingUtilities

private const val SIGN_IN_HOST = "auth.openai.com"
private const val FEATURE_LABEL_WIDTH_GROUP = "codex.featureLabel"
private const val FEATURE_MODEL_WIDTH_GROUP = "codex.featureModel"
private const val VERSION_HELP = "Latest updates automatically. Choosing a version switches to it right away."
private const val FEATURE_MODELS_HELP = "An override replaces the Core or Instant model from Model Assignment for one feature."
private const val DIAGNOSTICS_HELP = "Responses can include project code. Turn this on only while diagnosing a problem."
// Keeps help text under the controls instead of widening the settings page.
private const val HELP_LINE_LENGTH = 50
private const val FEATURE_MODELS_SIGNED_OUT = "Sign in to choose a model for a feature."
private const val ADD_OVERRIDE = "Add Override…"

/**
 * Account sign-in and runtime switching act immediately because they change live state.
 * Feature models and diagnostics are preferences, so they follow the settings page's Apply and Cancel.
 */
internal class CodexSettingsPanel(
    private val provider: CodexProvider,
    private val scope: CoroutineScope,
) : ThirdPartyAIProvider.SettingsUIBuilder() {
    private val changeListeners = mutableListOf<() -> Unit>()

    override fun buildUI(parentPanel: Panel, settings: ThirdPartyAISettingsManager): List<Row> {
        uiState = object : UIState {
            override val providerWithCurrentSettings: ThirdPartyAIProvider get() = provider
            override fun onSettingsChanged(listener: () -> Unit) { changeListeners.add(listener) }
        }
        val codexSettings = settings as? CodexSettings ?: CodexSettings.instance
        val tasks = PanelTasks(scope)
        val runtime = CodexRuntime.instance
        val account = AccountSection(runtime, tasks)
        val runtimeSection = RuntimeSection(runtime, codexSettings, tasks)
        val featureModels = FeatureModelsSection(runtime, codexSettings, tasks) { changeListeners.toList().forEach { it() } }
        val rows = listOf(account.build(parentPanel), runtimeSection.build(parentPanel)) +
            featureModels.build(parentPanel) +
            buildDiagnostics(parentPanel, codexSettings)
        account.start()
        runtimeSection.start()
        featureModels.start()
        loadInitialState(runtime, tasks, account)
        return rows
    }

    private fun buildDiagnostics(parentPanel: Panel, settings: CodexSettings): Row =
        parentPanel.row("Diagnostics:") {
            checkBox("Log model responses to idea.log")
                .bindSelected(settings::debugMode)
                .comment(DIAGNOSTICS_HELP, maxLineLength = HELP_LINE_LENGTH)
        }

    private fun loadInitialState(runtime: CodexRuntime, tasks: PanelTasks, account: AccountSection) = tasks.launch {
        runCatchingUnlessCancelled {
            runtime.refreshAccount()
            runtime.checkForRuntimeUpdate()
        }.onFailure { error -> tasks.onEdt { account.showFailure("Couldn't check your account", error) } }
    }
}

/** Runs panel work off the EDT and applies its results only while the settings page is open. */
private class PanelTasks(private val scope: CoroutineScope) {
    fun launch(work: suspend () -> Unit) {
        scope.launch { work() }
    }

    // The settings dialog is modal; status updates are pure UI and must not wait for it to close.
    fun onEdt(update: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (scope.isActive) update() }, ModalityState.any())
    }

    fun <T> render(states: Flow<T>, update: (T) -> Unit) = launch { states.collect { state -> onEdt { update(state) } } }

    /** Disables only the controls of the section doing the work, so the rest of the page stays usable. */
    fun perform(
        busyControls: List<JComponent>,
        onFailure: (Throwable) -> Unit,
        onSuccess: () -> Unit = {},
        work: suspend () -> Unit,
    ) {
        busyControls.forEach { it.isEnabled = false }
        launch {
            try {
                runCatchingUnlessCancelled { work() }
                    .onSuccess { onEdt(onSuccess) }
                    .onFailure { error -> onEdt { onFailure(error) } }
            } finally {
                onEdt { busyControls.forEach { it.isEnabled = true } }
            }
        }
    }
}

private class AccountSection(private val runtime: CodexRuntime, private val tasks: PanelTasks) {
    private val status = StatusLine()
    private var action = runtime.status.value.action
    private lateinit var actionButton: JButton

    fun build(panel: Panel): Row = panel.row("Account:") {
        status.attachDetail(cell(status.label).comment("").comment!!)
        actionButton = button(action.label) { performAccountAction() }.component
    }.also { render(runtime.status.value) }

    fun start() = tasks.render(runtime.status, ::render)

    fun showFailure(summary: String, error: Throwable) = status.show(summary, CodexStatusTone.ERROR, error.message)

    private fun render(state: CodexAccountStatus) {
        status.show(state.text, state.tone)
        action = state.action
        actionButton.text = state.action.label
    }

    private fun performAccountAction() = when (action) {
        CodexAccountAction.SIGN_IN -> tasks.perform(listOf(actionButton), onFailure = { showFailure("Couldn't start sign-in", it) }) {
            val url = runtime.login()
            val uri = URI(url)
            check(uri.scheme == "https" && uri.host == SIGN_IN_HOST) { "Codex returned an unexpected sign-in address." }
            tasks.onEdt { BrowserUtil.browse(url) }
        }
        CodexAccountAction.SIGN_OUT -> tasks.perform(listOf(actionButton), onFailure = { showFailure("Couldn't sign out", it) }) {
            runtime.logout()
        }
    }
}

private class RuntimeSection(
    private val runtime: CodexRuntime,
    private val settings: CodexSettings,
    private val tasks: PanelTasks,
) {
    private data class VersionChoice(val selection: String, val label: String) {
        override fun toString() = label
    }

    private val versions = DefaultComboBoxModel<VersionChoice>()
    private val versionSelector = ComboBox(versions).apply { prototypeDisplayValue = VersionChoice("", "0.000.0-alpha.000") }
    private var versionsBeingReplaced = false
    private val status = StatusLine()
    private lateinit var actionButton: JButton

    fun build(panel: Panel): Row = panel.row("Codex runtime:") {
        status.attachDetail(cell(versionSelector).comment("").comment!!)
        contextHelp(VERSION_HELP)
        cell(status.label)
        actionButton = button(CodexRuntimeAction.UPDATE.label) { retryRuntime() }.component
    }.also {
        replaceVersions(emptyList())
        versionSelector.addActionListener { onVersionSelected() }
        render(runtime.runtimeStatus.value)
    }

    fun start() {
        tasks.render(runtime.runtimeStatus, ::render)
        loadVersions()
    }

    private fun render(state: CodexRuntimeStatus) {
        status.show(state.text, state.tone, state.details)
        actionButton.text = (state.action ?: CodexRuntimeAction.UPDATE).label
        actionButton.isVisible = state.action != null
        if (state.action == null) selectStoredVersion()
    }

    private fun retryRuntime() = tasks.perform(listOf(versionSelector, actionButton), onFailure = {}) { runtime.retryRuntime() }

    private fun onVersionSelected() {
        val choice = versionSelector.selectedItem as? VersionChoice ?: return
        if (versionsBeingReplaced || choice.selection == settings.runtimeVersion) return
        // The runtime reports its own failure; restore the selection that is still active.
        tasks.perform(listOf(versionSelector, actionButton), onFailure = { selectStoredVersion() }) {
            runtime.installRuntime(choice.selection)
        }
    }

    private fun loadVersions() = tasks.launch {
        runCatchingUnlessCancelled { runtime.runtimeVersions() }
            .onSuccess { available -> tasks.onEdt { replaceVersions(available) } }
            .onFailure { error ->
                CodexTrace.warn("runtime-version-list-failed", error)
                tasks.onEdt { status.fallbackDetail = "Couldn't load Codex versions, so only Latest is available right now." }
            }
    }

    private fun replaceVersions(available: List<String>) = whileReplacingVersions {
        val selected = settings.runtimeVersion
        versions.removeAllElements()
        versions.addElement(VersionChoice(CodexRuntimeInstaller.LATEST, "Latest"))
        if (selected != CodexRuntimeInstaller.LATEST && selected !in available) versions.addElement(VersionChoice(selected, selected))
        available.forEach { versions.addElement(VersionChoice(it, it)) }
        selectStoredVersion()
    }

    private fun selectStoredVersion() = whileReplacingVersions {
        val stored = (0 until versions.size).map(versions::getElementAt).firstOrNull { it.selection == settings.runtimeVersion }
        versions.selectedItem = stored ?: versions.getElementAt(0)
    }

    private fun whileReplacingVersions(update: () -> Unit) {
        val nested = versionsBeingReplaced
        versionsBeingReplaced = true
        try { update() } finally { versionsBeingReplaced = nested }
    }
}

/** Lists only the features with their own model; every other feature keeps AI Assistant's Core or Instant model. */
private class FeatureModelsSection(
    private val runtime: CodexRuntime,
    private val settings: CodexSettings,
    private val tasks: PanelTasks,
    private val onCatalogChanged: () -> Unit,
) {
    private data class ModelChoice(val id: String, val label: String) {
        override fun toString() = label
    }

    private class OverrideRow(val feature: CodexFeature) {
        val selector = ComboBox(DefaultComboBoxModel<ModelChoice>())
        lateinit var row: Row
    }

    private val overrides = CodexFeatures.all.map(::OverrideRow)
    /** Unapplied overrides by feature ID; they follow the settings page's Apply and Cancel. */
    private val pending = mutableMapOf<String, String>()
    private var rendering = false
    private lateinit var addLink: Cell<ActionLink>
    private lateinit var signedOutNote: Cell<JEditorPane>
    private lateinit var overrideList: Panel

    /** The label gets a single-line row so it lines up with the link instead of centering on the whole list. */
    fun build(panel: Panel): List<Row> {
        val header = panel.row("Feature models:") {
            addLink = link(ADD_OVERRIDE) { chooseFeatureToOverride() }
            signedOutNote = comment(FEATURE_MODELS_SIGNED_OUT)
            contextHelp(FEATURE_MODELS_HELP)
        }
        val list = panel.row("") {
            overrideList = panel {
                overrides.forEach { overrideRow(it) }
                onReset { resetPending() }
                onIsModified { pending != storedOverrides() }
                onApply { applyPending() }
            }
        }
        resetPending()
        return listOf(header, list)
    }

    fun start() = tasks.render(runtime.modelChanges.filterNotNull()) {
        render()
        onCatalogChanged()
    }

    private fun Panel.overrideRow(override: OverrideRow) {
        override.row = row {
            label(override.feature.label)
                .widthGroup(FEATURE_LABEL_WIDTH_GROUP)
                .applyToComponent { toolTipText = override.feature.description }
            cell(override.selector).widthGroup(FEATURE_MODEL_WIDTH_GROUP)
            cell(InplaceButton(IconButton("Remove override", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                removeOverride(override.feature)
            })
        }
        override.selector.addActionListener {
            val choice = override.selector.selectedItem as? ModelChoice
            if (!rendering && choice != null) pending[override.feature.id] = choice.id
        }
    }

    private fun chooseFeatureToOverride() {
        val available = CodexFeatures.all.filter { it.id !in pending }
        JBPopupFactory.getInstance().createPopupChooserBuilder(available)
            .setRenderer(FeatureChoiceRenderer())
            .setItemChosenCallback(::addOverride)
            .createPopup()
            .showUnderneathOf(addLink.component)
    }

    /** Starts from the model the feature already runs on, then opens the list so the user can pick another. */
    private fun addOverride(feature: CodexFeature) {
        val models = runtime.models()
        val current = models.firstOrNull { it.id == AssistantDefaultModels.assignedCodexModel(feature.default) }
        pending[feature.id] = (current ?: models.firstOrNull() ?: return).id
        render()
        val selector = overrides.first { it.feature == feature }.selector
        SwingUtilities.invokeLater {
            selector.requestFocusInWindow()
            selector.showPopup()
        }
    }

    private fun removeOverride(feature: CodexFeature) {
        pending.remove(feature.id)
        render()
    }

    private fun storedOverrides(): Map<String, String> =
        CodexFeatures.all.mapNotNull { feature -> settings.featureModel(feature.id)?.let { feature.id to it } }.toMap()

    private fun resetPending() {
        pending.clear()
        pending.putAll(storedOverrides())
        render()
    }

    private fun applyPending() = CodexFeatures.all.forEach { feature ->
        val chosen = pending[feature.id]
        if (chosen != settings.featureModel(feature.id)) settings.setFeatureModel(feature.id, chosen)
    }

    private fun render() {
        val models = runtime.models()
        rendering = true
        try {
            overrides.forEach { renderOverride(it, models) }
        } finally {
            rendering = false
        }
        signedOutNote.visible(models.isEmpty())
        addLink.visible(models.isNotEmpty() && overrides.any { it.feature.id !in pending })
        overrideList.visible(pending.isNotEmpty())
    }

    private fun renderOverride(override: OverrideRow, models: List<CodexModel>) {
        val selected = pending[override.feature.id]
        override.row.visible(selected != null)
        if (selected == null) return
        val choices = override.selector.model as DefaultComboBoxModel<ModelChoice>
        choices.removeAllElements()
        models.forEach { choices.addElement(ModelChoice(it.id, it.name)) }
        // An override for a model this account no longer offers stays visible instead of silently resetting.
        if (models.none { it.id == selected }) choices.addElement(ModelChoice(selected, "$selected (unavailable)"))
        choices.selectedItem = (0 until choices.size).map(choices::getElementAt).first { it.id == selected }
        override.selector.isEnabled = models.isNotEmpty()
    }
}

/** Shows each feature with its description, since the override list itself has no room for one. */
private class FeatureChoiceRenderer : ColoredListCellRenderer<CodexFeature>() {
    override fun customizeCellRenderer(
        list: JList<out CodexFeature>, value: CodexFeature, index: Int, selected: Boolean, hasFocus: Boolean,
    ) {
        append(value.label)
        append("  ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

/** A short status beside the controls, with an optional explanation in the comment line below them. */
private class StatusLine {
    val label = JBLabel()
    private var detail: JEditorPane? = null
    private var shownDetail: String? = null

    var fallbackDetail: String? = null
        set(value) {
            field = value
            renderDetail()
        }

    fun attachDetail(comment: JEditorPane) {
        detail = comment
        renderDetail()
    }

    fun show(text: String, tone: CodexStatusTone, detailText: String? = null) {
        label.text = text
        label.icon = tone.icon
        shownDetail = detailText
        renderDetail()
    }

    private fun renderDetail() {
        val comment = detail ?: return
        val text = shownDetail ?: fallbackDetail
        comment.text = text.orEmpty()
        comment.isVisible = text != null
    }
}

private val CodexStatusTone.icon: Icon?
    get() = when (this) {
        CodexStatusTone.NEUTRAL -> null
        CodexStatusTone.SUCCESS -> AllIcons.General.GreenCheckmark
        CodexStatusTone.BUSY -> AnimatedIcon.Default.INSTANCE
        CodexStatusTone.ERROR -> AllIcons.General.Error
    }
