package ru.medeng.dicomtool.ui.screens.send

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import ru.medeng.dicomtool.domain.model.FileSendStatus
import ru.medeng.dicomtool.domain.model.ScuBatchProgressState
import ru.medeng.dicomtool.domain.model.ScuCandidateFile
import ru.medeng.dicomtool.domain.model.ScuSendMode
import java.util.Locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.dicom.date.DateOfBirthVisualTransformation
import ru.medeng.dicomtool.dicom.date.DateValidationResult
import ru.medeng.dicomtool.dicom.date.DicomDateUtils
import ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator
import ru.medeng.dicomtool.dicom.scu.PreparedSecondaryCaptureDicomObject
import ru.medeng.dicomtool.dicom.scu.ScuTestImageRenderer
import ru.medeng.dicomtool.domain.model.FreehandStroke
import ru.medeng.dicomtool.domain.model.NormalizedPoint
import ru.medeng.dicomtool.domain.model.PatientSexOption
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import ru.medeng.dicomtool.domain.model.TestImageMode
import ru.medeng.dicomtool.ui.components.DiagnosticOutlinedField
import ru.medeng.dicomtool.ui.components.DicomToolSubScreenTopBar
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.DicomViewerCanvasBlack
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.SuccessGreen
import ru.medeng.dicomtool.ui.theme.SuccessGreenBackground
import ru.medeng.dicomtool.ui.theme.SuccessGreenBorder
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.WarningAmber
import kotlin.math.roundToInt

/**
 * Экран «Отправка DICOM (SCU)» (Этап 3):
 * 1. Параметры PACS (IP/hostname, Порт, Called AE Title, Calling AE Title).
 * 2. Кнопки C-ECHO и C-STORE одинаковой высоты с блокировкой повторного запуска во время выполнения операции.
 * 3. Русифицированные поля тестового пациента непосредственно на основном экране (дата ДД.ММ.ГГГГ -> YYYYMMDD, пол M/F/O/"", модальность OT по умолчанию).
 * 4. Генератор тестового изображения 512×512 (8-bit MONOCHROME2):
 *    - Режим A: многострочный текст с поддержкой кириллицы и авторазмещением.
 *    - Режим B: процедурный генератор случайных смайликов с кнопкой «Сгенерировать заново».
 *    - Режим C: холст рисования пальцем/стилусом с настройкой толщины линии и очисткой.
 * 5. Предпросмотр реальных Pixel Data (512×512).
 * 6. Карточка результата последней операции SCU.
 */
