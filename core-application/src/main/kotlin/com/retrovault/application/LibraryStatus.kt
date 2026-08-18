package com.retrovault.application

import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.resolution.ResolutionState

/**
 * What a scanned file needs from the user, in words.
 *
 * The resolution states are precise and there are twelve of them; a person
 * looking at their library needs to know which pile a file is in and what to do
 * about it. This is that grouping - and it groups by *what is required*, not by
 * how confident RetroVault is, because those are different questions and the
 * user acts on the first.
 *
 * Nothing here softens an outcome. Constitution section 169: a file the
 * datasets do not list is a statement about the datasets, and section 6: an
 * inferred identity is never presented as a verified one. Both survive into the
 * wording, because the wording is what the user actually reads.
 */
enum class LibraryStatus(
    /** Short enough for a filter chip. */
    val label: String,
    /** What it means, in a sentence a person can act on. */
    val meaning: String,
) {
    IDENTIFIED(
        label = "Identified",
        meaning = "RetroVault matched this against your datasets and is willing to rename it without asking.",
    ),

    NEEDS_REVIEW(
        label = "Needs review",
        meaning = "RetroVault has an answer but will not act on it until you agree. " +
            "Open the file to see what it found and why.",
    ),

    UNMATCHED(
        label = "Not listed",
        meaning = "Your datasets cover this kind of file and do not list this one. " +
            "That is a fact about the datasets, not proof the file is unknown.",
    ),

    OUT_OF_SCOPE(
        label = "No dataset for it",
        meaning = "No dataset you have imported describes this kind of media, so there was nothing " +
            "to compare it against. Importing one that covers this platform may identify it.",
    ),

    REJECTED(
        label = "You said no",
        meaning = "You told RetroVault its answer was wrong, so this file is left alone.",
    ),

    UNSUPPORTED(
        label = "Cannot identify",
        meaning = "This file exposes no single piece of content RetroVault can identify - " +
            "an archive holding several games, for example.",
    ),
    ;

    /** Whether opening this file could let the user change the outcome. */
    val isActionable: Boolean
        get() = this == NEEDS_REVIEW || this == UNMATCHED || this == OUT_OF_SCOPE

    companion object {
        /**
         * The pile one resolution belongs in.
         *
         * Takes the automation decision rather than re-deriving it, so the
         * status a user sees and the action the planner will take cannot
         * disagree. A file shown as "Identified" is exactly a file the planner
         * would rename unasked.
         */
        fun of(state: ResolutionState, decision: AutomationDecision): LibraryStatus = when (state) {
            ResolutionState.EXACT_HASH,
            ResolutionState.EXACT_MULTI_HASH,
            ResolutionState.MODIFIED_MATCH,
            ResolutionState.STRUCTURAL_MATCH,
            ResolutionState.STRONG_METADATA_MATCH,
            ResolutionState.FUZZY_MATCH,
            ResolutionState.USER_CORRECTED,
            ->
                if (decision == AutomationDecision.AUTOMATIC) IDENTIFIED else NEEDS_REVIEW

            // Reviewable rather than refused: the user may pick a candidate,
            // which is the whole point of showing them.
            ResolutionState.AMBIGUOUS, ResolutionState.CONFLICT -> NEEDS_REVIEW

            ResolutionState.NO_MATCH -> UNMATCHED
            ResolutionState.OUT_OF_CATALOGUE_SCOPE -> OUT_OF_SCOPE
            ResolutionState.USER_REJECTED -> REJECTED
            ResolutionState.UNSUPPORTED -> UNSUPPORTED
        }
    }
}

/**
 * How many files are in each pile, and how many could not be read at all.
 *
 * [errors] is separate from the statuses because a file that failed to scan has
 * no resolution to classify - RetroVault never got far enough to have an
 * opinion. Folding it into "cannot identify" would claim it did.
 */
data class LibraryStatusCounts(
    val byStatus: Map<LibraryStatus, Int> = emptyMap(),
    val errors: Int = 0,
) {
    operator fun get(status: LibraryStatus): Int = byStatus[status] ?: 0

    val total: Int get() = byStatus.values.sum()

    /** Statuses with at least one file, in enum order, so chips stay stable. */
    val present: List<LibraryStatus> get() = LibraryStatus.entries.filter { this[it] > 0 }

    /** Files the user could do something about right now. */
    val needingAttention: Int get() = LibraryStatus.entries.filter { it.isActionable }.sumOf { this[it] }

    fun plus(status: LibraryStatus): LibraryStatusCounts =
        copy(byStatus = byStatus + (status to this[status] + 1))

    fun plusError(): LibraryStatusCounts = copy(errors = errors + 1)
}
