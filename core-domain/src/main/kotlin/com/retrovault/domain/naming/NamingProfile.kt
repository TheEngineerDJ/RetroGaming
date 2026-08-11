package com.retrovault.domain.naming

import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.RegionVocabulary
import com.retrovault.domain.identity.ReleaseFlag

/**
 * A deterministic projection from canonical identity to a filename.
 *
 * DOMAIN_MODEL.md section 27/28 and Constitution section 176: the filename is a
 * projection, never identity. Several profiles may project the same identity
 * differently without creating different objects.
 *
 * Profiles are versioned because changing one can affect thousands of user
 * files (Constitution section 185).
 */
data class NamingProfile(
    val id: String,
    val version: String,
    val displayName: String,
    val includeRegions: Boolean = true,
    val includeLanguages: Boolean = false,
    val includeRevision: Boolean = true,
    val includeVersion: Boolean = true,
    val includeDisc: Boolean = true,
    val includeStatusFlags: Boolean = true,
    val regionSeparator: String = ", ",
    val maxFilenameBytes: Int = FilenameSanitizer.MAX_FILENAME_BYTES,
) {
    val versionedId: String get() = "$id@$version"
}

object NamingProfiles {
    /**
     * No-Intro-style output: `Title (Region) (Languages) (Rev A) (Disc 1) (Beta)`.
     *
     * Chosen as the default because it is what the preservation datasets and
     * the common frontends already agree on (Constitution section 158).
     */
    val NO_INTRO_V1 = NamingProfile(
        id = "no-intro",
        version = "v1",
        displayName = "No-Intro style",
        includeRegions = true,
        includeLanguages = true,
        includeRevision = true,
        includeVersion = true,
        includeDisc = true,
        includeStatusFlags = true,
    )

    /**
     * Title and region only. Useful for frontends that key artwork on a short
     * name and choke on long token lists.
     */
    val MINIMAL_V1 = NamingProfile(
        id = "minimal",
        version = "v1",
        displayName = "Title and region only",
        includeRegions = true,
        includeLanguages = false,
        includeRevision = false,
        includeVersion = false,
        includeDisc = true,
        includeStatusFlags = false,
    )

    val all: List<NamingProfile> = listOf(NO_INTRO_V1, MINIMAL_V1)

    fun byId(id: String): NamingProfile? = all.firstOrNull { it.id == id }
}

/**
 * Builds canonical filenames.
 *
 * Deterministic by construction: the same record and profile always produce the
 * same string (Constitution section 157, ENGINEERING_SPEC.md section 11). The
 * generated name is validated before it can be used, so an unsafe DAT title
 * fails as data rather than as a filesystem operation.
 */
object CanonicalFilenameGenerator {
    const val VERSION: String = "canonical-filename-v1"

    /**
     * Flag tokens, in the order a No-Intro name writes them. The list is fixed
     * rather than derived from the enum so that reordering the enum cannot
     * silently rewrite a user's library.
     */
    private val flagTokens: List<Pair<ReleaseFlag, String>> = listOf(
        ReleaseFlag.PROTOTYPE to "Proto",
        ReleaseFlag.BETA to "Beta",
        ReleaseFlag.DEMO to "Demo",
        ReleaseFlag.SAMPLE to "Sample",
        ReleaseFlag.KIOSK to "Kiosk",
        ReleaseFlag.UNLICENSED to "Unl",
        ReleaseFlag.PIRATE to "Pirate",
        ReleaseFlag.TRANSLATION to "T-En",
        ReleaseFlag.HACK to "Hack",
        ReleaseFlag.TRAINER to "Trainer",
        ReleaseFlag.ALTERNATE to "Alt",
    )

    /**
     * @param containerExtension extension of the file being renamed. It is
     * taken from the observed file, not from the catalogue, so that renaming a
     * `.zip` never turns it into a `.sfc`.
     */
    fun generate(
        record: DumpRecord,
        profile: NamingProfile,
        containerExtension: String?,
    ): FilenameValidation {
        val tokens = buildList {
            if (profile.includeRegions && record.regions.isNotEmpty()) {
                add(
                    RegionVocabulary.sort(record.regions)
                        .joinToString(profile.regionSeparator) { it.displayToken },
                )
            }
            if (profile.includeLanguages && record.languages.isNotEmpty()) {
                add(record.languages.joinToString(",") { it.displayToken })
            }
            if (profile.includeRevision) record.revision?.let { add("Rev $it") }
            if (profile.includeVersion) record.version?.let { add("v$it") }
            if (profile.includeDisc) record.discNumber?.let { add("Disc $it") }
            if (profile.includeStatusFlags) {
                flagTokens.forEach { (flag, token) -> if (flag in record.flags) add(token) }
            }
        }

        val suffix = buildString {
            tokens.forEach { token -> append(" (").append(token).append(')') }
            if (!containerExtension.isNullOrBlank()) append('.').append(containerExtension)
        }

        val stem = FilenameSanitizer.sanitize(record.canonicalTitle)
        // Sanitizing turns separators into underscores, so a malformed title
        // like "///" would otherwise yield the perfectly "valid" name "___".
        // Refusing is better than renaming a user's file to punctuation.
        if (stem.none { it.isLetterOrDigit() }) {
            return FilenameValidation.Invalid(
                InvalidNameReason.EMPTY,
                "The catalogue title '${record.canonicalTitle}' contains no usable characters.",
            )
        }
        val truncated = FilenameSanitizer.truncateStem(stem, suffix, profile.maxFilenameBytes)
        if (truncated.isEmpty()) {
            return FilenameValidation.Invalid(
                InvalidNameReason.TOO_LONG,
                "The identity tokens alone exceed the filename length limit.",
            )
        }
        return FilenameSanitizer.validate(truncated + suffix)
    }
}
