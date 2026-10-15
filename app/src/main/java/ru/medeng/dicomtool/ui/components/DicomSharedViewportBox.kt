package ru.medeng.dicomtool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.ui.theme.DicomViewerCanvasBlack
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.ErrorRedLight
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.WarningAmber
import ru.medeng.dicomtool.ui.viewmodel.DicomViewerRenderState
import kotlin.math.roundToInt

/**
 * Единый переиспользуемый компонент отображения DICOM Pixel Data:
 * - Чёрная подложка DicomViewerCanvasBlack
 * - Адаптивное масштабирование двумя пальцами (pinch-to-zoom)
 * - Перемещение (pan) увеличенного снимка
 * - Двойной тап для Fit to Screen
 * - Одиночный тап по изображению скрывает / показывает оверлеи управления
 * - Компактный информационный оверлей (WW/WL, матрица, Zoom, кадры)
 * - Панель инструментов с кнопками Zoom+/-, Fit, Инверсия, WW+/-, Сброс и «На весь экран»
 * - Боковые стрелки переключения кадров / снимков
 */
@Composable
fun DicomSharedViewportBox(
    renderState: DicomViewerRenderState,
    zoom: Float,
    pan: Offset,
    inverted: Boolean,
    windowWidth: Int,
    windowCenter: Int,
    currentFrame: Int,
    totalFrames: Int,
    modifier: Modifier = Modifier,
    currentInstance: Int = 1,
    totalInstances: Int = 1,
    defaultColumns: Int = 0,
    defaultRows: Int = 0,
    defaultPhotometricInterpretation: String = "MONOCHROME2",
    defaultBitsStored: Int = 16,
    defaultBitsAllocated: Int = 16,
    isFullScreen: Boolean = false,
    onZoomFactor: (Float) -> Unit,
    onPanDelta: (Offset) -> Unit,
    onFitToScreen: () -> Unit,
    onResetTransform: () -> Unit,
    onToggleInvert: () -> Unit,
    onAdjustWindow: (Int, Int) -> Unit,
    onStepFrame: (Int) -> Unit,
    onToggleFullScreen: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .background(DicomViewerCanvasBlack)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { onFitToScreen() }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    if (zoomChange != 1f) onZoomFactor(zoomChange)
                    if (panChange != Offset.Zero) onPanDelta(panChange)
                }
            }
    ) {
        // Рендеринг контента изображения / состояния загрузки / ошибок
        when (renderState) {
            is DicomViewerRenderState.Idle,
            is DicomViewerRenderState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = PrimaryBlue,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Декодирование Pixel Data...",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            is DicomViewerRenderState.Ready -> {
                val imageBitmap = remember(renderState.bitmap) {
                    renderState.bitmap.asImageBitmap()
                }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "DICOM изображение",
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .aspectRatio(renderState.descriptor.columns.toFloat() / renderState.descriptor.rows)
                        .align(Alignment.Center)
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = pan.x,
                            translationY = pan.y
                        )
                )
            }

            is DicomViewerRenderState.UnsupportedFormat -> {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, WarningAmber)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Формат Pixel Data не поддерживается",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Column {
                                Text(
                                    text = "Transfer Syntax UID: ${renderState.transferSyntaxUid} (${renderState.transferSyntaxName})",
                                    color = Color(0xFF93C5FD),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = true
                                )
                                Text(
                                    text = "Photometric: ${renderState.photometricInterpretation} | Bits Allocated: ${renderState.bitsAllocated}",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = true
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = renderState.reasonRu,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    softWrap = true
                                )
                            }
                        }
                    }
                }
            }

            is DicomViewerRenderState.Error -> {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, ErrorRed)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Ошибка декодирования изображения",
                            color = ErrorRedLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = renderState.reasonRu,
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            softWrap = true
                        )
                    }
                }
            }
        }

        // Параметры для отображения в информационном оверлее
        val readyDescriptor = (renderState as? DicomViewerRenderState.Ready)?.descriptor
        val cols = readyDescriptor?.columns ?: defaultColumns
        val rows = readyDescriptor?.rows ?: defaultRows
        val photo = readyDescriptor?.photometricInterpretation ?: defaultPhotometricInterpretation
        val bitsS = readyDescriptor?.bitsStored ?: defaultBitsStored
        val bitsA = readyDescriptor?.bitsAllocated ?: defaultBitsAllocated

        // Верхний информационный оверлей с плавной анимацией видимости
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Левый компактный блок: параметры окна яркости и матрицы пикселей
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = (if ((renderState as? DicomViewerRenderState.Ready)?.descriptor?.photometricInterpretation == "RGB") "RGB • WW/WL не применяется" else "WW:$windowWidth WL:$windowCenter") + if (inverted) " • INV" else "",
                        color = Color.White,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        softWrap = true
                    )
                    Text(
                        text = "${cols}×${rows} • ${bitsS}/${bitsA}b • $photo",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = true
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Правый компактный блок: масштаб, кадр/снимок и полноэкранный статус
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Zoom ${(zoom * 100).roundToInt()}%",
                        color = Color.White,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (totalFrames > 1) {
                            "Кадр $currentFrame/$totalFrames" + if (totalInstances > 1) " • Сн. $currentInstance/$totalInstances" else ""
                        } else {
                            if (totalInstances > 1) "Снимок $currentInstance/$totalInstances" else "1 кадр"
                        },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (isFullScreen) "Полноэкранный режим" else "Инженерный просмотр",
                        color = Color(0xFF93C5FD),
                        fontSize = 9.sp,
                        lineHeight = 11.sp
                    )
                }
            }
        }

        // Боковые кнопки переключения кадров/снимков
        AnimatedVisibility(
            visible = controlsVisible && (totalFrames > 1 || totalInstances > 1),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Surface(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(36.dp)
                    .clickable { onStepFrame(-1) },
                shape = RoundedCornerShape(6.dp),
                color = Color.DarkGray.copy(alpha = 0.72f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Предыдущий кадр/снимок",
                        tint = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && (totalFrames > 1 || totalInstances > 1),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(36.dp)
                    .clickable { onStepFrame(1) },
                shape = RoundedCornerShape(6.dp),
                color = Color.DarkGray.copy(alpha = 0.72f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Следующий кадр/снимок",
                        tint = Color.White
                    )
                }
            }
        }

        // Нижняя панель инструментов
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ViewerToolButton(
                            icon = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            label = if (isFullScreen) "Выход" else "Экран",
                            description = if (isFullScreen) "Выйти из полноэкранного режима" else "На весь экран",
                            isActive = isFullScreen
                        ) {
                            onToggleFullScreen()
                        }
                        ViewerToolButton(icon = Icons.Default.Add, label = "Zoom+", description = "Увеличить") {
                            onZoomFactor(1.25f)
                        }
                        ViewerToolButton(icon = Icons.Default.Remove, label = "Zoom-", description = "Уменьшить") {
                            onZoomFactor(0.8f)
                        }
                        ViewerToolButton(icon = Icons.Default.CropFree, label = "Fit", description = "По размеру экрана") {
                            onFitToScreen()
                        }
                        ViewerToolButton(
                            icon = Icons.Default.Brightness6,
                            label = if (inverted) "Инв:ВКЛ" else "Инверсия",
                            description = "Инверсия изображения",
                            isActive = inverted
                        ) {
                            onToggleInvert()
                        }
                        ViewerToolButton(icon = Icons.Default.Contrast, label = "WW-", description = "Уменьшить WW") {
                            onAdjustWindow(-10, 0)
                        }
                        ViewerToolButton(icon = Icons.Default.Contrast, label = "WW+", description = "Увеличить WW") {
                            onAdjustWindow(10, 0)
                        }
                        ViewerToolButton(icon = Icons.Default.Refresh, label = "Сброс", description = "Сброс WW/WL и масштаба") {
                            onResetTransform()
                        }
                    }
                }
            }
        }
    }
}