@Composable
fun SendScuScreen(
    config: ScuSendConfig,
    preparedObject: PreparedSecondaryCaptureDicomObject,
    freehandStrokes: List<FreehandStroke>,
    isExpandedWidth: Boolean,
    candidateFiles: List<ScuCandidateFile> = emptyList(),
    batchProgress: ScuBatchProgressState = ScuBatchProgressState(),
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onSetSendMode: (ScuSendMode) -> Unit = {},
    onAddCandidateFiles: (List<Uri>) -> Unit = {},
    onScanCandidateFolder: (Uri) -> Unit = {},
    onCancelFolderScan: () -> Unit = {},
    onRemoveCandidateFile: (String) -> Unit = {},
    onClearCandidateFiles: () -> Unit = {},
    onTriggerCStoreBatch: () -> Unit = {},
    onCancelBatchSend: () -> Unit = {},
    onUpdateBirthDateDigits: (String) -> Unit,
    onSetImageMode: (TestImageMode) -> Unit,
    onRegenerateSmiley: () -> Unit,
    onSetBrushThickness: (Float) -> Unit,
    onAddStroke: (List<NormalizedPoint>) -> Unit,
    onClearCanvas: () -> Unit,
    onRegenerateUids: () -> Unit,
    onTriggerCEcho: () -> Unit,
    onTriggerCStore: () -> Unit,
    onOpenGeneratorSubScreen: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useTwoColumnLayout = isExpandedWidth || isLandscape

    val singleFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onAddCandidateFiles(listOf(it)) }
    }
    val multiFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAddCandidateFiles(uris)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onScanCandidateFolder(it) }
    }

    val validFilesCount = candidateFiles.count { it.isValidDicom }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (config.isOperationInProgress || !config.lastStatusMessage.isNullOrBlank() || batchProgress.isSending) {
            LastScuOperationResultBanner(config = config, batchProgress = batchProgress)
        }

        // Переключатель режимов: «Тестовое изображение» / «DICOM-файлы»
        ScuModeSelector(
            currentMode = config.sendMode,
            onSelectMode = onSetSendMode,
            isBusy = config.isOperationInProgress || batchProgress.isSending
        )

        if (config.sendMode == ScuSendMode.TEST_IMAGE) {
            if (useTwoColumnLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PacsConnectionCard(
                            config = config,
                            sendMode = ScuSendMode.TEST_IMAGE,
                            validCandidateFilesCount = validFilesCount,
                            onUpdateConfig = onUpdateConfig,
                            onTriggerCEcho = onTriggerCEcho,
                            onTriggerCStore = onTriggerCStore,
                            onTriggerCStoreBatch = onTriggerCStoreBatch
                        )
                        PatientMetadataCard(
                            config = config,
                            onUpdateConfig = onUpdateConfig,
                            onUpdateBirthDateDigits = onUpdateBirthDateDigits
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TestImageGeneratorCard(
                            config = config,
                            preparedObject = preparedObject,
                            freehandStrokes = freehandStrokes,
                            onUpdateConfig = onUpdateConfig,
                            onSetImageMode = onSetImageMode,
                            onRegenerateSmiley = onRegenerateSmiley,
                            onSetBrushThickness = onSetBrushThickness,
                            onAddStroke = onAddStroke,
                            onClearCanvas = onClearCanvas,
                            onRegenerateUids = onRegenerateUids,
                            onOpenFullScreen = onOpenGeneratorSubScreen
                        )
                    }
                }
            } else {
                PacsConnectionCard(
                    config = config,
                    sendMode = ScuSendMode.TEST_IMAGE,
                    validCandidateFilesCount = validFilesCount,
                    onUpdateConfig = onUpdateConfig,
                    onTriggerCEcho = onTriggerCEcho,
                    onTriggerCStore = onTriggerCStore,
                    onTriggerCStoreBatch = onTriggerCStoreBatch
                )
                PatientMetadataCard(
                    config = config,
                    onUpdateConfig = onUpdateConfig,
                    onUpdateBirthDateDigits = onUpdateBirthDateDigits
                )
                TestImageGeneratorCard(
                    config = config,
                    preparedObject = preparedObject,
                    freehandStrokes = freehandStrokes,
                    onUpdateConfig = onUpdateConfig,
                    onSetImageMode = onSetImageMode,
                    onRegenerateSmiley = onRegenerateSmiley,
                    onSetBrushThickness = onSetBrushThickness,
                    onAddStroke = onAddStroke,
                    onClearCanvas = onClearCanvas,
                    onRegenerateUids = onRegenerateUids,
                    onOpenFullScreen = onOpenGeneratorSubScreen
                )
            }
        } else {
            // Режим «DICOM-файлы»: форма тестового пациента и генератор изображения скрыты!
            if (useTwoColumnLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PacsConnectionCard(
                            config = config,
                            sendMode = ScuSendMode.DICOM_FILES,
                            validCandidateFilesCount = validFilesCount,
                            onUpdateConfig = onUpdateConfig,
                            onTriggerCEcho = onTriggerCEcho,
                            onTriggerCStore = onTriggerCStore,
                            onTriggerCStoreBatch = onTriggerCStoreBatch
                        )
                        DicomFilesPickerCard(
                            candidateFilesCount = candidateFiles.size,
                            isBusy = batchProgress.isSending || batchProgress.isScanning,
                            onPickSingleFile = { singleFileLauncher.launch(arrayOf("*/*")) },
                            onPickMultipleFiles = { multiFileLauncher.launch(arrayOf("*/*")) },
                            onPickFolder = { folderLauncher.launch(null) },
                            onClearFiles = onClearCandidateFiles
                        )
                        if (batchProgress.isScanning) {
                            FolderScanProgressCard(
                                progress = batchProgress,
                                onCancelScan = onCancelFolderScan
                            )
                        }
                        if (batchProgress.isSending) {
                            BatchSendProgressCard(
                                progress = batchProgress,
                                onCancelSend = onCancelBatchSend
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        DicomFilesQueueCard(
                            candidateFiles = candidateFiles,
                            isSending = batchProgress.isSending,
                            onRemoveFile = onRemoveCandidateFile
                        )
                    }
                }
            } else {
                PacsConnectionCard(
                    config = config,
                    sendMode = ScuSendMode.DICOM_FILES,
                    validCandidateFilesCount = validFilesCount,
                    onUpdateConfig = onUpdateConfig,
                    onTriggerCEcho = onTriggerCEcho,
                    onTriggerCStore = onTriggerCStore,
                    onTriggerCStoreBatch = onTriggerCStoreBatch
                )
                DicomFilesPickerCard(
                    candidateFilesCount = candidateFiles.size,
                    isBusy = batchProgress.isSending || batchProgress.isScanning,
                    onPickSingleFile = { singleFileLauncher.launch(arrayOf("*/*")) },
                    onPickMultipleFiles = { multiFileLauncher.launch(arrayOf("*/*")) },
                    onPickFolder = { folderLauncher.launch(null) },
                    onClearFiles = onClearCandidateFiles
                )
                if (batchProgress.isScanning) {
                    FolderScanProgressCard(
                        progress = batchProgress,
                        onCancelScan = onCancelFolderScan
                    )
                }
                if (batchProgress.isSending) {
                    BatchSendProgressCard(
                        progress = batchProgress,
                        onCancelSend = onCancelBatchSend
                    )
                }
                DicomFilesQueueCard(
                    candidateFiles = candidateFiles,
                    isSending = batchProgress.isSending,
                    onRemoveFile = onRemoveCandidateFile
                )
            }
        }
    }
}


