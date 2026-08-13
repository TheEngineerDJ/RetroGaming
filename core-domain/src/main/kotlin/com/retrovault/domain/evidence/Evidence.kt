package com.retrovault.domain.evidence

import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.MediaType
import com.retrovault.domain.identity.RegionCode

/**
 * How much weight one signal carries.
 *
 * Constitution section 223: evidence is weighted by reliability, directness,
 * independence and specificity. These bands are the domain's vocabulary for
 * that; they are never presented to users as probabilities
 * (Constitution section 222).
 */
enum class EvidenceStrength {
    /** Content-level identity evidence: an exact cryptographic hash match. */
    DECISIVE,

    /** Exact structural agreement, e.g. CRC32 plus size against a hash-poor record. */
    STRONG,

    /** Exact metadata agreement that is not content-level. */
    MODERATE,

    /** Textual or token agreement. Never sufficient on its own. */
    WEAK,

    /** Explains the pipeline's behaviour without supporting any candidate. */
    INFORMATIONAL,
}

/**
 * A single observable reason.
 *
 * ROM_INTELLIGENCE.md section 8 requires the engine to expose reasons such as
 * "SHA1 exact match" or "multiple releases share same CRC32", so each signal is
 * a distinct type rather than a free-text string.
 */
sealed interface MatchSignal {
    /** Stable machine label, safe to persist and to key UI strings on. */
    val id: String

    /**
     * Whether this signal rules the candidate out as an identity entirely.
     *
     * Constitution section 200 draws the line this property encodes: a file
     * whose *representation* differs (headers, padding, trimming, container)
     * may still be a copy of a release, so a size difference weakens a
     * candidate without eliminating it. A region, revision, disc or
     * cryptographic-hash disagreement means it is a different thing.
     */
    val excludesIdentity: Boolean get() = false

    data class HashExact(val algorithm: HashAlgorithm) : MatchSignal {
        override val id: String get() = "hash_exact_${algorithm.name.lowercase()}"
    }

    data class HashMismatch(
        val algorithm: HashAlgorithm,
        val observed: String,
        val expected: String,
    ) : MatchSignal {
        override val id: String get() = "hash_mismatch_${algorithm.name.lowercase()}"
        override val excludesIdentity: Boolean get() = true
    }

    data object SizeExact : MatchSignal {
        override val id: String get() = "size_exact"
    }

    data class SizeMismatch(val observed: Long, val expected: Long) : MatchSignal {
        override val id: String get() = "size_mismatch"
    }

    /**
     * The observed size appears in no catalogue record.
     *
     * Constitution section 151 requires this to be visible: size filtering is
     * an optimisation, and the user must be able to tell that it, rather than
     * a real absence of the release, pushed the file into fallback matching.
     */
    data object SizeAbsentFromCatalog : MatchSignal {
        override val id: String get() = "size_absent_from_catalog"
    }

    data object TitleExact : MatchSignal {
        override val id: String get() = "title_exact"
    }

    data class TitleSimilar(val score: Int) : MatchSignal {
        override val id: String get() = "title_similar"
    }

    data class TitleConflict(val reason: String) : MatchSignal {
        override val id: String get() = "title_conflict"
        override val excludesIdentity: Boolean get() = true
    }

    data class RegionAgreement(val regions: List<RegionCode>) : MatchSignal {
        override val id: String get() = "region_agreement"
    }

    data class RegionConflict(
        val observed: List<RegionCode>,
        val expected: List<RegionCode>,
    ) : MatchSignal {
        override val id: String get() = "region_conflict"
        override val excludesIdentity: Boolean get() = true
    }

    data class RevisionConflict(val observed: String?, val expected: String?) : MatchSignal {
        override val id: String get() = "revision_conflict"
        override val excludesIdentity: Boolean get() = true
    }

    data class DiscNumberConflict(val observed: Int?, val expected: Int?) : MatchSignal {
        override val id: String get() = "disc_conflict"
        override val excludesIdentity: Boolean get() = true
    }

    /**
     * The filename carries an identity token the catalogue record does not.
     *
     * Weaker than a conflict: a record without a revision token is not
     * asserting "not Rev A". It ranks below a record that states the same
     * revision, which is enough to break a tie deterministically without
     * claiming certainty.
     */
    data class IdentityTokenUnmatched(val kind: String, val observed: String) : MatchSignal {
        override val id: String get() = "identity_token_unmatched_$kind"
    }

