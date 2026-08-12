package com.retrovault.platform.jvm

import com.retrovault.application.Clock
import com.retrovault.application.DatInput
import com.retrovault.application.ExecuteRenamePlanUseCase
import com.retrovault.application.GenerateRenamePlanUseCase
import com.retrovault.application.IdGenerator
import com.retrovault.application.ImportDatUseCase
import com.retrovault.application.Outcome
import com.retrovault.application.PreviewRenamePlanUseCase
import com.retrovault.application.ReconcileInterruptedRenamesUseCase
import com.retrovault.application.RenameExecutor
import com.retrovault.application.ResolveArtifactUseCase
import com.retrovault.application.ScanConfig
import com.retrovault.application.ScanEvent
import com.retrovault.application.ScanLocationUseCase
import com.retrovault.application.StorageLocation
import com.retrovault.application.ValidateRenamePlanUseCase
import com.retrovault.data.Schema
import com.retrovault.data.SqlDumpCatalog
import com.retrovault.data.SqlObservationRepository
import com.retrovault.data.SqlRenameJournalRepository
import com.retrovault.data.SqlScanSessionRepository
import com.retrovault.data.jdbc.JdbcSqlDatabase
import com.retrovault.dat.DatByteSource
import com.retrovault.dat.LogiqxDatReader
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.rename.PlanIssue
import com.retrovault.domain.rename.PlanVerdict
import com.retrovault.domain.rename.PlannedAction
import com.retrovault.domain.rename.RenameOperationState
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole vertical slice, against real files and a real SQLite database.
 *
 * BUILD_PLAN.md section 4 lists the exit criteria for this slice. This test
 * exercises them end to end: folder selection, recursive scan, offline DAT
 * ingestion, hash identification, fallback matching, uncertainty, canonical
 * filenames, batch preview, collision rejection, safe rename and retained
 * history.
 */
class VerticalSliceEndToEndTest {

    private lateinit var root: Path
    private lateinit var database: JdbcSqlDatabase
    private lateinit var catalog: SqlDumpCatalog
    private lateinit var observations: SqlObservationRepository
    private lateinit var sessions: SqlScanSessionRepository
    private lateinit var journal: SqlRenameJournalRepository

    private var now = 1_700_000_000_000L
    private var counter = 0

