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
        assertEquals(
            listOf("CARTRIDGE", "CARTRIDGE", "CARTRIDGE"),
            database.query("SELECT media_type FROM dump_record ORDER BY id") { it.getString(0) },
            "A version 1 database must reach version 3 with its media backfilled, not left UNKNOWN",
        )
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
                // Regions are populated because a real version 2 row is: the
                // catalogue derives them at import. Seeding them empty would
                // make the backfill look wrong when it was faithfully
                // reproducing what the row actually says.
                "INSERT INTO dump_record (id, source_id, set_name, rom_name, size, platform, " +
                    "canonical_title, normalized_title, status, regions, languages, flags) " +
                    "VALUES (?, 'src', 'Some Game (USA)', ?, 100, 'PSP', 'Some Game', 'some game', " +
                    "'GOOD', 'USA', '', '')",
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

    // ------------------------------------------------------------------
    // Version 4: the canonical entity graph and durable corrections
    // ------------------------------------------------------------------

    @Test
    fun `upgrading from version 3 adds the entity graph without touching the catalogue`() {
        Schema.migrateTo(database, 2)
        seedVersion2WithMedia()
        Schema.migrateTo(database, 3)
        val recordsBefore = countOf("dump_record")

        Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
        assertEquals(recordsBefore, countOf("dump_record"), "A projection must not disturb its source")
        listOf(
            "platform_entity",
            "work_entity",
            "release_entity",
            "artifact_entity",
            "artifact_hash",
            "entity_relationship",
            "identity_correction",
        ).forEach { table -> assertEquals(0, countOf(table), table) }
    }

    @Test
    fun `a version 1 database reaches version 4 in one pass`() {
        // Users upgrade from whatever they have, not from the version before
        // this one.
        seedVersion1()

        Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
        assertEquals(3, countOf("dump_record"))
        assertEquals(3, countOf("dump_hash"))
        assertEquals(0, countOf("identity_correction"))
    }

    @Test
    fun `the entity graph enforces its own foreign keys`() {
        Schema.migrate(database)

        val orphanRelease = runCatching {
            database.execute(
                "INSERT INTO release_entity (id, work_id, platform_id) VALUES ('r', 'missing', 'also')",
            )
        }
        val orphanArtifact = runCatching {
            database.execute(
                "INSERT INTO artifact_entity (id, release_id) VALUES ('a', 'missing')",
            )
        }

        assertTrue(orphanRelease.isFailure, "A release must belong to a work that exists")
        assertTrue(orphanArtifact.isFailure, "An artifact must belong to a release that exists")
    }

    @Test
    fun `a correction outlives the catalogue it was made against`() {
        // Constitution section 69: the previous claim survives. A correction is
        // the user's, so removing a dataset must not remove their decision.
        Schema.migrate(database)
        seedVersion1()
        database.execute(
            "INSERT INTO identity_correction (id, scope_algorithm, scope_digest, corrected_kind, " +
                "recorded_at, state) VALUES ('c1', 'SHA1', 'aa', 'NOT_THIS', 1, 'ACTIVE')",
        )

        database.execute("DELETE FROM dat_source")

        assertEquals(0, countOf("dump_record"))
        assertEquals(1, countOf("identity_correction"))
    }

    @Test
    fun `upgrading backfills the release each catalogued record projects into`() {
        // A correction names a release. Without this, only records imported
        // after the upgrade could be named, so a user upgrading with a
        // catalogue already in place could correct nothing.
        Schema.migrateTo(database, 2)
        seedVersion2WithMedia()
        Schema.migrateTo(database, 3)

        Schema.migrate(database)

        val releaseIds = database
            .query("SELECT DISTINCT release_id FROM dump_record") { it.getStringOrNull(0) }
        assertTrue(releaseIds.all { it != null && it.startsWith("release:") }, releaseIds.toString())
        assertEquals(
            1,
            releaseIds.size,
            "All three seeded records are the same release in different forms",
        )
    }

    @Test
    fun `the backfill agrees with what a fresh import would write`() {
        // The upgrade path and the import path must not derive the key
        // differently, or an upgraded catalogue answers corrections one way and
        // a re-imported one another.
        Schema.migrateTo(database, 2)
        seedVersion2WithMedia()
        Schema.migrate(database)

        val backfilled = database
            .query("SELECT release_id FROM dump_record WHERE id = 'disc'") { it.getString(0) }
            .single()

        val fresh = com.retrovault.domain.entity.EntityPromoter.releaseId(
            com.retrovault.domain.catalog.DumpRecord.derive(
                id = com.retrovault.domain.identity.DumpRecordId("probe"),
                source = com.retrovault.domain.catalog.DatSourceRef(
                    id = com.retrovault.domain.identity.DatSourceId("src"),
                    provider = "redump",
                    setName = "PSP",
                    version = "1",
                    platform = com.retrovault.domain.identity.PlatformName("PSP"),
                    importedAtEpochMillis = 1,
                ),
                setName = "Some Game (USA)",
                romName = "Some Game (USA).iso",
                size = 100,
                hashes = com.retrovault.domain.identity.HashDigests.EMPTY,
            ).canonicalIdentityKey,
        ).value

        assertEquals(fresh, backfilled)
    }

    // ------------------------------------------------------------------
    // Version 5: when an entity was first seen and last changed
    // ------------------------------------------------------------------

    @Test
    fun `upgrading from version 4 gives every entity table its timestamps`() {
        Schema.migrateTo(database, 4)
        database.execute("INSERT INTO platform_entity (id, name) VALUES ('p', 'Test')")
        database.execute(
            "INSERT INTO work_entity (id, canonical_title, normalized_title) VALUES ('w', 'Some', 'some')",
        )
        database.execute(
            "INSERT INTO release_entity (id, work_id, platform_id) VALUES ('r', 'w', 'p')",
        )
        database.execute("INSERT INTO artifact_entity (id, release_id) VALUES ('a', 'r')")

        Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
        listOf("platform_entity", "work_entity", "release_entity", "artifact_entity").forEach { table ->
            assertEquals(1, countOf(table), "$table must keep the row it already had")
            val stamps = database.query("SELECT first_seen_at, last_updated_at FROM $table") {
                it.getLong(0) to it.getLong(1)
            }.single()
            assertEquals(
                0L to 0L,
                stamps,
                "A row that predates timestamps must not be backdated to a time nobody observed",
            )
        }
    }

    @Test
    fun `upgrading from version 5 lets the journal say where a rename left a file`() {
        Schema.migrateTo(database, 5)
        database.execute(
            "INSERT INTO rename_batch (id, plan_id, session_id, naming_profile, policy_version, " +
                "dry_run, created_at) VALUES ('b', 'p', 's', 'no-intro@v1', 'automation-policy-v1', 0, 1)",
        )
        database.execute(
            "INSERT INTO rename_operation (id, batch_id, plan_entry_id, source_ref, directory_ref, " +
                "source_name, destination_name, resolution_state, confidence, identity_description, " +
                "naming_profile, precondition_size, state, planned_at) VALUES " +
                "('o', 'b', 'e', 'file:///a', 'file:///', 'a.sfc', 'B.sfc', 'EXACT_HASH', 'EXACT', " +
                "'some game', 'no-intro@v1', 4096, 'COMPLETED', 1)",
        )

        Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
        assertEquals(1, countOf("rename_operation"), "The upgrade must keep the operations already recorded")
        assertNull(
            database.query("SELECT result_ref FROM rename_operation") { it.getStringOrNull(0) }.single(),
            "A rename recorded before this column existed was never handed a ref, and one must not be invented",
        )
    }

    @Test
    fun `a version 1 database reaches the current version in one pass`() {
        seedVersion1()

        Schema.migrate(database)

        assertEquals(Schema.CURRENT_VERSION, Schema.versionOf(database))
        assertEquals(3, countOf("dump_record"))
        assertEquals(0, countOf("platform_entity"))
    }
}