@Composable
private fun LastScuOperationResultBanner(
    config: ScuSendConfig,
    batchProgress: ScuBatchProgressState = ScuBatchProgressState()
) {
    val isBusy = config.isOperationInProgress || batchProgress.isSending
    val isWarning = config.isLastOperationWarning || (batchProgress.failedCount > 0 && batchProgress.sentSuccessCount > 0)
    val isSuccess = if (batchProgress.totalFiles > 0 && !batchProgress.isSending) {
        batchProgress.failedCount == 0
    } else {
        config.isLastOperationSuccess
    }

    val bgColor = when {
        isBusy -> PrimaryBlueLight
        isWarning -> WarningAmber.copy(alpha = 0.12f)
        isSuccess == true -> SuccessGreenBackground
        isSuccess == false -> ErrorRed.copy(alpha = 0.10f)
        else -> InputBackgroundGray
    }
    val borderColor = when {
        isBusy -> PrimaryBlue
        isWarning -> WarningAmber.copy(alpha = 0.45f)
        isSuccess == true -> SuccessGreenBorder
        isSuccess == false -> ErrorRed.copy(alpha = 0.45f)
        else -> CardBorderGray
    }
    val accentColor = when {
        isBusy -> PrimaryBlue
        isWarning -> WarningAmber
        isSuccess == true -> SuccessGreen
        isSuccess == false -> ErrorRed
        else -> TextPrimaryDark
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = PrimaryBlue,
                    strokeWidth = 2.2.dp
                )
            } else {
                val icon = when {
                    isWarning -> Icons.Default.WarningAmber
                    isSuccess == true -> Icons.Default.CheckCircle
                    else -> Icons.Default.ErrorOutline
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val title = if (batchProgress.isSending) {
                        "Отправка DICOM-файлов…"
                    } else {
                        config.lastStatusTitle
                    }
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    val msg = if (batchProgress.isSending) {
                        "Передано ${batchProgress.sentSuccessCount} из ${batchProgress.totalFiles}" +
                            if (batchProgress.currentFileName != null) " (${batchProgress.currentFileName})" else ""
                    } else {
                        config.lastStatusMessage
                    }
                    if (!msg.isNullOrBlank()) {
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
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
private fun PacsConnectionCard(
    config: ScuSendConfig,
    sendMode: ScuSendMode,
    validCandidateFilesCount: Int,
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onTriggerCEcho: () -> Unit,
    onTriggerCStore: () -> Unit,
    onTriggerCStoreBatch: () -> Unit
) {
    val isBusy = config.isOperationInProgress

    WhiteDiagnosticCard {
        Text(
            text = "Параметры PACS",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DiagnosticOutlinedField(
                label = "IP-адрес или хост",
                value = config.remoteIp,
                onValueChange = { ip -> onUpdateConfig { it.copy(remoteIp = ip) } },
                modifier = Modifier.weight(0.65f)
            )
            DiagnosticOutlinedField(
                label = "Порт",
                value = if (config.remotePort > 0) config.remotePort.toString() else "",
                onValueChange = { p ->
                    val digits = p.filter { it.isDigit() }.take(5)
                    val port = digits.toIntOrNull() ?: 0
                    onUpdateConfig { it.copy(remotePort = port.coerceIn(0, 65535)) }
                },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(0.35f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DiagnosticOutlinedField(
                label = "Called AE Title",
                value = config.calledAeTitle,
                onValueChange = { ae -> onUpdateConfig { it.copy(calledAeTitle = ae.uppercase().take(16)) } },
                modifier = Modifier.weight(0.5f)
            )
            DiagnosticOutlinedField(
                label = "Calling AE Title",
                value = config.callingAeTitle,
                onValueChange = { ae -> onUpdateConfig { it.copy(callingAeTitle = ae.uppercase().take(16)) } },
                modifier = Modifier.weight(0.5f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Кнопки C-ECHO и C-STORE одинаковой высоты и ширины с блокировкой повторного нажатия во время выполнения
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onTriggerCEcho,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                border = BorderStroke(1.5.dp, if (isBusy) CardBorderGray else PrimaryBlue),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CardSurfaceWhite,
                    contentColor = PrimaryBlue,
                    disabledContainerColor = InputBackgroundGray,
                    disabledContentColor = TextSecondarySlate
                )
            ) {
                if (isBusy && config.activeOperationName == "C-ECHO") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PrimaryBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "C-ECHO",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val cStoreEnabled = if (sendMode == ScuSendMode.TEST_IMAGE) {
                !isBusy
            } else {
                !isBusy && validCandidateFilesCount > 0
            }

            val cStoreButtonText = if (sendMode == ScuSendMode.TEST_IMAGE) {
                "C-STORE"
            } else {
                if (validCandidateFilesCount > 0) "C-STORE ($validCandidateFilesCount)" else "C-STORE"
            }

            Button(
                onClick = {
                    if (sendMode == ScuSendMode.TEST_IMAGE) {
                        onTriggerCStore()
                    } else {
                        onTriggerCStoreBatch()
                    }
                },
                enabled = cStoreEnabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryBlue,
                    contentColor = Color.White,
                    disabledContainerColor = CardBorderGray,
                    disabledContentColor = TextSecondarySlate
                )
            ) {
                if (isBusy && (config.activeOperationName == "C-STORE" || config.activeOperationName == "C-STORE (пакет)")) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = cStoreButtonText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Карточка «Данные тестового пациента» на основном экране отправки с русифицированными подписями.
 */
@Composable
fun PatientMetadataCard(
    config: ScuSendConfig,
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onUpdateBirthDateDigits: (String) -> Unit
) {
    WhiteDiagnosticCard {
        Text(
            text = "Данные тестового пациента",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark
        )
        Spacer(modifier = Modifier.height(10.dp))
        PatientMetadataFormFields(
            config = config,
            onUpdateConfig = onUpdateConfig,
            onUpdateBirthDateDigits = onUpdateBirthDateDigits
        )
    }
}

@Composable
fun PatientMetadataFormFields(
    config: ScuSendConfig,
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onUpdateBirthDateDigits: (String) -> Unit
) {
    var sexMenuExpanded by remember { mutableStateOf(false) }
    var modalityMenuExpanded by remember { mutableStateOf(false) }
    val selectedSexOption = PatientSexOption.fromDicomCode(config.patientSex)
    val dateValidation = remember(config.patientBirthDateDigits) {
        DicomDateUtils.validateBirthDateDigits(config.patientBirthDateDigits)
    }
    val isDateError = dateValidation is DateValidationResult.Invalid

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalFormFieldRow(
            label = "ФИО пациента",
            value = config.patientName,
            onValueChange = { v -> onUpdateConfig { it.copy(patientName = v) } }
        )
        HorizontalFormFieldRow(
            label = "ID пациента",
            value = config.patientId,
            onValueChange = { v -> onUpdateConfig { it.copy(patientId = v) } }
        )

        // Автоматическая маска ввода ДД.ММ.ГГГГ без вызова календаря (KeyboardType.Number, 8 цифр)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Дата рождения",
                fontSize = 13.sp,
                color = TextSecondarySlate,
                modifier = Modifier
                    .weight(0.38f)
                    .padding(end = 6.dp)
            )
            OutlinedTextField(
                value = config.patientBirthDateDigits,
                onValueChange = { input ->
                    val cleanDigits = input.filter { it.isDigit() }.take(8)
                    onUpdateBirthDateDigits(cleanDigits)
                },
                singleLine = true,
                placeholder = { Text("ДД.ММ.ГГГГ", fontSize = 13.sp, color = TextSecondarySlate) },
                visualTransformation = remember { DateOfBirthVisualTransformation() },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = isDateError,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(0.62f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardSurfaceWhite,
                    unfocusedContainerColor = CardSurfaceWhite,
                    focusedBorderColor = if (isDateError) ErrorRed else PrimaryBlue,
                    unfocusedBorderColor = if (isDateError) ErrorRed else CardBorderGray,
                    focusedTextColor = TextPrimaryDark,
                    unfocusedTextColor = TextPrimaryDark
                )
            )
        }

        when (dateValidation) {
            is DateValidationResult.Valid -> {
                Text(
                    text = "Формат ДД.ММ.ГГГГ → в DICOM (0010,0030): ${dateValidation.dicomDa}",
                    fontSize = 11.sp,
                    color = SuccessGreen
                )
            }
            is DateValidationResult.ValidEmpty -> {
                Text(
                    text = "Формат ДД.ММ.ГГГГ → в DICOM (0010,0030): пустое значение",
                    fontSize = 11.sp,
                    color = TextSecondarySlate
                )
            }
            is DateValidationResult.Incomplete -> {
                Text(
                    text = "Ввод даты не завершён (требуется 8 цифр: ДД.ММ.ГГГГ)",
                    fontSize = 11.sp,
                    color = TextSecondarySlate
                )
            }
            is DateValidationResult.Invalid -> {
                Text(
                    text = dateValidation.messageRu,
                    fontSize = 11.sp,
                    color = ErrorRed
                )
            }
        }

        // Пол пациента: Мужской (M), Женский (F), Другой (O), Не указан ("")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Пол",
                fontSize = 13.sp,
                color = TextSecondarySlate,
                modifier = Modifier
                    .weight(0.38f)
                    .padding(end = 6.dp)
            )
            Box(modifier = Modifier.weight(0.62f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sexMenuExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = CardSurfaceWhite,
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedSexOption.labelRu,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimaryDark
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TextSecondarySlate
                        )
                    }
                }
                DropdownMenu(
                    expanded = sexMenuExpanded,
                    onDismissRequest = { sexMenuExpanded = false },
                    modifier = Modifier.background(CardSurfaceWhite)
                ) {
                    PatientSexOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.labelRu, color = TextPrimaryDark) },
                            onClick = {
                                sexMenuExpanded = false
                                onUpdateConfig { it.copy(patientSex = option.dicomCode) }
                            }
                        )
                    }
                }
            }
        }

        // Модальность (0008,0060) — по умолчанию OT. Выбор не меняет Secondary Capture SOP Class UID.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Модальность",
                fontSize = 13.sp,
                color = TextSecondarySlate,
                modifier = Modifier
                    .weight(0.38f)
                    .padding(end = 6.dp)
            )
            Box(modifier = Modifier.weight(0.62f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { modalityMenuExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    color = CardSurfaceWhite,
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = config.modality,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimaryDark
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TextSecondarySlate
                        )
                    }
                }
                DropdownMenu(
                    expanded = modalityMenuExpanded,
                    onDismissRequest = { modalityMenuExpanded = false },
                    modifier = Modifier.background(CardSurfaceWhite)
                ) {
                    DicomSecondaryCaptureGenerator.SUPPORTED_MODALITIES.forEach { (modCode, modDescription) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = modDescription,
                                    color = TextPrimaryDark,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                modalityMenuExpanded = false
                                onUpdateConfig {
                                    it.copy(
                                        modality = modCode,
                                        sopClassUid = DicomSecondaryCaptureGenerator.SECONDARY_CAPTURE_SOP_CLASS_UID
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        HorizontalFormFieldRow(
            label = "Описание исследования",
            value = config.studyDescription,
            onValueChange = { v -> onUpdateConfig { it.copy(studyDescription = v) } }
        )
        HorizontalFormFieldRow(
            label = "Описание серии",
            value = config.seriesDescription,
            onValueChange = { v -> onUpdateConfig { it.copy(seriesDescription = v) } }
        )
    }
}

@Composable
private fun HorizontalFormFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondarySlate,
            softWrap = true,
            modifier = Modifier
                .weight(0.38f)
                .padding(end = 6.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = if (placeholder != null) {
                { Text(placeholder, fontSize = 13.sp, color = TextSecondarySlate) }
            } else {
                null
            },
            shape = RoundedCornerShape(8.dp),
            trailingIcon = trailingIcon,
            modifier = Modifier.weight(0.62f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardSurfaceWhite,
                unfocusedContainerColor = CardSurfaceWhite,
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = CardBorderGray,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            )
        )
    }
}

