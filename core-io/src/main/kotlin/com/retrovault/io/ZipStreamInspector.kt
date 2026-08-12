package com.retrovault.io

import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.observation.ArchiveEntryObservation
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Bounds on archive inspection.
 *
 * SECURITY_SPEC.md section 3: nesting depth, entry count, decompressed size,
 * filename length and metadata size are all bounded, because an archive may be
 * malicious or malformed.
 */
data class ArchiveLimits(
    val maxEntries: Int = 4_096,
    val maxEntryNameLength: Int = 512,
    val maxEntryUncompressedBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxTotalUncompressedBytes: Long = 8L * 1024 * 1024 * 1024,
)

/** Something noticed while reading an archive, without failing it. */
data class ArchiveWarning(val entryPath: String?, val message: String)

sealed interface ArchiveInspection {
    data class Inspected(
        val entries: List<ArchiveEntryObservation>,
        /** True when a limit stopped inspection before the end of the archive. */
        val truncated: Boolean,
        val warnings: List<ArchiveWarning>,
    ) : ArchiveInspection

    data class Failed(val failure: ContentFailure, val warnings: List<ArchiveWarning> = emptyList()) :
        ArchiveInspection
}

/** Maps a filename to how its bytes are packaged. */
object ContainerDetector {
    private val supportedArchives = setOf("zip")

    private val unsupportedArchives = setOf(
        "7z", "rar", "gz", "bz2", "xz", "tar", "tgz", "lzh", "lha", "arj", "cab",
    )

    fun detect(filename: String): ContainerKind {
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (extension) {
            in supportedArchives -> ContainerKind.ZIP
            in unsupportedArchives -> ContainerKind.UNSUPPORTED_ARCHIVE
            else -> ContainerKind.RAW
        }
    }
}

/**
 * Reads a ZIP without extracting it to disk.
 *
 * Constitution section 150: archive identity and contained-file identity stay
 * separate, and nothing is written to disk when streaming inspection suffices.
 *
 * CRC32 is computed while streaming rather than trusted from the entry header.
 * A streamed ZIP writes the CRC in a trailing data descriptor, so the value
 * available before reading is frequently zero - and an attacker controls it in
 * any case. Identity evidence is computed from the bytes, never read from
 * metadata.
 */
class ZipStreamInspector(private val limits: ArchiveLimits = ArchiveLimits()) {

    private companion object {
        const val ZIP_SIGNATURE_LENGTH = 4
    }

    /**
     * Lists the archive's entries, computing [algorithms] for each.
     *
     * @param input a fresh stream positioned at the start of the archive.
     */
    fun inspect(
        input: InputStream,
        algorithms: Set<HashAlgorithm> = setOf(HashAlgorithm.CRC32),
        cancellation: CancellationSignal = CancellationSignal.NONE,
    ): ArchiveInspection {
        val entries = mutableListOf<ArchiveEntryObservation>()
        val warnings = mutableListOf<ArchiveWarning>()
        var totalBytes = 0L
        var truncated = false

        // ZipInputStream reports a file that is not a ZIP as an archive with no
        // entries, which is indistinguishable from a genuinely empty archive.
        // Checking the signature keeps "this is not an archive" and "this
        // archive is empty" as different answers.
        val stream = PushbackInputStream(input, ZIP_SIGNATURE_LENGTH)
        when (readSignature(stream)) {
            SignatureCheck.NOT_AN_ARCHIVE ->
                return ArchiveInspection.Failed(ContentFailure.ReadFailed("not a ZIP archive"))

            SignatureCheck.UNREADABLE ->
                return ArchiveInspection.Failed(ContentFailure.ReadFailed("the file could not be read"))

            SignatureCheck.ZIP -> Unit
        }

        try {
            ZipInputStream(stream).use { zip ->
                while (true) {
                    cancellation.check()
                    val entry = nextEntry(zip, warnings) ?: break
                    if (entries.size >= limits.maxEntries) {
                        truncated = true
                        warnings += ArchiveWarning(null, "Stopped after ${limits.maxEntries} entries.")
                        break
                    }
                    if (entry.isDirectory) continue

                    val name = entry.name
                    if (isArchiverMetadata(name)) continue
                    if (name.length > limits.maxEntryNameLength) {
                        warnings += ArchiveWarning(null, "Skipped an entry with an unreasonably long name.")
                        continue
                    }
                    if (isUnsafePath(name)) {
                        // Nothing is extracted, so this cannot escape a
                        // directory here. It is still refused and reported,
                        // because a crafted path is a signal about the archive.
                        warnings += ArchiveWarning(name, "Skipped an entry whose path escapes the archive.")
                        continue
                    }

                    val measured = measureEntry(zip, name, algorithms, totalBytes, cancellation, warnings)
                    if (measured == null) {
                        truncated = true
                        break
                    }
                    totalBytes += measured.uncompressedSize
                    entries += measured
                }
            }
        } catch (failure: ZipException) {
            return ArchiveInspection.Failed(
                ContentFailure.ReadFailed("not a readable ZIP: ${failure.message}"),
                warnings,
            )
        } catch (failure: IOException) {
            return ArchiveInspection.Failed(
                ContentFailure.ReadFailed(failure.message ?: "I/O error"),
                warnings,
            )
        } catch (failure: SecurityException) {
            return ArchiveInspection.Failed(ContentFailure.PermissionDenied, warnings)
        }

        return ArchiveInspection.Inspected(entries, truncated, warnings)
    }

