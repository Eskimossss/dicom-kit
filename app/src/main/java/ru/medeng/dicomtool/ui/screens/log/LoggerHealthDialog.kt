package ru.medeng.dicomtool.ui.screens.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.medeng.dicomtool.R
import ru.medeng.dicomtool.data.logging.LoggerHealth
import ru.medeng.dicomtool.data.logging.LoggerWriteState

/** Presentation only: never flushes, resets or acknowledges the writer. Avoid sums for health
 * decisions: the counters are Long and critical counters are subsets, not additional losses. */
internal fun loggerHealthHasIssues(h: LoggerHealth): Boolean =
    h.state == LoggerWriteState.DEGRADED || h.state == LoggerWriteState.WRITE_FAILED ||
        h.rotationPending || h.lastError != null || listOf(h.droppedNormal, h.droppedCritical,
            h.writeUnconfirmed, h.criticalWriteUnconfirmed, h.readFailures, h.rejectedAfterStop,
            h.rejectedCriticalAfterStop, h.controlRejected, h.controlFailures, h.controlTimeouts).any { it > 0 }

private fun overview(h: LoggerHealth): Int = when {
    loggerHealthHasIssues(h) -> R.string.logger_health_issues
    h.historyLoading -> R.string.logger_health_loading
    h.state == LoggerWriteState.STOPPING -> R.string.logger_health_stopping_summary
    h.state == LoggerWriteState.STOPPED -> R.string.logger_health_stopped_summary
    h.queueDepth > 0 || h.accepted > h.persisted -> R.string.logger_health_pending
    else -> R.string.logger_health_ok
}

private fun stateLabel(state: LoggerWriteState): Int = when (state) {
    LoggerWriteState.ACTIVE -> R.string.logger_health_active
    LoggerWriteState.DEGRADED -> R.string.logger_health_degraded
    LoggerWriteState.WRITE_FAILED -> R.string.logger_health_write_failed
    LoggerWriteState.STOPPING -> R.string.logger_health_stopping
    LoggerWriteState.STOPPED -> R.string.logger_health_stopped
}

@Composable
internal fun LoggerHealthDialog(health: LoggerHealth, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    var showTechnical by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text(stringResource(R.string.logger_health_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(R.string.logger_health_general)
                Metric(R.string.logger_health_state, stringResource(stateLabel(health.state)))
                Surface(shape = MaterialTheme.shapes.small,
                    color = if (loggerHealthHasIssues(health)) palette.errorContainer else palette.surfaceVariant) {
                    Text(stringResource(overview(health)), modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (loggerHealthHasIssues(health)) palette.onErrorContainer else palette.onSurfaceVariant)
                }
                SectionTitle(R.string.logger_health_events)
                Text(stringResource(R.string.logger_health_counters_note), style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Metric(R.string.logger_health_accepted, health.accepted)
                    Metric(R.string.logger_health_persisted, health.persisted)
                    Metric(R.string.logger_health_queue, health.queueDepth)
                    // Exact display even for synthetic counters near Long.MAX_VALUE.
                    Metric(R.string.logger_health_dropped, health.droppedNormal.toBigInteger() + health.droppedCritical.toBigInteger())
                    Metric(R.string.logger_health_dropped_normal, health.droppedNormal)
                    Metric(R.string.logger_health_dropped_critical, health.droppedCritical)
                    Metric(R.string.logger_health_unconfirmed, health.writeUnconfirmed)
                    Metric(R.string.logger_health_unconfirmed_critical, health.criticalWriteUnconfirmed)
                }
                SectionTitle(R.string.logger_health_errors)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Metric(R.string.logger_health_read_failures, health.readFailures)
                    Metric(R.string.logger_health_rejected, health.rejectedAfterStop)
                    Metric(R.string.logger_health_rejected_critical, health.rejectedCriticalAfterStop)
                    Metric(R.string.logger_health_control_failures, health.controlFailures)
                    Metric(R.string.logger_health_control_timeouts, health.controlTimeouts)
                    Metric(R.string.logger_health_control_rejected, health.controlRejected)
                }
                SectionTitle(R.string.logger_health_extra)
                Metric(R.string.logger_health_rotation, yesNo(health.rotationPending))
                Metric(R.string.logger_health_history, yesNo(health.historyLoading))
                Text(stringResource(R.string.logger_health_last_error), style = MaterialTheme.typography.labelMedium)
                Surface(shape = MaterialTheme.shapes.small,
                    color = if (health.lastError != null) palette.errorContainer else palette.surfaceVariant) {
                    SelectionContainer {
                        Text(health.lastError ?: stringResource(R.string.logger_health_no), modifier = Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (health.lastError != null) palette.onErrorContainer else palette.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { showTechnical = !showTechnical }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.logger_health_technical))
                }
                if (showTechnical) SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        technicalValues(health).forEach { (key, value) ->
                            Text("$key: $value", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Surface(shape = MaterialTheme.shapes.small, color = palette.surfaceVariant,
                    border = BorderStroke(1.dp, palette.outlineVariant)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.logger_health_information), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.logger_health_crash_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.logger_health_close)) } }
    )
}

@Composable private fun yesNo(value: Boolean) = stringResource(if (value) R.string.logger_health_yes else R.string.logger_health_no)
@Composable private fun SectionTitle(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface)
}
@Composable private fun Metric(label: Int, value: Any) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), modifier = Modifier.weight(0.58f), style = MaterialTheme.typography.bodySmall)
        Text(value.toString(), modifier = Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace)
    }
}

private fun technicalValues(h: LoggerHealth) = listOf(
    "state" to h.state.name, "historyLoading" to h.historyLoading, "accepted" to h.accepted, "persisted" to h.persisted,
    "queueDepth" to h.queueDepth, "droppedNormal" to h.droppedNormal, "droppedCritical" to h.droppedCritical,
    "writeUnconfirmed" to h.writeUnconfirmed, "criticalWriteUnconfirmed" to h.criticalWriteUnconfirmed,
    "readFailures" to h.readFailures, "rejectedAfterStop" to h.rejectedAfterStop,
    "rejectedCriticalAfterStop" to h.rejectedCriticalAfterStop, "controlFailures" to h.controlFailures,
    "controlTimeouts" to h.controlTimeouts, "controlRejected" to h.controlRejected,
    "rotationPending" to h.rotationPending, "lastError" to (h.lastError ?: "null")
)
