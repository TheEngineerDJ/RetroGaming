package com.retrovault.data

import com.retrovault.application.Outcome
import com.retrovault.application.ResolvedObservation
import com.retrovault.application.ScanSessionRecord
import com.retrovault.application.ScanSummary
import com.retrovault.data.jdbc.JdbcSqlDatabase
import com.retrovault.domain.catalog.DatSourceRef
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
import com.retrovault.domain.identity.PlanEntryId
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.RenameOperationId
import com.retrovault.domain.identity.RenamePlanId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.observation.ArchiveEntryObservation
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.rename.RenameBatch
import com.retrovault.domain.rename.RenameFailure
import com.retrovault.domain.rename.RenameOperation
import com.retrovault.domain.rename.RenameOperationState
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.Candidate
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Persistence behaviour.
 *
 * DATABASE.md section 22 requires coverage of fresh install, migrations,
 * foreign keys, duplicate entities, DAT re-import and interrupted renames.
 */
class PersistenceTest {

    private lateinit var database: JdbcSqlDatabase
    private lateinit var catalog: SqlDumpCatalog
    private lateinit var observations: SqlObservationRepository
    private lateinit var sessions: SqlScanSessionRepository
    private lateinit var journal: SqlRenameJournalRepository

    private val source = DatSourceRef(
        id = DatSourceId("no_intro:Test Console:1"),
        provider = "no_intro",
        setName = "Test Console",
        version = "1",
        platform = PlatformName("Test Console"),
        importedAtEpochMillis = 1_700_000_000_000L,
    )

