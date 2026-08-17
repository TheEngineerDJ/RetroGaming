package com.retrovault.application

import com.retrovault.domain.entity.EntityProvenance
import com.retrovault.domain.identity.WorkId

/** One work as a browse list shows it. */
data class WorkSummary(
    val id: WorkId,
    val title: String,
    val matchKind: MatchKind,
    /** The name that matched, so an alias hit explains itself. */
    val matchedOn: String,
    val provenance: EntityProvenance,
    val releaseCount: Int,
)

/** One release under a work, with the digital images RetroVault holds of it. */
data class ReleaseSummary(
    val id: String,
    val label: String,
    val platform: String,
    val provenance: EntityProvenance,
    val artifactCount: Int,
    /** Cryptographic digests of each artifact, as text a user could search elsewhere. */
    val artifactDigests: List<String>,
)

/** A work opened up: its releases, and where the knowledge came from. */
data class WorkDetail(
    val title: String,
    val aliases: Set<String>,
    val provenance: EntityProvenance,
    val releases: List<ReleaseSummary>,
    /** Datasets whose records project into this work. */
    val sources: List<String>,
    val independentSourceCount: Int,
    val firstSeenAtEpochMillis: Long?,
    val lastUpdatedAtEpochMillis: Long?,
)

/**
 * Browsing what RetroVault knows, rather than what it just scanned.
 *
 * Constitution section 137 lists the MVP's obligations, and three of them -
 * representing entities, connecting them and searching them - had no way to a
 * user at all: the graph was written by every scan and read by nobody.
 *
 * Assembling the view here rather than in a screen keeps the counting and
 * joining testable on a JVM, and keeps a view model from deciding what a
 * release is called.
 */
class BrowseLibraryUseCase(private val queries: EntityQueries) {

    /**
     * Works matching a search, ranked by how they matched.
     *
     * The release count comes from the graph rather than being estimated,
     * because "3 releases" is a claim about the library and a wrong one would
     * be a small lie told on every row.
     */
    suspend fun works(query: String? = null, limit: Int = EntityPage.DEFAULT_LIMIT): Outcome<List<WorkSummary>> =
        runCatching {
            queries.works(query = query, limit = limit).items.map { match ->
                WorkSummary(
                    id = match.entity.id,
                    title = match.entity.canonicalTitle,
                    matchKind = match.matchKind,
                    matchedOn = match.matchedOn,
                    provenance = match.entity.provenance,
                    releaseCount = queries.releasesOfWork(match.entity.id).size,
                )
            }
        }.fold(
            onSuccess = { Outcome.success(it) },
            onFailure = { Outcome.failure(RetroVaultFailure.PersistenceFailure(it.message ?: "browse failed")) },
        )

    /** One work opened up, with its releases, artifacts and provenance. */
    suspend fun work(id: WorkId): Outcome<WorkDetail> = runCatching {
        val work = queries.findWork(id)
            ?: return Outcome.failure(RetroVaultFailure.PersistenceFailure("That title is no longer in the library."))
        val report = queries.provenanceOf(work.entityRef)
        val releases = queries.releasesOfWork(id).items.map { release ->
            val artifacts = queries.artifactsOfRelease(release.id)
            ReleaseSummary(
                id = release.id.value,
                label = describe(release.regions.map { it.code }, release.revision, release.discNumber),
                platform = release.platformId.value.substringAfter("platform:"),
                provenance = release.provenance,
                artifactCount = artifacts.size,
                // Shown because it is the one thing a user can carry to another
                // tool and check for themselves. Section 264: evidence should
                // be accessible, not merely stored.
                artifactDigests = artifacts.mapNotNull { artifact ->
                    artifact.hashes.asList()
                        .firstOrNull { it.algorithm.isCryptographicIdentityEvidence }
                        ?.let { "${it.algorithm.canonicalName} ${it.hex}" }
                },
            )
        }
        Outcome.success(
            WorkDetail(
                title = work.canonicalTitle,
                // Includes names this work used to carry, which is what makes
                // historical identity recoverable (section 43, invariant 12).
                aliases = work.aliases,
                provenance = work.provenance,
                releases = releases,
                sources = report?.contributingSources.orEmpty().map { "${it.provider}: ${it.setName}" },
                independentSourceCount = report?.independentSourceCount ?: 0,
                firstSeenAtEpochMillis = report?.timestamps?.firstSeenAtEpochMillis,
                lastUpdatedAtEpochMillis = report?.timestamps?.lastUpdatedAtEpochMillis,
            ),
        )
    }.getOrElse {
        Outcome.failure(RetroVaultFailure.PersistenceFailure(it.message ?: "browse failed"))
    }

    /**
     * Words for what distinguishes one release of a work from another.
     *
     * Deliberately plain, and deliberately says "no region recorded" rather
     * than inventing one: a release whose region nothing states is a different
     * fact from a worldwide release.
     */
    private fun describe(regions: List<String>, revision: String?, disc: Int?): String = buildList {
        if (regions.isEmpty()) add("no region recorded") else add(regions.joinToString(", "))
        revision?.let { add("Rev $it") }
        disc?.let { add("Disc $it") }
    }.joinToString(" · ")
}
