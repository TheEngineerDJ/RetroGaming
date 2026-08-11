package com.retrovault.domain.resolution

import com.retrovault.domain.catalog.CanonicalIdentityKey
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.evidence.Evidence
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.ObservationId

/**
 * How an artifact was resolved.
 *
 * ROM_INTELLIGENCE.md section 7 is explicit: exact and heuristic states must
 * never collapse into one generic boolean such as `matched=true`.
 */
enum class ResolutionState {
    /** One cryptographic hash (MD5 or SHA1) matched exactly. */
    EXACT_HASH,

    /** Both MD5 and SHA1 matched exactly. */
    EXACT_MULTI_HASH,

    /**
     * Size and CRC32 agree exactly and the catalogue record offers nothing
     * stronger. Strong, but not content-level proof (Constitution section 148).
     */
    STRUCTURAL_MATCH,

    /** Exact metadata agreement against a record that carries no hashes. */
    STRONG_METADATA_MATCH,

    /** Textual identification only. Never treated as content identity. */
    FUZZY_MATCH,

    /** Several candidates remain plausible. A valid, first-class outcome. */
    AMBIGUOUS,

    /** Nothing in the catalogue plausibly matches. */
    NO_MATCH,

    /** Strong evidence contradicts an otherwise promising candidate. */
    CONFLICT,

    /** The artifact cannot be identified by this pipeline at all. */
    UNSUPPORTED,
    ;

    val isExact: Boolean get() = this == EXACT_HASH || this == EXACT_MULTI_HASH

    /** Whether this state is allowed to carry a selected identity. */
    val canCarrySelection: Boolean
        get() = this in setOf(
            EXACT_HASH,
            EXACT_MULTI_HASH,
            STRUCTURAL_MATCH,
            STRONG_METADATA_MATCH,
            FUZZY_MATCH,
        )
}

/**
 * The trust label shown to users.
 *
 * Constitution section 167. Deliberately coarse: a raw score must not be
 * presented without interpretation (UX_SPEC.md section 16).
 */
enum class ConfidenceLevel {
    EXACT,
    STRONG,
    PROBABLE,
    AMBIGUOUS,
    UNKNOWN,
    ;

    companion object {
        fun forState(state: ResolutionState): ConfidenceLevel = when (state) {
            ResolutionState.EXACT_HASH, ResolutionState.EXACT_MULTI_HASH -> EXACT
            ResolutionState.STRUCTURAL_MATCH -> STRONG
            ResolutionState.STRONG_METADATA_MATCH, ResolutionState.FUZZY_MATCH -> PROBABLE
            ResolutionState.AMBIGUOUS, ResolutionState.CONFLICT -> AMBIGUOUS
            ResolutionState.NO_MATCH, ResolutionState.UNSUPPORTED -> UNKNOWN
        }
    }
}

/**
 * One possible identity for an observation, with everything that argues for
 * and against it (ROM_INTELLIGENCE.md section 7).
 */
data class Candidate(
    val record: DumpRecord,
    val supporting: List<Evidence> = emptyList(),
    val contradicting: List<Evidence> = emptyList(),
    /** Deterministic ranking score, 0..100. Never shown as a probability. */
    val score: Int = 0,
    /** Records from other datasets describing the same identity. */
    val corroborating: List<DumpRecord> = emptyList(),
) {
    val identityKey: CanonicalIdentityKey get() = record.canonicalIdentityKey

    val hasContradiction: Boolean get() = contradicting.isNotEmpty()

    /**
     * Whether something rules this candidate out entirely, as opposed to
     * merely weakening it (see [com.retrovault.domain.evidence.MatchSignal.excludesIdentity]).
     */
    val isExcluded: Boolean get() = contradicting.any { it.signal.excludesIdentity }

    val evidence: List<Evidence> get() = supporting + contradicting

    /** Datasets that independently describe this identity (Constitution section 46). */
    val independentSourceCount: Int
        get() = (listOf(record) + corroborating).map { it.source.id }.distinct().size
}

/**
 * The complete outcome of resolving one observation.
 *
 * Constitution section 165: a scanned file produces a structured result, not a
 * renamed/not-renamed flag. Everything needed to explain, audit and reproduce
 * the decision is carried here.
 */
data class ArtifactResolution(
    val observationId: ObservationId,
    val state: ResolutionState,
    val confidence: ConfidenceLevel,
    /** Non-null only when [ResolutionState.canCarrySelection] holds. */
    val selected: Candidate?,
    val candidates: List<Candidate>,
    /** Evidence about the pipeline itself rather than about one candidate. */
    val pipelineEvidence: List<Evidence>,
    val hashesComputed: Set<HashAlgorithm>,
    val consultedSources: List<DatSourceId>,
    val resolverVersion: String,
    val tokenizerVersion: String,
    val normalizerVersion: String,
) {
    init {
        require(selected == null || state.canCarrySelection) {
            "ResolutionState $state must not carry a selected identity"
        }
    }

    /** Everything the UI can show under "why did RetroVault choose this?". */
    val explanation: List<Evidence>
        get() = (selected?.evidence.orEmpty() + pipelineEvidence)

    val isResolved: Boolean get() = selected != null

    companion object {
        @Suppress("LongParameterList")
        fun terminal(
            observationId: ObservationId,
            state: ResolutionState,
            candidates: List<Candidate> = emptyList(),
            pipelineEvidence: List<Evidence> = emptyList(),
            hashesComputed: Set<HashAlgorithm> = emptySet(),
            consultedSources: List<DatSourceId> = emptyList(),
            selected: Candidate? = null,
            resolverVersion: String,
            tokenizerVersion: String,
            normalizerVersion: String,
        ): ArtifactResolution = ArtifactResolution(
            observationId = observationId,
            state = state,
            confidence = ConfidenceLevel.forState(state),
            selected = selected,
            candidates = candidates,
            pipelineEvidence = pipelineEvidence,
            hashesComputed = hashesComputed,
            consultedSources = consultedSources,
            resolverVersion = resolverVersion,
            tokenizerVersion = tokenizerVersion,
            normalizerVersion = normalizerVersion,
        )
    }
}
