package dev.piexel.codex.jetbrains

import com.intellij.ml.llm.agents.acp.api.AcpAgentStartConfig
import com.intellij.ml.llm.agents.acp.api.AcpLaunchPolicy
import com.intellij.ml.llm.agents.acp.api.AcpProcessLauncher
import com.intellij.ml.llm.agents.acp.api.AcpServerProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.provider.EelProviderUtil
import com.intellij.platform.eel.provider.LocalEelDescriptor
import dev.piexel.codex.CodexTrace
import dev.piexel.codex.runtime.CodexRuntime
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** Keep the registry Codex adapter on the same CLI as the provider at launch time. */
class CodexAcpProcessLauncher(private val project: Project) : AcpProcessLauncher {
    // JetBrains exposes the launcher interface but keeps its implementation Kotlin-internal.
    // Isolate that dependency here; the IDE contract test verifies construction and registration.
    private val delegate = AcpProcessLauncher::class.java.classLoader
        .loadClass("com.intellij.ml.llm.agents.acp.runtime.process.AcpProcessLauncherImpl")
        .getConstructor(Project::class.java).newInstance(project) as AcpProcessLauncher

    override suspend fun startProcess(
        agentName: String, config: AcpAgentStartConfig, projectDir: Path, processLifetimeCoroutineScope: CoroutineScope,
    ): AcpServerProcessHandler = delegate.startProcess(agentName, sharedRuntimeConfig(config), projectDir, processLifetimeCoroutineScope)

    override suspend fun startProcess(
        agentName: String, config: AcpAgentStartConfig, projectDir: Path, processLifetimeCoroutineScope: CoroutineScope,
        launchPolicy: AcpLaunchPolicy,
    ): AcpServerProcessHandler = delegate.startProcess(
        agentName, sharedRuntimeConfig(config), projectDir, processLifetimeCoroutineScope, launchPolicy,
    )

    private suspend fun sharedRuntimeConfig(config: AcpAgentStartConfig): AcpAgentStartConfig {
        // A locally installed executable cannot be launched inside WSL or a remote Eel environment.
        return codexSharedRuntimeConfig(config, EelProviderUtil.getEelDescriptor(project) == LocalEelDescriptor) {
            CodexRuntime.instance.executable().also { CodexTrace.info("chat-runtime-selected", "executable" to it) }
        }
    }
}

internal fun isRegistryCodexAdapter(arguments: List<String>): Boolean = arguments.any {
    it == "@agentclientprotocol/codex-acp" || it.startsWith("@agentclientprotocol/codex-acp@")
}

internal suspend fun codexSharedRuntimeConfig(
    config: AcpAgentStartConfig, isLocal: Boolean, executable: suspend () -> Path,
): AcpAgentStartConfig {
    if (!isLocal || !isRegistryCodexAdapter(config.args) || config.env.containsKey("CODEX_PATH")) return config
    return AcpAgentStartConfig.create(
        config.command, config.baseArgs, config.acpArgs,
        config.env + ("CODEX_PATH" to executable().toString()), config.workingDir,
    )
}
