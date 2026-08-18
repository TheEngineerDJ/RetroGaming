package com.retrovault.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrovault.application.DatInput
import com.retrovault.application.Outcome
import com.retrovault.application.RenameHistoryEntry
import com.retrovault.application.RenamePreview
import com.retrovault.application.ReviewSubject
import com.retrovault.application.WorkDetail
import com.retrovault.application.WorkSummary
import com.retrovault.application.ResolvedObservation
import com.retrovault.application.ScanEvent
import com.retrovault.application.LibraryStatus
import com.retrovault.application.LibraryStatusCounts
import com.retrovault.application.ScanSummary
import com.retrovault.application.StorageLocation
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.identity.WorkId
import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.rename.PlanVerdict
import com.retrovault.domain.rename.RenamePlan
import com.retrovault.domain.resolution.ConfidenceLevel
import com.retrovault.domain.resolution.IdentityBasis
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the workflow currently is (UX_SPEC.md section 2). */
enum class WorkflowPhase { IDLE, SCANNING, SCANNED, PREVIEWING, EXECUTING, FINISHED }

/**
 * One scanned file, ready to display.
 *
 * Everything here is already decided by the domain. The view model computes no
 * identity, confidence or name of its own.
 */
data class ResultRow(
    val observationId: ObservationId,
    val filename: String,
    val relativePath: String,
    val matchType: String,
    val confidence: ConfidenceLevel,
    /** What the identity rests on. Never collapsed into the confidence. */
    val identityBasis: IdentityBasis,
    val status: LibraryStatus,
    val identity: String?,
    val reasons: List<String>,
    val candidates: List<String>,
    val reviewable: Boolean,
)

/** One file the scan could not read at all. */
data class ScanIssue(val relativePath: String, val message: String)

/** Where the user is. Four places, no deeper stack than a detail view. */
enum class Destination { LIBRARY, FILES, ACTIVITY, SETUP }

/** One rename batch, ready to display in the history. */
data class HistoryRow(
    val batchId: RenameBatchId,
    val createdAtEpochMillis: Long,
    val planned: Int,
    val completed: Int,
    val failed: Int,
    val restored: Int,
    val dryRun: Boolean,
    val undoable: Boolean,
    val renames: List<String>,
)

data class ScannerUiState(
    val rootDisplayName: String? = null,
    val importedDatSets: List<String> = emptyList(),
    val phase: WorkflowPhase = WorkflowPhase.IDLE,
    val summary: ScanSummary = ScanSummary(),
    val currentActivity: String = "",
    val results: List<ResultRow> = emptyList(),
    val confirmed: Set<ObservationId> = emptySet(),
    val preview: RenamePreview? = null,
    val executionReport: String? = null,
    val notices: List<String> = emptyList(),
    /** The file currently open for review, if any. */
    val review: ReviewSubject? = null,
    val reviewBusy: Boolean = false,
    val history: List<HistoryRow> = emptyList(),
    val librarySearch: String = "",
    val libraryWorks: List<WorkSummary> = emptyList(),
    val openWork: WorkDetail? = null,
    val destination: Destination = Destination.LIBRARY,
    /** Files the scan could not read. Never folded into a status pile. */
    val errors: List<ScanIssue> = emptyList(),
    val statusCounts: LibraryStatusCounts = LibraryStatusCounts(),
    /** `null` shows every file, including the ones nothing can be done about. */
    val statusFilter: LibraryStatus? = null,
) {
    val canScan: Boolean get() = rootDisplayName != null && phase != WorkflowPhase.SCANNING
    val canPreview: Boolean get() = phase == WorkflowPhase.SCANNED || phase == WorkflowPhase.PREVIEWING
    val canExecute: Boolean get() = preview?.executable == true && phase == WorkflowPhase.PREVIEWING

    /**
     * Whether RetroVault has what it needs to identify anything.
     *
     * Until both exist the library cannot fill, so setup leads. Afterwards it
     * steps out of the way and stays reachable.
     */
    val isSetUp: Boolean get() = rootDisplayName != null && importedDatSets.isNotEmpty()

    val hasScanned: Boolean get() = results.isNotEmpty() || errors.isNotEmpty()

    /** The files the current filter admits. A filter narrows; it never hides. */
    val visibleResults: List<ResultRow>
        get() = statusFilter?.let { wanted -> results.filter { it.status == wanted } } ?: results

    val needingAttention: Int get() = statusCounts.needingAttention
}

/**
 * Holds workflow state and calls use cases.
 *
 * ENGINEERING_SPEC.md section 18 forbids business logic in view models. This
 * class starts and cancels work, maps results into display rows and records
 * which files the user confirmed. Every decision it shows was made in the
 * domain.
 */
