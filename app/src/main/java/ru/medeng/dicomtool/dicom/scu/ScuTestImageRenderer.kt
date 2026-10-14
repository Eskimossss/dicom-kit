package ru.medeng.dicomtool.dicom.scu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import ru.medeng.dicomtool.domain.model.FreehandStroke
import ru.medeng.dicomtool.domain.model.NormalizedPoint
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import ru.medeng.dicomtool.domain.model.TestImageMode
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Параметры процедурно сгенерированного смайлика (Режим B).
 * Полностью определяются зерном `seed`, что гарантирует неизменность изображения
 * после генерации до следующего нажатия кнопки «Сгенерировать заново».
 */
data class ProceduralSmileySpec(
    val seed: Long,
    val faceShape: FaceShape,
    val eyeStyle: EyeStyle,
    val browStyle: BrowStyle,
    val mouthStyle: MouthStyle,
    val detailStyle: DetailStyle,
    val headRadiusScale: Float,
    val eyeSpacingScale: Float,
    val eyeVerticalOffset: Float,
    val mouthWidthScale: Float,
    val mouthCurvature: Float
) {
    enum class FaceShape(val labelRu: String) {
        CLASSIC_CIRCLE("Классический круг"),
        CHUBBY_ROUND("Широкий контур"),
        SLIGHT_OVAL("Овальный контур"),
        BADGE_DOUBLE_RING("Двойное кольцо")
    }

    enum class EyeStyle(val labelRu: String) {
        ROUND_WITH_PUPILS("Круглые со зрачками"),
        HAPPY_ARCS("Радостные дуги"),
        WINK_LEFT("Подмигивающий слева"),
        WINK_RIGHT("Подмигивающий справа"),
        WIDE_SURPRISED_RINGS("Удивлённые кольца"),
        ENGINEER_GLASSES("В очках инженера"),
        STAR_EYES("Звёздные глаза"),
        CROSSHAIR_EYES("Прицельные визоры")
    }

    enum class BrowStyle(val labelRu: String) {
        NONE("Без бровей"),
        RAISED_ARCH("Приподнятые дуги"),
        CURIOUS_TILT("Любопытный наклон"),
        SERIOUS_FLAT("Сосредоточенные"),
        CHEERFUL_HIGH("Высокие весёлые")
    }

    enum class MouthStyle(val labelRu: String) {
        BROAD_SMILE("Широкая улыбка"),
        OPEN_LAUGH("Открытая улыбка"),
        TOOTHED_GRIN("Улыбка с зубами"),
        SURPRISED_O("Удивлённый рот"),
        SMIRK_ASYM("Ухмылка"),
        CAT_W_SMILE("Двойная дуга"),
        CALM_WAVE("Волнистая улыбка")
    }

    enum class DetailStyle(val labelRu: String) {
        CHEEK_DIMPLES("Ямочки на щеках"),
        FRECKLES("Веснушки"),
        ENGINEER_ANTENNA("Сервисная антенна"),
        MEDICAL_CROSS_BADGE("Медицинский крест"),
        BOW_TIE("Галстук-бабочка"),
        HEADSET_MIC("Гарнитура инженера"),
        SPARKLE_MARKS("Искры вокруг")
    }

    val summaryRu: String
        get() = "${eyeStyle.labelRu}, ${mouthStyle.labelRu.lowercase()}, ${detailStyle.labelRu.lowercase()}"
}

/**
 * Генератор настоящих 8-bit grayscale (MONOCHROME2, 512 × 512) Pixel Data для трёх режимов SCU:
 * - Режим A (TEXT): многострочный текст с поддержкой кириллицы, автопереносом и автомасштабированием в пределах 512 × 512.
 * - Режим B (EMOJI): процедурный генератор случайных смайликов по `smileySeed` без постоянных шаблонов.
 * - Режим C (FREEHAND): рисование пальцем/стилусом белой линией на чёрном фоне с настраиваемой толщиной линии.
 */
object ScuTestImageRenderer {

    const val IMAGE_WIDTH = 512
    const val IMAGE_HEIGHT = 512
    const val PIXEL_COUNT = IMAGE_WIDTH * IMAGE_HEIGHT

    /**
     * Детерминированно создаёт спецификацию смайлика из `seed`.
     */
    fun createSmileySpec(seed: Long): ProceduralSmileySpec {
        val rng = Random(seed xor 0x5DEECE66DL)
        val faces = ProceduralSmileySpec.FaceShape.entries
        val eyes = ProceduralSmileySpec.EyeStyle.entries
        val brows = ProceduralSmileySpec.BrowStyle.entries
        val mouths = ProceduralSmileySpec.MouthStyle.entries
        val details = ProceduralSmileySpec.DetailStyle.entries

        return ProceduralSmileySpec(
            seed = seed,
            faceShape = faces[rng.nextInt(faces.size)],
            eyeStyle = eyes[rng.nextInt(eyes.size)],
            browStyle = brows[rng.nextInt(brows.size)],
            mouthStyle = mouths[rng.nextInt(mouths.size)],
            detailStyle = details[rng.nextInt(details.size)],
            headRadiusScale = 0.34f + rng.nextFloat() * 0.05f,
            eyeSpacingScale = 0.31f + rng.nextFloat() * 0.08f,
            eyeVerticalOffset = -0.18f - rng.nextFloat() * 0.07f,
            mouthWidthScale = 0.42f + rng.nextFloat() * 0.16f,
            mouthCurvature = 0.22f + rng.nextFloat() * 0.18f
        )
    }

