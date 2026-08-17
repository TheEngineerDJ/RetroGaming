package com.retrovault.domain.policy

import com.retrovault.domain.evidence.EvidenceStrength
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.ResolutionState

/**
 * Risk of an action, independent of how confident the identification is.
 *
 * Constitution section 276. Automation requirements rise with risk, which is
 * why confidence alone never authorises a mutation
 * (Constitution section 262/263).
 */
enum class RiskLevel {
    /** Read-only suggestion. */
    R0,

    /** Reversible presentation change. */
    R1,

    /** User-data mutation with easy recovery. Renaming a file lives here. */
    R2,

    /** Consequential user-data mutation. */
    R3,
}

/** What automation is permitted for one resolution. */
enum class AutomationDecision {
    /** May be executed without per-file confirmation. */
    AUTOMATIC,

    /** May only be executed after the user explicitly confirms this file. */
    REQUIRES_REVIEW,

    /** Must never be executed, confirmed or not. */
    FORBIDDEN,
}

/**
 * Decides whether a resolution may drive a rename.
 *
 * CLAUDE_CODE.md: never auto-rename an unresolved artifact. UX_SPEC.md
 * section 16: never silently rename. Constitution section 168: unknown files
 * are never renamed merely to make a frontend display artwork.
 *
 * The policy is versioned because changing it changes what the application is
 * willing to do to user files (Constitution section 185, SECURITY_SPEC.md
 * section 9).
 */
data class AutomationPolicy(
    /**
     * Whether a CRC32-plus-size match against a hash-poor catalogue record may
     * be renamed without per-file confirmation.
     *
     * Off by default: Constitution section 168 permits this only when the user
     * has explicitly enabled that policy and no conflicting candidate exists.
     */
    val allowStructuralAutomation: Boolean = false,
    /**
     * Whether a dump whose payload matched once a header was skipped may be
     * renamed without per-file confirmation.
     *
     * Off by default. The measurement is real - a cryptographic hash of the
     * payload matched the catalogued digest - but the canonical name describes
     * the dump the dataset holds, and this file is not that dump. Renaming it
     * to that name is a small claim beyond what was measured, and Constitution
     * section 263 puts a high-risk action on that footing behind review. A user
     * normalising a headered library can turn it on once rather than confirming
     * every file.
     */
    val allowHeaderedAutomation: Boolean = false,
    val riskLevel: RiskLevel = RiskLevel.R2,
) {
    fun decide(resolution: ArtifactResolution): AutomationDecision {
        val selected = resolution.selected ?: return forbiddenOrReview(resolution.state)
        if (selected.hasContradiction && selected.contradicting.any { it.strength.isBlocking }) {
            return AutomationDecision.REQUIRES_REVIEW
        }
        return when (resolution.state) {
            ResolutionState.EXACT_HASH,
            ResolutionState.EXACT_MULTI_HASH,
            -> AutomationDecision.AUTOMATIC

            // The payload was verified, so this is never forbidden. What is at
            // stake is whether the *file* may be given the catalogued dump's
            // name without the user seeing that a header was skipped to get
            // there (Constitution section 200 and section 306).
            ResolutionState.MODIFIED_MATCH ->
                if (allowHeaderedAutomation) {
                    AutomationDecision.AUTOMATIC
                } else {
                    AutomationDecision.REQUIRES_REVIEW
                }

            ResolutionState.STRUCTURAL_MATCH ->
                if (allowStructuralAutomation && resolution.candidates.size == 1) {
                    AutomationDecision.AUTOMATIC
                } else {
                    AutomationDecision.REQUIRES_REVIEW
                }

            ResolutionState.STRONG_METADATA_MATCH,
            ResolutionState.FUZZY_MATCH,
            -> AutomationDecision.REQUIRES_REVIEW

            // A correction settles what RetroVault *believes*; it does not by
            // itself authorise touching the file. Identity and authorisation
            // are separate questions (Constitution section 262: confidence
            // alone never authorises a mutation), and a user assertion is a
            // claim about identity, not a measurement of it. Constitution
            // section 263 reads directly: a high-risk action on uncertain
            // evidence requires human review, and an assertion nothing has
            // checked is uncertain evidence about the content whoever made it.
            //
            // So a correction is reviewable like any other unverified identity,
            // and becomes automatic only when the content independently agrees
            // - when a cryptographic hash of the bytes matches the digest
            // catalogued for the release the user named. Then the rename rests
            // on the measurement, and the correction merely pointed at it.
            ResolutionState.USER_CORRECTED ->
                if (selected.hasIndependentContentAgreement) {
                    AutomationDecision.AUTOMATIC
                } else {
                    AutomationDecision.REQUIRES_REVIEW
                }

            ResolutionState.AMBIGUOUS,
            ResolutionState.CONFLICT,
            ResolutionState.NO_MATCH,
            ResolutionState.OUT_OF_CATALOGUE_SCOPE,
            ResolutionState.USER_REJECTED,
            ResolutionState.UNSUPPORTED,
            -> AutomationDecision.FORBIDDEN
        }
    }

    private fun forbiddenOrReview(state: ResolutionState): AutomationDecision = when (state) {
        // Ambiguity and conflict are reviewable: the user may pick a candidate.
        ResolutionState.AMBIGUOUS, ResolutionState.CONFLICT -> AutomationDecision.REQUIRES_REVIEW
        else -> AutomationDecision.FORBIDDEN
    }

    companion object {
        const val VERSION: String = "automation-policy-v1"
    }
}

private val EvidenceStrength.isBlocking: Boolean
    get() = this == EvidenceStrength.DECISIVE || this == EvidenceStrength.STRONG
