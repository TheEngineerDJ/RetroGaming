package com.retrovault.application

import com.retrovault.domain.correction.CorrectedIdentity
import com.retrovault.domain.correction.CorrectionApplier
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.CorrectionSet
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.identity.CorrectionId
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.resolution.ArtifactResolution

/** Why a correction could not be recorded. */
enum class CorrectionRefusal {
    /**
     * No cryptographic hash is known for the artifact, so nothing durable can
     * be keyed on. Correcting it would produce an assertion that stops applying
     * the next time the file is scanned, which is worse than refusing.
     */
    NOT_CONTENT_IDENTIFIED,
}

/**
 * Records what the user says an artifact actually is.
 *
 * Constitution section 218: a correction must not require the user to prove
 * expertise. Nothing here judges whether they are right - it records that they
 * said so, with everything section 69 requires kept alongside.
 */
class RecordCorrectionUseCase(
    private val corrections: CorrectionStore,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    /**
     * @param resolution what automatic identification concluded, so the previous
     * claim survives the correction (Constitution section 69).
     */
    suspend fun correct(
        observation: FileObservation,
        resolution: ArtifactResolution,
        corrected: CorrectedIdentity,
        reason: String? = null,
    ): Outcome<IdentityCorrection> {
        val scope = CorrectionScope.forObservation(observation)
            ?: return Outcome.failure(
                RetroVaultFailure.CorrectionRefused(
                    CorrectionRefusal.NOT_CONTENT_IDENTIFIED,
                    "This file has no cryptographic hash, so a correction could not be tied to its " +
                        "contents and would stop applying after the next scan.",
                ),
            )

        return corrections.record(
            IdentityCorrection(
                id = CorrectionId(ids.next("correction")),
                scope = scope,
                previousIdentityDescription = resolution.selected
                    ?.record
                    ?.canonicalIdentityKey
                    ?.describe(),
                corrected = corrected,
                reason = reason,
                recordedAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    /** Takes a correction back. Automatic identification applies again afterwards. */
    suspend fun withdraw(observation: FileObservation): Outcome<Unit> {
        val scope = CorrectionScope.forObservation(observation)
            ?: return Outcome.failure(
                RetroVaultFailure.CorrectionRefused(
                    CorrectionRefusal.NOT_CONTENT_IDENTIFIED,
                    "This file has no cryptographic hash, so it can carry no correction to withdraw.",
                ),
            )
        return corrections.withdraw(scope)
    }

    /** Everything ever asserted about this content, newest first (Constitution section 70). */
    suspend fun history(observation: FileObservation): Outcome<List<IdentityCorrection>> {
        val scope = CorrectionScope.forObservation(observation)
            ?: return Outcome.success(emptyList())
        return corrections.history(scope)
    }
}

/**
 * Overlays active corrections onto automatic identification.
 *
 * Separate from [ResolveArtifactUseCase] on purpose. The resolver reaches its
 * own conclusion from evidence alone, and this decides what to do with that
 * conclusion afterwards; folding the two together would let a stored assertion
 * change what the evidence appears to say.
 */
class ApplyCorrectionsUseCase(private val graph: EntityGraph) {

    suspend fun apply(
        resolution: ArtifactResolution,
        observation: FileObservation,
        active: CorrectionSet,
    ): ArtifactResolution {
        if (active.isEmpty) return resolution
        val correction = active.forObservation(observation) ?: return resolution
        // The lookup is resolved eagerly so the domain function stays pure and
        // synchronous; only releases a correction actually names are fetched.
        val records = when (val corrected = correction.corrected) {
            is CorrectedIdentity.IsRelease -> graph.recordsForRelease(corrected.releaseId)
            is CorrectedIdentity.NotThis -> emptyList()
        }
        return CorrectionApplier.apply(resolution, correction) { records }
    }
}
