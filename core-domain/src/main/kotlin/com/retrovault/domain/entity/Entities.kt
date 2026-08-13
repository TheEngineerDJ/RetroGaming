package com.retrovault.domain.entity

import com.retrovault.domain.identity.ArtifactId
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.PlatformId
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.ReleaseFlag
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.identity.WorkId
import com.retrovault.domain.naming.NormalizedTitle

/**
 * How firmly an entity's identity is established.
 *
 * Constitution section 43 draws this line: automated matching may *propose*
 * identity links, but human or high-confidence evidence must establish an
 * important canonical merge. An entity RetroVault derived from a catalogue
 * record is a proposal; one the user confirmed is settled. Storing which is
 * which is what stops a later automatic pass quietly overwriting a decision a
 * person made.
 */
enum class EntityProvenance {
    /** Projected from catalogue evidence. A proposal, not a ruling. */
    DERIVED,

    /** Established by an explicit user decision. Automation must not overwrite it. */
    CONFIRMED,
    ;

    val isConfirmed: Boolean get() = this == CONFIRMED
}

/**
 * One canonical entity in the knowledge graph.
 *
 * The four types below are the Constitution's `Platform -> Work -> Release ->
 * Artifact` chain (section 305). They are deliberately *not* a second copy of
 * [com.retrovault.domain.catalog.DumpRecord]: a dump record is external
 * evidence written by a dataset (section 145), while these are RetroVault's own
 * reading of what that evidence describes. One record projects into exactly one
 * artifact, but several records from several datasets project into the *same*
 * release, which is the whole reason the distinction is needed.
 */
sealed interface CanonicalEntity {
    val entityRef: EntityRef
    val provenance: EntityProvenance

    /**
     * Names this entity is also known by (Constitution section 43).
     *
     * Aliases are search aids. They are never separate entities, and adding one
     * never changes what the entity *is*.
     */
    val aliases: Set<String>
}

/**
 * A platform family (Constitution section 33).
 *
 * Family level on purpose. Hardware models and revisions hang below a platform
 * and are not modelled yet; when they are, they attach here rather than
 * replacing this.
 */
data class Platform(
    val id: PlatformId,
    val name: PlatformName,
    override val provenance: EntityProvenance = EntityProvenance.DERIVED,
    override val aliases: Set<String> = emptySet(),
) : CanonicalEntity {
    override val entityRef: EntityRef get() = EntityRef(EntityKind.PLATFORM, id.value)
}

/**
 * A game concept - the underlying work (Constitution section 31).
 *
 * One work has many releases. The work deliberately carries no region, no
 * revision and no platform: those distinguish releases *of* it, and folding
 * them in here is exactly the collapse section 31 forbids.
 */
data class Work(
    val id: WorkId,
    val canonicalTitle: String,
    val normalizedTitle: NormalizedTitle,
    override val provenance: EntityProvenance = EntityProvenance.DERIVED,
    override val aliases: Set<String> = emptySet(),
) : CanonicalEntity {
    init {
        require(canonicalTitle.isNotBlank()) { "A work must have a title" }
    }

    override val entityRef: EntityRef get() = EntityRef(EntityKind.WORK, id.value)
}

/**
 * One specific published form of a work (Constitution section 31).
 *
 * The fields here are the ones that make two publications of the same game
 * genuinely different things: platform, region, language, revision, version,
 * disc and release flags. They are the same fields
 * [com.retrovault.domain.catalog.CanonicalIdentityKey] already groups records
 * by, and that is not a coincidence - the key is how a release is recognised,
 * and this is the entity it recognises.
 */
data class Release(
    val id: ReleaseId,
    val workId: WorkId,
    val platformId: PlatformId,
    val regions: List<RegionCode> = emptyList(),
    val languages: List<LanguageCode> = emptyList(),
    val revision: String? = null,
    val version: String? = null,
    val discNumber: Int? = null,
    val flags: Set<ReleaseFlag> = emptySet(),
    override val provenance: EntityProvenance = EntityProvenance.DERIVED,
    override val aliases: Set<String> = emptySet(),
) : CanonicalEntity {
    override val entityRef: EntityRef get() = EntityRef(EntityKind.RELEASE, id.value)
}

/**
 * One digital image of a release (Constitution section 38).
 *
 * A release may exist as several artifacts - a cartridge dump and a headered
 * copy of it, a disc as `.cue`+`.bin` and as `.chd`. Each is a distinct digital
 * image with its own hashes; none of them is "the" release.
 */
data class Artifact(
    val id: ArtifactId,
    val releaseId: ReleaseId,
    val mediaType: MediaType,
    /** Catalogued byte size, or `null` when no dataset states one. */
    val size: Long?,
    val hashes: HashDigests,
    override val provenance: EntityProvenance = EntityProvenance.DERIVED,
    override val aliases: Set<String> = emptySet(),
) : CanonicalEntity {
    init {
        require(size == null || size >= 0) { "An artifact size must not be negative" }
    }

    override val entityRef: EntityRef get() = EntityRef(EntityKind.ARTIFACT, id.value)
}
