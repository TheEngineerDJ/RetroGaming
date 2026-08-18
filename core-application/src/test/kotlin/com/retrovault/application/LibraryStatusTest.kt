package com.retrovault.application

import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.resolution.ResolutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The piles a user sees their library sorted into.
 *
 * The grouping is what the whole home screen is built on, so it is checked
 * here rather than trusted: a state that quietly fell into the wrong pile would
 * hide files the user needs to act on, or claim confidence RetroVault does not
 * have.
 */
class LibraryStatusTest {

    private fun statusOf(state: ResolutionState, decision: AutomationDecision) =
        LibraryStatus.of(state, decision)

    @Test
    fun `a file the planner would rename unasked is shown as identified`() {
        // The status and the action must agree: "Identified" means exactly
        // "the planner would rename this without asking".
        listOf(ResolutionState.EXACT_HASH, ResolutionState.EXACT_MULTI_HASH).forEach { state ->
            assertEquals(
                LibraryStatus.IDENTIFIED,
                statusOf(state, AutomationDecision.AUTOMATIC),
                state.name,
            )
        }
    }

    @Test
    fun `a match the policy holds back is shown as needing review, however strong it is`() {
        // A headered dump matched its payload exactly and still needs review.
        // Showing it as identified would tell the user RetroVault is about to
        // act when it is not.
        assertEquals(
            LibraryStatus.NEEDS_REVIEW,
            statusOf(ResolutionState.MODIFIED_MATCH, AutomationDecision.REQUIRES_REVIEW),
        )
        assertEquals(
            LibraryStatus.NEEDS_REVIEW,
            statusOf(ResolutionState.EXACT_HASH, AutomationDecision.REQUIRES_REVIEW),
        )
    }

    @Test
    fun `an inferred identity is never shown as identified unless the policy agrees`() {
        // Section 6: an inferred identity is not a verified one, and the
        // default policy holds fuzzy and metadata matches back.
        assertEquals(
            LibraryStatus.NEEDS_REVIEW,
            statusOf(ResolutionState.FUZZY_MATCH, AutomationDecision.REQUIRES_REVIEW),
        )
        assertEquals(
            LibraryStatus.NEEDS_REVIEW,
            statusOf(ResolutionState.STRONG_METADATA_MATCH, AutomationDecision.REQUIRES_REVIEW),
        )
    }

    @Test
    fun `ambiguity and conflict are reviewable, not failures`() {
        // The user may pick a candidate, which is why they are shown at all.
        assertEquals(
            LibraryStatus.NEEDS_REVIEW,
            statusOf(ResolutionState.AMBIGUOUS, AutomationDecision.REQUIRES_REVIEW),
        )
        assertEquals(
            LibraryStatus.NEEDS_REVIEW,
            statusOf(ResolutionState.CONFLICT, AutomationDecision.REQUIRES_REVIEW),
        )
    }

    @Test
    fun `not listed and no dataset stay apart`() {
        // Section 169 and section 174: the two call for different action - one
        // may mean a bad dump, the other means the right dataset is missing.
        assertEquals(LibraryStatus.UNMATCHED, statusOf(ResolutionState.NO_MATCH, AutomationDecision.FORBIDDEN))
        assertEquals(
            LibraryStatus.OUT_OF_SCOPE,
            statusOf(ResolutionState.OUT_OF_CATALOGUE_SCOPE, AutomationDecision.FORBIDDEN),
        )
    }

    @Test
    fun `a user rejection is its own outcome, not an error`() {
        assertEquals(LibraryStatus.REJECTED, statusOf(ResolutionState.USER_REJECTED, AutomationDecision.FORBIDDEN))
    }

    @Test
    fun `every resolution state has a pile`() {
        // Exhaustive by construction, but a new state added later must not
        // silently land in a default bucket.
        ResolutionState.entries.forEach { state ->
            AutomationDecision.entries.forEach { decision ->
                statusOf(state, decision)
            }
        }
    }

    @Test
    fun `no status says verified`() {
        // Section 6: the labels are what a user actually reads, so the promise
        // has to hold in the wording and not only in the model.
        LibraryStatus.entries.forEach { status ->
            assertFalse(
                status.label.contains("verified", ignoreCase = true),
                "${status.name} label claims verification: ${status.label}",
            )
        }
    }

    @Test
    fun `every status explains itself`() {
        LibraryStatus.entries.forEach { status ->
            assertTrue(status.label.isNotBlank(), status.name)
            assertTrue(status.meaning.length > 20, "${status.name} needs a usable explanation")
        }
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    @Test
    fun `counts total what was classified and keep errors apart`() {
        // A file that failed to scan has no resolution, so it is not in any
        // pile - claiming it "cannot be identified" would say RetroVault
        // looked, and it did not.
        val counts = LibraryStatusCounts()
            .plus(LibraryStatus.IDENTIFIED)
            .plus(LibraryStatus.IDENTIFIED)
            .plus(LibraryStatus.NEEDS_REVIEW)
            .plusError()

        assertEquals(2, counts[LibraryStatus.IDENTIFIED])
        assertEquals(1, counts[LibraryStatus.NEEDS_REVIEW])
        assertEquals(0, counts[LibraryStatus.UNMATCHED])
        assertEquals(3, counts.total, "Errors are not one of the piles")
        assertEquals(1, counts.errors)
    }

    @Test
    fun `only statuses with files are offered as filters`() {
        val counts = LibraryStatusCounts()
            .plus(LibraryStatus.IDENTIFIED)
            .plus(LibraryStatus.UNMATCHED)

        assertEquals(listOf(LibraryStatus.IDENTIFIED, LibraryStatus.UNMATCHED), counts.present)
        assertTrue(LibraryStatusCounts().present.isEmpty())
    }

    @Test
    fun `attention counts only what the user can act on`() {
        val counts = LibraryStatusCounts()
            .plus(LibraryStatus.IDENTIFIED)
            .plus(LibraryStatus.NEEDS_REVIEW)
            .plus(LibraryStatus.UNMATCHED)
            .plus(LibraryStatus.OUT_OF_SCOPE)
            .plus(LibraryStatus.REJECTED)

        // Identified needs nothing. A rejection is a decision the user already
        // made, so nagging about it would be arguing with them.
        assertEquals(3, counts.needingAttention)
    }
}