/**
 * Карточка и генератор «Тестовое изображение» с реальными подготовленными UID Secondary Capture и предпросмотром 512×512.
 */
@Composable
fun TestImageGeneratorCard(
    config: ScuSendConfig,
    preparedObject: PreparedSecondaryCaptureDicomObject,
    freehandStrokes: List<FreehandStroke>,
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onSetImageMode: (TestImageMode) -> Unit,
    onRegenerateSmiley: () -> Unit,
    onSetBrushThickness: (Float) -> Unit,
    onAddStroke: (List<NormalizedPoint>) -> Unit,
    onClearCanvas: () -> Unit,
    onRegenerateUids: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenFullScreen: (() -> Unit)? = null
) {
    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Тестовое изображение",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            TextButton(onClick = onRegenerateUids) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Новые UID", fontSize = 12.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TestImageGeneratorContent(
            config = config,
            preparedObject = preparedObject,
            freehandStrokes = freehandStrokes,
            onUpdateConfig = onUpdateConfig,
            onSetImageMode = onSetImageMode,
            onRegenerateSmiley = onRegenerateSmiley,
            onSetBrushThickness = onSetBrushThickness,
            onAddStroke = onAddStroke,
            onClearCanvas = onClearCanvas,
            onRegenerateUids = onRegenerateUids
        )
    }
}

