package com.retrovault.domain.identity

/**
 * Hash algorithms supported by the identification baseline.
 *
 * DOMAIN_MODEL.md section 6. The ordering of this enum is the *escalation*
 * order (cheap to expensive), not a claim about evidentiary equivalence.
 */
enum class HashAlgorithm(val hexLength: Int, val canonicalName: String) {
    CRC32(hexLength = 8, canonicalName = "CRC32"),
    MD5(hexLength = 32, canonicalName = "MD5"),
    SHA1(hexLength = 40, canonicalName = "SHA1"),
    ;

    /**
     * Whether a single exact match of this algorithm is strong enough to be
     * treated as content-level identity evidence.
     *
     * CRC32 is explicitly excluded: Constitution section 148 and
     * ROM_INTELLIGENCE.md section 3 both state that a CRC32 collision is not
     * an identity proof.
     */
    val isCryptographicIdentityEvidence: Boolean
        get() = this == MD5 || this == SHA1
}

/**
 * What exactly was hashed.
 *
 * DOMAIN_MODEL.md section 6: "A hash must always be scoped to what was hashed."
 * Hashing a ZIP and hashing the ROM inside it are different observations
 * (DOMAIN_MODEL.md section 7).
 */
sealed interface HashScope {
    /** The bytes of the file itself, container included. */
    data object WholeFile : HashScope

    /** The uncompressed bytes of one entry inside a container. */
    data class ArchiveEntry(val entryPath: String) : HashScope {
        init {
            require(entryPath.isNotBlank()) { "Archive entry path must not be blank" }
        }
    }
}

/**
 * A normalized hash digest.
 *
 * DAT files are inconsistent about case and zero padding (a CRC32 of
 * `0x00ab12cd` is frequently written `AB12CD`). Normalizing at construction
 * is what makes indexed lookup deterministic, so parsing is the only way to
 * build one.
 */
class HashValue private constructor(
    val algorithm: HashAlgorithm,
    /** Lowercase, zero-padded, exactly [HashAlgorithm.hexLength] characters. */
    val hex: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is HashValue && algorithm == other.algorithm && hex == other.hex)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + hex.hashCode()

    override fun toString(): String = "${algorithm.canonicalName}:$hex"

    companion object {
        /**
         * Returns the normalized value, or `null` when [raw] is absent or is
         * not a valid digest for [algorithm].
         *
         * Malformed hashes in an untrusted DAT must degrade to "no evidence",
         * never to a crash and never to a silently truncated digest
         * (SECURITY_SPEC.md section 1).
         */
        fun parse(algorithm: HashAlgorithm, raw: String?): HashValue? {
            if (raw == null) return null
            var text = raw.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("0x", ignoreCase = true)) text = text.substring(2)
            if (text.isEmpty() || text.length > algorithm.hexLength) return null
            if (!text.all { it.isHexDigit() }) return null
            return HashValue(algorithm, text.lowercase().padStart(algorithm.hexLength, '0'))
        }

        /** Strict variant for values the caller believes are already valid. */
        fun of(algorithm: HashAlgorithm, raw: String): HashValue =
            requireNotNull(parse(algorithm, raw)) {
                "Not a valid ${algorithm.canonicalName} digest: '$raw'"
            }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** The set of hashes known for one scope, keyed by algorithm. */
data class HashDigests(private val values: Map<HashAlgorithm, HashValue> = emptyMap()) {
    operator fun get(algorithm: HashAlgorithm): HashValue? = values[algorithm]

    fun with(hash: HashValue): HashDigests = HashDigests(values + (hash.algorithm to hash))

    fun contains(algorithm: HashAlgorithm): Boolean = values.containsKey(algorithm)

    val algorithms: Set<HashAlgorithm> get() = values.keys

    fun asList(): List<HashValue> = HashAlgorithm.entries.mapNotNull { values[it] }

    val isEmpty: Boolean get() = values.isEmpty()

    companion object {
        val EMPTY = HashDigests()

        fun of(vararg hashes: HashValue): HashDigests =
            HashDigests(hashes.associateBy { it.algorithm })
    }
}
