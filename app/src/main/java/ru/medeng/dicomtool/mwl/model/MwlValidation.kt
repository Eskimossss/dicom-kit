package ru.medeng.dicomtool.mwl.model

import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator
import java.text.SimpleDateFormat
import java.util.Locale

object MwlValidation {

    sealed class Result {
        object Valid : Result()
        data class Invalid(val field: String, val messageRu: String) : Result()
    }

    /**
     * Валидация Patient Name (VR: PN).
     * Длина не более 64 символов. Не пустое.
     */
    fun validatePatientName(name: String): Result {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.Invalid("patientName", "ФИО пациента не может быть пустым")
        }
        if (trimmed.length > 64) {
            return Result.Invalid("patientName", "Длина ФИО пациента превышает 64 символа (VR: PN)")
        }
        return Result.Valid
    }

    /**
     * Валидация Patient ID (VR: LO).
     * Длина не более 64 символов. Не пустое.
     */
    fun validatePatientId(patientId: String): Result {
        val trimmed = patientId.trim()
        if (trimmed.isEmpty()) {
            return Result.Invalid("patientId", "Идентификатор пациента (Patient ID) не может быть пустым")
        }
        if (trimmed.length > 64) {
            return Result.Invalid("patientId", "Длина Patient ID превышает 64 символа (VR: LO)")
        }
        return Result.Valid
    }

    /**
     * Валидация даты (VR: DA). Формат: YYYYMMDD.
     */
    fun validateDate(dateStr: String, fieldName: String = "date", required: Boolean = false): Result {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) {
            return if (required) Result.Invalid(fieldName, "Дата обязательна для заполнения") else Result.Valid
        }
        if (trimmed.length != 8 || !trimmed.all { it.isDigit() }) {
            return Result.Invalid(fieldName, "Дата должна быть в формате YYYYMMDD (8 цифр), получено: $trimmed")
        }
        val year = trimmed.substring(0, 4).toIntOrNull() ?: 0
        val month = trimmed.substring(4, 6).toIntOrNull() ?: 0
        val day = trimmed.substring(6, 8).toIntOrNull() ?: 0

        if (year < 1850 || year > 2100) return Result.Invalid(fieldName, "Некорректный год ($year)")
        if (month !in 1..12) return Result.Invalid(fieldName, "Некорректный месяц ($month)")
        if (day !in 1..31) return Result.Invalid(fieldName, "Некорректный день месяца ($day)")

        return Result.Valid
    }

    /**
     * Валидация времени (VR: TM). Формат: HHMMSS или HHMM.
     */
    fun validateTime(timeStr: String, fieldName: String = "time", required: Boolean = false): Result {
        val trimmed = timeStr.trim().replace(":", "").replace(".", "")
        if (trimmed.isEmpty()) {
            return if (required) Result.Invalid(fieldName, "Время обязательно для заполнения") else Result.Valid
        }
        if (!trimmed.all { it.isDigit() }) {
            return Result.Invalid(fieldName, "Время должно состоять из цифр, получено: $trimmed")
        }
        if (trimmed.length !in 2..6) {
            return Result.Invalid(fieldName, "Время должно быть в формате HHMMSS или HHMM, получено: $trimmed")
        }
        val hour = trimmed.substring(0, 2).toIntOrNull() ?: -1
        if (hour !in 0..23) return Result.Invalid(fieldName, "Некорректный час ($hour)")
        if (trimmed.length >= 4) {
            val minute = trimmed.substring(2, 4).toIntOrNull() ?: -1
            if (minute !in 0..59) return Result.Invalid(fieldName, "Некорректные минуты ($minute)")
        }
        if (trimmed.length >= 6) {
            val second = trimmed.substring(4, 6).toIntOrNull() ?: -1
            if (second !in 0..59) return Result.Invalid(fieldName, "Некорректные секунды ($second)")
        }
        return Result.Valid
    }

    /**
     * Валидация Study Instance UID (VR: UI).
     */
    fun validateStudyInstanceUid(uid: String): Result {
        val trimmed = uid.trim()
        if (trimmed.isEmpty()) {
            return Result.Invalid("studyInstanceUid", "Study Instance UID не может быть пустым")
        }
        if (trimmed.length > 64) {
            return Result.Invalid("studyInstanceUid", "Длина UID не может превышать 64 символа (VR: UI)")
        }
        val uidRegex = Regex("^[0-9]+(\\.[0-9]+)*$")
        if (!uidRegex.matches(trimmed)) {
            return Result.Invalid("studyInstanceUid", "UID должен содержать только цифры и точки, без пробелов и букв")
        }
        // Проверка отсутствия ведущих нулей в компонентах, за исключением самого числа 0
        for (part in trimmed.split('.')) {
            if (part.isEmpty()) return Result.Invalid("studyInstanceUid", "Пустой компонент в UID (две точки подряд или точка на конце)")
            if (part.length > 1 && part.startsWith("0")) {
                return Result.Invalid("studyInstanceUid", "Компонент UID '$part' содержит ведущий ноль (запрещено стандартом DICOM)")
            }
        }
        return Result.Valid
    }

    /**
     * Валидация Accession Number (VR: SH, max 16 chars).
     */
    fun validateAccessionNumber(accession: String): Result {
        val trimmed = accession.trim()
        if (trimmed.length > 16) {
            return Result.Invalid("accessionNumber", "Accession Number не может превышать 16 символов (VR: SH)")
        }
        return Result.Valid
    }

    /**
     * Валидация AE Title (VR: AE, max 16 chars).
     */
    fun validateAeTitle(ae: String, fieldName: String = "aeTitle"): Result {
        val v = DicomValidationUtils.validateAeTitle(ae)
        return when (v) {
            is DicomValidationUtils.ValidationResult.Valid -> Result.Valid
            is DicomValidationUtils.ValidationResult.Invalid -> Result.Invalid(fieldName, v.messageRu)
        }
    }

    /**
     * Валидация Modality (VR: CS, max 16 chars, обычно 2-4 символа).
     */
    fun validateModality(modality: String): Result {
        val trimmed = modality.trim().uppercase(Locale.US)
        if (trimmed.isEmpty()) {
            return Result.Invalid("modality", "Модальность не может быть пустой")
        }
        if (trimmed.length > 16) {
            return Result.Invalid("modality", "Код модальности превышает 16 символов")
        }
        return Result.Valid
    }

    /**
     * Генерация стандартного уникального Study Instance UID.
     */
    fun generateStudyInstanceUid(): String {
        return DicomSecondaryCaptureGenerator.createStandardUid()
    }

    /**
     * Текущая дата в формате YYYYMMDD.
     */
    fun currentDateFormatted(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(System.currentTimeMillis())
    }

    /**
     * Текущее время в формате HHMMSS.
     */
    fun currentTimeFormatted(): String {
        return SimpleDateFormat("HHmmss", Locale.US).format(System.currentTimeMillis())
    }
}