@Composable
fun TestImageGeneratorContent(
    config: ScuSendConfig,
    preparedObject: PreparedSecondaryCaptureDicomObject,
    freehandStrokes: List<FreehandStroke>,
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onSetImageMode: (TestImageMode) -> Unit,
    onRegenerateSmiley: () -> Unit = {},
    onSetBrushThickness: (Float) -> Unit = {},
    onAddStroke: (List<NormalizedPoint>) -> Unit,
    onClearCanvas: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onRegenerateUids: () -> Unit = {}
) {
    val activeStrokePoints = remember { mutableStateListOf<NormalizedPoint>() }

    // Создаём реальный Bitmap предпросмотра 512×512 из фактического буфера Pixel Data подготовленного объекта
    val previewBitmap = remember(preparedObject.contentSignature, preparedObject.uids.sopInstanceUid) {
        ScuTestImageRenderer.createPreviewBitmapFromGray8(
            grayPixels = preparedObject.pixelDataBytes,
            width = DicomSecondaryCaptureGenerator.DEFAULT_ROWS,
            height = DicomSecondaryCaptureGenerator.DEFAULT_COLUMNS
        )?.asImageBitmap()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Три кнопки режима: [Текст] [Смайлик] [Рисунок]
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = InputBackgroundGray,
            border = BorderStroke(1.dp, CardBorderGray)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GeneratorModeTab(
                    label = "Текст",
                    icon = Icons.Default.TextFields,
                    selected = config.imageMode == TestImageMode.TEXT,
                    onClick = { onSetImageMode(TestImageMode.TEXT) },
                    modifier = Modifier.weight(1f)
                )
                GeneratorModeTab(
                    label = "Смайлик",
                    icon = Icons.Default.SentimentSatisfiedAlt,
                    selected = config.imageMode == TestImageMode.EMOJI,
                    onClick = { onSetImageMode(TestImageMode.EMOJI) },
                    modifier = Modifier.weight(1f)
                )
                GeneratorModeTab(
                    label = "Рисунок",
                    icon = Icons.Default.Create,
                    selected = config.imageMode == TestImageMode.FREEHAND,
                    onClick = { onSetImageMode(TestImageMode.FREEHAND) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        when (config.imageMode) {
            TestImageMode.TEXT -> {
                Text(
                    text = "Многострочный текст на изображении (поддерживается кириллица и латиница):",
                    fontSize = 13.sp,
                    color = TextSecondarySlate
                )
                OutlinedTextField(
                    value = config.customText,
                    onValueChange = { newText ->
                        val lines = newText.lines()
                        onUpdateConfig {
                            it.copy(
                                customText = newText,
                                customTextLine1 = lines.getOrNull(0) ?: "",
                                customTextLine2 = lines.getOrNull(1) ?: ""
                            )
                        }
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardSurfaceWhite,
                        unfocusedContainerColor = CardSurfaceWhite,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = CardBorderGray,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )
            }

            TestImageMode.EMOJI -> {
                val smileySpec = remember(config.smileySeed) {
                    ScuTestImageRenderer.createSmileySpec(config.smileySeed)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Случайный процедурный смайлик (#${(config.smileySeed and 0xFFFFL)})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = smileySpec.summaryRu,
                            fontSize = 12.sp,
                            color = TextSecondarySlate,
                            softWrap = true
                        )
                    }
                    OutlinedButton(
                        onClick = onRegenerateSmiley,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.2.dp, PrimaryBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Casino,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Сгенерировать заново",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue
                        )
                    }
                }
            }

            TestImageMode.FREEHAND -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Толщина линии: ${config.brushThicknessPx.roundToInt()} пикс.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimaryDark
                        )
                        TextButton(
                            onClick = {
                                activeStrokePoints.clear()
                                onClearCanvas()
                            }
                        ) {
                            Text("Очистить холст", fontSize = 12.sp, color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Slider(
                        value = config.brushThicknessPx,
                        onValueChange = { onSetBrushThickness(it) },
                        valueRange = 2f..24f,
                        steps = 10,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue,
                            inactiveTrackColor = CardBorderGray
                        )
                    )
                }
            }
        }

        Text(
            text = "Предпросмотр (512 × 512, 8-bit MONOCHROME2):",
            fontSize = 13.sp,
            color = TextSecondarySlate
        )

        // Квадратное чёрное окно предпросмотра реального Pixel Data (512 × 512)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(256.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .widthIn(max = 256.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DicomViewerCanvasBlack)
                    .pointerInput(config.imageMode, config.brushThicknessPx) {
                        if (config.imageMode == TestImageMode.FREEHAND) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    activeStrokePoints.clear()
                                    val w = size.width.coerceAtLeast(1)
                                    val h = size.height.coerceAtLeast(1)
                                    activeStrokePoints.add(
                                        NormalizedPoint(
                                            x = (offset.x / w).coerceIn(0f, 1f),
                                            y = (offset.y / h).coerceIn(0f, 1f)
                                        )
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val w = size.width.coerceAtLeast(1)
                                    val h = size.height.coerceAtLeast(1)
                                    activeStrokePoints.add(
                                        NormalizedPoint(
                                            x = (change.position.x / w).coerceIn(0f, 1f),
                                            y = (change.position.y / h).coerceIn(0f, 1f)
                                        )
                                    )
                                },
                                onDragEnd = {
                                    if (activeStrokePoints.isNotEmpty()) {
                                        onAddStroke(activeStrokePoints.toList())
                                        activeStrokePoints.clear()
                                    }
                                },
                                onDragCancel = {
                                    activeStrokePoints.clear()
                                }
                            )
                        }
                    }
                    .pointerInput(config.imageMode, config.brushThicknessPx) {
                        if (config.imageMode == TestImageMode.FREEHAND) {
                            detectTapGestures { offset ->
                                val w = size.width.coerceAtLeast(1)
                                val h = size.height.coerceAtLeast(1)
                                onAddStroke(
                                    listOf(
                                        NormalizedPoint(
                                            x = (offset.x / w).coerceIn(0f, 1f),
                                            y = (offset.y / h).coerceIn(0f, 1f)
                                        )
                                    )
                                )
                            }
                        }
                    }
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Предпросмотр DICOM 512x512",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Medium
                    )
                }

                // Отрисовка текущего незавершённого штриха поверх холста во время ведения пальца/стилуса
                if (config.imageMode == TestImageMode.FREEHAND) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (activeStrokePoints.size > 1) {
                            val path = Path().apply {
                                val first = activeStrokePoints.first()
                                moveTo(first.x * size.width, first.y * size.height)
                                for (i in 1 until activeStrokePoints.size) {
                                    val pt = activeStrokePoints[i]
                                    lineTo(pt.x * size.width, pt.y * size.height)
                                }
                            }
                            val scaledStrokeWidth = (config.brushThicknessPx / 512f) * size.minDimension
                            drawPath(
                                path = path,
                                color = Color.White,
                                style = Stroke(
                                    width = scaledStrokeWidth.coerceAtLeast(2f),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    if (freehandStrokes.isEmpty() && activeStrokePoints.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Проведите пальцем или стилусом для рисования",
                                fontSize = 12.sp,
                                color = Color.LightGray.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Размер: 512 × 512 (${preparedObject.pixelDataBytes.size / 1024} КБ)",
                fontSize = 12.sp,
                color = TextSecondarySlate
            )
            Text(
                text = "8-bit Gray (MONOCHROME2, Explicit VR LE)",
                fontSize = 12.sp,
                color = TextSecondarySlate
            )
        }

        // Реальные сгенерированные UID тестового объекта Secondary Capture
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = InputBackgroundGray,
            border = BorderStroke(1.dp, CardBorderGray)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "SOP Class UID: ${preparedObject.uids.sopClassUid} (Secondary Capture)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
                    Text(
                        text = "Study Instance UID: ${preparedObject.uids.studyInstanceUid}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                    Text(
                        text = "Series Instance UID: ${preparedObject.uids.seriesInstanceUid}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                    Text(
                        text = "SOP Instance UID: ${preparedObject.uids.sopInstanceUid}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratorModeTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) CardSurfaceWhite else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, PrimaryBlue) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) PrimaryBlue else TextSecondarySlate,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) PrimaryBlue else TextSecondarySlate,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Отдельный подэкран «Тестовое изображение» (генератор изображения) со стрелкой назад.
 */
@Composable
fun ScuImageGeneratorSubScreen(
    config: ScuSendConfig,
    preparedObject: PreparedSecondaryCaptureDicomObject,
    freehandStrokes: List<FreehandStroke>,
    onUpdateConfig: ((ScuSendConfig) -> ScuSendConfig) -> Unit,
    onSetImageMode: (TestImageMode) -> Unit,
    onRegenerateSmiley: () -> Unit = {},
    onSetBrushThickness: (Float) -> Unit = {},
    onAddStroke: (List<NormalizedPoint>) -> Unit,
    onClearCanvas: () -> Unit,
    onRegenerateUids: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CardSurfaceWhite)
    ) {
        DicomToolSubScreenTopBar(
            title = "Отправка DICOM (SCU)",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Тестовое изображение",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            TestImageGeneratorContent(
                config = config,
                preparedObject = preparedObject,
                freehandStrokes = freehandStrokes,
                onUpdateConfig = onUpdateConfig,
                onSetImageMode = onSetImageMode,
                onRegenerateSmiley = onRegenerateSmiley,
                onSetBrushThickness = onSetBrushThickness,
                onAddStroke = onAddStroke,
                onClearCanvas = onClearCanvas,
                onRegenerateUids = onRegenerateUids
            )
        }
    }
}