    private val clock = Clock { now++ }
    private val ids = IdGenerator { prefix -> "$prefix-${counter++}" }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("retrovault-e2e")
        database = JdbcSqlDatabase.inMemory()
        Schema.migrate(database)
        catalog = SqlDumpCatalog(database)
        observations = SqlObservationRepository(database)
        sessions = SqlScanSessionRepository(database)
        journal = SqlRenameJournalRepository(database)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        root.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private fun payload(seed: Int, size: Int = 4096): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) % 251).toByte() }

    private fun crc32(bytes: ByteArray): String =
        "%08x".format(CRC32().apply { update(bytes) }.value)

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeFile(relativePath: String, bytes: ByteArray): Path {
        val target = root.resolve(relativePath)
        target.parent.createDirectories()
        target.writeBytes(bytes)
        return target
    }

    private fun writeZip(relativePath: String, entries: Map<String, ByteArray>): Path {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return writeFile(relativePath, buffer.toByteArray())
    }

    private fun writeDat(name: String, body: String): Path {
        val target = root.resolve(name)
        target.writeText(body)
        return target
    }

    private suspend fun importDat(path: Path) {
        val reader = LogiqxDatReader(
            byteSource = DatByteSource { ref -> Files.newInputStream(ref.toPath()) as InputStream },
        )
        val result = ImportDatUseCase(reader, catalog, clock).import(
            DatInput(path.toStorageRef(), path.name, provider = "no_intro"),
        )
        assertIs<Outcome.Success<*>>(result, "The DAT fixture must import cleanly")
    }

    private suspend fun scan(): Pair<ScanSessionId, List<ScanEvent>> {
        val useCase = ScanLocationUseCase(
            walker = LocalDirectoryWalker(),
            contentSource = LocalContentSource(),
            resolveArtifact = ResolveArtifactUseCase(catalog, LocalContentSource()),
            catalog = catalog,
            observations = observations,
            sessions = sessions,
            clock = clock,
            ids = ids,
            config = ScanConfig(concurrency = 4, persistBatchSize = 8),
        )
        val events = useCase
            .scan(StorageLocation(root.toStorageRef(), "Test library"))
            .toList()
        val started = events.filterIsInstance<ScanEvent.SessionStarted>().single()
        return started.session.id to events
    }

    private fun resolutionsByName(events: List<ScanEvent>) =
        events.filterIsInstance<ScanEvent.FileResolved>()
            .associate { it.resolved.observation.filename to it.resolved.resolution }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `a library is scanned, identified, previewed and safely renamed`() = runTest {
        val marioBytes = payload(seed = 1)
        val zeldaBytes = payload(seed = 2, size = 8192)
        val zippedBytes = payload(seed = 3)
        val unknownBytes = payload(seed = 99, size = 1234)

        writeDat(
            "test.dat",
            """
            <?xml version="1.0"?>
            <datafile>
              <header><name>Test Console</name><version>2026-01-01</version></header>
              <game name="Super Mario World (USA)">
                <rom name="Super Mario World (USA).sfc" size="${marioBytes.size}"
                     crc="${crc32(marioBytes)}" md5="${md5(marioBytes)}" sha1="${sha1(marioBytes)}"/>
              </game>
              <game name="Legend of Zelda, The (Europe) (Rev A)">
                <rom name="Legend of Zelda, The (Europe) (Rev A).sfc" size="${zeldaBytes.size}"
                     crc="${crc32(zeldaBytes)}" sha1="${sha1(zeldaBytes)}"/>
              </game>
              <game name="Packed Game (Japan)">
                <rom name="Packed Game (Japan).sfc" size="${zippedBytes.size}"
                     crc="${crc32(zippedBytes)}" sha1="${sha1(zippedBytes)}"/>
              </game>
            </datafile>
            """.trimIndent(),
        )

        // Deliberately messy names, in nested folders.
        writeFile("snes/smw_scrubbed_v2.sfc", marioBytes)
        writeFile("snes/euro/zelda-alttp-PAL.sfc", zeldaBytes)
        writeZip("snes/packed.zip", mapOf("whatever.sfc" to zippedBytes))
        writeFile("snes/mystery-homebrew.sfc", unknownBytes)

        importDat(root.resolve("test.dat"))

        // --- scan ------------------------------------------------------
        val (sessionId, events) = scan()
        val resolutions = resolutionsByName(events)

        assertEquals(
            ResolutionState.EXACT_MULTI_HASH,
            resolutions.getValue("smw_scrubbed_v2.sfc").state,
            "MD5 and SHA1 both matched, so this is a multi-hash exact match",
        )
        assertEquals(ResolutionState.EXACT_HASH, resolutions.getValue("zelda-alttp-PAL.sfc").state)
        assertEquals(
            ResolutionState.EXACT_HASH,
            resolutions.getValue("packed.zip").state,
            "The archive is identified from its single contained artifact",
        )
        assertEquals(ResolutionState.NO_MATCH, resolutions.getValue("mystery-homebrew.sfc").state)

        val finished = events.filterIsInstance<ScanEvent.SessionFinished>().single()
        assertFalse(finished.cancelled)
        assertEquals(4, finished.summary.processed)
        assertEquals(3, finished.summary.exact)
        assertEquals(1, finished.summary.unmatched)

        // Progressive: results arrived before the session finished.
        assertTrue(
            events.indexOfFirst { it is ScanEvent.FileResolved } <
                events.indexOfFirst { it is ScanEvent.SessionFinished },
        )

        // --- plan and preview ------------------------------------------
        val plan = (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value
        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val preview = PreviewRenamePlanUseCase(validate).preview(plan)

        assertEquals(PlanVerdict.EXECUTABLE, preview.validation.verdict)
        val proposals = preview.rows.associate { it.currentName to it.proposedName }
        assertEquals("Super Mario World (USA).sfc", proposals["smw_scrubbed_v2.sfc"])
        assertEquals("Legend of Zelda, The (Europe) (Rev A).sfc", proposals["zelda-alttp-PAL.sfc"])
        assertEquals(
            "Packed Game (Japan).zip",
            proposals["packed.zip"],
            "An archive keeps its own extension",
        )
        assertEquals(
            null,
            proposals["mystery-homebrew.sfc"],
            "An unidentified file gets no proposed name at all",
        )
        assertTrue(
            preview.rows.single { it.currentName == "smw_scrubbed_v2.sfc" }.reasons.isNotEmpty(),
            "The preview must be able to explain why",
        )

        // --- dry run: nothing changes ----------------------------------
        val execute = ExecuteRenamePlanUseCase(validate, LocalRenameExecutor(), journal, clock, ids)
        val before = root.resolve("snes").listDirectoryEntries().map { it.name }.sorted()
        val dryRun = (execute.execute(plan, dryRun = true) as Outcome.Success).value

        assertEquals(before, root.resolve("snes").listDirectoryEntries().map { it.name }.sorted())
        assertEquals(3, dryRun.batch.operations.size)
        assertIs<Outcome.Failure>(journal.findBatch(dryRun.batch.id), "A dry run writes no journal")

        // --- execute ----------------------------------------------------
        val result = (execute.execute(plan) as Outcome.Success).value

        assertEquals(3, result.summary.completed)
        assertEquals(0, result.summary.failed)
        assertTrue(result.summary.isFullySuccessful)

        val names = root.resolve("snes").listDirectoryEntries().map { it.name }
        assertTrue("Super Mario World (USA).sfc" in names)
        assertTrue("Packed Game (Japan).zip" in names)
        assertTrue("mystery-homebrew.sfc" in names, "An unresolved file is left exactly as it was")
        assertTrue(
            "Legend of Zelda, The (Europe) (Rev A).sfc" in
                root.resolve("snes/euro").listDirectoryEntries().map { it.name },
        )

        // Content is untouched: rename never rewrites bytes.
        assertTrue(root.resolve("snes/Super Mario World (USA).sfc").readBytes().contentEquals(marioBytes))

        // --- history ----------------------------------------------------
        val stored = (journal.findBatch(result.batch.id) as Outcome.Success).value
        assertEquals(3, stored.operations.size)
        assertTrue(stored.operations.all { it.state == RenameOperationState.COMPLETED })
        assertTrue(
            stored.operations.all { it.identityDescription.isNotBlank() },
            "The journal records why each rename was believed correct",
        )

        val session = (sessions.find(sessionId) as Outcome.Success).value
        assertEquals(4, session.summary.processed)
        assertEquals("Test library", session.rootDisplayName)
    }

    @Test
    fun `a rerun after renaming changes nothing`() = runTest {
        val bytes = payload(seed = 7)
        writeDat(
            "test.dat",
            """
            <datafile>
              <header><name>Test Console</name><version>1</version></header>
              <game name="Some Game (USA)">
                <rom name="Some Game (USA).sfc" size="${bytes.size}"
                     crc="${crc32(bytes)}" sha1="${sha1(bytes)}"/>
              </game>
            </datafile>
            """.trimIndent(),
        )
        writeFile("roms/messy.sfc", bytes)
        importDat(root.resolve("test.dat"))

        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val execute = ExecuteRenamePlanUseCase(validate, LocalRenameExecutor(), journal, clock, ids)

        val (firstSession, _) = scan()
        val firstPlan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(firstSession) as Outcome.Success).value
        execute.execute(firstPlan)
        assertTrue("Some Game (USA).sfc" in root.resolve("roms").listDirectoryEntries().map { it.name })

        // Second pass over the now-canonical library.
        val (secondSession, _) = scan()
        val secondPlan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(secondSession) as Outcome.Success).value
        val preview = PreviewRenamePlanUseCase(validate).preview(secondPlan)

        assertEquals(
            PlanVerdict.NOTHING_TO_DO,
            preview.validation.verdict,
            "normalize(normalize(x)) == normalize(x): a canonical library needs no second rename",
        )
        assertTrue(
            secondPlan.entries.all { it.action == PlannedAction.SKIP_ALREADY_CANONICAL },
        )
    }

    @Test
    fun `two files resolving to the same name block the whole batch`() = runTest {
        // Two byte-identical copies of one dump: both resolve to the same
        // canonical name, so neither may be renamed.
        val bytes = payload(seed = 11)
        writeDat(
            "test.dat",
            """
            <datafile>
              <header><name>Test Console</name><version>1</version></header>
              <game name="Duplicated Game (USA)">
                <rom name="Duplicated Game (USA).sfc" size="${bytes.size}"
                     crc="${crc32(bytes)}" sha1="${sha1(bytes)}"/>
              </game>
            </datafile>
            """.trimIndent(),
        )
        writeFile("roms/copy-one.sfc", bytes)
        writeFile("roms/copy-two.sfc", bytes)
        importDat(root.resolve("test.dat"))

        val (sessionId, _) = scan()
        val plan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value
        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val execute = ExecuteRenamePlanUseCase(validate, LocalRenameExecutor(), journal, clock, ids)

        val result = (execute.execute(plan) as Outcome.Success).value

        assertTrue(result.refused != null, "A colliding batch must be refused outright")
        assertTrue(
            PreviewRenamePlanUseCase(validate).preview(plan).validation.blockingIssues
                .any { it is PlanIssue.DuplicateDestination },
        )
        assertEquals(
            listOf("copy-one.sfc", "copy-two.sfc"),
            root.resolve("roms").listDirectoryEntries().map { it.name }.sorted(),
            "Nothing at all may be renamed when the batch is blocked",
        )
    }

    @Test
    fun `a file changed since the scan blocks the batch`() = runTest {
        val bytes = payload(seed = 13)
        writeDat(
            "test.dat",
            """
            <datafile>
              <header><name>Test Console</name><version>1</version></header>
              <game name="Stale Game (USA)">
                <rom name="Stale Game (USA).sfc" size="${bytes.size}"
                     crc="${crc32(bytes)}" sha1="${sha1(bytes)}"/>
              </game>
            </datafile>
            """.trimIndent(),
        )
        writeFile("roms/stale.sfc", bytes)
        importDat(root.resolve("test.dat"))

        val (sessionId, _) = scan()
        val plan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value

        // Someone edits the file between the scan and the rename.
        writeFile("roms/stale.sfc", payload(seed = 13, size = 2048))

        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val validation = validate.validate(plan)

        assertEquals(PlanVerdict.BLOCKED, validation.verdict)
        assertTrue(validation.blockingIssues.any { it is PlanIssue.StaleObservation })
    }

    @Test
    fun `an interrupted rename is reconciled from the journal`() = runTest {
        val bytes = payload(seed = 17)
        writeDat(
            "test.dat",
            """
            <datafile>
              <header><name>Test Console</name><version>1</version></header>
              <game name="Interrupted Game (USA)">
                <rom name="Interrupted Game (USA).sfc" size="${bytes.size}"
                     crc="${crc32(bytes)}" sha1="${sha1(bytes)}"/>
              </game>
            </datafile>
            """.trimIndent(),
        )
        writeFile("roms/interrupted.sfc", bytes)
        importDat(root.resolve("test.dat"))

        val (sessionId, _) = scan()
        val plan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value
        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)

        // Simulate a crash: the journal records EXECUTING, the filesystem
        // rename happens, and the process dies before the result is written.
        val executor = LocalRenameExecutor()
        val crashingExecutor = object : RenameExecutor {
            override suspend fun rename(ref: StorageRef, newName: String): Outcome<StorageRef> {
                executor.rename(ref, newName)
                throw SimulatedCrash()
            }
        }
        val crashing = ExecuteRenamePlanUseCase(validate, crashingExecutor, journal, clock, ids)
        runCatching { crashing.execute(plan) }

        val unfinished = (journal.findUnfinishedBatches() as Outcome.Success).value
        assertTrue(unfinished.isNotEmpty(), "The journal must show work that never completed")

        val reconciled = (
            ReconcileInterruptedRenamesUseCase(journal, LocalContentSource(), clock).reconcile()
                as Outcome.Success
            ).value

        assertEquals(1, reconciled.size)
        assertEquals(
            RenameOperationState.RECONCILED_COMPLETED,
            reconciled.single().state,
            "The destination exists and the source is gone, so the rename did take effect",
        )
        assertTrue((journal.findUnfinishedBatches() as Outcome.Success).value.isEmpty())
    }

    private class SimulatedCrash : RuntimeException("simulated crash after the filesystem call")
}
