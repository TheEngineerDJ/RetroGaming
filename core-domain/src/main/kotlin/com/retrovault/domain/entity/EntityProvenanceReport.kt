package com.retrovault.domain.entity

import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.correction.IdentityCorrection

/**
 * When RetroVault learned something, and when it last changed its mind.
 *
 * The minimum Constitution section 41 and section 70 require without a temporal
 * system. `null` means the row predates RetroVault recording this, which is a
 * different fact from "at the epoch" and is reported as unknown rather than as
 * 1 January 1970.
 */
data class EntityTimestamps(
    val firstSeenAtEpochMillis: Long?,
    val lastUpdatedAtEpochMillis: Long?,
) {
    companion object {
        val UNKNOWN = EntityTimestamps(null, null)

        /** Reads a stored pair, treating the migration default as unknown. */
        fun of(firstSeen: Long, lastUpdated: Long): EntityTimestamps = EntityTimestamps(
            firstSeenAtEpochMillis = firstSeen.takeIf { it > 0 },
            lastUpdatedAtEpochMillis = lastUpdated.takeIf { it > 0 },
        )
    }
}

/**
 * Everything that supports one entity, and everything that argues with it.
 *
 * Constitution section 196 forbids a dataset becoming an invisible authority
 * and section 44 forbids flattening a conflict to produce a cleaner interface.
 * A reader of this type can therefore always answer "why does RetroVault think
 * this, and who disagreed" without a second query.
 *
 * Nothing here is filtered for tidiness. Superseded and withdrawn corrections
 * are included, because a history that only shows the current answer is not a
 * history (section 70).
 */
data class EntityProvenanceReport(
    val ref: EntityRef,
    val provenance: EntityProvenance,
    val timestamps: EntityTimestamps,
    /** Names this entity is also known by, including ones it used to carry. */
    val aliases: Set<String>,
    /** Datasets whose records project into this entity. */
    val contributingSources: List<DatSourceRef>,
    /** Every edge touching this entity, in either direction, with its provenance. */
    val relationships: List<EntityRelationship>,
    /**
     * Corrections recorded against this entity's content, newest first.
     *
     * Only ever non-empty for an artifact: a correction is keyed by content,
     * and content is what an artifact is. Includes superseded and withdrawn
     * entries.
     *
     * This is also where an *overridden* identity stays visible. Each entry
     * carries [IdentityCorrection.previousIdentityDescription] - what
     * RetroVault had concluded before the user disagreed - so a reader can see
     * the claim that lost as well as the one that won (Constitution
     * section 44).
     */
    val corrections: List<IdentityCorrection> = emptyList(),
) {
    /**
     * True when a person established this, rather than automation proposing it.
     *
     * This, [independentSourceCount] and [hasActiveCorrection] are how firmly
     * an entity is established. There is deliberately no numeric confidence:
     * confidence is a property of *resolving one file*, and attaching a score
     * to an entity would be presenting a number nothing measured
     * (Constitution section 167).
     */
    val isUserEstablished: Boolean get() = provenance.isConfirmed

    /**
     * Whether more than one independent dataset describes this.
     *
     * Constitution section 46: several sources are not automatically
     * independent confirmation, so this counts datasets rather than claiming
     * corroboration. What it means is left to the reader.
     */
    val independentSourceCount: Int get() = contributingSources.map { it.id }.distinct().size

    /** True when a correction is currently overriding automatic identification. */
    val hasActiveCorrection: Boolean
        get() = corrections.any { it.state.appliesToResolution }
}
