package com.retrovault.domain.observation

import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.MediaTypeVocabulary
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef

/**
 * Points at the exact bytes to hash.
 *
 * DOMAIN_MODEL.md section 7: hashing an archive and hashing the ROM inside it
 * are different observations, so every content operation names its scope.
 */
data class ArtifactContentRef(
    val storageRef: StorageRef,
    /** `null` means the file itself; otherwise the entry inside the container. */
    val archiveEntryPath: String? = null,
    /**
     * Bytes to skip before the content that carries identity begins.
     *
     * Non-zero when the file starts with a copier or console header. A
     * preservation dataset catalogues the dump *without* that prefix, so
     * hashing from byte zero would produce a digest matching nothing and
     * Constitution section 200 would be violated in the most literal way: a
     * difference of representation rejected as a difference of identity.
     */
    val byteOffset: Long = 0,
) {
    init {
        require(byteOffset >= 0) { "A content offset must not be negative" }
    }
}

/** One file inside a container, as seen without extracting it to disk. */
data class ArchiveEntryObservation(
    val entryPath: String,
    val uncompressedSize: Long,
    val hashes: HashDigests = HashDigests.EMPTY,
    /** A container inside a container. Not descended into (SECURITY_SPEC.md section 3). */
    val isNestedArchive: Boolean = false,
) {
    init {
        require(entryPath.isNotBlank()) { "Archive entry path must not be blank" }
        require(uncompressedSize >= 0) { "Archive entry size must not be negative" }
    }

    val filename: String get() = entryPath.substringAfterLast('/')
}

/**
 * What the scanner actually saw, at one point in time.
 *
 * DOMAIN_MODEL.md section 24: this observation is immutable after the scan
 * completes, except through explicit correction. Identity conclusions live
 * elsewhere so that a later change of opinion cannot destroy the evidence it
 * was based on (ARCHITECTURE.md section 7).
 */
