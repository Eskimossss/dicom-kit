package ru.medeng.dicomtool.mwl.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.StopButtonRed
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.WarningAmber

@Composable
fun MwlJournalTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var filterDirection by remember { mutableStateOf<String>("ALL") } // "ALL", "SCP", "SCU"
    var filterErrorsOnly by remember { mutableStateOf(false) }

    var selectedEntryDetail by remember { mutableStateOf<DicomLogEntry?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val filteredLogs = remember(logs, searchQuery, filterDirection, filterErrorsOnly) {
        val q = searchQuery.trim().lowercase()
        logs.filter { entry ->
            val matchesDir = when (filterDirection) {
                "SCP" -> entry.associationId.contains("SCP", ignoreCase = true)
                "SCU" -> entry.associationId.contains("SCU", ignoreCase = true)
                else -> true
            }
            val matchesErr = !filterErrorsOnly || entry.isError
            val matchesQuery = q.isEmpty() ||
                    entry.title.lowercase().contains(q) ||
                    entry.associationId.lowercase().contains(q) ||
                    entry.summaryLines.any { it.lowercase().contains(q) } ||
                    entry.technicalDetails.values.any { it.lowercase().contains(q) }
            matchesDir && matchesErr && matchesQuery
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Панель управления и фильтрации журнала
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Журнал операций Worklist (MWL)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = "События SCP/SCU: ${logs.size} · Отфильтровано: ${filteredLogs.size}",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Экспорт отчёта", tint = PrimaryBlue)
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить журнал", tint = ErrorRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Поисковая строка
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск по событиям, IP, AE, кодам…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Фильтры SCP / SCU / Ошибки
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = filterDirection == "ALL",
                        onClick = { filterDirection = "ALL" },
                        label = { Text("Все события") }
                    )
                }
                item {
                    FilterChip(
                        selected = filterDirection == "SCP",
                        onClick = { filterDirection = "SCP" },
                        label = { Text("MWL SCP") }
                    )
                }
                item {
                    FilterChip(
                        selected = filterDirection == "SCU",
                        onClick = { filterDirection = "SCU" },
                        label = { Text("MWL SCU") }
                    )
                }
                item {
                    FilterChip(
                        selected = filterErrorsOnly,
                        onClick = { filterErrorsOnly = !filterErrorsOnly },
                        label = { Text("Только ошибки") }
                    )
                }
            }
        }

        // Список записей журнала
        if (filteredLogs.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CardSurfaceWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Text(
                    text = "Записи журнала отсутствуют",
                    fontSize = 13.sp,
                    color = TextSecondarySlate,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    MwlLogEntryItem(
                        entry = entry,
                        onClick = { selectedEntryDetail = entry }
                    )
                }
            }
        }
    }

    // Диалог деталей записи журнала
    if (selectedEntryDetail != null) {
        val entry = selectedEntryDetail!!
        AlertDialog(
            onDismissRequest = { selectedEntryDetail = null },
            title = {
                Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Время: ${entry.timestampFormatted} · Сессия: ${entry.associationId}", fontSize = 11.sp, color = TextSecondarySlate)

                    if (entry.summaryLines.isNotEmpty()) {
                        Text("Сводка:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        entry.summaryLines.forEach { line ->
                            Text("• $line", fontSize = 12.sp, color = TextPrimaryDark)
                        }
                    }

                    if (entry.technicalDetails.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Технические параметры:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        entry.technicalDetails.forEach { (k, v) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(k, fontSize = 11.sp, color = TextSecondarySlate)
                                Text(v, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val textToCopy = buildString {
                        appendLine("${entry.timestampFormatted} | ${entry.associationId} | ${entry.title}")
                        entry.summaryLines.forEach { appendLine("• $it") }
                        entry.technicalDetails.forEach { (k, v) -> appendLine("$k: $v") }
                    }
                    cm.setPrimaryClip(ClipData.newPlainText("MWL Log Entry", textToCopy))
                    Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Скопировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntryDetail = null }) { Text("Закрыть") }
            }
        )
    }

    // Диалог экспорта отчёта
    if (showExportDialog) {
        var includePhi by remember { mutableStateOf(false) }
        var reportText by remember(includePhi) { mutableStateOf(viewModel.exportDiagnosticReport(includePhi)) }

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Экспорт диагностического отчёта MWL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includePhi, onCheckedChange = { includePhi = it })
                        Column {
                            Text("Включить реальные данные пациентов (PHI)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("По умолчанию данные обезличены для безопасности", fontSize = 10.sp, color = TextSecondarySlate)
                        }
                    }

                    OutlinedTextField(
                        value = reportText,
                        onValueChange = {},
                        readOnly = true,
                        maxLines = 10,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("MWL Diagnostic Report", reportText))
                    Toast.makeText(context, "Отчёт скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                    showExportDialog = false
                }) {
                    Text("Скопировать отчёт")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Закрыть") }
            }
        )
    }

    // Подтверждение очистки журнала
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить журнал Worklist?") },
            text = { Text("Все накопленные записи журнала MWL будут безвозвратно удалены с диска.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearLogs()
                        showClearConfirm = false
                        Toast.makeText(context, "Журнал очищен", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StopButtonRed)
                ) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun MwlLogEntryItem(
    entry: DicomLogEntry,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = CardSurfaceWhite,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (entry.isError) ErrorRed.copy(alpha = 0.5f) else CardBorderGray)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Бейдж направления
                    val dirColor = when (entry.direction) {
                        DicomTrafficDirection.INBOUND -> PrimaryBlue
                        DicomTrafficDirection.OUTBOUND -> WarningAmber
                        DicomTrafficDirection.LOCAL -> TextSecondarySlate
                    }
                    Surface(
                        color = dirColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = entry.direction.code,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = dirColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = entry.associationId,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimaryDark
                    )
                }

                Text(
                    text = entry.timestampFormatted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = entry.title,
                fontSize = 13.sp,
                fontWeight = if (entry.isError) FontWeight.Bold else FontWeight.Normal,
                color = if (entry.isError) ErrorRed else TextPrimaryDark
            )

            if (entry.summaryLines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.summaryLines.first(),
                    fontSize = 11.sp,
                    color = TextSecondarySlate,
                    maxLines = 1
                )
            }
        }
    }
}
