package dev.piexel.codex

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream

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
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val target: CodexRuntimeTarget = currentTarget(),
) {
    private data class Release(val version: String, val archive: URI, val sha256: String?)

    fun latestVersion(): String = findRelease(LATEST).version

    fun availableVersions(): List<String> {
        val request = HttpRequest.newBuilder(releasesApi.resolve("../git/matching-refs/tags/rust-v"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Codex-Subscription-JetBrains")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Could not load Codex versions. GitHub returned ${response.statusCode()}."
        }
        return JsonParser.parseString(response.body()).asJsonArray
            .mapNotNull { tag ->
                val ref = tag.asJsonObject.get("ref")?.asString.orEmpty()
                if (!ref.startsWith(VERSION_TAG_PREFIX)) null
                else runCatching { normalizeVersion(ref.removePrefix(VERSION_TAG_PREFIX)) }
                    .getOrNull()
                    ?.takeUnless { '-' in it }
            }
            .distinct()
            .sortedWith(
                compareByDescending<String> { it.substringBefore('.').toLong() }
                    .thenByDescending { it.substringAfter('.').substringBefore('.').toLong() }
                    .thenByDescending { it.substringAfterLast('.').toLong() },
            )
            .take(VERSION_MENU_LIMIT)
    }

    fun install(selection: String): InstalledCodex {
        val requested = normalizeSelection(selection)
        if (requested != LATEST) cached(requested)?.let { return it }
        val release = try {
            findRelease(requested)
        } catch (error: Exception) {
            if (requested == LATEST) {
                baseDirectory.resolve(".latest").readStringOrNull()?.trim()?.let(::cached)?.let { return it }
            }
            throw error
        }
        val installDirectory = baseDirectory.resolve("runtime-${release.version}")
        val executable = installDirectory.resolve("bin").resolve(target.installedExecutableName)
        if (isUsableExecutable(executable) && installDirectory.resolve(".complete").readStringOrNull() == completionMarker(release.version)) {
            if (requested == LATEST) Files.writeString(baseDirectory.resolve(".latest"), release.version)
            return InstalledCodex(release.version, executable, requested == LATEST)
        }

        Files.createDirectories(baseDirectory)
        val staging = Files.createTempDirectory(baseDirectory, "install-")
        try {
            val archive = staging.resolve("codex.tar.gz")
            val stagedExecutable = staging.resolve("bin").resolve(target.installedExecutableName)
            download(release, archive)
            extractExecutable(archive, stagedExecutable)
            Files.writeString(staging.resolve(".complete"), completionMarker(release.version))
            if (Files.exists(installDirectory)) installDirectory.toFile().deleteRecursively()
            try {
                Files.move(staging, installDirectory, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging, installDirectory)
            }
            if (requested == LATEST) Files.writeString(baseDirectory.resolve(".latest"), release.version)
            return InstalledCodex(release.version, installDirectory.resolve("bin").resolve(target.installedExecutableName), requested == LATEST)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun cached(version: String): InstalledCodex? {
        val normalized = runCatching { normalizeVersion(version) }.getOrNull() ?: return null
        val installDirectory = baseDirectory.resolve("runtime-$normalized")
        val executable = installDirectory.resolve("bin").resolve(target.installedExecutableName)
        return if (isUsableExecutable(executable) && installDirectory.resolve(".complete").readStringOrNull() == completionMarker(normalized)) {
            InstalledCodex(normalized, executable)
        } else null
    }

    private fun completionMarker(version: String): String = "$version\n${target.assetName}"

    private fun isUsableExecutable(executable: Path): Boolean =
        Files.isRegularFile(executable) && (!target.requiresExecutablePermission || Files.isExecutable(executable))

    private fun findRelease(selection: String): Release {
        val endpoint = if (selection == LATEST) releasesApi.resolve("latest")
        else releasesApi.resolve("tags/rust-v$selection")
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Codex-Subscription-JetBrains")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            if (selection == LATEST) "Could not find the latest Codex release. GitHub returned ${response.statusCode()}."
            else "Could not find Codex $selection. GitHub returned ${response.statusCode()}."
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val tag = json.get("tag_name")?.asString.orEmpty()
        check(tag.startsWith("rust-v")) { "GitHub returned an invalid Codex release tag." }
        val version = normalizeVersion(tag.removePrefix("rust-v"))
        val asset = json.getAsJsonArray("assets")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("name")?.asString == target.assetName }
            ?: error("Codex $version does not provide ${target.assetName}.")
        val archive = URI(asset.get("browser_download_url").asString)
        check(archive.scheme in setOf("https", "http") && archive.host == downloadHost) { "GitHub returned an invalid Codex download address." }
        check(releasesApi.scheme != "https" || archive.scheme == "https") { "GitHub returned an insecure Codex download address." }
        val digest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString
            ?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
        return Release(version, archive, digest)
    }

    private fun download(release: Release, destination: Path) {
        val request = HttpRequest.newBuilder(release.archive)
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "Codex-Subscription-JetBrains")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Could not download Codex ${release.version}. GitHub returned ${response.statusCode()}." }
        val digest = MessageDigest.getInstance("SHA-256")
        response.headers().firstValueAsLong("Content-Length").ifPresent { size ->
            check(size <= MAX_ARCHIVE_BYTES) { "The Codex download is unexpectedly large." }
        }
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
        GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { tar ->
            val header = ByteArray(TAR_BLOCK_SIZE)
            while (tar.readBlock(header)) {
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).cstring()
                val size = header.copyOfRange(124, 136).cstring().trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                if (name == target.archiveExecutableName && (type == 0.toChar() || type == '0')) {
                    Files.newOutputStream(destination).use { output -> tar.copyExactly(output, size) }
                    if (target.requiresExecutablePermission) destination.toFile().setExecutable(true, true)
                    check(isUsableExecutable(destination)) { "Could not make the Codex executable runnable." }
                    return
                }
                tar.skipExactly(size)
                tar.skipExactly((TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE)
            }
        }
        error("The Codex release archive does not contain ${target.archiveExecutableName}.")
    }

    companion object {
        const val LATEST = "latest"
        private const val TAR_BLOCK_SIZE = 512
        private const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024
        private const val VERSION_MENU_LIMIT = 100
        private const val VERSION_TAG_PREFIX = "refs/tags/rust-v"
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")

        fun normalizeSelection(value: String): String {
            val selection = value.trim().lowercase()
            return if (selection.isEmpty() || selection == LATEST) LATEST else normalizeVersion(selection.removePrefix("rust-v").removePrefix("v"))
        }

        internal fun targetFor(osName: String, osArch: String): CodexRuntimeTarget {
            val windows = osName.lowercase().contains("windows")
            val platform = when {
                windows -> "pc-windows-msvc"
                osName.lowercase().contains("mac") || osName.lowercase().contains("darwin") -> "apple-darwin"
                osName.lowercase().contains("linux") -> "unknown-linux-musl"
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
    }
}

private fun Path.readStringOrNull(): String? = runCatching { Files.readString(this) }.getOrNull()

private fun ByteArray.cstring(): String = takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)

private fun InputStream.readBlock(block: ByteArray): Boolean {
    var offset = 0
    while (offset < block.size) {
        val count = read(block, offset, block.size - offset)
        if (count < 0) {
            if (offset == 0) return false
            error("The Codex release archive is truncated.")
        }
        offset += count
    }
    return true
}

private fun InputStream.copyExactly(output: java.io.OutputStream, byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(count >= 0) { "The Codex release archive is truncated." }
        output.write(buffer, 0, count)
        remaining -= count
    }
    skipExactly((512 - byteCount % 512) % 512)
}

private fun InputStream.copyAtMost(output: java.io.OutputStream, maximum: Long) {
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

private fun InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else check(read() >= 0) { "The Codex release archive is truncated." }.also { remaining-- }
    }
}
