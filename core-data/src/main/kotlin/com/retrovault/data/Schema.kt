package com.retrovault.data

import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.identity.MediaTypeVocabulary

/**
 * Schema and forward-only migrations.
 *
 * DATABASE.md section 2 and 15: migrations only go forward, every change has a
 * number and deterministic code, and no migration silently drops user state.
 *
 * The schema keeps observations, conclusions and the reasons behind them as
 * separate tables, because DATABASE.md section 25 is explicit: store what was
 * observed, what was concluded, why, and what the user decided - never only
 * the conclusion.
 */
object Schema {

    const val CURRENT_VERSION: Int = 6

    /**
     * Applies every migration needed to bring [database] up to date.
     *
     * @return the version the database was on before this call.
     */
    fun migrate(database: SqlDatabase): Int = migrateTo(database, CURRENT_VERSION)

    /**
     * One schema version.
     *
     * [statements] is the DDL; [afterStatements] is for the rare change whose
     * data cannot be derived in SQL. DATABASE.md section 15 requires migration
     * code to be deterministic, not to be SQL - and reproducing a Kotlin
     * derivation in SQL is how the two drift apart.
     */
    private class Migration(
        val statements: List<String>,
        val afterStatements: (SqlDatabase) -> Unit = {},
    )

    /**
     * Applies migrations up to [targetVersion] only.
     *
     * Exists so that an upgrade can be tested from the schema version users
     * actually have, rather than only ever from an empty database. A migration
     * that is only exercised on a fresh install is a migration nobody has run.
     */
    internal fun migrateTo(database: SqlDatabase, targetVersion: Int): Int = database.transaction {
        // Foreign-key enforcement is switched on by each binding when it opens
        // the connection, not here. `PRAGMA foreign_keys` is a no-op inside a
        // transaction, and Android's execSQL rejects some PRAGMA statements
        // outright, so issuing it here would be misleading at best.
        database.execute(
            "CREATE TABLE IF NOT EXISTS schema_version (" +
                "version INTEGER NOT NULL, applied_at INTEGER NOT NULL)",
        )
        val current = database
            .query("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1") { it.getInt(0) }
            .firstOrNull() ?: 0

        migrations
            .filterKeys { it > current && it <= targetVersion }
            .toSortedMap()
            .forEach { (version, migration) ->
                migration.statements.forEach(database::execute)
                migration.afterStatements(database)
                database.execute(
                    "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                    listOf(version.toLong(), System.currentTimeMillis()),
                )
            }
        current
    }

    fun versionOf(database: SqlDatabase): Int =
        database
            .query("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1") { it.getInt(0) }
            .firstOrNull() ?: 0

    private val migrations: Map<Int, Migration> = mapOf(
        1 to Migration(version1()),
        2 to Migration(version2()),
        3 to Migration(version3()),
        4 to Migration(version4(), ::backfillReleaseIds),
        5 to Migration(version5()),
        6 to Migration(version6()),
    )

    /**
     * When RetroVault first learned an entity, and when it last changed.
     *
     * The minimum Constitution section 41 and section 70 need to be honoured
     * without a temporal system: "when did RetroVault first know this" and
     * "when did it last change its mind" become answerable, and a provenance
     * report can say so.
     *
     * This is deliberately *not* bitemporality. There is no valid-time, no
     * as-of query and no version table. What section 37 invariant 12 requires -
     * that historical identity remains recoverable - is served instead by never
     * discarding a name: a derived entity whose title changes keeps the old one
     * as an alias, which section 43 already lists historical names among.
     *
     * Existing rows get 0, which reads as "before RetroVault started recording
     * this" rather than as the epoch. A provenance report shows it as unknown
     * rather than as 1 January 1970.
     */
    private fun version5(): List<String> = listOf(
        "platform_entity",
        "work_entity",
        "release_entity",
        "artifact_entity",
    ).flatMap { table ->
        listOf(
            "ALTER TABLE $table ADD COLUMN first_seen_at INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE $table ADD COLUMN last_updated_at INTEGER NOT NULL DEFAULT 0",
        )
    }

