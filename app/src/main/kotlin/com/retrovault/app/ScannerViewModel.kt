package com.retrovault.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrovault.application.DatInput
import com.retrovault.application.Outcome
import com.retrovault.application.RenameHistoryEntry
import com.retrovault.application.RenamePreview
import com.retrovault.application.ReviewSubject
import com.retrovault.application.ResolvedObservation
import com.retrovault.application.ScanEvent
import com.retrovault.application.ScanSummary
import com.retrovault.application.StorageLocation
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.identity.RenameBatchId
import com.retrovault.domain.identity.ScanSessionId
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.policy.AutomationDecision
import com.retrovault.domain.rename.PlanVerdict
import com.retrovault.domain.rename.RenamePlan
import com.retrovault.domain.resolution.ConfidenceLevel
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
    val identity: String?,
    val reasons: List<String>,
    val candidates: List<String>,
    val reviewable: Boolean,
)

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
    val historyOpen: Boolean = false,
) {
    val canScan: Boolean get() = rootDisplayName != null && phase != WorkflowPhase.SCANNING
    val canPreview: Boolean get() = phase == WorkflowPhase.SCANNED || phase == WorkflowPhase.PREVIEWING
    val canExecute: Boolean get() = preview?.executable == true && phase == WorkflowPhase.PREVIEWING
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
                preview = null,
                executionReport = null,
                confirmed = emptySet(),
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

    fun openHistory() {
        viewModelScope.launch {
            when (val outcome = container.renameHistory.recent()) {
                is Outcome.Success -> _state.update { current ->
                    current.copy(historyOpen = true, history = outcome.value.map { it.toRow() })
                }

                is Outcome.Failure -> _state.update {
                    it.copy(notices = it.notices + outcome.failure.message)
                }
            }
        }
    }

    fun closeHistory() = _state.update { it.copy(historyOpen = false) }

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
                    openHistory()
                }

                is Outcome.Failure -> _state.update {
                    it.copy(currentActivity = "", notices = it.notices + outcome.failure.message)
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
                current.copy(
                    summary = event.summary,
                    results = current.results + event.resolved.toRow(),
                    currentActivity = "Identified ${event.resolved.observation.filename}",
                )
            }

            is ScanEvent.FileFailed -> _state.update { current ->
                current.copy(
                    summary = event.summary,
                    notices = current.notices + "${event.relativePath}: ${event.failure.message}",
                )
            }

            is ScanEvent.SessionFinished -> _state.update { current ->
                current.copy(
                    phase = WorkflowPhase.SCANNED,
                    summary = event.summary,
                    currentActivity = "",
                    notices = current.notices +
                        listOfNotNull(
                            if (event.cancelled) "Scan cancelled. Results so far were kept." else null,
                            event.persistenceFailure?.message,
                        ),
                )
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
