package com.retrovault.application

import com.retrovault.domain.catalog.CatalogueCoverage
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.CorrectionSet
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.entity.EntityRef
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.PromotedIdentity
import com.retrovault.domain.entity.Release
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.naming.NormalizedTitle
import com.retrovault.domain.observation.ArchiveEntryObservation
import com.retrovault.domain.observation.ArtifactContentRef
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.rename.ArtifactState
import com.retrovault.domain.rename.DirectorySnapshot
import com.retrovault.domain.rename.RenameBatch
import com.retrovault.domain.rename.RenameOperation
import com.retrovault.domain.resolution.ArtifactResolution
import kotlinx.coroutines.flow.Flow

/**
 * Ports.
 *
 * ARCHITECTURE.md section 5: external systems are reached through interfaces
 * owned by the application boundary. Every one of these is implemented by
 * infrastructure and none of them leaks an Android, SQLite or java.io type.
 */

/** A user-authorised place to scan. */
data class StorageLocation(val ref: StorageRef, val displayName: String)

/** A file the walker found. */
data class DiscoveredFile(
    val ref: StorageRef,
    val parentRef: StorageRef,
    val name: String,
    /** Path relative to the scan root, for display only. */
    val relativePath: String,
    val size: Long,
    val lastModifiedEpochMillis: Long?,
)

sealed interface WalkEvent {
    data class FileFound(val file: DiscoveredFile) : WalkEvent

    data class DirectoryEntered(val ref: StorageRef, val relativePath: String) : WalkEvent

    /**
     * One location could not be read.
     *
     * ARCHITECTURE.md section 14: one bad file must not terminate a whole scan,
     * so this is an event rather than an exception.
     */
    data class Failed(val relativePath: String, val failure: RetroVaultFailure) : WalkEvent
}

/**
 * Recursive traversal of user storage.
 *
 * Implementations must stream (ROM_INTELLIGENCE.md section 15) and must
 * respect coroutine cancellation.
 */
interface DirectoryWalker {
    fun walk(root: StorageLocation): Flow<WalkEvent>
}

/** Reading bytes and container structure. */
interface ContentSource {
    /** Computes digests for a file or for one entry inside it. */
    suspend fun computeHashes(
        ref: ArtifactContentRef,
        algorithms: Set<HashAlgorithm>,
    ): Outcome<HashDigests>

    /** Lists what a container holds, without extracting it. */
    suspend fun inspectArchive(ref: StorageRef): Outcome<List<ArchiveEntryObservation>>

    /** Re-reads a file's current state, for staleness and permission checks. */
    suspend fun stat(ref: StorageRef): Outcome<ArtifactState>

    /** Lists the names currently present in a directory, for collision checks. */
    suspend fun listNames(directory: StorageRef): Outcome<DirectorySnapshot>
}

/** Performs the one mutation this slice is allowed to make. */
interface RenameExecutor {
    /**
     * Renames [ref] to [newName] within its current directory.
     *
     * Implementations must not move the file, create directories, or overwrite
     * an existing destination.
     */
    suspend fun rename(ref: StorageRef, newName: String): Outcome<StorageRef>
}

/**
 * Indexed catalogue lookup.
 *
 * Contract, relied on by [ResolveArtifactUseCase]:
 * - [findBySize] returns every record with exactly that byte size.
 * - [findByHash] returns every record whose digest for that algorithm matches.
 * - [findByNormalizedTitle] returns candidates that share at least one token
 *   with the query. It is a recall-oriented index; scoring, ranking and
 *   rejection are the resolver's job, never the index's.
 * - [coverage] reports what each ready dataset actually indexes, measured from
 *   its records rather than assumed from its name.
 */
interface DumpCatalog {
    suspend fun findBySize(size: Long): List<DumpRecord>

    suspend fun findByHash(hash: HashValue): List<DumpRecord>

    suspend fun findByNormalizedTitle(title: NormalizedTitle): List<DumpRecord>

    /** Datasets currently indexed, for reproducibility of a scan. */
    suspend fun sources(): List<DatSourceRef>

    /**
     * What the indexed datasets describe, per dataset.
     *
     * Read once per scan and handed to the resolver so that "no dataset covers
     * optical discs" can be told apart from "the disc is not listed"
     * (Constitution section 174).
     */
    suspend fun coverage(): CatalogueCoverage
}

/**
 * The canonical entity graph.
 *
 * Entities are *projected* from catalogue evidence rather than being a second
 * copy of it (Constitution section 145), so writing is idempotent by
 * construction: promoting the same identity twice produces the same rows.
 *
 * Contract:
 * - [save] never downgrades an entity a user confirmed to a derived one
 *   (Constitution section 43: automation proposes, people establish).
 * - [recordsForRelease] returns the catalogue records that describe a release,
 *   which is how a correction naming a release becomes an identity again.
 */
interface EntityGraph {
    suspend fun save(identity: PromotedIdentity): Outcome<Unit>

    suspend fun findRelease(id: ReleaseId): Outcome<Release?>

