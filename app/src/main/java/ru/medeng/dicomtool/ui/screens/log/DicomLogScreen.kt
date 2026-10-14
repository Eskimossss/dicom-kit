package ru.medeng.dicomtool.ui.screens.log

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.data.logging.DiagnosticRecommendation
import ru.medeng.dicomtool.data.logging.DicomDiagnosticHandbook
import ru.medeng.dicomtool.data.logging.DicomLogDisplayMode
import ru.medeng.dicomtool.data.logging.DicomLogExportUtils
import ru.medeng.dicomtool.data.logging.DicomOutcomeFilter
import ru.medeng.dicomtool.data.logging.DicomSessionSummary
import ru.medeng.dicomtool.data.logging.DicomTrafficFilter
import ru.medeng.dicomtool.data.logging.SessionOutcomeStatus
import ru.medeng.dicomtool.data.logging.TransferredFileItem
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.SuccessGreen
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.WarningAmber

/**
 * Экран «Журнал DICOM» (Этап 4.3.2):
 * - Два режима отображения: «Краткий» (по сеансам) и «Подробный» (по событиям).
 * - Локальный офлайн-справочник типовых ошибок с практическими рекомендациями для сервисных инженеров.
 * - Фильтры по направлению, результату и текстовый поиск по всем полям.
 * - Копирование и экспорт: техническая сводка по allowlist или полный текст с предупреждением.
 */
