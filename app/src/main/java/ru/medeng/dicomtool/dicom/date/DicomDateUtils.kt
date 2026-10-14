package ru.medeng.dicomtool.dicom.date

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.util.Locale

/**
 * Результат валидации даты рождения тестового пациента.
 */
sealed interface DateValidationResult {
    /**
     * Поле пустое (в стандарте DICOM тег (0010,0030) PatientBirthDate может быть пустым: VR = DA).
     */
    data object ValidEmpty : DateValidationResult

    /**
     * Валидная дата с преобразованием в формат DICOM (YYYYMMDD) и отображением (ДД.ММ.ГГГГ).
     */
    data class Valid(
        val dicomDa: String,
        val displayFormatted: String
    ) : DateValidationResult

    /**
     * Неполный ввод (1..7 цифр).
     * По ТЗ: при неполном вводе не показывать ошибку до завершения редактирования, но запрещать отправку.
     */
    data class Incomplete(val digitsCount: Int) : DateValidationResult

    /**
     * Ошибка: несуществующая дата, некорректный месяц, невисокосный год и т.д.
     */
    data class Invalid(val messageRu: String) : DateValidationResult
}

/**
 * Утилиты для работы с датами в формате ДД.ММ.ГГГГ и DICOM VR = DA (YYYYMMDD).
 */
object DicomDateUtils {

    fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }

    fun daysInMonth(month: Int, year: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

    /**
     * Валидирует строку из чистых цифр даты (от 0 до 8 цифр).
     */
    fun validateBirthDateDigits(digits: String): DateValidationResult {
        val cleanDigits = digits.filter { it.isDigit() }.take(8)
        if (cleanDigits.isEmpty()) {
            return DateValidationResult.ValidEmpty
        }
        if (cleanDigits.length < 8) {
            return DateValidationResult.Incomplete(cleanDigits.length)
        }
        val day = cleanDigits.substring(0, 2).toIntOrNull() ?: return DateValidationResult.Invalid("Некорректный день")
        val month = cleanDigits.substring(2, 4).toIntOrNull() ?: return DateValidationResult.Invalid("Некорректный месяц")
        val year = cleanDigits.substring(4, 8).toIntOrNull() ?: return DateValidationResult.Invalid("Некорректный год")

        if (year < 1850 || year > 2100) {
            return DateValidationResult.Invalid("Год должен быть в диапазоне 1850..2100")
        }
        if (month !in 1..12) {
            return DateValidationResult.Invalid("Месяц должен быть в диапазоне 01..12")
        }
        val maxDays = daysInMonth(month, year)
        if (day !in 1..maxDays) {
            return if (month == 2 && day == 29) {
                DateValidationResult.Invalid("$year год не високосный (в феврале 28 дней)")
            } else {
                DateValidationResult.Invalid("В $month-м месяце максимум $maxDays дней")
            }
        }

        val dicomDa = String.format(Locale.US, "%04d%02d%02d", year, month, day)
        val display = String.format(Locale.US, "%02d.%02d.%04d", day, month, year)
        return DateValidationResult.Valid(dicomDa = dicomDa, displayFormatted = display)
    }

    /**
     * Форматирует сырые цифры (0..8 цифр) в визуальную строку с точками: "ДД.ММ.ГГГГ".
     */
    fun formatDigitsToDisplay(digits: String): String {
        val clean = digits.filter { it.isDigit() }.take(8)
        return buildString {
            for (i in clean.indices) {
                append(clean[i])
                if ((i == 1 || i == 3) && i < clean.length - 1) {
                    append('.')
                }
            }
        }
    }

    /**
     * Преобразует любую строку ввода (например "28.02.1998" или "28021998") в 8 цифр.
     */
    fun extractDigits(input: String): String {
        return input.filter { it.isDigit() }.take(8)
    }

    /**
     * Преобразует DICOM VR DA (YYYYMMDD) в цифры даты (DDMMYYYY).
     */
    fun dicomDaToDigits(dicomDa: String): String {
        val clean = dicomDa.filter { it.isDigit() }
        if (clean.length == 8) {
            val yyyy = clean.substring(0, 4)
            val mm = clean.substring(4, 6)
            val dd = clean.substring(6, 8)
            return "$dd$mm$yyyy"
        }
        return ""
    }
}

/**
 * Профессиональная маска ввода даты (ДД.ММ.ГГГГ) средствами Jetpack Compose.
 *
 * Пользователь работает только с цифрами (0..8 цифр), точки добавляются визуально,
 * а [OffsetMapping] корректно транслирует позиции курсора в обе стороны при редактировании,
 * перемещении курсора, удалении и вставке из буфера обмена.
 */
class DateOfBirthVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text.filter { it.isDigit() }.take(8)
        val formatted = buildString {
            for (i in raw.indices) {
                append(raw[i])
                if ((i == 1 || i == 3) && i < raw.length - 1) {
                    append('.')
                }
            }
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val clamped = offset.coerceIn(0, raw.length)
                return when {
                    clamped <= 2 -> clamped
                    clamped <= 4 -> clamped + 1
                    else -> (clamped + 2).coerceAtMost(formatted.length)
                }
            }

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, formatted.length)
                return when {
                    clamped <= 2 -> clamped
                    clamped == 3 -> 2 // клик по первой точке
                    clamped <= 5 -> clamped - 1
                    clamped == 6 -> 4 // клик по второй точке
                    else -> (clamped - 2).coerceAtMost(raw.length)
                }
            }
        }

        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}