    /**
     * Генерирует новое случайное зерно смайлика, гарантированно отличающееся от предыдущего
     * и дающее другую комбинацию черт лица.
     */
    fun nextRandomSmileySeed(previousSeed: Long): Long {
        val prevSpec = createSmileySpec(previousSeed)
        var candidate = System.nanoTime() xor (previousSeed * 6364136223846793005L + 1442695040888963407L)
        var attempts = 0
        while (attempts < 32) {
            val spec = createSmileySpec(candidate)
            if (candidate != previousSeed &&
                (spec.eyeStyle != prevSpec.eyeStyle ||
                    spec.mouthStyle != prevSpec.mouthStyle ||
                    spec.detailStyle != prevSpec.detailStyle)
            ) {
                return candidate
            }
            candidate = candidate * 2862933555777941757L + 3037000493L + attempts
            attempts++
        }
        return candidate
    }

    /**
     * Формирует массив реальных 8-битных пикселей MONOCHROME2 (512 × 512 = 262 144 байт)
     * в соответствии с выбранным режимом генератора.
     */
    fun renderPixelData(
        config: ScuSendConfig,
        freehandStrokes: List<FreehandStroke>
    ): ByteArray {
        return when (config.imageMode) {
            TestImageMode.TEXT -> {
                val effectiveText = config.customText.ifBlank {
                    listOf(config.customTextLine1, config.customTextLine2)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
                renderTextModePixels(effectiveText)
            }
            TestImageMode.EMOJI -> {
                renderProceduralSmileyPixels(config.smileySeed)
            }
            TestImageMode.FREEHAND -> {
                renderFreehandPixels(freehandStrokes)
            }
        }
    }

    /**
     * Режим A: Рендеринг многострочного текста (включая кириллицу) белым цветом на чёрном фоне 512 × 512.
     * Автоматически переносит длинные строки и масштабирует размер шрифта, чтобы весь текст помещался в кадр.
     */
    fun renderTextModePixels(
        text: String,
        width: Int = IMAGE_WIDTH,
        height: Int = IMAGE_HEIGHT
    ): ByteArray {
        val normalizedText = text.replace("\r\n", "\n").replace("\r", "\n").trim()
        val displayLines = if (normalizedText.isEmpty()) listOf("DICOM TEST") else normalizedText.lines()

        // Сначала пробуем нативный Android Canvas + Paint (с поддержкой системных TrueType шрифтов и кириллицы)
        val nativePixels = tryRenderTextWithAndroidCanvas(displayLines, width, height)
        if (nativePixels != null) {
            return nativePixels
        }

        // Резервный программный растеризатор (гарантирует работу в чистых JVM unit-тестах без Robolectric)
        return renderTextWithSoftwareRasterizer(displayLines, width, height)
    }

    private fun tryRenderTextWithAndroidCanvas(
        rawLines: List<String>,
        width: Int,
        height: Int
    ): ByteArray? {
        return try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) ?: return null
            if (bmp.width != width || bmp.height != height) return null
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.BLACK)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            val horizontalPadding = width * 0.08f
            val maxLineWidth = width - horizontalPadding * 2f
            val maxTotalHeight = height * 0.84f

            // Подбираем размер шрифта от 44px до 14px так, чтобы с учётом переноса строк всё поместилось по высоте
            var chosenFontSize = 42f
            var wrappedLines: List<String> = rawLines
            while (chosenFontSize >= 14f) {
                paint.textSize = chosenFontSize
                wrappedLines = wrapLinesByPaintWidth(rawLines, paint, maxLineWidth)
                val lineSpacing = chosenFontSize * 1.28f
                val totalHeight = wrappedLines.size * lineSpacing
                if (totalHeight <= maxTotalHeight) {
                    break
                }
                chosenFontSize -= 2f
            }

            paint.textSize = chosenFontSize
            val lineSpacing = chosenFontSize * 1.28f
            val totalBlockHeight = wrappedLines.size * lineSpacing
            val fontMetrics = paint.fontMetrics
            var currentBaselineY = (height - totalBlockHeight) / 2f - fontMetrics.ascent

            for (line in wrappedLines) {
                canvas.drawText(line, width / 2f, currentBaselineY, paint)
                currentBaselineY += lineSpacing
            }

            val argb = IntArray(width * height)
            bmp.getPixels(argb, 0, width, 0, 0, width, height)
            bmp.recycle()