    /**
     * Computes digests for one named entry.
     *
     * Used when identification escalates past CRC32 and needs MD5 or SHA1 for a
     * contained artifact.
     */
    fun hashEntry(
        input: InputStream,
        entryPath: String,
        algorithms: Set<HashAlgorithm>,
        cancellation: CancellationSignal = CancellationSignal.NONE,
    ): HashOutcome {
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    cancellation.check()
                    val entry = zip.nextEntry ?: return HashOutcome.Failed(ContentFailure.NotFound)
                    if (entry.name != entryPath) continue
                    return StreamingHasher.hash(BoundedStream(zip, limits.maxEntryUncompressedBytes), algorithms, cancellation)
                }
            }
        } catch (failure: ZipException) {
            return HashOutcome.Failed(ContentFailure.ReadFailed("not a readable ZIP: ${failure.message}"))
        } catch (failure: IOException) {
            return HashOutcome.Failed(ContentFailure.ReadFailed(failure.message ?: "I/O error"))
        } catch (failure: SecurityException) {
            return HashOutcome.Failed(ContentFailure.PermissionDenied)
        }
    }

    /** @return `null` when a size limit was hit and inspection must stop. */
    private fun measureEntry(
        zip: ZipInputStream,
        name: String,
        algorithms: Set<HashAlgorithm>,
        totalSoFar: Long,
        cancellation: CancellationSignal,
        warnings: MutableList<ArchiveWarning>,
    ): ArchiveEntryObservation? {
        val remainingBudget = limits.maxTotalUncompressedBytes - totalSoFar
        val entryBudget = minOf(limits.maxEntryUncompressedBytes, remainingBudget)
        if (entryBudget <= 0) {
            warnings += ArchiveWarning(name, "Stopped: the archive expands beyond the size budget.")
            return null
        }

        val counting = BoundedStream(zip, entryBudget)
        return when (val outcome = StreamingHasher.hash(counting, algorithms, cancellation)) {
            is HashOutcome.Computed -> {
                if (counting.exceeded) {
                    warnings += ArchiveWarning(name, "Stopped: an entry expands beyond the size budget.")
                    null
                } else {
                    ArchiveEntryObservation(
                        entryPath = name,
                        uncompressedSize = counting.bytesRead,
                        hashes = outcome.digests,
                        isNestedArchive = ContainerDetector.detect(name) != ContainerKind.RAW,
                    )
                }
            }

            is HashOutcome.Failed -> {
                warnings += ArchiveWarning(name, "Skipped: ${outcome.failure.message}")
                ArchiveEntryObservation(
                    entryPath = name,
                    uncompressedSize = counting.bytesRead,
                    hashes = HashDigests.EMPTY,
                    isNestedArchive = ContainerDetector.detect(name) != ContainerKind.RAW,
                )
            }
        }
    }

    /**
     * Advances to the next entry, treating a corrupt entry header as the end of
     * the readable archive rather than as a crash.
     */
    private fun nextEntry(zip: ZipInputStream, warnings: MutableList<ArchiveWarning>): ZipEntry? = try {
        zip.nextEntry
    } catch (failure: ZipException) {
        warnings += ArchiveWarning(null, "The archive is damaged after this point: ${failure.message}")
        null
    }

    private enum class SignatureCheck { ZIP, NOT_AN_ARCHIVE, UNREADABLE }

    /** Peeks at the leading bytes and pushes them back for the real reader. */
    private fun readSignature(stream: PushbackInputStream): SignatureCheck {
        val header = ByteArray(ZIP_SIGNATURE_LENGTH)
        val read = try {
            stream.readNBytes(header, 0, ZIP_SIGNATURE_LENGTH)
        } catch (failure: IOException) {
            return SignatureCheck.UNREADABLE
        } catch (failure: SecurityException) {
            return SignatureCheck.UNREADABLE
        }
        if (read > 0) stream.unread(header, 0, read)
        if (read < ZIP_SIGNATURE_LENGTH) return SignatureCheck.NOT_AN_ARCHIVE
        val isZip = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            (
                // Local file header, empty-archive end record, or spanned marker.
                (header[2].toInt() == 0x03 && header[3].toInt() == 0x04) ||
                    (header[2].toInt() == 0x05 && header[3].toInt() == 0x06) ||
                    (header[2].toInt() == 0x07 && header[3].toInt() == 0x08)
                )
        return if (isZip) SignatureCheck.ZIP else SignatureCheck.NOT_AN_ARCHIVE
    }

    /**
     * True for bookkeeping written by the archiver rather than by the user.
     *
     * A ZIP made on macOS carries a `__MACOSX/._name` resource fork beside every
     * real entry, and Finder or Explorer may leave `.DS_Store` / `Thumbs.db`
     * behind. Counting those as artifacts turns a single-ROM archive into a
     * multi-entry one, which suppresses the archive's identity and makes the
     * result ambiguous for no reason. They are dropped silently, not warned
     * about: their presence says nothing about the archive.
     */
    private fun isArchiverMetadata(name: String): Boolean {
        if (name.startsWith("__MACOSX/")) return true
        val leaf = name.substringAfterLast('/')
        if (leaf.isEmpty()) return true
        if (leaf.startsWith("._")) return true
        return leaf.equals(".DS_Store", ignoreCase = true) || leaf.equals("Thumbs.db", ignoreCase = true)
    }

    /** Rejects absolute paths and traversal segments (SECURITY_SPEC.md section 2). */
    private fun isUnsafePath(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("\\")) return true
        if (name.length > 1 && name[1] == ':') return true
        return name.split('/', '\\').any { it == ".." }
    }
}

/**
 * Reads at most [limit] bytes, then reports that it stopped early.
 *
 * This is the decompression-bomb bound: a 40 GB entry inside a 4 MB archive
 * stops at the budget rather than filling memory or spinning forever.
 */
private class BoundedStream(
    private val delegate: InputStream,
    private val limit: Long,
) : InputStream() {
    var bytesRead: Long = 0
        private set

    var exceeded: Boolean = false
        private set

    override fun read(): Int {
        if (bytesRead >= limit) {
            exceeded = true
            return -1
        }
        val value = delegate.read()
        if (value >= 0) bytesRead++
        return value
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (bytesRead >= limit) {
            exceeded = true
            return -1
        }
        val allowed = minOf(length.toLong(), limit - bytesRead).toInt()
        val read = delegate.read(destination, offset, allowed)
        if (read > 0) bytesRead += read
        return read
    }

    /** The archive stream stays open for the next entry. */
    override fun close() = Unit
}
