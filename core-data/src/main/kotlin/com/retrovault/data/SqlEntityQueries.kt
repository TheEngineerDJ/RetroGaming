package com.retrovault.data

import com.retrovault.application.CorrectionStore
import com.retrovault.application.EntityPage
import com.retrovault.application.EntityQueries
import com.retrovault.application.Outcome
import com.retrovault.data.RecordMapper.splitList
import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.entity.Artifact
import com.retrovault.domain.entity.EntityKind
import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.entity.EntityProvenanceReport
import com.retrovault.domain.entity.EntityRef
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.EntityTimestamps
import com.retrovault.domain.entity.Platform
import com.retrovault.domain.entity.Release
import com.retrovault.domain.entity.RelationshipType
import com.retrovault.domain.entity.Work
import com.retrovault.domain.identity.ArtifactId
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DatasetKind
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.PlatformId
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.ReleaseFlag
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.identity.WorkId
import com.retrovault.domain.naming.NormalizedTitle
import com.retrovault.domain.naming.TitleNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading the canonical entity graph over SQLite.
 *
 * Every list query fetches one row more than the caller asked for, so
 * [EntityPage.hasMore] is measured rather than guessed. A truncated answer that
 * cannot say it was truncated misleads the user about their own library, and a
 * `COUNT(*)` to find out would double the cost of every browse.
 *
 * Nothing here filters for tidiness. Provenance travels with every entity and
 * correction history is returned complete, including superseded and withdrawn
 * entries (Constitution section 44 and section 70).
 */
