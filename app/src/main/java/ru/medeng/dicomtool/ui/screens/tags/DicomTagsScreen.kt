package ru.medeng.dicomtool.ui.screens.tags

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.dicom.pixel.DicomWindowPreset
import ru.medeng.dicomtool.domain.model.DicomEncodingOverride
import ru.medeng.dicomtool.domain.model.DicomFileSummary
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import ru.medeng.dicomtool.ui.components.DicomSharedViewportBox
import ru.medeng.dicomtool.ui.components.KeyValueTableRow
import ru.medeng.dicomtool.ui.components.ModalityFilterChip
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.ErrorRedLight
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.TextMutedSlate
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.viewmodel.DicomViewerRenderState
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Экран раздела «DICOM-теги» (5-я вкладка нижней навигации).
 * Поддерживает:
 * - Выбор любого локального DICOM-файла через Storage Access Framework (SAF)
 * - Переключатель режимов: «Теги» (по умолчанию) и «Изображение» (DICOM Viewer)
 * - Единый просмотрщик изображений с поддержкой полноэкранного режима, WW/WL, зума и кадров
 */
@Composable
fun DicomTagsScreen(
    summary: DicomFileSummary?,
    tags: List<DicomTagEntry>,
    isLoading: Boolean,
    errorMessage: String?,
    searchQuery: String,
    encodingOverride: DicomEncodingOverride,
    viewerRenderState: DicomViewerRenderState,
    viewerZoom: Float,
    viewerPan: Offset,
    viewerInverted: Boolean,
    viewerWindowWidth: Int,
    viewerWindowCenter: Int,
    viewerCurrentFrame: Int,
    isViewerFullScreen: Boolean,
    onSelectFileUri: (Uri) -> Unit,
    onClearFile: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onEncodingOverrideChange: (DicomEncodingOverride) -> Unit,
    onZoomFactor: (Float) -> Unit,
    onPanDelta: (Offset) -> Unit,
    onFitToScreen: () -> Unit,
    onResetTransform: () -> Unit,
    onResetWindowOnly: () -> Unit,
    onApplyWindowPreset: (DicomWindowPreset) -> Unit,
    onToggleInvert: () -> Unit,
    onAdjustWindow: (Int, Int) -> Unit,
    onSetWindowWidthLevel: (Int, Int) -> Unit,
    onStepFrame: (Int) -> Unit,
    onToggleFullScreen: () -> Unit,
    onEnsureDecoded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onSelectFileUri(uri)
        }
    }

    var selectedModeTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Теги, 1 = Изображение
    var showDetailsSummary by remember { mutableStateOf(false) }
    var showFullFileNameDialog by remember { mutableStateOf(false) }

    // При переключении на режим «Изображение» убедимся, что начат рендеринг кадра
    LaunchedEffect(selectedModeTab, summary) {
        if (selectedModeTab == 1 && summary != null) {
            onEnsureDecoded()
        }
    }

    if (showFullFileNameDialog && summary != null) {
        AlertDialog(
            onDismissRequest = { showFullFileNameDialog = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text(
                    text = "Имя DICOM-файла",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val formattedPatient = DicomCharsetDecoder.formatDicomPersonName(summary.patientName)
                    if (formattedPatient.isNotBlank()) {
                        Text(
                            text = "Пациент: $formattedPatient",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimaryDark
                        )
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = summary.fileName,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextPrimaryDark,
                            softWrap = true
                        )
                    }
                    Text(
                        text = "Размер: ${formatFileSize(summary.fileSizeBytes)} (${summary.fileSizeBytes} байт)",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                    Text(
                        text = "Модальность: ${summary.modality} • ${if (summary.hasPreambleAndDicm) "DICOM Part 10 (сигнатура DICM)" else "Raw DICOM (без преамбулы)"}",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullFileNameDialog = false }) {
                    Text("Закрыть", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (isViewerFullScreen && summary != null) {
        val totalFrames = summary.numberOfFrames.coerceAtLeast(1)
        DicomSharedViewportBox(
            renderState = viewerRenderState,
            zoom = viewerZoom,
            pan = viewerPan,
            inverted = viewerInverted,
            windowWidth = viewerWindowWidth,
            windowCenter = viewerWindowCenter,
            currentFrame = viewerCurrentFrame,
            totalFrames = totalFrames,
            currentInstance = 1,
            totalInstances = 1,
            defaultColumns = summary.columns,
            defaultRows = summary.rows,
            defaultPhotometricInterpretation = "MONOCHROME2",
            defaultBitsStored = summary.bitsAllocated,
            defaultBitsAllocated = summary.bitsAllocated,
            isFullScreen = true,
            onZoomFactor = onZoomFactor,
            onPanDelta = onPanDelta,
            onFitToScreen = onFitToScreen,
            onResetTransform = onResetTransform,
            onToggleInvert = onToggleInvert,
            onAdjustWindow = onAdjustWindow,
            onStepFrame = onStepFrame,
            onToggleFullScreen = onToggleFullScreen,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
        when {
            // Индикатор загрузки метаданных
            isLoading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryBlue,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Чтение метаданных DICOM-файла...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Анализ заголовков и набора атрибутов без загрузки Pixel Data в память",
                            fontSize = 12.sp,
                            color = TextSecondarySlate
                        )
                    }
                }
            }

            // Ошибка чтения файла
            !errorMessage.isNullOrBlank() -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ErrorRedLight),
                    border = BorderStroke(1.dp, ErrorRed)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Ошибка чтения файла",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorRed
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            border = BorderStroke(1.dp, ErrorRed)
                        ) {
                            Text("Выбрать другой файл", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Файл ещё не выбран — начальная карточка
            summary == null -> {
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
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PrimaryBlueLight,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Sell,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Выберите DICOM-файл для анализа",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Поддерживаются любые медицинские снимки (.dcm, .dicom, без расширения) со стандартным маркером DICM и без него (ACR-NEMA, raw).",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextSecondarySlate,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Файлы читаются локально без интернета и без загрузки больших массивов Pixel Data в оперативную память устройства.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = TextMutedSlate,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Icon(imageVector = Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выбрать файл на устройстве", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Файл успешно загружен — карточка файла, переключатель «Теги» / «Изображение» и контент
            else -> {
                // Компактная карточка сведений о выбранном файле
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        val formattedPatientName = remember(summary.patientName) {
                            DicomCharsetDecoder.formatDicomPersonName(summary.patientName)
                        }
                        val hasPatientName = formattedPatientName.isNotBlank()
                        val cardMainTitle = if (hasPatientName) formattedPatientName else summary.fileName
                        val cardSubtitle = if (hasPatientName) {
                            "${summary.fileName} · ${summary.modality} · ${formatFileSize(summary.fileSizeBytes)}"
                        } else {
                            "${formatFileSize(summary.fileSizeBytes)} · ${summary.modality} · ${if (summary.hasPreambleAndDicm) "DICM" else "Raw"}"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showFullFileNameDialog = true }
                                    .padding(end = 6.dp)
                            ) {
                                Text(
                                    text = cardMainTitle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = cardSubtitle,
                                    fontSize = 12.sp,
                                    color = TextSecondarySlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                IconButton(
                                    onClick = { showDetailsSummary = !showDetailsSummary },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = "Сведения о файле",
                                        tint = if (showDetailsSummary) PrimaryBlue else TextSecondarySlate,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onClearFile,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Закрыть файл",
                                        tint = TextSecondarySlate,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                OutlinedButton(
                                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    border = BorderStroke(1.dp, PrimaryBlue),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FileOpen,
                                        contentDescription = null,
                                        tint = PrimaryBlue,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Выбрать другой",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PrimaryBlue
                                    )
                                }
                            }
                        }

                        if (showDetailsSummary) {
                            HorizontalDivider(color = CardBorderGray, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 6.dp))
                            KeyValueTableRow("Пациент", summary.patientName)
                            KeyValueTableRow("ID пациента", summary.patientId)
                            KeyValueTableRow("Дата исследования", summary.studyDateFormatted)
                            KeyValueTableRow("Описание исследования", summary.studyDescription)
                            if (summary.rows > 0 && summary.columns > 0) {
                                KeyValueTableRow("Разрешение", "${summary.columns} × ${summary.rows} (${summary.bitsAllocated} бит)")
                            }
                            KeyValueTableRow("Transfer Syntax", "${summary.transferSyntaxUid} (${summary.transferSyntaxName})")
                            KeyValueTableRow("SOP Class UID", "${summary.sopClassUid} (${summary.sopClassName})")
                            KeyValueTableRow("Кодировка (0008,0005)", summary.specificCharacterSet, showDivider = false)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Переключатель режимов: «Теги» / «Изображение»
                TabRow(
                    selectedTabIndex = selectedModeTab,
                    containerColor = CardSurfaceWhite,
                    contentColor = PrimaryBlue,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedModeTab]),
                            color = PrimaryBlue
                        )
                    },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedModeTab == 0,
                        onClick = { selectedModeTab = 0 },
                        modifier = Modifier.height(40.dp),
                        text = {
                            Text(
                                text = "Теги",
                                fontSize = 13.sp,
                                fontWeight = if (selectedModeTab == 0) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedModeTab == 0) PrimaryBlue else TextSecondarySlate
                            )
                        }
                    )
                    Tab(
                        selected = selectedModeTab == 1,
                        onClick = {
                            selectedModeTab = 1
                            onEnsureDecoded()
                        },
                        modifier = Modifier.height(40.dp),
                        text = {
                            Text(
                                text = "Изображение",
                                fontSize = 13.sp,
                                fontWeight = if (selectedModeTab == 1) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedModeTab == 1) PrimaryBlue else TextSecondarySlate
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedModeTab == 0) {
                    // Режим «Теги»: таблица DICOM-тегов
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                        border = BorderStroke(1.dp, CardBorderGray)
                    ) {
                        DicomTagsBrowserComponent(
                            tags = tags,
                            searchQuery = searchQuery,
                            encodingWarning = summary.encodingWarning,
                            currentEncodingOverride = encodingOverride,
                            showEncodingSelector = true,
                            onSearchQueryChange = onSearchQueryChange,
                            onEncodingOverrideChange = onEncodingOverrideChange
                        )
                    }
                } else {
                    // Режим «Изображение»: единый просмотрщик DICOM Viewer
                    val readyDescriptor = (viewerRenderState as? DicomViewerRenderState.Ready)?.descriptor
                    val totalFrames = summary.numberOfFrames.coerceAtLeast(1)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Вьюпорт изображения
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.58f)
                                .heightIn(min = 240.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Black),
                            border = BorderStroke(1.dp, CardBorderGray)
                        ) {
                            DicomSharedViewportBox(
                                renderState = viewerRenderState,
                                zoom = viewerZoom,
                                pan = viewerPan,
                                inverted = viewerInverted,
                                windowWidth = viewerWindowWidth,
                                windowCenter = viewerWindowCenter,
                                currentFrame = viewerCurrentFrame,
                                totalFrames = totalFrames,
                                currentInstance = 1,
                                totalInstances = 1,
                                defaultColumns = summary.columns,
                                defaultRows = summary.rows,
                                defaultPhotometricInterpretation = "MONOCHROME2",
                                defaultBitsStored = summary.bitsAllocated,
                                defaultBitsAllocated = summary.bitsAllocated,
                                isFullScreen = isViewerFullScreen,
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
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Панель регулировки WW/WL и пресетов
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.42f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
                            border = BorderStroke(1.dp, CardBorderGray)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Управление WW / WL",
                                            fontSize = 13.sp,
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
                                }

                                item {
                                    Text("WW — ширина окна (контраст); WL — центр окна (яркость). Авто Мин/Макс охватывает диапазон значений текущего кадра. Числа в скобках — WW / WL.", fontSize = 12.sp, color = TextPrimaryDark)
                                }

                                // Пресеты
                                val presets = readyDescriptor?.windowPresets.orEmpty()
                                if (presets.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            presets.forEach { preset ->
                                                val isSelected = !preset.isVoiLutSequence &&
                                                    viewerWindowWidth == preset.windowWidth.roundToInt() &&
                                                    viewerWindowCenter == preset.windowCenter.roundToInt()
                                                ModalityFilterChip(
                                                    label = preset.name,
                                                    selected = isSelected,
                                                    onClick = { onApplyWindowPreset(preset) }
                                                )
                                            }
                                        }
                                    }
                                }

                                val minMod = readyDescriptor?.minModalityValue ?: 0.0
                                val maxMod = readyDescriptor?.maxModalityValue ?: max(viewerWindowWidth.toDouble(), 16384.0)
                                val fullSpan = (maxMod - minMod).coerceAtLeast(255.0)
                                val maxWwSlider = max(fullSpan * 1.5, viewerWindowWidth.toDouble() * 1.2).toFloat().coerceAtLeast(255f)
                                val minWlSlider = min(minMod - fullSpan * 0.25, viewerWindowCenter.toDouble() - 500.0).toFloat()
                                val maxWlSlider = max(maxMod + fullSpan * 0.25, viewerWindowCenter.toDouble() + 500.0).toFloat()

                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "WW: $viewerWindowWidth",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimaryDark,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            SmallStepButton(label = "−") { onAdjustWindow(-(viewerWindowWidth / 20).coerceAtLeast(1), 0) }
                                            SmallStepButton(label = "+") { onAdjustWindow((viewerWindowWidth / 20).coerceAtLeast(1), 0) }
                                        }
                                    }
                                    Slider(
                                        value = viewerWindowWidth.toFloat().coerceIn(1f, maxWwSlider),
                                        onValueChange = { newWw ->
                                            onSetWindowWidthLevel(newWw.roundToInt().coerceAtLeast(1), viewerWindowCenter)
                                        },
                                        valueRange = 1f..maxWwSlider,
                                        colors = SliderDefaults.colors(thumbColor = PrimaryBlue, activeTrackColor = PrimaryBlue),
                                        modifier = Modifier.height(24.dp)
                                    )
                                }

                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "WL: $viewerWindowCenter",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimaryDark,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            SmallStepButton(label = "−") { onAdjustWindow(0, -(viewerWindowWidth / 20).coerceAtLeast(1)) }
                                            SmallStepButton(label = "+") { onAdjustWindow(0, (viewerWindowWidth / 20).coerceAtLeast(1)) }
                                        }
                                    }
                                    Slider(
                                        value = viewerWindowCenter.toFloat().coerceIn(minWlSlider, maxWlSlider),
                                        onValueChange = { newWl ->
                                            onSetWindowWidthLevel(viewerWindowWidth, newWl.roundToInt())
                                        },
                                        valueRange = minWlSlider..maxWlSlider.coerceAtLeast(minWlSlider + 1f),
                                        colors = SliderDefaults.colors(thumbColor = PrimaryBlue, activeTrackColor = PrimaryBlue),
                                        modifier = Modifier.height(24.dp)
                                    )
                                }

                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = onToggleInvert,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (viewerInverted) PrimaryBlue else CardBorderGray),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (viewerInverted) PrimaryBlueLight else CardSurfaceWhite
                                            )
                                        ) {
                                            Text(
                                                text = if (viewerInverted) "Инверсия: ВКЛ" else "Инверсия MONOCHROME",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (viewerInverted) PrimaryBlue else TextPrimaryDark
                                            )
                                        }

                                        if (totalFrames > 1) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                SmallStepButton(label = "◀") { onStepFrame(-1) }
                                                Text(
                                                    text = "Кадр $viewerCurrentFrame/$totalFrames",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextPrimaryDark
                                                )
                                                SmallStepButton(label = "▶") { onStepFrame(1) }
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
        color = CardSurfaceWhite,
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 0.1) {
        String.format(Locale.US, "%.2f МБ", mb)
    } else {
        String.format(Locale.US, "%.1f КБ", bytes.toDouble() / 1024.0)
    }
}