            val out = ByteArray(width * height)
            var nonZeroCount = 0
            for (i in argb.indices) {
                val c = argb[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val lum = ((r * 77 + g * 150 + b * 29) ushr 8).coerceIn(0, 255)
                if (lum > 0) nonZeroCount++
                out[i] = lum.toByte()
            }
            if (nonZeroCount == 0) return null
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun wrapLinesByPaintWidth(
        rawLines: List<String>,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        val result = ArrayList<String>()
        for (raw in rawLines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                result.add("")
                continue
            }
            if (paint.measureText(trimmed) <= maxWidth) {
                result.add(trimmed)
                continue
            }
            // Разбиваем по словам, а слишком длинные слова — посимвольно
            val words = trimmed.split(Regex("\\s+"))
            var currentLine = StringBuilder()
            for (word in words) {
                val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    currentLine = StringBuilder(candidate)
                } else {
                    if (currentLine.isNotEmpty()) {
                        result.add(currentLine.toString())
                        currentLine = StringBuilder()
                    }
                    if (paint.measureText(word) <= maxWidth) {
                        currentLine.append(word)
                    } else {
                        for (ch in word) {
                            val charCandidate = "$currentLine$ch"
                            if (paint.measureText(charCandidate) <= maxWidth) {
                                currentLine.append(ch)
                            } else {
                                if (currentLine.isNotEmpty()) {
                                    result.add(currentLine.toString())
                                }
                                currentLine = StringBuilder().append(ch)
                            }
                        }
                    }
                }
            }
            if (currentLine.isNotEmpty()) {
                result.add(currentLine.toString())
            }
        }
        return if (result.isEmpty()) listOf("") else result
    }

    /**
     * Программный растеризатор текста (для многострочного текста и кириллицы в окружениях без Android Canvas,
     * например в JVM unit-тестах).
     */
    private fun renderTextWithSoftwareRasterizer(
        rawLines: List<String>,
        width: Int,
        height: Int
    ): ByteArray {
        val pixels = ByteArray(width * height)
        val marginX = (width * 0.08f).roundToInt()
        val usableWidth = width - marginX * 2
        val usableHeight = (height * 0.84f).roundToInt()

        // Подбираем масштаб символа (базовая матрица глифа 6x8 с межсимвольным интервалом)
        var scale = 5
        var wrapped = wrapLinesForSoftwareFont(rawLines, usableWidth, scale)
        while (scale > 1 && wrapped.size * (11 * scale) > usableHeight) {
            scale--
            wrapped = wrapLinesForSoftwareFont(rawLines, usableWidth, scale)
        }

        val charAdvX = 7 * scale
        val lineAdvY = 11 * scale
        val totalHeight = wrapped.size * lineAdvY
        var startY = ((height - totalHeight) / 2).coerceAtLeast(marginX)

        for (line in wrapped) {
            if (startY + 8 * scale > height - 4) break
            val lineWidth = line.length * charAdvX
            var cursorX = ((width - lineWidth) / 2).coerceAtLeast(marginX)
            for (ch in line) {
                drawSoftwareGlyph(pixels, width, height, cursorX, startY, ch, scale)
                cursorX += charAdvX
            }
            startY += lineAdvY
        }
        return pixels
    }

    private fun wrapLinesForSoftwareFont(
        rawLines: List<String>,
        usableWidth: Int,
        scale: Int
    ): List<String> {
        val maxCharsPerLine = (usableWidth / (7 * scale)).coerceAtLeast(6)
        val out = ArrayList<String>()
        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.add("")
                continue
            }
            var idx = 0
            while (idx < trimmed.length) {
                val end = (idx + maxCharsPerLine).coerceAtMost(trimmed.length)
                out.add(trimmed.substring(idx, end))
                idx = end
            }
        }
        return if (out.isEmpty()) listOf("DICOM") else out
    }

    private fun drawSoftwareGlyph(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        ch: Char,
        scale: Int
    ) {
        if (ch.isWhitespace()) return
        val rows = getGlyphRows5x7(ch)
        for (r in rows.indices) {
            val rowBits = rows[r]
            for (c in 0 until 5) {
                if (((rowBits ushr (4 - c)) and 1) != 0) {
                    fillRectGray(
                        pixels = pixels,
                        width = width,
                        height = height,
                        left = x0 + c * scale,
                        top = y0 + r * scale,
                        right = x0 + (c + 1) * scale,
                        bottom = y0 + (r + 1) * scale,
                        value = 255
                    )
                }
            }
        }
    }

    /**
     * Компактный 5×7 растровый шрифт для латиницы, кириллицы, цифр и базовых символов.
     */
    private fun getGlyphRows5x7(ch: Char): IntArray {
        return when (ch.uppercaseChar()) {
            'A', 'А' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001)
            'B', 'В' -> intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110)
            'C', 'С' -> intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110)
            'D' -> intArrayOf(0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100)
            'E', 'Е', 'Ё' -> intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111)
            'F' -> intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000)
            'G' -> intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110)
            'H', 'Н' -> intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001)
            'I' -> intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
            'J' -> intArrayOf(0b00111, 0b00010, 0b00010, 0b00010, 0b10010, 0b10010, 0b01100)
            'K', 'К' -> intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001)
            'L' -> intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111)
            'M', 'М' -> intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001)
            'N' -> intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001)
            'O', 'О', '0' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110)
            'P', 'Р' -> intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000)
            'Q' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101)
            'R' -> intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001)
            'S' -> intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110)
            'T', 'Т' -> intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100)
            'U' -> intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110)
            'V' -> intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100)
            'W' -> intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010)
            'X', 'Х' -> intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001)
            'Y', 'У' -> intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100)
            'Z' -> intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111)
            'Б' -> intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b11110)
            'Г' -> intArrayOf(0b11111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000)
            'Д' -> intArrayOf(0b00110, 0b01010, 0b01010, 0b01010, 0b01010, 0b11111, 0b10001)
            'Ж' -> intArrayOf(0b10101, 0b10101, 0b01110, 0b00100, 0b01110, 0b10101, 0b10101)
            'З', '3' -> intArrayOf(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110)
            'И', 'Й' -> intArrayOf(0b10001, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b10001)
            'Л' -> intArrayOf(0b00111, 0b01001, 0b01001, 0b01001, 0b01001, 0b01001, 0b10001)
            'П' -> intArrayOf(0b11111, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001)
            'Ф' -> intArrayOf(0b00100, 0b01110, 0b10101, 0b10101, 0b01110, 0b00100, 0b00100)
            'Ц' -> intArrayOf(0b10010, 0b10010, 0b10010, 0b10010, 0b10010, 0b11111, 0b00001)
            'Ч', '4' -> intArrayOf(0b10001, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b00001)
            'Ш', 'Щ' -> intArrayOf(0b10101, 0b10101, 0b10101, 0b10101, 0b10101, 0b10101, 0b11111)
            'Ъ', 'Ь', 'Ы' -> intArrayOf(0b10000, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b11110)
            'Э' -> intArrayOf(0b01110, 0b10001, 0b00001, 0b00111, 0b00001, 0b10001, 0b01110)
            'Ю' -> intArrayOf(0b10010, 0b10101, 0b10101, 0b11101, 0b10101, 0b10101, 0b10010)
            'Я' -> intArrayOf(0b01111, 0b10001, 0b10001, 0b01111, 0b00101, 0b01001, 0b10001)
            '1' -> intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
            '2' -> intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b01000, 0b10000, 0b11111)
            '5' -> intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110)
            '6' -> intArrayOf(0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110)
            '7' -> intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000)
            '8' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110)
            '9' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110)
            '-' -> intArrayOf(0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000)
            '.' -> intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100)
            ':' -> intArrayOf(0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000)
            '/' -> intArrayOf(0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000)
            '^' -> intArrayOf(0b00100, 0b01010, 0b10001, 0b00000, 0b00000, 0b00000, 0b00000)
            '_' -> intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111)
            else -> intArrayOf(0b11111, 0b10001, 0b10101, 0b10101, 0b10101, 0b10001, 0b11111)
        }
    }

    /**
     * Режим B: Процедурный генератор случайных смайликов 512 × 512 (8-bit MONOCHROME2).
     * Полностью детерминирован относительно `seed`.
     */
    fun renderProceduralSmileyPixels(
        seed: Long,
        width: Int = IMAGE_WIDTH,
        height: Int = IMAGE_HEIGHT
    ): ByteArray {
        val spec = createSmileySpec(seed)
        val pixels = ByteArray(width * height)
        val cx = width / 2f
        val cy = height / 2f
        val minDim = min(width, height).toFloat()
        val r = minDim * spec.headRadiusScale
        val strokeMain = (minDim * 0.022f).coerceAtLeast(6f)

        // 1. Контур головы
        when (spec.faceShape) {
            ProceduralSmileySpec.FaceShape.CLASSIC_CIRCLE -> {
                drawCircleOutlineAA(pixels, width, height, cx, cy, r, strokeMain)
            }
            ProceduralSmileySpec.FaceShape.CHUBBY_ROUND -> {
                drawEllipseOutlineAA(pixels, width, height, cx, cy, r * 1.07f, r * 0.96f, strokeMain * 1.15f)
            }
            ProceduralSmileySpec.FaceShape.SLIGHT_OVAL -> {
                drawEllipseOutlineAA(pixels, width, height, cx, cy, r * 0.95f, r * 1.05f, strokeMain)
            }
            ProceduralSmileySpec.FaceShape.BADGE_DOUBLE_RING -> {
                drawCircleOutlineAA(pixels, width, height, cx, cy, r, strokeMain)
                drawCircleOutlineAA(pixels, width, height, cx, cy, r * 0.91f, strokeMain * 0.45f, 180)
            }
        }

        val leftEyeX = cx - r * spec.eyeSpacingScale
        val rightEyeX = cx + r * spec.eyeSpacingScale
        val eyeY = cy + r * spec.eyeVerticalOffset
        val eyeR = r * 0.13f

        // 2. Брови
        val browY = eyeY - eyeR * 1.55f
        val browHalfW = eyeR * 1.15f
        when (spec.browStyle) {
            ProceduralSmileySpec.BrowStyle.NONE -> Unit
            ProceduralSmileySpec.BrowStyle.RAISED_ARCH -> {
                drawArcOutlineAA(pixels, width, height, leftEyeX, browY + eyeR * 0.4f, browHalfW, 205f, 335f, strokeMain * 0.65f)
                drawArcOutlineAA(pixels, width, height, rightEyeX, browY + eyeR * 0.4f, browHalfW, 205f, 335f, strokeMain * 0.65f)
            }
            ProceduralSmileySpec.BrowStyle.CURIOUS_TILT -> {
                drawLineSegmentAA(pixels, width, height, leftEyeX - browHalfW, browY + 4f, leftEyeX + browHalfW, browY - 6f, strokeMain * 0.65f)
                drawArcOutlineAA(pixels, width, height, rightEyeX, browY + eyeR * 0.2f, browHalfW, 200f, 340f, strokeMain * 0.65f)
            }
            ProceduralSmileySpec.BrowStyle.SERIOUS_FLAT -> {
                drawLineSegmentAA(pixels, width, height, leftEyeX - browHalfW, browY, leftEyeX + browHalfW, browY + 3f, strokeMain * 0.7f)
                drawLineSegmentAA(pixels, width, height, rightEyeX - browHalfW, browY + 3f, rightEyeX + browHalfW, browY, strokeMain * 0.7f)
            }
            ProceduralSmileySpec.BrowStyle.CHEERFUL_HIGH -> {
                drawArcOutlineAA(pixels, width, height, leftEyeX, browY, browHalfW * 1.1f, 210f, 330f, strokeMain * 0.6f)
                drawArcOutlineAA(pixels, width, height, rightEyeX, browY, browHalfW * 1.1f, 210f, 330f, strokeMain * 0.6f)
            }
        }

        // 3. Глаза
        when (spec.eyeStyle) {
            ProceduralSmileySpec.EyeStyle.ROUND_WITH_PUPILS -> {
                drawFilledCircleAA(pixels, width, height, leftEyeX, eyeY, eyeR)
                drawFilledCircleAA(pixels, width, height, rightEyeX, eyeY, eyeR)
                // Блики в глазах
                drawFilledCircleAA(pixels, width, height, leftEyeX - eyeR * 0.3f, eyeY - eyeR * 0.3f, eyeR * 0.32f, 0)
                drawFilledCircleAA(pixels, width, height, rightEyeX - eyeR * 0.3f, eyeY - eyeR * 0.3f, eyeR * 0.32f, 0)
            }
            ProceduralSmileySpec.EyeStyle.HAPPY_ARCS -> {
                drawArcOutlineAA(pixels, width, height, leftEyeX, eyeY + eyeR * 0.3f, eyeR * 1.1f, 200f, 340f, strokeMain * 0.85f)
                drawArcOutlineAA(pixels, width, height, rightEyeX, eyeY + eyeR * 0.3f, eyeR * 1.1f, 200f, 340f, strokeMain * 0.85f)
            }
            ProceduralSmileySpec.EyeStyle.WINK_LEFT -> {
                drawArcOutlineAA(pixels, width, height, leftEyeX, eyeY + eyeR * 0.2f, eyeR * 1.1f, 200f, 340f, strokeMain * 0.85f)
                drawFilledCircleAA(pixels, width, height, rightEyeX, eyeY, eyeR)
                drawFilledCircleAA(pixels, width, height, rightEyeX - eyeR * 0.28f, eyeY - eyeR * 0.28f, eyeR * 0.3f, 0)
            }
            ProceduralSmileySpec.EyeStyle.WINK_RIGHT -> {
                drawFilledCircleAA(pixels, width, height, leftEyeX, eyeY, eyeR)
                drawFilledCircleAA(pixels, width, height, leftEyeX - eyeR * 0.28f, eyeY - eyeR * 0.28f, eyeR * 0.3f, 0)
                drawArcOutlineAA(pixels, width, height, rightEyeX, eyeY + eyeR * 0.2f, eyeR * 1.1f, 200f, 340f, strokeMain * 0.85f)
            }
            ProceduralSmileySpec.EyeStyle.WIDE_SURPRISED_RINGS -> {
                drawCircleOutlineAA(pixels, width, height, leftEyeX, eyeY, eyeR * 1.18f, strokeMain * 0.7f)
                drawFilledCircleAA(pixels, width, height, leftEyeX, eyeY, eyeR * 0.45f)
                drawCircleOutlineAA(pixels, width, height, rightEyeX, eyeY, eyeR * 1.18f, strokeMain * 0.7f)
                drawFilledCircleAA(pixels, width, height, rightEyeX, eyeY, eyeR * 0.45f)
            }
            ProceduralSmileySpec.EyeStyle.ENGINEER_GLASSES -> {
                val frameR = eyeR * 1.45f
                drawCircleOutlineAA(pixels, width, height, leftEyeX, eyeY, frameR, strokeMain * 0.7f)
                drawCircleOutlineAA(pixels, width, height, rightEyeX, eyeY, frameR, strokeMain * 0.7f)
                drawLineSegmentAA(pixels, width, height, leftEyeX + frameR, eyeY, rightEyeX - frameR, eyeY, strokeMain * 0.7f)
                drawFilledCircleAA(pixels, width, height, leftEyeX, eyeY, eyeR * 0.65f)
                drawFilledCircleAA(pixels, width, height, rightEyeX, eyeY, eyeR * 0.65f)
            }
            ProceduralSmileySpec.EyeStyle.STAR_EYES -> {
                drawStarShape(pixels, width, height, leftEyeX, eyeY, eyeR * 1.25f, strokeMain * 0.65f)
                drawStarShape(pixels, width, height, rightEyeX, eyeY, eyeR * 1.25f, strokeMain * 0.65f)
            }
            ProceduralSmileySpec.EyeStyle.CROSSHAIR_EYES -> {
                drawCircleOutlineAA(pixels, width, height, leftEyeX, eyeY, eyeR * 1.15f, strokeMain * 0.65f)
                drawLineSegmentAA(pixels, width, height, leftEyeX - eyeR * 1.3f, eyeY, leftEyeX + eyeR * 1.3f, eyeY, strokeMain * 0.45f)
                drawLineSegmentAA(pixels, width, height, leftEyeX, eyeY - eyeR * 1.3f, leftEyeX, eyeY + eyeR * 1.3f, strokeMain * 0.45f)
                drawCircleOutlineAA(pixels, width, height, rightEyeX, eyeY, eyeR * 1.15f, strokeMain * 0.65f)
                drawLineSegmentAA(pixels, width, height, rightEyeX - eyeR * 1.3f, eyeY, rightEyeX + eyeR * 1.3f, eyeY, strokeMain * 0.45f)
                drawLineSegmentAA(pixels, width, height, rightEyeX, eyeY - eyeR * 1.3f, rightEyeX, eyeY + eyeR * 1.3f, strokeMain * 0.45f)
            }
        }

        // 4. Рот и выражение лица
        val mouthCenterY = cy + r * 0.24f
        val mouthRadius = r * spec.mouthWidthScale
        when (spec.mouthStyle) {
            ProceduralSmileySpec.MouthStyle.BROAD_SMILE -> {
                drawArcOutlineAA(pixels, width, height, cx, cy + r * 0.05f, mouthRadius, 22f, 158f, strokeMain * 0.95f)
            }
            ProceduralSmileySpec.MouthStyle.OPEN_LAUGH -> {
                val topY = mouthCenterY - r * 0.04f
                drawArcOutlineAA(pixels, width, height, cx, topY, mouthRadius, 10f, 170f, strokeMain * 0.9f)
                val leftX = cx + mouthRadius * cos(Math.toRadians(170.0)).toFloat()
                val rightX = cx + mouthRadius * cos(Math.toRadians(10.0)).toFloat()
                val chordY = topY + mouthRadius * sin(Math.toRadians(10.0)).toFloat()
                drawLineSegmentAA(pixels, width, height, leftX, chordY, rightX, chordY, strokeMain * 0.85f)
            }
            ProceduralSmileySpec.MouthStyle.TOOTHED_GRIN -> {
                val topY = mouthCenterY - r * 0.04f
                drawArcOutlineAA(pixels, width, height, cx, topY, mouthRadius, 12f, 168f, strokeMain * 0.9f)
                val leftX = cx + mouthRadius * cos(Math.toRadians(168.0)).toFloat()
                val rightX = cx + mouthRadius * cos(Math.toRadians(12.0)).toFloat()
                val chordY = topY + mouthRadius * sin(Math.toRadians(12.0)).toFloat()
                drawLineSegmentAA(pixels, width, height, leftX, chordY, rightX, chordY, strokeMain * 0.8f)
                // Зубные перегородки
                for (t in -1..1) {
                    val tx = cx + t * (mouthRadius * 0.35f)
                    val bottomArcY = topY + sqrt((mouthRadius * mouthRadius - (tx - cx) * (tx - cx)).coerceAtLeast(0f))
                    drawLineSegmentAA(pixels, width, height, tx, chordY, tx, bottomArcY, strokeMain * 0.5f)
                }
            }
            ProceduralSmileySpec.MouthStyle.SURPRISED_O -> {
                drawEllipseOutlineAA(pixels, width, height, cx, mouthCenterY + r * 0.08f, r * 0.16f, r * 0.22f, strokeMain * 0.9f)
            }
            ProceduralSmileySpec.MouthStyle.SMIRK_ASYM -> {
                drawArcOutlineAA(pixels, width, height, cx + r * 0.08f, cy + r * 0.08f, mouthRadius * 0.9f, 15f, 130f, strokeMain * 0.95f)
            }
            ProceduralSmileySpec.MouthStyle.CAT_W_SMILE -> {
                val subR = mouthRadius * 0.46f
                drawArcOutlineAA(pixels, width, height, cx - subR, mouthCenterY, subR, 15f, 165f, strokeMain * 0.85f)
                drawArcOutlineAA(pixels, width, height, cx + subR, mouthCenterY, subR, 15f, 165f, strokeMain * 0.85f)
            }
            ProceduralSmileySpec.MouthStyle.CALM_WAVE -> {
                var prevX = cx - mouthRadius * 0.85f
                var prevY = mouthCenterY + r * 0.06f
                val steps = 24
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val x = (cx - mouthRadius * 0.85f) + t * (mouthRadius * 1.7f)
                    val y = mouthCenterY + r * 0.06f + sin(t * PI.toFloat() * 2f) * (r * 0.06f)
                    drawLineSegmentAA(pixels, width, height, prevX, prevY, x, y, strokeMain * 0.8f)
                    prevX = x
                    prevY = y
                }
            }
        }

        // 5. Случайные графические детали
        when (spec.detailStyle) {
            ProceduralSmileySpec.DetailStyle.CHEEK_DIMPLES -> {
                drawArcOutlineAA(pixels, width, height, cx - r * 0.56f, mouthCenterY + r * 0.05f, r * 0.11f, 140f, 240f, strokeMain * 0.6f)
                drawArcOutlineAA(pixels, width, height, cx + r * 0.56f, mouthCenterY + r * 0.05f, r * 0.11f, 300f, 400f, strokeMain * 0.6f)
            }
            ProceduralSmileySpec.DetailStyle.FRECKLES -> {
                val dotR = strokeMain * 0.42f
                for (dx in listOf(-0.62f, -0.52f, -0.44f)) {
                    drawFilledCircleAA(pixels, width, height, cx + r * dx, cy + r * 0.06f, dotR, 210)
                    drawFilledCircleAA(pixels, width, height, cx - r * dx, cy + r * 0.06f, dotR, 210)
                }
            }
            ProceduralSmileySpec.DetailStyle.ENGINEER_ANTENNA -> {
                val topHeadY = cy - r
                val tipY = (topHeadY - r * 0.25f).coerceAtLeast(24f)
                drawLineSegmentAA(pixels, width, height, cx, topHeadY, cx, tipY, strokeMain * 0.75f)
                drawFilledCircleAA(pixels, width, height, cx, tipY, strokeMain * 1.3f)
            }
            ProceduralSmileySpec.DetailStyle.MEDICAL_CROSS_BADGE -> {
                val badgeY = cy - r * 0.62f
                val arm = r * 0.11f
                drawLineSegmentAA(pixels, width, height, cx - arm, badgeY, cx + arm, badgeY, strokeMain * 0.75f)
                drawLineSegmentAA(pixels, width, height, cx, badgeY - arm, cx, badgeY + arm, strokeMain * 0.75f)
            }
            ProceduralSmileySpec.DetailStyle.BOW_TIE -> {
                val tieY = (cy + r + r * 0.14f).coerceAtMost(height - 20f)
                val w = r * 0.24f
                val h = r * 0.11f
                drawLineSegmentAA(pixels, width, height, cx - w, tieY - h, cx + w, tieY + h, strokeMain * 0.65f)
                drawLineSegmentAA(pixels, width, height, cx - w, tieY + h, cx + w, tieY - h, strokeMain * 0.65f)
                drawLineSegmentAA(pixels, width, height, cx - w, tieY - h, cx - w, tieY + h, strokeMain * 0.65f)
                drawLineSegmentAA(pixels, width, height, cx + w, tieY - h, cx + w, tieY + h, strokeMain * 0.65f)
                drawFilledCircleAA(pixels, width, height, cx, tieY, strokeMain * 0.9f)
            }
            ProceduralSmileySpec.DetailStyle.HEADSET_MIC -> {
                drawArcOutlineAA(pixels, width, height, cx, cy, r * 1.09f, 195f, 345f, strokeMain * 0.65f)
                drawFilledCircleAA(pixels, width, height, cx - r * 1.06f, cy, strokeMain * 1.4f)
                drawFilledCircleAA(pixels, width, height, cx + r * 1.06f, cy, strokeMain * 1.4f)
                drawLineSegmentAA(pixels, width, height, cx - r * 1.04f, cy + strokeMain, cx - r * 0.35f, mouthCenterY + r * 0.18f, strokeMain * 0.55f)
                drawFilledCircleAA(pixels, width, height, cx - r * 0.32f, mouthCenterY + r * 0.18f, strokeMain * 0.85f)
            }
            ProceduralSmileySpec.DetailStyle.SPARKLE_MARKS -> {
                drawStarShape(pixels, width, height, cx - r * 1.15f, cy - r * 0.75f, r * 0.12f, strokeMain * 0.5f)
                drawStarShape(pixels, width, height, cx + r * 1.15f, cy - r * 0.75f, r * 0.12f, strokeMain * 0.5f)
            }
        }

        return pixels
    }

    /**
     * Режим C: Преобразование пользовательских штрихов с холста в 512 × 512 8-bit MONOCHROME2 Pixel Data.
     * Сохраняет индивидуальную толщину каждого штриха (`strokeWidthPx`).
     */
    fun renderFreehandPixels(
        strokes: List<FreehandStroke>,
        width: Int = IMAGE_WIDTH,
        height: Int = IMAGE_HEIGHT
    ): ByteArray {
        val pixels = ByteArray(width * height)
        for (stroke in strokes) {
            val pts = stroke.points
            val thickness = stroke.strokeWidthPx.coerceIn(1f, 48f)
            if (pts.isEmpty()) continue
            if (pts.size == 1) {
                val p = pts.first()
                drawFilledCircleAA(
                    pixels = pixels,
                    width = width,
                    height = height,
                    cx = p.x.coerceIn(0f, 1f) * (width - 1),
                    cy = p.y.coerceIn(0f, 1f) * (height - 1),
                    radius = thickness / 2f
                )
            } else {
                for (i in 0 until pts.size - 1) {
                    val p0 = pts[i]
                    val p1 = pts[i + 1]
                    drawLineSegmentAA(
                        pixels = pixels,
                        width = width,
                        height = height,
                        x0 = p0.x.coerceIn(0f, 1f) * (width - 1),
                        y0 = p0.y.coerceIn(0f, 1f) * (height - 1),
                        x1 = p1.x.coerceIn(0f, 1f) * (width - 1),
                        y1 = p1.y.coerceIn(0f, 1f) * (height - 1),
                        thickness = thickness
                    )
                }
            }
        }
        return pixels
    }

    /**
     * Создаёт Android Bitmap для предпросмотра из 8-bit MONOCHROME2 буфера.
     */
    fun createPreviewBitmapFromGray8(
        grayPixels: ByteArray,
        width: Int = IMAGE_WIDTH,
        height: Int = IMAGE_HEIGHT
    ): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) ?: return null
            val argb = IntArray(width * height)
            val limit = min(grayPixels.size, argb.size)
            for (i in 0 until limit) {
                val v = grayPixels[i].toInt() and 0xFF
                argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bmp.setPixels(argb, 0, width, 0, 0, width, height)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    // --- Геометрические примитивы со сглаживанием (Anti-Aliased 8-bit Grayscale) ---

    private fun setPixelMax(pixels: ByteArray, width: Int, height: Int, x: Int, y: Int, value: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val idx = y * width + x
        val current = pixels[idx].toInt() and 0xFF
        val clamped = value.coerceIn(0, 255)
        if (clamped > current) {
            pixels[idx] = clamped.toByte()
        }
    }

    private fun setPixelExact(pixels: ByteArray, width: Int, height: Int, x: Int, y: Int, value: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        pixels[y * width + x] = value.coerceIn(0, 255).toByte()
    }

    private fun fillRectGray(
        pixels: ByteArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        value: Int
    ) {
        val xStart = left.coerceIn(0, width - 1)
        val xEnd = (right - 1).coerceIn(0, width - 1)
        val yStart = top.coerceIn(0, height - 1)
        val yEnd = (bottom - 1).coerceIn(0, height - 1)
        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                setPixelMax(pixels, width, height, x, y, value)
            }
        }
    }

    private fun drawFilledCircleAA(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        peakValue: Int = 255
    ) {
        val rOuter = radius + 1.0f
        val xMin = floor(cx - rOuter).toInt().coerceAtLeast(0)
        val xMax = ceil(cx + rOuter).toInt().coerceAtMost(width - 1)
        val yMin = floor(cy - rOuter).toInt().coerceAtLeast(0)
        val yMax = ceil(cy + rOuter).toInt().coerceAtMost(height - 1)

        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                val dist = hypot(x - cx, y - cy)
                if (dist <= radius + 0.75f) {
                    val coverage = (radius + 0.5f - dist).coerceIn(0f, 1f)
                    if (peakValue == 0) {
                        if (dist <= radius) {
                            setPixelExact(pixels, width, height, x, y, 0)
                        }
                    } else {
                        setPixelMax(pixels, width, height, x, y, (peakValue * coverage).roundToInt())
                    }
                }
            }
        }
    }

    private fun drawCircleOutlineAA(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        strokeWidth: Float,
        peakValue: Int = 255
    ) {
        val halfStroke = strokeWidth / 2f
        val rOuter = radius + halfStroke + 1.2f
        val xMin = floor(cx - rOuter).toInt().coerceAtLeast(0)
        val xMax = ceil(cx + rOuter).toInt().coerceAtMost(width - 1)
        val yMin = floor(cy - rOuter).toInt().coerceAtLeast(0)
        val yMax = ceil(cy + rOuter).toInt().coerceAtMost(height - 1)

        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                val distToRing = abs(hypot(x - cx, y - cy) - radius)
                if (distToRing <= halfStroke + 1.0f) {
                    val alpha = (halfStroke + 0.5f - distToRing).coerceIn(0f, 1f)
                    setPixelMax(pixels, width, height, x, y, (peakValue * alpha).roundToInt())
                }
            }
        }
    }

    private fun drawEllipseOutlineAA(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        strokeWidth: Float
    ) {
        val steps = 180
        var prevX = cx + rx
        var prevY = cy
        for (i in 1..steps) {
            val angle = (i.toDouble() / steps) * 2.0 * PI
            val x = cx + rx * cos(angle).toFloat()
            val y = cy + ry * sin(angle).toFloat()
            drawLineSegmentAA(pixels, width, height, prevX, prevY, x, y, strokeWidth)
            prevX = x
            prevY = y
        }
    }

    private fun drawArcOutlineAA(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        startDeg: Float,
        endDeg: Float,
        strokeWidth: Float
    ) {
        val halfStroke = strokeWidth / 2f
        val rOuter = radius + halfStroke + 1.2f
        val xMin = floor(cx - rOuter).toInt().coerceAtLeast(0)
        val xMax = ceil(cx + rOuter).toInt().coerceAtMost(width - 1)
        val yMin = floor(cy - rOuter).toInt().coerceAtLeast(0)
        val yMax = ceil(cy + rOuter).toInt().coerceAtMost(height - 1)

        val normStart = ((startDeg % 360f) + 360f) % 360f
        val normEnd = ((endDeg % 360f) + 360f) % 360f

        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                val dx = x - cx
                val dy = y - cy
                val distToRing = abs(hypot(dx, dy) - radius)
                if (distToRing <= halfStroke + 1.0f) {
                    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (deg < 0f) deg += 360f
                    val inAngle = if (normStart <= normEnd) {
                        deg in normStart..normEnd
                    } else {
                        deg >= normStart || deg <= normEnd
                    }
                    if (inAngle) {
                        val alpha = (halfStroke + 0.5f - distToRing).coerceIn(0f, 1f)
                        setPixelMax(pixels, width, height, x, y, (255f * alpha).roundToInt())
                    }
                }
            }
        }
    }

    private fun drawStarShape(
        pixels: ByteArray,
        width: Int,
        height: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        strokeWidth: Float
    ) {
        drawLineSegmentAA(pixels, width, height, cx - radius, cy, cx + radius, cy, strokeWidth)
        drawLineSegmentAA(pixels, width, height, cx, cy - radius, cx, cy + radius, strokeWidth)
        val diag = radius * 0.7f
        drawLineSegmentAA(pixels, width, height, cx - diag, cy - diag, cx + diag, cy + diag, strokeWidth * 0.85f)
        drawLineSegmentAA(pixels, width, height, cx - diag, cy + diag, cx + diag, cy - diag, strokeWidth * 0.85f)
    }

    fun drawLineSegmentAA(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        thickness: Float,
        peakValue: Int = 255
    ) {
        val halfW = (thickness / 2f).coerceAtLeast(0.75f)
        val pad = halfW + 1.5f
        val xMin = floor(min(x0, x1) - pad).toInt().coerceAtLeast(0)
        val xMax = ceil(max(x0, x1) + pad).toInt().coerceAtMost(width - 1)
        val yMin = floor(min(y0, y1) - pad).toInt().coerceAtLeast(0)
        val yMax = ceil(max(y0, y1) + pad).toInt().coerceAtMost(height - 1)

        val vx = x1 - x0
        val vy = y1 - y0
        val lenSq = vx * vx + vy * vy

        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                val t = if (lenSq > 1e-5f) {
                    (((x - x0) * vx + (y - y0) * vy) / lenSq).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val projX = x0 + t * vx
                val projY = y0 + t * vy
                val dist = hypot(x - projX, y - projY)
                if (dist <= halfW + 1.0f) {
                    val alpha = (halfW + 0.5f - dist).coerceIn(0f, 1f)
                    setPixelMax(pixels, width, height, x, y, (peakValue * alpha).roundToInt())
                }
            }
        }
    }
}
