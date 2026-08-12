package com.retrovault.dat

import com.retrovault.application.DatInput
import com.retrovault.application.DatReadEvent
import com.retrovault.application.Outcome
import com.retrovault.application.RetroVaultFailure
import com.retrovault.domain.identity.StorageRef
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reader's job is to name what went wrong before the XML scanner turns it
 * into an offset. UX_SPEC.md section 13: a failure must say what happened and
 * what the user can do about it.
 */
class LogiqxDatReaderTest {

    private fun readerFor(bytes: ByteArray) =
        LogiqxDatReader(byteSource = { ByteArrayInputStream(bytes) })

    private suspend fun read(text: String, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        readAll(text.toByteArray(charset))

    private suspend fun readAll(bytes: ByteArray): Pair<Outcome<*>, List<DatReadEvent>> {
        val events = mutableListOf<DatReadEvent>()
        val outcome = readerFor(bytes).read(
            DatInput(StorageRef("test://dat"), "test.dat", "no_intro"),
        ) { events += it }
        return outcome to events
    }

    @Test
    fun `a clrmamepro dat is named, not reported as broken xml`() = runTest {
        val clrMamePro = """
            clrmamepro (
                name "Test Platform"
                description "Test Platform"
            )

            game (
                name "Some Game (USA)"
                rom ( name "Some Game (USA).sfc" size 10 crc 00000001 )
            )
        """.trimIndent()

        val failure = assertIs<Outcome.Failure>(read(clrMamePro).first)
        val invalid = assertIs<RetroVaultFailure.InvalidDat>(failure.failure)

        assertTrue(
            invalid.detail.contains("ClrMamePro") && invalid.detail.contains("Logiqx"),
            "The message must name the format and the fix: ${invalid.detail}",
        )
    }

    @Test
    fun `a dat that starts with a game block is recognised too`() = runTest {
        val failure = assertIs<Outcome.Failure>(read("game (\n  name \"X\"\n)\n").first)

        assertTrue(assertIs<RetroVaultFailure.InvalidDat>(failure.failure).detail.contains("ClrMamePro"))
    }

    @Test
    fun `an empty file says so`() = runTest {
        val failure = assertIs<Outcome.Failure>(read("").first)

        assertEquals("the file is empty", assertIs<RetroVaultFailure.InvalidDat>(failure.failure).detail)
    }

    @Test
    fun `an unrelated text file is refused before parsing`() = runTest {
        val failure = assertIs<Outcome.Failure>(read("just some notes I wrote").first)

        assertEquals(
            "the file is not an XML DAT",
            assertIs<RetroVaultFailure.InvalidDat>(failure.failure).detail,
        )
    }

    @Test
    fun `a byte order mark does not hide the xml prologue`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val xml = """<datafile><game name="X (USA)"><rom name="x.sfc" size="1" crc="00000001"/></game></datafile>"""

        val (outcome, events) = readAll(bom + xml.toByteArray(Charsets.UTF_8))

        assertIs<Outcome.Success<*>>(outcome)
        assertEquals(1, events.filterIsInstance<DatReadEvent.Record>().size)
    }

    @Test
    fun `a well formed dat is read through to records`() = runTest {
        val xml = """
            <datafile>
              <header><name>Test Platform</name></header>
              <game name="Some Game (USA)">
                <rom name="Some Game (USA).sfc" size="10" crc="00000001"/>
              </game>
            </datafile>
        """.trimIndent()

        val (outcome, events) = read(xml)

        assertIs<Outcome.Success<*>>(outcome)
        val record = events.filterIsInstance<DatReadEvent.Record>().single()
        assertEquals("Some Game (USA).sfc", record.romName)
        assertEquals(10L, record.size)
    }

    @Test
    fun `a record with no size reaches the importer as an unknown size`() = runTest {
        val xml = """
            <datafile>
              <game name="Arcade Thing (USA)"><disk name="arcadething" sha1="${"5".repeat(40)}"/></game>
            </datafile>
        """.trimIndent()

        val (outcome, events) = read(xml)

        assertIs<Outcome.Success<*>>(outcome)
        assertEquals(null, events.filterIsInstance<DatReadEvent.Record>().single().size)
    }
}
