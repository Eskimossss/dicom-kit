package ru.medeng.dicomtool.ui.screens.tags

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.domain.model.DicomEncodingOverride
import ru.medeng.dicomtool.domain.model.DicomFileSummary
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.viewmodel.DicomViewerRenderState

/**
 * Экран раздела «DICOM-теги» (5-я вкладка нижней навигации).
 * Поддерживает:
 * - Выбор любого локального DICOM-файла через Storage Access Framework (SAF)
 * - Единая адаптивная рабочая область с изображением, WW/WL и браузером тегов
 * - Единый просмотрщик изображений с поддержкой полноэкранного режима, WW/WL, зума и кадров
 */
@Composable
fun DicomTagsScreen(
    summary: DicomFileSummary?,
    tags: List<DicomTagEntry>,
    isLoading: Boolean,
    errorMessage: String?,
    readNoticeId: String,
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
    onToggleInvert: () -> Unit,
    onAdjustWindow: (Int, Int) -> Unit,
    onSetWindowWidthLevel: (Int, Int) -> Unit,
    onStepFrame: (Int) -> Unit,
    onToggleFullScreen: () -> Unit,
    onEnsureDecoded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onSelectFileUri)
    }
    LaunchedEffect(summary) { if (summary != null) onEnsureDecoded() }
    Column(modifier.fillMaxSize()) {
        if (!isViewerFullScreen) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(summary?.fileName ?: "Выберите DICOM-файл", modifier = Modifier.weight(1f), maxLines = 2, fontSize = 12.sp)
                IconButton(onClick = onClearFile, enabled = summary != null) {
                    Icon(Icons.Outlined.Close, contentDescription = "Закрыть файл")
                }
                OutlinedButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) { Text("Выбрать другой") }
            }
        }
        if (isLoading) CircularProgressIndicator(Modifier.padding(12.dp))
        errorMessage?.let { ru.medeng.dicomtool.ui.components.AppNotice("external-read:$readNoticeId", it, error = true) }
        if (summary != null && !isLoading && errorMessage == null) {
            ru.medeng.dicomtool.ui.components.DicomViewerWorkspace(
                sourceKey = summary.sopClassUid + summary.fileName, diagnosticNoticeId = summary.diagnosticNoticeId,
                metadata = listOf("Пациент" to DicomCharsetDecoder.formatDicomPersonName(summary.patientName),
                    "ID пациента" to summary.patientId, "Дата исследования" to summary.studyDateFormatted,
                    "Описание исследования" to summary.studyDescription, "Серия" to summary.seriesDescription,
                    "Изображение" to "${summary.columns}×${summary.rows} • ${summary.bitsAllocated} bit • ${summary.modality}",
                    "SOP Class" to summary.sopClassName, "Transfer Syntax" to summary.transferSyntaxName,
                    "Кодировка (0008,0005)" to summary.specificCharacterSet),
                tags = tags, query = searchQuery, renderState = viewerRenderState, zoom = viewerZoom, pan = viewerPan,
                inverted = viewerInverted, ww = viewerWindowWidth, wl = viewerWindowCenter,
                frame = viewerCurrentFrame, frames = summary.numberOfFrames.coerceAtLeast(1), fullScreen = isViewerFullScreen,
                encodingWarning = summary.encodingWarning, encodingOverride = encodingOverride, onEncodingOverride = onEncodingOverrideChange,
                onQuery = onSearchQueryChange, onZoom = onZoomFactor, onPan = onPanDelta, onFit = onFitToScreen,
                onResetView = onResetTransform, onResetWindow = onResetWindowOnly, onInvert = onToggleInvert,
                onAdjustWindow = onAdjustWindow, onWindow = onSetWindowWidthLevel, onFrame = onStepFrame,
                onFullScreen = onToggleFullScreen, modifier = Modifier.weight(1f))
        } else if (!isLoading && errorMessage == null) {
            Text("Выберите DICOM-файл для просмотра и анализа тегов", modifier = Modifier.padding(16.dp))
        }
    }
}