class SqlEntityQueries(
    private val database: SqlDatabase,
    private val corrections: CorrectionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EntityQueries {

    // ------------------------------------------------------------- platforms

    override suspend fun platforms(query: String?, limit: Int): EntityPage<Platform> = read {
        val bounded = bound(limit)
        val term = searchTerm(query)
        val rows = if (term == null) {
            database.query(
                "$PLATFORM_COLUMNS FROM platform_entity ORDER BY name LIMIT ${bounded + 1}",
            ) { it.toPlatform() }
        } else {
            database.query(
                "$PLATFORM_COLUMNS FROM platform_entity " +
                    "WHERE LOWER(name) LIKE ? ESCAPE '\\' OR LOWER(aliases) LIKE ? ESCAPE '\\' " +
                    "ORDER BY name LIMIT ${bounded + 1}",
                listOf(term, term),
            ) { it.toPlatform() }
        }
        EntityPage.of(rows, bounded)
    }

    override suspend fun findPlatform(id: PlatformId): Platform? = read {
        database.queryOne("$PLATFORM_COLUMNS FROM platform_entity WHERE id = ?", listOf(id.value)) {
            it.toPlatform()
        }
    }

    // ----------------------------------------------------------------- works

    /**
     * The search term is matched against the normalized title as well as the
     * written one, using the same normalizer the resolver uses. That is what
     * lets "Legend of Zelda, The" find a work stored as "The Legend of Zelda"
     * without a second set of rules to keep in step.
     */
    override suspend fun works(query: String?, platformId: PlatformId?, limit: Int): EntityPage<Work> =
        read {
            val bounded = bound(limit)
            val conditions = mutableListOf<String>()
            val arguments = mutableListOf<Any?>()

            searchTerm(query)?.let { term ->
                conditions += "(LOWER(w.canonical_title) LIKE ? ESCAPE '\\' " +
                    "OR LOWER(w.aliases) LIKE ? ESCAPE '\\' " +
                    "OR w.normalized_title LIKE ? ESCAPE '\\')"
                arguments += term
                arguments += term
                arguments += searchTerm(TitleNormalizer.normalize(query.orEmpty()).key) ?: term
            }
            platformId?.let {
                conditions += "EXISTS (SELECT 1 FROM release_entity r " +
                    "WHERE r.work_id = w.id AND r.platform_id = ?)"
                arguments += it.value
            }

            val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
            EntityPage.of(
                database.query(
                    "$WORK_COLUMNS FROM work_entity w $where ORDER BY w.canonical_title, w.id " +
                        "LIMIT ${bounded + 1}",
                    arguments,
                ) { it.toWork() },
                bounded,
            )
        }

    override suspend fun findWork(id: WorkId): Work? = read {
        database.queryOne("$WORK_COLUMNS FROM work_entity w WHERE w.id = ?", listOf(id.value)) {
            it.toWork()
        }
    }

    // -------------------------------------------------------------- releases

    override suspend fun releasesOfWork(id: WorkId, limit: Int): EntityPage<Release> = read {
        val bounded = bound(limit)
        EntityPage.of(
            database.query(
                "$RELEASE_COLUMNS FROM release_entity WHERE work_id = ? " +
                    "ORDER BY regions, revision, disc_number, id LIMIT ${bounded + 1}",
                listOf(id.value),
            ) { it.toRelease() },
            bounded,
        )
    }

    override suspend fun releasesOnPlatform(id: PlatformId, limit: Int): EntityPage<Release> = read {
        val bounded = bound(limit)
        EntityPage.of(
            database.query(
                "$RELEASE_COLUMNS FROM release_entity WHERE platform_id = ? ORDER BY id " +
                    "LIMIT ${bounded + 1}",
                listOf(id.value),
            ) { it.toRelease() },
            bounded,
        )
    }

    override suspend fun findRelease(id: ReleaseId): Release? = read {
        database.queryOne("$RELEASE_COLUMNS FROM release_entity WHERE id = ?", listOf(id.value)) {
            it.toRelease()
        }
    }

    // ------------------------------------------------------------- artifacts

    /**
     * Unbounded on purpose. A release has a handful of digital images - a
     * cartridge dump and a headered copy, a disc as `.cue` and as `.chd` - not
     * a page of them, and paginating a set that small would cost a caller a
     * `hasMore` check it can never need.
     */
    override suspend fun artifactsOfRelease(id: ReleaseId): List<Artifact> = read {
        withHashes(
            database.query(
                "$ARTIFACT_COLUMNS FROM artifact_entity WHERE release_id = ? ORDER BY id",
                listOf(id.value),
            ) { it.toArtifact() },
        )
    }

    override suspend fun findArtifact(id: ArtifactId): Artifact? = read {
        val artifact = database.queryOne(
            "$ARTIFACT_COLUMNS FROM artifact_entity WHERE id = ?",
            listOf(id.value),
        ) { it.toArtifact() } ?: return@read null
        withHashes(listOf(artifact)).single()
    }

    // --------------------------------------------------------- relationships

    override suspend fun relationships(ref: EntityRef): List<EntityRelationship> = read {
        database.query(
            "$RELATIONSHIP_COLUMNS FROM entity_relationship " +
                "WHERE (from_kind = ? AND from_id = ?) OR (to_kind = ? AND to_id = ?) " +
                "ORDER BY type, from_id, to_id",
            listOf(ref.kind.name, ref.value, ref.kind.name, ref.value),
        ) { it.toRelationship() }.filterNotNull()
    }

    // ------------------------------------------------------------ provenance

    override suspend fun provenanceOf(ref: EntityRef): EntityProvenanceReport? {
        val base = readBase(ref) ?: return null
        return EntityProvenanceReport(
            ref = ref,
            provenance = base.provenance,
            timestamps = base.timestamps,
            aliases = base.aliases,
            contributingSources = contributingSources(ref),
            relationships = relationships(ref),
            corrections = correctionsFor(ref),
        )
    }

    private data class BaseRow(
        val provenance: EntityProvenance,
        val timestamps: EntityTimestamps,
        val aliases: Set<String>,
    )

    private suspend fun readBase(ref: EntityRef): BaseRow? = read {
        val table = when (ref.kind) {
            EntityKind.PLATFORM -> "platform_entity"
            EntityKind.WORK -> "work_entity"
            EntityKind.RELEASE -> "release_entity"
            EntityKind.ARTIFACT -> "artifact_entity"
        }
        database.queryOne(
            "SELECT provenance, aliases, first_seen_at, last_updated_at FROM $table WHERE id = ?",
            listOf(ref.value),
        ) { row ->
            BaseRow(
                provenance = provenanceOf(row.getString(0)),
                timestamps = EntityTimestamps.of(row.getLong(2), row.getLong(3)),
                aliases = row.getString(1).splitList().toSet(),
            )
        }
    }

    /**
     * Datasets whose records project into this entity.
     *
     * Constitution section 196: a dataset must never become an invisible
     * authority. Reached through `dump_record.release_id`, so it answers for a
     * release and for anything that resolves to one.
     */
    private suspend fun contributingSources(ref: EntityRef) = read {
        val releaseFilter = when (ref.kind) {
            EntityKind.RELEASE -> "r.release_id = ?" to ref.value
            EntityKind.ARTIFACT ->
                "r.release_id = (SELECT release_id FROM artifact_entity WHERE id = ?)" to ref.value

            EntityKind.WORK ->
                "r.release_id IN (SELECT id FROM release_entity WHERE work_id = ?)" to ref.value

            EntityKind.PLATFORM ->
                "r.release_id IN (SELECT id FROM release_entity WHERE platform_id = ?)" to ref.value
        }
        database.query(
            "SELECT DISTINCT s.id, s.provider, s.set_name, s.version, s.platform, s.imported_at, " +
                "s.source_digest, s.kind FROM dat_source s JOIN dump_record r ON r.source_id = s.id " +
                "WHERE s.state = 'ready' AND ${releaseFilter.first} ORDER BY s.id",
            listOf(releaseFilter.second),
        ) { row -> row.toSourceRef() }
    }

    /**
     * Every correction ever recorded against this artifact's content.
     *
     * Only an artifact has content, so only an artifact can carry corrections.
     * The list is complete - superseded and withdrawn included - because a
     * history that shows only the current answer is not a history
     * (Constitution section 70).
     */
    private suspend fun correctionsFor(ref: EntityRef) =
        if (ref.kind != EntityKind.ARTIFACT) {
            emptyList()
        } else {
            val scopes = read {
                database.query(
                    "SELECT algorithm, digest FROM artifact_hash WHERE artifact_id = ?",
                    listOf(ref.value),
                ) { row -> row.getString(0) to row.getString(1) }
            }
            scopes.mapNotNull { (algorithm, digest) ->
                runCatching { HashAlgorithm.valueOf(algorithm) }.getOrNull()
                    ?.takeIf { it.isCryptographicIdentityEvidence }
                    ?.let { CorrectionScope(it, digest) }
            }.flatMap { scope ->
                (corrections.history(scope) as? Outcome.Success)?.value.orEmpty()
            }.distinctBy { it.id.value }
                .sortedWith(
                    compareByDescending<IdentityCorrection> { it.recordedAtEpochMillis }
                        .thenByDescending { it.id.value },
                )
        }

    // -------------------------------------------------------------- plumbing

    private fun withHashes(artifacts: List<Artifact>): List<Artifact> {
        if (artifacts.isEmpty()) return artifacts
        val rows = RecordMapper.chunked(artifacts.map { it.id.value }) { placeholders, chunk ->
            database.query(
                "SELECT artifact_id, algorithm, digest FROM artifact_hash " +
                    "WHERE artifact_id IN ($placeholders)",
                chunk,
            ) { row -> Triple(row.getString(0), row.getString(1), row.getString(2)) }
        }
        val byArtifact = rows.groupBy { it.first }
        return artifacts.map { artifact ->
            val digests = byArtifact[artifact.id.value].orEmpty().fold(artifact.hashes) { acc, row ->
                val parsed = runCatching { HashAlgorithm.valueOf(row.second) }.getOrNull()
                    ?.let { HashValue.parse(it, row.third) }
                if (parsed == null) acc else acc.with(parsed)
            }
            artifact.copy(hashes = digests)
        }
    }

    /**
     * Turns a user's search text into a `LIKE` pattern.
     *
     * `%` and `_` are escaped: a user typing `100%` is searching for a title,
     * not writing a wildcard, and letting it through would silently widen their
     * search to everything.
     */
    private fun searchTerm(query: String?): String? {
        val trimmed = query?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return null
        val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private fun bound(limit: Int): Int = limit.coerceIn(1, EntityPage.MAX_LIMIT)

    private fun provenanceOf(raw: String): EntityProvenance =
        runCatching { EntityProvenance.valueOf(raw) }.getOrDefault(EntityProvenance.DERIVED)

    private fun SqlRow.toPlatform() = Platform(
        id = PlatformId(getString(0)),
        name = PlatformName(getString(1)),
        provenance = provenanceOf(getString(2)),
        aliases = getString(3).splitList().toSet(),
    )

    private fun SqlRow.toWork() = Work(
        id = WorkId(getString(0)),
        canonicalTitle = getString(1),
        normalizedTitle = NormalizedTitle(getString(2)),
        provenance = provenanceOf(getString(3)),
        aliases = getString(4).splitList().toSet(),
    )

    private fun SqlRow.toRelease() = Release(
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

    private fun SqlRow.toArtifact() = Artifact(
        id = ArtifactId(getString(0)),
        releaseId = ReleaseId(getString(1)),
        mediaType = runCatching { MediaType.valueOf(getString(2)) }.getOrDefault(MediaType.UNKNOWN),
        size = getLongOrNull(3),
        hashes = HashDigests.EMPTY,
        provenance = provenanceOf(getString(4)),
        aliases = getString(5).splitList().toSet(),
    )

    private fun SqlRow.toSourceRef() = DatSourceRef(
        id = DatSourceId(getString(0)),
        provider = getString(1),
        setName = getString(2),
        version = getStringOrNull(3),
        platform = PlatformName(getString(4)),
        importedAtEpochMillis = getLong(5),
        sourceDigest = getStringOrNull(6),
        kind = runCatching { DatasetKind.valueOf(getString(7)) }
            .getOrDefault(DatasetKind.UNKNOWN),
    )

    /** @return `null` for an edge whose type or endpoint kinds this build cannot read. */
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

    private suspend fun <T> read(body: () -> T): T = withContext(dispatcher) { body() }

    private companion object {
        const val PLATFORM_COLUMNS = "SELECT id, name, provenance, aliases "
        const val WORK_COLUMNS =
            "SELECT w.id, w.canonical_title, w.normalized_title, w.provenance, w.aliases "
        const val RELEASE_COLUMNS =
            "SELECT id, work_id, platform_id, regions, languages, revision, version, disc_number, " +
                "flags, provenance, aliases "
        const val ARTIFACT_COLUMNS = "SELECT id, release_id, media_type, size, provenance, aliases "
        const val RELATIONSHIP_COLUMNS =
            "SELECT from_kind, from_id, type, to_kind, to_id, provenance, note "
    }
}
