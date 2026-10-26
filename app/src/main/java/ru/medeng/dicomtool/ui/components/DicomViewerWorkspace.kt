package ru.medeng.dicomtool.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.ui.screens.tags.DicomTagsBrowserComponent
import ru.medeng.dicomtool.ui.viewmodel.DicomViewerRenderState
import kotlin.math.roundToInt

/** One workspace for received and external objects; decoding remains owned by the existing ViewModel. */
@Composable
fun DicomViewerWorkspace(
    sourceKey: String, metadata: List<Pair<String, String>>, tags: List<DicomTagEntry>, query: String,
    renderState: DicomViewerRenderState, zoom: Float, pan: Offset, inverted: Boolean,
    ww: Int, wl: Int, frame: Int, frames: Int, modifier: Modifier = Modifier, instance: Int = 1, instances: Int = 1,
    fullScreen: Boolean, initialTab: Int = 0, diagnosticNoticeId: String = sourceKey, encodingWarning: String? = null,
    encodingOverride: DicomEncodingOverride = DicomEncodingOverride.AUTO,
    onEncodingOverride: ((DicomEncodingOverride) -> Unit)? = null,
    onQuery: (String) -> Unit, onZoom: (Float) -> Unit, onPan: (Offset) -> Unit,
    onFit: () -> Unit, onResetView: () -> Unit, onResetWindow: () -> Unit,
    onInvert: () -> Unit, onAdjustWindow: (Int, Int) -> Unit, onWindow: (Int, Int) -> Unit,
    onFrame: (Int) -> Unit, onInstance: (Int) -> Unit = {}, onFullScreen: () -> Unit,
    leadingControls: @Composable () -> Unit = {}
) {
    var tab by rememberSaveable(sourceKey) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val descriptor = (renderState as? DicomViewerRenderState.Ready)?.descriptor
    val viewport: @Composable (Modifier) -> Unit = { target ->
        DicomSharedViewportBox(renderState = renderState, zoom = zoom, pan = pan, inverted = inverted,
            windowWidth = ww, windowCenter = wl, currentFrame = frame, totalFrames = frames,
            currentInstance = instance, totalInstances = instances, isFullScreen = fullScreen,
            onZoomFactor = onZoom, onPanDelta = onPan, onFitToScreen = onFit,
            onResetTransform = onResetView, onToggleInvert = onInvert, onAdjustWindow = onAdjustWindow,
            onStepFrame = onFrame, onStepInstance = onInstance, onToggleFullScreen = onFullScreen, modifier = target)
    }
    val panel: @Composable (Modifier) -> Unit = { target ->
        Column(target) {
            encodingWarning?.let { AppNotice("charset:$diagnosticNoticeId", it) }
            TabRow(selectedTabIndex = tab) {
                listOf("Просмотр и WW/WL", "DICOM-теги").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, fontSize = 12.sp) })
                }
            }
            if (tab == 1) {
                DicomTagsBrowserComponent(tags, query, modifier = Modifier.fillMaxSize(),
                    encodingWarning = null, currentEncodingOverride = encodingOverride,
                    showEncodingSelector = onEncodingOverride != null, onSearchQueryChange = onQuery,
                    onEncodingOverrideChange = onEncodingOverride)
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    leadingControls()
                    metadata.forEach { (key, value) -> KeyValueTableRow(key, value) }
                    Text("WW — контраст; WL — яркость", fontSize = 12.sp)
                    val span = ((descriptor?.maxModalityValue ?: 65535.0) - (descriptor?.minModalityValue ?: 0.0)).coerceAtLeast(255.0)
                    // Ranges may expand for external step buttons but never shrink under a moving thumb.
                    var widthBound by remember(sourceKey, descriptor) { mutableFloatStateOf(maxOf(ww.toDouble(), descriptor?.defaultWindowWidth ?: 255.0, span * 1.5).coerceIn(255.0, 131072.0).toFloat()) }
                    var lowBound by remember(sourceKey, descriptor) { mutableFloatStateOf(minOf(wl.toDouble(), (descriptor?.minModalityValue ?: 0.0) - span / 4).coerceAtLeast(-65536.0).toFloat()) }
                    var highBound by remember(sourceKey, descriptor) { mutableFloatStateOf(maxOf(wl.toDouble(), (descriptor?.maxModalityValue ?: 65535.0) + span / 4).coerceAtMost(131072.0).toFloat()) }
                    val maxWidth = maxOf(widthBound, ww.toFloat())
                    val minCenter = minOf(lowBound, wl.toFloat())
                    val maxCenter = maxOf(highBound, wl.toFloat())
                    SideEffect { widthBound = maxWidth; lowBound = minCenter; highBound = maxCenter }
                    val mono = descriptor?.photometricInterpretation != "RGB"
                    Text("WW: $ww")
                    Slider(value = ww.toFloat().coerceIn(1f, maxWidth), onValueChange = { onWindow(it.roundToInt(), wl) },
                        valueRange = 1f..maxWidth, enabled = mono && descriptor != null, modifier = Modifier.testTag("viewer-ww-slider"))
                    Text("WL: $wl")
                    Slider(value = wl.toFloat().coerceIn(minCenter, maxCenter), onValueChange = { onWindow(ww, it.roundToInt()) },
                        valueRange = minCenter..maxOf(minCenter + 1f, maxCenter), enabled = mono && descriptor != null, modifier = Modifier.testTag("viewer-wl-slider"))
                    // A vertical button stack also fits narrow phones without clipping.
                    FilledTonalButton(onClick = onInvert, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Brightness6, null); Spacer(Modifier.width(8.dp)); Text(if (inverted) "Инверсия включена" else "Инверсия")
                    }
                    Button(onClick = onResetWindow, modifier = Modifier.fillMaxWidth(), enabled = mono && descriptor != null) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Сбросить WW/WL")
                    }
                    Text("Сброс WW/WL сохраняет масштаб, сдвиг, кадр и инверсию.", fontSize = 11.sp)
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        when {
            fullScreen -> viewport(Modifier.fillMaxSize())
            maxWidth >= 700.dp -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewport(Modifier.weight(0.62f).fillMaxHeight())
                panel(Modifier.weight(0.38f).fillMaxHeight())
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (tab == 0) viewport(Modifier.fillMaxWidth().weight(0.55f))
                panel(Modifier.fillMaxWidth().weight(if (tab == 0) 0.45f else 1f))
            }
        }
    }
}
