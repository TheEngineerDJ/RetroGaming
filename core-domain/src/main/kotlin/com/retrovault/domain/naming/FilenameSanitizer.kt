package com.retrovault.domain.naming

/** Why a proposed filename was rejected. */
enum class InvalidNameReason {
    EMPTY,
    PATH_SEPARATOR,
    PATH_TRAVERSAL,
    RESERVED_DEVICE_NAME,
    ILLEGAL_CHARACTER,
    CONTROL_CHARACTER,
    TRAILING_DOT_OR_SPACE,
    TOO_LONG,
}

/** The result of validating a proposed destination name. */
sealed interface FilenameValidation {
    data class Valid(val name: String) : FilenameValidation

    data class Invalid(val reason: InvalidNameReason, val message: String) : FilenameValidation
}

/**
 * Destination-name safety.
 *
 * SECURITY_SPEC.md section 2: never construct a destination from unvalidated
 * user or DAT input, and reject path traversal, invalid components and empty
 * names. A DAT is untrusted input; a `<game name="../../etc/passwd">` must
 * produce a rejected plan entry, not a written file.
 *
 * The rules target the *weakest* filesystem the app can plausibly write to,
 * because SAF volumes are frequently FAT32 or exFAT.
 */
object FilenameSanitizer {
    const val VERSION: String = "filename-sanitizer-v1"

    /** Bytes, not characters: FAT and ext4 both bound the encoded length. */
    const val MAX_FILENAME_BYTES: Int = 255

    private const val REPLACEMENT = '_'

    private val illegalCharacters = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val reservedDeviceNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { add("COM$it") }
        (1..9).forEach { add("LPT$it") }
    }

    /**
     * Replaces characters that cannot appear in a filename.
     *
     * Sanitizing is only ever applied to text RetroVault generates. It is not a
     * licence to accept anything: [validate] still runs afterwards, and a name
     * that cannot be made safe is rejected rather than mangled into something
     * that happens to be writable.
     */
    fun sanitize(raw: String): String {
        val replaced = buildString(raw.length) {
            for (character in raw) {
                when {
                    character.isISOControl() -> append(REPLACEMENT)
                    character in illegalCharacters -> append(REPLACEMENT)
                    else -> append(character)
                }
            }
        }
        return replaced.trim().trimEnd('.', ' ')
    }

    fun validate(name: String): FilenameValidation {
        if (name.isEmpty()) {
            return FilenameValidation.Invalid(InvalidNameReason.EMPTY, "The destination name is empty.")
        }
        if (name == "." || name == "..") {
            return FilenameValidation.Invalid(
                InvalidNameReason.PATH_TRAVERSAL,
                "'$name' refers to a directory, not a file.",
            )
        }
        if (name.contains('/') || name.contains('\\')) {
            return FilenameValidation.Invalid(
                InvalidNameReason.PATH_SEPARATOR,
                "A destination name may not contain a path separator.",
            )
        }
        name.firstOrNull { it.isISOControl() }?.let {
            return FilenameValidation.Invalid(
                InvalidNameReason.CONTROL_CHARACTER,
                "The destination name contains a control character.",
            )
        }
        name.firstOrNull { it in illegalCharacters }?.let { character ->
            return FilenameValidation.Invalid(
                InvalidNameReason.ILLEGAL_CHARACTER,
                "The destination name contains '$character', which is not allowed on all storage volumes.",
            )
        }
        if (name.last() == '.' || name.last() == ' ' || name.first() == ' ') {
            return FilenameValidation.Invalid(
                InvalidNameReason.TRAILING_DOT_OR_SPACE,
                "The destination name starts or ends with a space or a dot.",
            )
        }
        val stem = name.substringBefore('.').uppercase()
        if (stem in reservedDeviceNames) {
            return FilenameValidation.Invalid(
                InvalidNameReason.RESERVED_DEVICE_NAME,
                "'$stem' is a reserved device name on some platforms.",
            )
        }
        if (name.toByteArray(Charsets.UTF_8).size > MAX_FILENAME_BYTES) {
            return FilenameValidation.Invalid(
                InvalidNameReason.TOO_LONG,
                "The destination name is longer than $MAX_FILENAME_BYTES bytes.",
            )
        }
        return FilenameValidation.Valid(name)
    }

    /**
     * Shortens [stem] so that `stem + suffix` fits within the byte budget.
     *
     * Only the title is shortened. Region, revision and disc tokens are
     * identity-bearing and must survive truncation
     * (Constitution section 175), so they live in [suffix].
     */
    fun truncateStem(stem: String, suffix: String, maxBytes: Int = MAX_FILENAME_BYTES): String {
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        val budget = maxBytes - suffixBytes
        if (budget <= 0) return ""
        if (stem.toByteArray(Charsets.UTF_8).size <= budget) return stem
        var result = stem
        while (result.isNotEmpty() && result.toByteArray(Charsets.UTF_8).size > budget) {
            // Drop a whole code point, not a UTF-16 code unit. Cutting between a
            // surrogate pair leaves an unpaired half, which encodes to a
            // replacement character and silently corrupts the name.
            val drop = if (result.length >= 2 && result[result.lastIndex - 1].isHighSurrogate() &&
                result[result.lastIndex].isLowSurrogate()
            ) {
                2
            } else {
                1
            }
            result = result.dropLast(drop)
        }
        return result.trimEnd('.', ' ')
    }
}
