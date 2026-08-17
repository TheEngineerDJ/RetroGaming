package com.retrovault.application

import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.correction.CorrectedIdentity
import com.retrovault.domain.correction.CorrectionScope
import com.retrovault.domain.correction.CorrectionSet
import com.retrovault.domain.correction.CorrectionState
import com.retrovault.domain.correction.IdentityCorrection
import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.evidence.Evidence
import com.retrovault.domain.evidence.EvidenceStrength
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.observation.FileObservation
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.Candidate
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.ResolutionState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Reviewing a file and disagreeing with what RetroVault concluded.
 *
 * Constitution section 218: expressing a correction must demand no expertise,
 * which means the screen has to be handed the overruled candidates *and* their
 * evidence. Section 44: the disagreement is preserved rather than flattened.
 */
class ReviewUseCaseTest {

    private val sessionId = ScanSessionId("session")
    private val observationId = ObservationId("observation")
    private val sha1 = HashValue.of(HashAlgorithm.SHA1, "a".repeat(40))

    private val source = DatSourceRef(
        id = DatSourceId("no_intro:Test:1"),
        provider = "no_intro",
        setName = "Test Console",
        version = "1",
        platform = PlatformName("Test Console"),
        importedAtEpochMillis = 1,
    )

    private fun record(setName: String, id: String) = DumpRecord.derive(
        id = DumpRecordId(id),
        source = source,
        setName = setName,
        romName = "$setName.sfc",
        size = 4096,
        hashes = HashDigests.of(sha1),
    )

    private val chrono = record("Chrono Trigger (USA)", "record-chrono")
    private val mario = record("Super Mario World (USA)", "record-mario")

    private val observation = FileObservation(
        id = observationId,
        sessionId = sessionId,
        storageRef = StorageRef("file:///roms/mystery.sfc"),
        parentRef = StorageRef("file:///roms"),
        filename = "mystery.sfc",
        relativePath = "mystery.sfc",
        size = 4096,
        lastModifiedEpochMillis = null,
        container = ContainerKind.RAW,
        hashes = HashDigests.of(sha1),
        observedAtEpochMillis = 1,
    )

    private fun candidate(record: DumpRecord, supporting: String) = Candidate(
        record = record,
        supporting = listOf(
            Evidence.supporting(MatchSignal.HashExact(HashAlgorithm.SHA1), EvidenceStrength.DECISIVE, supporting),
        ),
        score = 100,
    )

    private val resolution = ArtifactResolution(
        observationId = observationId,
        state = ResolutionState.AMBIGUOUS,
        confidence = ConfidenceLevel.AMBIGUOUS,
        selected = null,
        candidates = listOf(candidate(chrono, "SHA1 matches"), candidate(mario, "SHA1 matches")),
        pipelineEvidence = emptyList(),
        hashesComputed = setOf(HashAlgorithm.SHA1),
        hashes = HashDigests.of(sha1),
        consultedSources = listOf(source.id),
        resolverVersion = "v1",
        tokenizerVersion = "v1",
        normalizerVersion = "v1",
    )

    private class FakeObservations(private val entries: List<ResolvedObservation>) : ObservationRepository {
        override suspend fun saveAll(entries: List<ResolvedObservation>): Outcome<Int> =
            Outcome.success(entries.size)

        override suspend fun findBySession(id: ScanSessionId): Outcome<List<ResolvedObservation>> =
            Outcome.success(entries)
    }

    private class FakeCorrections : CorrectionStore {
        val recorded = mutableListOf<IdentityCorrection>()
        var withdrawn = 0

        override suspend fun record(correction: IdentityCorrection): Outcome<IdentityCorrection> {
            recorded.replaceAll { existing ->
                if (existing.scope == correction.scope && existing.state == CorrectionState.ACTIVE) {
                    existing.copy(state = CorrectionState.SUPERSEDED, supersededBy = correction.id)
                } else {
                    existing
                }
            }
            recorded += correction
            return Outcome.success(correction)
        }

        override suspend fun withdraw(scope: CorrectionScope): Outcome<Unit> {
            withdrawn++
            recorded.replaceAll { existing ->
                if (existing.state == CorrectionState.ACTIVE) {
                    existing.copy(state = CorrectionState.WITHDRAWN)
                } else {
                    existing
                }
            }
            return Outcome.success(Unit)
        }

        override suspend fun history(scope: CorrectionScope): Outcome<List<IdentityCorrection>> =
            Outcome.success(recorded.reversed())

        override suspend fun active(): CorrectionSet =
            CorrectionSet(recorded.filter { it.state.appliesToResolution })
    }

    private fun useCase(
        corrections: FakeCorrections = FakeCorrections(),
        entries: List<ResolvedObservation> = listOf(ResolvedObservation(observation, resolution)),
    ): Pair<ReviewObservationUseCase, FakeCorrections> {
        var counter = 0
        val record = RecordCorrectionUseCase(
            corrections,
            Clock { 1_000L },
            IdGenerator { prefix -> "$prefix-${counter++}" },
        )
        return ReviewObservationUseCase(FakeObservations(entries), record) to corrections
    }