    /**
     * Fills in the release each catalogued record projects into.
     *
     * The release identifier is derived from a normalized title, a sorted
     * region list and a sorted flag list. Reproducing that ordering in SQL
     * would be a second implementation of the same rule, free to drift from the
     * first - which is exactly the fault the media backfill was written to
     * avoid. So the records are read back through the same mapper the
     * application uses and the same promoter, and written out again.
     *
     * Bounded by chunking: a catalogue can hold hundreds of thousands of
     * records, and holding them all in memory during an upgrade is not
     * acceptable on a phone.
     */
    private fun backfillReleaseIds(database: SqlDatabase) {
        var offset = 0
        while (true) {
            val batch = database.query(
                RELEASE_BACKFILL_SELECT + " LIMIT $BACKFILL_BATCH OFFSET $offset",
            ) { row -> RecordMapper.map(row) }
            if (batch.isEmpty()) return
            batch.forEach { record ->
                database.execute(
                    "UPDATE dump_record SET release_id = ? WHERE id = ?",
                    listOf(EntityPromoter.releaseId(record.canonicalIdentityKey).value, record.id.value),
                )
            }
            offset += batch.size
        }
    }

    private const val BACKFILL_BATCH = 500

    private val RELEASE_BACKFILL_SELECT =
        "SELECT r.id, r.set_name, r.rom_name, r.size, r.platform, r.canonical_title, " +
            "r.normalized_title, r.revision, r.version, r.disc_number, r.status, r.external_id, " +
            "r.regions, r.languages, r.flags, " +
            "s.id, s.provider, s.set_name, s.version, s.platform, s.imported_at, s.source_digest, " +
            "r.media_type, s.kind " +
            "FROM dump_record r JOIN dat_source s ON s.id = r.source_id ORDER BY r.id"

