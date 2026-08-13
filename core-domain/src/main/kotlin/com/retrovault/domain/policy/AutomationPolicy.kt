package com.retrovault.domain.policy

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

            ResolutionState.STRUCTURAL_MATCH ->
                if (allowStructuralAutomation && resolution.candidates.size == 1) {
                    AutomationDecision.AUTOMATIC
                } else {
                    AutomationDecision.REQUIRES_REVIEW
                }

            ResolutionState.STRONG_METADATA_MATCH,
            ResolutionState.FUZZY_MATCH,
            -> AutomationDecision.REQUIRES_REVIEW

            ResolutionState.AMBIGUOUS,
            ResolutionState.CONFLICT,
            ResolutionState.NO_MATCH,
            ResolutionState.OUT_OF_CATALOGUE_SCOPE,
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

private val com.retrovault.domain.evidence.EvidenceStrength.isBlocking: Boolean
    get() = this == com.retrovault.domain.evidence.EvidenceStrength.DECISIVE ||
        this == com.retrovault.domain.evidence.EvidenceStrength.STRONG
