package ru.medeng.dicomtool.ui.screens.received

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ru.medeng.dicomtool.domain.model.DicomInstanceFileItem
import ru.medeng.dicomtool.domain.model.DicomSeriesGroup
import ru.medeng.dicomtool.domain.model.DicomStudyItem
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import ru.medeng.dicomtool.ui.components.DicomToolSubScreenTopBar
import ru.medeng.dicomtool.ui.components.KeyValueTableRow
import ru.medeng.dicomtool.ui.components.ModalityFilterChip
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.DicomViewerCanvasBlack
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.TextMutedSlate
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.WarningAmber
import ru.medeng.dicomtool.ui.theme.WarningAmberLight
import ru.medeng.dicomtool.ui.viewmodel.DicomViewerRenderState
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Экран «Принятые исследования».
 * Отображает исключительно реально принятые и сохранённые во внутреннем хранилище DICOM Part 10 файлы,
 * сгруппированные по Study Instance UID и Series Instance UID, с поддержкой кириллических кодировок.
 */
@Composable
fun ReceivedStudiesScreen(
    studies: List<DicomStudyItem>,
    indexState: ru.medeng.dicomtool.data.storage.ArchiveIndexState = ru.medeng.dicomtool.data.storage.ArchiveIndexState(),
    selectedModality: String,
    selectedStudyUid: String?,
    onSelectModality: (String) -> Unit,
    onOpenStudyViewer: (DicomStudyItem) -> Unit,
    onDeleteStudy: (String) -> Unit,
    onRefreshStudies: () -> Unit
) {
    var showReadProblems by rememberSaveable { mutableStateOf(false) }
    if (showReadProblems) {
        AlertDialog(onDismissRequest = { showReadProblems = false }, title = { Text("Диагностика чтения DICOM") },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(indexState.readProblems, key = { it.source }) { problem ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(problem.fileName, fontWeight = FontWeight.Bold)
                        Text("Стадия: ${problem.stage.label}", fontSize = 12.sp)
                        Text(problem.reason, fontSize = 12.sp)
                        Text("Файл не изменён. Проверьте доступ или исходник у отправителя.", fontSize = 12.sp)
                    }
                }
            } }, confirmButton = { TextButton(onClick = onRefreshStudies) { Text("Повторить чтение") } },
            dismissButton = { TextButton(onClick = { showReadProblems = false }) { Text("Закрыть") } })
    }
    val filteredStudies = remember(studies, selectedModality) {
        if (selectedModality == "ALL") studies
        else studies.filter { it.modality.equals(selectedModality, ignoreCase = true) }
    }

    val countsByModality = remember(studies) {
        studies.groupingBy { it.modality.uppercase(Locale.ROOT) }.eachCount()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModalityFilterChip(
                    label = "Все (${studies.size})",
                    selected = selectedModality == "ALL",
                    onClick = { onSelectModality("ALL") }
                )
                countsByModality.forEach { (mod, count) ->
                    if (count > 0) {
                        ModalityFilterChip(
                            label = "$mod ($count)",
                            selected = selectedModality.equals(mod, ignoreCase = true),
                            onClick = { onSelectModality(mod) }
                        )
                    }
                }
            }

            IconButton(
                onClick = onRefreshStudies,
                modifier = Modifier.padding(start = 4.dp).size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Обновить список исследований",
                    tint = TextSecondarySlate
                )
            }
        }

        if (indexState.isLoading) Text("Обновление списка исследований…", fontSize = 12.sp)
        indexState.warning?.let { ru.medeng.dicomtool.ui.components.AppNotice("archive:${indexState.noticeId}", it) }
        if (indexState.readProblems.isNotEmpty()) {
            ru.medeng.dicomtool.ui.components.AppNotice("archive-read:${indexState.noticeId}:${indexState.readProblems.hashCode()}",
                "${indexState.readProblems.size} файла с ошибками чтения DICOM. Подробности — в диагностике ниже.")
            TextButton(onClick = { showReadProblems = true }) {
                Text("Диагностика чтения DICOM (${indexState.readProblems.size})", color = ErrorRed)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (filteredStudies.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Принятых исследований пока нет",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryDark
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Запустите DICOM Приёмник (SCP) на первой вкладке и отправьте исследования по протоколу C-STORE. Принятые файлы группируются по Study Instance UID и Series Instance UID.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredStudies, key = { it.studyInstanceUid }) { study ->
                    val isSelected = study.studyInstanceUid == selectedStudyUid ||
                        (selectedStudyUid == null && study == filteredStudies.first())
                    StudyListItemCard(
                        study = study,
                        isHighlighted = isSelected,
                        onClick = { onOpenStudyViewer(study) },
                        onDelete = { onDeleteStudy(study.studyInstanceUid) }
                    )
                }
            }
        }
    }
}