class ScannerViewModel(private val container: RetroVaultContainer) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var sessionId: ScanSessionId? = null
    private var plan: RenamePlan? = null
    private var root: StorageLocation? = null

    init {
        // Settle anything an earlier run left half-done before the user acts.
        viewModelScope.launch {
            val outcome = container.reconcileInterruptedRenames.reconcile()
            if (outcome is Outcome.Success && outcome.value.isNotEmpty()) {
                notice(
                    "${outcome.value.size} rename(s) from a previous session were interrupted and have " +
                        "been reconciled. See history for what happened to each.",
                )
            }
        }
    }

    /**
     * Reports a storage problem the platform layer detected.
     *
     * Exists so a permission failure reaches the user through the same channel
     * as every other notice instead of being swallowed at the Activity, where
     * nothing can see it.
     */
    fun reportStorageProblem(message: String) = notice(message)

    fun onFolderSelected(ref: StorageRef, displayName: String) {
        root = StorageLocation(ref, displayName)
        _state.update { it.copy(rootDisplayName = displayName, phase = WorkflowPhase.IDLE) }
    }

    fun onDatSelected(ref: StorageRef, displayName: String, provider: String = "no_intro") {
        viewModelScope.launch {
            _state.update { it.copy(currentActivity = "Reading $displayName") }
            when (val outcome = container.importDat.import(DatInput(ref, displayName, provider))) {
                is Outcome.Success -> {
                    val result = outcome.value
                    _state.update { current ->
                        current.copy(
                            importedDatSets = current.importedDatSets + result.source.setName,
                            currentActivity = "",
                            notices = current.notices +
                                "Imported ${result.report.entries} record(s) from ${result.source.setName}." +
                                if (result.problems.isEmpty()) "" else " ${result.problems.size} problem(s).",
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(currentActivity = "", notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    fun startScan() {
        val location = root ?: return
        scanJob?.cancel()
        _state.update {
            it.copy(
                phase = WorkflowPhase.SCANNING,
                results = emptyList(),
                summary = ScanSummary(),
                statusCounts = LibraryStatusCounts(),
                errors = emptyList(),
                statusFilter = null,
                preview = null,
                executionReport = null,
                confirmed = emptySet(),
                destination = Destination.FILES,
            )
        }
        scanJob = viewModelScope.launch {
            container.scanLocation.scan(location).collect { event -> onScanEvent(event) }
        }
    }

    /** Cooperative: the flow is cancelled and the session is closed out safely. */
    fun cancelScan() {
        scanJob?.cancel()
    }

    // ------------------------------------------------------------------
    // Reviewing one file (Constitution section 218)
    // ------------------------------------------------------------------

    fun openReview(observationId: ObservationId) {
        val session = sessionId ?: return
        viewModelScope.launch {
            _state.update { it.copy(reviewBusy = true) }
            when (val outcome = container.reviewObservation.subject(session, observationId)) {
                is Outcome.Success -> _state.update { it.copy(review = outcome.value, reviewBusy = false) }
                is Outcome.Failure -> _state.update {
                    it.copy(reviewBusy = false, notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    fun closeReview() = _state.update { it.copy(review = null) }

    /** Records that the file is the release one of its candidates describes. */
    fun correctTo(recordId: DumpRecordId, reason: String?) =
        applyDecision { session, observationId ->
            container.reviewObservation.correctToCandidate(session, observationId, recordId, reason)
        }

    /** Records that none of the candidates is right. */
    fun rejectIdentity(reason: String?) =
        applyDecision { session, observationId ->
            container.reviewObservation.reject(session, observationId, reason)
        }

    fun withdrawCorrection() =
        applyDecision { session, observationId ->
            container.reviewObservation.withdraw(session, observationId)
        }

    /**
     * Runs one decision and refreshes what the screen shows.
     *
     * A correction changes what a rescan will conclude, not what the current
     * scan concluded - the results on screen were produced before the user
     * disagreed. Saying so is more honest than silently re-labelling a row and
     * implying RetroVault re-identified anything.
     */
    private fun applyDecision(
        decision: suspend (ScanSessionId, ObservationId) -> Outcome<*>,
    ) {
        val session = sessionId ?: return
        val observationId = _state.value.review?.observationId ?: return
        viewModelScope.launch {
            _state.update { it.copy(reviewBusy = true) }
            when (val outcome = decision(session, observationId)) {
                is Outcome.Success -> {
                    val refreshed = container.reviewObservation.subject(session, observationId)
                    _state.update { current ->
                        current.copy(
                            reviewBusy = false,
                            review = (refreshed as? Outcome.Success)?.value ?: current.review,
                            notices = current.notices +
                                "Saved. Scan again to identify this file with your correction applied.",
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(reviewBusy = false, notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // History and undo (Constitution section 170 and section 233)
    // ------------------------------------------------------------------

    /**
     * Moves to a destination and loads what it needs.
     *
     * Loading on arrival rather than eagerly: the library and the history are
     * both database reads, and doing them on every scan event would be work
     * nobody asked for.
     */
    fun navigate(destination: Destination) {
        _state.update { it.copy(destination = destination, openWork = null) }
        when (destination) {
            Destination.LIBRARY -> refreshLibrary(_state.value.librarySearch)
            Destination.ACTIVITY -> refreshHistory()
            Destination.FILES, Destination.SETUP -> Unit
        }
    }

    /** Narrows the file list to one pile, or clears the filter when re-tapped. */
    fun filterBy(status: LibraryStatus?) = _state.update {
        it.copy(statusFilter = if (it.statusFilter == status) null else status)
    }

    /** Jumps to the files needing attention, which is what a summary tile is for. */
    fun showFiles(status: LibraryStatus?) {
        _state.update { it.copy(destination = Destination.FILES, statusFilter = status) }
    }

    private fun refreshHistory() {
        viewModelScope.launch {
            when (val outcome = container.renameHistory.recent()) {
                is Outcome.Success -> _state.update { current ->
                    current.copy(history = outcome.value.map { it.toRow() })
                }

                is Outcome.Failure -> _state.update {
                    it.copy(notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    /** Puts a batch back, or explains precisely why it will not. */
    fun undoBatch(batchId: RenameBatchId) {
        viewModelScope.launch {
            _state.update { it.copy(currentActivity = "Putting files back") }
            when (val outcome = container.undoRenames.undo(batchId)) {
                is Outcome.Success -> {
                    val result = outcome.value
                    val message = when {
                        result.isFullySuccessful ->
                            "Put ${result.restored} file(s) back to the names they had before."

                        result.plan.hasNothingToDo -> "That batch has nothing left to put back."

                        // Every refusal is named. Section 250: failure is data,
                        // and "it did not work" is not data.
                        else -> "Nothing was changed. " +
                            result.plan.issues.joinToString(" ") { it.message }
                    }
                    _state.update { it.copy(currentActivity = "", notices = it.notices + message) }
                    refreshHistory()
                }

                is Outcome.Failure -> _state.update {
                    it.copy(currentActivity = "", notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Browsing the library (Constitution section 137)
    // ------------------------------------------------------------------

    fun onLibrarySearchChange(query: String) {
        _state.update { it.copy(librarySearch = query) }
        refreshLibrary(query)
    }

    fun openWork(id: WorkId) {
        viewModelScope.launch {
            when (val outcome = container.browseLibrary.work(id)) {
                is Outcome.Success -> _state.update { it.copy(openWork = outcome.value) }
                is Outcome.Failure -> _state.update {
                    it.copy(notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    fun closeWork() = _state.update { it.copy(openWork = null) }

    private fun refreshLibrary(query: String) {
        viewModelScope.launch {
            when (val outcome = container.browseLibrary.works(query.takeIf { it.isNotBlank() })) {
                is Outcome.Success -> _state.update { it.copy(libraryWorks = outcome.value) }
                is Outcome.Failure -> _state.update {
                    it.copy(notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    private fun RenameHistoryEntry.toRow(): HistoryRow {
        val summary = batch.summary()
        return HistoryRow(
            batchId = batch.id,
            createdAtEpochMillis = batch.createdAtEpochMillis,
            planned = summary.planned,
            completed = summary.completed,
            failed = summary.failed,
            restored = restoredCount,
            dryRun = batch.dryRun,
            undoable = undoable,
            renames = batch.operations.map { "${it.sourceName}  ->  ${it.destinationName}" },
        )
    }

    fun toggleConfirmation(observationId: ObservationId) {
        _state.update { current ->
            val confirmed = if (observationId in current.confirmed) {
                current.confirmed - observationId
            } else {
                current.confirmed + observationId
            }
            current.copy(confirmed = confirmed)
        }
    }

    fun buildPreview() {
        val session = sessionId ?: return
        viewModelScope.launch {
            _state.update { it.copy(currentActivity = "Checking the whole batch") }
            val generated = container.generatePlan.generate(
                sessionId = session,
                policy = container.automationPolicy,
                confirmations = _state.value.confirmed,
            )
            when (generated) {
                is Outcome.Success -> {
                    plan = generated.value
                    val preview = container.previewPlan.preview(generated.value)
                    _state.update {
                        it.copy(
                            phase = WorkflowPhase.PREVIEWING,
                            preview = preview,
                            currentActivity = "",
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(currentActivity = "", notices = it.notices + generated.failure.message)
                }
            }
        }
    }

    fun executeRenames(dryRun: Boolean) {
        val current = plan ?: return
        viewModelScope.launch {
            _state.update { it.copy(phase = WorkflowPhase.EXECUTING, currentActivity = "Renaming") }
            when (val outcome = container.executePlan.execute(current, dryRun = dryRun)) {
                is Outcome.Success -> {
                    val result = outcome.value
                    val summary = result.summary
                    val report = when {
                        result.refused != null -> result.refused!!.message
                        dryRun -> "Dry run: ${summary.planned} file(s) would be renamed. Nothing was changed."
                        else -> "${summary.planned} planned, ${summary.completed} completed, " +
                            "${summary.failed} failed, ${summary.skipped} skipped."
                    }
                    _state.update {
                        it.copy(
                            phase = WorkflowPhase.FINISHED,
                            executionReport = report,
                            currentActivity = "",
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(
                        phase = WorkflowPhase.PREVIEWING,
                        currentActivity = "",
                        notices = it.notices + outcome.failure.message,
                    )
                }
            }
        }
    }

    private fun onScanEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.SessionStarted -> {
                sessionId = event.session.id
                _state.update { it.copy(currentActivity = "Looking for files") }
            }

            is ScanEvent.FileDiscovered -> _state.update {
                it.copy(summary = event.summary, currentActivity = "Found ${event.file.name}")
            }

            is ScanEvent.FileResolved -> _state.update { current ->
                val row = event.resolved.toRow()
                current.copy(
                    summary = event.summary,
                    results = current.results + row,
                    statusCounts = current.statusCounts.plus(row.status),
                    currentActivity = "Identified ${event.resolved.observation.filename}",
                )
            }

            // Kept as its own list rather than as a notice. A file the scan
            // could not read is not a file RetroVault formed an opinion about,
            // and burying it in a notice feed is how it stops being visible.
            is ScanEvent.FileFailed -> _state.update { current ->
                current.copy(
                    summary = event.summary,
                    errors = current.errors + ScanIssue(event.relativePath, event.failure.message),
                    statusCounts = current.statusCounts.plusError(),
                )
            }

            // The scan has just written to the entity graph, so what the
            // library screen holds is now stale.
            is ScanEvent.SessionFinished -> {
                refreshLibrary(_state.value.librarySearch)
                _state.update { current ->
                    current.copy(
                        phase = WorkflowPhase.SCANNED,
                        summary = event.summary,
                        currentActivity = "",
                        notices = current.notices +
                            listOfNotNull(
                                if (event.cancelled) "Scan cancelled. Results so far were kept." else null,
                                event.persistenceFailure?.message,
                                // Distinct from a persistence failure: the
                                // scan's own results are fine and a rescan
                                // rebuilds the graph, so the wording says so
                                // rather than alarming the user about their
                                // scan.
                                event.graphFailure?.let {
                                    "Your scan results were saved, but the game library could not be " +
                                        "updated: ${it.message} Scanning again will rebuild it."
                                },
                            ),
                    )
                }
            }
        }
    }

    private fun ResolvedObservation.toRow(): ResultRow = ResultRow(
        observationId = observation.id,
        filename = observation.filename,
        relativePath = observation.relativePath,
        matchType = resolution.state.name,
        confidence = resolution.confidence,
        identity = resolution.selected?.record?.canonicalIdentityKey?.describe(),
        reasons = resolution.explanation.map { it.description },
        candidates = resolution.candidates.map { candidate ->
            "${candidate.record.setName} (${candidate.record.source.provider})"
        },
        // Asked of the policy rather than inferred from the confidence label.
        // Confirmation only ever upgrades REQUIRES_REVIEW: it cannot authorise
        // a FORBIDDEN resolution, and an AUTOMATIC one has nothing to confirm.
        // Deciding that here from confidence would put a domain rule in the
        // presentation layer and let the screen offer a checkbox the planner
        // then ignores.
        reviewable = container.automationPolicy.decide(resolution) == AutomationDecision.REQUIRES_REVIEW,
        identityBasis = resolution.identityBasis,
        // The pile is derived from the same decision the planner will make, so
        // "Identified" on screen and "renamed unasked" cannot disagree.
        status = LibraryStatus.of(resolution.state, container.automationPolicy.decide(resolution)),
    )

    private fun notice(message: String) {
        _state.update { it.copy(notices = it.notices + message) }
    }
}

/** Human-readable verdict for the preview banner (UX_SPEC.md section 8). */
fun PlanVerdict.describe(): String = when (this) {
    PlanVerdict.EXECUTABLE -> "Ready to rename."
    PlanVerdict.BLOCKED -> "Blocked. Nothing will be renamed until these are resolved."
    PlanVerdict.NOTHING_TO_DO -> "Nothing to rename. Every identified file already has its canonical name."
}
