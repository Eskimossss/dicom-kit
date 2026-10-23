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
        if (pn(name, "patientName", true) is Result.Invalid) return pn(name, "patientName", true)
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
        try { java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }
        catch (_: Exception) { return Result.Invalid(fieldName, "Несуществующая календарная дата") }
        return Result.Valid
    }

    /**
     * Валидация времени (VR: TM). Формат: HHMMSS или HHMM.
     */
    fun validateTime(timeStr: String, fieldName: String = "time", required: Boolean = false): Result {
        val trimmed = timeStr.trim()
        if (trimmed.isEmpty()) return if (required) Result.Invalid(fieldName, "Время обязательно") else Result.Valid
        if (!Regex("[0-9]{2}([0-9]{2}([0-9]{2}(\\.[0-9]{1,6})?)?)?").matches(trimmed)) return Result.Invalid(fieldName, "Ожидается DICOM TM: HH[MM[SS[.FFFFFF]]]")
        val clock = trimmed.substringBefore('.')
        if (clock.substring(0, 2).toInt() !in 0..23 ||
            (clock.length >= 4 && clock.substring(2, 4).toInt() !in 0..59) ||
            (clock.length >= 6 && clock.substring(4, 6).toInt() !in 0..60)) return Result.Invalid(fieldName, "Некорректное время")
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
        if (accession.length > 16 || accession.any { it == '\\' || it.isISOControl() }) {
            return Result.Invalid("accessionNumber", "Accession Number: одно значение SH до 16 символов, без обратной косой черты и управляющих символов")
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
        if (!Regex("[A-Z0-9 _]{1,16}").matches(modality)) {
            return Result.Invalid("modality", "Модальность: допустимы A–Z, 0–9, пробел и подчёркивание (VR: CS)")
        }
        return Result.Valid
    }

    private fun text(value: String, field: String, max: Int, required: Boolean = false): Result = when {
        required && value.isBlank() -> Result.Invalid(field, "$field: обязательное поле не заполнено")
        value.length > max || value.any { it < ' ' || it == '\\' } -> Result.Invalid(field, "$field: недопустимое значение или длина VR")
        else -> Result.Valid
    }
    fun validateShortString(value: String, field: String): Result = text(value, field, 16)
    private fun pn(value: String, field: String, required: Boolean = false): Result {
        if (required && value.isBlank()) return Result.Invalid(field, "Обязательное имя не заполнено")
        if (value.split('=').size > 3 || value.split('=').any { it.length > 64 || it.split('^').size > 5 } || value.any { it < ' ' || it == '\\' }) return Result.Invalid(field, "Некорректное DICOM PN")
        return Result.Valid
    }
    fun validatePatient(patient: MwlPatient): Result = listOf(
        pn(patient.patientName, "patientName", true), text(patient.patientId, "patientId", 64, true),
        validateDate(patient.patientBirthDate, "patientBirthDate"),
        if (patient.patientSex in setOf("", "M", "F", "O")) Result.Valid else Result.Invalid("patientSex", "Допустимы M/F/O"),
        text(patient.medicalAlerts, "medicalAlerts", 64),
        if (patient.patientWeight.isEmpty() || (patient.patientWeight.length <= 16 && patient.patientWeight.toBigDecimalOrNull() != null)) Result.Valid else Result.Invalid("patientWeight", "Некорректное DS")
    ).firstOrNull { it is Result.Invalid } ?: Result.Valid

    fun validateStudy(study: MwlScheduledStudy): Result = listOf(
        pn(study.patientName, "patientName", true), text(study.patientId, "patientId", 64, true),
        validateStudyInstanceUid(study.studyInstanceUid), validateAccessionNumber(study.accessionNumber),
        text(study.requestedProcedureId, "requestedProcedureId", 16, true), text(study.spsId, "spsId", 16, true),
        text(study.requestedProcedureDescription, "requestedProcedureDescription", 64, true),
        text(study.spsDescription.ifEmpty { study.requestedProcedureDescription }, "spsDescription", 64, true),
        validateDate(study.spsStartDate, "spsStartDate", true), validateTime(study.spsStartTime, "spsStartTime", true),
        validateAeTitle(study.scheduledStationAeTitle, "scheduledStationAeTitle"),
        text(study.scheduledStationName, "scheduledStationName", 16), text(study.spsLocation, "spsLocation", 16),
        text(study.admissionId, "admissionId", 64), pn(study.referringPhysicianName, "referringPhysicianName"),
        pn(study.requestingPhysician, "requestingPhysician"), pn(study.scheduledPerformingPhysician, "scheduledPerformingPhysician"),
        if (Regex("[A-Z0-9 _]{1,16}").matches(study.modality)) Result.Valid else Result.Invalid("modality", "Некорректное CS"),
        if (Regex("[A-Z0-9 _]{1,16}").matches(study.spsStatus)) Result.Valid else Result.Invalid("spsStatus", "Некорректное CS")
    ).firstOrNull { it is Result.Invalid } ?: Result.Valid

    fun requireValid(result: Result) {
        require(result is Result.Valid) { (result as Result.Invalid).let { "${it.field}: ${it.messageRu}" } }
    }

    /**
     * Генерация стандартного уникального Study Instance UID.
     */
    fun generateStudyInstanceUid(): String {
        return DicomSecondaryCaptureGenerator.createStandardUid()
    }

    /**
     * Генерация стандартного уникального Accession Number (VR: SH, до 16 символов).
     */
    fun generateAccessionNumber(): String {
        return "ACC-${(100000..999999).random()}"
    }

    /**
     * Генерация стандартного уникального Requested Procedure ID (VR: SH, до 16 символов).
     */
    fun generateRequestedProcedureId(): String {
        return "RP-${(100000..999999).random()}"
    }

    /**
     * Генерация стандартного уникального Scheduled Procedure Step ID (VR: SH, до 16 символов).
     */
    fun generateSpsId(): String {
        return "SPS-${(100000..999999).random()}"
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
