package com.retrovault.domain.identity

/**
 * The physical or logical medium a dump came from.
 *
 * Constitution section 23 is explicit that ROMs are not all the same class of
 * artifact and that the architecture must avoid a universal
 * "one file = one hash = one game" assumption. Media type is the first-class
 * expression of that: a PSP UMD image and a SNES cartridge dump are catalogued
 * by different projects, carry different evidence and fail in different ways,
 * and treating both as a generic "ROM" is what makes a PSP library look
 * unidentifiable rather than uncatalogued.
 *
 * `UNKNOWN` is a valid escape state (Constitution section 238). It means
 * "RetroVault did not recognise the medium", never "this is not a game".
 */
enum class MediaType {
    /** Cartridge or card dumps: SNES, Mega Drive, Game Boy, DS, 3DS, Switch. */
    CARTRIDGE,

    /** Optical media: CD, DVD, GD-ROM, UMD, Blu-ray, and their disc images. */
    OPTICAL_DISC,

    /** Floppy and other removable magnetic disk images. */
    FLOPPY_DISK,

    /** Cassette and other tape images. */
    TAPE,

    /** Installed or imaged fixed storage. */
    HARD_DISK,

    /** Digitally distributed packages with no physical original. */
    DIGITAL_DOWNLOAD,

    /** Arcade board ROM sets, catalogued as a set rather than as one dump. */
    ARCADE_BOARD,

    UNKNOWN,
    ;

    /** Human-readable form for evidence descriptions. */
    val describe: String
        get() = name.lowercase().replace('_', ' ')
}

/**
 * Recognises the medium a filename implies.
 *
 * This is *derived data* (Constitution section 72): it never replaces the
 * observed filename and never becomes identity on its own. It exists so the
 * pipeline can tell a disc image from a cartridge dump, and so RetroVault can
 * say "no dataset covers optical discs" instead of "unknown game".
 *
 * Extensions that genuinely belong to more than one medium resolve to
 * [MediaType.UNKNOWN] rather than to a guess. `.bin` is the clearest case: it
 * is both a Mega Drive cartridge dump and a CD track. Guessing there would put
 * a wrong medium on the evidence trail, and an unknown medium costs nothing
 * because media type only ever widens what RetroVault will consider.
 */
object MediaTypeVocabulary {
    const val VERSION: String = VocabularyVersions.MEDIA

    private val byExtension: Map<String, MediaType> = buildMap {
        listOf(
            // Nintendo
            "nes", "fds", "sfc", "smc", "swc", "fig", "n64", "z64", "v64", "ndd",
            "gb", "gbc", "gba", "srl", "nds", "dsi", "3ds", "cci", "cia", "xci",
            // Sega
            "sms", "gg", "md", "smd", "gen", "32x", "sg", "col",
            // Others
            "pce", "sgx", "a26", "a52", "a78", "lnx", "j64", "jag", "int", "vec",
            "ws", "wsc", "ngp", "ngc", "vb", "min", "gbs",
        ).forEach { put(it, MediaType.CARTRIDGE) }

        listOf(
            // Generic and platform-specific disc images. `iso` covers PSP UMD
            // dumps, which is the case this vocabulary was written for.
            "iso", "cso", "dax", "chd", "cue", "gdi", "ccd", "nrg", "mdf", "mds",
            "cdi", "wbfs", "wia", "rvz", "gcm", "gcz", "pbp",
        ).forEach { put(it, MediaType.OPTICAL_DISC) }

        listOf("d64", "d71", "d81", "adf", "adz", "dsk", "st", "msa", "fdi", "ipf", "g64", "atr")
            .forEach { put(it, MediaType.FLOPPY_DISK) }

        listOf("tap", "tzx", "cas", "cdt", "uef")
            .forEach { put(it, MediaType.TAPE) }

        listOf("hdf", "hdi", "vhd")
            .forEach { put(it, MediaType.HARD_DISK) }

        listOf("nsp", "wud", "wux", "wua", "xex", "rpx")
            .forEach { put(it, MediaType.DIGITAL_DOWNLOAD) }
    }