    private fun version1(): List<String> = listOf(
        // --- Imported datasets -------------------------------------------
        """
        CREATE TABLE dat_source (
            id TEXT PRIMARY KEY NOT NULL,
            provider TEXT NOT NULL,
            set_name TEXT NOT NULL,
            version TEXT,
            platform TEXT NOT NULL,
            imported_at INTEGER NOT NULL,
            source_digest TEXT,
            state TEXT NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE dump_record (
            id TEXT PRIMARY KEY NOT NULL,
            source_id TEXT NOT NULL REFERENCES dat_source(id) ON DELETE CASCADE,
            set_name TEXT NOT NULL,
            rom_name TEXT NOT NULL,
            size INTEGER NOT NULL,
            platform TEXT NOT NULL,
            canonical_title TEXT NOT NULL,
            normalized_title TEXT NOT NULL,
            revision TEXT,
            version TEXT,
            disc_number INTEGER,
            status TEXT NOT NULL,
            external_id TEXT,
            regions TEXT NOT NULL,
            languages TEXT NOT NULL,
            flags TEXT NOT NULL
        )
        """.trimIndent(),

        // Hashes are a normalized table so a future algorithm needs no schema
        // redesign (DATABASE.md section 9).
        """
        CREATE TABLE dump_hash (
            record_id TEXT NOT NULL REFERENCES dump_record(id) ON DELETE CASCADE,
            algorithm TEXT NOT NULL,
            digest TEXT NOT NULL,
            PRIMARY KEY (record_id, algorithm)
        )
        """.trimIndent(),

        // Recall-oriented title index. Derived data: it can be rebuilt from
        // dump_record without loss (DATABASE.md section 12).
        """
        CREATE TABLE dump_title_token (
            record_id TEXT NOT NULL REFERENCES dump_record(id) ON DELETE CASCADE,
            token TEXT NOT NULL,
            PRIMARY KEY (record_id, token)
        )
        """.trimIndent(),

        // Indexes serve the actual access patterns of the matching ladder:
        // size filter, hash lookup, title fallback (DATABASE.md section 20).
        "CREATE INDEX idx_dump_record_size ON dump_record(size)",
        "CREATE INDEX idx_dump_record_source ON dump_record(source_id)",
        "CREATE INDEX idx_dump_hash_lookup ON dump_hash(algorithm, digest)",
        "CREATE INDEX idx_dump_title_token ON dump_title_token(token)",

        // --- Scans --------------------------------------------------------
        """
        CREATE TABLE scan_session (
            id TEXT PRIMARY KEY NOT NULL,
            root_ref TEXT NOT NULL,
            root_display_name TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            finished_at INTEGER,
            dat_source_ids TEXT NOT NULL,
            naming_profile TEXT NOT NULL,
            resolver_version TEXT NOT NULL,
            cancelled INTEGER NOT NULL DEFAULT 0,
            discovered INTEGER NOT NULL DEFAULT 0,
            processed INTEGER NOT NULL DEFAULT 0,
            exact INTEGER NOT NULL DEFAULT 0,
            strong INTEGER NOT NULL DEFAULT 0,
            review_required INTEGER NOT NULL DEFAULT 0,
            ambiguous INTEGER NOT NULL DEFAULT 0,
            unmatched INTEGER NOT NULL DEFAULT 0,
            failed INTEGER NOT NULL DEFAULT 0,
            hashes_computed INTEGER NOT NULL DEFAULT 0,
            hashing_skipped INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),

        """
        CREATE TABLE file_observation (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT NOT NULL REFERENCES scan_session(id) ON DELETE CASCADE,
            storage_ref TEXT NOT NULL,
            parent_ref TEXT NOT NULL,
            filename TEXT NOT NULL,
            relative_path TEXT NOT NULL,
            size INTEGER NOT NULL,
            last_modified INTEGER,
            container TEXT NOT NULL,
            observed_at INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE observation_hash (
            observation_id TEXT NOT NULL REFERENCES file_observation(id) ON DELETE CASCADE,
            entry_path TEXT NOT NULL DEFAULT '',
            algorithm TEXT NOT NULL,
            digest TEXT NOT NULL,
            PRIMARY KEY (observation_id, entry_path, algorithm)
        )
        """.trimIndent(),

        """
        CREATE TABLE observation_archive_entry (
            observation_id TEXT NOT NULL REFERENCES file_observation(id) ON DELETE CASCADE,
            entry_path TEXT NOT NULL,
            uncompressed_size INTEGER NOT NULL,
            nested_archive INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (observation_id, entry_path)
        )
        """.trimIndent(),

        """
        CREATE TABLE resolution (
            observation_id TEXT PRIMARY KEY NOT NULL
                REFERENCES file_observation(id) ON DELETE CASCADE,
            state TEXT NOT NULL,
            confidence TEXT NOT NULL,
            selected_record_id TEXT,
            hashes_computed TEXT NOT NULL,
            consulted_sources TEXT NOT NULL,
            resolver_version TEXT NOT NULL,
            tokenizer_version TEXT NOT NULL,
            normalizer_version TEXT NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE resolution_candidate (
            observation_id TEXT NOT NULL REFERENCES resolution(observation_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            record_id TEXT NOT NULL,
            score INTEGER NOT NULL,
            PRIMARY KEY (observation_id, ordinal)
        )
        """.trimIndent(),

        // Evidence is append-oriented and survives from resolver to audit
        // record (TRACEABILITY.md, DATABASE.md section 5).
        """
        CREATE TABLE resolution_evidence (
            observation_id TEXT NOT NULL REFERENCES resolution(observation_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            scope TEXT NOT NULL,
            candidate_record_id TEXT,
            signal_id TEXT NOT NULL,
            strength TEXT NOT NULL,
            supports INTEGER NOT NULL,
            excludes_identity INTEGER NOT NULL,
            description TEXT NOT NULL,
            source_id TEXT,
            PRIMARY KEY (observation_id, ordinal)
        )
        """.trimIndent(),

        "CREATE INDEX idx_observation_session ON file_observation(session_id)",
        "CREATE INDEX idx_observation_storage ON file_observation(storage_ref)",
        "CREATE INDEX idx_resolution_state ON resolution(state)",

        // --- Rename journal ----------------------------------------------
        """
        CREATE TABLE rename_batch (
            id TEXT PRIMARY KEY NOT NULL,
            plan_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            naming_profile TEXT NOT NULL,
            policy_version TEXT NOT NULL,
            dry_run INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE rename_operation (
            id TEXT PRIMARY KEY NOT NULL,
            batch_id TEXT NOT NULL REFERENCES rename_batch(id) ON DELETE CASCADE,
            plan_entry_id TEXT NOT NULL,
            source_ref TEXT NOT NULL,
            directory_ref TEXT NOT NULL,
            source_name TEXT NOT NULL,
            destination_name TEXT NOT NULL,
            resolution_state TEXT NOT NULL,
            confidence TEXT NOT NULL,
            identity_description TEXT NOT NULL,
            naming_profile TEXT NOT NULL,
            precondition_size INTEGER NOT NULL,
            precondition_hash_algorithm TEXT,
            precondition_hash_digest TEXT,
            state TEXT NOT NULL,
            failure_code TEXT,
            failure_detail TEXT,
            planned_at INTEGER NOT NULL,
            started_at INTEGER,
            finished_at INTEGER
        )
        """.trimIndent(),

        "CREATE INDEX idx_rename_operation_batch ON rename_operation(batch_id)",
        "CREATE INDEX idx_rename_operation_state ON rename_operation(state)",
    )

    /**
     * Where a rename left the file.
     *
     * A `DocumentsContract` rename can hand back a document URI unrelated to
     * the one it was given, so without this the journal can name what it
     * renamed but cannot address it afterwards - and an unaddressable rename
     * cannot be reversed (Constitution section 170). Nullable because an
     * operation that failed, was skipped, or was reconciled from the
     * filesystem was never handed a ref, and inventing one would be a claim
     * about where a file is.
     */
    private fun version6(): List<String> = listOf(
        "ALTER TABLE rename_operation ADD COLUMN result_ref TEXT",
    )

    /**
     * Records an unknown size as unknown, and marks records unfit for matching.
     *
     * Two changes, both to `dump_record`:
     *
     * - `size` becomes nullable. Some DATs state no size (`<disk>` entries in
     *   particular). Version 1 forced the importer to discard those records
     *   along with every hash they carried; now the size is simply absent and
     *   produces no evidence either way.
     * - `matchable` records whether the entry may identify a local file. A
     *   `nodump` entry carries a placeholder hash and a `baddump` entry carries
     *   the hash of a known-broken dump, so matching against either asserts a
     *   wrong identity confidently. They stay stored - Constitution section 199
     *   keeps imperfect artifacts as evidence - but lookups skip them.
     *
     * SQLite cannot relax a NOT NULL constraint in place, so the table is
     * rebuilt. Every existing row is copied with `matchable` derived from the
     * status already recorded, and no user state is dropped
     * (DATABASE.md section 15).
     *
     * The dependent tables are rebuilt in the same pass, and that ordering is
     * load-bearing. With foreign keys enforced, `DROP TABLE dump_record`
     * performs an implicit delete that fires `ON DELETE CASCADE` and would
     * empty `dump_hash` and `dump_title_token` - silently destroying the entire
     * lookup index. Copying the children onto the new parent *first* means
     * nothing references the old table by the time it is dropped. The final
     * renames restore the original names; SQLite rewrites the children's
     * foreign-key clauses to follow the parent's rename.
     */
    private fun version2(): List<String> = listOf(
        """
        CREATE TABLE dump_record_v2 (
            id TEXT PRIMARY KEY NOT NULL,
            source_id TEXT NOT NULL REFERENCES dat_source(id) ON DELETE CASCADE,
            set_name TEXT NOT NULL,
            rom_name TEXT NOT NULL,
            size INTEGER,
            platform TEXT NOT NULL,
            canonical_title TEXT NOT NULL,
            normalized_title TEXT NOT NULL,
            revision TEXT,
            version TEXT,
            disc_number INTEGER,
            status TEXT NOT NULL,
            external_id TEXT,
            regions TEXT NOT NULL,
            languages TEXT NOT NULL,
            flags TEXT NOT NULL,
            matchable INTEGER NOT NULL DEFAULT 1
        )
        """.trimIndent(),

        """
        INSERT INTO dump_record_v2 (id, source_id, set_name, rom_name, size, platform,
            canonical_title, normalized_title, revision, version, disc_number, status,
            external_id, regions, languages, flags, matchable)
        SELECT id, source_id, set_name, rom_name, size, platform,
            canonical_title, normalized_title, revision, version, disc_number, status,
            external_id, regions, languages, flags,
            CASE WHEN status IN ('BAD_DUMP', 'NO_DUMP') THEN 0 ELSE 1 END
        FROM dump_record
        """.trimIndent(),

        """
        CREATE TABLE dump_hash_v2 (
            record_id TEXT NOT NULL REFERENCES dump_record_v2(id) ON DELETE CASCADE,
            algorithm TEXT NOT NULL,
            digest TEXT NOT NULL,
            PRIMARY KEY (record_id, algorithm)
        )
        """.trimIndent(),

        "INSERT INTO dump_hash_v2 (record_id, algorithm, digest) " +
            "SELECT record_id, algorithm, digest FROM dump_hash",

        """
        CREATE TABLE dump_title_token_v2 (
            record_id TEXT NOT NULL REFERENCES dump_record_v2(id) ON DELETE CASCADE,
            token TEXT NOT NULL,
            PRIMARY KEY (record_id, token)
        )
        """.trimIndent(),

        "INSERT INTO dump_title_token_v2 (record_id, token) " +
            "SELECT record_id, token FROM dump_title_token",

        "DROP TABLE dump_hash",
        "DROP TABLE dump_title_token",
        "DROP TABLE dump_record",

        "ALTER TABLE dump_record_v2 RENAME TO dump_record",
        "ALTER TABLE dump_hash_v2 RENAME TO dump_hash",
        "ALTER TABLE dump_title_token_v2 RENAME TO dump_title_token",

        "CREATE INDEX idx_dump_record_size ON dump_record(size)",
        "CREATE INDEX idx_dump_record_source ON dump_record(source_id)",
        "CREATE INDEX idx_dump_record_matchable ON dump_record(matchable)",
        "CREATE INDEX idx_dump_hash_lookup ON dump_hash(algorithm, digest)",
        "CREATE INDEX idx_dump_title_token ON dump_title_token(token)",

        // The staging name a case-only rename passes through. Journalled
        // because a crash between the two steps leaves the file under a name
        // that appears nowhere else, and reconciliation has to be able to name
        // it back to the user.
        "ALTER TABLE rename_operation ADD COLUMN intermediate_name TEXT",
    )

    /**
     * Makes media type and dataset provenance first-class.
     *
     * Constitution section 322 and section 323 require media-specific evidence and
     * forbid a universal "one file = one hash = one game" assumption. Until now
     * the schema had no place to record what medium a dump came from, so a PSP
     * UMD image and a SNES cartridge dump were indistinguishable rows and
     * RetroVault could not tell a user that no imported dataset covers discs.
     *
     * `dump_record.media_type` is what makes coverage measurable, and
     * `dat_source.kind` records which preservation project a dataset came from.
     * Existing rows are backfilled from the rom-name extensions already stored,
     * so an upgraded catalogue gains coverage without a re-import. Extensions
     * that belong to more than one medium are left UNKNOWN, which reads as
     * "covers everything" and therefore never narrows what is consulted.
     */
    private fun version3(): List<String> = buildList {
        add("ALTER TABLE dump_record ADD COLUMN media_type TEXT NOT NULL DEFAULT 'UNKNOWN'")
        add("ALTER TABLE dat_source ADD COLUMN kind TEXT NOT NULL DEFAULT 'UNKNOWN'")

        MediaTypeBackfill.extensionsByMedia().forEach { (media, extensions) ->
            val predicate = extensions.joinToString(" OR ") { "LOWER(rom_name) LIKE '%.$it'" }
            add("UPDATE dump_record SET media_type = '$media' WHERE $predicate")
        }

        add("CREATE INDEX idx_dump_record_media ON dump_record(source_id, media_type)")

        // Scans now report "no dataset covers this medium" separately from
        // "not listed". Counting them together would hide the one problem the
        // user can actually fix.
        add("ALTER TABLE scan_session ADD COLUMN out_of_scope INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * The canonical entity graph and durable user corrections.
     *
     * Constitution section 305 states the model as
     * `Platform -> Work -> Release -> Artifact`. Until now the schema held only
     * external evidence - `dat_source` and `dump_record` - and the entities that
     * evidence *describes* existed nowhere, so nothing a user decided could
     * outlive the scan that produced it.
     *
     * Entity rows are projections, not a second catalogue. They carry no hashes
     * a `dump_record` does not already have; what they add is the identity those
     * records agree on, which is the thing a correction, a relationship or a
     * collection can be attached to.
     *
     * `identity_correction` is append-only. Section 69 forbids silently
     * rewriting history and section 70 requires earlier knowledge to stay
     * reconstructable, so superseding a correction inserts a row and marks the
     * old one rather than updating it in place.
     */
    private fun version4(): List<String> = listOf(
        """
        CREATE TABLE platform_entity (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            provenance TEXT NOT NULL DEFAULT 'DERIVED',
            aliases TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),

        """
        CREATE TABLE work_entity (
            id TEXT PRIMARY KEY NOT NULL,
            canonical_title TEXT NOT NULL,
            normalized_title TEXT NOT NULL,
            provenance TEXT NOT NULL DEFAULT 'DERIVED',
            aliases TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),

        """
        CREATE TABLE release_entity (
            id TEXT PRIMARY KEY NOT NULL,
            work_id TEXT NOT NULL REFERENCES work_entity(id) ON DELETE CASCADE,
            platform_id TEXT NOT NULL REFERENCES platform_entity(id) ON DELETE CASCADE,
            regions TEXT NOT NULL DEFAULT '',
            languages TEXT NOT NULL DEFAULT '',
            revision TEXT,
            version TEXT,
            disc_number INTEGER,
            flags TEXT NOT NULL DEFAULT '',
            provenance TEXT NOT NULL DEFAULT 'DERIVED',
            aliases TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),

        """
        CREATE TABLE artifact_entity (
            id TEXT PRIMARY KEY NOT NULL,
            release_id TEXT NOT NULL REFERENCES release_entity(id) ON DELETE CASCADE,
            media_type TEXT NOT NULL DEFAULT 'UNKNOWN',
            size INTEGER,
            provenance TEXT NOT NULL DEFAULT 'DERIVED',
            aliases TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),

        // Artifact hashes are normalized for the same reason dump_hash is: a
        // future algorithm must not need a schema redesign
        // (DATABASE.md section 9).
        """
        CREATE TABLE artifact_hash (
            artifact_id TEXT NOT NULL REFERENCES artifact_entity(id) ON DELETE CASCADE,
            algorithm TEXT NOT NULL,
            digest TEXT NOT NULL,
            PRIMARY KEY (artifact_id, algorithm)
        )
        """.trimIndent(),

        // The edge is its endpoints and its type, so that is the key. Asserting
        // the same relationship twice is not two facts.
        """
        CREATE TABLE entity_relationship (
            from_kind TEXT NOT NULL,
            from_id TEXT NOT NULL,
            type TEXT NOT NULL,
            to_kind TEXT NOT NULL,
            to_id TEXT NOT NULL,
            provenance TEXT NOT NULL DEFAULT 'DERIVED',
            note TEXT,
            PRIMARY KEY (from_kind, from_id, type, to_kind, to_id)
        )
        """.trimIndent(),

        // Keyed by content, never by filename or observation id: a correction
        // has to find its file after a rename and after the next scan.
        """
        CREATE TABLE identity_correction (
            id TEXT PRIMARY KEY NOT NULL,
            scope_algorithm TEXT NOT NULL,
            scope_digest TEXT NOT NULL,
            scope_size INTEGER,
            previous_identity TEXT,
            corrected_kind TEXT NOT NULL,
            corrected_release_id TEXT,
            reason TEXT,
            recorded_at INTEGER NOT NULL,
            state TEXT NOT NULL,
            superseded_by TEXT
        )
        """.trimIndent(),

        "CREATE INDEX idx_release_work ON release_entity(work_id)",
        "CREATE INDEX idx_artifact_release ON artifact_entity(release_id)",
        "CREATE INDEX idx_artifact_hash_lookup ON artifact_hash(algorithm, digest)",
        "CREATE INDEX idx_relationship_from ON entity_relationship(from_kind, from_id)",
        "CREATE INDEX idx_relationship_to ON entity_relationship(to_kind, to_id)",
        "CREATE INDEX idx_correction_scope ON identity_correction(scope_algorithm, scope_digest, state)",

        // The release each record projects into, stored so a correction can
        // name any catalogued release rather than only ones a scan happened to
        // promote. Derived data: it can be recomputed from the record at any
        // time (DATABASE.md section 12).
        "ALTER TABLE dump_record ADD COLUMN release_id TEXT",
        "CREATE INDEX idx_dump_record_release ON dump_record(release_id)",
    )
}

/**
 * The extension-to-medium table, in the form the migration needs.
 *
 * Deliberately derived from the domain vocabulary rather than duplicated here:
 * a backfill that disagreed with live classification would produce a catalogue
 * whose stored media types drift from what a re-import would produce.
 */
internal object MediaTypeBackfill {
    fun extensionsByMedia(): Map<String, List<String>> =
        MediaTypeVocabulary.knownExtensions()
            .entries
            .groupBy({ it.value.name }, { it.key })
            .toSortedMap()
}
