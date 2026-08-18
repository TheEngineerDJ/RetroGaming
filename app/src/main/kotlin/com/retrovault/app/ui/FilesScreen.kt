package com.retrovault.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.retrovault.app.ResultRow
import com.retrovault.app.ScannerUiState
import com.retrovault.app.WorkflowPhase
import com.retrovault.app.describe
import com.retrovault.app.ui.theme.StatusTint
import com.retrovault.application.LibraryStatus
import com.retrovault.domain.identity.ObservationId
import com.retrovault.domain.resolution.IdentityBasis

/**
 * Every file the last scan looked at, and what RetroVault made of it.
 *
 * This is where the conservative promises are kept or broken. Nothing is
 * filtered away by default, including the files nothing can be done about
 * (section 277). Every row says what the identity *rests on* as well as how
 * sure RetroVault is, because those are different questions and collapsing
 * them is how an inferred identity starts reading as a verified one
 * (section 6, ROM_INTELLIGENCE.md section 7).
 */
@Composable
fun FilesScreen(
    state: ScannerUiState,
    onFilter: (LibraryStatus?) -> Unit,
    onReview: (ObservationId) -> Unit,
    onToggleConfirm: (ObservationId) -> Unit,
    onBuildPreview: () -> Unit,
    onDryRun: () -> Unit,
    onExecute: () -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Files", style = MaterialTheme.typography.headlineMedium)
                if (state.phase == WorkflowPhase.SCANNING) {
                    OutlinedButton(onClick = onCancel) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = onScan, enabled = state.canScan) { Text("Scan again") }
                }
            }
        }

        if (state.phase == WorkflowPhase.SCANNING) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    // Counts, not a percentage: progress must communicate real
                    // work rather than a bar implying everything is fine
                    // (UX_SPEC.md section 4).
                    Text(
                        "${state.summary.discovered} found · ${state.summary.processed} processed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(state.currentActivity, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!state.hasScanned && state.phase != WorkflowPhase.SCANNING) {
            item {
                Text(
                    text = if (state.isSetUp) {
                        "Nothing scanned yet. Scanning reads your folder and identifies what it can."
                    } else {
                        "Choose a folder and import a dataset in Setup, then scan."
                    },
                )
            }
        }

        if (state.statusCounts.present.isNotEmpty()) {
            item { StatusFilters(state = state, onFilter = onFilter) }
        }

        if (state.errors.isNotEmpty()) {
            item { ErrorsCard(state = state) }
        }

        if (state.hasScanned) {
            item {
                RenameCard(
                    state = state,
                    onBuildPreview = onBuildPreview,
                    onDryRun = onDryRun,
                    onExecute = onExecute,
                )
            }
        }

        items(state.visibleResults, key = { it.observationId.value }) { row ->
            FileCard(
                row = row,
                confirmed = row.observationId in state.confirmed,
                onReview = { onReview(row.observationId) },
                onToggleConfirm = { onToggleConfirm(row.observationId) },
            )
        }

        if (state.statusFilter != null && state.visibleResults.isEmpty()) {
            item { Text("No file is in that pile.") }
        }
    }
}

@Composable
private fun StatusFilters(state: ScannerUiState, onFilter: (LibraryStatus?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.statusFilter == null,
                onClick = { onFilter(null) },
                label = { Text("All ${state.statusCounts.total}") },
            )
            state.statusCounts.present.forEach { status ->
                FilterChip(
                    selected = state.statusFilter == status,
                    onClick = { onFilter(status) },
                    label = { Text("${status.label} ${state.statusCounts[status]}") },
                )
            }
        }
        // The selected pile explains itself, so a user never has to infer what
        // "Not listed" means from the count beside it.
        state.statusFilter?.let {
            Text(it.meaning, style = MaterialTheme.typography.bodySmall, color = StatusTint.settled)
        }
    }
}

