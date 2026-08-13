package com.retrovault.application

import com.retrovault.domain.catalog.DatSourceRef
import com.retrovault.domain.catalog.DumpRecord
import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DatasetKindVocabulary
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.StorageRef

/** A DAT file the user chose to import. */
data class DatInput(
    val ref: StorageRef,
    val displayName: String,
    /** Provider namespace, e.g. `no_intro`. Supplied by the user or inferred. */
    val provider: String,
)

/** What a reader found, in source terms. */
sealed interface DatReadEvent {
    data class Metadata(
        val name: String?,
        val description: String?,
        val version: String?,
        val date: String?,
        val author: String?,
    ) : DatReadEvent

    data class Record(
        val setName: String,
        val romName: String,
        /** `null` when the dataset states no size for this entry. */
        val size: Long?,
        val hashes: HashDigests,
        val status: DumpStatus,
        val regions: List<RegionCode>,
        val languages: List<LanguageCode>,
        val serial: String?,
    ) : DatReadEvent

    data class Malformed(val setName: String?, val romName: String?, val reason: String) : DatReadEvent

    data class Skipped(val setName: String?, val reason: String) : DatReadEvent
}

data class DatReadReport(
    val entries: Int,
    val malformed: Int,
    val skipped: Int,
    val aborted: Boolean = false,
    val abortReason: String? = null,
    val abortCharacterOffset: Long? = null,
)

/**
 * Reads a DAT file into source-level events.
 *
 * The reader is a port so that the parser stays infrastructure
 * (Constitution section 146) and so that the use case can be tested without a
 * file.
 */
interface DatReader {
    suspend fun read(input: DatInput, onEvent: suspend (DatReadEvent) -> Unit): Outcome<DatReadReport>
}

/** The outcome of one import, including what could not be read. */
data class DatImportResult(
    val source: DatSourceRef,
    val report: DatReadReport,
    /** A bounded sample of problems, for the user to see without a log file. */
    val problems: List<String>,
)

/**
 * Imports a DAT into the local catalogue.
 *
 * Constitution section 81: imported data preserves originating dataset,
 * external identifier, import date and conflicts, and bulk import never
 * bypasses the trust architecture. Constitution section 184: the version is
 * kept so a past decision stays explainable after the dataset changes.
 *
 * The whole import is transactional: a DAT that turns out to be damaged
 * half-way through does not leave a half-indexed catalogue
 * (DATABASE.md section 16).
 */
class ImportDatUseCase(
    private val reader: DatReader,
    private val writer: DatCatalogWriter,
    private val clock: Clock,
    private val batchSize: Int = 500,
    private val maxReportedProblems: Int = 50,
) {
    suspend fun import(input: DatInput): Outcome<DatImportResult> {
        var metadata: DatReadEvent.Metadata? = null
        var source: DatSourceRef? = null
        var sourceId: DatSourceId? = null
        val pending = mutableListOf<DumpRecord>()
        val problems = mutableListOf<String>()
        var failure: RetroVaultFailure? = null
        var sequence = 0

        suspend fun ensureSource(): DatSourceId? {
            if (sourceId != null) return sourceId
            val resolved = buildSource(input, metadata)
            source = resolved
            return when (val begun = writer.beginImport(resolved)) {
                is Outcome.Success -> begun.value.also { sourceId = it }
                is Outcome.Failure -> {
                    failure = begun.failure
                    null
                }
            }
        }

        suspend fun flush(): Boolean {
            if (pending.isEmpty()) return true
            val id = ensureSource() ?: return false
            return when (val written = writer.writeBatch(id, pending.toList())) {
                is Outcome.Success -> {
                    pending.clear()
                    true
                }

                is Outcome.Failure -> {
                    failure = written.failure
                    false
                }
            }
        }

        val readOutcome = reader.read(input) { event ->
            if (failure != null) return@read
            when (event) {
                is DatReadEvent.Metadata -> metadata = event

                is DatReadEvent.Record -> {
                    val currentSource = source ?: buildSource(input, metadata).also { source = it }
                    pending += toRecord(currentSource, event, sequence++)
                    if (pending.size >= batchSize) flush()
                }

                is DatReadEvent.Malformed -> if (problems.size < maxReportedProblems) {
                    problems += "Skipped '${event.setName ?: "unnamed set"}': ${event.reason}"
                }

                is DatReadEvent.Skipped -> if (problems.size < maxReportedProblems) {
                    problems += "Ignored '${event.setName ?: "unnamed set"}': ${event.reason}"
                }
            }
        }

        val report = when (readOutcome) {
            is Outcome.Success -> readOutcome.value
            is Outcome.Failure -> {
                sourceId?.let { writer.rollbackImport(it) }
                return readOutcome
            }
        }

        if (failure == null) flush()

        failure?.let { persistenceFailure ->
            sourceId?.let { writer.rollbackImport(it) }
            return Outcome.failure(persistenceFailure)
        }

        val id = sourceId ?: ensureSource()
        if (id == null) {
            return Outcome.failure(
                failure ?: RetroVaultFailure.PersistenceFailure("the dataset could not be registered"),
            )
        }
        when (val committed = writer.commitImport(id)) {
            is Outcome.Failure -> return committed
            is Outcome.Success -> Unit
        }

        val resolvedSource = source ?: buildSource(input, metadata)
        val allProblems = buildList {
            addAll(problems)
            if (report.aborted) {
                add(
                    "The file ended unexpectedly: ${report.abortReason}. " +
                        "${report.entries} record(s) read before that point were imported.",
                )
            }
        }
        return Outcome.success(DatImportResult(resolvedSource, report, allProblems))
    }

    private fun buildSource(input: DatInput, metadata: DatReadEvent.Metadata?): DatSourceRef {
        val setName = metadata?.name?.takeIf { it.isNotBlank() } ?: input.displayName
        val version = metadata?.version?.takeIf { it.isNotBlank() }
            ?: metadata?.date?.takeIf { it.isNotBlank() }
        // Read from what the DAT says about itself. It is provenance the user
        // can see and disagree with, and it never restricts what is consulted -
        // coverage is measured from the records themselves.
        val kind = DatasetKindVocabulary.infer(
            provider = input.provider,
            setName = setName,
            author = metadata?.author,
            homepage = metadata?.description,
        )
        return DatSourceRef(
            id = DatSourceId("${input.provider}:$setName:${version ?: "unversioned"}"),
            provider = input.provider,
            setName = setName,
            version = version,
            platform = PlatformName(setName),
            importedAtEpochMillis = clock.nowEpochMillis(),
            kind = kind,
        )
    }

    private fun toRecord(source: DatSourceRef, event: DatReadEvent.Record, sequence: Int): DumpRecord =
        DumpRecord.derive(
            // Deterministic and namespaced: re-importing the same dataset
            // produces the same identifiers (DATABASE.md section 3).
            id = DumpRecordId("${source.id.value}#$sequence"),
            source = source,
            setName = event.setName,
            romName = event.romName,
            size = event.size,
            hashes = event.hashes,
            status = event.status,
            externalId = "${source.provider}:rom:${event.setName}/${event.romName}",
            declaredRegions = event.regions,
            declaredLanguages = event.languages,
        )
}
