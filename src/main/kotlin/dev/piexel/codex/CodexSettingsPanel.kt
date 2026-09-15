package dev.piexel.codex

import com.intellij.ide.BrowserUtil
import com.intellij.ml.llm.core.providers.ThirdPartyAIProvider
import com.intellij.ml.llm.core.providers.ThirdPartyAISettingsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JLabel
import java.net.URI

internal class CodexSettingsPanel(
    private val provider: CodexProvider,
    private val scope: CoroutineScope,
) : ThirdPartyAIProvider.SettingsUIBuilder() {
    private data class ModelChoice(val id: String?, val label: String) {
        override fun toString() = label
    }

    private data class VersionChoice(val selection: String, val label: String) {
        override fun toString() = label
    }

    private val changeListeners = mutableListOf<() -> Unit>()

    override fun buildUI(parentPanel: Panel, settings: ThirdPartyAISettingsManager): List<Row> {
        uiState = object : UIState {
            override val providerWithCurrentSettings: ThirdPartyAIProvider get() = provider
            override fun onSettingsChanged(listener: () -> Unit) { changeListeners.add(listener) }
        }
        val rows = mutableListOf<Row>()
        val codexSettings = settings as? CodexSettings ?: CodexSettings.instance
        val initialAccountStatus = CodexRuntime.instance.status.value
        val accountStatus = JLabel(initialAccountStatus.text)
        var accountAction = initialAccountStatus.action
        lateinit var accountButton: JButton
        lateinit var refreshButton: JButton
        val initialRuntimeStatus = CodexRuntime.instance.runtimeStatus.value
        val runtimeStatus = JLabel(initialRuntimeStatus.text)
        var runtimeAction = initialRuntimeStatus.action
        lateinit var runtimeButton: JButton
        lateinit var versionHelp: javax.swing.JEditorPane
        val controls = mutableListOf<java.awt.Component>()
        val versionSelector = JComboBox<VersionChoice>().apply {
            prototypeDisplayValue = VersionChoice("", "0.000.0-alpha.000")
        }
        var versionSelectorBeingUpdated = false
        val featureSelectors = mutableMapOf<CodexFeature, JComboBox<ModelChoice>>()
        val selectorsBeingUpdated = mutableSetOf<JComboBox<ModelChoice>>()
        fun refreshVersionSelector(versions: List<String>) {
            versionSelectorBeingUpdated = true
            try {
                val selected = codexSettings.runtimeVersion
                versionSelector.removeAllItems()
                versionSelector.addItem(VersionChoice(CodexRuntimeInstaller.LATEST, "Latest"))
                if (selected != CodexRuntimeInstaller.LATEST && selected !in versions) {
                    versionSelector.addItem(VersionChoice(selected, selected))
                }
                versions.forEach { version -> versionSelector.addItem(VersionChoice(version, version)) }
                versionSelector.selectedIndex = (0 until versionSelector.itemCount)
                    .firstOrNull { versionSelector.getItemAt(it).selection == selected } ?: 0
            } finally {
                versionSelectorBeingUpdated = false
            }
        }
        fun refreshSelector(feature: CodexFeature, selector: JComboBox<ModelChoice>) {
            selectorsBeingUpdated += selector
            try {
                val selected = codexSettings.featureModel(feature.id)
                selector.removeAllItems()
                selector.addItem(ModelChoice(null, feature.default.label))
                CodexRuntime.instance.models().forEach { selector.addItem(ModelChoice(it.id, it.name)) }
                selector.selectedIndex = (0 until selector.itemCount)
                    .firstOrNull { selector.getItemAt(it).id == selected } ?: 0
            } finally {
                selectorsBeingUpdated -= selector
            }
        }
        fun ui(action: () -> Unit) {
            ApplicationManager.getApplication().invokeLater { if (scope.isActive) action() }
        }
        fun perform(
            onError: (Exception) -> Unit = { error -> accountStatus.text = error.message ?: "Cannot connect to Codex." },
            action: suspend () -> Unit,
        ) {
            controls.forEach { it.isEnabled = false }
            scope.launch(Dispatchers.IO) {
                try {
                    action()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { ui { onError(error) } }
                finally { ui { controls.forEach { it.isEnabled = true } } }
            }
        }
        refreshVersionSelector(emptyList())
        versionSelector.addActionListener {
            val choice = versionSelector.selectedItem as? VersionChoice
            if (!versionSelectorBeingUpdated && choice != null && choice.selection != codexSettings.runtimeVersion) {
                codexSettings.runtimeVersion = choice.selection
                codexSettings.changed()
                perform(onError = { error -> runtimeStatus.text = error.message ?: "Could not install Codex." }) {
                    CodexRuntime.instance.installRuntime(choice.selection)
                }
            }
        }
        controls += versionSelector
        rows += parentPanel.row("Codex version:") {
            cell(versionSelector)
            cell(runtimeStatus)
            runtimeButton = button(runtimeAction?.label ?: CodexRuntimeAction.UPDATE.label) {
                if (runtimeAction != null) perform(onError = {}) {
                    CodexRuntime.instance.installRuntime(codexSettings.runtimeVersion)
                }
            }.component.apply { isVisible = runtimeAction != null }
            controls += runtimeButton
            versionHelp = comment("Latest follows new releases; choose a version to pin it.").component
        }
        rows += parentPanel.row("Account:") {
            cell(accountStatus)
            accountButton = button(accountAction.label) {
                when (accountAction) {
                    CodexAccountAction.SIGN_IN -> perform {
                        val url = CodexRuntime.instance.login()
                        val uri = URI(url)
                        check(uri.scheme == "https" && uri.host == "auth.openai.com") {
                            "Codex returned an unexpected sign-in address."
                        }
                        ui { BrowserUtil.browse(url) }
                    }
                    CodexAccountAction.SIGN_OUT -> perform { CodexRuntime.instance.logout() }
                }
            }.component
            controls += accountButton
        }
        rows += parentPanel.row {
            label("Feature model overrides")
            refreshButton = button("Refresh") {
                perform(onError = { refreshButton.text = "Retry" }) {
                    CodexRuntime.instance.refreshAccount()
                    ui { refreshButton.text = "Refresh" }
                }
            }.component.apply { isVisible = accountAction == CodexAccountAction.SIGN_OUT }
            controls += refreshButton
        }
        CodexFeatures.all.forEach { feature ->
            val selector = JComboBox<ModelChoice>()
            refreshSelector(feature, selector)
            selector.addActionListener {
                if (selector !in selectorsBeingUpdated) {
                    codexSettings.setFeatureModel(feature.id, (selector.selectedItem as? ModelChoice)?.id)
                }
            }
            featureSelectors[feature] = selector
            rows += parentPanel.row("${feature.label}:") {
                cell(selector)
                comment(feature.description)
            }
        }
        scope.launch {
            CodexRuntime.instance.status.collect { state ->
                ui {
                    accountStatus.text = state.text
                    accountAction = state.action
                    accountButton.text = state.action.label
                    refreshButton.isVisible = state.action == CodexAccountAction.SIGN_OUT
                    accountButton.revalidate()
                    refreshButton.revalidate()
                }
            }
        }
        scope.launch {
            CodexRuntime.instance.runtimeStatus.collect { state ->
                ui {
                    runtimeStatus.text = state.text
                    runtimeAction = state.action
                    runtimeButton.text = state.action?.label ?: CodexRuntimeAction.UPDATE.label
                    runtimeButton.isVisible = state.action != null
                    runtimeButton.revalidate()
                }
            }
        }
        scope.launch {
            // Starting login or refreshing the same account must not revalidate
            // unrelated model assignments. Completed sign-in/logout can change
            // the catalog asynchronously, without another button click.
            CodexRuntime.instance.modelChanges.filterNotNull().collect {
                ui {
                    featureSelectors.forEach { (feature, selector) -> refreshSelector(feature, selector) }
                    changeListeners.toList().forEach { listener -> listener() }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                CodexRuntime.instance.refreshAccount()
                CodexRuntime.instance.checkForRuntimeUpdate()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { ui { refreshButton.text = "Retry" } }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val versions = CodexRuntime.instance.runtimeVersions()
                ui {
                    refreshVersionSelector(versions)
                    versionHelp.text = "Latest follows new releases; choose a version to pin it."
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                CodexTrace.warn("runtime-version-list-failed", error)
                ui { versionHelp.text = error.message ?: "Could not load Codex versions." }
            }
        }
        return rows
    }
}
