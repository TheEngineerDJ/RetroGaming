package com.retrovault.data

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

    const val CURRENT_VERSION: Int = 1

    /**
     * Applies every migration needed to bring [database] up to date.
     *
     * @return the version the database was on before this call.
     */
    fun migrate(database: SqlDatabase): Int = database.transaction {
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
            .filterKeys { it > current }
            .toSortedMap()
            .forEach { (version, statements) ->
                statements.forEach(database::execute)
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

    private val migrations: Map<Int, List<String>> = mapOf(1 to version1())

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
}
