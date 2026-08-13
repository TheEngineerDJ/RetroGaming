package com.retrovault.domain.correction

import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.evidence.Evidence
import com.retrovault.domain.evidence.EvidenceStrength
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.ReleaseId
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.Candidate
import com.retrovault.domain.resolution.ResolutionState

/**
 * Applies a user's correction over an automatic identification.
 *
 * Pure, so the rule that a person outranks the pipeline is testable without a
 * database and cannot drift into infrastructure. The resolver itself is
 * untouched: it produces the same answer it always did, and this decides what
 * to do with that answer afterwards. That ordering matters - a correction must
 * never be able to influence what the evidence says, only what is concluded
 * from it.
 *
 * DOMAIN_MODEL.md section 37 invariant 13: user corrections outrank automatic
 * suggestions for that user's collection. Constitution section 44: the
 * disagreement is preserved rather than flattened, so every automatic candidate
 * survives in the result with its evidence intact.
 */
object CorrectionApplier {
    const val VERSION: String = "correction-applier-v1"

    /**
     * @param releaseLookup resolves the release the user named to the catalogue
     * records describing it. Returning an empty list means the user named a
     * release RetroVault can no longer find - the correction still stands as a
     * rejection of the automatic answer, because the user's disagreement does
     * not evaporate when a dataset is removed.
     */
    fun apply(
        resolution: ArtifactResolution,
        correction: IdentityCorrection,
        releaseLookup: (ReleaseId) -> List<DumpRecord>,
    ): ArtifactResolution {
        if (!correction.state.appliesToResolution) return resolution

        return when (val corrected = correction.corrected) {
            is CorrectedIdentity.NotThis -> reject(resolution, correction)
            is CorrectedIdentity.IsRelease -> {
                val records = releaseLookup(corrected.releaseId)
                val chosen = pick(records, resolution)
                if (chosen == null) reject(resolution, correction) else assert(resolution, correction, chosen)
            }
        }
    }

    /**
     * Picks the record that best represents the release the user named.
     *
     * A release can be described by several records - two datasets, or several
     * tracks. Preferring the one whose content the observation already matches
     * keeps the artifact's own hashes meaningful; otherwise the ordering is
     * deterministic so a rescan does not silently swap one record for another.
     */
    private fun pick(records: List<DumpRecord>, resolution: ArtifactResolution): DumpRecord? {
        if (records.isEmpty()) return null
        val observedHashes = resolution.selected?.record?.hashes
        val matchingContent = observedHashes?.let { hashes ->
            records.firstOrNull { record ->
                record.hashes.asList().any { it == hashes[it.algorithm] }
            }
        }
        return matchingContent ?: records.sortedWith(
            compareBy({ it.source.provider }, { it.setName }, { it.romName }, { it.id.value }),
        ).first()
    }

    private fun assert(
        resolution: ArtifactResolution,
        correction: IdentityCorrection,
        record: DumpRecord,
    ): ArtifactResolution {
        val asserted = Candidate(
            record = record,
            supporting = listOf(
                Evidence.supporting(
                    MatchSignal.UserCorrection,
                    // Strong, not decisive. The user is the highest authority
                    // over their own collection and still has not shown that
                    // these bytes are that release; IdentityBasis.USER_ASSERTED
                    // is what carries that distinction to the reader.
                    EvidenceStrength.STRONG,
                    describe(correction),
                    source = record.source,
                ),
            ),
            score = 100,
        )
        return resolution.copy(
            state = ResolutionState.USER_CORRECTED,
            confidence = ConfidenceLevel.forState(ResolutionState.USER_CORRECTED),
            selected = asserted,
            // Everything the pipeline concluded stays, including the candidate
            // the user overruled. Constitution section 44: preserve both claims.
            candidates = (listOf(asserted) + resolution.candidates.filter { it.record.id != record.id }),
        )
    }

    private fun reject(resolution: ArtifactResolution, correction: IdentityCorrection): ArtifactResolution =
        resolution.copy(
            state = ResolutionState.USER_REJECTED,
            confidence = ConfidenceLevel.forState(ResolutionState.USER_REJECTED),
            selected = null,
            candidates = resolution.candidates.map { candidate ->
                candidate.copy(
                    contradicting = candidate.contradicting + Evidence.contradicting(
                        MatchSignal.UserRejection,
                        EvidenceStrength.STRONG,
                        describe(correction),
                    ),
                )
            },
        )

    private fun describe(correction: IdentityCorrection): String {
        val previous = correction.previousIdentityDescription
            ?.let { " RetroVault had identified it as $it." }
            .orEmpty()
        val reason = correction.reason?.takeIf { it.isNotBlank() }?.let { " Reason: $it" }.orEmpty()
        return "You corrected this file's identity.$previous$reason"
    }

    /**
     * The release a correction should name for [record].
     *
     * Uses the same derivation as [EntityPromoter] so a correction and the
     * entity graph cannot disagree about which release is which.
     */
    fun releaseIdFor(record: DumpRecord): ReleaseId =
        EntityPromoter.releaseId(record.canonicalIdentityKey)
}
