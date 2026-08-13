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
import com.retrovault.domain.identity.DatasetKind
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.rename.PlanIssue
import com.retrovault.domain.rename.PlanVerdict
import com.retrovault.domain.rename.PlannedAction
import com.retrovault.domain.rename.RenameOperationState
import com.retrovault.domain.rename.RenameStaging
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


    @Test
    fun `a case-only rename is staged through a temporary name and journalled`() = runTest {
        val bytes = payload(seed = 23)
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
        writeFile("roms/some game (usa).sfc", bytes)
        importDat(root.resolve("test.dat"))

        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val (sessionId, _) = scan()
        val plan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value
        val result = ExecuteRenamePlanUseCase(validate, LocalRenameExecutor(), journal, clock, ids)
            .execute(plan)

        val names = root.resolve("roms").listDirectoryEntries().map { it.name }
        assertEquals(listOf("Some Game (USA).sfc"), names)
        assertTrue(
            names.none { it.endsWith(RenameStaging.SUFFIX) },
            "The staging name must not survive a successful rename",
        )

        val operation = (result as Outcome.Success).value.batch.operations.single()
        assertEquals("Some Game (USA).sfc" + RenameStaging.SUFFIX, operation.intermediateName)
        assertEquals(RenameOperationState.COMPLETED, operation.state)
    }

    @Test
    fun `files that shift along a chain of names are renamed in a safe order`() {
        // "Beta Game (USA).sfc" actually holds Alpha's bytes, so it has to
        // vacate that name before the file that genuinely is Beta can take it.
        // Executed in plan order this collides; executed in dependency order it
        // succeeds.
        runTest {
            val alpha = payload(seed = 31)
            val beta = payload(seed = 32)
            writeDat(
                "test.dat",
                """
                <datafile>
                  <header><name>Test Console</name><version>1</version></header>
                  <game name="Alpha Game (USA)">
                    <rom name="Alpha Game (USA).sfc" size="${alpha.size}"
                         crc="${crc32(alpha)}" sha1="${sha1(alpha)}"/>
                  </game>
                  <game name="Beta Game (USA)">
                    <rom name="Beta Game (USA).sfc" size="${beta.size}"
                         crc="${crc32(beta)}" sha1="${sha1(beta)}"/>
                  </game>
                </datafile>
                """.trimIndent(),
            )
            writeFile("roms/Beta Game (USA).sfc", alpha)
            writeFile("roms/mystery.sfc", beta)
            importDat(root.resolve("test.dat"))

            val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
            val (sessionId, _) = scan()
            val plan =
                (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success)
                    .value
            val preview = PreviewRenamePlanUseCase(validate).preview(plan)

            assertEquals(
                PlanVerdict.EXECUTABLE,
                preview.validation.verdict,
                "A name held by another file in the same batch is an ordering problem, not a collision: " +
                    preview.validation.issues.map { it.message },
            )
            assertEquals(
                listOf("Beta Game (USA).sfc", "mystery.sfc"),
                preview.validation.executable.map { it.currentName },
                "The misnamed file must free the name before the real Beta takes it",
            )

            val result = ExecuteRenamePlanUseCase(validate, LocalRenameExecutor(), journal, clock, ids)
                .execute(plan)

            assertTrue(
                (result as Outcome.Success).value.summary.isFullySuccessful,
                result.value.summary.toString(),
            )
            assertEquals(
                listOf("Alpha Game (USA).sfc", "Beta Game (USA).sfc"),
                root.resolve("roms").listDirectoryEntries().map { it.name }.sorted(),
            )
            assertEquals(
                alpha.toList(),
                root.resolve("roms/Alpha Game (USA).sfc").readBytes().toList(),
                "Each file must end up under the name its own bytes earned",
            )
        }
    }

    @Test
    fun `an archive carrying macos bookkeeping is still identified by its rom`() = runTest {
        val bytes = payload(seed = 41)
        writeDat(
            "test.dat",
            """
            <datafile>
              <header><name>Test Console</name><version>1</version></header>
              <game name="Packed Game (USA)">
                <rom name="Packed Game (USA).sfc" size="${bytes.size}"
                     crc="${crc32(bytes)}" sha1="${sha1(bytes)}"/>
              </game>
            </datafile>
            """.trimIndent(),
        )
        writeZip(
            "roms/packed.zip",
            mapOf(
                "__MACOSX/._packed.sfc" to "resource fork".toByteArray(),
                "packed.sfc" to bytes,
                ".DS_Store" to "finder junk".toByteArray(),
            ),
        )
        importDat(root.resolve("test.dat"))

        val (_, events) = scan()
        val resolution = resolutionsByName(events).getValue("packed.zip")

        assertEquals(
            ResolutionState.EXACT_HASH,
            resolution.state,
            "Archiver bookkeeping must not turn a single-ROM archive into an ambiguous one",
        )
    }


    // ------------------------------------------------------------------
    // Optical media: PSP UMD images against Redump and non-Redump datasets
    // ------------------------------------------------------------------

    private fun writePspDat(bytes: ByteArray, name: String = "Some PSP Game (USA)") = writeDat(
        "redump-psp.dat",
        """
        <datafile>
          <header>
            <name>Sony - PlayStation Portable</name>
            <description>Sony - PlayStation Portable - Redump</description>
            <version>1</version>
            <author>Redump</author>
          </header>
          <game name="$name">
            <rom name="$name.iso" size="${bytes.size}"
                 crc="${crc32(bytes)}" md5="${md5(bytes)}" sha1="${sha1(bytes)}"/>
          </game>
        </datafile>
        """.trimIndent(),
    )

    private fun writeCartridgeDat(bytes: ByteArray) = writeDat(
        "no-intro-snes.dat",
        """
        <datafile>
          <header>
            <name>Nintendo - Super Nintendo Entertainment System</name>
            <version>1</version>
            <author>No-Intro</author>
          </header>
          <game name="Super Mario World (USA)">
            <rom name="Super Mario World (USA).sfc" size="${bytes.size}"
                 crc="${crc32(bytes)}" sha1="${sha1(bytes)}"/>
          </game>
        </datafile>
        """.trimIndent(),
    )

    @Test
    fun `a psp iso is verified against a redump dataset and renamed`() = runTest {
        val umd = payload(seed = 51, size = 65_536)
        writePspDat(umd)
        writeFile("psp/ULUS12345.iso", umd)
        importDat(root.resolve("redump-psp.dat"))

        val (sessionId, events) = scan()
        val resolution = resolutionsByName(events).getValue("ULUS12345.iso")

        assertTrue(
            resolution.state.isExact,
            "A UMD image is identified by its bytes like any other dump: ${resolution.state}",
        )
        assertTrue(resolution.isVerified)
        assertEquals(MediaType.OPTICAL_DISC, resolution.selected?.record?.mediaType)
        assertEquals(DatasetKind.REDUMP, resolution.selected?.record?.source?.kind)

        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val plan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value
        val result = ExecuteRenamePlanUseCase(validate, LocalRenameExecutor(), journal, clock, ids)
            .execute(plan)

        assertTrue((result as Outcome.Success).value.summary.isFullySuccessful)
        assertEquals(
            listOf("Some PSP Game (USA).iso"),
            root.resolve("psp").listDirectoryEntries().map { it.name },
        )
    }

    @Test
    fun `a psp library scanned against a cartridge dataset is reported as uncatalogued`() = runTest {
        val umd = payload(seed = 52, size = 65_536)
        val cart = payload(seed = 53)
        writeCartridgeDat(cart)
        writeFile("psp/ULUS12345.iso", umd)
        importDat(root.resolve("no-intro-snes.dat"))

        val (sessionId, events) = scan()
        val resolution = resolutionsByName(events).getValue("ULUS12345.iso")

        assertEquals(
            ResolutionState.OUT_OF_CATALOGUE_SCOPE,
            resolution.state,
            "The right dataset is missing; that is not the same as the file being unknown",
        )
        assertTrue(
            resolution.explanation.any { it.description.contains("optical disc") },
            "The user must be told which medium is uncovered: " +
                resolution.explanation.map { it.description },
        )

        val session = (sessions.find(sessionId) as Outcome.Success).value
        assertEquals(1, session.summary.outOfCatalogueScope)
        assertEquals(0, session.summary.unmatched, "Out-of-scope files must not inflate the unmatched count")
    }

    @Test
    fun `an uncatalogued psp iso is left untouched and explained`() = runTest {
        val umd = payload(seed = 54, size = 65_536)
        writeCartridgeDat(payload(seed = 55))
        writeFile("psp/ULUS12345.iso", umd)
        importDat(root.resolve("no-intro-snes.dat"))

        val (sessionId, _) = scan()
        val validate = ValidateRenamePlanUseCase(LocalContentSource(), clock)
        val plan =
            (GenerateRenamePlanUseCase(observations, clock, ids).generate(sessionId) as Outcome.Success).value
        val preview = PreviewRenamePlanUseCase(validate).preview(plan)

        assertEquals(PlanVerdict.NOTHING_TO_DO, preview.validation.verdict)
        val row = preview.rows.single { it.currentName == "ULUS12345.iso" }
        assertEquals(PlannedAction.SKIP_UNRESOLVED, row.action)
        assertEquals("NONE", row.identityBasis)
        assertTrue(
            row.reasons.any { it.contains("no imported dataset covers") } ||
                row.warnings.any { it.contains("covers this kind of media") },
            "The preview must say what to do about it: ${row.reasons + row.warnings}",
        )
        assertEquals(
            listOf("ULUS12345.iso"),
            root.resolve("psp").listDirectoryEntries().map { it.name },
            "Nothing may be renamed",
        )
    }

    @Test
    fun `importing the disc dataset turns an uncatalogued library into a verified one`() = runTest {
        val umd = payload(seed = 56, size = 65_536)
        writeCartridgeDat(payload(seed = 57))
        writeFile("psp/ULUS12345.iso", umd)
        importDat(root.resolve("no-intro-snes.dat"))

        val before = resolutionsByName(scan().second).getValue("ULUS12345.iso")
        assertEquals(ResolutionState.OUT_OF_CATALOGUE_SCOPE, before.state)

        writePspDat(umd)
        importDat(root.resolve("redump-psp.dat"))

        val after = resolutionsByName(scan().second).getValue("ULUS12345.iso")
        assertTrue(after.state.isExact, after.state.name)
        assertTrue(after.isVerified)
    }

    @Test
    fun `a disc dataset that does not list this disc reports absence, not uncovered media`() = runTest {
        writePspDat(payload(seed = 58, size = 65_536), name = "Another PSP Game (USA)")
        writeFile("psp/Mystery Game (USA).iso", payload(seed = 59, size = 32_768))
        importDat(root.resolve("redump-psp.dat"))

        val resolution = resolutionsByName(scan().second).getValue("Mystery Game (USA).iso")

        assertEquals(ResolutionState.NO_MATCH, resolution.state)
        assertFalse(
            resolution.explanation.any { it.description.contains("no imported dataset catalogues") },
            "The medium is covered, so nothing may claim otherwise",
        )
    }

    @Test
    fun `a mixed library identifies each medium against the dataset that covers it`() = runTest {
        val umd = payload(seed = 60, size = 65_536)
        val cart = payload(seed = 61)
        writePspDat(umd)
        writeCartridgeDat(cart)
        writeFile("psp/ULUS12345.iso", umd)
        writeFile("snes/smw.sfc", cart)
        importDat(root.resolve("redump-psp.dat"))
        importDat(root.resolve("no-intro-snes.dat"))

        val resolutions = resolutionsByName(scan().second)

        assertTrue(resolutions.getValue("ULUS12345.iso").state.isExact)
        assertTrue(resolutions.getValue("smw.sfc").state.isExact)
        assertEquals(
            MediaType.OPTICAL_DISC,
            resolutions.getValue("ULUS12345.iso").selected?.record?.mediaType,
        )
        assertEquals(
            MediaType.CARTRIDGE,
            resolutions.getValue("smw.sfc").selected?.record?.mediaType,
        )
    }

    private class SimulatedCrash : RuntimeException("simulated crash after the filesystem call")
}
