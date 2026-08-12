package com.retrovault.domain.naming

/**
 * A title reduced to a comparison key.
 *
 * The normalized form is *derived data* (Constitution section 72). It exists
 * only to make lookup deterministic; it never replaces the observed title,
 * which remains immutable evidence.
 */
@JvmInline
value class NormalizedTitle(val key: String) {
    val isBlank: Boolean get() = key.isBlank()

    /** Whitespace-separated comparison tokens. */
    fun tokens(): List<String> = if (key.isBlank()) emptyList() else key.split(' ')
}

/**
 * Deterministic, idempotent title normalization.
 *
 * TESTING_SPEC.md section 9 requires `normalize(normalize(x)) == normalize(x)`.
 * Every transformation here is therefore a projection onto a fixed alphabet:
 * lowercase ASCII letters, digits and single spaces.
 *
 * Normalization is *loss aware* (Constitution section 175): the caller keeps
 * the original title, and nothing here is used to generate output filenames.
 */
object TitleNormalizer {
    const val VERSION: String = "title-normalizer-v1"

    /** Trailing articles as written by No-Intro, e.g. `Legend of Zelda, The`. */
    private val trailingArticles = listOf("the", "a", "an", "les", "la", "le", "der", "die", "das")

    private val romanNumerals = mapOf(
        "i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5,
        "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10,
    )

    /**
     * Reduces [title] to its comparison key.
     *
     * The steps are ordered so that the output is a fixed point of the whole
     * function: after one pass the text contains no diacritics, no punctuation,
     * no leading/trailing article and no repeated spaces, so a second pass
     * cannot change it.
     */
    fun normalize(title: String): NormalizedTitle {
        val folded = foldDiacritics(title)
        val withoutTrailingArticle = removeTrailingArticleClause(folded)
        val expanded = expandSymbols(withoutTrailingArticle)
        // Apostrophes are deleted rather than turned into a separator, so that
        // "Link's Awakening" and "Links Awakening" produce the same key.
        val deapostrophized = expanded.replace("'", "").replace("’", "")
        val alphanumeric = buildString(deapostrophized.length) {
            for (character in deapostrophized) {
                // Non-Latin scripts are kept rather than stripped: dropping them
                // would collapse every Japanese title to an empty key and make
                // unrelated titles look identical.
                when {
                    character.isLetterOrDigit() -> append(character.lowercaseChar())
                    else -> append(' ')
                }
            }
        }
        val words = alphanumeric.split(' ').filter { it.isNotEmpty() }
        val withoutArticle = dropArticle(words)
        val canonicalNumbers = withoutArticle.map { word -> romanNumerals[word]?.toString() ?: word }
        return NormalizedTitle(canonicalNumbers.joinToString(" "))
    }

    /**
     * Candidate comparison keys for one observed title, best guess first.
     *
     * Tag stripping cannot be done safely in a single pass: removing a trailing
     * `-token` rescues `Red Hot Rumble-memorypsp` but would butcher
     * `Spider-Man`. Producing both forms and letting the caller score each means
     * an over-eager strip can only ever fail to help - it can never cause a
     * wrong match, because the unstripped form is always still in the list.
     *
     * The tokenizer already removes *recognised* noise suffixes. This is the
     * generic case, for the site watermarks and scene tags nobody has enumerated.
     */
    fun comparisonVariants(title: String): List<NormalizedTitle> {
        val withoutSite = stripSitePrefix(title)
        val forms = LinkedHashSet<String>()
        forms += withoutSite
        strippedTrailingTag(withoutSite)?.let { forms += it }
        return forms.map(::normalize).filter { !it.isBlank }.distinct()
    }

    /**
     * Drops a trailing `-tag`, the usual shape of a scene group or watermark.
     *
     * Returns `null` when the segment does not look like a tag: too long, or
     * spaced away from the hyphen as in `Ratchet - Deadlocked`, where the hyphen
     * is punctuation rather than a separator.
     */
    private fun strippedTrailingTag(title: String): String? {
        val hyphen = title.lastIndexOf('-')
        if (hyphen <= 0 || hyphen == title.lastIndex) return null
        val tag = title.substring(hyphen + 1)
        if (tag.isBlank() || tag.length > MAX_TRAILING_TAG_LENGTH) return null
        if (tag.any { it.isWhitespace() }) return null
        if (title[hyphen - 1].isWhitespace()) return null
        return title.substring(0, hyphen)
    }

    /** Removes a `www.site.com -` style watermark from the front of a name. */
    private fun stripSitePrefix(title: String): String =
        sitePrefix.replace(title, "").ifBlank { title }

    private val sitePrefix = Regex(
        """^\s*(www\.)?[a-z0-9-]+\.(com|net|org|to|me|io|ru|eu)\s*[-_ ]+""",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_TRAILING_TAG_LENGTH = 20

    /**
     * Moves an article to the front or drops it entirely.
     *
     * `Legend of Zelda, The` and `The Legend of Zelda` must produce the same
     * key, otherwise a DAT written in one convention can never match a
     * filename written in the other. Dropping is safe here because the article
     * is never the distinguishing element between two catalogued releases.
     */
    private fun dropArticle(words: List<String>): List<String> {
        if (words.size <= 1) return words
        val head = words.first()
        val tail = words.last()
        return when {
            head in trailingArticles -> words.drop(1)
            tail in trailingArticles -> words.dropLast(1)
            else -> words
        }
    }

    /**
     * Removes the No-Intro trailing-article clause.
     *
     * `Legend of Zelda, The - Link's Awakening` puts the article in the middle
     * of the string, where a first-or-last word check cannot see it. Matching
     * on the comma is precise: it never touches an article that is genuinely
     * part of a title, such as `Legend of the Mystical Ninja`.
     */
    private fun removeTrailingArticleClause(text: String): String =
        trailingArticleClause.replace(text) { match -> match.groupValues[2] }

    private val trailingArticleClause = Regex(
        """,\s*(the|a|an|les|la|le|der|die|das)\b(\s*[-(]|\s*$)""",
        RegexOption.IGNORE_CASE,
    )

    private fun expandSymbols(text: String): String =
        text.replace("&", " and ")
            .replace("+", " plus ")
            .replace("@", " at ")

    /**
     * Maps the Latin-1/Latin-A range onto ASCII.
     *
     * `java.text.Normalizer` is available on both the JVM and Android, but the
     * domain deliberately avoids platform text services so that this rule stays
     * verifiable and identical everywhere.
     */
    internal fun foldDiacritics(text: String): String = buildString(text.length) {
        for (character in text) {
            val index = DIACRITIC_SOURCE.indexOf(character)
            if (index >= 0) append(DIACRITIC_TARGET[index]) else append(character)
        }
    }

    internal const val DIACRITIC_SOURCE =
        "ÀÁÂÃÄÅÇÈÉÊË" +
            "ÌÍÎÏÑÒÓÔÕÖÙ" +
            "ÚÛÜÝàáâãäåç" +
            "èéêëìíîïñòó" +
            "ôõöùúûüýÿ"

    internal const val DIACRITIC_TARGET =
        "AAAAAACEEEE" +
            "IIIINOOOOOU" +
            "UUUYaaaaaac" +
            "eeeeiiiinoo" +
            "ooouuuuyy"
}
