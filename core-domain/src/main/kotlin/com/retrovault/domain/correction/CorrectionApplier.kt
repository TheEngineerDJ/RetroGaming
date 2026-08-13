package com.retrovault.domain.correction

import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.evidence.Evidence
import com.retrovault.domain.evidence.EvidenceStrength
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.HashAlgorithm
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
            supporting = buildList {
                add(
                    Evidence.supporting(
                        MatchSignal.UserCorrection,
                        // Strong, not decisive. The user is the highest
                        // authority over their own collection and still has not
                        // shown that these bytes are that release;
                        // IdentityBasis.USER_ASSERTED carries that distinction.
                        EvidenceStrength.STRONG,
                        describe(correction),
                        source = record.source,
                    ),
                )
                // When the content agrees with what the user named, that
                // agreement is a measurement and is recorded as one. It is what
                // lets the rename policy authorise on evidence rather than on
                // assertion - and when it is absent, its absence is the reason
                // the rename waits for review.
                addAll(corroboration(resolution, record))
            },
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

    /**
     * Content-level agreement between the observed bytes and the named release.
     *
     * Only cryptographic hashes count. CRC32 agreeing is a discriminator, not
     * proof (Constitution section 148), and treating it as verification here
     * would let a 32-bit collision authorise a rename the user never checked.
     */
    private fun corroboration(resolution: ArtifactResolution, record: DumpRecord): List<Evidence> =
        HashAlgorithm.entries
            .filter { it.isCryptographicIdentityEvidence }
            .mapNotNull { algorithm ->
                val observed = resolution.hashes[algorithm] ?: return@mapNotNull null
                val catalogued = record.hashes[algorithm] ?: return@mapNotNull null
                if (observed != catalogued) return@mapNotNull null
                Evidence.supporting(
                    MatchSignal.HashExact(algorithm),
                    EvidenceStrength.DECISIVE,
                    "${algorithm.canonicalName} of this file matches the release you named, so the " +
                        "content agrees with your correction.",
                    source = record.source,
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
