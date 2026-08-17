package com.retrovault.app.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovault.app.ResultRow
import com.retrovault.app.ScannerUiState
import com.retrovault.app.ScannerViewModel
import com.retrovault.app.WorkflowPhase
import com.retrovault.app.describe
import com.retrovault.domain.identity.StorageRef
import com.retrovault.domain.resolution.ConfidenceLevel

/**
 * The whole initial workflow, in one screen.
 *
 * UX_SPEC.md section 18 scopes the first Android UI to folder selection, scan
 * configuration, progressive results, match inspection, batch preview, safe
 * execution and history. Nothing here decides identity: every label comes from
 * a domain decision.
 */
@Composable
fun RetroVaultApp(
    viewModel: ScannerViewModel,
    onPersistFolderPermission: (Uri) -> Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // Access is persisted before the folder is accepted. A folder
        // RetroVault cannot keep access to would scan once and then leave any
        // interrupted rename unrecoverable, so it is not adopted at all.
        if (uri != null && onPersistFolderPermission(uri)) {
            // The tree URI is turned into a document URI so that the scanner
            // and the rename executor address the folder the same way.
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
            viewModel.onFolderSelected(
                StorageRef(documentUri.toString()),
                uri.lastPathSegment ?: "Selected folder",
            )
        }
    }

    // No persistable grant is taken for the DAT. `ACTION_OPEN_DOCUMENT` does
    // not offer one, so asking would always fail, and it is not needed: the DAT
    // is read once into the local catalogue at import and never opened again.
    val datPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onDatSelected(
                StorageRef(uri.toString()),
                uri.lastPathSegment?.substringAfterLast('/') ?: "DAT file",
            )
        }
    }

    Scaffold { padding ->
        val review = state.review
        val column = Modifier.fillMaxSize().padding(padding).padding(16.dp)

        // One thing at a time. Reviewing a file is a decision about that file,
        // and leaving the whole scan on screen behind it invites the user to
        // act on a list that their own decision is about to change.
        if (review != null) {
            Column(modifier = column) {
                ReviewSheet(
                    subject = review,
                    busy = state.reviewBusy,
                    onCorrectTo = viewModel::correctTo,
                    onReject = viewModel::rejectIdentity,
                    onWithdraw = viewModel::withdrawCorrection,
                    onClose = viewModel::closeReview,
                )
            }
            return@Scaffold
        }

        if (state.historyOpen) {
            Column(modifier = column) {
                HistorySheet(
                    history = state.history,
                    onUndo = viewModel::undoBatch,
                    onClose = viewModel::closeHistory,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = column,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupSection(
                state = state,
                onPickFolder = { folderPicker.launch(null) },
                onPickDat = { datPicker.launch(arrayOf("*/*")) },
            )

            ScanSection(
                state = state,
                onScan = viewModel::startScan,
                onCancel = viewModel::cancelScan,
            )

            if (state.phase == WorkflowPhase.PREVIEWING || state.phase == WorkflowPhase.FINISHED) {
                PreviewSection(
                    state = state,
                    onDryRun = { viewModel.executeRenames(dryRun = true) },
                    onExecute = { viewModel.executeRenames(dryRun = false) },
                )
            }

            if (state.results.isNotEmpty()) {
                Button(
                    onClick = viewModel::buildPreview,
                    enabled = state.canPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Preview rename")
                }
            }

            OutlinedButton(
                onClick = viewModel::openHistory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("What RetroVault has renamed")
            }

            ResultsList(
                results = state.results,
                confirmed = state.confirmed,
                onToggle = viewModel::toggleConfirmation,
                onReview = viewModel::openReview,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.notices.isNotEmpty()) {
                NoticeList(state.notices)
            }
        }
    }
}

@Composable
private fun SetupSection(state: ScannerUiState, onPickFolder: () -> Unit, onPickDat: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("1. Choose where your games are", fontWeight = FontWeight.Bold)
            Text(
                state.rootDisplayName?.let { "Folder: $it" }
                    ?: "No folder chosen. RetroVault only reads the folder you pick.",
            )
            OutlinedButton(onClick = onPickFolder) { Text("Choose folder") }

            Text("2. Add reference data", fontWeight = FontWeight.Bold)
            Text(
                if (state.importedDatSets.isEmpty()) {
                    "No DAT imported. Identification works offline against the DATs you import."
                } else {
                    "Imported: ${state.importedDatSets.joinToString(", ")}"
                },
            )
            OutlinedButton(onClick = onPickDat) { Text("Import DAT file") }
        }
    }
}