@Composable
fun DicomLogScreen(
    displayMode: DicomLogDisplayMode,
    trafficFilter: DicomTrafficFilter,
    outcomeFilter: DicomOutcomeFilter,
    searchQuery: String,
    sessionSummaries: List<DicomSessionSummary>,
    logs: List<DicomLogEntry>,
    expandedSessionKeys: Set<String>,
    expandedLogIds: Set<String>,
    autoScroll: Boolean,
    onSetDisplayMode: (DicomLogDisplayMode) -> Unit,
    onSetTrafficFilter: (DicomTrafficFilter) -> Unit,
    onSetOutcomeFilter: (DicomOutcomeFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit,
    onToggleSessionExpand: (String) -> Unit,
    onToggleLogExpand: (String) -> Unit,
    onClearLogsConfirmed: () -> Unit,
    loggerHealth: ru.medeng.dicomtool.data.logging.LoggerHealth = ru.medeng.dicomtool.data.logging.LoggerHealth()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showClearDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var copyEvent by remember { mutableStateOf<DicomLogEntry?>(null) }
    var copySession by remember { mutableStateOf<DicomSessionSummary?>(null) }
    var showLoggerDetails by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Автопрокрутка в подробном режиме
    LaunchedEffect(logs.size, autoScroll, displayMode) {
        if (autoScroll && displayMode == DicomLogDisplayMode.DETAILED && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // Диалог подтверждения очистки
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text(
                    text = "Очистить журнал DICOM?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            },
            text = {
                Text(
                    text = "Удаляются все записи технического журнала DICOM-обмена и файлы ротации. Принятые исследования в архиве сохраняются.",
                    color = TextSecondarySlate,
                    softWrap = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearLogsConfirmed()
                    }
                ) {
                    Text("Очистить", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена", color = TextSecondarySlate)
                }
            }
        )
    }

    // Диалог экспорта журнала
    if (showExportDialog) {
        ExportJournalDialog(
            context = context,
            sessions = sessionSummaries,
            logs = logs,
            isBriefMode = displayMode == DicomLogDisplayMode.BRIEF,
            onDismiss = { showExportDialog = false }
        )
    }

    if (copyEvent != null || copySession != null) ExportJournalDialog(
        context = context, sessions = listOfNotNull(copySession), logs = copyEvent?.let { listOf(it) } ?: copySession!!.events,
        isBriefMode = copySession != null, onDismiss = { copyEvent = null; copySession = null },
        onCopy = { text, safe ->
            clipboardManager.setText(AnnotatedString(text))
            Toast.makeText(context, if (safe) "Техническая сводка скопирована" else "Полный текст скопирован; возможны персональные данные", Toast.LENGTH_SHORT).show()
        })

    if (showLoggerDetails) AlertDialog(onDismissRequest = { showLoggerDetails = false }, containerColor = CardSurfaceWhite,
        title = { Text("Состояние журнала", color = TextPrimaryDark) }, text = {
            Text("Состояние: ${loggerHealth.state}\nПринято: ${loggerHealth.accepted}; записано: ${loggerHealth.persisted}; в очереди: ${loggerHealth.queueDepth}\n" +
                "${loggerHealth.warning}\nОшибки чтения: ${loggerHealth.readFailures}; отклонено после остановки: ${loggerHealth.rejectedAfterStop}; управляющих команд отклонено: ${loggerHealth.controlRejected}, ошибок: ${loggerHealth.controlFailures}, тайм-аутов: ${loggerHealth.controlTimeouts}\n" +
                "Незавершённая ротация: ${loggerHealth.rotationPending}\nПоследняя ошибка (может содержать персональные данные): ${loggerHealth.lastError ?: "нет"}\nПри аварийном завершении сохранение очереди не гарантируется.",
                color = TextSecondarySlate, modifier = Modifier.verticalScroll(rememberScrollState()))
        }, confirmButton = { TextButton(onClick = { showLoggerDetails = false }) { Text("Закрыть") } })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CardSurfaceWhite)
    ) {
        if (loggerHealth.needsAttention) Surface(color = WarningAmber.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()
            .clickable { showLoggerDetails = true }) {
            Text(loggerHealth.warning, color = TextPrimaryDark, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
        }
        // --- 1. ВЕРХНЯЯ ПАНЕЛЬ: Переключатель режимов + действия ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = CardSurfaceWhite,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Переключатель: Краткий | Подробный
                    ModeSwitchSegment(
                        currentMode = displayMode,
                        onModeSelected = onSetDisplayMode
                    )

                    // Действия: Автопрокрутка, Экспорт, Очистить
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (displayMode == DicomLogDisplayMode.DETAILED) {
                            Text(
                                text = "Авто",
                                fontSize = 12.sp,
                                color = TextSecondarySlate
                            )
                            Switch(
                                checked = autoScroll,
                                onCheckedChange = onToggleAutoScroll,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = PrimaryBlue,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = CardBorderGray
                                ),
                                modifier = Modifier.size(width = 44.dp, height = 28.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Кнопка экспорта
                        Surface(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showExportDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = InputBackgroundGray,
                            border = BorderStroke(1.dp, CardBorderGray)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Экспорт журнала",
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // Кнопка очистки
                        Surface(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showClearDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = InputBackgroundGray,
                            border = BorderStroke(1.dp, CardBorderGray)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Очистить журнал",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Поисковая строка
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Поиск по AE, IP, файлу, UID, ошибке...",
                            fontSize = 13.sp,
                            color = TextSecondarySlate
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TextSecondarySlate,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Очистить поиск",
                                    tint = TextSecondarySlate,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = CardBorderGray,
                        focusedContainerColor = CardSurfaceWhite,
                        unfocusedContainerColor = InputBackgroundGray
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Фильтр направления потока в горизонтальной строке
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Поток:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondarySlate
                    )
                    DicomTrafficFilter.entries.forEach { f ->
                        FilterChip(
                            selected = trafficFilter == f,
                            onClick = { onSetTrafficFilter(f) },
                            label = { Text(f.labelRu, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryBlueLight,
                                selectedLabelColor = PrimaryBlue,
                                containerColor = InputBackgroundGray,
                                labelColor = TextSecondarySlate
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = trafficFilter == f,
                                borderColor = if (trafficFilter == f) PrimaryBlue else CardBorderGray
                            )
                        )
                    }

                    if (trafficFilter != DicomTrafficFilter.ALL || searchQuery.isNotBlank()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                onSetTrafficFilter(DicomTrafficFilter.ALL)
                                onSearchQueryChange("")
                            }
                        ) {
                            Text("Сбросить", fontSize = 11.sp, color = ErrorRed)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = CardBorderGray, thickness = 1.dp)

        // Индикатор количества записей
        val countText = if (displayMode == DicomLogDisplayMode.BRIEF) {
            "Сеансов: ${sessionSummaries.size}"
        } else {
            "Записей: ${logs.size}"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(InputBackgroundGray.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = countText,
                fontSize = 11.sp,
                color = TextSecondarySlate,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (displayMode == DicomLogDisplayMode.BRIEF) "Краткий режим (сеансы)" else "Подробный режим (хронология)",
                fontSize = 11.sp,
                color = TextSecondarySlate
            )
        }

        HorizontalDivider(color = CardBorderGray.copy(alpha = 0.6f), thickness = 0.7.dp)

        // --- 2. СПИСОК: Краткий или Подробный ---
        if (displayMode == DicomLogDisplayMode.BRIEF) {
            if (sessionSummaries.isEmpty()) {
                EmptyStateView(
                    isSearchActive = searchQuery.isNotBlank() || trafficFilter != DicomTrafficFilter.ALL,
                    onReset = {
                        onSetTrafficFilter(DicomTrafficFilter.ALL)
                        onSearchQueryChange("")
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                ) {
                    items(sessionSummaries, key = { it.sessionKey }) { summary ->
                        val isExpanded = expandedSessionKeys.contains(summary.sessionKey)
                        DicomSessionSummaryCard(
                            summary = summary,
                            isExpanded = isExpanded,
                            onToggleExpand = { onToggleSessionExpand(summary.sessionKey) },
                            onCopySession = {
                                copySession = summary
                            }
                        )
                    }
                }
            }
        } else {
            // Подробный режим
            if (logs.isEmpty()) {
                EmptyStateView(
                    isSearchActive = searchQuery.isNotBlank() || trafficFilter != DicomTrafficFilter.ALL,
                    onReset = {
                        onSetTrafficFilter(DicomTrafficFilter.ALL)
                        onSearchQueryChange("")
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs, key = { it.id }) { entry ->
                        val isExpanded = expandedLogIds.contains(entry.id)
                        DicomDetailedLogRow(
                            entry = entry,
                            isExpanded = isExpanded,
                            onToggleExpand = { onToggleLogExpand(entry.id) },
                            onCopyEvent = {
                                copyEvent = entry
                            }
                        )
                        HorizontalDivider(color = CardBorderGray.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

/**
 * Переключатель режимов «Краткий | Подробный».
 */
@Composable
private fun ModeSwitchSegment(
    currentMode: DicomLogDisplayMode,
    onModeSelected: (DicomLogDisplayMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = InputBackgroundGray,
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DicomLogDisplayMode.entries.forEach { mode ->
                val isSelected = currentMode == mode
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) PrimaryBlue else Color.Transparent,
                    modifier = Modifier.clickable { onModeSelected(mode) }
                ) {
                    Text(
                        text = mode.labelRu,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else TextPrimaryDark,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * Карточка сгруппированного сеанса в Кратком режиме.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DicomSessionSummaryCard(
    summary: DicomSessionSummary,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopySession: () -> Unit
) {
    val statusColor = when (summary.status) {
        SessionOutcomeStatus.SUCCESS -> SuccessGreen
        SessionOutcomeStatus.FAILURE -> ErrorRed
        SessionOutcomeStatus.WARNING -> WarningAmber
        SessionOutcomeStatus.IN_PROGRESS -> PrimaryBlue
        SessionOutcomeStatus.UNKNOWN -> TextSecondarySlate
    }

    val roleBgColor = if (summary.role == "SCU") WarningAmber.copy(alpha = 0.16f) else PrimaryBlueLight
    val roleTextColor = if (summary.role == "SCU") WarningAmber else PrimaryBlue

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleExpand),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
        border = BorderStroke(1.dp, if (isExpanded) PrimaryBlue.copy(alpha = 0.5f) else CardBorderGray)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Верхняя строка карточки: Направление, ID, Статус, Время
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = roleBgColor
                    ) {
                        Text(
                            text = "${summary.role} ${summary.direction.code}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = roleTextColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondarySlate
                    )

                    Text(
                        text = summary.associationId,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Бейдж статуса
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = summary.status.labelRu,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = summary.durationFormatted.takeIf { it != "—" } ?: summary.startTimeFormatted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondarySlate
                    )

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondarySlate,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Заголовок сеанса (Итог)
            Text(
                text = summary.statusTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (summary.status == SessionOutcomeStatus.FAILURE) ErrorRed else TextPrimaryDark
            )

            if (summary.statusSubtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary.statusSubtitle,
                    fontSize = 12.sp,
                    color = TextSecondarySlate
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Ключевые параметры сеанса (Удалённый узел, Операция, Файлов, Результат)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InputBackgroundGray, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (summary.formattedRemoteNode.isNotBlank() && summary.formattedRemoteNode != "-") {
                    SessionParamRow(
                        label = "Удалённый узел",
                        value = summary.formattedRemoteNode
                    )
                }
                SessionParamRow(label = "Операция", value = summary.primaryOperation)
                if (summary.filesCount > 0) {
                    SessionParamRow(label = "Файлов", value = summary.filesCount.toString())
                }
                SessionParamRow(
                    label = "Результат",
                    value = summary.statusTitle,
                    valueColor = statusColor
                )
            }

            // --- РАСКРЫТАЯ ЧАСТЬ КАРТОЧКИ ---
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = CardBorderGray, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // 1. Общая информация о сеансе
                    Text(
                        text = "1. Общая информация о сеансе",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            DetailItem(label = "ID сеанса (UID)", value = summary.sessionUid, isMono = true)
                            DetailItem(label = "Calling AE Title", value = summary.callingAeTitle)
                            DetailItem(label = "Called AE Title", value = summary.calledAeTitle)
                            DetailItem(label = "Удалённый сокет", value = summary.remoteHostAndPort, isMono = true)
                            DetailItem(label = "Время начала", value = summary.startTimeFormatted, isMono = true)
                            DetailItem(label = "Время завершения", value = summary.endTimeFormatted, isMono = true)
                            DetailItem(label = "Продолжительность", value = summary.durationFormatted)
                        }
                    }

                    // 2. Переданные файлы и результаты (если есть)
                    if (summary.filesTransferred.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "2. Переданные / принятые файлы (${summary.filesTransferred.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        summary.filesTransferred.forEachIndexed { i, file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = CardDefaults.cardColors(containerColor = InputBackgroundGray),
                                border = BorderStroke(0.8.dp, CardBorderGray)
                            ) {
                                SelectionContainer {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${i + 1}. ${file.displayTitle}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimaryDark,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            val badgeColor = if (file.isSuccess) SuccessGreen else if (file.isWarning) WarningAmber else ErrorRed
                                            Text(
                                                text = file.statusText,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor
                                            )
                                        }
                                        if (file.sopInstanceUid.isNotBlank()) {
                                            Text(
                                                text = "SOP UID: ${file.sopInstanceUid}",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextSecondarySlate
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Технические параметры ассоциации (если есть)
                    if (summary.associationParameters.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "3. Технические параметры ассоциации",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                summary.associationParameters.forEach { (k, v) ->
                                    DetailItem(label = k, value = v, isMono = true)
                                }
                            }
                        }
                    }

                    // 4. Диагностика и рекомендации для инженера
                    if (summary.diagnosticRecommendations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "4. Ошибки и рекомендации по диагностике",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        summary.diagnosticRecommendations.forEach { rec ->
                            DiagnosticRecommendationCard(rec = rec)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    // 5. Хронология событий соединения
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "5. Хронология событий соединения (${summary.events.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    summary.events.forEachIndexed { idx, ev ->
                        TimelineEventItem(index = idx + 1, entry = ev)
                        if (idx < summary.events.size - 1) {
                            HorizontalDivider(color = CardBorderGray.copy(alpha = 0.4f), thickness = 0.5.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Кнопка копирования полного сеанса
                    OutlinedButton(
                        onClick = onCopySession,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PrimaryBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Копировать данные сеанса", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionParamRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimaryDark
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            fontSize = 12.sp,
            color = TextSecondarySlate
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailItem(
    label: String,
    value: String,
    isMono: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            fontSize = 11.sp,
            color = TextSecondarySlate,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            color = TextPrimaryDark,
            modifier = Modifier.weight(0.58f),
            softWrap = true
        )
    }
}

/**
 * Карточка с рекомендацией из локального справочника ошибок.
 */
@Composable
private fun DiagnosticRecommendationCard(rec: DiagnosticRecommendation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.05f)),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f))
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = rec.technicalName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ErrorRed
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = rec.explanationRu,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = TextPrimaryDark
                )

                if (rec.possibleCausesRu.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Возможные причины:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondarySlate
                    )
                    rec.possibleCausesRu.forEach { cause ->
                        Text(
                            text = "• $cause",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = TextPrimaryDark,
                            modifier = Modifier.padding(start = 6.dp, top = 1.dp)
                        )
                    }
                }

                if (rec.engineerChecklistRu.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Что проверить инженеру:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryBlue
                    )
                    rec.engineerChecklistRu.forEach { checkItem ->
                        Text(
                            text = "✓ $checkItem",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = TextPrimaryDark,
                            modifier = Modifier.padding(start = 6.dp, top = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Элемент хронологии внутри раскрытой карточки сеанса.
 */
@Composable
private fun TimelineEventItem(
    index: Int,
    entry: DicomLogEntry
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$index.",
            fontSize = 10.sp,
            color = TextSecondarySlate,
            modifier = Modifier.width(22.dp)
        )
        Text(
            text = entry.timestampFormatted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondarySlate,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = if (entry.isError) ErrorRed.copy(alpha = 0.15f) else PrimaryBlueLight,
            modifier = Modifier.padding(end = 6.dp)
        ) {
            Text(
                text = entry.category.name,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (entry.isError) ErrorRed else PrimaryBlue,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(
            text = entry.title,
            fontSize = 11.sp,
            fontWeight = if (entry.isError) FontWeight.SemiBold else FontWeight.Normal,
            color = if (entry.isError) ErrorRed else TextPrimaryDark,
            softWrap = true,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Строка отдельного события в Подробном режиме.
 */
@Composable
private fun DicomDetailedLogRow(
    entry: DicomLogEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopyEvent: () -> Unit
) {
    val roleLabel = remember(entry.associationId, entry.technicalDetails) {
        entry.technicalDetails["Роль"]
            ?: when {
                entry.associationId.startsWith("SCU", ignoreCase = true) -> "SCU"
                entry.associationId.startsWith("ASSOC", ignoreCase = true) ||
                    entry.associationId.startsWith("SCP", ignoreCase = true) -> "SCP"
                else -> "SCP"
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Левая колонка: Время, Направление, ID
            Column(modifier = Modifier.width(115.dp)) {
                Text(
                    text = entry.timestampFormatted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondarySlate,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (roleLabel == "SCU") WarningAmber.copy(alpha = 0.16f) else PrimaryBlueLight
                    ) {
                        Text(
                            text = roleLabel,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (roleLabel == "SCU") WarningAmber else PrimaryBlue,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = PrimaryBlueLight
                    ) {
                        Text(
                            text = entry.direction.code,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = entry.associationId,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondarySlate,
                        maxLines = 1
                    )
                }
            }

            // Иконка категории
            LogCategoryIcon(category = entry.category, isError = entry.isError)

            Spacer(modifier = Modifier.width(8.dp))

            // Содержимое события
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = entry.title,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.isError) ErrorRed else TextPrimaryDark,
                        softWrap = true,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onCopyEvent,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Копировать событие",
                            tint = TextSecondarySlate,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                entry.summaryLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }

                // Раскрытые технические детали и диагностика
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val rec = remember(entry) { DicomDiagnosticHandbook.analyzeEntry(entry) }
                    if (rec != null) {
                        DiagnosticRecommendationCard(rec = rec)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (entry.technicalDetails.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = InputBackgroundGray),
                            border = BorderStroke(1.dp, CardBorderGray)
                        ) {
                            SelectionContainer {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    entry.technicalDetails.forEach { (k, v) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text(
                                                text = "$k:",
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                                color = TextSecondarySlate,
                                                modifier = Modifier.weight(0.4f)
                                            )
                                            Text(
                                                text = v,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = TextPrimaryDark,
                                                modifier = Modifier.weight(0.6f),
                                                softWrap = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCategoryIcon(
    category: DicomLogCategory,
    isError: Boolean
) {
    val bgColor = when {
        isError || category == DicomLogCategory.ASSOCIATE_RJ ||
            category == DicomLogCategory.ABORT ||
            category == DicomLogCategory.NETWORK_ERROR -> ErrorRed

        category == DicomLogCategory.TCP_DISCONNECT -> if (isError) ErrorRed else TextSecondarySlate

        category == DicomLogCategory.TCP_CONNECT ||
            category == DicomLogCategory.ASSOCIATE_RQ ||
            category == DicomLogCategory.RELEASE_RQ ||
            category == DicomLogCategory.SERVER_LIFECYCLE -> PrimaryBlue

        category == DicomLogCategory.C_ECHO_RQ ||
            category == DicomLogCategory.C_STORE_RQ -> WarningAmber

        else -> SuccessGreen
    }

    val icon = when (category) {
        DicomLogCategory.TCP_CONNECT,
        DicomLogCategory.SERVER_LIFECYCLE -> Icons.Default.ArrowUpward
        DicomLogCategory.ASSOCIATE_RQ,
        DicomLogCategory.C_ECHO_RQ,
        DicomLogCategory.C_STORE_RQ,
        DicomLogCategory.RELEASE_RQ -> Icons.Default.ArrowDownward
        DicomLogCategory.ASSOCIATE_RJ,
        DicomLogCategory.ABORT,
        DicomLogCategory.NETWORK_ERROR -> Icons.Default.Close
        DicomLogCategory.TCP_DISCONNECT -> if (isError) Icons.Default.Close else Icons.Default.Check
        else -> Icons.Default.Check
    }

    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp)
        )
    }
}

/**
 * Пустое состояние журнала.
 */
@Composable
private fun EmptyStateView(
    isSearchActive: Boolean,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSearchActive) Icons.Outlined.Info else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = TextSecondarySlate,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isSearchActive) "Ничего не найдено" else "Журнал DICOM пуст",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimaryDark
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isSearchActive) {
                "По текущим критериям фильтрации и поисковому запросу нет подходящих записей."
            } else {
                "Запустите DICOM Приёмник (SCP) или выполните отправку (SCU) для фиксации сетевого обмена."
            },
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = TextSecondarySlate,
            softWrap = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (isSearchActive) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PrimaryBlue)
            ) {
                Text("Сбросить фильтры", color = PrimaryBlue, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Диалог экспорта журнала с выбором обезличивания и предварительным просмотром.
 */
@Composable
private fun ExportJournalDialog(
    context: Context,
    sessions: List<DicomSessionSummary>,
    logs: List<DicomLogEntry>,
    isBriefMode: Boolean,
    onDismiss: () -> Unit,
    onCopy: ((String, Boolean) -> Unit)? = null
) {
    var sanitizePhi by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val exportSnapshot = remember(sanitizePhi, sessions, logs, isBriefMode) {
        sanitizePhi to DicomLogExportUtils.formatFullExport(
            sessions = sessions,
            allFilteredEntries = logs,
            isBriefMode = isBriefMode,
            sanitizePhi = sanitizePhi
        )
    }
    val exportContent = exportSnapshot.second
    val previewText = if (exportContent.length > 60_000) exportContent.take(60_000) + "\n[Предпросмотр ограничен 60000 символами; экспорт содержит весь выбранный текст]" else exportContent

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceWhite,
        title = {
            Text(
                    text = if (onCopy == null) "Экспорт журнала DICOM" else "Копирование журнала DICOM",
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (onCopy == null) "Выберите содержимое перед отправкой через Android Share Sheet:" else "Выберите содержимое для буфера обмена:",
                    fontSize = 13.sp,
                    color = TextSecondarySlate
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Вариант 1: Без ПДн (по умолчанию)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !sharing) { sanitizePhi = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = sanitizePhi,
                        enabled = !sharing,
                        onClick = { sanitizePhi = true }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                    text = "Безопасная техническая сводка (по умолчанию)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Только разрешённые технические поля; свободный текст и идентификаторы исследований исключены.",
                            fontSize = 11.sp,
                            color = TextSecondarySlate
                        )
                    }
                }

                // Вариант 2: Полный журнал
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !sharing) { sanitizePhi = false }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !sanitizePhi,
                        enabled = !sharing,
                        onClick = { sanitizePhi = false }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Полный технический журнал (может содержать персональные данные)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Полные поля выбранных записей. Оперативный список ограничен 2000 событиями.",
                            fontSize = 11.sp,
                            color = TextSecondarySlate
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Предупреждение
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (sanitizePhi) PrimaryBlueLight else WarningAmber.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (sanitizePhi) Icons.Outlined.Info else Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = if (sanitizePhi) PrimaryBlue else WarningAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (sanitizePhi) {
                                "Используется разрешённый список проверяемых технических полей. Это не гарантия юридической анонимизации. Исходные записи не изменяются."
                            } else {
                                "Полные данные могут содержать ФИО, Patient ID, имена файлов, пути и строки исключений. Проверьте текст перед копированием или передачей."
                            },
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = TextPrimaryDark
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Предпросмотр экспорта
                Text(
                    text = "Предварительный просмотр:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondarySlate
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = InputBackgroundGray,
                    border = BorderStroke(0.8.dp, CardBorderGray)
                ) {
                    Text(
                        text = previewText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondarySlate,
                        modifier = Modifier.padding(6.dp).verticalScroll(rememberScrollState()),
                        lineHeight = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = click@{
                    // A mode change awaiting recomposition must not label an old full preview as safe.
                    if (exportSnapshot.first != sanitizePhi) return@click
                    if (onCopy != null) { onCopy(exportContent, exportSnapshot.first); onDismiss() }
                    else {
                        sharing = true
                        val safeSnapshot = exportSnapshot.first
                        val contentSnapshot = exportContent
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { DicomLogExportUtils.prepareExportFile(context, contentSnapshot, safeSnapshot) }
                                DicomLogExportUtils.sharePreparedFile(context, file, safeSnapshot); onDismiss()
                            } catch (e: Exception) { Toast.makeText(context, "Экспорт не выполнен: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
                            finally { sharing = false }
                        }
                    }
                },
                enabled = !sharing,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(if (onCopy == null) "Поделиться" else if (sanitizePhi) "Копировать сводку" else "Копировать полный текст", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondarySlate)
            }
        }
    )
}
