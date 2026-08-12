package com.retrovault.data

import com.retrovault.application.ObservationRepository
import com.retrovault.application.Outcome
import com.retrovault.application.ResolvedObservation
import com.retrovault.application.RetroVaultFailure
import com.retrovault.application.ScanSessionRecord
import com.retrovault.application.ScanSessionRepository
import com.retrovault.application.ScanSummary
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.evidence.Evidence
import com.retrovault.domain.evidence.EvidenceStrength
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.observation.ArchiveEntryObservation
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.Candidate
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scan sessions, so a result can be explained and aged (DATABASE.md section 10). */
class SqlScanSessionRepository(
    private val database: SqlDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScanSessionRepository {

    override suspend fun start(session: ScanSessionRecord): Outcome<Unit> = write {
        database.execute(
            "INSERT OR REPLACE INTO scan_session (id, root_ref, root_display_name, started_at, " +
                "finished_at, dat_source_ids, naming_profile, resolver_version, cancelled) " +
                "VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 0)",
            listOf(
                session.id.value,
                session.rootRef.value,
                session.rootDisplayName,
                session.startedAtEpochMillis,
                session.datSourceIds.joinToString(SEPARATOR) { it.value },
                session.namingProfileVersionedId,
                session.resolverVersion,
            ),
        )
    }

    override suspend fun finish(
        id: ScanSessionId,
        summary: ScanSummary,
        cancelled: Boolean,
    ): Outcome<Unit> = write {
        database.execute(
            "UPDATE scan_session SET finished_at = ?, cancelled = ?, discovered = ?, processed = ?, " +
                "exact = ?, strong = ?, review_required = ?, ambiguous = ?, unmatched = ?, failed = ?, " +
                "hashes_computed = ?, hashing_skipped = ? WHERE id = ?",
            listOf(
                System.currentTimeMillis(),
                if (cancelled) 1L else 0L,
                summary.discovered.toLong(),
                summary.processed.toLong(),
                summary.exact.toLong(),
                summary.strong.toLong(),
                summary.reviewRequired.toLong(),
                summary.ambiguous.toLong(),
                summary.unmatched.toLong(),
                summary.failed.toLong(),
                summary.hashesComputed.toLong(),
                summary.hashingSkippedBySizeFilter.toLong(),
                id.value,
            ),
        )
    }

    override suspend fun find(id: ScanSessionId): Outcome<ScanSessionRecord> = withContext(dispatcher) {
        val record = database.queryOne(
            "SELECT id, root_ref, root_display_name, started_at, finished_at, dat_source_ids, " +
                "naming_profile, resolver_version, cancelled, discovered, processed, exact, strong, " +
                "review_required, ambiguous, unmatched, failed, hashes_computed, hashing_skipped " +
                "FROM scan_session WHERE id = ?",
            listOf(id.value),
        ) { row ->
            ScanSessionRecord(
                id = ScanSessionId(row.getString(0)),
                rootRef = StorageRef(row.getString(1)),
                rootDisplayName = row.getString(2),
                startedAtEpochMillis = row.getLong(3),
                finishedAtEpochMillis = row.getLongOrNull(4),
                datSourceIds = row.getString(5)
                    .split(SEPARATOR)
                    .filter { it.isNotEmpty() }
                    .map(::DatSourceId),
                namingProfileVersionedId = row.getString(6),
                resolverVersion = row.getString(7),
                cancelled = row.getBoolean(8),
                summary = ScanSummary(
                    discovered = row.getInt(9),
                    processed = row.getInt(10),
                    exact = row.getInt(11),
                    strong = row.getInt(12),
                    reviewRequired = row.getInt(13),
                    ambiguous = row.getInt(14),
                    unmatched = row.getInt(15),
                    failed = row.getInt(16),
                    hashesComputed = row.getInt(17),
                    hashingSkippedBySizeFilter = row.getInt(18),
                ),
            )
        }
        record?.let { Outcome.success(it) }
            ?: Outcome.failure(RetroVaultFailure.PersistenceFailure("No scan session ${id.value}"))
    }

    private suspend fun write(body: () -> Unit): Outcome<Unit> = withContext(dispatcher) {
        try {
            Outcome.success(body())
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}

/**
 * Observations and the conclusions drawn from them.
 *
 * Observation, conclusion and reason are separate tables on purpose
 * (DATABASE.md section 25): re-running identification later must be able to
 * change the conclusion without rewriting what was observed.
 */
class SqlObservationRepository(
    private val database: SqlDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ObservationRepository {

    override suspend fun saveAll(entries: List<ResolvedObservation>): Outcome<Int> =
        withContext(dispatcher) {
            try {
                database.transaction { entries.forEach(::insert) }
                Outcome.success(entries.size)
            } catch (failure: SqlFailure) {
                Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
            }
        }

    override suspend fun findBySession(id: ScanSessionId): Outcome<List<ResolvedObservation>> =
        withContext(dispatcher) {
            try {
                Outcome.success(loadSession(id))
            } catch (failure: SqlFailure) {
                Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
            }
        }

    private fun insert(entry: ResolvedObservation) {
        val observation = entry.observation
        database.execute(
            "INSERT OR REPLACE INTO file_observation (id, session_id, storage_ref, parent_ref, filename, " +
                "relative_path, size, last_modified, container, observed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                observation.id.value,
                observation.sessionId.value,
                observation.storageRef.value,
                observation.parentRef.value,
                observation.filename,
                observation.relativePath,
                observation.size,
                observation.lastModifiedEpochMillis,
                observation.container.name,
                observation.observedAtEpochMillis,
            ),
        )

        observation.hashes.asList().forEach { hash -> insertObservationHash(observation.id, "", hash) }
        observation.archiveEntries.forEach { archiveEntry ->
            database.execute(
                "INSERT OR REPLACE INTO observation_archive_entry " +
                    "(observation_id, entry_path, uncompressed_size, nested_archive) VALUES (?, ?, ?, ?)",
                listOf(
                    observation.id.value,
                    archiveEntry.entryPath,
                    archiveEntry.uncompressedSize,
                    if (archiveEntry.isNestedArchive) 1L else 0L,
                ),
            )
            archiveEntry.hashes.asList().forEach { hash ->
                insertObservationHash(observation.id, archiveEntry.entryPath, hash)
            }
        }

        val resolution = entry.resolution
        database.execute(
            "INSERT OR REPLACE INTO resolution (observation_id, state, confidence, selected_record_id, " +
                "hashes_computed, consulted_sources, resolver_version, tokenizer_version, " +
                "normalizer_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                observation.id.value,
                resolution.state.name,
                resolution.confidence.name,
                resolution.selected?.record?.id?.value,
                resolution.hashesComputed.joinToString(SEPARATOR) { it.name },
                resolution.consultedSources.joinToString(SEPARATOR) { it.value },
                resolution.resolverVersion,
                resolution.tokenizerVersion,
                resolution.normalizerVersion,
            ),
        )

        resolution.candidates.forEachIndexed { index, candidate ->
            database.execute(
                "INSERT OR REPLACE INTO resolution_candidate (observation_id, ordinal, record_id, score) " +
                    "VALUES (?, ?, ?, ?)",
                listOf(observation.id.value, index.toLong(), candidate.record.id.value, candidate.score.toLong()),
            )
        }

        var ordinal = 0
        resolution.pipelineEvidence.forEach { evidence ->
            insertEvidence(observation.id, ordinal++, SCOPE_PIPELINE, null, evidence)
        }
        resolution.candidates.forEach { candidate ->
            candidate.evidence.forEach { evidence ->
                insertEvidence(observation.id, ordinal++, SCOPE_CANDIDATE, candidate.record.id.value, evidence)
            }
        }
    }

    private fun insertObservationHash(id: ObservationId, entryPath: String, hash: HashValue) {
        database.execute(
            "INSERT OR REPLACE INTO observation_hash (observation_id, entry_path, algorithm, digest) " +
                "VALUES (?, ?, ?, ?)",
            listOf(id.value, entryPath, hash.algorithm.name, hash.hex),
        )
    }

    private fun insertEvidence(
        id: ObservationId,
        ordinal: Int,
        scope: String,
        candidateRecordId: String?,
        evidence: Evidence,
    ) {
        database.execute(
            "INSERT OR REPLACE INTO resolution_evidence (observation_id, ordinal, scope, " +
                "candidate_record_id, signal_id, strength, supports, excludes_identity, description, " +
                "source_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                id.value,
                ordinal.toLong(),
                scope,
                candidateRecordId,
                evidence.signal.id,
                evidence.strength.name,
                if (evidence.supports) 1L else 0L,
                if (evidence.signal.excludesIdentity) 1L else 0L,
                evidence.description,
                evidence.source?.id?.value,
            ),
        )
    }

    private fun loadSession(id: ScanSessionId): List<ResolvedObservation> {
        val observations = database.query(
            "SELECT id, session_id, storage_ref, parent_ref, filename, relative_path, size, " +
                "last_modified, container, observed_at FROM file_observation WHERE session_id = ? " +
                "ORDER BY relative_path, id",
            listOf(id.value),
        ) { row ->
            FileObservation(
                id = ObservationId(row.getString(0)),
                sessionId = ScanSessionId(row.getString(1)),
                storageRef = StorageRef(row.getString(2)),
                parentRef = StorageRef(row.getString(3)),
                filename = row.getString(4),
                relativePath = row.getString(5),
                size = row.getLong(6),
                lastModifiedEpochMillis = row.getLongOrNull(7),
                container = runCatching { ContainerKind.valueOf(row.getString(8)) }
                    .getOrDefault(ContainerKind.RAW),
                observedAtEpochMillis = row.getLong(9),
            )
        }
        if (observations.isEmpty()) return emptyList()

        val records = loadRecordsFor(id)
        return observations.map { observation ->
            val hydrated = observation
                .withArchiveEntries(loadArchiveEntries(observation.id))
                .let { withHashes(it) }
            ResolvedObservation(hydrated, loadResolution(hydrated, records))
        }
    }

    private fun loadArchiveEntries(id: ObservationId): List<ArchiveEntryObservation> {
        val hashes = loadHashesByEntry(id)
        return database.query(
            "SELECT entry_path, uncompressed_size, nested_archive FROM observation_archive_entry " +
                "WHERE observation_id = ? ORDER BY entry_path",
            listOf(id.value),
        ) { row ->
            val path = row.getString(0)
            ArchiveEntryObservation(
                entryPath = path,
                uncompressedSize = row.getLong(1),
                hashes = hashes[path] ?: HashDigests.EMPTY,
                isNestedArchive = row.getBoolean(2),
            )
        }
    }

    private fun withHashes(observation: FileObservation): FileObservation {
        val whole = loadHashesByEntry(observation.id)[""] ?: return observation
        return whole.asList().fold(observation) { current, hash -> current.withHash(hash) }
    }

    private fun loadHashesByEntry(id: ObservationId): Map<String, HashDigests> =
        database.query(
            "SELECT entry_path, algorithm, digest FROM observation_hash WHERE observation_id = ?",
            listOf(id.value),
        ) { row -> Triple(row.getString(0), row.getString(1), row.getString(2)) }
            .groupBy { it.first }
            .mapValues { (_, rows) ->
                rows.fold(HashDigests.EMPTY) { digests, (_, algorithm, digest) ->
                    val parsed = runCatching { HashAlgorithm.valueOf(algorithm) }.getOrNull()
                        ?.let { HashValue.parse(it, digest) }
                    if (parsed == null) digests else digests.with(parsed)
                }
            }

    /** Every catalogue record referenced by this session's resolutions, in one query. */
    private fun loadRecordsFor(id: ScanSessionId): Map<String, DumpRecord> {
        val catalogRows = database.query(
            "SELECT DISTINCT c.record_id FROM resolution_candidate c " +
                "JOIN file_observation o ON o.id = c.observation_id WHERE o.session_id = ?",
            listOf(id.value),
        ) { row -> row.getString(0) }
        if (catalogRows.isEmpty()) return emptyMap()

        val records = RecordMapper.chunked(catalogRows) { placeholders, chunk ->
            database.query(
                "SELECT r.id, r.set_name, r.rom_name, r.size, r.platform, r.canonical_title, " +
                    "r.normalized_title, r.revision, r.version, r.disc_number, r.status, r.external_id, " +
                    "r.regions, r.languages, r.flags, s.id, s.provider, s.set_name, s.version, s.platform, " +
                    "s.imported_at, s.source_digest FROM dump_record r " +
                    "JOIN dat_source s ON s.id = r.source_id WHERE r.id IN ($placeholders)",
                chunk,
            ) { row -> RecordMapper.map(row) }
        }

        val hashes = RecordMapper.loadHashes(database, records.map { it.id.value })
        return records.associate { record ->
            record.id.value to record.copy(hashes = hashes[record.id.value] ?: HashDigests.EMPTY)
        }
    }

    private fun loadResolution(
        observation: FileObservation,
        records: Map<String, DumpRecord>,
    ): ArtifactResolution {
        val header = database.queryOne(
            "SELECT state, confidence, selected_record_id, hashes_computed, consulted_sources, " +
                "resolver_version, tokenizer_version, normalizer_version FROM resolution " +
                "WHERE observation_id = ?",
            listOf(observation.id.value),
        ) { row ->
            ResolutionHeader(
                state = runCatching { ResolutionState.valueOf(row.getString(0)) }
                    .getOrDefault(ResolutionState.NO_MATCH),
                confidence = runCatching { ConfidenceLevel.valueOf(row.getString(1)) }
                    .getOrDefault(ConfidenceLevel.UNKNOWN),
                selectedRecordId = row.getStringOrNull(2),
                hashesComputed = row.getString(3).split(SEPARATOR).filter { it.isNotEmpty() },
                consultedSources = row.getString(4).split(SEPARATOR).filter { it.isNotEmpty() },
                resolverVersion = row.getString(5),
                tokenizerVersion = row.getString(6),
                normalizerVersion = row.getString(7),
            )
        } ?: return ArtifactResolution.terminal(
            observationId = observation.id,
            state = ResolutionState.NO_MATCH,
            resolverVersion = "unknown",
            tokenizerVersion = "unknown",
            normalizerVersion = "unknown",
        )

        val evidenceRows = database.query(
            "SELECT scope, candidate_record_id, signal_id, strength, supports, excludes_identity, " +
                "description FROM resolution_evidence WHERE observation_id = ? ORDER BY ordinal",
            listOf(observation.id.value),
        ) { row ->
            EvidenceRow(
                scope = row.getString(0),
                candidateRecordId = row.getStringOrNull(1),
                evidence = Evidence(
                    signal = MatchSignal.Recorded(row.getString(2), row.getBoolean(5)),
                    strength = runCatching { EvidenceStrength.valueOf(row.getString(3)) }
                        .getOrDefault(EvidenceStrength.INFORMATIONAL),
                    supports = row.getBoolean(4),
                    description = row.getString(6),
                ),
            )
        }

        val candidates = database.query(
            "SELECT record_id, score FROM resolution_candidate WHERE observation_id = ? ORDER BY ordinal",
            listOf(observation.id.value),
        ) { row -> row.getString(0) to row.getInt(1) }
            .mapNotNull { (recordId, score) ->
                val record = records[recordId] ?: return@mapNotNull null
                val forCandidate = evidenceRows
                    .filter { it.scope == SCOPE_CANDIDATE && it.candidateRecordId == recordId }
                    .map { it.evidence }
                Candidate(
                    record = record,
                    supporting = forCandidate.filter { it.supports },
                    contradicting = forCandidate.filterNot { it.supports },
                    score = score,
                )
            }

        val selected = header.selectedRecordId?.let { id -> candidates.firstOrNull { it.record.id.value == id } }
        return ArtifactResolution(
            observationId = observation.id,
            state = header.state,
            confidence = header.confidence,
            selected = selected,
            candidates = candidates,
            pipelineEvidence = evidenceRows.filter { it.scope == SCOPE_PIPELINE }.map { it.evidence },
            hashesComputed = header.hashesComputed
                .mapNotNull { name -> runCatching { HashAlgorithm.valueOf(name) }.getOrNull() }
                .toSet(),
            consultedSources = header.consultedSources.map(::DatSourceId),
            resolverVersion = header.resolverVersion,
            tokenizerVersion = header.tokenizerVersion,
            normalizerVersion = header.normalizerVersion,
        )
    }

    private data class ResolutionHeader(
        val state: ResolutionState,
        val confidence: ConfidenceLevel,
        val selectedRecordId: String?,
        val hashesComputed: List<String>,
        val consultedSources: List<String>,
        val resolverVersion: String,
        val tokenizerVersion: String,
        val normalizerVersion: String,
    )

    private data class EvidenceRow(
        val scope: String,
        val candidateRecordId: String?,
        val evidence: Evidence,
    )

    private companion object {
        const val SEPARATOR = "\u001F"
        const val SCOPE_PIPELINE = "pipeline"
        const val SCOPE_CANDIDATE = "candidate"
    }
}
