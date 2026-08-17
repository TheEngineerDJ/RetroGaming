package com.retrovault.application

import com.retrovault.domain.catalog.CatalogueCoverage
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DatasetCoverage
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.EntityRef
import com.retrovault.domain.entity.PromotedIdentity
import com.retrovault.domain.entity.Release
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.naming.NormalizedTitle
import com.retrovault.domain.observation.ArchiveEntryObservation
import com.retrovault.domain.observation.ArtifactContentRef
import com.retrovault.domain.rename.ArtifactState
import com.retrovault.domain.rename.DirectorySnapshot
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scan orchestration.
 *
 * TESTING_SPEC.md section 6 requires cancellation, progress events, filtering
 * and failure isolation to be verified.
 */
class ScanLocationUseCaseTest {

    private val source = DatSourceRef(
        id = DatSourceId("no_intro:test:1"),
        provider = "no_intro",
        setName = "Test",
        version = "1",
        platform = PlatformName("Test"),
        importedAtEpochMillis = 0,
    )

    private fun record(setName: String, size: Long, crc: String): DumpRecord = DumpRecord.derive(
        id = DumpRecordId("record-$setName"),
        source = source,
        setName = setName,
        romName = "$setName.sfc",
        size = size,
        hashes = HashDigests.of(HashValue.of(HashAlgorithm.CRC32, crc)),
    )

    /** The CRC of the record used by the promotion tests, so a match is reached. */
    private val matchingCrc = HashValue.of(HashAlgorithm.CRC32, "11111111")

    private fun file(name: String, size: Long = 1024) = DiscoveredFile(
        ref = StorageRef("mem:/roms/$name"),
        parentRef = StorageRef("mem:/roms"),
        name = name,
        relativePath = name,
        size = size,
        lastModifiedEpochMillis = 0,
    )

    private class FakeWalker(private val events: List<WalkEvent>) : DirectoryWalker {
        override fun walk(root: StorageLocation): Flow<WalkEvent> = flow {
            events.forEach { emit(it) }
        }
    }

    /** A catalogue whose coverage query fails, to prove a scan survives it. */
    private class CoverageFailingCatalog(records: List<DumpRecord>) : DumpCatalog by FakeCatalog(records) {
        override suspend fun coverage(): CatalogueCoverage = throw IllegalStateException("database is busy")
    }

    private class FakeCatalog(private val records: List<DumpRecord>) : DumpCatalog {
        override suspend fun findBySize(size: Long) =
            records.filter { it.size == null || it.size == size }

        override suspend fun findByHash(hash: HashValue) =
            records.filter { it.hashes[hash.algorithm] == hash }

        override suspend fun findByNormalizedTitle(title: NormalizedTitle): List<DumpRecord> {
            val tokens = title.tokens().toSet()
            return records.filter { record -> record.normalizedTitle.tokens().any { it in tokens } }
        }

        override suspend fun sources() = listOf(
            DatSourceRef(
                id = DatSourceId("no_intro:test:1"),
                provider = "no_intro",
                setName = "Test",
                version = "1",
                platform = PlatformName("Test"),
                importedAtEpochMillis = 0,
            ),
        )

        // Measured the same way the real catalogue measures it: from the media
        // the indexed records actually carry.
        override suspend fun coverage() = CatalogueCoverage(
            sources().map { source ->
                DatasetCoverage(
                    source = source,
                    mediaTypes = records.mapTo(mutableSetOf()) { it.mediaType },
                    recordCount = records.size,
                )
            },
        )
    }

