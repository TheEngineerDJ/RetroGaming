package com.retrovault.domain.naming

import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.LanguageVocabulary
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.RegionVocabulary
import com.retrovault.domain.identity.ReleaseFlag
import com.retrovault.domain.identity.VocabularyVersions

/**
 * What a filename token means.
 *
 * Constitution section 156 forbids a blanket "remove everything in brackets"
 * rule: some bracketed tokens are identity-critical and some are noise, so
 * every token is classified and none is discarded.
 */
enum class TokenClass {
    REGION,
    LANGUAGE,
    MULTI_LANGUAGE_COUNT,
    REVISION,
    VERSION,
    DISC,
    DUMP_STATUS,
    TRANSLATION,
    HACK,
    TRAINER,
    PROTOTYPE,
    BETA,
    DEMO,
    SAMPLE,
    KIOSK,
    UNLICENSED,
    PIRATE,
    ALTERNATE,
    RELEASE_GROUP,
    MEMORY_REQUIREMENT,
    EMULATOR_METADATA,

    /**
     * Recognised as a token but not understood.
     *
     * Constitution section 174: unknown tokens are preserved because they may
     * carry future identity evidence.
     */
    UNKNOWN,
}

/** How the token was delimited in the original filename. */
enum class TokenDelimiter { PARENTHESIS, BRACKET, HYPHEN_SUFFIX, SCENE_SEGMENT }

/** The structured meaning extracted from a token, when there is one. */
sealed interface TokenValue {
    data class Regions(val regions: List<RegionCode>) : TokenValue
    data class Languages(val languages: List<LanguageCode>) : TokenValue
    data class LanguageCount(val count: Int) : TokenValue
    data class Revision(val revision: String) : TokenValue
    data class Version(val version: String) : TokenValue
    data class Disc(val number: Int) : TokenValue
    data class Status(val status: DumpStatus) : TokenValue
    data class Flag(val flag: ReleaseFlag) : TokenValue
}

/** One classified token, with its original text preserved verbatim. */
data class FilenameToken(
    val text: String,
    val delimiter: TokenDelimiter,
    val tokenClass: TokenClass,
    val value: TokenValue? = null,
)

/**
 * The structured reading of one filename.
 *
 * This is an *observation*, never an identity (Constitution section 144,
 * DOMAIN_MODEL.md section 27). [original] is retained so that nothing the
 * tokenizer decided can destroy the evidence it was derived from.
 */
data class ParsedFilename(
    val original: String,
    val baseName: String,
    val extension: String?,
    val titleText: String,
    val tokens: List<FilenameToken>,
) {
    val normalizedTitle: NormalizedTitle by lazy { TitleNormalizer.normalize(titleText) }

    val regions: List<RegionCode>
        get() = tokens.mapNotNull { it.value as? TokenValue.Regions }.flatMap { it.regions }.distinct()

    val languages: List<LanguageCode>
        get() = tokens.mapNotNull { it.value as? TokenValue.Languages }.flatMap { it.languages }.distinct()

    val revision: String?
        get() = tokens.firstNotNullOfOrNull { (it.value as? TokenValue.Revision)?.revision }

    val version: String?
        get() = tokens.firstNotNullOfOrNull { (it.value as? TokenValue.Version)?.version }

    val discNumber: Int?
        get() = tokens.firstNotNullOfOrNull { (it.value as? TokenValue.Disc)?.number }

    val flags: Set<ReleaseFlag>
        get() = tokens.mapNotNull { (it.value as? TokenValue.Flag)?.flag }.toSet()

    val unknownTokens: List<FilenameToken>
        get() = tokens.filter { it.tokenClass == TokenClass.UNKNOWN }
}

/**
 * Splits a filename into a title and typed tokens.
 *
 * The tokenizer never decides identity. It produces signals that the resolver
 * may weigh against catalogue evidence (ROM_INTELLIGENCE.md section 6).
 */
object FilenameTokenizer {
    const val VERSION: String = VocabularyVersions.TOKEN