data class FileObservation(
    val id: ObservationId,
    val sessionId: ScanSessionId,
    val storageRef: StorageRef,
    /** Directory containing the file. Rename collisions are scoped to it. */
    val parentRef: StorageRef,
    val filename: String,
    /** Display path relative to the scan root. Never used as identity. */
    val relativePath: String,
    val size: Long,
    val lastModifiedEpochMillis: Long?,
    val container: ContainerKind,
    val hashes: HashDigests = HashDigests.EMPTY,
    val archiveEntries: List<ArchiveEntryObservation> = emptyList(),
    /**
     * A prefix in front of the software, when one was recognised.
     *
     * Kept on the observation rather than resolved away, because it is
     * something that was *seen*: DOMAIN_MODEL.md section 24 makes an
     * observation a record of what the scanner found, and the header is part of
     * what this file is even though it is not part of what the file *contains*.
     */
    val header: DetectedHeader? = null,
    val observedAtEpochMillis: Long,
) {
    init {
        require(filename.isNotBlank()) { "Observed filename must not be blank" }
        require(size >= 0) { "Observed size must not be negative" }
    }

    val extension: String?
        get() = filename.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && it.length <= 8 }

    val contentRef: ArtifactContentRef get() = ArtifactContentRef(storageRef, byteOffset = headerLength)

    /** Bytes of header in front of the payload. Zero when there is none. */
    val headerLength: Long get() = header?.length ?: 0L

    fun withHash(hash: HashValue): FileObservation = copy(hashes = hashes.with(hash))

    fun withArchiveEntries(entries: List<ArchiveEntryObservation>): FileObservation =
        copy(archiveEntries = entries)

    fun withEntryHash(entryPath: String, hash: HashValue): FileObservation =
        copy(
            archiveEntries = archiveEntries.map { entry ->
                if (entry.entryPath == entryPath) entry.copy(hashes = entry.hashes.with(hash)) else entry
            },
        )

    /**
     * The entries that could plausibly be the identity-bearing ROM.
     *
     * Nested archives are excluded because this slice does not descend into
     * them, and zero-length entries are excluded because directory markers are
     * not artifacts.
     */
    val candidateArchiveEntries: List<ArchiveEntryObservation>
        get() = archiveEntries.filter { !it.isNestedArchive && it.uncompressedSize > 0 }

    /**
     * The bytes that identity should be resolved against.
     *
     * For a raw file that is the file itself. For an archive holding exactly
     * one candidate entry it is that entry. An archive holding several
     * candidates has no single identity-bearing artifact, and the resolver
     * reports that rather than choosing one (Constitution section 202).
     */
    fun identityBearingRef(): ArtifactContentRef? = when (container) {
        ContainerKind.RAW -> contentRef
        ContainerKind.ZIP -> candidateArchiveEntries.singleOrNull()
            ?.let { ArtifactContentRef(storageRef, it.entryPath) }
        ContainerKind.UNSUPPORTED_ARCHIVE -> null
    }

    /**
     * Size of the identity-bearing bytes, or `null` when there is no single
     * artifact.
     *
     * A header is subtracted, so a headered dump is looked up by the size the
     * catalogue actually records. Without this the first identification stage -
     * size filtering, Constitution section 151 - excludes every catalogued
     * record before any hash is computed, and the file falls through to being
     * identified by its filename.
     */
    fun identityBearingSize(): Long? = when (container) {
        ContainerKind.RAW -> (size - headerLength).takeIf { it > 0 }
        ContainerKind.ZIP -> candidateArchiveEntries.singleOrNull()?.uncompressedSize
        ContainerKind.UNSUPPORTED_ARCHIVE -> null
    }

    /** Hashes known for the identity-bearing bytes. */
    fun identityBearingHashes(): HashDigests = when (container) {
        ContainerKind.RAW -> hashes
        ContainerKind.ZIP -> candidateArchiveEntries.singleOrNull()?.hashes ?: HashDigests.EMPTY
        ContainerKind.UNSUPPORTED_ARCHIVE -> HashDigests.EMPTY
    }

    /**
     * Name used for filename evidence.
     *
     * For an archive the contained entry name is usually the better signal,
     * because repackaging tools rewrite the archive name far more often than
     * the entry name. Both remain recorded.
     */
    fun identityBearingName(): String = when (container) {
        ContainerKind.ZIP -> candidateArchiveEntries.singleOrNull()?.filename ?: filename
        else -> filename
    }

    fun hasHash(algorithm: HashAlgorithm): Boolean = identityBearingHashes().contains(algorithm)

    /**
     * Records digests against whatever the identity-bearing bytes are.
     *
     * For a raw file that is the file itself; for a single-entry archive it is
     * the contained entry, because hashing an archive and hashing the ROM
     * inside it are different observations (DOMAIN_MODEL.md section 7). Writing
     * both to the same field would make the archive appear to have the ROM's
     * digest, which is exactly the conflation that distinction exists to
     * prevent.
     */
    fun withIdentityBearingHashes(digests: HashDigests): FileObservation {
        if (digests.isEmpty) return this
        return when (container) {
            ContainerKind.RAW -> copy(hashes = merge(hashes, digests))
            ContainerKind.ZIP -> {
                val entry = candidateArchiveEntries.singleOrNull() ?: return this
                copy(
                    archiveEntries = archiveEntries.map { candidate ->
                        if (candidate.entryPath == entry.entryPath) {
                            candidate.copy(hashes = merge(candidate.hashes, digests))
                        } else {
                            candidate
                        }
                    },
                )
            }

            ContainerKind.UNSUPPORTED_ARCHIVE -> this
        }
    }

    private fun merge(existing: HashDigests, added: HashDigests): HashDigests =
        added.asList().fold(existing) { accumulated, hash -> accumulated.with(hash) }

    /**
     * The medium the identity-bearing bytes appear to have come from.
     *
     * Derived, not stored: it can be recomputed from the observation at any
     * time, so it never becomes a second source of truth about the file
     * (Constitution section 72). It is read from the *identity-bearing* name so
     * that `psp-game.zip` containing `Some Game.iso` is understood as a disc
     * image rather than as an archive of unknown medium.
     *
     * This is a reading of a filename and is therefore never identity. It is
     * used only to widen or explain - to say which datasets could plausibly
     * describe this file - and never to reject a candidate outright.
     */
    val mediaType: MediaType get() = MediaTypeVocabulary.forFilename(identityBearingName())
}
