package com.retrovault.data

import com.retrovault.data.jdbc.JdbcSqlDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upgrades from the schema users already have.
 *
 * DATABASE.md section 15: migrations go forward only and no migration silently
 * drops user state. Version 2 rebuilds `dump_record`, which under enforced
 * foreign keys is the single most destructive thing this codebase does - a
 * naive `DROP TABLE` cascades and empties the entire hash index. These tests
 * exist so that stays impossible.
 */
class SchemaMigrationTest {

    private lateinit var database: JdbcSqlDatabase

    @BeforeTest
    fun setUp() {
        database = JdbcSqlDatabase.inMemory()
        Schema.migrateTo(database, 1)
    }

    @AfterTest
    fun tearDown() = database.close()

    private fun seedVersion1() {
        database.execute(
            "INSERT INTO dat_source (id, provider, set_name, version, platform, imported_at, " +
                "source_digest, state) VALUES ('src', 'no_intro', 'Test', '1', 'Test', 1, NULL, 'ready')",
        )
        listOf("good" to "GOOD", "bad" to "BAD_DUMP", "none" to "NO_DUMP").forEach { (id, status) ->
            database.execute(
                "INSERT INTO dump_record (id, source_id, set_name, rom_name, size, platform, " +
                    "canonical_title, normalized_title, status, regions, languages, flags) " +
                    "VALUES (?, 'src', ?, ?, 4096, 'Test', ?, ?, ?, '', '', '')",
                listOf(id, "Set $id", "$id.sfc", "Set $id", "set $id", status),
            )
            database.execute(
                "INSERT INTO dump_hash (record_id, algorithm, digest) VALUES (?, 'CRC32', 'aabbccdd')",
                listOf(id),
            )
            database.execute(
                "INSERT INTO dump_title_token (record_id, token) VALUES (?, 'set')",
                listOf(id),
            )
        }
    }

    private fun countOf(table: String): Int =
        database.query("SELECT COUNT(*) FROM $table") { it.getInt(0) }.first()

    @Test
    fun `upgrading from version 1 keeps every hash and title token`() {
        seedVersion1()

        Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
        assertEquals(3, countOf("dump_record"))
        assertEquals(3, countOf("dump_hash"), "Rebuilding dump_record must not cascade into the hashes")
        assertEquals(3, countOf("dump_title_token"))
    }

    @Test
    fun `upgrading derives matchability from the status already recorded`() {
        seedVersion1()

        Schema.migrate(database)

        val matchable = database
            .query("SELECT id FROM dump_record WHERE matchable = 1 ORDER BY id") { it.getString(0) }
        assertEquals(listOf("good"), matchable)
    }

    @Test
    fun `the rebuilt table accepts a record with no size`() {
        seedVersion1()
        Schema.migrate(database)

        database.execute(
            "INSERT INTO dump_record (id, source_id, set_name, rom_name, size, platform, " +
                "canonical_title, normalized_title, status, regions, languages, flags) " +
                "VALUES ('disk', 'src', 'Disk', 'disk.chd', NULL, 'Test', 'Disk', 'disk', 'GOOD', '', '', '')",
        )

        val size = database
            .query("SELECT size FROM dump_record WHERE id = 'disk'") { it.getLongOrNull(0) }
            .single()
        assertNull(size)
    }

    @Test
    fun `foreign keys still cascade after the rebuild`() {
        seedVersion1()
        Schema.migrate(database)

        database.execute("DELETE FROM dat_source WHERE id = 'src'")

        assertEquals(0, countOf("dump_record"))
        assertEquals(0, countOf("dump_hash"), "The rebuilt children must still hang off dump_record")
        assertEquals(0, countOf("dump_title_token"))
    }

    @Test
    fun `the rename journal gains its staging column without losing operations`() {
        database.execute(
            "INSERT INTO rename_batch (id, plan_id, session_id, naming_profile, policy_version, " +
                "dry_run, created_at) VALUES ('batch', 'plan', 'session', 'p', 'v', 0, 1)",
        )
        database.execute(
            "INSERT INTO rename_operation (id, batch_id, plan_entry_id, source_ref, directory_ref, " +
                "source_name, destination_name, resolution_state, confidence, identity_description, " +
                "naming_profile, precondition_size, state, planned_at) " +
                "VALUES ('op', 'batch', 'entry', 'ref', 'dir', 'a.sfc', 'b.sfc', 'EXACT_HASH', " +
                "'EXACT', 'x', 'p', 10, 'PLANNED', 1)",
        )

        Schema.migrate(database)

        assertEquals(1, countOf("rename_operation"))
        val staging = database
            .query("SELECT intermediate_name FROM rename_operation") { it.getStringOrNull(0) }
            .single()
        assertNull(staging, "An operation written before the column existed has no staging name")
    }

    @Test
    fun `migrating twice changes nothing`() {
        seedVersion1()
        Schema.migrate(database)

        val previous = Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, previous)
        assertEquals(3, countOf("dump_hash"))
        assertTrue(countOf("dump_record") == 3)
    }

    // ------------------------------------------------------------------
    // Version 3: media type and dataset provenance
    // ------------------------------------------------------------------

    private fun seedVersion2WithMedia() {
        Schema.migrateTo(database, 2)
        database.execute(
            "INSERT INTO dat_source (id, provider, set_name, version, platform, imported_at, " +
                "source_digest, state) VALUES ('src', 'redump', 'PSP', '1', 'PSP', 1, NULL, 'ready')",
        )
        listOf(
            "disc" to "Some Game (USA).iso",
            "cart" to "Some Game (USA).sfc",
            "track" to "Some Game (USA) (Track 1).bin",
        ).forEach { (id, romName) ->
            database.execute(
                "INSERT INTO dump_record (id, source_id, set_name, rom_name, size, platform, " +
                    "canonical_title, normalized_title, status, regions, languages, flags) " +
                    "VALUES (?, 'src', 'Some Game (USA)', ?, 100, 'PSP', 'Some Game', 'some game', " +
                    "'GOOD', '', '', '')",
                listOf(id, romName),
            )
        }
    }

    private fun mediaOf(id: String): String = database
        .query("SELECT media_type FROM dump_record WHERE id = ?", listOf(id)) { it.getString(0) }
        .single()

    @Test
    fun `upgrading backfills media type from the rom names already stored`() {
        // An existing catalogue must gain coverage without a re-import.
        // Otherwise every user who upgrades is told their whole library is
        // out of scope until they re-import every DAT.
        seedVersion2WithMedia()

        Schema.migrate(database)

        assertEquals("OPTICAL_DISC", mediaOf("disc"))
        assertEquals("CARTRIDGE", mediaOf("cart"))
    }

    @Test
    fun `the backfill leaves an ambiguous extension unknown`() {
        // `.bin` is both a cartridge dump and a CD track. UNKNOWN reads as
        // "covers everything", so guessing here would be the only way this
        // could narrow a search wrongly.
        seedVersion2WithMedia()

        Schema.migrate(database)

        assertEquals("UNKNOWN", mediaOf("track"))
    }

    @Test
    fun `datasets imported before provenance existed default to unknown`() {
        seedVersion2WithMedia()

        Schema.migrate(database)

        val kind = database
            .query("SELECT kind FROM dat_source WHERE id = 'src'") { it.getString(0) }
            .single()
        assertEquals(
            "UNKNOWN",
            kind,
            "Provenance is read from the DAT, so a row written before it existed cannot claim one",
        )
    }

    @Test
    fun `upgrading to version 3 preserves every record`() {
        seedVersion2WithMedia()

        Schema.migrate(database)

        assertEquals(3, countOf("dump_record"))
        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
    }
}
