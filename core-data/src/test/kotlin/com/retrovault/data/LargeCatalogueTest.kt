package com.retrovault.data

import com.retrovault.application.Outcome
import com.retrovault.data.jdbc.JdbcSqlDatabase
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.naming.TitleNormalizer
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Behaviour at a scale where SQLite's own limits bite.
 *
 * Regression coverage for a defect a small fixture cannot reveal: an
 * `IN (?, ?, ...)` clause built from a result set exceeds
 * `SQLITE_MAX_VARIABLE_NUMBER` as soon as a lookup matches enough records. The
 * limit is 999 on the SQLite build Android ships, and 32766 on the modern
 * build used here.
 *
 * That difference matters for reading these tests honestly: on the JVM they do
 * not reproduce the Android failure, because the JVM limit is far higher. What
 * they do prove is that the chunked query path returns complete and correct
 * results - every record, with every hash, reassembled across chunk
 * boundaries. The record count is chosen to be larger than the chunk size, so
 * the multi-chunk path is genuinely exercised. Verifying the limit itself
 * requires a device.
 *
 * Constitution section 249 separately requires bounded memory, so the textual
 * fallback caps how many candidates it will hold; that cap is verified here.
 */
class LargeCatalogueTest {

    private lateinit var database: JdbcSqlDatabase
    private lateinit var catalog: SqlDumpCatalog

    private val source = DatSourceRef(
        id = DatSourceId("no_intro:Big Console:1"),
        provider = "no_intro",
        setName = "Big Console",
        version = "1",
        platform = PlatformName("Big Console"),
        importedAtEpochMillis = 0,
    )

    /** Comfortably past the 999-parameter limit. */
    private val recordCount = 1_500

    @BeforeTest
    fun setUp() {
        database = JdbcSqlDatabase.inMemory()
        Schema.migrate(database)
        catalog = SqlDumpCatalog(database)
    }

    @AfterTest
    fun tearDown() = database.close()

    private suspend fun importSharedSizeRecords() {
        assertIs<Outcome.Success<*>>(catalog.beginImport(source))
        val records = (0 until recordCount).map { index ->
            DumpRecord.derive(
                id = DumpRecordId("record-$index"),
                source = source,
                // Same size for every record, so one size lookup returns them
                // all - which is exactly what a real DAT of fixed-size carts
                // does.
                setName = "Shared Size Game $index (USA)",
                romName = "game$index.sfc",
                size = 4096,
                hashes = HashDigests.of(HashValue.of(HashAlgorithm.CRC32, "%08x".format(index))),
            )
        }
        records.chunked(250).forEach { chunk ->
            assertIs<Outcome.Success<*>>(catalog.writeBatch(source.id, chunk))
        }
        assertIs<Outcome.Success<*>>(catalog.commitImport(source.id))
    }

    @Test
    fun `a size lookup matching more records than the parameter limit still works`() = runTest {
        importSharedSizeRecords()

        val found = catalog.findBySize(4096)

        assertEquals(recordCount, found.size)
        assertTrue(
            found.all { it.hashes.contains(HashAlgorithm.CRC32) },
            "Hashes must be loaded for every record, across every query chunk",
        )
    }

    @Test
    fun `hash lookup remains exact at scale`() = runTest {
        importSharedSizeRecords()

        val found = catalog.findByHash(HashValue.of(HashAlgorithm.CRC32, "%08x".format(1_234)))

        assertEquals(1, found.size)
        assertEquals("record-1234", found.single().id.value)
    }

    @Test
    fun `title lookup is bounded and prefers the closest candidates`() = runTest {
        importSharedSizeRecords()

        // "shared size game" is a token set shared by every record, so an
        // unbounded query would return all 1,500.
        val found = catalog.findByNormalizedTitle(
            TitleNormalizer.normalize("Shared Size Game 1234"),
        )

        assertTrue(
            found.size <= 500,
            "The fallback must bound how many candidates it holds; got ${found.size}",
        )
        assertTrue(
            found.any { it.id.value == "record-1234" },
            "The best-matching record must survive the bound",
        )
    }
}
