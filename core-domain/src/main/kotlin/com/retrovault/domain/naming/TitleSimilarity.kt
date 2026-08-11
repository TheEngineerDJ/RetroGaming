package com.retrovault.domain.naming

/** The outcome of comparing two normalized titles. */
sealed interface TitleComparison {
    /** The comparison keys are identical. Still only filename evidence. */
    data object Exact : TitleComparison

    /** Close enough to propose as a candidate. [score] is 0..100. */
    data class Similar(val score: Int) : TitleComparison

    /**
     * The titles actively disagree on something that distinguishes releases.
     * A conflicting comparison must never produce a candidate.
     */
    data class Conflicting(val reason: String) : TitleComparison

    /** Not similar enough to say anything. */
    data object Unrelated : TitleComparison
}

/**
 * Deterministic, bounded, explainable title comparison.
 *
 * ROM_INTELLIGENCE.md section 7 requires fuzzy matching to be deterministic,
 * explainable, bounded, confidence-scored and reversible. TESTING_SPEC.md
 * section 1 states plainly that a false positive is more serious than a missed
 * match, which is why sequence numbers are treated as hard conflicts rather
 * than as small textual differences: "Super Mario Bros." and
 * "Super Mario Bros. 2" are 90% textually similar and are different games.
 */
object TitleSimilarity {
    const val VERSION: String = "title-similarity-v1"

    /** Minimum Dice score, in percent, for a pair to be proposed at all. */
    const val SIMILARITY_THRESHOLD: Int = 85

    /**
     * Minimum lead, in percent, that the best candidate must have over the
     * runner-up before it can be treated as a single candidate rather than an
     * ambiguity.
     */
    const val AMBIGUITY_MARGIN: Int = 5

    private val numericToken = Regex("""^\d+$""")

    fun compare(left: NormalizedTitle, right: NormalizedTitle): TitleComparison {
        if (left.isBlank || right.isBlank) return TitleComparison.Unrelated
        if (left.key == right.key) return TitleComparison.Exact

        val leftTokens = left.tokens()
        val rightTokens = right.tokens()

        val leftNumbers = leftTokens.filter { numericToken.matches(it) }.sorted()
        val rightNumbers = rightTokens.filter { numericToken.matches(it) }.sorted()
        if (leftNumbers != rightNumbers) {
            return TitleComparison.Conflicting(
                "sequence or numbering differs (${describe(leftNumbers)} vs ${describe(rightNumbers)})",
            )
        }

        val score = diceScore(leftTokens.toSet(), rightTokens.toSet())
        return if (score >= SIMILARITY_THRESHOLD) TitleComparison.Similar(score) else TitleComparison.Unrelated
    }

    /** Dice coefficient over token sets, expressed as an integer percentage. */
    private fun diceScore(left: Set<String>, right: Set<String>): Int {
        if (left.isEmpty() || right.isEmpty()) return 0
        val shared = left.count { it in right }
        return (2 * shared * 100) / (left.size + right.size)
    }

    private fun describe(numbers: List<String>): String =
        if (numbers.isEmpty()) "none" else numbers.joinToString(",")
}
