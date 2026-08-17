package com.retrovault.application

import com.retrovault.domain.entity.Artifact
import com.retrovault.domain.entity.EntityProvenanceReport
import com.retrovault.domain.entity.EntityRef
import com.retrovault.domain.entity.EntityRelationship
import com.retrovault.domain.entity.Platform
import com.retrovault.domain.entity.Release
import com.retrovault.domain.entity.Work
import com.retrovault.domain.identity.ArtifactId
import com.retrovault.domain.identity.PlatformId
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.identity.WorkId

/**
 * How much of a result set a caller is willing to hold.
 *
 * Every list query is bounded. Constitution section 249 requires memory to stay
 * bounded for large collections, and an unbounded browse over a catalogue of
 * hundreds of thousands of releases is exactly the query that will be written
 * once and then run on a phone.
 *
 * [hasMore] is what stops a truncated answer being mistaken for a complete one.
 * A caller that shows ten of four hundred results and says nothing has misled
 * the user about their own library.
 */
data class EntityPage<T>(
    val items: List<T>,
    val hasMore: Boolean,
) {
    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        const val DEFAULT_LIMIT: Int = 50

        /** Upper bound a caller cannot raise. Section 249: bounded means bounded. */
        const val MAX_LIMIT: Int = 500

        fun <T> of(items: List<T>, limit: Int): EntityPage<T> =
            EntityPage(items.take(limit), hasMore = items.size > limit)

        fun <T> empty(): EntityPage<T> = EntityPage(emptyList(), hasMore = false)
    }
}

/**
 * Why a result matched.
 *
 * Constitution section 213 requires search to distinguish exact, alias and
 * fuzzy matches rather than returning one undifferentiated list. Without it a
 * result found through a name the entity used to carry is indistinguishable
 * from one whose title the user typed exactly - and a user cannot tell whether
 * the thing they were looking for is the thing they found.
 *
 * Ordered strongest first, which is also the ranking order.
 */
enum class MatchKind {
    /** The query is exactly the entity's current name. */
    EXACT,

    /** The query normalizes to the entity's title, e.g. "Zelda, The" for "The Zelda". */
    NORMALIZED,

    /** The query matched a name the entity is also, or used to be, known by. */
    ALIAS,

    /** The query appears somewhere within the name. */
    PARTIAL,
}

/** One result, with the reason it is a result (Constitution section 213). */
data class EntityMatch<T>(
    val entity: T,
    val matchKind: MatchKind,
    /** The text that matched, so a user can see *why* an alias hit was returned. */
    val matchedOn: String,
)

/**
 * Reading the canonical entity graph.
 *
 * Separate from [EntityGraph], which writes it. The split is not ceremony: a
 * caller that only browses should not be able to promote or relate, and the
 * read surface is where result bounds and provenance exposure are enforced.
 *
 * Every query here obeys two rules. Results are bounded, and nothing is hidden
 * for tidiness - an overridden candidate, a superseded correction and a
 * derived-versus-confirmed distinction all survive into what the caller sees
 * (Constitution section 44 and section 196).
 */
interface EntityQueries {

    /**
     * Platforms, optionally narrowed by a search term.
     *
     * The term matches the platform's name or any of its aliases, because
     * section 43 makes aliases search aids - a user typing "SNES" for
     * "Nintendo - Super Nintendo Entertainment System" is the ordinary case,
     * not an edge one.
     */
    suspend fun platforms(
        query: String? = null,
        limit: Int = EntityPage.DEFAULT_LIMIT,
    ): EntityPage<EntityMatch<Platform>>

    suspend fun findPlatform(id: PlatformId): Platform?

    /**
     * Works, optionally narrowed by a search term and by platform.
     *
     * Matches canonical title, normalized title and aliases. Normalized title
     * is included so a search for "Legend of Zelda, The" finds the work stored
     * as "The Legend of Zelda" - the same normalization the resolver uses,
     * reused rather than reimplemented.
     *
     * Ranked by how the match was made, not alphabetically. Constitution
     * section 212 requires identity relevance to outrank raw text: searching
     * "Zelda" must not put *A Link to the Past* above *Zelda* because "A" sorts
     * first. Ties are broken by title so the order is still deterministic.
     */
    suspend fun works(
        query: String? = null,
        platformId: PlatformId? = null,
        limit: Int = EntityPage.DEFAULT_LIMIT,
    ): EntityPage<EntityMatch<Work>>

    suspend fun findWork(id: WorkId): Work?

    /** Every published form of one work (Constitution section 31). */
    suspend fun releasesOfWork(id: WorkId, limit: Int = EntityPage.DEFAULT_LIMIT): EntityPage<Release>

    /** Releases on one platform, for browsing a console rather than a title. */
    suspend fun releasesOnPlatform(
        id: PlatformId,
        limit: Int = EntityPage.DEFAULT_LIMIT,
    ): EntityPage<Release>

    suspend fun findRelease(id: ReleaseId): Release?

    /** Every digital image of one release (Constitution section 38). */
    suspend fun artifactsOfRelease(id: ReleaseId): List<Artifact>

    suspend fun findArtifact(id: ArtifactId): Artifact?

    /**
     * Every edge touching an entity, in both directions.
     *
     * Both directions on purpose. A release's incoming `IMAGE_OF` edges are how
     * a caller reaches its artifacts, and a graph that could only be walked
     * downwards would make half of it unreachable.
     */
    suspend fun relationships(ref: EntityRef): List<EntityRelationship>

    /**
     * Why RetroVault holds this entity, and what argues with it.
     *
     * The one query that must never tidy anything: it carries provenance,
     * timestamps, contributing datasets, edges and - for an artifact - the full
     * correction history including superseded and withdrawn entries.
     */
    suspend fun provenanceOf(ref: EntityRef): EntityProvenanceReport?
}
