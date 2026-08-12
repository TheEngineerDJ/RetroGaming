package com.retrovault.data

import com.retrovault.application.DatCatalogWriter
import com.retrovault.application.DumpCatalog
import com.retrovault.application.Outcome
import com.retrovault.application.RetroVaultFailure
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.naming.NormalizedTitle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Indexed catalogue over SQLite.
 *
 * Import state is explicit: a dataset is `importing` until it commits, and only
 * `ready` datasets are visible to lookups. A DAT that fails half-way therefore
 * never contributes to an identification (DATABASE.md section 16).
 */
class SqlDumpCatalog(
    private val database: SqlDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DumpCatalog, DatCatalogWriter {

    // ---------------------------------------------------------------- reads

    override suspend fun findBySize(size: Long): List<DumpRecord> = read {
        selectRecords("r.size = ?", listOf(size))
    }

    override suspend fun findByHash(hash: HashValue): List<DumpRecord> = read {
        selectRecords(
            where = "r.id IN (SELECT record_id FROM dump_hash WHERE algorithm = ? AND digest = ?)",
            arguments = listOf(hash.algorithm.name, hash.hex),
        )
    }

    /**
     * Recall-oriented: any record sharing a title token is returned, and the
     * resolver decides what survives. Narrowing here would hide candidates the
     * user is entitled to see.
     */
    override suspend fun findByNormalizedTitle(title: NormalizedTitle): List<DumpRecord> = read {
        val tokens = title.tokens().distinct()
        if (tokens.isEmpty()) return@read emptyList()
        val placeholders = tokens.joinToString(",") { "?" }
        selectRecords(
            where = "r.id IN (SELECT record_id FROM dump_title_token WHERE token IN ($placeholders))",
            arguments = tokens,
        )
    }

    override suspend fun sources(): List<DatSourceRef> = read {
        database.query(
            "SELECT id, provider, set_name, version, platform, imported_at, source_digest " +
                "FROM dat_source WHERE state = ? ORDER BY id",
            listOf(STATE_READY),
        ) { row -> row.toSource() }
    }

    // --------------------------------------------------------------- writes

    override suspend fun beginImport(source: DatSourceRef): Outcome<DatSourceId> = write {
        database.transaction {
            // Re-importing a dataset replaces the previous version of that
            // exact source id. Records cascade, so a stale index cannot linger.
            database.execute("DELETE FROM dat_source WHERE id = ?", listOf(source.id.value))
            database.execute(
                "INSERT INTO dat_source " +
                    "(id, provider, set_name, version, platform, imported_at, source_digest, state) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    source.id.value,
                    source.provider,
                    source.setName,
                    source.version,
                    source.platform.value,
                    source.importedAtEpochMillis,
                    source.sourceDigest,
                    STATE_IMPORTING,
                ),
            )
        }
        source.id
    }

    override suspend fun writeBatch(sourceId: DatSourceId, records: List<DumpRecord>): Outcome<Int> = write {
        if (records.isEmpty()) return@write 0
        database.transaction {
            records.forEach { record -> insertRecord(sourceId, record) }
        }
        records.size
    }

    override suspend fun commitImport(sourceId: DatSourceId): Outcome<Unit> = write {
        database.execute(
            "UPDATE dat_source SET state = ? WHERE id = ?",
            listOf(STATE_READY, sourceId.value),
        )
    }

    override suspend fun rollbackImport(sourceId: DatSourceId): Outcome<Unit> = write {
        // Cascades remove every record written by the failed import.
        database.execute("DELETE FROM dat_source WHERE id = ?", listOf(sourceId.value))
    }

    private fun insertRecord(sourceId: DatSourceId, record: DumpRecord) {
        database.execute(
            "INSERT OR REPLACE INTO dump_record (id, source_id, set_name, rom_name, size, platform, " +
                "canonical_title, normalized_title, revision, version, disc_number, status, external_id, " +
                "regions, languages, flags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                record.id.value,
                sourceId.value,
                record.setName,
                record.romName,
                record.size,
                record.platform.value,
                record.canonicalTitle,
                record.normalizedTitle.key,
                record.revision,
                record.version,
                record.discNumber?.toLong(),
                record.status.name,
                record.externalId,
                RecordMapper.encodeList(record.regions.map { it.code }),
                RecordMapper.encodeList(record.languages.map { it.code }),
                RecordMapper.encodeList(record.flags.map { it.name }.sorted()),
            ),
        )
        record.hashes.asList().forEach { hash ->
            database.execute(
                "INSERT OR REPLACE INTO dump_hash (record_id, algorithm, digest) VALUES (?, ?, ?)",
                listOf(record.id.value, hash.algorithm.name, hash.hex),
            )
        }
        record.normalizedTitle.tokens().distinct().forEach { token ->
            database.execute(
                "INSERT OR REPLACE INTO dump_title_token (record_id, token) VALUES (?, ?)",
                listOf(record.id.value, token),
            )
        }
    }

    // ------------------------------------------------------------- plumbing

    private fun selectRecords(where: String, arguments: List<Any?>): List<DumpRecord> {
        val rows = database.query(
            "SELECT r.id, r.set_name, r.rom_name, r.size, r.platform, r.canonical_title, " +
                "r.normalized_title, r.revision, r.version, r.disc_number, r.status, r.external_id, " +
                "r.regions, r.languages, r.flags, " +
                "s.id, s.provider, s.set_name, s.version, s.platform, s.imported_at, s.source_digest " +
                "FROM dump_record r JOIN dat_source s ON s.id = r.source_id " +
                "WHERE s.state = ? AND ($where) ORDER BY r.id",
            listOf(STATE_READY) + arguments,
        ) { row -> RecordMapper.map(row) }

        if (rows.isEmpty()) return emptyList()
        val hashes = RecordMapper.loadHashes(database, rows.map { it.id.value })
        return rows.map { record -> record.copy(hashes = hashes[record.id.value] ?: HashDigests.EMPTY) }
    }

    private fun SqlRow.toSource(): DatSourceRef = DatSourceRef(
        id = DatSourceId(getString(0)),
        provider = getString(1),
        setName = getString(2),
        version = getStringOrNull(3),
        platform = PlatformName(getString(4)),
        importedAtEpochMillis = getLong(5),
        sourceDigest = getStringOrNull(6),
    )

    private suspend fun <T> read(body: () -> T): T = withContext(dispatcher) { body() }

    private suspend fun <T> write(body: () -> T): Outcome<T> = withContext(dispatcher) {
        try {
            Outcome.success(body())
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }

    private companion object {
        const val STATE_IMPORTING = "importing"
        const val STATE_READY = "ready"
    }
}
