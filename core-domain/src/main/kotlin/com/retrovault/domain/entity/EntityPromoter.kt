package com.retrovault.domain.entity

import com.retrovault.domain.catalog.CanonicalIdentityKey
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.ArtifactId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.PlatformId
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.identity.WorkId

/**
 * The entity chain one catalogue record projects into, with its edges.
 *
 * Returned as a unit because the four entities are only meaningful together: a
 * release with no work and no platform is not a partial answer, it is a broken
 * one.
 */
data class PromotedIdentity(
    val platform: Platform,
    val work: Work,
    val release: Release,
    val artifact: Artifact,
    val relationships: List<EntityRelationship>,
) {
    val entities: List<CanonicalEntity> get() = listOf(platform, work, release, artifact)
}

/**
 * Projects catalogue evidence into canonical entities.
 *
 * This is the bridge between what a dataset says and what RetroVault holds.
 * Constitution section 145 keeps a [DumpRecord] as *external evidence*; these
 * entities are RetroVault's own reading of it, which is why promotion produces
 * [EntityProvenance.DERIVED] and never overwrites something a user confirmed
 * (section 43).
 *
 * Identifiers are derived, not generated. Two datasets describing the same
 * release produce the same [ReleaseId], so importing No-Intro and Redump gives
 * one release with two artifacts rather than two parallel graphs - and a rescan
 * produces the entities that already exist rather than duplicates of them.
 *
 * Nothing here decides *whether* a record describes the user's file. That is
 * the resolver's job, and promotion only ever runs on a record the resolver
 * already selected.
 */
object EntityPromoter {
    const val VERSION: String = "entity-promoter-v1"

    fun promote(record: DumpRecord): PromotedIdentity {
        val key = record.canonicalIdentityKey
        val platform = Platform(id = platformId(key), name = record.platform)
        val work = Work(
            id = workId(key),
            canonicalTitle = record.canonicalTitle,
            normalizedTitle = record.normalizedTitle,
            // The dataset's own set name is how this work is written elsewhere.
            // It is an alias, never a second work (Constitution section 43).
            aliases = setOf(record.setName).filterTo(mutableSetOf()) { it != record.canonicalTitle },
        )
        val release = Release(
            id = releaseId(key),
            workId = work.id,
            platformId = platform.id,
            regions = record.regions,
            languages = record.languages,
            revision = record.revision,
            version = record.version,
            discNumber = record.discNumber,
            flags = record.flags,
        )
        val artifact = Artifact(
            id = artifactId(record),
            releaseId = release.id,
            mediaType = record.mediaType,
            size = record.size,
            hashes = record.hashes,
            aliases = setOf(record.romName),
        )
        return PromotedIdentity(
            platform = platform,
            work = work,
            release = release,
            artifact = artifact,
            relationships = listOf(
                EntityRelationship(release.entityRef, RelationshipType.RELEASE_OF, work.entityRef),
                EntityRelationship(release.entityRef, RelationshipType.RUNS_ON, platform.entityRef),
                EntityRelationship(artifact.entityRef, RelationshipType.IMAGE_OF, release.entityRef),
            ),
        )
    }

    /**
     * A platform is identified by its name alone.
     *
     * Two datasets naming the same console differently produce two platforms
     * today. Merging those is a canonical merge, which section 43 reserves for
     * human or high-confidence evidence - so it is left undone rather than
     * guessed at.
     */
    fun platformId(key: CanonicalIdentityKey): PlatformId = PlatformId("platform:${key.platform}")

    /**
     * A work is identified by its normalized title within a platform.
     *
     * Scoping to platform is a deliberate under-merge. The same title on SNES
     * and on PSP is very often a port rather than one work, and section 32
     * requires a port to be an explicit, evidenced relationship. Producing one
     * work automatically would be exactly the inference that section forbids;
     * two works joined later by a confirmed `PORT_OF` edge loses nothing.
     */
    fun workId(key: CanonicalIdentityKey): WorkId = WorkId("work:${key.platform}:${key.normalizedTitle}")

    /**
     * A release is identified by everything that distinguishes one publication
     * from another - which is precisely [CanonicalIdentityKey].
     */
    fun releaseId(key: CanonicalIdentityKey): ReleaseId = ReleaseId(
        "release:" + listOf(
            key.platform,
            key.normalizedTitle,
            key.regions.joinToString(","),
            key.revision.orEmpty(),
            key.version.orEmpty(),
            key.discNumber?.toString().orEmpty(),
            key.flags.joinToString(","),
        ).joinToString("|"),
    )

    /**
     * An artifact is identified by its content.
     *
     * The strongest available hash, because that is what makes two datasets
     * describing the same bytes converge on one artifact. A record with no hash
     * at all cannot be content-identified, so it falls back to its record id -
     * which keeps it distinct rather than letting it collide with another
     * hashless record of the same size.
     */
    fun artifactId(record: DumpRecord): ArtifactId {
        val strongest = listOf(HashAlgorithm.SHA1, HashAlgorithm.MD5, HashAlgorithm.CRC32)
            .firstNotNullOfOrNull { algorithm -> record.hashes[algorithm] }
        return if (strongest == null) {
            ArtifactId("artifact:record:${record.id.value}")
        } else {
            ArtifactId("artifact:${strongest.algorithm.name.lowercase()}:${strongest.hex}")
        }
    }
}
