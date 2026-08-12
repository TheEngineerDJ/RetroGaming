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

        // Two measures, because they fail in different places. Token overlap
        // ignores word order but cannot see inside a word, so it scores a
        // one-character typo at zero. Edit distance catches the typo but
        // punishes reordering. Taking the better of the two lets either carry a
        // match, which is the behaviour the ROMRenamer prototype settled on.
        val score = maxOf(
            diceScore(leftTokens.toSet(), rightTokens.toSet()),
            editDistanceScore(left.key, right.key),
        )
        return if (score >= SIMILARITY_THRESHOLD) TitleComparison.Similar(score) else TitleComparison.Unrelated
    }

    /** Dice coefficient over token sets, expressed as an integer percentage. */
    private fun diceScore(left: Set<String>, right: Set<String>): Int {
        if (left.isEmpty() || right.isEmpty()) return 0
        val shared = left.count { it in right }
        return (2 * shared * 100) / (left.size + right.size)
    }

    /** Levenshtein distance as an integer percentage similarity. */
    private fun editDistanceScore(left: String, right: String): Int {
        val longest = maxOf(left.length, right.length)
        if (longest == 0) return 0
        // Only a near-match can reach the threshold, so the distance search is
        // bounded by what would still qualify. Beyond that the exact distance
        // does not matter and the computation stops early.
        val budget = ((100 - SIMILARITY_THRESHOLD) * longest) / 100
        val distance = levenshtein(left, right, budget)
        if (distance > budget) return 0
        return ((longest - distance) * 100) / longest
    }

    /**
     * Edit distance with an early exit.
     *
     * Returns `maxDistance + 1` as soon as every cell in a row exceeds the
     * budget: no completion of the matrix can come back under it. One file is
     * scored against many candidate titles, and this bound is what keeps the
     * fallback affordable (Constitution section 249).
     */
    internal fun levenshtein(left: String, right: String, maxDistance: Int): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        if (kotlin.math.abs(left.length - right.length) > maxDistance) return maxDistance + 1

        // Iterate over the shorter string so the rows stay small.
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left

        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)

        for (row in 1..longer.length) {
            current[0] = row
            var rowMinimum = current[0]
            for (column in 1..shorter.length) {
                val substitution =
                    previous[column - 1] + if (shorter[column - 1] == longer[row - 1]) 0 else 1
                current[column] = minOf(current[column - 1] + 1, previous[column] + 1, substitution)
                if (current[column] < rowMinimum) rowMinimum = current[column]
            }
            if (rowMinimum > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.length]
    }

    private fun describe(numbers: List<String>): String =
        if (numbers.isEmpty()) "none" else numbers.joinToString(",")
}
