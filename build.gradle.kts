import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

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
    .orElse(providers.environmentVariable("CI").map { it.equals("true", ignoreCase = true) })
    .orElse(false)
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
    }
    if (!downloadPlatform) {
        // AI Assistant's content modules define its provider extension contract.
        compileOnly(fileTree(aiAssistantPath.map { "$it/lib" }) { include("**/*.jar") })
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
        // Keep the artifact installable on future IDE builds when the binary/API
        // contract remains compatible; Plugin Verifier should catch regressions.
        ideaVersion { sinceBuild = targetBuild }
    }
    buildSearchableOptions = false
}

val platformPath = intellijPlatform.platformPath
fun platformLibrary(vararg candidates: String): File = candidates
    .asSequence()
    .map(platformPath::resolve)
    .map { it.toFile() }
    .firstOrNull(File::isFile)
    ?: error("None of the platform library candidates exist: ${candidates.joinToString()}")

val protocolRuntimeLibraries = listOf(
    platformLibrary("lib/util-8.jar"),
    platformLibrary(
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

tasks.register<JavaExec>("protocolTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    classpath(files(protocolRuntimeLibraries))
    classpath(aiAssistantPath.map { file("$it/lib/modules/intellij.ml.llm.core.jar") })
    mainClass = "dev.piexel.codex.ProtocolTestKt"
    enableAssertions = true
    systemProperty("codex.trace.disabled", "true")
}
tasks.check { dependsOn("protocolTest") }
// ProtocolTest is an executable test suite, not a JUnit test class.
tasks.test { enabled = false }