    /** Records how many hash operations overlap, to prove concurrency is bounded. */
    private class CountingContentSource(
        private val hashes: Map<String, HashDigests> = emptyMap(),
        private val delayMillis: Long = 0,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ContentSource {
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val started = AtomicInteger(0)

        override suspend fun computeHashes(
            ref: ArtifactContentRef,
            algorithms: Set<HashAlgorithm>,
        ): Outcome<HashDigests> {
            started.incrementAndGet()
            val current = inFlight.incrementAndGet()
            peakInFlight.updateAndGet { peak -> maxOf(peak, current) }
            try {
                gate?.await()
                if (delayMillis > 0) delay(delayMillis)
                val name = ref.storageRef.value.substringAfterLast('/')
                return Outcome.success(hashes[name] ?: HashDigests.EMPTY)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun readPrefix(ref: StorageRef, byteCount: Int): Outcome<ByteArray> =
        Outcome.success(ByteArray(0))

    override suspend fun inspectArchive(ref: StorageRef): Outcome<List<ArchiveEntryObservation>> =
            Outcome.success(emptyList())

        override suspend fun stat(ref: StorageRef): Outcome<ArtifactState> =
            Outcome.success(ArtifactState(ref, exists = true, filename = null, size = null, writable = true))

        override suspend fun listNames(directory: StorageRef): Outcome<DirectorySnapshot> =
            Outcome.success(DirectorySnapshot(directory, emptySet()))
    }

    /** Fails the Nth save and every one after it, to model a database going away. */
    private class FailingObservations(private val failFromBatch: Int) : ObservationRepository {
        var batches = 0
            private set

        override suspend fun saveAll(entries: List<ResolvedObservation>): Outcome<Int> {
            val index = synchronized(this) { batches++ }
            return if (index >= failFromBatch) {
                Outcome.failure(RetroVaultFailure.PersistenceFailure("disk full"))
            } else {
                Outcome.success(entries.size)
            }
        }

        override suspend fun findBySession(id: ScanSessionId): Outcome<List<ResolvedObservation>> =
            Outcome.success(emptyList())
    }

    private class RecordingObservations : ObservationRepository {
        val saved = mutableListOf<ResolvedObservation>()
        val batchSizes = mutableListOf<Int>()

        override suspend fun saveAll(entries: List<ResolvedObservation>): Outcome<Int> {
            synchronized(saved) {
                saved += entries
                batchSizes += entries.size
            }
            return Outcome.success(entries.size)
        }

        override suspend fun findBySession(id: ScanSessionId): Outcome<List<ResolvedObservation>> =
            Outcome.success(saved.toList())
    }

    private class RecordingSessions : ScanSessionRepository {
        var started: ScanSessionRecord? = null
        var finishedSummary: ScanSummary? = null
        var cancelled: Boolean? = null

        override suspend fun start(session: ScanSessionRecord): Outcome<Unit> {
            started = session
            return Outcome.success(Unit)
        }

        override suspend fun finish(
            id: ScanSessionId,
            summary: ScanSummary,
            cancelled: Boolean,
        ): Outcome<Unit> {
            finishedSummary = summary
            this.cancelled = cancelled
            return Outcome.success(Unit)
        }

        override suspend fun find(id: ScanSessionId): Outcome<ScanSessionRecord> =
            started?.let { Outcome.success(it) }
                ?: Outcome.failure(RetroVaultFailure.PersistenceFailure("none"))
    }


    @Test
    fun `a catalogue that cannot report coverage does not fail the scan`() = runTest {
        // Coverage only adds a distinction between two ways of finding nothing.
        // Losing it must cost the user that distinction, never their scan.
        val walker = FakeWalker(listOf(WalkEvent.FileFound(file("game.sfc", size = 4096))))
        val events = useCase(
            walker,
            CountingContentSource(),
            CoverageFailingCatalog(emptyList()),
            RecordingObservations(),
            RecordingSessions(),
        ).scan(location).toList()

        val resolved = events.filterIsInstance<ScanEvent.FileResolved>().single()
        assertEquals(
            ResolutionState.NO_MATCH,
            resolved.resolved.resolution.state,
            "Unmeasured coverage must fall back to plain absence, not to a scope claim",
        )
        assertTrue(events.any { it is ScanEvent.SessionFinished })
    }


    @Test
    fun `a persistence failure part-way through a scan is reported, not swallowed`() = runTest {
        // Only the final flush used to be checked. A database that started
        // failing at batch two produced a scan reporting success and a rename
        // plan missing most of its files, with nothing anywhere saying why.
        val walker = FakeWalker((1..12).map { WalkEvent.FileFound(file("game$it.sfc")) })

        val events = useCase(
            walker,
            CountingContentSource(),
            FakeCatalog(emptyList()),
            FailingObservations(failFromBatch = 0),
            RecordingSessions(),
            config = ScanConfig(concurrency = 1, persistBatchSize = 4),
        ).scan(location).toList()

        val finished = events.filterIsInstance<ScanEvent.SessionFinished>().single()
        assertNotNull(
            finished.persistenceFailure,
            "A scan that could not persist its results must say so",
        )
    }

    @Test
    fun `a scan that persists everything reports no failure`() = runTest {
        val walker = FakeWalker((1..12).map { WalkEvent.FileFound(file("game$it.sfc")) })

        val events = useCase(
            walker,
            CountingContentSource(),
            FakeCatalog(emptyList()),
            RecordingObservations(),
            RecordingSessions(),
            config = ScanConfig(concurrency = 1, persistBatchSize = 4),
        ).scan(location).toList()

        assertNull(events.filterIsInstance<ScanEvent.SessionFinished>().single().persistenceFailure)
    }

    private fun useCase(
        walker: DirectoryWalker,
        content: ContentSource,
        catalog: DumpCatalog,
        observations: ObservationRepository,
        sessions: ScanSessionRepository,
        config: ScanConfig = ScanConfig(),
        entities: EntityGraph? = null,
    ): ScanLocationUseCase {
        var counter = 0
        return ScanLocationUseCase(
            walker = walker,
            contentSource = content,
            resolveArtifact = ResolveArtifactUseCase(catalog, content),
            entities = entities,
            catalog = catalog,
            observations = observations,
            sessions = sessions,
            clock = Clock { 1_700_000_000_000L },
            ids = IdGenerator { prefix -> "$prefix-${counter++}" },
            config = config,
        )
    }

    private val location = StorageLocation(StorageRef("mem:/roms"), "Test")

    @Test
    fun `results are emitted progressively, before the scan finishes`() = runTest {
        val walker = FakeWalker(
            (1..5).map { WalkEvent.FileFound(file("game$it.sfc")) },
        )
        val observations = RecordingObservations()
        val sessions = RecordingSessions()

        val events = useCase(walker, CountingContentSource(), FakeCatalog(emptyList()), observations, sessions)
            .scan(location)
            .toList()

        val firstResolved = events.indexOfFirst { it is ScanEvent.FileResolved }
        val finished = events.indexOfFirst { it is ScanEvent.SessionFinished }
        assertTrue(firstResolved in 0 until finished, "Results must appear before completion")
        assertEquals(5, events.filterIsInstance<ScanEvent.FileResolved>().size)
    }

    @Test
    fun `concurrency stays within the configured bound`() = runTest {
        val walker = FakeWalker((1..40).map { WalkEvent.FileFound(file("game$it.sfc", size = 4096)) })
        val content = CountingContentSource(delayMillis = 5)
        // Every file has a catalogued size, so all of them reach hashing.
        val catalog = FakeCatalog(listOf(record("Game (USA)", size = 4096, crc = "11111111")))

        useCase(
            walker,
            content,
            catalog,
            RecordingObservations(),
            RecordingSessions(),
            ScanConfig(concurrency = 3),
        ).scan(location).toList()

        assertTrue(
            content.peakInFlight.get() <= 3,
            "Peak concurrent hashing was ${content.peakInFlight.get()}, expected at most 3",
        )
        assertTrue(content.started.get() > 3, "The test must actually exercise the bound")
    }

    @Test
    fun `cancellation stops work and closes the session as cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val walker = FakeWalker((1..50).map { WalkEvent.FileFound(file("game$it.sfc", size = 4096)) })
        val content = CountingContentSource(gate = gate)
        val catalog = FakeCatalog(listOf(record("Game (USA)", size = 4096, crc = "11111111")))
        val sessions = RecordingSessions()

        // Collect only the first few events, then stop: the flow is cancelled
        // exactly as it would be if the user left the screen.
        val events = useCase(
            walker,
            content,
            catalog,
            RecordingObservations(),
            sessions,
            ScanConfig(concurrency = 2),
        ).scan(location).take(3).toList()

        gate.complete(Unit)

        assertEquals(3, events.size)
        assertTrue(
            content.started.get() < 50,
            "Cancellation must stop the remaining work, not merely stop reporting it",
        )
    }

    @Test
    fun `a session cancelled mid-scan is recorded as cancelled`() = runTest {
        val walker = FakeWalker((1..5).map { WalkEvent.FileFound(file("game$it.sfc")) })
        val sessions = RecordingSessions()

        useCase(walker, CountingContentSource(), FakeCatalog(emptyList()), RecordingObservations(), sessions)
            .scan(location)
            .take(2)
            .toList()

        assertEquals(true, sessions.cancelled, "An abandoned scan must not look like a completed one")
    }

    @Test
    fun `a completed scan is recorded as not cancelled`() = runTest {
        val walker = FakeWalker(listOf(WalkEvent.FileFound(file("game.sfc"))))
        val sessions = RecordingSessions()

        useCase(walker, CountingContentSource(), FakeCatalog(emptyList()), RecordingObservations(), sessions)
            .scan(location)
            .toList()

        assertEquals(false, sessions.cancelled)
        assertEquals(1, sessions.finishedSummary?.processed)
    }

    @Test
    fun `a directory failure does not stop the scan`() = runTest {
        val walker = FakeWalker(
            listOf(
                WalkEvent.Failed("locked", RetroVaultFailure.PermissionDenied(StorageRef("mem:/locked"))),
                WalkEvent.FileFound(file("game.sfc")),
            ),
        )

        val events = useCase(
            walker,
            CountingContentSource(),
            FakeCatalog(emptyList()),
            RecordingObservations(),
            RecordingSessions(),
        ).scan(location).toList()

        assertEquals(1, events.filterIsInstance<ScanEvent.FileFailed>().size)
        assertEquals(1, events.filterIsInstance<ScanEvent.FileResolved>().size)
    }

    @Test
    fun `non-game files are filtered out before any hashing`() = runTest {
        val walker = FakeWalker(
            listOf(
                WalkEvent.FileFound(file("readme.txt")),
                WalkEvent.FileFound(file("boxart.png")),
                WalkEvent.FileFound(file(".hidden")),
                WalkEvent.FileFound(file("game.sfc")),
            ),
        )
        val content = CountingContentSource()

        val events = useCase(
            walker,
            content,
            FakeCatalog(emptyList()),
            RecordingObservations(),
            RecordingSessions(),
        ).scan(location).toList()

        assertEquals(1, events.filterIsInstance<ScanEvent.FileDiscovered>().size)
        assertEquals(0, content.started.get(), "Filtered files must never be opened")
    }

    @Test
    fun `observations are persisted in bounded batches`() = runTest {
        val walker = FakeWalker((1..10).map { WalkEvent.FileFound(file("game$it.sfc")) })
        val observations = RecordingObservations()

        useCase(
            walker,
            CountingContentSource(),
            FakeCatalog(emptyList()),
            observations,
            RecordingSessions(),
            ScanConfig(concurrency = 1, persistBatchSize = 4),
        ).scan(location).toList()

        assertEquals(10, observations.saved.size)
        assertTrue(
            observations.batchSizes.all { it <= 4 },
            "Batches were ${observations.batchSizes}; none may exceed the configured size",
        )
        assertTrue(observations.batchSizes.size > 1, "Persistence must happen during the scan, not only at the end")
    }

    @Test
    fun `a size that matches nothing skips hashing entirely`() = runTest {
        val walker = FakeWalker(listOf(WalkEvent.FileFound(file("game.sfc", size = 999))))
        val content = CountingContentSource()
        val catalog = FakeCatalog(listOf(record("Game (USA)", size = 4096, crc = "11111111")))

        val events = useCase(walker, content, catalog, RecordingObservations(), RecordingSessions())
            .scan(location)
            .toList()

        assertEquals(0, content.started.get(), "Size filtering must prevent the read")
        val finished = events.filterIsInstance<ScanEvent.SessionFinished>().single()
        assertEquals(1, finished.summary.hashingSkippedBySizeFilter)
    }

    @Test
    fun `a scan with no catalogue leaves everything unmatched and untouched`() = runTest {
        val walker = FakeWalker((1..3).map { WalkEvent.FileFound(file("game$it.sfc")) })

        val events = useCase(
            walker,
            CountingContentSource(),
            FakeCatalog(emptyList()),
            RecordingObservations(),
            RecordingSessions(),
        ).scan(location).toList()

        assertTrue(
            events.filterIsInstance<ScanEvent.FileResolved>()
                .all { it.resolved.resolution.state == ResolutionState.NO_MATCH },
        )
    }

    /**
     * Records what it was asked to save, and can be told to refuse.
     *
     * The refusal path is the point: a graph failure used to be discarded
     * entirely, so a scan against a full disk reported success and left an
     * empty graph.
     */
    private class RecordingGraph(private val failAfter: Int = Int.MAX_VALUE) : EntityGraph {
        val saved = mutableListOf<PromotedIdentity>()

        override suspend fun save(identity: PromotedIdentity): Outcome<Unit> {
            if (saved.size >= failAfter) {
                return Outcome.failure(RetroVaultFailure.PersistenceFailure("disk full"))
            }
            saved += identity
            return Outcome.success(Unit)
        }

        override suspend fun findRelease(id: ReleaseId): Outcome<Release?> = Outcome.success(null)

        override suspend fun recordsForRelease(id: ReleaseId): List<DumpRecord> = emptyList()

        override suspend fun relationshipsFrom(ref: EntityRef): Outcome<List<EntityRelationship>> =
            Outcome.success(emptyList())

        override suspend fun relate(relationship: EntityRelationship): Outcome<Unit> = Outcome.success(Unit)
    }

    @Test
    fun `identical identities are written to the graph once per batch, not once per file`() = runTest {
        // Every file in this fixture is the same record, so the graph should
        // see one identity rather than one per file.
        val walker = FakeWalker((1..8).map { WalkEvent.FileFound(file("game$it.sfc")) })
        val graph = RecordingGraph()
        val matching = (1..8).associate { "game$it.sfc" to HashDigests.of(matchingCrc) }

        useCase(
            walker = walker,
            content = CountingContentSource(matching),
            catalog = FakeCatalog(listOf(record("Game (USA)", size = 1024, crc = "11111111"))),
            observations = RecordingObservations(),
            sessions = RecordingSessions(),
            config = ScanConfig(concurrency = 1, persistBatchSize = 8),
            entities = graph,
        ).scan(location).toList()

        assertTrue(graph.saved.isNotEmpty(), "The scan must project what it identified")
        assertEquals(
            graph.saved.size,
            graph.saved.distinctBy { it.artifact.id.value }.size,
            "A batch that promotes the same identity repeatedly wastes a write transaction per file",
        )
    }

    @Test
    fun `a graph failure is reported rather than discarded`() = runTest {
        val walker = FakeWalker((1..4).map { WalkEvent.FileFound(file("game$it.sfc")) })
        val graph = RecordingGraph(failAfter = 0)
        val matching = (1..4).associate { "game$it.sfc" to HashDigests.of(matchingCrc) }

        val events = useCase(
            walker = walker,
            content = CountingContentSource(matching),
            catalog = FakeCatalog(listOf(record("Game (USA)", size = 1024, crc = "11111111"))),
            observations = RecordingObservations(),
            sessions = RecordingSessions(),
            config = ScanConfig(concurrency = 1, persistBatchSize = 2),
            entities = graph,
        ).scan(location).toList()

        val finished = events.filterIsInstance<ScanEvent.SessionFinished>().single()
        assertNotNull(
            finished.graphFailure,
            "Section 250: a graph that could not be written is data, not silence",
        )
        assertNull(finished.persistenceFailure, "The scan's own results persisted fine")
    }
}
