package com.retrovault.domain.correction

import com.retrovault.domain.Fixtures
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.entity.EntityPromoter
import com.retrovault.domain.evidence.MatchSignal
import com.retrovault.domain.identity.ContainerKind
import com.retrovault.domain.identity.CorrectionId
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.policy.AutomationPolicy
import com.retrovault.domain.resolution.ArtifactResolution
import com.retrovault.domain.resolution.Candidate
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.IdentityBasis
import com.retrovault.domain.resolution.ResolutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Durable user corrections.
 *
 * Two rules pull against each other here and both must hold. DOMAIN_MODEL.md
 * section 37 invariant 13: user corrections outrank automatic suggestions for
 * that user's collection. TESTING_SPEC.md section 1: a wrong match presented as
 * certain is unacceptable. A correction therefore wins the *selection* and
 * never claims content verification.
 */
class CorrectionTest {

    private val sha1 = Fixtures.sha1("1111")
    private val md5 = Fixtures.md5("2222")
    private val crc = Fixtures.crc("aabbccdd")

    private val wrong = Fixtures.record("Chrono Trigger (USA)", hashes = Fixtures.digests(sha1))
    private val right = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(sha1))

    private fun observation(hashes: HashDigests = Fixtures.digests(sha1)) =
        Fixtures.observation("mystery.sfc", hashes = hashes)

    private fun resolvedAs(record: DumpRecord, state: ResolutionState = ResolutionState.EXACT_HASH) =
        ArtifactResolution.terminal(
            observationId = observation().id,
            state = state,
            candidates = listOf(Candidate(record = record, score = 100)),
            selected = Candidate(record = record, score = 100),
            resolverVersion = "test",
            tokenizerVersion = "test",
            normalizerVersion = "test",
        )

    private fun correction(
        corrected: CorrectedIdentity,
        scope: CorrectionScope = CorrectionScope(HashAlgorithm.SHA1, sha1.hex, size = 524_288),
        state: CorrectionState = CorrectionState.ACTIVE,
        reason: String? = null,
    ) = IdentityCorrection(
        id = CorrectionId("correction-1"),
        scope = scope,
        previousIdentityDescription = "chrono trigger [USA]",
        corrected = corrected,
        reason = reason,
        recordedAtEpochMillis = 1_700_000_000_000L,
        state = state,
    )

    // ------------------------------------------------------------------
    // Scope: durable means content-keyed
    // ------------------------------------------------------------------

    @Test
    fun `a correction is keyed by content, so it survives a rename`() {
        val before = Fixtures.observation("mystery.sfc", hashes = Fixtures.digests(sha1))
        val after = Fixtures.observation("Super Mario World (USA).sfc", hashes = Fixtures.digests(sha1))

        assertEquals(
            CorrectionScope.forObservation(before),
            CorrectionScope.forObservation(after),
            "The same bytes must get the same answer whatever the file is called",
        )
    }

    @Test
    fun `a correction cannot be keyed on crc32 alone`() {
        // CRC32 is a discriminator, not content proof (Constitution section
        // 148). A correction keyed on 32 bits would eventually attach a user's
        // assertion to bytes they never saw.
        val crcOnly = Fixtures.observation("mystery.sfc", hashes = Fixtures.digests(crc))

        assertNull(CorrectionScope.forObservation(crcOnly))
        assertNull(CorrectionScope.forHash(crc))
        assertNotNull(CorrectionScope.forHash(sha1))
    }

    @Test
    fun `an archive is corrected by its contained rom, not by the zip`() {
        val zipped = Fixtures.observation(
            "pack.zip",
            container = ContainerKind.ZIP,
            archiveEntries = listOf(Fixtures.zipEntry("game.sfc", hashes = Fixtures.digests(sha1))),
        )

        assertEquals(sha1.hex, CorrectionScope.forObservation(zipped)?.digest)
    }

    @Test
    fun `a correction made against md5 still applies once sha1 is known`() {
        val set = CorrectionSet(
            listOf(correction(CorrectedIdentity.NotThis, scope = CorrectionScope(HashAlgorithm.MD5, md5.hex))),
        )

        assertNotNull(set.forHashes(Fixtures.digests(md5, sha1)))
    }

    // ------------------------------------------------------------------
    // Applying: the user outranks the pipeline, without claiming verification
    // ------------------------------------------------------------------

    @Test
    fun `a correction overrides an exact hash match`() {
        // The strongest automatic evidence there is, overruled - because the
        // user is the authority over their own collection.
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { listOf(right) }

        assertEquals(ResolutionState.USER_CORRECTED, corrected.state)
        assertEquals(right.id, corrected.selected?.record?.id)
    }

    @Test
    fun `a correction is never presented as content verification`() {
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { listOf(right) }

        assertEquals(IdentityBasis.USER_ASSERTED, corrected.identityBasis)
        assertFalse(
            corrected.isVerified,
            "Nothing checked these bytes against the release the user named",
        )
    }

    @Test
    fun `the overruled candidate survives with its evidence`() {
        // Constitution section 44: preserve both claims rather than flattening
        // the disagreement.
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { listOf(right) }

        assertTrue(
            corrected.candidates.any { it.record.id == wrong.id },
            "The automatic answer must stay visible: ${corrected.candidates.map { it.record.id.value }}",
        )
    }

    @Test
    fun `the reason and the previous claim are carried into the explanation`() {
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(
                CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey)),
                reason = "I dumped this myself",
            ),
        ) { listOf(right) }

        val text = corrected.explanation.joinToString(" ") { it.description }
        assertTrue(text.contains("chrono trigger"), text)
        assertTrue(text.contains("I dumped this myself"), text)
    }

    @Test
    fun `a rejection selects nothing and says why`() {
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.NotThis),
        ) { emptyList() }

        assertEquals(ResolutionState.USER_REJECTED, corrected.state)
        assertNull(corrected.selected)
        assertEquals(IdentityBasis.NONE, corrected.identityBasis)
        assertTrue(
            corrected.candidates.single().contradicting.any { it.signal == MatchSignal.UserRejection },
        )
    }

    @Test
    fun `naming a release RetroVault can no longer find is still a rejection`() {
        // The dataset was removed. The user's disagreement does not evaporate
        // with it, and proposing the old answer again would be worse.
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { emptyList() }

        assertEquals(ResolutionState.USER_REJECTED, corrected.state)
        assertNull(corrected.selected)
    }

    @Test
    fun `a superseded or withdrawn correction changes nothing`() {
        val automatic = resolvedAs(wrong)

        // Built through the transitions rather than constructed directly: the
        // invariant refuses a superseded correction with no successor, which is
        // itself the point.
        listOf(
            correction(CorrectedIdentity.NotThis).supersededBy(CorrectionId("later")),
            correction(CorrectedIdentity.NotThis).withdrawn(),
        ).forEach { stale ->
            assertEquals(
                automatic,
                CorrectionApplier.apply(automatic, stale) { emptyList() },
                stale.state.name,
            )
        }
    }

    @Test
    fun `applying a correction is idempotent`() {
        val correction =
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey)))
        val once = CorrectionApplier.apply(resolvedAs(wrong), correction) { listOf(right) }
        val twice = CorrectionApplier.apply(once, correction) { listOf(right) }

        assertEquals(once.state, twice.state)
        assertEquals(once.selected?.record?.id, twice.selected?.record?.id)
        assertEquals(once.candidates.size, twice.candidates.size)
    }

    // ------------------------------------------------------------------
    // Automation
    // ------------------------------------------------------------------

    @Test
    fun `a correction the content does not corroborate still requires review`() {
        // Identity and authorisation are separate questions. The user has said
        // what this is; nothing has shown that the bytes agree, so renaming it
        // is still a decision to be confirmed rather than one already made.
        val unverified = Fixtures.record(
            "Super Mario World (USA)",
            hashes = Fixtures.digests(Fixtures.sha1("9999")),
        )
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(unverified.canonicalIdentityKey))),
        ) { listOf(unverified) }

        assertEquals(ResolutionState.USER_CORRECTED, corrected.state)
        assertEquals(AutomationDecision.REQUIRES_REVIEW, AutomationPolicy().decide(corrected))
    }

    @Test
    fun `a correction the content corroborates may be renamed automatically`() {
        // Here the rename rests on the measurement - a cryptographic hash of
        // the bytes matching the digest catalogued for the release the user
        // named - and the correction merely pointed at it.
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong).copy(hashes = Fixtures.digests(sha1)),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { listOf(right) }

        assertTrue(corrected.selected!!.hasIndependentContentAgreement)
        assertEquals(AutomationDecision.AUTOMATIC, AutomationPolicy().decide(corrected))
    }

    @Test
    fun `crc32 agreement alone does not authorise a corrected rename`() {
        // CRC32 is a discriminator, not proof (Constitution section 148).
        // Treating it as verification would let a 32-bit collision authorise a
        // rename the user never checked.
        val crcOnlyRecord = Fixtures.record("Super Mario World (USA)", hashes = Fixtures.digests(crc))
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong).copy(hashes = Fixtures.digests(crc)),
            correction(
                CorrectedIdentity.IsRelease(EntityPromoter.releaseId(crcOnlyRecord.canonicalIdentityKey)),
            ),
        ) { listOf(crcOnlyRecord) }

        assertFalse(corrected.selected!!.hasIndependentContentAgreement)
        assertEquals(AutomationDecision.REQUIRES_REVIEW, AutomationPolicy().decide(corrected))
    }

    @Test
    fun `content corroboration never turns a user assertion into verified content`() {
        // Even when the bytes agree, the *identity* came from a person. The
        // basis says so, and only the evidence says the content agrees.
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong).copy(hashes = Fixtures.digests(sha1)),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { listOf(right) }

        assertEquals(IdentityBasis.USER_ASSERTED, corrected.identityBasis)
        assertFalse(corrected.isVerified)
    }

    @Test
    fun `content agreement survives a resolution being read back from storage`() {
        // A persisted signal comes back as MatchSignal.Recorded, carrying its id
        // but not its Kotlin type - and the rename planner only ever sees
        // persisted resolutions. If authorisation were decided by pattern
        // matching on the live type, every corroborated correction would
        // silently drop back to review after a round trip.
        val corrected = CorrectionApplier.apply(
            resolvedAs(wrong).copy(hashes = Fixtures.digests(sha1)),
            correction(CorrectedIdentity.IsRelease(EntityPromoter.releaseId(right.canonicalIdentityKey))),
        ) { listOf(right) }

        val reloaded = corrected.copy(
            selected = corrected.selected!!.copy(
                supporting = corrected.selected!!.supporting.map { evidence ->
                    evidence.copy(
                        signal = MatchSignal.Recorded(
                            evidence.signal.id,
                            evidence.signal.excludesIdentity,
                        ),
                    )
                },
            ),
        )

        assertTrue(reloaded.selected!!.hasIndependentContentAgreement)
        assertEquals(AutomationDecision.AUTOMATIC, AutomationPolicy().decide(reloaded))
    }

    @Test
    fun `a rejected identity is never renamed`() {
        val rejected = CorrectionApplier.apply(
            resolvedAs(wrong),
            correction(CorrectedIdentity.NotThis),
        ) { emptyList() }

        assertEquals(AutomationDecision.FORBIDDEN, AutomationPolicy().decide(rejected))
    }

    @Test
    fun `correction states keep the resolution invariants`() {
        assertTrue(ResolutionState.USER_CORRECTED.canCarrySelection)
        assertFalse(ResolutionState.USER_REJECTED.canCarrySelection)
        assertEquals(ConfidenceLevel.UNKNOWN, ConfidenceLevel.forState(ResolutionState.USER_REJECTED))
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Test
    fun `superseding preserves the earlier correction rather than rewriting it`() {
        // Constitution section 69: never silently rewrite history.
        val first = correction(CorrectedIdentity.NotThis)

        val superseded = first.supersededBy(CorrectionId("correction-2"))

        assertEquals(CorrectionState.SUPERSEDED, superseded.state)
        assertEquals(CorrectionId("correction-2"), superseded.supersededBy)
        assertEquals(first.reason, superseded.reason)
        assertEquals(first.previousIdentityDescription, superseded.previousIdentityDescription)
    }

    @Test
    fun `a superseded correction must name its successor`() {
        val inconsistent = runCatching {
            correction(CorrectedIdentity.NotThis, state = CorrectionState.SUPERSEDED)
        }

        assertTrue(inconsistent.isFailure)
    }

    @Test
    fun `only active corrections reach a scan`() {
        val set = CorrectionSet(
            listOf(
                correction(CorrectedIdentity.NotThis).withdrawn(),
                correction(
                    CorrectedIdentity.NotThis,
                    scope = CorrectionScope(HashAlgorithm.MD5, md5.hex),
                ),
            ),
        )

        assertEquals(1, set.size)
        assertNull(set.forHashes(Fixtures.digests(sha1)))
        assertNotNull(set.forHashes(Fixtures.digests(md5)))
    }
}