@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun StudyListItemCard(
    study: DicomStudyItem,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text("Удалить исследование?", fontWeight = FontWeight.Bold, color = TextPrimaryDark)
            },
            text = {
                Text(
                    "Исследование ${study.patientName} (StudyInstanceUID: ${study.studyInstanceUid}) будет удалено из локального хранилища.",
                    color = TextSecondarySlate,
                    softWrap = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Удалить", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Отмена", color = TextSecondarySlate)
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) PrimaryBlueLight.copy(alpha = 0.45f) else CardSurfaceWhite
        ),
        border = BorderStroke(
            width = if (isHighlighted) 1.5.dp else 1.dp,
            color = if (isHighlighted) PrimaryBlue else CardBorderGray
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(study.patientName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(study.modality, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("ID: ${study.patientId}", fontSize = 13.sp)
                        Text("${study.columns}×${study.rows}", fontSize = 13.sp)
                    }
                    Text(study.studyDateFormatted, fontSize = 13.sp, color = TextSecondarySlate)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Серий: ${study.seriesCount}", fontSize = 12.sp)
                        Text("Изображений: ${study.instancesCount}", fontSize = 12.sp)
                        Text(formatSizeMb(study.totalBytes), fontSize = 12.sp)
                    }
                }
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Удалить исследование",
                        tint = TextMutedSlate,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Открыть",
                    tint = if (isHighlighted) PrimaryBlue else TextMutedSlate
                )
            }

            if (!study.encodingWarning.isNullOrBlank()) {
                ru.medeng.dicomtool.ui.components.AppNotice("charset-study:${study.diagnosticNoticeId}", study.encodingWarning)
            }
        }
    }
}