    /**
     * Extensions that name a real medium but are used by more than one, so they
     * are deliberately not classified.
     */
    private val ambiguous: Set<String> = setOf("bin", "rom", "dat", "raw", "img", "ima")

    /** @param filename a plain filename, not a path. */
    fun forFilename(filename: String): MediaType {
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return forExtension(extension)
    }

    /**
     * The full extension table, for callers that must reproduce this mapping
     * somewhere it cannot be called - a SQL backfill, for instance. Exposing it
     * keeps that duplicate in step with the live classification instead of
     * drifting from it.
     */
    fun knownExtensions(): Map<String, MediaType> = byExtension

    fun forExtension(extension: String?): MediaType {
        val key = extension?.lowercase()?.removePrefix(".").orEmpty()
        if (key.isEmpty() || key in ambiguous) return MediaType.UNKNOWN
        return byExtension[key] ?: MediaType.UNKNOWN
    }
}

/**
 * Which preservation project produced a dataset.
 *
 * Constitution section 6 requires source-specific status to be preserved rather
 * than flattened into one universal truth value, and section 196 forbids a DAT
 * from becoming an invisible authority. Knowing that a dataset came from Redump
 * rather than No-Intro is what lets RetroVault explain *why* a PSP library
 * found no matches against a cartridge dataset.
 */
enum class DatasetKind {
    /** No-Intro: cartridge and digital-download dumps. */
    NO_INTRO,

    /** Redump: optical-disc dumps, frequently with track-level evidence. */
    REDUMP,

    /** TOSEC: broad, multi-medium coverage. */
    TOSEC,

    /** MAME and derivatives: arcade and system ROM sets. */
    MAME,

    /** GoodTools sets. */
    GOODTOOLS,

    UNKNOWN,
    ;

    /**
     * The media this project characteristically catalogues.
     *
     * Advisory only. Actual coverage is measured from the records a dataset
     * indexes, never assumed from its name - a dataset is allowed to surprise
     * us, and measured coverage is evidence while a project's reputation is not.
     */
    val typicalMedia: Set<MediaType>
        get() = when (this) {
            NO_INTRO -> setOf(MediaType.CARTRIDGE, MediaType.DIGITAL_DOWNLOAD)
            REDUMP -> setOf(MediaType.OPTICAL_DISC)
            TOSEC -> setOf(MediaType.CARTRIDGE, MediaType.FLOPPY_DISK, MediaType.TAPE, MediaType.OPTICAL_DISC)
            MAME -> setOf(MediaType.ARCADE_BOARD)
            GOODTOOLS -> setOf(MediaType.CARTRIDGE)
            UNKNOWN -> emptySet()
        }
}

object DatasetKindVocabulary {
    const val VERSION: String = VocabularyVersions.DATASET_KIND

    /**
     * Infers the project from what the DAT says about itself.
     *
     * Every field consulted here is written by the dataset author, so this is a
     * reading of the document, not a claim about it. An unrecognised dataset is
     * [DatasetKind.UNKNOWN] and loses no capability: coverage is measured from
     * its records either way.
     */
    fun infer(provider: String?, setName: String?, author: String?, homepage: String?): DatasetKind {
        val haystack = listOfNotNull(provider, setName, author, homepage)
            .joinToString(" ")
            .lowercase()
        return when {
            haystack.contains("redump") -> DatasetKind.REDUMP
            haystack.contains("no-intro") || haystack.contains("no_intro") ||
                haystack.contains("nointro") -> DatasetKind.NO_INTRO
            haystack.contains("tosec") -> DatasetKind.TOSEC
            haystack.contains("mame") || haystack.contains("fbneo") ||
                haystack.contains("final burn") -> DatasetKind.MAME
            haystack.contains("goodtools") || haystack.contains("goodset") -> DatasetKind.GOODTOOLS
            else -> DatasetKind.UNKNOWN
        }
    }
}
