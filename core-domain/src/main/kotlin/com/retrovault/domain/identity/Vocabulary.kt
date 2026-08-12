package com.retrovault.domain.identity

/**
 * Controlled vocabularies.
 *
 * Constitution section 238: controlled vocabularies must be versioned,
 * documented and extensible, and "unknown"/"other" must remain valid escape
 * states. Every vocabulary here therefore carries a version string that is
 * recorded alongside any result it influenced.
 */
object VocabularyVersions {
    const val REGION = "region-v1"
    const val LANGUAGE = "language-v1"
    const val TOKEN = "token-v1"
}

/**
 * Region is first-class identity data, not a display filter
 * (Constitution section 22 and section 117).
 *
 * [code] is the canonical internal code; [displayToken] is what a naming
 * profile writes into a filename.
 */
data class RegionCode(val code: String) {
    init {
        require(code.isNotBlank()) { "Region code must not be blank" }
    }

    val displayToken: String get() = RegionVocabulary.displayTokenFor(this)

    companion object {
        val UNKNOWN = RegionCode("UNKNOWN")
    }
}

object RegionVocabulary {
    const val VERSION: String = VocabularyVersions.REGION

    /**
     * Canonical code to the exact token a No-Intro style filename uses.
     * Ordering of this map is also the canonical ordering used when several
     * regions apply to one release, so filename generation stays deterministic.
     */
    private val canonical: Map<String, String> = linkedMapOf(
        "WORLD" to "World",
        "USA" to "USA",
        "EUROPE" to "Europe",
        "JAPAN" to "Japan",
        "ASIA" to "Asia",
        "AUSTRALIA" to "Australia",
        "BRAZIL" to "Brazil",
        "CANADA" to "Canada",
        "CHINA" to "China",
        "FRANCE" to "France",
        "GERMANY" to "Germany",
        "HONG_KONG" to "Hong Kong",
        "ITALY" to "Italy",
        "KOREA" to "Korea",
        "NETHERLANDS" to "Netherlands",
        "RUSSIA" to "Russia",
        "SPAIN" to "Spain",
        "SWEDEN" to "Sweden",
        "TAIWAN" to "Taiwan",
        "UNITED_KINGDOM" to "UK",
    )

    private val aliases: Map<String, String> = buildMap {
        canonical.forEach { (code, token) ->
            put(code.lowercase(), code)
            put(token.lowercase(), code)
        }
        put("us", "USA")
        put("u", "USA")
        put("usa, europe", "USA")
        put("eur", "EUROPE")
        put("eu", "EUROPE")
        put("e", "EUROPE")
        put("jpn", "JAPAN")
        put("jap", "JAPAN")
        put("jp", "JAPAN")
        put("j", "JAPAN")
        put("uk", "UNITED_KINGDOM")
        put("gb", "UNITED_KINGDOM")
        put("aus", "AUSTRALIA")
        put("kor", "KOREA")
        put("ger", "GERMANY")
        put("fra", "FRANCE")
        put("spa", "SPAIN")
        put("ita", "ITALY")
        put("ned", "NETHERLANDS")
        put("hk", "HONG_KONG")
        put("w", "WORLD")
    }

    /** Canonical ordering index; unknown regions sort last but stay stable. */
    private val order: Map<String, Int> =
        canonical.keys.withIndex().associate { (index, code) -> code to index }

    /**
     * Parses one filename or DAT token into a region.
     *
     * Returns `null` rather than guessing: an unrecognised token is not a
     * region, and must remain available as unknown evidence
     * (Constitution section 174).
     */
    fun parse(token: String): RegionCode? {
        val key = token.trim().lowercase()
        if (key.isEmpty()) return null
        aliases[key]?.let { return RegionCode(it) }
        return null
    }

    /**
     * Parses a compound region token such as `USA, Europe` into its parts.
     * Every part must be recognised; otherwise the whole token is rejected so
     * a partially understood region never becomes a confident identity signal.
     */
    fun parseCompound(token: String): List<RegionCode>? {
        val parts = token.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val parsed = parts.map { parse(it) ?: return null }
        return parsed.distinct()
    }

    fun displayTokenFor(region: RegionCode): String = canonical[region.code] ?: region.code

    fun sort(regions: Collection<RegionCode>): List<RegionCode> =
        regions.distinct().sortedWith(
            compareBy({ order[it.code] ?: Int.MAX_VALUE }, { it.code }),
        )
}

/** ISO-639-1 style language token. Language is separate from region (Constitution section 118). */
data class LanguageCode(val code: String) {
    init {
        require(code.isNotBlank()) { "Language code must not be blank" }
    }

    /** No-Intro writes languages capitalised, e.g. `En`, `Fr`, `Zh-Hant`. */
    val displayToken: String
        get() = code.split('-').joinToString("-") { part ->
            part.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
}

object LanguageVocabulary {
    const val VERSION: String = VocabularyVersions.LANGUAGE

    private val known: Set<String> = setOf(
        "en", "ja", "fr", "de", "es", "it", "nl", "pt", "sv", "no", "da", "fi",
        "zh", "ko", "ru", "pl", "cs", "hu", "el", "tr", "ar", "he", "ca", "gl",
    )

    /**
     * Parses a language list token such as `En,Fr,De`.
     * Returns `null` when any part is unrecognised, for the same reason as
     * [RegionVocabulary.parseCompound].
     */
    fun parseList(token: String): List<LanguageCode>? {
        val parts = token.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val parsed = parts.map { part ->
            val base = part.substringBefore('-').lowercase()
            if (base !in known) return null
            LanguageCode(part.lowercase())
        }
        return parsed.distinct()
    }
}

/**
 * Preservation status of a catalogued dump.
 *
 * Constitution section 199: an imperfect artifact remains valid evidence and
 * must not be deleted from knowledge merely because it fails verification.
 */
enum class DumpStatus {
    /** No status flag present; the DAT considers this a normal dump. */
    GOOD,

    /** DAT marked the entry `verified`. */
    VERIFIED,

    /** DAT marked the entry `baddump`. */
    BAD_DUMP,

    /** DAT records the release but has no dump (`nodump`). */
    NO_DUMP,

    UNKNOWN,
    ;

    /**
     * Whether a record with this status may be used to identify a local file.
     *
     * `nodump` entries carry placeholder hashes and `baddump` entries carry the
     * hashes of a known-broken dump. Indexing either would point a real file at
     * the wrong release with full confidence, which is the exact failure mode
     * TESTING_SPEC.md section 1 forbids.
     *
     * The records are still stored: Constitution section 199 keeps imperfect
     * artifacts as evidence. They are excluded from matching, not from
     * knowledge.
     */
    val isReliableForMatching: Boolean
        get() = this != BAD_DUMP && this != NO_DUMP
}

/**
 * Software-state flags that materially change what a file *is*.
 *
 * Constitution section 193: these overlap (a translation can also be a patched
 * build), so this is a set, never a single mutually exclusive label.
 */
enum class ReleaseFlag {
    PROTOTYPE,
    BETA,
    DEMO,
    SAMPLE,
    KIOSK,
    UNLICENSED,
    PIRATE,
    TRANSLATION,
    HACK,
    TRAINER,
    ALTERNATE,
    HOMEBREW,
}

/** How the bytes on storage are packaged. */
enum class ContainerKind {
    /** The file is the artifact. */
    RAW,

    /** The file is a ZIP container; contained entries are separate artifacts. */
    ZIP,

    /** Recognised as a container but not supported for inspection in this slice. */
    UNSUPPORTED_ARCHIVE,
}