/**
 * Переключатель режимов: «Тестовое изображение» / «DICOM-файлы».
 */
@Composable
fun ScuModeSelector(
    currentMode: ScuSendMode,
    onSelectMode: (ScuSendMode) -> Unit,
    isBusy: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = InputBackgroundGray,
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val modes = listOf(
                ScuSendMode.TEST_IMAGE to "Тестовое изображение",
                ScuSendMode.DICOM_FILES to "DICOM-файлы"
            )
            modes.forEach { (mode, label) ->
                val selected = currentMode == mode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isBusy) { onSelectMode(mode) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) CardSurfaceWhite else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, PrimaryBlue) else null
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) PrimaryBlue else TextSecondarySlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Карточка выбора DICOM-файлов: выбор одного файла, выбор нескольких файлов, выбор папки (с рекурсией).
 */
@Composable
fun DicomFilesPickerCard(
    candidateFilesCount: Int,
    isBusy: Boolean,
    onPickSingleFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onClearFiles: () -> Unit
) {
    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Выбор DICOM-файлов",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            if (candidateFilesCount > 0 && !isBusy) {
                TextButton(
                    onClick = onClearFiles,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Очистить список",
                        tint = ErrorRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Очистить",
                        fontSize = 12.sp,
                        color = ErrorRed
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPickSingleFile,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Файл",
                    fontSize = 12.sp,
                    color = TextPrimaryDark,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onPickMultipleFiles,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1.1f)
                    .heightIn(min = 42.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Несколько",
                    fontSize = 12.sp,
                    color = TextPrimaryDark,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onPickFolder,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1.1f)
                    .heightIn(min = 42.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Папка",
                    fontSize = 12.sp,
                    color = TextPrimaryDark,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Поддерживаются одиночные файлы, выбор нескольких документов и папки с рекурсивным поиском DICOM (в т. ч. без расширения .dcm).",
            fontSize = 11.sp,
            color = TextSecondarySlate,
            lineHeight = 15.sp
        )
    }
}

