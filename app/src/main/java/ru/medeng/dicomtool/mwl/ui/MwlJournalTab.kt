package ru.medeng.dicomtool.mwl.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.saveable.rememberSaveable
import ru.medeng.dicomtool.ui.screens.log.DicomDetailedLogRow
import ru.medeng.dicomtool.ui.screens.log.ExportJournalDialog
import ru.medeng.dicomtool.data.logging.DicomLogExportUtils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MwlJournalTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filterDirection by rememberSaveable { mutableStateOf<String>("ALL") } // "ALL", "SCP", "SCU"
    var filterErrorsOnly by rememberSaveable { mutableStateOf(false) }

    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var copyEntry by remember { mutableStateOf<DicomLogEntry?>(null) }
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
        // Панель управления и фильтрации журнала (компактный UI в стиле основного журнала)
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Журнал операций MWL",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1
                    )
                    Text(
                        text = "Событий: ${logs.size} · Найдено: ${filteredLogs.size}",
                        fontSize = 11.sp,
                        color = TextSecondarySlate,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = {
                            if (searchOpen && searchQuery.isNotEmpty()) {
                                searchQuery = ""
                            }
                            searchOpen = !searchOpen
                        }
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = if (searchOpen) "Скрыть поиск" else "Поиск в журнале",
                            tint = if (searchOpen || searchQuery.isNotEmpty()) PrimaryBlue else TextSecondarySlate
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = { showExportDialog = true }
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Экспорт отчёта", tint = PrimaryBlue)
                    }
                    IconButton(
                        modifier = Modifier.size(44.dp),
                        onClick = { showClearConfirm = true }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Очистить журнал", tint = ErrorRed)
                    }
                }
            }

            if (searchOpen) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по событиям, IP, AE, кодам…", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) {
                                searchQuery = ""
                            } else {
                                searchOpen = false
                            }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить поиск", modifier = Modifier.size(18.dp))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Компактные фильтры потоков и ошибок
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = filterDirection == "ALL",
                        onClick = { filterDirection = "ALL" },
                        label = { Text("Все", fontSize = 11.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = filterDirection == "SCP",
                        onClick = { filterDirection = "SCP" },
                        label = { Text("MWL SCP", fontSize = 11.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = filterDirection == "SCU",
                        onClick = { filterDirection = "SCU" },
                        label = { Text("MWL SCU", fontSize = 11.sp) }
                    )
                }
                item {
                    FilterChip(
                        selected = filterErrorsOnly,
                        onClick = { filterErrorsOnly = !filterErrorsOnly },
                        label = { Text("Только ошибки", fontSize = 11.sp) }
                    )
                }
            }
        }

        }
        // Список записей журнала
        if (filteredLogs.isEmpty()) {
            item {
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
            }
        } else {
            items(filteredLogs, key = { it.id }) { entry ->
                DicomDetailedLogRow(
                    entry = entry,
                    isExpanded = entry.id in expandedIds,
                    onToggleExpand = { expandedIds = if (entry.id in expandedIds) expandedIds - entry.id else expandedIds + entry.id },
                    onCopyEvent = { copyEntry = entry }
                )
            }
        }
    }

    copyEntry?.let { entry ->
        ExportJournalDialog(context, emptyList(), listOf(entry), false, onDismiss = { copyEntry = null },
            onCopy = { text, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MWL event", text))
                copyEntry = null
            })
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