    private val discPattern = Regex("""^(?:disc|disk|cd|dvd)\s*[-#]?\s*(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val revisionPattern = Regex("""^rev(?:ision)?[\s._-]*([0-9a-z.]{1,8})$""", RegexOption.IGNORE_CASE)
    private val versionPattern = Regex("""^v(?:er(?:sion)?)?[\s._-]*(\d[0-9a-z.]{0,9})$""", RegexOption.IGNORE_CASE)
    private val multiLanguagePattern = Regex("""^m(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val betaPattern = Regex("""^beta\s*\d*$""", RegexOption.IGNORE_CASE)
    private val protoPattern = Regex("""^(?:proto|prototype)\s*\d*$""", RegexOption.IGNORE_CASE)
    private val demoPattern = Regex("""^demo(?:\s.*)?$""", RegexOption.IGNORE_CASE)
    private val translationPattern = Regex("""^t[+-].*$""", RegexOption.IGNORE_CASE)
    private val trainerPattern = Regex("""^t\+?\d*$|^trainer$""", RegexOption.IGNORE_CASE)
    private val alternatePattern = Regex("""^a\d*$""", RegexOption.IGNORE_CASE)
    private val badDumpPattern = Regex("""^b\d*$""", RegexOption.IGNORE_CASE)
    private val overdumpPattern = Regex("""^o\d*$""", RegexOption.IGNORE_CASE)
    private val fixedPattern = Regex("""^f\d*$""", RegexOption.IGNORE_CASE)
    private val hackPattern = Regex("""^h\d*$|^hack$""", RegexOption.IGNORE_CASE)
    private val memoryPattern = Regex("""^(?:memory\w*|\d{1,4}[kmg]b?|\d{1,2}mbit)$""", RegexOption.IGNORE_CASE)
    private val emulatorPattern =
        Regex("""^(?:no-?intro|redump|goodmerge|tosec|gamecube|nkit|decrypted|encrypted|trimmed|scrubbed)$""", RegexOption.IGNORE_CASE)
    private val releaseGroupPattern = Regex("""^[a-z0-9]{2,12}-[a-z0-9]{2,12}$""", RegexOption.IGNORE_CASE)
    private val extensionPattern = Regex("""^[a-z0-9]{1,8}$""", RegexOption.IGNORE_CASE)

    /**
     * Scene tokens that are safe to strip from a title.
     *
     * Deliberately narrow: an unrecognised trailing segment stays part of the
     * title, because "Spider-Man" must not lose "-Man".
     */
    /**
     * Region codes whose names are also ordinary title words.
     *
     * Outside brackets these are read as part of the title. Missing a region
     * token costs a little matching power; misreading "World" in
     * "Super Mario World" as a region silently corrupts the title.
     */
    private val ambiguousOutsideBrackets = setOf(
        "WORLD", "ASIA", "CANADA", "FRANCE", "ITALY", "SPAIN", "GERMANY",
        "RUSSIA", "SWEDEN", "NETHERLANDS", "TAIWAN", "HONG_KONG", "CHINA",
        "UNITED_KINGDOM", "AUSTRALIA", "BRAZIL", "KOREA",
    )

    private val scenePlatformTokens = setOf(
        "psp", "ps2", "ps1", "psx", "nds", "3ds", "wii", "gc", "gba", "gbc", "gb",
        "snes", "nes", "n64", "genesis", "megadrive", "dreamcast", "saturn", "pce",
    )

    fun tokenize(filename: String): ParsedFilename {
        val extension = extractExtension(filename)
        val baseName = if (extension == null) filename else filename.dropLast(extension.length + 1)
        val bracketGroups = extractBracketGroups(baseName)

        return if (bracketGroups.groups.isEmpty()) {
            tokenizeWithoutBrackets(filename, baseName, extension)
        } else {
            val tokens = bracketGroups.groups.map { group -> classify(group.text, group.delimiter) }
            val (title, suffixTokens) = stripKnownHyphenSuffix(bracketGroups.leadingText)
            val trailingTokens = bracketGroups.trailingText
                ?.let { listOf(classifyTrailingText(it)) }
                .orEmpty()
            ParsedFilename(
                original = filename,
                baseName = baseName,
                extension = extension,
                titleText = title,
                tokens = suffixTokens + tokens + trailingTokens,
            )
        }
    }

    /**
     * Handles scene-style names such as `Super.Mario.World.USA.SNES-Group`.
     *
     * Dots are only treated as separators when the name has no spaces and at
     * least two of them, so `Sonic 3.bin` and `Game v1.2` keep their meaning.
     */
    private fun tokenizeWithoutBrackets(
        filename: String,
        baseName: String,
        extension: String?,
    ): ParsedFilename {
        val looksSceneStyle = !baseName.contains(' ') && baseName.count { it == '.' || it == '_' } >= 2
        if (!looksSceneStyle) {
            val (title, suffixTokens) = stripKnownHyphenSuffix(baseName)
            return ParsedFilename(filename, baseName, extension, title, suffixTokens)
        }

        val segments = baseName.split('.', '_').filter { it.isNotEmpty() }
        val titleSegments = mutableListOf<String>()
        val tokens = mutableListOf<FilenameToken>()
        // Segments are classified from the right: once a segment is recognised
        // as metadata, everything to its right is metadata too. This stops a
        // title word that happens to look like a token from truncating a title.
        var stillMetadata = true
        for (segment in segments.reversed()) {
            val classified = if (stillMetadata) classify(segment, TokenDelimiter.SCENE_SEGMENT) else null
            if (classified != null && classified.tokenClass != TokenClass.UNKNOWN) {
                tokens.add(0, classified)
            } else {
                stillMetadata = false
                titleSegments.add(0, segment)
            }
        }
        return ParsedFilename(
            original = filename,
            baseName = baseName,
            extension = extension,
            titleText = titleSegments.joinToString(" "),
            tokens = tokens,
        )
    }

    /**
     * Removes a trailing `-token` suffix such as `-memorypsp`, but only when
     * the suffix is recognised noise.
     */
    private fun stripKnownHyphenSuffix(text: String): Pair<String, List<FilenameToken>> {
        val trimmed = text.trim()
        val hyphenIndex = trimmed.lastIndexOf('-')
        if (hyphenIndex <= 0 || hyphenIndex == trimmed.lastIndex) return trimmed to emptyList()
        val suffix = trimmed.substring(hyphenIndex + 1).trim()
        if (suffix.contains(' ')) return trimmed to emptyList()
        val classified = classify(suffix, TokenDelimiter.HYPHEN_SUFFIX)
        val strippable = classified.tokenClass in setOf(
            TokenClass.MEMORY_REQUIREMENT,
            TokenClass.EMULATOR_METADATA,
            TokenClass.RELEASE_GROUP,
        )
        return if (strippable) {
            trimmed.substring(0, hyphenIndex).trim() to listOf(classified)
        } else {
            trimmed to emptyList()
        }
    }

    /**
     * Classifies free text that follows the last bracket group, such as the
     * `-memorypsp` in `Some Game (USA)-memorypsp.iso`.
     *
     * Unrecognised trailing text is kept as an unknown token rather than
     * discarded (Constitution section 174).
     */
    private fun classifyTrailingText(text: String): FilenameToken {
        val stripped = text.trimStart('-', '_', ' ')
        val delimiter =
            if (text.startsWith("-")) TokenDelimiter.HYPHEN_SUFFIX else TokenDelimiter.SCENE_SEGMENT
        val classified = classify(stripped, delimiter)
        return if (classified.tokenClass == TokenClass.UNKNOWN) {
            FilenameToken(text, delimiter, TokenClass.UNKNOWN)
        } else {
            classified
        }
    }

    private data class BracketGroup(val text: String, val delimiter: TokenDelimiter)

    private data class BracketScan(
        val leadingText: String,
        val groups: List<BracketGroup>,
        val trailingText: String?,
    )

    /**
     * Splits a base name into the text before the first bracket group, the
     * bracket groups themselves, and any free text after the last group.
     *
     * Unbalanced brackets are treated as literal text rather than as an error:
     * a filename is untrusted input and must never abort a scan
     * (SECURITY_SPEC.md section 1).
     */
    private fun extractBracketGroups(baseName: String): BracketScan {
        val groups = mutableListOf<BracketGroup>()
        val leading = StringBuilder()
        val trailing = StringBuilder()
        var index = 0
        var seenGroup = false

        while (index < baseName.length) {
            val character = baseName[index]
            val closing = when (character) {
                '(' -> ')'
                '[' -> ']'
                else -> null
            }
            if (closing == null) {
                if (seenGroup) trailing.append(character) else leading.append(character)
                index++
                continue
            }
            val end = baseName.indexOf(closing, startIndex = index + 1)
            if (end < 0) {
                if (seenGroup) trailing.append(character) else leading.append(character)
                index++
                continue
            }
            val inner = baseName.substring(index + 1, end).trim()
            if (inner.isNotEmpty()) {
                groups.add(
                    BracketGroup(
                        text = inner,
                        delimiter = if (character == '(') TokenDelimiter.PARENTHESIS else TokenDelimiter.BRACKET,
                    ),
                )
                seenGroup = true
            }
            index = end + 1
        }

        val trailingText = trailing.toString().trim().takeIf { it.isNotEmpty() }
        return BracketScan(leading.toString().trim(), groups, trailingText)
    }

    /** Classifies one token's text. Never throws; unknown text stays unknown. */
    internal fun classify(text: String, delimiter: TokenDelimiter): FilenameToken {
        val trimmed = text.trim()
        fun token(tokenClass: TokenClass, value: TokenValue? = null) =
            FilenameToken(trimmed, delimiter, tokenClass, value)

        if (trimmed.isEmpty()) return token(TokenClass.UNKNOWN)

        // A bracketed token is a deliberate metadata marker. A bare dot-separated
        // segment is not, so region and language reading is restricted there:
        // "Super.Mario.World" must not lose "World" to the World region.
        val bracketed = delimiter == TokenDelimiter.PARENTHESIS || delimiter == TokenDelimiter.BRACKET

        RegionVocabulary.parseCompound(trimmed)?.let { regions ->
            if (bracketed || regions.none { it.code in ambiguousOutsideBrackets }) {
                return token(TokenClass.REGION, TokenValue.Regions(regions))
            }
        }
        if (bracketed) {
            LanguageVocabulary.parseList(trimmed)?.let {
                // A single-letter region alias such as "E" is also a plausible
                // language prefix; regions are checked first so the stronger
                // No-Intro convention wins.
                return token(TokenClass.LANGUAGE, TokenValue.Languages(it))
            }
        }
        multiLanguagePattern.matchEntire(trimmed)?.let { match ->
            val count = match.groupValues[1].toIntOrNull()
            if (count != null && count > 1) {
                return token(TokenClass.MULTI_LANGUAGE_COUNT, TokenValue.LanguageCount(count))
            }
        }
        discPattern.matchEntire(trimmed)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return token(TokenClass.DISC, TokenValue.Disc(it)) }
        }
        revisionPattern.matchEntire(trimmed)?.let { match ->
            return token(TokenClass.REVISION, TokenValue.Revision(match.groupValues[1].uppercase()))
        }
        versionPattern.matchEntire(trimmed)?.let { match ->
            return token(TokenClass.VERSION, TokenValue.Version(match.groupValues[1].lowercase()))
        }
        if (trimmed == "!") return token(TokenClass.DUMP_STATUS, TokenValue.Status(DumpStatus.VERIFIED))
        if (translationPattern.matches(trimmed)) {
            return token(TokenClass.TRANSLATION, TokenValue.Flag(ReleaseFlag.TRANSLATION))
        }
        if (protoPattern.matches(trimmed)) return token(TokenClass.PROTOTYPE, TokenValue.Flag(ReleaseFlag.PROTOTYPE))
        if (betaPattern.matches(trimmed)) return token(TokenClass.BETA, TokenValue.Flag(ReleaseFlag.BETA))
        if (demoPattern.matches(trimmed)) return token(TokenClass.DEMO, TokenValue.Flag(ReleaseFlag.DEMO))
        if (trimmed.equals("sample", ignoreCase = true)) {
            return token(TokenClass.SAMPLE, TokenValue.Flag(ReleaseFlag.SAMPLE))
        }
        if (trimmed.equals("kiosk", ignoreCase = true)) {
            return token(TokenClass.KIOSK, TokenValue.Flag(ReleaseFlag.KIOSK))
        }
        if (trimmed.equals("unl", ignoreCase = true) || trimmed.equals("unlicensed", ignoreCase = true)) {
            return token(TokenClass.UNLICENSED, TokenValue.Flag(ReleaseFlag.UNLICENSED))
        }
        if (trimmed.equals("pirate", ignoreCase = true) || trimmed.matches(Regex("^p\\d*$"))) {
            return token(TokenClass.PIRATE, TokenValue.Flag(ReleaseFlag.PIRATE))
        }
        if (hackPattern.matches(trimmed)) return token(TokenClass.HACK, TokenValue.Flag(ReleaseFlag.HACK))
        if (trainerPattern.matches(trimmed)) return token(TokenClass.TRAINER, TokenValue.Flag(ReleaseFlag.TRAINER))
        if (alternatePattern.matches(trimmed)) {
            return token(TokenClass.ALTERNATE, TokenValue.Flag(ReleaseFlag.ALTERNATE))
        }
        if (badDumpPattern.matches(trimmed)) {
            return token(TokenClass.DUMP_STATUS, TokenValue.Status(DumpStatus.BAD_DUMP))
        }
        if (overdumpPattern.matches(trimmed) || fixedPattern.matches(trimmed)) {
            return token(TokenClass.DUMP_STATUS, TokenValue.Status(DumpStatus.UNKNOWN))
        }
        if (memoryPattern.matches(trimmed)) return token(TokenClass.MEMORY_REQUIREMENT)
        if (emulatorPattern.matches(trimmed)) return token(TokenClass.EMULATOR_METADATA)
        if (trimmed.lowercase() in scenePlatformTokens) return token(TokenClass.EMULATOR_METADATA)
        if (releaseGroupPattern.matches(trimmed)) {
            val platformPart = trimmed.substringBefore('-').lowercase()
            if (platformPart in scenePlatformTokens) return token(TokenClass.RELEASE_GROUP)
        }
        return token(TokenClass.UNKNOWN)
    }

    private fun extractExtension(filename: String): String? {
        val dotIndex = filename.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == filename.lastIndex) return null
        val candidate = filename.substring(dotIndex + 1)
        return if (extensionPattern.matches(candidate)) candidate else null
    }
}