    suspend fun recordsForRelease(id: ReleaseId): List<DumpRecord>

    suspend fun relationshipsFrom(ref: EntityRef): Outcome<List<EntityRelationship>>

    /** Asserts an edge the user established, e.g. a port or remake (section 32). */
    suspend fun relate(relationship: EntityRelationship): Outcome<Unit>
}

/**
 * Durable user corrections.
 *
 * Append-only. Superseding a correction writes a new row and marks the old one
 * superseded, because Constitution section 69 forbids silently rewriting
 * history and section 70 requires earlier knowledge to stay reconstructable.
 */
interface CorrectionStore {
    /** Records a correction, superseding any active one for the same content. */
    suspend fun record(correction: IdentityCorrection): Outcome<IdentityCorrection>

    /** Marks the active correction for this content withdrawn. */
    suspend fun withdraw(scope: CorrectionScope): Outcome<Unit>

    /** Every correction ever made for this content, newest first. */
    suspend fun history(scope: CorrectionScope): Outcome<List<IdentityCorrection>>

    /**
     * Every active correction, for a scan to apply.
     *
     * Read once per scan. Corrections are authored by hand, so the set is small
     * enough to hold; a library with more corrections than files has bigger
     * problems than memory.
     */
    suspend fun active(): CorrectionSet
}

/** Writing an imported dataset. */
interface DatCatalogWriter {
    /** Registers a dataset, replacing any previous import of the same source id. */
    suspend fun beginImport(source: DatSourceRef): Outcome<DatSourceId>

    /** Persists a bounded batch of records inside one transaction. */
    suspend fun writeBatch(sourceId: DatSourceId, records: List<DumpRecord>): Outcome<Int>

    suspend fun commitImport(sourceId: DatSourceId): Outcome<Unit>

    suspend fun rollbackImport(sourceId: DatSourceId): Outcome<Unit>
}

/** Scan sessions, for reproducibility and stale-result detection. */
interface ScanSessionRepository {
    suspend fun start(session: ScanSessionRecord): Outcome<Unit>

    suspend fun finish(id: ScanSessionId, summary: ScanSummary, cancelled: Boolean): Outcome<Unit>

    suspend fun find(id: ScanSessionId): Outcome<ScanSessionRecord>
}

/** What a scan was, so its results can be explained later (DATABASE.md section 10). */
data class ScanSessionRecord(
    val id: ScanSessionId,
    val rootRef: StorageRef,
    val rootDisplayName: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?,
    val datSourceIds: List<DatSourceId>,
    val namingProfileVersionedId: String,
    val resolverVersion: String,
    val cancelled: Boolean = false,
    val summary: ScanSummary = ScanSummary(),
)

/** Observations and their resolutions. */
interface ObservationRepository {
    suspend fun saveAll(entries: List<ResolvedObservation>): Outcome<Int>

    suspend fun findBySession(id: ScanSessionId): Outcome<List<ResolvedObservation>>
}

/** An observation together with what RetroVault concluded about it. */
data class ResolvedObservation(
    val observation: FileObservation,
    val resolution: ArtifactResolution,
)

/** Durable rename intent (DATABASE.md section 11). */
interface RenameJournalRepository {
    suspend fun createBatch(batch: RenameBatch): Outcome<Unit>

    suspend fun updateOperation(operation: RenameOperation): Outcome<Unit>

    suspend fun findBatch(id: RenameBatchId): Outcome<RenameBatch>

    /** Batches with operations that never reached a terminal state. */
    suspend fun findUnfinishedBatches(): Outcome<List<RenameBatch>>

    /**
     * Recent batches, newest first, for the history a user can read.
     *
     * Bounded: Constitution section 249 requires memory to stay bounded, and a
     * library renamed weekly for a year is a lot of batches. A journal nobody
     * can read is only half of section 170, so this is what makes the audit
     * trail reachable rather than merely stored.
     */
    suspend fun findRecentBatches(limit: Int = 50): Outcome<List<RenameBatch>>
}

/** Time, injected so results are reproducible in tests. */
fun interface Clock {
    fun nowEpochMillis(): Long
}

/** Identifier generation, injected for the same reason. */
fun interface IdGenerator {
    fun next(prefix: String): String
}

/** Running totals for a scan (UX_SPEC.md section 4). */
data class ScanSummary(
    val discovered: Int = 0,
    val processed: Int = 0,
    val exact: Int = 0,
    val strong: Int = 0,
    val reviewRequired: Int = 0,
    val ambiguous: Int = 0,
    /**
     * Files the datasets cover but do not list.
     *
     * Kept apart from [outOfCatalogueScope] because the two call for different
     * action: this one may mean a bad dump or a release nobody has catalogued,
     * while the other means the right dataset has not been imported.
     */
    val unmatched: Int = 0,
    /** Files no imported dataset covers the medium of. */
    val outOfCatalogueScope: Int = 0,
    val failed: Int = 0,
    val hashesComputed: Int = 0,
    val hashingSkippedBySizeFilter: Int = 0,
)