    @BeforeTest
    fun setUp() {
        database = JdbcSqlDatabase.inMemory()
        Schema.migrate(database)
        catalog = SqlDumpCatalog(database)
        observations = SqlObservationRepository(database)
        sessions = SqlScanSessionRepository(database)
        journal = SqlRenameJournalRepository(database)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun record(
        setName: String,
        size: Long = 4096,
        crc: String = "aabbccdd",
        sha1: String? = null,
        id: String = "record-$setName",
        from: DatSourceRef = source,
    ): DumpRecord = DumpRecord.derive(
        id = DumpRecordId(id),
        source = from,
        setName = setName,
        romName = "$setName.sfc",
        size = size,
        hashes = HashDigests.of(
            *listOfNotNull(
                HashValue.of(HashAlgorithm.CRC32, crc),
                sha1?.let { HashValue.of(HashAlgorithm.SHA1, it) },
            ).toTypedArray(),
        ),
    )

    private suspend fun importReady(vararg records: DumpRecord, from: DatSourceRef = source) {
        assertIs<Outcome.Success<*>>(catalog.beginImport(from))
        assertIs<Outcome.Success<*>>(catalog.writeBatch(from.id, records.toList()))
        assertIs<Outcome.Success<*>>(catalog.commitImport(from.id))
    }

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    @Test
    fun `a fresh database is migrated to the current version`() {
        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
    }

    @Test
    fun `migration is idempotent`() {
        val previous = Schema.migrate(database)
        assertEquals(Schema.CURRENT_VERSION, previous, "The database was already current")
        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
    }

    @Test
    fun `foreign keys are enforced`() {
        val failure = runCatching {
            database.execute(
                "INSERT INTO dump_record (id, source_id, set_name, rom_name, size, platform, " +
                    "canonical_title, normalized_title, status, regions, languages, flags) " +
                    "VALUES ('x', 'missing-source', 'a', 'b', 1, 'p', 't', 't', 'GOOD', '', '', '')",
            )
        }
        assertTrue(failure.isFailure, "A record must not reference a dataset that does not exist")
    }

    // ------------------------------------------------------------------
    // Catalogue
    // ------------------------------------------------------------------

    @Test
    fun `records are found by size, hash and title`() = runTest {
        importReady(record("Super Mario World (USA)", sha1 = "b".repeat(40)))

        assertEquals(1, catalog.findBySize(4096).size)
        assertEquals(1, catalog.findByHash(HashValue.of(HashAlgorithm.CRC32, "aabbccdd")).size)
        assertEquals(1, catalog.findByHash(HashValue.of(HashAlgorithm.SHA1, "b".repeat(40))).size)
        assertEquals(
            1,
            catalog.findByNormalizedTitle(
                com.retrovault.domain.naming.TitleNormalizer.normalize("Super Mario World"),
            ).size,
        )
    }

    @Test
    fun `a record round-trips with its hashes, regions and flags intact`() = runTest {
        val original = record("Some Game (USA, Europe) (Rev A) (Beta)", sha1 = "c".repeat(40))
        importReady(original)

        val loaded = catalog.findBySize(4096).single()

        assertEquals(original.id, loaded.id)
        assertEquals(original.hashes.asList(), loaded.hashes.asList())
        assertEquals(original.regions, loaded.regions)
        assertEquals(original.flags, loaded.flags)
        assertEquals(original.revision, loaded.revision)
        assertEquals(original.normalizedTitle, loaded.normalizedTitle)
        assertEquals(original.canonicalIdentityKey, loaded.canonicalIdentityKey)
    }

    @Test
    fun `an uncommitted import is invisible to lookups`() = runTest {
        catalog.beginImport(source)
        catalog.writeBatch(source.id, listOf(record("Half Imported (USA)")))

        assertTrue(
            catalog.findBySize(4096).isEmpty(),
            "A dataset that has not committed must not influence identification",
        )

        catalog.commitImport(source.id)
        assertEquals(1, catalog.findBySize(4096).size)
    }

    @Test
    fun `a rolled back import leaves nothing behind`() = runTest {
        catalog.beginImport(source)
        catalog.writeBatch(source.id, listOf(record("Rolled Back (USA)")))
        catalog.rollbackImport(source.id)

        assertTrue(catalog.findBySize(4096).isEmpty())
        assertTrue(catalog.sources().isEmpty())
        assertEquals(
            0L,
            database.query("SELECT COUNT(*) FROM dump_record") { it.getLong(0) }.single(),
            "Cascade delete must remove the orphaned records",
        )
    }

    @Test
    fun `re-importing the same dataset replaces it rather than duplicating`() = runTest {
        importReady(record("Old Name (USA)", id = "record-1"))
        importReady(record("New Name (USA)", id = "record-2"))

        val all = catalog.findBySize(4096)
        assertEquals(1, all.size, "The second import replaces the first")
        assertEquals("New Name", all.single().canonicalTitle)
    }

    @Test
    fun `two datasets describing the same dump are both retained`() = runTest {
        val redump = source.copy(
            id = DatSourceId("redump:Test Console:1"),
            provider = "redump",
        )
        importReady(record("Shared Game (USA)", sha1 = "d".repeat(40), id = "no-intro-1"))
        importReady(
            record("Shared Game (USA)", sha1 = "d".repeat(40), id = "redump-1", from = redump),
            from = redump,
        )

        val found = catalog.findByHash(HashValue.of(HashAlgorithm.SHA1, "d".repeat(40)))

        assertEquals(2, found.size, "Independent corroboration must not be flattened")
        assertEquals(setOf("no_intro", "redump"), found.map { it.source.provider }.toSet())
    }

    @Test
    fun `title lookup returns nothing for an unknown title`() = runTest {
        importReady(record("Known Game (USA)"))

        assertTrue(
            catalog.findByNormalizedTitle(
                com.retrovault.domain.naming.TitleNormalizer.normalize("Completely Different"),
            ).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Observations and resolutions
    // ------------------------------------------------------------------

    private fun observation(id: String, sessionId: ScanSessionId) = FileObservation(
        id = ObservationId(id),
        sessionId = sessionId,
        storageRef = StorageRef("mem:/roms/$id.zip"),
        parentRef = StorageRef("mem:/roms"),
        filename = "$id.zip",
        relativePath = "roms/$id.zip",
        size = 2048,
        lastModifiedEpochMillis = 1_700_000_000_000L,
        container = ContainerKind.ZIP,
        hashes = HashDigests.of(HashValue.of(HashAlgorithm.CRC32, "12345678")),
        archiveEntries = listOf(
            ArchiveEntryObservation(
                entryPath = "inner.sfc",
                uncompressedSize = 4096,
                hashes = HashDigests.of(HashValue.of(HashAlgorithm.CRC32, "aabbccdd")),
            ),
        ),
        observedAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun `an observation and its resolution round-trip with evidence intact`() = runTest {
        val sessionId = ScanSessionId("session-1")
        sessions.start(
            ScanSessionRecord(
                id = sessionId,
                rootRef = StorageRef("mem:/roms"),
                rootDisplayName = "Roms",
                startedAtEpochMillis = 1_700_000_000_000L,
                finishedAtEpochMillis = null,
                datSourceIds = listOf(source.id),
                namingProfileVersionedId = "no-intro@v1",
                resolverVersion = "artifact-resolver-v1",
            ),
        )
        val catalogued = record("Packed Game (USA)", sha1 = "e".repeat(40))
        importReady(catalogued)

        val candidate = Candidate(
            record = catalogued,
            supporting = listOf(
                Evidence.supporting(
                    MatchSignal.HashExact(HashAlgorithm.SHA1),
                    EvidenceStrength.DECISIVE,
                    "SHA1 matches the catalogued dump exactly.",
                    source = source,
                ),
            ),
            contradicting = listOf(
                Evidence.contradicting(
                    MatchSignal.RegionConflict(emptyList(), emptyList()),
                    EvidenceStrength.MODERATE,
                    "Region token disagrees.",
                ),
            ),
            score = 100,
        )
        val resolution = ArtifactResolution(
            observationId = ObservationId("obs-1"),
            state = ResolutionState.EXACT_HASH,
            confidence = ConfidenceLevel.EXACT,
            selected = candidate,
            candidates = listOf(candidate),
            pipelineEvidence = listOf(
                Evidence.informational(
                    MatchSignal.SizeAbsentFromCatalog,
                    "No catalogue record has this exact size.",
                ),
            ),
            hashesComputed = setOf(HashAlgorithm.CRC32, HashAlgorithm.SHA1),
            consultedSources = listOf(source.id),
            resolverVersion = "artifact-resolver-v1",
            tokenizerVersion = "token-v1",
            normalizerVersion = "title-normalizer-v1",
        )

        observations.saveAll(
            listOf(ResolvedObservation(observation("obs-1", sessionId), resolution)),
        )

        val loaded = (observations.findBySession(sessionId) as Outcome.Success).value.single()

        assertEquals(ResolutionState.EXACT_HASH, loaded.resolution.state)
        assertEquals(ConfidenceLevel.EXACT, loaded.resolution.confidence)
        assertEquals(catalogued.id, loaded.resolution.selected?.record?.id)
        assertEquals(
            setOf(HashAlgorithm.CRC32, HashAlgorithm.SHA1),
            loaded.resolution.hashesComputed,
        )
        assertEquals("artifact-resolver-v1", loaded.resolution.resolverVersion)

        // The observation itself, including what was inside the archive.
        assertEquals(ContainerKind.ZIP, loaded.observation.container)
        assertEquals("inner.sfc", loaded.observation.archiveEntries.single().entryPath)
        assertEquals(
            "aabbccdd",
            loaded.observation.archiveEntries.single().hashes[HashAlgorithm.CRC32]?.hex,
        )
        assertEquals("12345678", loaded.observation.hashes[HashAlgorithm.CRC32]?.hex)

        // Evidence survives, with its direction and its exclusion flag.
        val supporting = loaded.resolution.selected!!.supporting.single()
        assertEquals("hash_exact_sha1", supporting.signal.id)
        assertEquals(EvidenceStrength.DECISIVE, supporting.strength)
        val contradicting = loaded.resolution.selected!!.contradicting.single()
        assertTrue(contradicting.signal.excludesIdentity)
        assertEquals(1, loaded.resolution.pipelineEvidence.size)
    }

    @Test
    fun `saving the same observation twice does not duplicate it`() = runTest {
        val sessionId = ScanSessionId("session-2")
        sessions.start(
            ScanSessionRecord(
                id = sessionId,
                rootRef = StorageRef("mem:/roms"),
                rootDisplayName = "Roms",
                startedAtEpochMillis = 0,
                finishedAtEpochMillis = null,
                datSourceIds = emptyList(),
                namingProfileVersionedId = "no-intro@v1",
                resolverVersion = "v1",
            ),
        )
        val resolved = ResolvedObservation(
            observation("obs-2", sessionId),
            ArtifactResolution.terminal(
                observationId = ObservationId("obs-2"),
                state = ResolutionState.NO_MATCH,
                resolverVersion = "v1",
                tokenizerVersion = "v1",
                normalizerVersion = "v1",
            ),
        )

        observations.saveAll(listOf(resolved))
        observations.saveAll(listOf(resolved))

        assertEquals(1, (observations.findBySession(sessionId) as Outcome.Success).value.size)
    }

    @Test
    fun `session counts are stored and read back`() = runTest {
        val sessionId = ScanSessionId("session-3")
        sessions.start(
            ScanSessionRecord(
                id = sessionId,
                rootRef = StorageRef("mem:/roms"),
                rootDisplayName = "Roms",
                startedAtEpochMillis = 10,
                finishedAtEpochMillis = null,
                datSourceIds = listOf(source.id),
                namingProfileVersionedId = "no-intro@v1",
                resolverVersion = "v1",
            ),
        )
        sessions.finish(sessionId, ScanSummary(discovered = 9, processed = 8, exact = 5, failed = 1), cancelled = true)

        val loaded = (sessions.find(sessionId) as Outcome.Success).value

        assertEquals(9, loaded.summary.discovered)
        assertEquals(5, loaded.summary.exact)
        assertEquals(true, loaded.cancelled)
        assertEquals(listOf(source.id), loaded.datSourceIds)
        assertNotNull(loaded.finishedAtEpochMillis)
    }

    // ------------------------------------------------------------------
    // Rename journal
    // ------------------------------------------------------------------

    private fun operation(
        id: String,
        state: RenameOperationState,
        failure: RenameFailure? = null,
    ) = RenameOperation(
        id = RenameOperationId(id),
        batchId = RenameBatchId("batch-1"),
        planEntryId = PlanEntryId("entry-$id"),
        sourceRef = StorageRef("mem:/roms/$id.sfc"),
        directoryRef = StorageRef("mem:/roms"),
        sourceName = "$id.sfc",
        destinationName = "Canonical $id (USA).sfc",
        resolutionState = ResolutionState.EXACT_HASH,
        confidence = ConfidenceLevel.EXACT,
        identityDescription = "canonical $id [USA]",
        namingProfileVersionedId = "no-intro@v1",
        preconditionSize = 4096,
        preconditionHash = HashValue.of(HashAlgorithm.SHA1, "f".repeat(40)),
        state = state,
        failure = failure,
        plannedAtEpochMillis = 1_700_000_000_000L,
    )

    private fun batch(vararg operations: RenameOperation) = RenameBatch(
        id = RenameBatchId("batch-1"),
        planId = RenamePlanId("plan-1"),
        sessionId = ScanSessionId("session-1"),
        namingProfileVersionedId = "no-intro@v1",
        policyVersion = "automation-policy-v1",
        dryRun = false,
        createdAtEpochMillis = 1_700_000_000_000L,
        operations = operations.toList(),
    )

    @Test
    fun `a batch round-trips with its operations and preconditions`() = runTest {
        journal.createBatch(batch(operation("a", RenameOperationState.VALIDATED)))

        val loaded = (journal.findBatch(RenameBatchId("batch-1")) as Outcome.Success).value
        val op = loaded.operations.single()

        assertEquals("Canonical a (USA).sfc", op.destinationName)
        assertEquals(4096, op.preconditionSize)
        assertEquals(HashAlgorithm.SHA1, op.preconditionHash?.algorithm)
        assertEquals("canonical a [USA]", op.identityDescription)
        assertEquals(ResolutionState.EXACT_HASH, op.resolutionState)
    }

    @Test
    fun `a typed failure round-trips`() = runTest {
        journal.createBatch(
            batch(
                operation("a", RenameOperationState.FAILED, RenameFailure.PermissionDenied),
                operation("b", RenameOperationState.FAILED, RenameFailure.ProviderRejected("provider said no")),
            ),
        )

        val loaded = (journal.findBatch(RenameBatchId("batch-1")) as Outcome.Success).value

        assertEquals(RenameFailure.PermissionDenied, loaded.operations.first { it.id.value == "a" }.failure)
        assertEquals(
            RenameFailure.ProviderRejected("provider said no"),
            loaded.operations.first { it.id.value == "b" }.failure,
        )
    }

    @Test
    fun `unfinished batches are discoverable for reconciliation`() = runTest {
        journal.createBatch(
            batch(
                operation("done", RenameOperationState.COMPLETED),
                operation("stuck", RenameOperationState.EXECUTING),
            ),
        )

        val unfinished = (journal.findUnfinishedBatches() as Outcome.Success).value

        assertEquals(1, unfinished.size)
        assertEquals(1, unfinished.single().unfinished.size)
        assertEquals("stuck", unfinished.single().unfinished.single().id.value)
    }

    @Test
    fun `a fully completed batch is not reported as unfinished`() = runTest {
        journal.createBatch(batch(operation("done", RenameOperationState.COMPLETED)))

        assertTrue((journal.findUnfinishedBatches() as Outcome.Success).value.isEmpty())
    }

    @Test
    fun `an operation update is persisted`() = runTest {
        journal.createBatch(batch(operation("a", RenameOperationState.VALIDATED)))

        journal.updateOperation(
            operation("a", RenameOperationState.EXECUTING).markCompleted(1_700_000_500_000L),
        )

        val loaded = (journal.findBatch(RenameBatchId("batch-1")) as Outcome.Success).value
        assertEquals(RenameOperationState.COMPLETED, loaded.operations.single().state)
        assertEquals(1_700_000_500_000L, loaded.operations.single().finishedAtEpochMillis)
    }

    @Test
    fun `an unknown batch reports a typed failure rather than an empty batch`() = runTest {
        assertIs<Outcome.Failure>(journal.findBatch(RenameBatchId("nope")))
    }
}
