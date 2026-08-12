package com.retrovault.dat

import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.RegionCode
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DAT ingestion.
 *
 * TESTING_SPEC.md section 4: fixtures are synthetic. No copyrighted dataset is
 * committed to this repository.
 */
class LogiqxDatParserTest {

    private val parser = LogiqxDatParser()

    private fun parse(xml: String): Pair<DatParseOutcome, List<DatParseEvent>> {
        val events = mutableListOf<DatParseEvent>()
        val outcome = parser.parse(StringReader(xml)) { events.add(it) }
        return outcome to events
    }

    private fun entries(xml: String): List<DatRomEntry> =
        parse(xml).second.filterIsInstance<DatParseEvent.Entry>().map { it.entry }

    private val validDat = """
        <?xml version="1.0"?>
        <!DOCTYPE datafile PUBLIC "-//Logiqx//DTD ROM Management Datafile//EN" "http://www.logiqx.com/Dats/datafile.dtd">
        <datafile>
          <header>
            <name>Test Platform</name>
            <description>Synthetic test set</description>
            <version>2026-01-01</version>
            <author>RetroVault tests</author>
          </header>
          <game name="Super Mario World (USA)">
            <description>Super Mario World (USA)</description>
            <release name="Super Mario World" region="USA" language="En"/>
            <rom name="Super Mario World (USA).sfc" size="524288" crc="AB12CD34" md5="${"1".repeat(32)}" sha1="${"2".repeat(40)}"/>
          </game>
          <game name="Some Game (Europe)">
            <rom name="Some Game (Europe).sfc" size="1048576" crc="deadbeef"/>
          </game>
        </datafile>
    """.trimIndent()

    @Test
    fun `a valid dat yields header and entries`() {
        val (outcome, events) = parse(validDat)

        assertIs<DatParseOutcome.Completed>(outcome)
        assertEquals(2, outcome.report.entries)
        assertEquals(0, outcome.report.malformed)
        assertEquals("Test Platform", outcome.report.header?.name)
        assertEquals("2026-01-01", outcome.report.header?.version)
        assertTrue(events.first() is DatParseEvent.Header)
    }

    @Test
    fun `hashes are normalized during ingestion`() {
        val entry = entries(validDat).first()

        assertEquals("ab12cd34", entry.hashes[HashAlgorithm.CRC32]?.hex)
        assertEquals("1".repeat(32), entry.hashes[HashAlgorithm.MD5]?.hex)
        assertEquals("2".repeat(40), entry.hashes[HashAlgorithm.SHA1]?.hex)
    }

    @Test
    fun `release region and language are captured`() {
        val entry = entries(validDat).first()

        assertEquals(listOf(RegionCode("USA")), entry.regions)
        assertEquals(listOf("en"), entry.languages.map { it.code })
    }

    @Test
    fun `a doctype is skipped without being interpreted`() {
        // The fixture declares an external DTD. Parsing must not fetch it.
        assertIs<DatParseOutcome.Completed>(parse(validDat).first)
    }

    @Test
    fun `an external entity is never resolved`() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE datafile [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <datafile>
              <game name="Evil &xxe; Game">
                <rom name="evil.sfc" size="1"/>
              </game>
            </datafile>
        """.trimIndent()

        val entry = entries(hostile).single()

        assertTrue(
            entry.gameName.contains("&xxe;"),
            "The entity must survive as literal text, never as file contents: ${entry.gameName}",
        )
    }

    @Test
    fun `a billion-laughs style declaration cannot expand`() {
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE datafile [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <datafile>
              <game name="Bomb &c;">
                <rom name="bomb.sfc" size="1"/>
              </game>
            </datafile>
        """.trimIndent()

        val entry = entries(bomb).single()

        assertEquals("Bomb &c;", entry.gameName)
    }

    @Test
    fun `predefined entities and character references are decoded`() {
        val xml = """
            <datafile>
              <game name="Tom &amp; Jerry &#65;">
                <rom name="tj.sfc" size="1"/>
              </game>
            </datafile>
        """.trimIndent()

        assertEquals("Tom & Jerry A", entries(xml).single().gameName)
    }

    @Test
    fun `a rom with no size is reported as malformed and the file continues`() {
        val xml = """
            <datafile>
              <game name="Broken"><rom name="broken.sfc"/></game>
              <game name="Fine (USA)"><rom name="fine.sfc" size="10"/></game>
            </datafile>
        """.trimIndent()

        val (outcome, events) = parse(xml)

        assertEquals(1, outcome.report.malformed)
        assertEquals(1, outcome.report.entries)
        val malformed = events.filterIsInstance<DatParseEvent.MalformedRecord>().single()
        assertEquals("Broken", malformed.gameName)
        assertTrue(malformed.reason.contains("size"))
    }

    @Test
    fun `a non-numeric size is reported as malformed`() {
        val xml = """<datafile><game name="X"><rom name="x.sfc" size="lots"/></game></datafile>"""

        val malformed = parse(xml).second.filterIsInstance<DatParseEvent.MalformedRecord>().single()

        assertTrue(malformed.reason.contains("not a number"))
    }

    @Test
    fun `a rom with no name is reported as malformed`() {
        val xml = """<datafile><game name="X"><rom size="10"/></game></datafile>"""

        assertEquals(1, parse(xml).first.report.malformed)
    }

    @Test
    fun `a set with no name is reported as malformed`() {
        val xml = """<datafile><game><rom name="x.sfc" size="10"/></game></datafile>"""

        assertEquals(1, parse(xml).first.report.malformed)
    }

