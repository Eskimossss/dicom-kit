package ru.medeng.dicomtool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра строго по референсу docs/references/ui-light.png и ТЗ:
// Только светлая тема, светло-серый фон, белые карточки, синие акценты, зелёный успех, красная ошибка,
// чёрный фон исключительно для области просмотра DICOM-изображения.
val AppBackgroundGray = Color(0xFFF3F5F8)
val CardSurfaceWhite = Color(0xFFFFFFFF)
val CardBorderGray = Color(0xFFE2E8F0)
val InputBackgroundGray = Color(0xFFF8FAFC)

val PrimaryBlue = Color(0xFF1570EF)
val PrimaryBlueLight = Color(0xFFEFF6FF)
val PrimaryBlueBorder = Color(0xFFBFDBFE)

val SuccessGreen = Color(0xFF16A34A)
val SuccessGreenBackground = Color(0xFFECFDF3)
val SuccessGreenBorder = Color(0xFFABEFC6)

val ErrorRed = Color(0xFFE11D48)
val ErrorRedLight = Color(0xFFFDA4AF)
val StopButtonRed = Color(0xFFEF4444)
val DangerActionSoftBg = Color(0xFFFEF2F2)
val DangerActionSoftBorder = Color(0xFFFECACA)

val WarningAmber = Color(0xFFD97706)
val WarningAmberLight = Color(0xFFFEF3C7)

val TextPrimaryDark = Color(0xFF0F172A)
val TextSecondarySlate = Color(0xFF475569)
val TextMutedSlate = Color(0xFF64748B)

val DicomViewerCanvasBlack = Color(0xFF000000)

private val DicomToolLightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = PrimaryBlue,
    secondary = TextSecondarySlate,
    onSecondary = Color.White,
    background = AppBackgroundGray,
    onBackground = TextPrimaryDark,
    surface = CardSurfaceWhite,
    onSurface = TextPrimaryDark,
    surfaceVariant = InputBackgroundGray,
    onSurfaceVariant = TextSecondarySlate,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun DicomToolTheme(
    content: @Composable () -> Unit
) {
    // Принудительно только светлая тема Material 3 (без системной тёмной темы и без переключателя)
    MaterialTheme(
        colorScheme = DicomToolLightColorScheme,
        content = content
    )
}
