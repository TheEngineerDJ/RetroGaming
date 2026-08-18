package com.retrovault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retrovault.app.HistoryRow
import com.retrovault.app.ui.theme.StatusTint
import com.retrovault.domain.identity.RenameBatchId
import java.text.DateFormat
import java.util.Date

/**
 * What RetroVault has done to the user's files, and how to put it back.
 *
 * Constitution section 233 requires an audit trail and section 170 requires a
 * rename to be reversible. Counts are reported honestly (section 245): "3
 * planned, 2 completed, 1 failed", never "done". Where nothing can be put back
 * the screen says so rather than hiding the control, because an unexplained
 * absence is its own kind of dead end.
 */
@Composable
fun ActivityScreen(
    history: List<HistoryRow>,
    notices: List<String>,
    onUndo: (RenameBatchId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Activity", style = MaterialTheme.typography.headlineMedium) }

        if (history.isEmpty()) {
            item { Text("RetroVault has not renamed anything yet.") }
        }

        items(history, key = { it.batchId.value }) { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(formatTime(row.createdAtEpochMillis), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${row.planned} planned · ${row.completed} completed · ${row.failed} failed" +
                            if (row.restored > 0) " · ${row.restored} put back" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (row.dryRun) {
                        Text("Dry run. Nothing was changed.", color = StatusTint.settled)
                    }
                    row.renames.take(MAX_SHOWN).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (row.renames.size > MAX_SHOWN) {
                        Text(
                            "…and ${row.renames.size - MAX_SHOWN} more",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (row.undoable) {
                        OutlinedButton(onClick = { onUndo(row.batchId) }) { Text("Put these files back") }
                    } else if (!row.dryRun) {
                        Text("Nothing in this batch can be put back.", color = StatusTint.settled)
                    }
                }
            }
        }

        if (notices.isNotEmpty()) {
            item { Text("Notices", style = MaterialTheme.typography.titleMedium) }
            items(notices.takeLast(MAX_NOTICES)) { notice ->
                Text(notice, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private const val MAX_SHOWN = 20
private const val MAX_NOTICES = 10
