package com.retrovault.data

import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.ReleaseFlag
import com.retrovault.domain.naming.NormalizedTitle

/**
 * Maps catalogue rows to domain records.
 *
 * Shared by the catalogue and by observation loading so that a record read
 * through either path is identical - a resolution replayed from the journal
 * must describe the same identity the scan described.
 */
internal object RecordMapper {

    /**
     * ASCII unit separator. Region, language and flag codes are drawn from
     * controlled vocabularies that cannot contain it.
     */
    const val SEPARATOR: String = "\u001F"

    /**
     * Column order expected by [map]:
     * `id, set_name, rom_name, size, platform, canonical_title, normalized_title,
     * revision, version, disc_number, status, external_id, regions, languages,
     * flags, source_id, provider, source_set_name, source_version,
     * source_platform, imported_at, source_digest`
     */
    fun map(row: SqlRow): DumpRecord = DumpRecord(
        id = DumpRecordId(row.getString(0)),
        source = DatSourceRef(
            id = DatSourceId(row.getString(15)),
            provider = row.getString(16),
            setName = row.getString(17),
            version = row.getStringOrNull(18),
            platform = PlatformName(row.getString(19)),
            importedAtEpochMillis = row.getLong(20),
            sourceDigest = row.getStringOrNull(21),
        ),
        setName = row.getString(1),
        romName = row.getString(2),
        size = row.getLongOrNull(3),
        hashes = HashDigests.EMPTY,
        platform = PlatformName(row.getString(4)),
        canonicalTitle = row.getString(5),
        normalizedTitle = NormalizedTitle(row.getString(6)),
        regions = row.getString(12).splitList().map(::RegionCode),
        languages = row.getString(13).splitList().map(::LanguageCode),
        revision = row.getStringOrNull(7),
        version = row.getStringOrNull(8),
        discNumber = row.getLongOrNull(9)?.toInt(),
        flags = row.getString(14).splitList()
            .mapNotNull { name -> runCatching { ReleaseFlag.valueOf(name) }.getOrNull() }
            .toSet(),
        status = runCatching { DumpStatus.valueOf(row.getString(10)) }.getOrDefault(DumpStatus.UNKNOWN),
        externalId = row.getStringOrNull(11),
    )

    /**
     * Maximum host parameters in one statement.
     *
     * SQLite's `SQLITE_MAX_VARIABLE_NUMBER` defaults to 999 on the build
     * Android ships. A size lookup against a large DAT can easily match more
     * records than that, so every `IN (...)` clause is chunked. Without this
     * the code passes a four-file test and throws on a real library.
     */
    const val MAX_PARAMETERS: Int = 900

    /** Loads digests for many records, in as few queries as the limit allows. */
    fun loadHashes(database: SqlDatabase, recordIds: List<String>): Map<String, HashDigests> {
        if (recordIds.isEmpty()) return emptyMap()
        val rows = chunked(recordIds) { placeholders, chunk ->
            database.query(
                "SELECT record_id, algorithm, digest FROM dump_hash WHERE record_id IN ($placeholders)",
                chunk,
            ) { row -> Triple(row.getString(0), row.getString(1), row.getString(2)) }
        }
        return rows.groupBy { it.first }
            .mapValues { (_, grouped) ->
                grouped.fold(HashDigests.EMPTY) { digests, (_, algorithm, digest) ->
                    val parsed = runCatching { HashAlgorithm.valueOf(algorithm) }.getOrNull()
                        ?.let { HashValue.parse(it, digest) }
                    if (parsed == null) digests else digests.with(parsed)
                }
            }
    }

    /**
     * Runs [query] once per chunk of [values] and concatenates the results.
     *
     * @param query receives the placeholder list and the arguments for one chunk.
     */
    fun <T> chunked(
        values: List<String>,
        query: (placeholders: String, chunk: List<String>) -> List<T>,
    ): List<T> =
        values.chunked(MAX_PARAMETERS).flatMap { chunk ->
            query(chunk.joinToString(",") { "?" }, chunk)
        }

    fun encodeList(values: List<String>): String = values.joinToString(SEPARATOR)

    fun String.splitList(): List<String> =
        if (isEmpty()) emptyList() else split(SEPARATOR).filter { it.isNotEmpty() }
}
