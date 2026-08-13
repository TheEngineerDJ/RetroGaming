package com.retrovault.data

import com.retrovault.application.Clock
import com.retrovault.application.EntityGraph
import com.retrovault.application.Outcome
import com.retrovault.application.RetroVaultFailure
import com.retrovault.data.RecordMapper.splitList
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.entity.EntityKind
import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.entity.EntityRef
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.PromotedIdentity
import com.retrovault.domain.entity.Release
import com.retrovault.domain.entity.RelationshipType
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.PlatformId
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.ReleaseFlag
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.identity.WorkId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The canonical entity graph over SQLite.
 *
 * Writes are idempotent because entity identifiers are derived from the
 * identity they describe: promoting the same release from two datasets, or from
 * the same dataset twice, updates one row rather than creating a second.
 *
 * The one thing this must never do is overwrite a decision a person made.
 * Constitution section 43 reserves canonical merges for human or
 * high-confidence evidence, so a derived write leaves a `CONFIRMED` row alone -
 * enforced in SQL rather than in the caller, because a caller can forget.
 */
class SqlEntityGraph(
    private val database: SqlDatabase,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EntityGraph {

    override suspend fun save(identity: PromotedIdentity): Outcome<Unit> = write {
        database.transaction {
            val now = clock.nowEpochMillis()
            savePlatform(identity, now)
            saveWork(identity, now)
            saveRelease(identity, now)
            saveArtifact(identity, now)
            identity.relationships.forEach(::saveRelationship)
        }
    }

    override suspend fun findRelease(id: ReleaseId): Outcome<Release?> = read {
        Outcome.success(
            database.queryOne(
                "SELECT id, work_id, platform_id, regions, languages, revision, version, " +
                    "disc_number, flags, provenance, aliases FROM release_entity WHERE id = ?",
                listOf(id.value),
            ) { row -> row.toRelease() },
        )
    }

    /**
     * The catalogue records that describe a release.
     *
     * Read from the release key stored on each record rather than from the
     * entity graph, and that is the point: a correction names a release the
     * user believes is right, which is very often *not* the one their scan
     * promoted. Requiring the entity to exist first would mean a user could
     * only correct a file to something they had already scanned a copy of.
     *
     * There is no link table. A `dump_record` is external evidence a dataset
     * may withdraw on re-import, so the association stays derived and a
     * re-import cannot leave a dangling edge behind.
     */
    override suspend fun recordsForRelease(id: ReleaseId): List<DumpRecord> = withContext(dispatcher) {
        val rows = database.query(
            "SELECT r.id, r.set_name, r.rom_name, r.size, r.platform, r.canonical_title, " +
                "r.normalized_title, r.revision, r.version, r.disc_number, r.status, r.external_id, " +
                "r.regions, r.languages, r.flags, " +
                "s.id, s.provider, s.set_name, s.version, s.platform, s.imported_at, s.source_digest, " +
                "r.media_type, s.kind " +
                "FROM dump_record r JOIN dat_source s ON s.id = r.source_id " +
                "WHERE s.state = 'ready' AND r.matchable = 1 AND r.release_id = ? ORDER BY r.id",
            listOf(id.value),
        ) { row -> RecordMapper.map(row) }

        if (rows.isEmpty()) return@withContext emptyList()
        val hashes = RecordMapper.loadHashes(database, rows.map { it.id.value })
        rows.map { record -> record.copy(hashes = hashes[record.id.value] ?: record.hashes) }
    }

    override suspend fun relationshipsFrom(ref: EntityRef): Outcome<List<EntityRelationship>> = read {
        Outcome.success(
            database.query(
                "SELECT from_kind, from_id, type, to_kind, to_id, provenance, note " +
                    "FROM entity_relationship WHERE from_kind = ? AND from_id = ? ORDER BY type, to_id",
                listOf(ref.kind.name, ref.value),
            ) { row -> row.toRelationship() }.filterNotNull(),
        )
    }

    override suspend fun relate(relationship: EntityRelationship): Outcome<Unit> = write {
        saveRelationship(relationship)
    }

    // --------------------------------------------------------------- writes

    /**
     * Writes an entity without demoting one the user confirmed.
     *
     * The `WHERE` clause is the enforcement point: a derived write updates only
     * rows that are themselves derived, so an automatic pass can refresh what it
     * proposed and can never quietly undo what a person settled.
     */
    private fun savePlatform(identity: PromotedIdentity, now: Long) {
        val platform = identity.platform
        val existing = existingRow("platform_entity", platform.id.value, "name")
        if (existing == null) {
            database.execute(
                "INSERT INTO platform_entity (id, name, provenance, aliases, first_seen_at, " +
                    "last_updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                listOf(
                    platform.id.value,
                    platform.name.value,
                    platform.provenance.name,
                    encode(platform.aliases),
                    now,
                    now,
                ),
            )
            return
        }
        if (existing.confirmed) return
        database.execute(
            "UPDATE platform_entity SET name = ?, aliases = ?, last_updated_at = ? WHERE id = ?",
            listOf(
                platform.name.value,
                encode(retainedNames(existing, platform.name.value, platform.aliases)),
                now,
                platform.id.value,
            ),
        )
    }

    private fun saveWork(identity: PromotedIdentity, now: Long) {
        val work = identity.work
        val existing = existingRow("work_entity", work.id.value, "canonical_title")
        if (existing == null) {
            database.execute(
                "INSERT INTO work_entity (id, canonical_title, normalized_title, provenance, aliases, " +
                    "first_seen_at, last_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    work.id.value,
                    work.canonicalTitle,
                    work.normalizedTitle.key,
                    work.provenance.name,
                    encode(work.aliases),
                    now,
                    now,
                ),
            )
            return
        }
        if (existing.confirmed) return
        database.execute(
            "UPDATE work_entity SET canonical_title = ?, aliases = ?, last_updated_at = ? WHERE id = ?",
            listOf(
                work.canonicalTitle,
                encode(retainedNames(existing, work.canonicalTitle, work.aliases)),
                now,
                work.id.value,
            ),
        )
    }

    private fun saveRelease(identity: PromotedIdentity, now: Long) {
        val release = identity.release
        val existing = existingRow("release_entity", release.id.value, "id")
        if (existing != null) {
            if (existing.confirmed) return
            database.execute(
                "UPDATE release_entity SET aliases = ?, last_updated_at = ? WHERE id = ?",
                listOf(
                    encode(existing.aliases + release.aliases),
                    now,
                    release.id.value,
                ),
            )
            return
        }
        database.execute(
            "INSERT INTO release_entity (id, work_id, platform_id, regions, languages, revision, " +
                "version, disc_number, flags, provenance, aliases, first_seen_at, last_updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                release.id.value,
                release.workId.value,
                release.platformId.value,
                encode(release.regions.map { it.code }),
                encode(release.languages.map { it.code }),
                release.revision,
                release.version,
                release.discNumber?.toLong(),
                encode(release.flags.map { it.name }.sorted()),
                release.provenance.name,
                encode(release.aliases),
                now,
                now,
            ),
        )
    }

    private fun saveArtifact(identity: PromotedIdentity, now: Long) {
        val artifact = identity.artifact
        val existing = existingRow("artifact_entity", artifact.id.value, "id")
        if (existing == null) {
            database.execute(
                "INSERT INTO artifact_entity (id, release_id, media_type, size, provenance, aliases, " +
                    "first_seen_at, last_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    artifact.id.value,
                    artifact.releaseId.value,
                    artifact.mediaType.name,
                    artifact.size,
                    artifact.provenance.name,
                    encode(artifact.aliases),
                    now,
                    now,
                ),
            )
        } else if (!existing.confirmed) {
            database.execute(
                "UPDATE artifact_entity SET media_type = ?, size = ?, aliases = ?, last_updated_at = ? " +
                    "WHERE id = ?",
                listOf(
                    artifact.mediaType.name,
                    artifact.size,
                    // Every rom name this artifact has been catalogued under is
                    // kept. They are the historical names section 43 lists, and
                    // dropping one would make an earlier identity unrecoverable
                    // (DOMAIN_MODEL.md section 37 invariant 12).
                    encode(existing.aliases + artifact.aliases),
                    now,
                    artifact.id.value,
                ),
            )
        }
        artifact.hashes.asList().forEach { hash ->
            database.execute(
                "INSERT OR REPLACE INTO artifact_hash (artifact_id, algorithm, digest) VALUES (?, ?, ?)",
                listOf(artifact.id.value, hash.algorithm.name, hash.hex),
            )
        }
    }

    /**
     * An edge is its endpoints and its type, so asserting it twice is one fact.
     * A confirmed edge is never demoted to derived, for the same reason an
     * entity is not.
     */
    private fun saveRelationship(relationship: EntityRelationship) {
        database.execute(
            "INSERT INTO entity_relationship (from_kind, from_id, type, to_kind, to_id, provenance, note) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(from_kind, from_id, type, to_kind, to_id) DO UPDATE SET " +
                "provenance = excluded.provenance, note = excluded.note " +
                "WHERE entity_relationship.provenance = 'DERIVED'",
            listOf(
                relationship.from.kind.name,
                relationship.from.value,
                relationship.type.name,
                relationship.to.kind.name,
                relationship.to.value,
                relationship.provenance.name,
                relationship.note,
            ),
        )
    }

    /** One stored row's identity-bearing bits, for a history-preserving update. */
    private data class ExistingEntity(
        val displayName: String?,
        val aliases: Set<String>,
        val confirmed: Boolean,
    )

    private fun existingRow(table: String, id: String, nameColumn: String): ExistingEntity? =
        database.queryOne(
            "SELECT $nameColumn, aliases, provenance FROM $table WHERE id = ?",
            listOf(id),
        ) { row ->
            ExistingEntity(
                displayName = row.getStringOrNull(0),
                aliases = row.getString(1).splitList().toSet(),
                confirmed = row.getString(2) == "CONFIRMED",
            )
        }

    /**
     * The names an entity should carry after an update.
     *
     * A derived entity's display name can change when a dataset is re-imported
     * with different wording. Overwriting it would make the earlier name
     * unrecoverable, which DOMAIN_MODEL.md section 37 invariant 12 forbids - so
     * the outgoing name is kept as an alias instead. Constitution section 43
     * already lists historical names among what aliases are for, which is why
     * this needs no new storage and no temporal system.
     */
    private fun retainedNames(
        existing: ExistingEntity,
        newName: String,
        newAliases: Set<String>,
    ): Set<String> = buildSet {
        addAll(existing.aliases)
        addAll(newAliases)
        existing.displayName?.takeIf { it != newName }?.let(::add)
        remove(newName)
    }

    // ------------------------------------------------------------- plumbing

    private fun SqlRow.toRelease(): Release = Release(
        id = ReleaseId(getString(0)),
        workId = WorkId(getString(1)),
        platformId = PlatformId(getString(2)),
        regions = getString(3).splitList().map(::RegionCode),
        languages = getString(4).splitList().map(::LanguageCode),
        revision = getStringOrNull(5),
        version = getStringOrNull(6),
        discNumber = getLongOrNull(7)?.toInt(),
        flags = getString(8).splitList()
            .mapNotNull { name -> runCatching { ReleaseFlag.valueOf(name) }.getOrNull() }
            .toSet(),
        provenance = provenanceOf(getString(9)),
        aliases = getString(10).splitList().toSet(),
    )

    /**
     * @return `null` for an edge whose type or endpoint kinds no longer parse.
     *
     * A stored edge naming a relationship this build does not know about is not
     * an error to crash on: the vocabulary is versioned and expected to grow, so
     * an unreadable row is skipped rather than allowed to fail a whole query.
     */
    private fun SqlRow.toRelationship(): EntityRelationship? {
        val type = runCatching { RelationshipType.valueOf(getString(2)) }.getOrNull() ?: return null
        val fromKind = runCatching { EntityKind.valueOf(getString(0)) }.getOrNull() ?: return null
        val toKind = runCatching { EntityKind.valueOf(getString(3)) }.getOrNull() ?: return null
        return runCatching {
            EntityRelationship(
                from = EntityRef(fromKind, getString(1)),
                type = type,
                to = EntityRef(toKind, getString(4)),
                provenance = provenanceOf(getString(5)),
                note = getStringOrNull(6),
            )
        }.getOrNull()
    }

    private fun provenanceOf(raw: String): EntityProvenance =
        runCatching { EntityProvenance.valueOf(raw) }.getOrDefault(EntityProvenance.DERIVED)

    private fun encode(values: Collection<String>): String = RecordMapper.encodeList(values.toList())

    private suspend fun <T> read(body: () -> T): T = withContext(dispatcher) { body() }

    private suspend fun write(body: () -> Unit): Outcome<Unit> = withContext(dispatcher) {
        try {
            Outcome.success(body())
        } catch (failure: SqlFailure) {
            Outcome.failure(RetroVaultFailure.PersistenceFailure(failure.message ?: "SQL failure"))
        }
    }
}