    /** Several DAT records share this hash but describe the same release. */
    data class CorroboratedByIndependentSources(val sourceCount: Int) : MatchSignal {
        override val id: String get() = "corroborated_by_sources"
    }

    /** Several DAT records share this hash and describe *different* releases. */
    data class SharedHashAcrossIdentities(
        val algorithm: HashAlgorithm,
        val identityCount: Int,
    ) : MatchSignal {
        override val id: String get() = "shared_hash_across_identities"
    }

    /** The catalogue record offers nothing stronger than CRC32. */
    data object CatalogHasNoCryptographicHash : MatchSignal {
        override val id: String get() = "catalog_has_no_cryptographic_hash"
    }

    /** A hash could not be computed; the reason is carried for diagnosis. */
    data class HashUnavailable(val algorithm: HashAlgorithm, val reason: String) : MatchSignal {
        override val id: String get() = "hash_unavailable"
    }

    /** The container holds more than one artifact, so it has no single identity. */
    data class ArchiveHasMultipleArtifacts(val count: Int) : MatchSignal {
        override val id: String get() = "archive_multiple_artifacts"
    }

    data class Unsupported(val reason: String) : MatchSignal {
        override val id: String get() = "unsupported"
    }

    /**
     * The candidate describes a different medium than the file appears to be.
     *
     * This weakens without excluding, and the distinction is deliberate. The
     * same disc legitimately exists as `.cue`+`.bin`, `.chd` and `.iso`, and
     * Constitution section 200 holds that a difference of *representation* does
     * not make something a different release. A medium disagreement is a reason
     * to look harder, not a reason to rule out.
     */
    data class MediaTypeMismatch(
        val observed: MediaType,
        val catalogued: MediaType,
    ) : MatchSignal {
        override val id: String get() = "media_type_mismatch"
    }

    /**
     * No imported dataset catalogues this kind of medium.
     *
     * Pipeline evidence, not candidate evidence: it says the catalogue has
     * nothing to say, which is a fact about the catalogue rather than about the
     * file (Constitution section 174).
     */
    data class MediaNotCovered(
        val observed: MediaType,
        val available: Set<MediaType>,
    ) : MatchSignal {
        override val id: String get() = "media_not_covered"
    }

    /** Nothing has been imported, so no identification was possible at all. */
    data object NoDatasetsImported : MatchSignal {
        override val id: String get() = "no_datasets_imported"
    }

    /**
     * Evidence read back from storage.
     *
     * A persisted signal keeps its stable [id], its direction and whether it
     * excluded the candidate - everything planning, display and audit need.
     * The structured payload (which hash, which regions) is not reconstructed,
     * because the human-readable description recorded alongside it already
     * carries that detail and re-deriving it would risk stating something the
     * original decision did not.
     */
    data class Recorded(
        override val id: String,
        override val excludesIdentity: Boolean,
    ) : MatchSignal
}

/**
 * One reason, with its weight, its direction and its provenance.
 *
 * TRACEABILITY.md requires evidence to survive from scanner through resolver,
 * database and UI into the audit record, so this type is what gets persisted
 * and what gets displayed. It is never reduced to a boolean.
 */
data class Evidence(
    val signal: MatchSignal,
    val strength: EvidenceStrength,
    /** `false` marks evidence that argues *against* the candidate. */
    val supports: Boolean,
    /** Stable, user-facing explanation. */
    val description: String,
    val source: DatSourceRef? = null,
) {
    val contradicts: Boolean get() = !supports

    companion object {
        fun supporting(
            signal: MatchSignal,
            strength: EvidenceStrength,
            description: String,
            source: DatSourceRef? = null,
        ): Evidence = Evidence(signal, strength, supports = true, description = description, source = source)

        fun contradicting(
            signal: MatchSignal,
            strength: EvidenceStrength,
            description: String,
            source: DatSourceRef? = null,
        ): Evidence = Evidence(signal, strength, supports = false, description = description, source = source)

        fun informational(signal: MatchSignal, description: String): Evidence =
            Evidence(signal, EvidenceStrength.INFORMATIONAL, supports = false, description = description)
    }
}
