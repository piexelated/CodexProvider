package dev.piexel.codex.install

import com.google.gson.JsonParser
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration

private const val CODEX_USER_AGENT = "Codex-Subscription-JetBrains"
private const val USER_AGENT_HEADER = "User-Agent"
private const val GITHUB_JSON = "application/vnd.github+json"
private const val LATEST_POINTER_FILE = ".latest"
private const val INSTALL_COMPLETION_MARKER_FILE = ".complete"
private const val CODEX_RELEASE_TAG_PREFIX = "rust-v"
private const val GITHUB_TAG_REF_PREFIX = "refs/tags/"
private const val SHA256_DIGEST_PREFIX = "sha256:"
private const val HTTP_OK = 200
private val CONNECT_TIMEOUT = Duration.ofSeconds(20)
private val API_TIMEOUT = Duration.ofSeconds(30)
private val DOWNLOAD_TIMEOUT = Duration.ofMinutes(5)

internal data class InstalledCodex(val version: String, val executable: Path, val isLatest: Boolean = false)

internal data class CodexRuntimeTarget(
    val assetName: String,
    val archiveExecutableName: String,
    val installedExecutableName: String,
    val requiresExecutablePermission: Boolean,
)

/** Downloads and caches one official Codex release behind a single install interface. */
internal class CodexRuntimeInstaller(
    private val baseDirectory: Path,
    private val releasesApi: URI = URI("https://api.github.com/repos/openai/codex/releases/"),
    private val downloadHost: String = "github.com",
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val target: CodexRuntimeTarget = currentTarget(),
) {
    private data class Release(val version: String, val archive: URI, val sha256: String?)

    fun latestVersion(): String = findRelease(LATEST).version

    fun availableVersions(): List<String> {
        val response = requestGitHubJson(releasesApi.resolve("../git/matching-refs/tags/$CODEX_RELEASE_TAG_PREFIX"))
        check(response.statusCode() == HTTP_OK) {
            "Could not load Codex versions. GitHub returned ${response.statusCode()}."
        }
        val tagPrefix = GITHUB_TAG_REF_PREFIX + CODEX_RELEASE_TAG_PREFIX
        return JsonParser.parseString(response.body()).asJsonArray
            .mapNotNull { tag -> tag.asJsonObject.get("ref")?.asString?.takeIf { it.startsWith(tagPrefix) } }
            .mapNotNull { ref -> runCatching { normalizeVersion(ref.removePrefix(tagPrefix)) }.getOrNull() }
            .filterNot { '-' in it }
            .distinct()
            .sortedWith(NEWEST_RELEASE_FIRST)
            .take(VERSION_MENU_LIMIT)
    }

    /** Resolving `latest` never moves the offline fallback; see [rememberLatestVersion]. */
    fun install(selection: String): InstalledCodex {
        val requested = normalizeSelection(selection)
        val followsLatest = requested == LATEST
        if (!followsLatest) cached(requested)?.let { return it }
        val release = try {
            findRelease(requested)
        } catch (error: Exception) {
            if (followsLatest) rememberedLatest()?.let { return it }
            throw error
        }
        cached(release.version)?.let { return it.copy(isLatest = followsLatest) }
        downloadAndInstall(release)
        return InstalledCodex(release.version, executableIn(installDirectory(release.version)), followsLatest)
    }

    /** The IDE advances its offline fallback only after the candidate session has been validated. */
    fun rememberLatestVersion(version: String) {
        val normalized = normalizeVersion(version)
        check(cached(normalized) != null) { "Cannot select an incomplete Codex installation." }
        Files.writeString(baseDirectory.resolve(LATEST_POINTER_FILE), normalized)
    }

    private fun rememberedLatest(): InstalledCodex? =
        baseDirectory.resolve(LATEST_POINTER_FILE).readStringOrNull()?.trim()?.let(::cached)

    private fun cached(version: String): InstalledCodex? {
        val normalized = runCatching { normalizeVersion(version) }.getOrNull() ?: return null
        val installDirectory = installDirectory(normalized)
        val executable = executableIn(installDirectory)
        val complete = installDirectory.resolve(INSTALL_COMPLETION_MARKER_FILE).readStringOrNull() == completionMarker(normalized)
        return if (complete && isUsableExecutable(executable)) InstalledCodex(normalized, executable) else null
    }

    private fun installDirectory(version: String): Path = baseDirectory.resolve("runtime-$version")

    private fun executableIn(installDirectory: Path): Path = installDirectory.resolve("bin").resolve(target.installedExecutableName)

    private fun completionMarker(version: String): String = "$version\n${target.assetName}"

    private fun isUsableExecutable(executable: Path): Boolean =
        Files.isRegularFile(executable) && (!target.requiresExecutablePermission || Files.isExecutable(executable))

    private fun downloadAndInstall(release: Release) {
        Files.createDirectories(baseDirectory)
        val staging = Files.createTempDirectory(baseDirectory, "install-")
        try {
            val archive = staging.resolve("codex.tar.gz")
            download(release, archive)
            extractExecutable(archive, executableIn(staging))
            Files.writeString(staging.resolve(INSTALL_COMPLETION_MARKER_FILE), completionMarker(release.version))
            publishInstall(staging, installDirectory(release.version))
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun publishInstall(staging: Path, installDirectory: Path) {
        if (Files.exists(installDirectory)) installDirectory.toFile().deleteRecursively()
        try {
            Files.move(staging, installDirectory, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, installDirectory)
        }
    }

    private fun findRelease(selection: String): Release {
        val endpoint = if (selection == LATEST) releasesApi.resolve("latest")
        else releasesApi.resolve("tags/$CODEX_RELEASE_TAG_PREFIX$selection")
        val response = requestGitHubJson(endpoint)
        check(response.statusCode() == HTTP_OK) {
            if (selection == LATEST) "Could not find the latest Codex release. GitHub returned ${response.statusCode()}."
            else "Could not find Codex $selection. GitHub returned ${response.statusCode()}."
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val tag = json.get("tag_name")?.asString.orEmpty()
        check(tag.startsWith(CODEX_RELEASE_TAG_PREFIX)) { "GitHub returned an invalid Codex release tag." }
        val version = normalizeVersion(tag.removePrefix(CODEX_RELEASE_TAG_PREFIX))
        val asset = json.getAsJsonArray("assets")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString == target.assetName }
            ?: error("Codex $version does not provide ${target.assetName}.")
        val archive = URI(asset.get("browser_download_url").asString)
        check(archive.scheme in setOf("https", "http") && archive.host == downloadHost) { "GitHub returned an invalid Codex download address." }
        check(releasesApi.scheme != "https" || archive.scheme == "https") { "GitHub returned an insecure Codex download address." }
        val digest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString
            ?.takeIf { it.startsWith(SHA256_DIGEST_PREFIX) }
            ?.removePrefix(SHA256_DIGEST_PREFIX)
        return Release(version, archive, digest)
    }

    private fun requestGitHubJson(endpoint: URI): HttpResponse<String> {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(API_TIMEOUT)
            .header("Accept", GITHUB_JSON)
            .header(USER_AGENT_HEADER, CODEX_USER_AGENT)
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun download(release: Release, destination: Path) {
        val request = HttpRequest.newBuilder(release.archive)
            .timeout(DOWNLOAD_TIMEOUT)
            .header(USER_AGENT_HEADER, CODEX_USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == HTTP_OK) { "Could not download Codex ${release.version}. GitHub returned ${response.statusCode()}." }
        response.headers().firstValueAsLong("Content-Length").ifPresent { size ->
            check(size <= MAX_ARCHIVE_BYTES) { "The Codex download is unexpectedly large." }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { body ->
            DigestInputStream(body, digest).use { input ->
                Files.newOutputStream(destination).use { output -> input.copyAtMost(output, MAX_ARCHIVE_BYTES) }
            }
        }
        release.sha256?.let { expected ->
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expected, ignoreCase = true)) { "The Codex download checksum does not match its release metadata." }
        }
    }

    private fun extractExecutable(archive: Path, destination: Path) {
        Files.createDirectories(destination.parent)
        check(extractTarGzFile(archive, target.archiveExecutableName, destination)) {
            "The Codex release archive does not contain ${target.archiveExecutableName}."
        }
        if (target.requiresExecutablePermission) {
            val executable = destination.toFile()
            check(executable.setExecutable(true, true) || executable.canExecute()) {
                "Could not make the Codex executable runnable."
            }
        }
        check(isUsableExecutable(destination)) { "Could not make the Codex executable runnable." }
    }

    companion object {
        const val LATEST = "latest"
        private const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024
        private const val VERSION_MENU_LIMIT = 100
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
        private val NEWEST_RELEASE_FIRST = compareByDescending<String> { it.versionPart(0) }
            .thenByDescending { it.versionPart(1) }
            .thenByDescending { it.versionPart(2) }

        fun normalizeSelection(value: String): String {
            val selection = value.trim().lowercase()
            return if (selection.isEmpty() || selection == LATEST) LATEST else normalizeVersion(selection.removePrefix(CODEX_RELEASE_TAG_PREFIX).removePrefix("v"))
        }

        internal fun targetFor(osName: String, osArch: String): CodexRuntimeTarget {
            val os = osName.lowercase()
            val windows = "windows" in os
            val platform = when {
                windows -> "pc-windows-msvc"
                "mac" in os || "darwin" in os -> "apple-darwin"
                "linux" in os -> "unknown-linux-musl"
                else -> error("Codex runtime downloads do not support $osName.")
            }
            val architecture = when (osArch.lowercase()) {
                "amd64", "x86_64", "x64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> error("Codex runtime downloads do not support the $osArch architecture on $osName.")
            }
            val archiveExecutableName = "codex-$architecture-$platform${if (windows) ".exe" else ""}"
            return CodexRuntimeTarget(
                assetName = "$archiveExecutableName.tar.gz",
                archiveExecutableName = archiveExecutableName,
                installedExecutableName = if (windows) "codex.exe" else "codex",
                requiresExecutablePermission = !windows,
            )
        }

        private fun currentTarget(): CodexRuntimeTarget = targetFor(
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
        )

        private fun normalizeVersion(version: String): String {
            require(VERSION.matches(version)) { "Enter 'latest' or a Codex version such as 0.154.0." }
            return version
        }

        /** Only stable releases reach the version menu, so every part is numeric. */
        private fun String.versionPart(index: Int): Long = split('.')[index].toLong()
    }
}

private fun Path.readStringOrNull(): String? = runCatching { Files.readString(this) }.getOrNull()

private fun InputStream.copyAtMost(output: OutputStream, maximum: Long) {
    var copied = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        copied += count
        check(copied <= maximum) { "The Codex download is unexpectedly large." }
        output.write(buffer, 0, count)
    }
}