@Composable
private fun ScanSection(state: ScannerUiState, onScan: () -> Unit, onCancel: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("3. Scan", fontWeight = FontWeight.Bold)
            val summary = state.summary
            // Counts, not a percentage: progress must communicate useful work
            // rather than a bar that implies everything is fine
            // (UX_SPEC.md section 4).
            Text(
                "${summary.discovered} found  ·  ${summary.processed} processed  ·  " +
                    "${summary.exact} exact  ·  ${summary.strong} strong  ·  " +
                    "${summary.reviewRequired} need review  ·  ${summary.ambiguous} ambiguous  ·  " +
                    "${summary.unmatched} no match  ·  ${summary.failed} errors",
            )
            if (summary.hashingSkippedBySizeFilter > 0) {
                Text(
                    "${summary.hashingSkippedBySizeFilter} file(s) had a size no catalogue record " +
                        "matches, so they were identified by name only.",
                )
            }
            if (state.phase == WorkflowPhase.SCANNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(state.currentActivity)
                OutlinedButton(onClick = onCancel) { Text("Cancel scan") }
            } else {
                Button(onClick = onScan, enabled = state.canScan) { Text("Start scan") }
            }
        }
    }
}

@Composable
private fun PreviewSection(state: ScannerUiState, onDryRun: () -> Unit, onExecute: () -> Unit) {
    val preview = state.preview ?: return
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("4. Preview", fontWeight = FontWeight.Bold)
            Text(preview.validation.verdict.describe())

            preview.validation.blockingIssues.forEach { issue ->
                Text("Blocked: ${issue.message}")
            }
            preview.validation.warnings.forEach { warning ->
                Text("Warning: ${warning.message}")
            }

            preview.rows
                .filter { it.proposedName != null && it.proposedName != it.currentName }
                .take(MAX_PREVIEW_ROWS)
                .forEach { row ->
                    Text("${row.currentName}  →  ${row.proposedName}")
                    // The basis travels with the confidence because they answer
                    // different questions, and the preview is where the user
                    // decides whether to accept a rename (Constitution
                    // section 306). Showing certainty without saying what it
                    // rests on is exactly what UX_SPEC.md section 16 forbids.
                    Text("    ${row.matchType} · ${row.confidence} · ${row.identityBasis}")
                }

            state.executionReport?.let { Text(it, fontWeight = FontWeight.Bold) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDryRun) { Text("Dry run") }
                // Disabled until validation passes: batch execution is not
                // offered while anything is blocked (UX_SPEC.md section 8).
                Button(onClick = onExecute, enabled = state.canExecute) { Text("Rename") }
            }
        }
    }
}

@Composable
private fun ResultsList(
    results: List<ResultRow>,
    confirmed: Set<com.retrovault.domain.identity.ObservationId>,
    onToggle: (com.retrovault.domain.identity.ObservationId) -> Unit,
    onReview: (com.retrovault.domain.identity.ObservationId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(results, key = { it.observationId.value }) { row ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                // State is carried by a text label, never by colour alone
                // (UX_SPEC.md section 5 and section 14).
                Text(
                    text = "${row.confidence.label()} — ${row.filename}",
                    modifier = Modifier.semantics {
                        contentDescription = "${row.filename}, ${row.confidence.label()}"
                    },
                )
                row.identity?.let { Text("    Identified as: $it") }
                // Section 306: "Modified" is its own state. A user must not be
                // told their headered copy *is* the catalogued dump.
                if (row.matchType == "MODIFIED_MATCH") {
                    Text("    The game data matches, but this file has extra header bytes in front of it.")
                }
                row.reasons.take(MAX_REASONS).forEach { reason -> Text("    · $reason") }
                if (row.candidates.size > 1) {
                    Text("    ${row.candidates.size} candidates remain; choose one to continue.")
                }
                // Offered on every row, not only ambiguous ones. Section 218:
                // a user may disagree with an exact hash match too, and the
                // strength of RetroVault's evidence is not a reason to make
                // saying so harder.
                TextButton(onClick = { onReview(row.observationId) }) {
                    Text("This is wrong / what is this?")
                }
                if (row.reviewable) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = row.observationId in confirmed,
                            onCheckedChange = { onToggle(row.observationId) },
                        )
                        Text("I confirm this identification")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NoticeList(notices: List<String>) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Notices", fontWeight = FontWeight.Bold)
            notices.takeLast(MAX_NOTICES).forEach { notice -> Text(notice) }
        }
    }
}

/**
 * Plain words rather than jargon.
 *
 * UX_SPEC.md section 16: a heuristic match must never be presented as exact,
 * and a user must never have to understand DAT internals to read a result.
 */
private fun ConfidenceLevel.label(): String = when (this) {
    ConfidenceLevel.EXACT -> "Exact match"
    ConfidenceLevel.STRONG -> "Strong match"
    ConfidenceLevel.PROBABLE -> "Review required"
    ConfidenceLevel.AMBIGUOUS -> "Ambiguous"
    ConfidenceLevel.UNKNOWN -> "No match"
}

private const val MAX_PREVIEW_ROWS = 50
private const val MAX_REASONS = 3
private const val MAX_NOTICES = 5
