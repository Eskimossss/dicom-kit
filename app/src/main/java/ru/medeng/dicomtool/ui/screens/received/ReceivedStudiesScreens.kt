package ru.medeng.dicomtool.ui.screens.received

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ru.medeng.dicomtool.dicom.pixel.DicomWindowPreset
import ru.medeng.dicomtool.domain.model.DicomInstanceFileItem
import ru.medeng.dicomtool.domain.model.DicomSeriesGroup
import ru.medeng.dicomtool.domain.model.DicomStudyItem
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import ru.medeng.dicomtool.ui.components.DicomSharedViewportBox
import ru.medeng.dicomtool.ui.components.DicomToolSubScreenTopBar
import ru.medeng.dicomtool.ui.components.KeyValueTableRow
import ru.medeng.dicomtool.ui.components.ModalityFilterChip
import ru.medeng.dicomtool.ui.components.ViewerToolButton
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.DicomViewerCanvasBlack
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.ErrorRedLight
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
import kotlin.math.roundToInt

/**
 * Экран «Принятые исследования».
 * Отображает исключительно реально принятые и сохранённые во внутреннем хранилище DICOM Part 10 файлы,
 * сгруппированные по Study Instance UID и Series Instance UID, с поддержкой кириллических кодировок.
 */
@Composable
fun ReceivedStudiesScreen(
    studies: List<DicomStudyItem>,
    selectedModality: String,
    selectedStudyUid: String?,
    onSelectModality: (String) -> Unit,
    onOpenStudyViewer: (DicomStudyItem) -> Unit,
    onDeleteStudy: (String) -> Unit,
    onRefreshStudies: () -> Unit
) {
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


@Composable
private fun StudyListItemCard(
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
                // Строгий технический бейдж модальности и разрешения (без демонстрационной картинки)
                DicomModalityResolutionBadge(
                    modality = study.modality,
                    columns = study.columns,
                    rows = study.rows,
                    bitsStored = study.bitsStored,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DicomViewerCanvasBlack)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = study.patientName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
                    Text(
                        text = "${study.modality} | ID: ${study.patientId} | ${study.columns}×${study.rows}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                    Text(
                        text = study.studyDateFormatted,
                        fontSize = 12.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                    Text(
                        text = "Study UID: ${study.studyInstanceUid}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMutedSlate,
                        softWrap = true
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Серий: ${study.seriesCount}   Изображений: ${study.instancesCount}   (${formatSizeMb(study.totalBytes)})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
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
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = WarningAmberLight,
                    border = BorderStroke(1.dp, WarningAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = study.encodingWarning,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = TextPrimaryDark,
                            softWrap = true
                        )
                    }
                }
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
    onApplyWindowPreset: (DicomWindowPreset) -> Unit,
    onStepFrame: (Int) -> Unit,
    onStepInstance: (Int) -> Unit,
    onEnsureDecoded: () -> Unit,
    onDeleteCurrentStudy: () -> Unit
) {
    LaunchedEffect(study.studyInstanceUid, selectedSeriesUid, selectedInstanceIndex, currentFrame) {
        onEnsureDecoded()
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectedTab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var showMenu by remember { mutableStateOf(false) }
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

    val subtitleText = buildString {
        append("${study.modality} | Серия $seriesIndex/${study.seriesCount} | Снимок $safeInstanceIdx/$instancesInActiveSeries")
        if (totalFramesInInstance > 1) {
            append(" | Кадр $currentFrame/$totalFramesInInstance")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CardSurfaceWhite)
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        if (!isFullScreen) {
            DicomToolSubScreenTopBar(
                title = study.patientName,
                subtitle = subtitleText,
                onBack = onBack,
                trailingContent = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Меню исследования",
                                tint = TextPrimaryDark
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(CardSurfaceWhite)
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isFullScreen) "Выйти из полного экрана" else "На весь экран", color = TextPrimaryDark) },
                                onClick = {
                                    showMenu = false
                                    onToggleFullScreen()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Вписать в экран (Fit to Screen)", color = TextPrimaryDark) },
                                onClick = {
                                    showMenu = false
                                    onFitToScreen()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сбросить WW/WL к значениям DICOM", color = TextPrimaryDark) },
                                onClick = {
                                    showMenu = false
                                    onResetWindowOnly()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Полный сброс WW/WL и масштаба", color = TextPrimaryDark) },
                                onClick = {
                                    showMenu = false
                                    onResetTransform()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить исследование", color = ErrorRed) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        }

        if (isFullScreen) {
            // В полноэкранном режиме вьюпорт занимает всё пространство экрана
            DicomSharedViewportBox(
                renderState = renderState,
                zoom = zoom,
                pan = pan,
                inverted = inverted,
                windowWidth = windowWidth,
                windowCenter = windowCenter,
                currentFrame = currentFrame,
                totalFrames = totalFramesInInstance,
                currentInstance = safeInstanceIdx,
                totalInstances = instancesInActiveSeries,
                defaultColumns = activeInstance?.columns ?: study.columns,
                defaultRows = activeInstance?.rows ?: study.rows,
                defaultPhotometricInterpretation = activeInstance?.photometricInterpretation ?: study.photometricInterpretation,
                defaultBitsStored = activeInstance?.bitsStored ?: study.bitsStored,
                defaultBitsAllocated = activeInstance?.bitsAllocated ?: study.bitsAllocated,
                isFullScreen = true,
                onZoomFactor = onZoomFactor,
                onPanDelta = onPanDelta,
                onFitToScreen = onFitToScreen,
                onResetTransform = onResetTransform,
                onToggleInvert = onToggleInvert,
                onAdjustWindow = onAdjustWindow,
                onStepFrame = onStepFrame,
                onToggleFullScreen = onToggleFullScreen,
                modifier = Modifier.fillMaxSize()
            )
        } else if (isLandscape && selectedTab == 0) {
            // Горизонтальная ориентация (Landscape): слева чёрный viewport снимка (60%), справа управление WW/WL и информация (40%)
            Row(modifier = Modifier.fillMaxSize()) {
                DicomSharedViewportBox(
                    renderState = renderState,
                    zoom = zoom,
                    pan = pan,
                    inverted = inverted,
                    windowWidth = windowWidth,
                    windowCenter = windowCenter,
                    currentFrame = currentFrame,
                    totalFrames = totalFramesInInstance,
                    currentInstance = safeInstanceIdx,
                    totalInstances = instancesInActiveSeries,
                    defaultColumns = activeInstance?.columns ?: study.columns,
                    defaultRows = activeInstance?.rows ?: study.rows,
                    defaultPhotometricInterpretation = activeInstance?.photometricInterpretation ?: study.photometricInterpretation,
                    defaultBitsStored = activeInstance?.bitsStored ?: study.bitsStored,
                    defaultBitsAllocated = activeInstance?.bitsAllocated ?: study.bitsAllocated,
                    isFullScreen = false,
                    onZoomFactor = onZoomFactor,
                    onPanDelta = onPanDelta,
                    onFitToScreen = onFitToScreen,
                    onResetTransform = onResetTransform,
                    onToggleInvert = onToggleInvert,
                    onAdjustWindow = onAdjustWindow,
                    onStepFrame = onStepFrame,
                    onToggleFullScreen = onToggleFullScreen,
                    modifier = Modifier
                        .weight(0.60f)
                        .fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .weight(0.40f)
                        .fillMaxHeight()
                ) {
                    ViewerTabsHeader(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it }
                    )
                    StudyInformationTabContent(
                        study = study,
                        activeSeries = activeSeries,
                        activeInstance = activeInstance,
                        renderState = renderState,
                        windowWidth = windowWidth,
                        windowCenter = windowCenter,
                        inverted = inverted,
                        currentFrame = currentFrame,
                        totalFrames = totalFramesInInstance,
                        currentInstance = safeInstanceIdx,
                        totalInstances = instancesInActiveSeries,
                        onSelectSeries = onSelectSeries,
                        onSetWindowWidthLevel = onSetWindowWidthLevel,
                        onAdjustWindow = onAdjustWindow,
                        onResetWindowOnly = onResetWindowOnly,
                        onApplyWindowPreset = onApplyWindowPreset,
                        onToggleInvert = onToggleInvert,
                        onStepFrame = onStepFrame,
                        onStepInstance = onStepInstance,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        } else {
            if (selectedTab == 0) {
                DicomSharedViewportBox(
                    renderState = renderState,
                    zoom = zoom,
                    pan = pan,
                    inverted = inverted,
                    windowWidth = windowWidth,
                    windowCenter = windowCenter,
                    currentFrame = currentFrame,
                    totalFrames = totalFramesInInstance,
                    currentInstance = safeInstanceIdx,
                    totalInstances = instancesInActiveSeries,
                    defaultColumns = activeInstance?.columns ?: study.columns,
                    defaultRows = activeInstance?.rows ?: study.rows,
                    defaultPhotometricInterpretation = activeInstance?.photometricInterpretation ?: study.photometricInterpretation,
                    defaultBitsStored = activeInstance?.bitsStored ?: study.bitsStored,
                    defaultBitsAllocated = activeInstance?.bitsAllocated ?: study.bitsAllocated,
                    isFullScreen = false,
                    onZoomFactor = onZoomFactor,
                    onPanDelta = onPanDelta,
                    onFitToScreen = onFitToScreen,
                    onResetTransform = onResetTransform,
                    onToggleInvert = onToggleInvert,
                    onAdjustWindow = onAdjustWindow,
                    onStepFrame = onStepFrame,
                    onToggleFullScreen = onToggleFullScreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.52f)
                        .heightIn(min = 240.dp)
                )
            }

            ViewerTabsHeader(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            if (selectedTab == 0) {
                StudyInformationTabContent(
                    study = study,
                    activeSeries = activeSeries,
                    activeInstance = activeInstance,
                    renderState = renderState,
                    windowWidth = windowWidth,
                    windowCenter = windowCenter,
                    inverted = inverted,
                    currentFrame = currentFrame,
                    totalFrames = totalFramesInInstance,
                    currentInstance = safeInstanceIdx,
                    totalInstances = instancesInActiveSeries,
                    onSelectSeries = onSelectSeries,
                    onSetWindowWidthLevel = onSetWindowWidthLevel,
                    onAdjustWindow = onAdjustWindow,
                    onResetWindowOnly = onResetWindowOnly,
                    onApplyWindowPreset = onApplyWindowPreset,
                    onToggleInvert = onToggleInvert,
                    onStepFrame = onStepFrame,
                    onStepInstance = onStepInstance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.48f)
                )
            } else {
                DicomTagsTableContent(
                    tags = tags,
                    tagSearchQuery = tagSearchQuery,
                    encodingWarning = activeInstance?.encodingWarning ?: study.encodingWarning,
                    onTagSearchChange = onTagSearchChange,
                    onTagClick = { tag: DicomTagEntry -> onSelectTagForDetail(tag) }
                )
            }
        }
    }
}

@Composable
private fun ViewerTabsHeader(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = CardSurfaceWhite,
        contentColor = PrimaryBlue,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                color = PrimaryBlue
            )
        }
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onSelectTab(0) },
            modifier = Modifier.height(40.dp),
            text = {
                Text(
                    text = "Просмотр и WW/WL",
                    fontSize = 13.sp,
                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedTab == 0) PrimaryBlue else TextSecondarySlate
                )
            }
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onSelectTab(1) },
            modifier = Modifier.height(40.dp),
            text = {
                Text(
                    text = "DICOM теги",
                    fontSize = 13.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedTab == 1) PrimaryBlue else TextSecondarySlate
                )
            }
        )
    }
}