@Composable
private fun ErrorsCard(state: ScannerUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${state.errors.size} file(s) could not be read", style = MaterialTheme.typography.titleSmall)
            Text(
                "RetroVault never got far enough to have an opinion about these, so they are not " +
                    "in any of the piles above.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.errors.take(MAX_ERRORS_SHOWN).forEach { issue ->
                Text("· ${issue.relativePath}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.errors.size > MAX_ERRORS_SHOWN) {
                Text("…and ${state.errors.size - MAX_ERRORS_SHOWN} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RenameCard(
    state: ScannerUiState,
    onBuildPreview: () -> Unit,
    onDryRun: () -> Unit,
    onExecute: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Rename to canonical names", style = MaterialTheme.typography.titleMedium)
            val preview = state.preview
            if (preview == null) {
                Text(
                    "RetroVault checks the whole batch before changing anything, and shows you " +
                        "every rename first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onBuildPreview, enabled = state.canPreview, modifier = Modifier.fillMaxWidth()) {
                    Text("Preview renames")
                }
            } else {
                Text(preview.validation.verdict.describe())
                preview.validation.blockingIssues.forEach {
                    Text("Blocked: ${it.message}", color = StatusTint.attention)
                }
                preview.validation.warnings.forEach { Text("Warning: ${it.message}") }

                preview.rows
                    .filter { it.proposedName != null && it.proposedName != it.currentName }
                    .take(MAX_PREVIEW_ROWS)
                    .forEach { row ->
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("${row.currentName}  →  ${row.proposedName}")
                            // The basis travels with the confidence because
                            // they answer different questions (section 306).
                            Text(
                                "    ${row.matchType} · ${row.confidence} · ${row.identityBasis}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusTint.settled,
                            )
                        }
                    }

                state.executionReport?.let { Text(it, style = MaterialTheme.typography.titleSmall) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDryRun) { Text("Dry run") }
                    // Disabled until validation passes: batch execution is not
                    // offered while anything is blocked (UX_SPEC.md section 8).
                    Button(onClick = onExecute, enabled = state.canExecute) { Text("Rename") }
                }
            }
        }
    }
}

@Composable
private fun FileCard(
    row: ResultRow,
    confirmed: Boolean,
    onReview: () -> Unit,
    onToggleConfirm: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(row.filename, style = MaterialTheme.typography.titleSmall)
            // State is carried by a text label, never by colour alone
            // (UX_SPEC.md section 5 and section 14).
            Text(
                text = row.status.label,
                color = if (row.status.isActionable) StatusTint.attention else StatusTint.confident,
                modifier = Modifier.semantics {
                    contentDescription = "${row.filename}, ${row.status.label}"
                },
            )
            row.identity?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(row.identityBasis.describe(), style = MaterialTheme.typography.bodySmall, color = StatusTint.settled)

            row.reasons.take(MAX_REASONS).forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall)
            }
            if (row.candidates.size > 1) {
                Text("${row.candidates.size} identities are still possible.")
            }

            if (row.reviewable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { onToggleConfirm() })
                    Text("I confirm this identification")
                }
            }
            TextButton(onClick = onReview) { Text("This is wrong / what is this?") }
        }
    }
}

/**
 * What an identity rests on, in plain words.
 *
 * Constitution section 6: a file whose bytes were checked is verified; a file
 * named after a catalogued release is inferred. Both may be right, and they
 * must never read as the same kind of statement.
 */
private fun IdentityBasis.describe(): String = when (this) {
    IdentityBasis.VERIFIED_CONTENT -> "Checked against the contents of the file"
    IdentityBasis.STRUCTURAL -> "Size and checksum agree, but nothing stronger was available"
    IdentityBasis.INFERRED -> "Read from the filename. The contents were not verified"
    IdentityBasis.USER_ASSERTED -> "You told RetroVault what this is"
    IdentityBasis.NONE -> "No identity was established"
}

private const val MAX_PREVIEW_ROWS = 50
private const val MAX_REASONS = 3
private const val MAX_ERRORS_SHOWN = 10
