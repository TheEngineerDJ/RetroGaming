package com.retrovault.dat

import com.retrovault.dat.xml.MalformedXmlException
import com.retrovault.dat.xml.XmlEvent
import com.retrovault.dat.xml.XmlLimits
import com.retrovault.dat.xml.XmlPullScanner
import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.LanguageVocabulary
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.RegionVocabulary
import java.io.Reader

/** DAT-level metadata from `<header>`. */
data class DatHeader(
    val name: String?,
    val description: String?,
    val version: String?,
    val date: String?,
    val author: String?,
    val homepage: String?,
)

/** One `<rom>` as written by the dataset, before any RetroVault interpretation. */
data class DatRomEntry(
    val gameName: String,
    val romName: String,
    /** `null` when the dataset states no size, as `<disk>` entries usually do. */
    val size: Long?,
    val hashes: HashDigests,
    val status: DumpStatus,
    val serial: String?,
    val regions: List<RegionCode>,
    val languages: List<LanguageCode>,
)

/**
 * Something the parser saw.
 *
 * Constitution section 146: the parser must report malformed records,
 * unsupported structures, duplicates and skipped entries rather than silently
 * corrupting the index.
 */
sealed interface DatParseEvent {
    data class Header(val header: DatHeader) : DatParseEvent

    data class Entry(val entry: DatRomEntry) : DatParseEvent

    /** One record could not be read. The rest of the file continues. */
    data class MalformedRecord(val gameName: String?, val romName: String?, val reason: String) :
        DatParseEvent

    /** A structurally valid record RetroVault chose not to index. */
    data class SkippedRecord(val gameName: String?, val reason: String) : DatParseEvent
}

data class DatParseReport(
    val header: DatHeader?,
    val entries: Int,
    val malformed: Int,
    val skipped: Int,
) {
    val total: Int get() = entries + malformed + skipped
}

/** How parsing ended. */
sealed interface DatParseOutcome {
    val report: DatParseReport

    data class Completed(override val report: DatParseReport) : DatParseOutcome

    /**
     * The document itself is broken.
     *
     * Everything already emitted stays valid: a truncated DAT still yields the
     * records that preceded the damage, which is the difference between a
     * partial import and a lost one.
     */
    data class Aborted(
        override val report: DatParseReport,
        val reason: String,
        val characterOffset: Long,
    ) : DatParseOutcome
}

/**
 * Streaming Logiqx DAT parser.
 *
 * ROM_INTELLIGENCE.md section 4: streams large files, never materialises the
 * document, preserves source metadata, and detects malformed records without
 * discarding the rest of the file.
 *
 * The parser produces structured records only. It makes no identity decisions
 * and applies no naming policy - those belong to the domain
 * (ROM_INTELLIGENCE.md section 21).
 */
class LogiqxDatParser(private val limits: XmlLimits = XmlLimits()) {

    /**
     * Parses [reader], invoking [onEvent] for each record as it is read.
     *
     * The reader is not closed here; the caller owns it.
     */
    fun parse(reader: Reader, onEvent: (DatParseEvent) -> Unit): DatParseOutcome {
        val scanner = XmlPullScanner(reader, limits)
        var header: DatHeader? = null
        var entries = 0
        var malformed = 0
        var skipped = 0

        fun report() = DatParseReport(header, entries, malformed, skipped)

        try {
            var event = scanner.next()
            while (event != XmlEvent.EndDocument) {
                if (event is XmlEvent.StartElement) {
                    when (event.name.lowercase()) {
                        "header" -> {
                            header = readHeader(scanner)
                            header?.let { onEvent(DatParseEvent.Header(it)) }
                        }

                        // Logiqx uses <game>; MAME-derived DATs use <machine>.
                        "game", "machine" -> {
                            val game = readGame(scanner, event)
                            game.events.forEach { produced ->
                                when (produced) {
                                    is DatParseEvent.Entry -> entries++
                                    is DatParseEvent.MalformedRecord -> malformed++
                                    is DatParseEvent.SkippedRecord -> skipped++
                                    is DatParseEvent.Header -> Unit
                                }
                                onEvent(produced)
                            }
                        }
                    }
                }
                event = scanner.next()
            }
            return DatParseOutcome.Completed(report())
        } catch (failure: MalformedXmlException) {
            return DatParseOutcome.Aborted(
                report = report(),
                reason = failure.message ?: "malformed XML",
                characterOffset = failure.characterOffset,
            )
        }
    }

    private fun readHeader(scanner: XmlPullScanner): DatHeader? {
        val fields = mutableMapOf<String, String>()
        var current: String? = null
        while (true) {
            when (val event = scanner.next()) {
                is XmlEvent.StartElement -> current = event.name.lowercase()
                is XmlEvent.Text -> current?.let { fields[it] = event.text.trim() }
                is XmlEvent.EndElement -> {
                    if (event.name.equals("header", ignoreCase = true)) {
                        return DatHeader(
                            name = fields["name"],
                            description = fields["description"],
                            version = fields["version"],
                            date = fields["date"],
                            author = fields["author"],
                            homepage = fields["homepage"],
                        )
                    }
                    current = null
                }

                XmlEvent.EndDocument -> return null
            }
        }
    }

