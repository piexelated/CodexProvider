package dev.piexel.codex.jetbrains

import dev.piexel.codex.CodexTrace
import dev.piexel.codex.install.CodexRuntimeInstaller
import dev.piexel.codex.runtime.CodexRuntime
import dev.piexel.codex.runtime.CodexRuntimeAction
import dev.piexel.codex.runtime.CodexAccountAction

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
        val codexSettings = settings as? CodexSettings ?: CodexSettings.instance
        return SettingsUi(parentPanel, codexSettings).build()
    }

    private inner class SettingsUi(
        private val parentPanel: Panel,
        private val codexSettings: CodexSettings,
    ) {
        private val runtime = CodexRuntime.instance
        private val rows = mutableListOf<Row>()
        private val initialAccountStatus = runtime.status.value
        private val accountStatus = JLabel(initialAccountStatus.text)
        private var accountAction = initialAccountStatus.action
        private lateinit var accountButton: JButton
        private lateinit var refreshButton: JButton
        private val initialRuntimeStatus = runtime.runtimeStatus.value
        private val runtimeStatus = JLabel(initialRuntimeStatus.text)
        private var runtimeAction = initialRuntimeStatus.action
        private lateinit var runtimeButton: JButton
        private lateinit var versionHelp: javax.swing.JEditorPane
        private val controls = mutableListOf<java.awt.Component>()
        private val versionSelector = JComboBox<VersionChoice>().apply {
            prototypeDisplayValue = VersionChoice("", "0.000.0-alpha.000")
        }
        private var versionSelectorBeingUpdated = false
        private val featureSelectors = mutableMapOf<CodexFeature, JComboBox<ModelChoice>>()
        private val selectorsBeingUpdated = mutableSetOf<JComboBox<ModelChoice>>()

        fun build(): List<Row> {
            refreshVersionSelector(emptyList())
            addVersionRow()
            addAccountRows()
            addFeatureRows()
            addDebugRow()
            observeRuntimeState()
            loadInitialAccountState()
            loadVersions()
            return rows
        }

        private fun addVersionRow() {
            versionSelector.addActionListener { onVersionSelected() }
            controls += versionSelector
            rows += parentPanel.row("Codex version:") {
                cell(versionSelector)
                cell(runtimeStatus)
                runtimeButton = button(runtimeAction?.label ?: CodexRuntimeAction.UPDATE.label) {
                    if (runtimeAction != null) {
                        perform(onError = {}) { runtime.retryRuntime() }
                    }
                }.component.apply { isVisible = runtimeAction != null }
                controls += runtimeButton
                versionHelp = comment("Latest follows new releases; choose a version to pin it.").component
            }
        }

        private fun onVersionSelected() {
            val choice = versionSelector.selectedItem as? VersionChoice
            if (versionSelectorBeingUpdated || choice == null || choice.selection == codexSettings.runtimeVersion) return
            perform(onError = {
                refreshVersionSelector((0 until versionSelector.itemCount).map { versionSelector.getItemAt(it).selection }
                    .filter { it != CodexRuntimeInstaller.LATEST })
            }) { runtime.installRuntime(choice.selection) }
        }

        private fun addAccountRows() {
            rows += parentPanel.row("Account:") {
                cell(accountStatus)
                accountButton = button(accountAction.label) { onAccountAction() }.component
                controls += accountButton
            }
            rows += parentPanel.row {
                label("Feature model overrides")
                refreshButton = button("Refresh") {
                    perform(onError = { refreshButton.text = "Retry" }) {
                        runtime.refreshAccount()
                        ui { refreshButton.text = "Refresh" }
                    }
                }.component.apply { isVisible = accountAction == CodexAccountAction.SIGN_OUT }
                controls += refreshButton
            }
        }

        private fun onAccountAction() {
            when (accountAction) {
                CodexAccountAction.SIGN_IN -> perform {
                    val url = runtime.login()
                    val uri = URI(url)
                    check(uri.scheme == "https" && uri.host == "auth.openai.com") {
                        "Codex returned an unexpected sign-in address."
                    }
                    ui { BrowserUtil.browse(url) }
                }
                CodexAccountAction.SIGN_OUT -> perform { runtime.logout() }
            }
        }

        private fun addFeatureRows() {
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
        }

        private fun addDebugRow() {
            rows += parentPanel.row {
                checkBox("Debug mode").applyToComponent {
                    isSelected = codexSettings.debugMode
                    addActionListener { codexSettings.debugMode = isSelected }
                }
                comment("Logs model responses to idea.log. Responses may contain project code; enable only while diagnosing a problem.")
            }
        }

        private fun refreshVersionSelector(versions: List<String>) {
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

        private fun syncVersionSelection() {
            val index = (0 until versionSelector.itemCount)
                .firstOrNull { versionSelector.getItemAt(it).selection == codexSettings.runtimeVersion } ?: return
            versionSelectorBeingUpdated = true
            try { versionSelector.selectedIndex = index }
            finally { versionSelectorBeingUpdated = false }
        }

        private fun refreshSelector(feature: CodexFeature, selector: JComboBox<ModelChoice>) {
            selectorsBeingUpdated += selector
            try {
                val selected = codexSettings.featureModel(feature.id)
                selector.removeAllItems()
                selector.addItem(ModelChoice(null, feature.default.label))
                runtime.models().forEach { selector.addItem(ModelChoice(it.id, it.name)) }
                selector.selectedIndex = (0 until selector.itemCount)
                    .firstOrNull { selector.getItemAt(it).id == selected } ?: 0
            } finally {
                selectorsBeingUpdated -= selector
            }
        }

        private fun ui(action: () -> Unit) {
            ApplicationManager.getApplication().invokeLater { if (scope.isActive) action() }
        }

        private fun perform(
            onError: (Exception) -> Unit = { error -> accountStatus.text = error.message ?: "Cannot connect to Codex." },
            action: suspend () -> Unit,
        ) {
            controls.forEach { it.isEnabled = false }
            scope.launch {
                try {
                    action()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { ui { onError(error) } }
                finally { ui { controls.forEach { it.isEnabled = true } } }
            }
        }

        private fun observeRuntimeState() {
            observeAccountStatus()
            observeRuntimeStatus()
            observeModelChanges()
        }

        private fun observeAccountStatus() {
            scope.launch {
                runtime.status.collect { state ->
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
        }

        private fun observeRuntimeStatus() {
            scope.launch {
                runtime.runtimeStatus.collect { state ->
                    ui {
                        runtimeStatus.text = state.text
                        runtimeAction = state.action
                        if (state.action == null) syncVersionSelection()
                        runtimeButton.text = state.action?.label ?: CodexRuntimeAction.UPDATE.label
                        runtimeButton.isVisible = state.action != null
                        runtimeButton.revalidate()
                    }
                }
            }
        }

        private fun observeModelChanges() {
            scope.launch {
                // Starting login or refreshing the same account must not revalidate
                // unrelated model assignments. Completed sign-in/logout can change
                // the catalog asynchronously, without another button click.
                runtime.modelChanges.filterNotNull().collect {
                    ui {
                        featureSelectors.forEach { (feature, selector) -> refreshSelector(feature, selector) }
                        changeListeners.toList().forEach { listener -> listener() }
                    }
                }
            }
        }

        private fun loadInitialAccountState() {
            scope.launch {
                try {
                    runtime.refreshAccount()
                    runtime.checkForRuntimeUpdate()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { ui { refreshButton.text = "Retry" } }
            }
        }

        private fun loadVersions() {
            scope.launch {
                try {
                    val versions = runtime.runtimeVersions()
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
        }
    }
}
