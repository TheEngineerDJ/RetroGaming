package com.retrovault.domain.observation

/**
 * A prefix a dumping tool added in front of the software itself.
 *
 * Constitution section 200: representation can differ without identity
 * differing, and "a file mismatch must not immediately become an identity
 * mismatch". A copier header is the plainest case there is - the same cartridge
 * dumped by two tools produces two files whose bytes differ only by a prefix
 * one of them wrote, and preservation datasets catalogue the dump *without* it.
 *
 * Only headers that can be *skipped* are modelled. A Mega Drive SMD dump is
 * also 512 bytes longer than its payload, but the payload is interleaved rather
 * than merely offset, so recovering it is a transform and not an offset. Naming
 * it here would imply RetroVault can identify one when it cannot.
 */
enum class RomHeaderKind(
    val length: Long,
    /** Words a user can act on, not a specification reference. */
    val description: String,
) {
    /** iNES header on an NES dump: `NES<1A>` then 12 more bytes. */
    INES(16, "a 16-byte iNES header"),

    /** Famicom Disk System header: `FDS<1A>` then 12 more bytes. */
    FDS(16, "a 16-byte Famicom Disk System header"),

    /** Atari Lynx `LYNX` header. */
    LYNX(64, "a 64-byte Lynx header"),

    /** Atari 7800 header, `ATARI7800` at offset 1. */
    A7800(128, "a 128-byte Atari 7800 header"),

    /**
     * SNES copier header.
     *
     * Detected by size rather than by content: copier headers carry no reliable
     * magic, and every SNES dump is a whole number of kilobytes, so a file
     * 512 bytes over a kilobyte boundary is carrying one. This is the same test
     * every SNES tool uses, and it is a heuristic - which is why an identity
     * that rests on it is reported as [com.retrovault.domain.resolution.ResolutionState.MODIFIED_MATCH]
     * rather than as a plain exact match.
     */
    SNES_COPIER(512, "a 512-byte copier header"),
}

/** A header found on one observed file. */
data class DetectedHeader(
    val kind: RomHeaderKind,
    val length: Long = kind.length,
)

/**
 * Recognises headers that can be skipped to reach the catalogued dump.
 *
 * Pure and byte-oriented so the rules are testable without a filesystem, and
 * deliberately conservative: a wrongly detected header shifts every subsequent
 * byte and would produce a hash that matches nothing, wasting a read; a wrongly
 * *undetected* header costs the same fallback the code has always had. Neither
 * can produce a wrong identity, because identity still rests on a cryptographic
 * hash of whatever the payload turns out to be.
 */
object RomHeaderDetector {
    const val VERSION: String = "rom-header-v1"

    /** The most bytes any recogniser needs in order to decide. */
    const val PREFIX_BYTES: Int = 128

    private val magicHeaders = mapOf(
        "nes" to (RomHeaderKind.INES to byteMagic("NES", 0x1A)),
        "fds" to (RomHeaderKind.FDS to byteMagic("FDS", 0x1A)),
        "lnx" to (RomHeaderKind.LYNX to byteMagic("LYNX")),
    )

    /** Extensions whose header can only be decided by looking at the bytes. */
    private val needsPrefixFor = magicHeaders.keys + "a78"

    /** Extensions carrying a header detectable from size alone. */
    private val sizeDetectedFor = setOf("smc", "sfc", "swc", "fig")

    /** Whether deciding about this file needs its first bytes read at all. */
    fun needsPrefix(extension: String?): Boolean =
        extension?.lowercase() in needsPrefixFor

    fun mayCarryHeader(extension: String?): Boolean {
        val normalized = extension?.lowercase() ?: return false
        return normalized in needsPrefixFor || normalized in sizeDetectedFor
    }

    /**
     * @param prefix the first [PREFIX_BYTES] of the file, or fewer if it is
     * shorter. Empty when the caller did not read any, in which case only
     * size-detected headers can be found - absence of evidence, treated as
     * absence of a header rather than as a guess.
     */
    fun detect(extension: String?, size: Long, prefix: ByteArray = ByteArray(0)): DetectedHeader? {
        val normalized = extension?.lowercase() ?: return null

        magicHeaders[normalized]?.let { (kind, magic) ->
            return if (size > kind.length && prefix.startsWith(magic, offset = 0)) {
                DetectedHeader(kind)
            } else {
                null
            }
        }

        if (normalized == "a78") {
            // The magic sits at offset 1, after a version byte.
            return if (size > RomHeaderKind.A7800.length && prefix.startsWith(ATARI_7800_MAGIC, offset = 1)) {
                DetectedHeader(RomHeaderKind.A7800)
            } else {
                null
            }
        }

        if (normalized in sizeDetectedFor) {
            val header = RomHeaderKind.SNES_COPIER.length
            // A file that is *only* a header has no payload to identify.
            return if (size > header && size % 1024L == header) DetectedHeader(RomHeaderKind.SNES_COPIER) else null
        }

        return null
    }

    private fun byteMagic(text: String, vararg trailing: Int): ByteArray =
        text.map { it.code.toByte() }.toByteArray() + trailing.map { it.toByte() }.toByteArray()

    private val ATARI_7800_MAGIC = byteMagic("ATARI7800")

    private fun ByteArray.startsWith(magic: ByteArray, offset: Int): Boolean {
        if (size < offset + magic.size) return false
        return magic.indices.all { this[offset + it] == magic[it] }
    }
}