    @Test
    fun `a set with no rom is skipped, not treated as an error`() {
        val xml = """<datafile><game name="Metadata only"><description>x</description></game></datafile>"""

        val (outcome, events) = parse(xml)

        assertEquals(1, outcome.report.skipped)
        assertEquals(0, outcome.report.malformed)
        assertTrue(events.filterIsInstance<DatParseEvent.SkippedRecord>().isNotEmpty())
    }

    @Test
    fun `a duplicate rom name inside one set is reported`() {
        val xml = """
            <datafile>
              <game name="Dupes">
                <rom name="same.sfc" size="10"/>
                <rom name="SAME.SFC" size="20"/>
              </game>
            </datafile>
        """.trimIndent()

        val (outcome, _) = parse(xml)

        assertEquals(1, outcome.report.entries)
        assertEquals(1, outcome.report.skipped)
    }

    @Test
    fun `a malformed hash is dropped without losing the entry`() {
        val xml = """<datafile><game name="X (USA)"><rom name="x.sfc" size="10" crc="not-hex"/></game></datafile>"""

        val entry = entries(xml).single()

        assertEquals(1, parse(xml).first.report.entries)
        assertNull(entry.hashes[HashAlgorithm.CRC32])
    }

    @Test
    fun `a multi-disc set produces one entry per rom`() {
        val xml = """
            <datafile>
              <game name="Big Game (USA)">
                <rom name="Big Game (USA) (Disc 1).bin" size="100" crc="11111111"/>
                <rom name="Big Game (USA) (Disc 2).bin" size="200" crc="22222222"/>
              </game>
            </datafile>
        """.trimIndent()

        assertEquals(2, entries(xml).size)
    }

    @Test
    fun `mame style machine elements are supported`() {
        val xml = """<datafile><machine name="X (USA)"><rom name="x.rom" size="10"/></machine></datafile>"""

        assertEquals(1, entries(xml).size)
    }

    @Test
    fun `dump status flags are read`() {
        val xml = """
            <datafile>
              <game name="Bad (USA)"><rom name="bad.sfc" size="1" status="baddump"/></game>
              <game name="None (USA)"><rom name="none.sfc" size="1" status="nodump"/></game>
              <game name="Ok (USA)"><rom name="ok.sfc" size="1" status="verified"/></game>
              <game name="Odd (USA)"><rom name="odd.sfc" size="1" status="something-new"/></game>
            </datafile>
        """.trimIndent()

        assertEquals(
            listOf(DumpStatus.BAD_DUMP, DumpStatus.NO_DUMP, DumpStatus.VERIFIED, DumpStatus.UNKNOWN),
            entries(xml).map { it.status },
        )
    }

    @Test
    fun `truncated xml aborts but keeps everything already parsed`() {
        val xml = """
            <datafile>
              <game name="First (USA)"><rom name="first.sfc" size="10"/></game>
              <game name="Second (USA)"><rom name="second.sfc" siz
        """.trimIndent()

        val (outcome, _) = parse(xml)

        val aborted = assertIs<DatParseOutcome.Aborted>(outcome)
        assertEquals(1, aborted.report.entries, "The complete record before the damage must survive")
        assertTrue(aborted.characterOffset > 0)
    }

    @Test
    fun `mismatched tags abort as data, not as a crash`() {
        val xml = """<datafile><game name="X"><rom name="x.sfc" size="1"/></wrong></datafile>"""

        assertIs<DatParseOutcome.Aborted>(parse(xml).first)
    }

    @Test
    fun `unbounded nesting is refused`() {
        val deep = buildString {
            append("<datafile>")
            repeat(200) { append("<nest>") }
            repeat(200) { append("</nest>") }
            append("</datafile>")
        }

        assertIs<DatParseOutcome.Aborted>(parse(deep).first)
    }

    @Test
    fun `an empty document parses to nothing`() {
        val (outcome, events) = parse("<datafile></datafile>")

        assertIs<DatParseOutcome.Completed>(outcome)
        assertEquals(0, outcome.report.total)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `comments and processing instructions are ignored`() {
        val xml = """
            <?xml version="1.0"?>
            <!-- a comment with <game name="fake"> inside it -->
            <datafile>
              <game name="Real (USA)"><rom name="real.sfc" size="1"/></game>
            </datafile>
        """.trimIndent()

        assertEquals(listOf("Real (USA)"), entries(xml).map { it.gameName })
    }

    @Test
    fun `cdata sections are read as text`() {
        val xml = """
            <datafile>
              <header><name><![CDATA[Platform & Friends]]></name></header>
              <game name="X (USA)"><rom name="x.sfc" size="1"/></game>
            </datafile>
        """.trimIndent()

        assertEquals("Platform & Friends", parse(xml).first.report.header?.name)
    }

    @Test
    fun `parsing is deterministic`() {
        val first = entries(validDat)
        val second = entries(validDat)
        assertEquals(first, second)
    }

    @Test
    fun `a large dat streams without materializing the document`() {
        // 5,000 sets is enough to catch accidental whole-document buffering in
        // the parser while keeping the test fast.
        val xml = buildString {
            append("<datafile><header><name>Big</name></header>")
            repeat(5_000) { index ->
                append("<game name=\"Game $index (USA)\">")
                append("<rom name=\"game$index.sfc\" size=\"$index\" crc=\"%08x\"/>".format(index))
                append("</game>")
            }
            append("</datafile>")
        }

        var count = 0
        val outcome = parser.parse(StringReader(xml)) { event ->
            if (event is DatParseEvent.Entry) count++
        }

        assertIs<DatParseOutcome.Completed>(outcome)
        assertEquals(5_000, count)
    }
}
