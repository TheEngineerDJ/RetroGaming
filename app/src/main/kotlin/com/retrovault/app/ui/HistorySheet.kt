package com.retrovault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retrovault.app.HistoryRow
import com.retrovault.domain.identity.RenameBatchId
import java.text.DateFormat
import java.util.Date

/**
 * What RetroVault did to the user's files, and how to put it back.
 *
 * Constitution section 233 requires an audit trail and section 170 requires
 * renames to be reversible. Both were satisfied in storage long before anything
 * could read them, which made them promises rather than properties. This is the
 * reader.
 *
 * Counts are reported honestly (section 245): "3 planned, 2 completed, 1
 * failed", never "done".
 */
@Composable
fun HistorySheet(
    history: List<HistoryRow>,
    onUndo: (RenameBatchId) -> Unit,
    onClose: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What RetroVault has renamed", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }

            if (history.isEmpty()) {
                item { Text("Nothing has been renamed yet.") }
            }

            items(history, key = { it.batchId.value }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(formatTime(row.createdAtEpochMillis), fontWeight = FontWeight.Bold)
                        Text(
                            "${row.planned} planned · ${row.completed} completed · " +
                                "${row.failed} failed" +
                                if (row.restored > 0) " · ${row.restored} put back" else "",
                        )
                        if (row.dryRun) {
                            Text("Dry run. Nothing was changed.")
                        }
                        row.renames.take(MAX_SHOWN).forEach { Text("    $it") }
                        if (row.renames.size > MAX_SHOWN) {
                            Text("    …and ${row.renames.size - MAX_SHOWN} more")
                        }
                        if (row.undoable) {
                            OutlinedButton(onClick = { onUndo(row.batchId) }) {
                                Text("Put these files back")
                            }
                        } else if (!row.dryRun) {
                            // Says why rather than hiding the control, so the
                            // absence of an action is explained rather than
                            // being a silent dead end (UX_SPEC.md section 16).
                            Text("Nothing in this batch can be put back.")
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private const val MAX_SHOWN = 20
