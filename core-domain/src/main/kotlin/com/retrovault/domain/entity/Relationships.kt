package com.retrovault.domain.entity

/** Which of the four canonical entity types a reference points at. */
enum class EntityKind {
    PLATFORM,
    WORK,
    RELEASE,
    ARTIFACT,
}

/**
 * A typed pointer to one canonical entity.
 *
 * Relationships need to name entities of different kinds on both ends, so they
 * cannot hold a `WorkId` or a `ReleaseId` directly. This carries the kind
 * alongside the value, which keeps a stored edge readable without joining four
 * tables to discover what it points at.
 */
data class EntityRef(val kind: EntityKind, val value: String) {
    init {
        require(value.isNotBlank()) { "An entity reference must not be blank" }
    }

    /** Stable textual form, e.g. `RELEASE:no_intro:...`. Used for persistence and logging. */
    fun describe(): String = "${kind.name}:$value"
}

/**
 * The controlled relationship vocabulary.
 *
 * Constitution section 40: relationships are first-class knowledge, the
 * vocabulary stays controlled, and free-form relationship text must not become
 * the canonical graph structure.
 *
 * Only the relations that connect the four entity types are defined here.
 * Section 40 lists many more - published by, repaired by, owned by, sold by -
 * and each of those needs an entity type RetroVault does not yet have. Adding a
 * relation whose other end cannot exist would be a vocabulary that describes
 * nothing, so they arrive with the entities they connect.
 */
enum class RelationshipType(
    val from: EntityKind,
    val to: EntityKind,
    /** True when the edge is what makes the graph a hierarchy rather than a set. */
    val isStructural: Boolean,
) {
    /** A release is one published form of a work (Constitution section 31). */
    RELEASE_OF(EntityKind.RELEASE, EntityKind.WORK, isStructural = true),

    /** A release targets a platform family (Constitution section 33). */
    RUNS_ON(EntityKind.RELEASE, EntityKind.PLATFORM, isStructural = true),

    /** An artifact is a digital image of a release (Constitution section 38). */
    IMAGE_OF(EntityKind.ARTIFACT, EntityKind.RELEASE, isStructural = true),

    /**
     * A release adapts substantially the same work to another platform
     * (Constitution section 32).
     *
     * The derivation relations below are not structural: a release stands
     * perfectly well without one, and asserting one is a historical claim that
     * section 32 says must not be inferred from marketing language.
     */
    PORT_OF(EntityKind.RELEASE, EntityKind.RELEASE, isStructural = false),

    /** A release recreates a work with substantial redevelopment (section 32). */
    REMAKE_OF(EntityKind.RELEASE, EntityKind.RELEASE, isStructural = false),

    /** A release modifies presentation while retaining a strong relationship (section 32). */
    REMASTER_OF(EntityKind.RELEASE, EntityKind.RELEASE, isStructural = false),

    /** A work is packaged inside a compilation release (section 32). */
    INCLUDED_IN(EntityKind.WORK, EntityKind.RELEASE, isStructural = false),
    ;

    companion object {
        const val VERSION: String = "relationship-v1"
    }
}

/**
 * How firmly a relationship is asserted.
 *
 * Constitution section 43 applies to edges as much as to entities: a structural
 * edge RetroVault derived from a catalogue record is a proposal. Section 32
 * adds that a port or remake claim must rest on historical evidence rather than
 * on marketing language, which is why nothing in this codebase derives one
 * automatically - they can only be [EntityProvenance.CONFIRMED].
 */
data class EntityRelationship(
    val from: EntityRef,
    val type: RelationshipType,
    val to: EntityRef,
    val provenance: EntityProvenance = EntityProvenance.DERIVED,
    /** Free text the user supplied when asserting this edge. Never parsed. */
    val note: String? = null,
) {
    init {
        require(from.kind == type.from) {
            "${type.name} starts at ${type.from}, not ${from.kind}"
        }
        require(to.kind == type.to) {
            "${type.name} ends at ${type.to}, not ${to.kind}"
        }
        require(from != to) { "An entity cannot relate to itself" }
    }

    /** Stable key for storage and for de-duplication. An edge is its endpoints and its type. */
    fun key(): String = "${from.describe()}|${type.name}|${to.describe()}"
}