/**
 * Содержимое вкладки «Просмотр и WW/WL»:
 * - Диагностические предупреждения кодировки (0008,0005) и конвейера изображения
 * - Интерактивное управление Window Width / Window Level (слайдеры, кнопки шага, пресеты DICOM, Reset WW/WL)
 * - Переключение серий, экземпляров серии и кадров многокадрового снимка
 * - Таблица метаданных пациента и исследования
 */
@Composable
private fun StudyInformationTabContent(
    study: DicomStudyItem,
    activeSeries: DicomSeriesGroup?,
    activeInstance: DicomInstanceFileItem?,
    renderState: DicomViewerRenderState,
    windowWidth: Int,
    windowCenter: Int,
    inverted: Boolean,
    currentFrame: Int,
    totalFrames: Int,
    currentInstance: Int,
    totalInstances: Int,
    onSelectSeries: (String) -> Unit,
    onSetWindowWidthLevel: (Int, Int) -> Unit,
    onAdjustWindow: (Int, Int) -> Unit,
    onResetWindowOnly: () -> Unit,
    onApplyWindowPreset: (DicomWindowPreset) -> Unit,
    onToggleInvert: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onStepInstance: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val readyState = renderState as? DicomViewerRenderState.Ready
    val descriptor = readyState?.descriptor
    val encodingWarning = activeInstance?.encodingWarning ?: study.encodingWarning

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Диагностическое предупреждение о кодировке (если исходный файл нарушает (0008,0005))
        if (!encodingWarning.isNullOrBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = WarningAmberLight),
                    border = BorderStroke(1.dp, WarningAmber)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Диагностика кодировки исходного DICOM-файла",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = encodingWarning,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = TextPrimaryDark,
                                softWrap = true
                            )
                        }
                    }
                }
            }
        }

        // 2. Предупреждения медицинского конвейера (например, FOR PROCESSING или отсутствие WW/WL)
        if (descriptor != null && descriptor.pipelineWarnings.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = PrimaryBlueLight.copy(alpha = 0.55f)),
                    border = BorderStroke(1.dp, PrimaryBlue)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        descriptor.pipelineWarnings.forEach { warn ->
                            Text(
                                text = "ℹ $warn",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = TextPrimaryDark,
                                softWrap = true
                            )
                        }
                    }
                }
            }
        }

        // 3. Панель управления изображением: WW/WL, Инверсия, Кадры и Снимки серии
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Управление окном WW / WL и кадрами",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        TextButton(onClick = onResetWindowOnly) {
                            Text(
                                text = "Reset WW/WL",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue
                            )
                        }
                    }

                    // Пресеты WW/WL и VOI LUT из DICOM-файла
                    val presets = descriptor?.windowPresets.orEmpty()
                    if (presets.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presets.forEach { preset ->
                                val isSelected = !preset.isVoiLutSequence &&
                                    windowWidth == preset.windowWidth.roundToInt() &&
                                    windowCenter == preset.windowCenter.roundToInt()
                                ModalityFilterChip(
                                    label = preset.name,
                                    selected = isSelected,
                                    onClick = { onApplyWindowPreset(preset) }
                                )
                            }
                        }
                    }

                    val minMod = descriptor?.minModalityValue ?: 0.0
                    val maxMod = descriptor?.maxModalityValue ?: max(windowWidth.toDouble(), 16384.0)
                    val fullSpan = (maxMod - minMod).coerceAtLeast(255.0)
                    val maxWwSlider = max(fullSpan * 1.5, windowWidth.toDouble() * 1.2).toFloat().coerceAtLeast(255f)
                    val minWlSlider = min(minMod - fullSpan * 0.25, windowCenter.toDouble() - 500.0).toFloat()
                    val maxWlSlider = max(maxMod + fullSpan * 0.25, windowCenter.toDouble() + 500.0).toFloat()

                    // Регулировка Window Width (WW)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Window Width (WW): $windowWidth",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallStepButton(label = "−") { onAdjustWindow(-5, 0) }
                            SmallStepButton(label = "+") { onAdjustWindow(5, 0) }
                        }
                    }
                    Slider(
                        value = windowWidth.toFloat().coerceIn(1f, maxWwSlider),
                        onValueChange = { newWw ->
                            onSetWindowWidthLevel(newWw.roundToInt().coerceAtLeast(1), windowCenter)
                        },
                        valueRange = 1f..maxWwSlider,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        ),
                        modifier = Modifier.height(26.dp)
                    )

                    // Регулировка Window Level / Center (WL)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Window Level (WL): $windowCenter",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallStepButton(label = "−") { onAdjustWindow(0, -5) }
                            SmallStepButton(label = "+") { onAdjustWindow(0, 5) }
                        }
                    }
                    Slider(
                        value = windowCenter.toFloat().coerceIn(minWlSlider, maxWlSlider),
                        onValueChange = { newWl ->
                            onSetWindowWidthLevel(windowWidth, newWl.roundToInt())
                        },
                        valueRange = minWlSlider..maxWlSlider.coerceAtLeast(minWlSlider + 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        ),
                        modifier = Modifier.height(26.dp)
                    )

                    // Переключение инверсии и кадров/снимков
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onToggleInvert,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (inverted) PrimaryBlue else CardBorderGray),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (inverted) PrimaryBlueLight else CardSurfaceWhite
                            )
                        ) {
                            Text(
                                text = if (inverted) "Инверсия: ВКЛ" else "Инверсия MONOCHROME",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (inverted) PrimaryBlue else TextPrimaryDark
                            )
                        }

                        if (totalFrames > 1) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmallStepButton(label = "◀") { onStepFrame(-1) }
                                Text(
                                    text = "Кадр $currentFrame/$totalFrames",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark
                                )
                                SmallStepButton(label = "▶") { onStepFrame(1) }
                            }
                        } else if (totalInstances > 1) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmallStepButton(label = "◀") { onStepInstance(-1) }
                                Text(
                                    text = "Снимок $currentInstance/$totalInstances",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark
                                )
                                SmallStepButton(label = "▶") { onStepInstance(1) }
                            }
                        }
                    }
                }
            }
        }

        // 4. Переключение серий и снимков серии
        if (study.seriesList.size > 1 || totalInstances > 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (study.seriesList.size > 1) {
                            Text(
                                text = "Серии исследования:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                study.seriesList.forEach { series ->
                                    val isSelected = series.seriesInstanceUid == activeSeries?.seriesInstanceUid
                                    ModalityFilterChip(
                                        label = "Серия #${series.seriesNumber} (${series.instances.size} шт.)",
                                        selected = isSelected,
                                        onClick = { onSelectSeries(series.seriesInstanceUid) }
                                    )
                                }
                            }
                        }
                        if (totalInstances > 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Изображение в серии: $currentInstance из $totalInstances",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallStepButton(label = "◀ Пред.") { onStepInstance(-1) }
                                    SmallStepButton(label = "След. ▶") { onStepInstance(1) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Таблица сведений о пациенте, параметрах изображения и UID
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    KeyValueTableRow("Patient Name (0010,0010)", study.patientName)
                    KeyValueTableRow("Patient ID (0010,0020)", study.patientId)
                    KeyValueTableRow("Specific Character Set (0008,0005)", activeInstance?.specificCharacterSet ?: study.specificCharacterSet)
                    KeyValueTableRow("Study Date", study.studyDateFormatted)
                    KeyValueTableRow("Modality (0008,0060)", study.modality)
                    KeyValueTableRow("Study Description (0008,1030)", study.studyDescription)
                    KeyValueTableRow("Series Description (0008,103E)", activeSeries?.seriesDescription ?: study.seriesDescription)
                    KeyValueTableRow(
                        "Разрешение (Columns × Rows)",
                        "${descriptor?.columns ?: activeInstance?.columns ?: study.columns} × ${descriptor?.rows ?: activeInstance?.rows ?: study.rows}"
                    )
                    KeyValueTableRow(
                        "Photometric Interpretation",
                        descriptor?.photometricInterpretation ?: activeInstance?.photometricInterpretation ?: study.photometricInterpretation
                    )
                    KeyValueTableRow(
                        "Bits Allocated / Stored / HighBit",
                        "${descriptor?.bitsAllocated ?: activeInstance?.bitsAllocated ?: study.bitsAllocated} / " +
                            "${descriptor?.bitsStored ?: activeInstance?.bitsStored ?: study.bitsStored} / " +
                            "${descriptor?.highBit ?: activeInstance?.highBit ?: study.highBit} " +
                            "(${if ((descriptor?.pixelRepresentation ?: activeInstance?.pixelRepresentation ?: study.pixelRepresentation) == 1) "Signed" else "Unsigned"})"
                    )
                    KeyValueTableRow(
                        "Rescale Slope / Intercept",
                        "${descriptor?.rescaleSlope ?: activeInstance?.rescaleSlope ?: study.rescaleSlope} / ${descriptor?.rescaleIntercept ?: activeInstance?.rescaleIntercept ?: study.rescaleIntercept}"
                    )
                    KeyValueTableRow("Manufacturer", study.manufacturer)
                    KeyValueTableRow("Institution Name", study.institutionName)
                    KeyValueTableRow("Study Instance UID", study.studyInstanceUid)
                    KeyValueTableRow(
                        label = "SOP Instance UID",
                        value = activeInstance?.sopInstanceUid ?: study.sopInstanceUid,
                        showDivider = false
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallStepButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = InputBackgroundGray,
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Таблица DICOM-тегов с поиском, диагностикой кодировки, адаптивным переносом длинных значений
 * и открытием подробной карточки по нажатию.
 */
@Composable
private fun DicomTagsTableContent(
    tags: List<DicomTagEntry>,
    tagSearchQuery: String,
    encodingWarning: String?,
    onTagSearchChange: (String) -> Unit,
    onTagClick: (DicomTagEntry) -> Unit
) {
    ru.medeng.dicomtool.ui.screens.tags.DicomTagsBrowserComponent(
        tags = tags,
        searchQuery = tagSearchQuery,
        encodingWarning = encodingWarning,
        showEncodingSelector = false,
        onSearchQueryChange = onTagSearchChange
    )
}

/**
 * Подробная карточка DICOM-тега по нажатию:
 * - Номер тега (GGGG,EEEE)
 * - Название
 * - VR
 * - Полное значение без обрезания (с прокруткой и переносом для длинных UID, текста и последовательностей SQ)
 * - Кнопка копирования значения в буфер обмена
 */
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
