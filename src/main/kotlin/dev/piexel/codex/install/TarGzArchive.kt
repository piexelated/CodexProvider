package dev.piexel.codex.install

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

private const val TRUNCATED_ARCHIVE_ERROR = "The Codex release archive is truncated."
private const val TAR_BLOCK_SIZE = 512
private const val NAME_OFFSET = 0
private const val NAME_LENGTH = 100
private const val SIZE_OFFSET = 124
private const val SIZE_LENGTH = 12
private const val TYPE_OFFSET = 156
private const val OCTAL_RADIX = 8
private val REGULAR_FILE_TYPES = setOf('0', 0.toChar())

/** Copies one regular file out of a gzip-compressed tar archive. Returns false when the archive has no such entry. */
internal fun extractTarGzFile(archive: Path, entryName: String, destination: Path): Boolean {
    GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { tar ->
        val header = ByteArray(TAR_BLOCK_SIZE)
        while (tar.readBlock(header)) {
            if (header.all { it == 0.toByte() }) return false
            val name = header.field(NAME_OFFSET, NAME_LENGTH)
            val size = header.field(SIZE_OFFSET, SIZE_LENGTH).trim().ifEmpty { "0" }.toLong(OCTAL_RADIX)
            val type = header[TYPE_OFFSET].toInt().toChar()
            if (name == entryName && type in REGULAR_FILE_TYPES) {
                Files.newOutputStream(destination).use { output -> tar.copyExactly(output, size) }
                return true
            }
            tar.skipExactly(size + paddingAfter(size))
        }
    }
    return false
}

private fun paddingAfter(size: Long): Long = (TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE

private fun ByteArray.field(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)

private fun InputStream.readBlock(block: ByteArray): Boolean {
    var offset = 0
    while (offset < block.size) {
        val count = read(block, offset, block.size - offset)
        if (count < 0) {
            if (offset == 0) return false
            error(TRUNCATED_ARCHIVE_ERROR)
        }
        offset += count
    }
    return true
}

private fun InputStream.copyExactly(output: OutputStream, byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(count >= 0) { TRUNCATED_ARCHIVE_ERROR }
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else check(read() >= 0) { TRUNCATED_ARCHIVE_ERROR }.also { remaining-- }
    }
}
