package dev.piexel.codex

import com.intellij.openapi.project.ProjectManager
import com.intellij.ml.llm.agents.acp.api.AcpProcessLauncher
import com.intellij.ml.llm.agents.acp.api.AcpAgentStartConfig
import dev.piexel.codex.jetbrains.CodexAcpProcessLauncher
import dev.piexel.codex.jetbrains.codexSharedRuntimeConfig
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class CodexAcpCompatibilityTest : UsefulTestCase() {
    fun testLauncherRegistration() {
        TestApplicationManager.getInstance()
        check(ProjectManager.getInstance().defaultProject.getService(AcpProcessLauncher::class.java) is CodexAcpProcessLauncher)
    }
    fun testRuntimeHandoff() = runBlocking {
        val config = AcpAgentStartConfig.create(
            "npx", listOf("--yes", "@agentclientprotocol/codex-acp@2.0.0"), listOf("--example"),
            mapOf("EXAMPLE" to "preserved"), Path.of(System.getProperty("java.io.tmpdir")),
        )
        for (version in listOf("0.158.0", "0.159.2")) {
            val executable = Path.of(System.getProperty("java.io.tmpdir"), "runtime-$version", "bin", "codex")
            val updated = codexSharedRuntimeConfig(config, true) { executable }
            check(updated.env == config.env + ("CODEX_PATH" to executable.toString()))
            check(updated.command == config.command && updated.args == config.args && updated.workingDir == config.workingDir)
        }
        check(codexSharedRuntimeConfig(config, false) { error("Must not install a local CLI for a remote agent") } === config)
        val custom = AcpAgentStartConfig.create("npx", listOf("another-agent"), emptyList(), emptyMap())
        check(codexSharedRuntimeConfig(custom, true) { error("Must not install for another agent") } === custom)
        val explicit = AcpAgentStartConfig.create(
            config.command, config.baseArgs, config.acpArgs, mapOf("CODEX_PATH" to "custom-codex"), config.workingDir,
        )
        check(codexSharedRuntimeConfig(explicit, true) { error("Must preserve explicit overrides") } === explicit)
    }
}
