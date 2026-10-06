import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.piexel.codex"
version = "1.0"

val ideaVersion = providers.gradleProperty("ideaVersion").orElse("2026.2.2").get()
val aiAssistantVersion = providers.gradleProperty("aiAssistantVersion").orElse("262.10315.187").get()
val targetBuild = providers.gradleProperty("targetBuild")
    .orElse(aiAssistantVersion.split('.').take(2).joinToString("."))
    .get()
val jvmTargetVersion = providers.gradleProperty("jvmTarget").orElse("21").get().toInt()
val downloadPlatform = providers.gradleProperty("downloadPlatform")
    .map(String::toBoolean)
    .orElse(true)
    .get()
val ideaPath = providers.gradleProperty("ideaPath").orElse(
    "${System.getProperty("user.home")}/.local/share/JetBrains/Toolbox/apps/intellij-idea"
)
val aiAssistantPath = providers.gradleProperty("aiAssistantPath").orElse(
    "${System.getProperty("user.home")}/.local/share/JetBrains/IntelliJIdea2026.2/ml-llm"
)

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        if (downloadPlatform) {
            intellijIdea(ideaVersion)
            plugin("com.intellij.ml.llm", aiAssistantVersion)
        } else {
            local(ideaPath)
            localPlugin(aiAssistantPath)
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    if (!downloadPlatform) {
        // AI Assistant's content modules define its provider extension contract.
        compileOnly(fileTree(aiAssistantPath.map { "$it/lib" }) { include("**/*.jar") })
    }
}

// Older supported AI Assistant builds have no ACP launcher contract. Keep the
// text provider buildable there and include chat integration only when available.
val hasAcpLauncher = configurations.getByName("intellijPlatformPlugin").files.any { plugin ->
    plugin.walkTopDown().filter { it.isFile && it.name == "intellij.ml.llm.agents.acp.jar" }.any { jar ->
        ZipFile(jar).use { it.getEntry("com/intellij/ml/llm/agents/acp/api/AcpProcessLauncher.class") != null }
    }
}
if (!hasAcpLauncher) {
    kotlin.sourceSets.main { kotlin.exclude("**/CodexAcpProcessLauncher.kt") }
    kotlin.sourceSets.test { kotlin.exclude("**/CodexAcpCompatibilityTest.kt") }
    tasks.processResources {
        exclude("META-INF/codex-acp.xml")
        filesMatching("META-INF/plugin.xml") {
            filter { it.replace(" config-file=\"codex-acp.xml\"", "") }
        }
    }
}

kotlin {
    // Java 25 is used to run Gradle/Kotlin compilation, while the emitted
    // bytecode can target the runtime shipped by the selected IDE branch.
    jvmToolchain(25)
}
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion.toString())) }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(jvmTargetVersion)
}

intellijPlatform {
    pluginConfiguration {
        name = "Codex Provider"
        // Each archive is built and tested for one IDE branch.
        ideaVersion {
            sinceBuild = targetBuild
            untilBuild = "${targetBuild.substringBefore('.')}.*"
        }
    }
    buildSearchableOptions = false
}

val platformPath = intellijPlatform.platformPath
fun optionalPlatformLibrary(vararg candidates: String): File? = candidates
    .asSequence()
    .map(platformPath::resolve)
    .map { it.toFile() }
    .firstOrNull(File::isFile)

fun platformLibrary(vararg candidates: String): File = optionalPlatformLibrary(*candidates)
    ?: error("None of the platform library candidates exist: ${candidates.joinToString()}")

val protocolRuntimeLibraries = listOfNotNull(
    platformLibrary("lib/util-8.jar"),
    platformLibrary("lib/intellij.platform.projectModel.jar", "lib/app.jar"),
    optionalPlatformLibrary(
        "lib/intellij.libraries.gson.jar",
        "lib/module-intellij.libraries.gson.jar",
        "plugins/maven/lib/maven3/lib/gson-2.13.1.jar",
    ),
    platformLibrary(
        "lib/intellij.libraries.kotlinx.coroutines.core.jar",
        "lib/module-intellij.libraries.kotlinx.coroutines.core.jar",
        "plugins/Kotlin/kotlinc/lib/kotlinx-coroutines-core-jvm.jar",
        "plugins/Kotlin/kotlinc.ide/lib/kotlinx-coroutines-core-jvm.jar",
    ),
    platformLibrary("plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar"),
    platformLibrary("plugins/Kotlin/kotlinc/lib/kotlin-stdlib-jdk7.jar"),
    platformLibrary("plugins/Kotlin/kotlinc/lib/kotlin-stdlib-jdk8.jar"),
)

val aiAssistantRuntimeLibraries = providers.provider {
    val needed = setOf("intellij.ml.llm.core.jar", "intellij.ml.llm.libraries.grazie.jar")
    val libraries = configurations.getByName("intellijPlatformPlugin").files.flatMap { plugin ->
        plugin.walkTopDown().filter { it.isFile && it.name in needed }.toList()
    }
    check(libraries.map(File::getName).toSet() == needed) {
        "AI Assistant runtime libraries were not found for version $aiAssistantVersion"
    }
    libraries
}

/** Executable suites that run on the JDK with only the IDE libraries the protocol client needs. */
fun registerExecutableSuite(name: String, suiteDescription: String, entryPoint: String, configure: JavaExec.() -> Unit = {}) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = suiteDescription
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        classpath(files(protocolRuntimeLibraries))
        classpath(files(aiAssistantRuntimeLibraries))
        mainClass = entryPoint
        systemProperty("codex.trace.disabled", "true")
        configure()
    }

registerExecutableSuite("protocolTest", "Runs the Codex JSON-RPC protocol verification suite.", "dev.piexel.codex.ProtocolTestKt") {
    enableAssertions = true
}
registerExecutableSuite(
    "codexCliTest", "Tests a released Codex CLI app-server against the provider's protocol client.",
    "dev.piexel.codex.CodexCliCompatibilityTestKt",
) {
    val cliVersion = providers.gradleProperty("codexCliVersion")
    argumentProviders.add(CommandLineArgumentProvider { listOf(cliVersion.get()) })
}
tasks.check { dependsOn("protocolTest") }
tasks.test {
    // ProtocolTest is an executable suite run by protocolTest.
    include("**/CompatibilitySmokeTest.class")
    include("**/CodexAcpCompatibilityTest.class")
    systemProperty("java.awt.headless", "true")
    systemProperty("codex.trace.disabled", "true")
    optionalPlatformLibrary("lib/intellij.libraries.fastutil.jar", "lib/module-intellij.libraries.fastutil.jar")?.let {
        classpath = files(it) + classpath
    }
}