    private data class GameResult(val events: List<DatParseEvent>)

    private fun readGame(scanner: XmlPullScanner, start: XmlEvent.StartElement): GameResult {
        val gameName = start.attributes["name"]?.trim().orEmpty()
        val elementName = start.name
        val events = mutableListOf<DatParseEvent>()
        val regions = mutableListOf<RegionCode>()
        val languages = mutableListOf<LanguageCode>()
        val roms = mutableListOf<Map<String, String>>()
        var serial: String? = null
        var currentText: String? = null

        while (true) {
            when (val event = scanner.next()) {
                is XmlEvent.StartElement -> {
                    currentText = event.name.lowercase()
                    when (currentText) {
                        // A <disk> is a CHD-backed dump. It is catalogued the
                        // same way as a <rom>, only without a size.
                        "rom", "disk" -> roms.add(event.attributes)
                        "release" -> {
                            event.attributes["region"]?.let { raw ->
                                RegionVocabulary.parse(raw)?.let(regions::add)
                            }
                            event.attributes["language"]?.let { raw ->
                                LanguageVocabulary.parseList(raw)?.let(languages::addAll)
                            }
                        }
                    }
                }

                is XmlEvent.Text -> if (currentText == "serial") serial = event.text.trim()

                is XmlEvent.EndElement -> {
                    currentText = null
                    if (event.name.equals(elementName, ignoreCase = true)) {
                        events += buildEntries(gameName, roms, serial, regions, languages)
                        return GameResult(events)
                    }
                }

                XmlEvent.EndDocument -> {
                    events += buildEntries(gameName, roms, serial, regions, languages)
                    return GameResult(events)
                }
            }
        }
    }

    private fun buildEntries(
        gameName: String,
        roms: List<Map<String, String>>,
        serial: String?,
        regions: List<RegionCode>,
        languages: List<LanguageCode>,
    ): List<DatParseEvent> {
        if (gameName.isBlank()) {
            return listOf(DatParseEvent.MalformedRecord(null, null, "the set has no name"))
        }
        if (roms.isEmpty()) {
            return listOf(
                DatParseEvent.SkippedRecord(gameName, "the set contains no <rom> element"),
            )
        }
        val seen = mutableSetOf<String>()
        return roms.map { attributes -> toEvent(gameName, attributes, serial, regions, languages, seen) }
    }

    private fun toEvent(
        gameName: String,
        attributes: Map<String, String>,
        serial: String?,
        regions: List<RegionCode>,
        languages: List<LanguageCode>,
        seen: MutableSet<String>,
    ): DatParseEvent {
        val romName = attributes["name"]?.trim()
        if (romName.isNullOrBlank()) {
            return DatParseEvent.MalformedRecord(gameName, null, "the rom has no name")
        }
        if (!seen.add(romName.lowercase())) {
            return DatParseEvent.SkippedRecord(
                gameName,
                "the set lists '$romName' more than once",
            )
        }

        // A malformed hash is dropped rather than fatal: losing one digest is
        // better than losing the entry (SECURITY_SPEC.md section 1). Some DATs
        // spell the CRC attribute `crc32`.
        val hashes = HashDigests.of(
            *listOfNotNull(
                HashValue.parse(HashAlgorithm.CRC32, attributes["crc"] ?: attributes["crc32"]),
                HashValue.parse(HashAlgorithm.MD5, attributes["md5"]),
                HashValue.parse(HashAlgorithm.SHA1, attributes["sha1"]),
            ).toTypedArray(),
        )
        // Size alone is not identity. A record with no hash could only ever be
        // matched on length, which would attach a confident name to any file
        // that happened to be the right number of bytes - the exact failure
        // TESTING_SPEC.md section 1 forbids. It is reported, not indexed.
        if (hashes.isEmpty) {
            return DatParseEvent.SkippedRecord(gameName, "'$romName' carries no hash")
        }

        // Size is corroboration, not identity, so an absent or unreadable one
        // downgrades the record to "size unknown" instead of discarding every
        // hash it carries. `<disk>` entries legitimately omit it.
        val rawSize = attributes["size"]?.trim()
        val size = rawSize?.toLongOrNull()?.takeIf { it >= 0 }

        return DatParseEvent.Entry(
            DatRomEntry(
                gameName = gameName,
                romName = romName,
                size = size,
                hashes = hashes,
                status = parseStatus(attributes["status"]),
                serial = serial?.takeIf { it.isNotBlank() },
                regions = regions.distinct(),
                languages = languages.distinct(),
            ),
        )
    }

    private fun parseStatus(raw: String?): DumpStatus = when (raw?.trim()?.lowercase()) {
        null, "" -> DumpStatus.GOOD
        "good" -> DumpStatus.GOOD
        "verified" -> DumpStatus.VERIFIED
        "baddump" -> DumpStatus.BAD_DUMP
        "nodump" -> DumpStatus.NO_DUMP
        else -> DumpStatus.UNKNOWN
    }

    companion object {
        const val VERSION: String = "logiqx-parser-v1"
    }
}