/**
 * Карточка прогресса фонового сканирования каталога.
 */
@Composable
fun FolderScanProgressCard(
    progress: ScuBatchProgressState,
    onCancelScan: () -> Unit
) {
    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = PrimaryBlue,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Сканирование папки…",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            }
            TextButton(
                onClick = onCancelScan,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(text = "Отмена", fontSize = 12.sp, color = ErrorRed)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Просканировано файлов: ${progress.scannedCount}" +
                if (progress.currentFileName != null) " (${progress.currentFileName})" else "",
            fontSize = 12.sp,
            color = TextSecondarySlate
        )
    }
}

/**
 * Карточка хода пакетной отправки C-STORE с линейным индикатором и кнопкой прерывания.
 */
@Composable
fun BatchSendProgressCard(
    progress: ScuBatchProgressState,
    onCancelSend: () -> Unit
) {
    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Передача DICOM-файлов",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryBlue
                )
                Text(
                    text = "Успешно: ${progress.sentSuccessCount} / ${progress.totalFiles}" +
                        if (progress.failedCount > 0) ", Ошибок: ${progress.failedCount}" else "",
                    fontSize = 12.sp,
                    color = TextSecondarySlate
                )
            }
            TextButton(
                onClick = onCancelSend,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(text = "Прервать", fontSize = 12.sp, color = ErrorRed, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val fraction = if (progress.totalFiles > 0) {
            (progress.sentSuccessCount + progress.failedCount).toFloat() / progress.totalFiles.toFloat()
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = PrimaryBlue,
            trackColor = InputBackgroundGray
        )

        if (!progress.currentFileName.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Текущий файл: ${progress.currentFileName}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondarySlate,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Карточка списка очередей файлов для отправки с метаданными и валидацией.
 */
@Composable
fun DicomFilesQueueCard(
    candidateFiles: List<ScuCandidateFile>,
    isSending: Boolean,
    onRemoveFile: (String) -> Unit
) {
    val totalBytes = candidateFiles.sumOf { it.fileSizeBytes }
    val validCount = candidateFiles.count { it.isValidDicom }
    val invalidCount = candidateFiles.size - validCount

    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Файлы к отправке",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            Text(
                text = "${candidateFiles.size} шт. (${formatFileSize(totalBytes)})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondarySlate
            )
        }

        if (candidateFiles.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = CardBorderGray,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "Файлы не выбраны",
                        fontSize = 13.sp,
                        color = TextSecondarySlate
                    )
                    Text(
                        text = "Выберите отдельные файлы или папку для отправки",
                        fontSize = 11.sp,
                        color = TextSecondarySlate
                    )
                }
            }
        } else {
            if (invalidCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = WarningAmber.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Файлов с ошибками формата/не DICOM: $invalidCount (будут пропущены)",
                            fontSize = 11.sp,
                            color = TextPrimaryDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                candidateFiles.forEach { file ->
                    DicomCandidateFileItemRow(
                        file = file,
                        isSending = isSending,
                        onRemove = { onRemoveFile(file.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DicomCandidateFileItemRow(
    file: ScuCandidateFile,
    isSending: Boolean,
    onRemove: () -> Unit
) {
    val borderColor = when (file.sendStatus) {
        FileSendStatus.SUCCESS -> SuccessGreenBorder
        FileSendStatus.WARNING -> WarningAmber.copy(alpha = 0.45f)
        FileSendStatus.FAILED -> ErrorRed.copy(alpha = 0.45f)
        FileSendStatus.SENDING -> PrimaryBlue
        FileSendStatus.SKIPPED -> CardBorderGray
        FileSendStatus.PENDING -> if (file.isValidDicom) CardBorderGray else WarningAmber.copy(alpha = 0.45f)
    }

    val bgColor = when (file.sendStatus) {
        FileSendStatus.SUCCESS -> SuccessGreenBackground
        FileSendStatus.WARNING -> WarningAmber.copy(alpha = 0.10f)
        FileSendStatus.FAILED -> ErrorRed.copy(alpha = 0.08f)
        FileSendStatus.SENDING -> PrimaryBlueLight
        FileSendStatus.SKIPPED -> InputBackgroundGray
        FileSendStatus.PENDING -> CardSurfaceWhite
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (file.sendStatus) {
                FileSendStatus.SENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 2.dp),
                        color = PrimaryBlue,
                        strokeWidth = 2.dp
                    )
                }
                FileSendStatus.SUCCESS -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Отправлен",
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                FileSendStatus.WARNING -> {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Предупреждение",
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
                FileSendStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Ошибка",
                        tint = ErrorRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = if (file.isValidDicom) Icons.Default.InsertDriveFile else Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = if (file.isValidDicom) PrimaryBlue else WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = file.fileName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatFileSize(file.fileSizeBytes),
                        fontSize = 11.sp,
                        color = TextSecondarySlate
                    )
                }

                if (file.isValidDicom) {
                    Text(
                        text = "Модальность: ${file.modality ?: "—"}  •  Пациент: ${file.patientId ?: "—"}",
                        fontSize = 11.sp,
                        color = TextSecondarySlate
                    )
                    if (!file.studyInstanceUid.isNullOrBlank()) {
                        Text(
                            text = "Study: ${file.studyInstanceUid}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondarySlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!file.sopClassUid.isNullOrBlank()) {
                        Text(
                            text = "SOP: ${file.sopClassUid}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondarySlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (!file.validationError.isNullOrBlank()) {
                    Text(
                        text = file.validationError,
                        fontSize = 11.sp,
                        color = ErrorRed
                    )
                }

                if (!file.sendErrorMessage.isNullOrBlank()) {
                    Text(
                        text = "Ошибка отправки: ${file.sendErrorMessage}",
                        fontSize = 11.sp,
                        color = ErrorRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (!isSending && file.sendStatus == FileSendStatus.PENDING) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Удалить из очереди",
                    tint = TextSecondarySlate,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onRemove)
                )
            }
        }
    }
}

/**
 * Форматирование размера файла в КБ / МБ.
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

