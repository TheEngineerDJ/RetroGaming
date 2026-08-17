package com.retrovault.application

import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.correction.CorrectedIdentity
import com.retrovault.domain.correction.CorrectionApplier
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.ScanSessionId

/**
 * One identity a user may choose for a file.
 *
 * Carries the record id rather than a display string because the choice has to
 * survive being shown: a label is ambiguous between two datasets describing the
 * same set name, and picking by label would let the wrong one be recorded.
 */
data class CandidateChoice(
    val recordId: DumpRecordId,
    val label: String,
    val provider: String,
    val setName: String,
    /** Whether this is the identity automatic matching selected. */
    val selected: Boolean,
    /** Everything that argued for and against it, already phrased for a reader. */
    val supporting: List<String>,
    val contradicting: List<String>,
)

/**
 * One past decision, with words a reader can act on.
 *
 * A correction stores the release it named as an identifier, which is the right
 * thing to persist and the wrong thing to show: a user cannot recognise
 * `release:SNES|chrono trigger|USA||||` as the game they picked last week. The
 * label is resolved from the candidates when one of them is that release, and
 * says so plainly when none is - guessing a title from an identifier would be
 * inventing a fact the correction never recorded.
 */
data class CorrectionEntry(
    val correction: IdentityCorrection,
    val describedAs: String,
)

/**
 * What a user needs in order to disagree with automatic identification.
 *
 * Constitution section 44 requires the disagreement to be visible rather than
 * flattened, and section 218 requires that expressing it demand no expertise.
 * That means the overruled candidates travel with the selected one, each with
 * its own evidence - a user choosing between two identities is doing evidence
 * assessment, and cannot do it from names alone.
 */
data class ReviewSubject(
    val observationId: ObservationId,
    val filename: String,
    val relativePath: String,
    val resolutionState: String,
    val identityBasis: String,
    val candidates: List<CandidateChoice>,
    /** Every correction ever recorded for this content, newest first. */
    val history: List<CorrectionEntry>,
    /** True when a correction is currently overriding automatic identification. */
    val corrected: Boolean,
)

/**
 * Reviewing one file and recording what the user decides.
 *
 * Sits between the screen and [RecordCorrectionUseCase] because a screen has an
 * [ObservationId] and the correction model needs the observation itself, its
 * resolution and the catalogue record behind the chosen candidate. Doing that
 * lookup here keeps it testable on a JVM and keeps the view model free of
 * domain reasoning (ENGINEERING_SPEC.md section 18).
 */
class ReviewObservationUseCase(
    private val observations: ObservationRepository,
    private val corrections: RecordCorrectionUseCase,
) {

    /** Everything needed to review one file, including what it was corrected to before. */
    suspend fun subject(sessionId: ScanSessionId, observationId: ObservationId): Outcome<ReviewSubject> {
        val resolved = when (val found = find(sessionId, observationId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        val history = when (val found = corrections.history(resolved.observation)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        val selectedId = resolved.resolution.selected?.record?.id
        return Outcome.success(
            ReviewSubject(
                observationId = resolved.observation.id,
                filename = resolved.observation.filename,
                relativePath = resolved.observation.relativePath,
                resolutionState = resolved.resolution.state.name,
                identityBasis = resolved.resolution.identityBasis.name,
                candidates = resolved.resolution.candidates.map { candidate ->
                    CandidateChoice(
                        recordId = candidate.record.id,
                        label = candidate.record.canonicalIdentityKey.describe(),
                        provider = candidate.record.source.provider,
                        setName = candidate.record.setName,
                        selected = candidate.record.id == selectedId,
                        supporting = candidate.supporting.map { it.description },
                        contradicting = candidate.contradicting.map { it.description },
                    )
                },
                history = history.map { describe(it, resolved.resolution.candidates.map { c -> c.record }) },
                corrected = history.any { it.state.appliesToResolution },
            ),
        )
    }

    /**
     * Puts words to what a correction said.
     *
     * Only ever names a release this file actually had as a candidate. When the
     * named release is not among them - the dataset was removed, or the user
     * corrected it during an earlier scan with different datasets imported -
     * the entry says the identity is one RetroVault can no longer describe,
     * which is true, rather than printing an identifier as though it were a
     * title.
     */
    private fun describe(
        correction: IdentityCorrection,
        candidates: List<DumpRecord>,
    ): CorrectionEntry {
        val described = when (val corrected = correction.corrected) {
            is CorrectedIdentity.NotThis -> "None of RetroVault's answers is right"
            is CorrectedIdentity.IsRelease ->
                candidates
                    .firstOrNull { CorrectionApplier.releaseIdFor(it) == corrected.releaseId }
                    ?.let { "It is ${it.canonicalIdentityKey.describe()}" }
                    ?: "It is a release none of the current datasets describes"
        }
        return CorrectionEntry(correction, described)
    }

    /**
     * Records that this file is the release one of its candidates describes.
     *
     * The release is derived from the record with the same function the entity
     * graph uses, so a correction and the graph cannot disagree about which
     * release was named.
     */
    suspend fun correctToCandidate(
        sessionId: ScanSessionId,
        observationId: ObservationId,
        recordId: DumpRecordId,
        reason: String? = null,
    ): Outcome<IdentityCorrection> {
        val resolved = when (val found = find(sessionId, observationId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        val record = resolved.resolution.candidates.firstOrNull { it.record.id == recordId }?.record
            ?: return Outcome.failure(
                RetroVaultFailure.CorrectionRefused(
                    CorrectionRefusal.NOT_CONTENT_IDENTIFIED,
                    "That identity is not one of the candidates RetroVault found for this file, so it " +
                        "cannot be recorded against it.",
                ),
            )
        return corrections.correct(
            observation = resolved.observation,
            resolution = resolved.resolution,
            corrected = CorrectedIdentity.IsRelease(CorrectionApplier.releaseIdFor(record)),
            reason = reason,
        )
    }

    /**
     * Records that none of the candidates is right.
     *
     * Distinct from withdrawing: the user has said something, and section 42
     * makes that a fact worth keeping. The file stops being renamed and stops
     * being offered the same wrong answer on every rescan.
     */
    suspend fun reject(
        sessionId: ScanSessionId,
        observationId: ObservationId,
        reason: String? = null,
    ): Outcome<IdentityCorrection> {
        val resolved = when (val found = find(sessionId, observationId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        return corrections.correct(
            observation = resolved.observation,
            resolution = resolved.resolution,
            corrected = CorrectedIdentity.NotThis,
            reason = reason,
        )
    }

    /** Takes a correction back, so automatic identification applies again. */
    suspend fun withdraw(sessionId: ScanSessionId, observationId: ObservationId): Outcome<Unit> =
        when (val found = find(sessionId, observationId)) {
            is Outcome.Success -> corrections.withdraw(found.value.observation)
            is Outcome.Failure -> found
        }

    private suspend fun find(
        sessionId: ScanSessionId,
        observationId: ObservationId,
    ): Outcome<ResolvedObservation> {
        val stored = when (val found = observations.findBySession(sessionId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> return found
        }
        val match = stored.firstOrNull { it.observation.id == observationId }
            ?: return Outcome.failure(
                RetroVaultFailure.PersistenceFailure(
                    "That file is not part of this scan any more. Scan again and review it there.",
                ),
            )
        return Outcome.success(match)
    }
}