@Composable
private fun DicomModalityResolutionBadge(
    modality: String,
    columns: Int,
    rows: Int,
    bitsStored: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = modality,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "${columns}×${rows}",
            color = Color(0xFF93C5FD),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        Text(
            text = "${bitsStored}-bit",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Экран просмотра реального DICOM-исследования (DICOM Viewer) и таблицы DICOM-тегов.
 * Поддерживает портретную и горизонтальную (Landscape) ориентацию без потери состояния.
 */
@Composable
fun DicomViewerAndTagsScreen(
    study: DicomStudyItem,
    selectedSeriesUid: String?,
    selectedInstanceIndex: Int,
    selectedTagForDetail: DicomTagEntry?,
    tags: List<DicomTagEntry>,
    tagSearchQuery: String,
    zoom: Float,
    pan: Offset,
    inverted: Boolean,
    windowWidth: Int,
    windowCenter: Int,
    currentFrame: Int,
    renderState: DicomViewerRenderState,
    isFullScreen: Boolean = false,
    onToggleFullScreen: () -> Unit = {},
    initialTab: Int = 0,
    onBack: () -> Unit,
    onSelectSeries: (String) -> Unit,
    onSelectTagForDetail: (DicomTagEntry?) -> Unit,
    onTagSearchChange: (String) -> Unit,
    onZoomFactor: (Float) -> Unit,
    onPanDelta: (Offset) -> Unit,
    onFitToScreen: () -> Unit,
    onResetTransform: () -> Unit,
    onResetWindowOnly: () -> Unit,
    onToggleInvert: () -> Unit,
    onAdjustWindow: (Int, Int) -> Unit,
    onSetWindowWidthLevel: (Int, Int) -> Unit,
    onStepFrame: (Int) -> Unit,
    onStepInstance: (Int) -> Unit,
    onEnsureDecoded: () -> Unit,
    onDeleteCurrentStudy: () -> Unit
) {
    LaunchedEffect(study.studyInstanceUid, selectedSeriesUid, selectedInstanceIndex, currentFrame) {
        onEnsureDecoded()
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    val activeSeries: DicomSeriesGroup? = remember(study, selectedSeriesUid) {
        study.seriesList.firstOrNull { it.seriesInstanceUid == selectedSeriesUid }
            ?: study.seriesList.firstOrNull()
    }
    val seriesIndex = remember(study, activeSeries) {
        val idx = study.seriesList.indexOf(activeSeries)
        if (idx >= 0) idx + 1 else 1
    }
    val instancesInActiveSeries = activeSeries?.instances?.size?.coerceAtLeast(1) ?: study.instancesCount.coerceAtLeast(1)
    val safeInstanceIdx = selectedInstanceIndex.coerceIn(1, instancesInActiveSeries)
    val activeInstance: DicomInstanceFileItem? = activeSeries?.instances?.getOrNull(safeInstanceIdx - 1)
    val totalFramesInInstance = activeInstance?.numberOfFrames?.coerceAtLeast(1) ?: 1

    if (selectedTagForDetail != null) {
        DicomTagDetailDialog(
            tagEntry = selectedTagForDetail,
            onDismiss = { onSelectTagForDetail(null) }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CardSurfaceWhite,
            title = { Text("Удалить исследование?", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Исследование пациента ${study.patientName} (Study UID: ${study.studyInstanceUid}) будет удалено.",
                    color = TextSecondarySlate,
                    softWrap = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteCurrentStudy()
                        onBack()
                    }
                ) {
                    Text("Удалить", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена", color = TextSecondarySlate)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!isFullScreen) {
                DicomToolSubScreenTopBar(
                    title = "Просмотр DICOM",
                    subtitle = study.modality,
                    onBack = onBack,
                    trailingContent = {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить исследование")
                        }
                    }
                )
            }
            ru.medeng.dicomtool.ui.components.DicomViewerWorkspace(
                sourceKey = study.studyInstanceUid, diagnosticNoticeId = study.diagnosticNoticeId, metadata = listOf("Пациент" to study.patientName,
                    "ID пациента" to study.patientId, "Дата исследования" to study.studyDateFormatted,
                    "Описание исследования" to study.studyDescription, "Study Instance UID" to study.studyInstanceUid), tags = tags, query = tagSearchQuery,
                renderState = renderState, zoom = zoom, pan = pan, inverted = inverted, ww = windowWidth, wl = windowCenter,
                frame = currentFrame, frames = totalFramesInInstance, instance = safeInstanceIdx, instances = instancesInActiveSeries,
                fullScreen = isFullScreen, initialTab = initialTab, encodingWarning = activeInstance?.encodingWarning ?: study.encodingWarning,
                onQuery = onTagSearchChange, onZoom = onZoomFactor, onPan = onPanDelta, onFit = onFitToScreen,
                onResetView = onResetTransform, onResetWindow = onResetWindowOnly, onInvert = onToggleInvert,
                onAdjustWindow = onAdjustWindow, onWindow = onSetWindowWidthLevel, onFrame = onStepFrame,
                onInstance = onStepInstance, onFullScreen = onToggleFullScreen, modifier = Modifier.weight(1f),
                leadingControls = {
                    var seriesMenu by remember { mutableStateOf(false) }
                    if (study.seriesList.size > 1) {
                        Box {
                            OutlinedButton(onClick = { seriesMenu = true }) { Text("Серия $seriesIndex/${study.seriesCount}") }
                            DropdownMenu(expanded = seriesMenu, onDismissRequest = { seriesMenu = false }) {
                                study.seriesList.forEach { series ->
                                    DropdownMenuItem(text = { Text("${series.seriesNumber}: ${series.seriesDescription}") },
                                        onClick = { seriesMenu = false; onSelectSeries(series.seriesInstanceUid) })
                                }
                            }
                        }
                    }
                })
        }
    }
}

@Composable
fun DicomTagDetailDialog(
    tagEntry: DicomTagEntry,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 560.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
            border = BorderStroke(1.dp, CardBorderGray),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Подробности DICOM-тега",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryBlueLight,
                        border = BorderStroke(1.dp, PrimaryBlue)
                    ) {
                        Text(
                            text = "VR: ${tagEntry.vr}",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = CardBorderGray, thickness = 1.dp)

                KeyValueTableRow(
                    label = "Номер тега:",
                    value = tagEntry.tagHex,
                    showDivider = true
                )
                KeyValueTableRow(
                    label = "Название:",
                    value = tagEntry.name,
                    showDivider = true
                )

                Text(
                    text = "Полное значение:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondarySlate
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 80.dp, max = 260.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = InputBackgroundGray,
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = tagEntry.value.ifBlank { "(пустое значение)" },
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimaryDark,
                                softWrap = true
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    "DICOM Tag ${tagEntry.tagHex}",
                                    tagEntry.value
                                )
                            )
                            Toast.makeText(
                                context,
                                "Значение тега ${tagEntry.tagHex} скопировано",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Копировать значение", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Закрыть", fontSize = 14.sp, color = TextSecondarySlate, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun formatSizeMb(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 0.1) {
        String.format(Locale.US, "%.2f МБ", mb)
    } else {
        String.format(Locale.US, "%.1f КБ", bytes.toDouble() / 1024.0)
    }
}