    @Test
    fun `a subject carries every candidate with the evidence for and against it`() = runTest {
        val (review, _) = useCase()

        val subject = assertIs<Outcome.Success<ReviewSubject>>(review.subject(sessionId, observationId)).value

        assertEquals(2, subject.candidates.size, "Section 44: the overruled candidate is not hidden")
        assertTrue(
            subject.candidates.all { it.supporting.isNotEmpty() },
            "A user choosing between identities is assessing evidence and cannot do it from names",
        )
        assertEquals("mystery.sfc", subject.filename)
        assertFalse(subject.corrected)
    }

    @Test
    fun `choosing a candidate records a correction naming its release`() = runTest {
        val (review, corrections) = useCase()

        val recorded = assertIs<Outcome.Success<IdentityCorrection>>(
            review.correctToCandidate(sessionId, observationId, mario.id, reason = "I dumped this myself"),
        ).value

        assertEquals(
            CorrectedIdentity.IsRelease(EntityPromoter.releaseId(mario.canonicalIdentityKey)),
            recorded.corrected,
            "The release must be derived the same way the entity graph derives it",
        )
        assertEquals("I dumped this myself", recorded.reason)
        assertEquals(1, corrections.recorded.size)
    }

    @Test
    fun `an identity that is not a candidate cannot be recorded against the file`() = runTest {
        // Otherwise a stale screen could attach an arbitrary release to bytes
        // nothing ever compared against it.
        val (review, corrections) = useCase()

        val outcome = review.correctToCandidate(sessionId, observationId, DumpRecordId("something-else"))

        assertIs<Outcome.Failure>(outcome)
        assertTrue(corrections.recorded.isEmpty())
    }

    @Test
    fun `rejecting records that none of the candidates is right`() = runTest {
        val (review, corrections) = useCase()

        val recorded = assertIs<Outcome.Success<IdentityCorrection>>(
            review.reject(sessionId, observationId, reason = "this is my own build"),
        ).value

        assertEquals(CorrectedIdentity.NotThis, recorded.corrected)
        assertEquals("this is my own build", corrections.recorded.single().reason)
    }

    @Test
    fun `the previous claim survives the correction`() = runTest {
        // Section 69: a correction preserves the previous claim.
        val selected = resolution.copy(
            state = ResolutionState.EXACT_HASH,
            selected = resolution.candidates.first(),
        )
        val (review, _) = useCase(entries = listOf(ResolvedObservation(observation, selected)))

        val recorded = assertIs<Outcome.Success<IdentityCorrection>>(
            review.correctToCandidate(sessionId, observationId, mario.id),
        ).value

        assertEquals(chrono.canonicalIdentityKey.describe(), recorded.previousIdentityDescription)
    }

    @Test
    fun `the subject shows the whole correction history, superseded entries included`() = runTest {
        val (review, _) = useCase()
        review.correctToCandidate(sessionId, observationId, mario.id, reason = "first")
        review.correctToCandidate(sessionId, observationId, chrono.id, reason = "second")

        val subject = assertIs<Outcome.Success<ReviewSubject>>(review.subject(sessionId, observationId)).value

        assertEquals(listOf("second", "first"), subject.history.map { it.correction.reason })
        assertEquals(CorrectionState.ACTIVE, subject.history.first().correction.state)
        assertEquals(CorrectionState.SUPERSEDED, subject.history.last().correction.state)
        assertTrue(subject.corrected)
        assertTrue(
            subject.history.first().describedAs.contains("chrono trigger", ignoreCase = true),
            "A correction is shown as the game it named, not as the identifier it stored",
        )
    }

    @Test
    fun `withdrawing leaves the record behind and stops it applying`() = runTest {
        val (review, corrections) = useCase()
        review.correctToCandidate(sessionId, observationId, mario.id)

        assertIs<Outcome.Success<*>>(review.withdraw(sessionId, observationId))

        assertEquals(1, corrections.recorded.size, "Section 70: withdrawing is not deleting")
        assertEquals(CorrectionState.WITHDRAWN, corrections.recorded.single().state)
        val subject = assertIs<Outcome.Success<ReviewSubject>>(review.subject(sessionId, observationId)).value
        assertFalse(subject.corrected)
    }

    @Test
    fun `a file with no cryptographic hash cannot be corrected durably`() = runTest {
        // A correction keyed on nothing would stop applying after the next
        // scan, which is worse than refusing to record it.
        val hashless = observation.copy(hashes = HashDigests.EMPTY)
        val (review, corrections) = useCase(
            entries = listOf(ResolvedObservation(hashless, resolution.copy(hashes = HashDigests.EMPTY))),
        )

        assertIs<Outcome.Failure>(review.correctToCandidate(sessionId, observationId, mario.id))
        assertTrue(corrections.recorded.isEmpty())
    }

    @Test
    fun `reviewing a file that is not part of the scan fails rather than inventing one`() = runTest {
        val (review, _) = useCase(entries = emptyList())

        assertIs<Outcome.Failure>(review.subject(sessionId, observationId))
    }
}
