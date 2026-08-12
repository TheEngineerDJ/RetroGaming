package com.retrovault.dat

import com.retrovault.application.DatInput
import com.retrovault.application.DatReadEvent
import com.retrovault.application.DatReadReport
import com.retrovault.application.DatReader
import com.retrovault.application.Outcome
import com.retrovault.application.RetroVaultFailure
import com.retrovault.domain.identity.StorageRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Opens a DAT for reading.
 *
 * The platform owns how a [StorageRef] becomes bytes; this module owns how
 * those bytes become records.
 */
fun interface DatByteSource {
    /** @throws IOException when the file cannot be opened. */
    fun open(ref: StorageRef): InputStream
}

/**
 * Adapts the streaming Logiqx parser to the application's [DatReader] port.
 *
 * Parsing runs on an I/O dispatcher and checks for cancellation between
 * records, so importing a very large DAT stays cancellable
 * (ENGINEERING_SPEC.md section 9).
 */
class LogiqxDatReader(
    private val byteSource: DatByteSource,
    private val parser: LogiqxDatParser = LogiqxDatParser(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DatReader {

    override suspend fun read(
        input: DatInput,
        onEvent: suspend (DatReadEvent) -> Unit,
    ): Outcome<DatReadReport> = withContext(dispatcher) {
        val stream = try {
            byteSource.open(input.ref)
        } catch (failure: SecurityException) {
            return@withContext Outcome.failure(RetroVaultFailure.PermissionDenied(input.ref))
        } catch (failure: IOException) {
            return@withContext Outcome.failure(
                RetroVaultFailure.InvalidDat(failure.message ?: "the file could not be opened"),
            )
        }

        try {
            stream.buffered().use { bytes ->
                classifyFormat(bytes)?.let { return@withContext Outcome.failure(it) }
                InputStreamReader(bytes, Charsets.UTF_8).buffered().use { reader ->
                    Outcome.success(parseInto(reader, onEvent))
                }
            }
        } catch (failure: IOException) {
            Outcome.failure(RetroVaultFailure.InvalidDat(failure.message ?: "the file could not be read"))
        }
    }

    /**
     * Names the most common wrong-file mistake before the XML scanner does.
     *
     * A ClrMamePro DAT is plain text, so handing it to an XML parser produces
     * "unexpected character at offset 0", which tells the user nothing about
     * what to do next. Peeking at the first bytes turns that into an
     * instruction (UX_SPEC.md section 12: a failure must say what happened and
     * what the user can do).
     *
     * @return the failure to report, or `null` when the file may be XML.
     */
    private fun classifyFormat(input: InputStream): RetroVaultFailure? {
        input.mark(PROBE_BYTES)
        val probe = ByteArray(PROBE_BYTES)
        val read = input.readNBytes(probe, 0, PROBE_BYTES)
        input.reset()

        if (read <= 0) return RetroVaultFailure.InvalidDat("the file is empty")
        // A UTF-16 document is not single-byte text; let the parser judge it.
        if (probe.startsWith(UTF16_LE_BOM, read) || probe.startsWith(UTF16_BE_BOM, read)) return null
        val offset = if (probe.startsWith(UTF8_BOM, read)) UTF8_BOM.size else 0

        val head = String(probe, offset, read - offset, Charsets.ISO_8859_1).trimStart()
        if (head.startsWith("<")) return null
        if (head.startsWith("clrmamepro", ignoreCase = true) || head.startsWith("game (")) {
            return RetroVaultFailure.InvalidDat(
                "this is a ClrMamePro-format DAT; RetroVault reads the XML (Logiqx) form, " +
                    "which No-Intro and Redump also publish",
            )
        }
        return RetroVaultFailure.InvalidDat("the file is not an XML DAT")
    }

    private fun ByteArray.startsWith(prefix: ByteArray, available: Int): Boolean =
        available >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private suspend fun parseInto(reader: Reader, onEvent: suspend (DatReadEvent) -> Unit): DatReadReport {
        val context = currentCoroutineContext()
        // The parser is a synchronous pull loop by design (it must be usable
        // without coroutines). Bridging here keeps that property while still
        // honouring cancellation and suspending consumers.
        val outcome = parser.parse(reader) { event ->
            context.ensureActive()
            runBlocking(context) { onEvent(event.toApplicationEvent()) }
        }
        return when (outcome) {
            is DatParseOutcome.Completed -> DatReadReport(
                entries = outcome.report.entries,
                malformed = outcome.report.malformed,
                skipped = outcome.report.skipped,
            )

            is DatParseOutcome.Aborted -> DatReadReport(
                entries = outcome.report.entries,
                malformed = outcome.report.malformed,
                skipped = outcome.report.skipped,
                aborted = true,
                abortReason = outcome.reason,
                abortCharacterOffset = outcome.characterOffset,
            )
        }
    }

    private fun DatParseEvent.toApplicationEvent(): DatReadEvent = when (this) {
        is DatParseEvent.Header -> DatReadEvent.Metadata(
            name = header.name,
            description = header.description,
            version = header.version,
            date = header.date,
            author = header.author,
        )

        is DatParseEvent.Entry -> DatReadEvent.Record(
            setName = entry.gameName,
            romName = entry.romName,
            size = entry.size,
            hashes = entry.hashes,
            status = entry.status,
            regions = entry.regions,
            languages = entry.languages,
            serial = entry.serial,
        )

        is DatParseEvent.MalformedRecord -> DatReadEvent.Malformed(gameName, romName, reason)

        is DatParseEvent.SkippedRecord -> DatReadEvent.Skipped(gameName, reason)
    }

    private companion object {
        const val PROBE_BYTES = 64

        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}
