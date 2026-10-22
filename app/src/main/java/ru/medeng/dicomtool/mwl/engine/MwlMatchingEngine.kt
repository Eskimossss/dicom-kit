package ru.medeng.dicomtool.mwl.engine

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Sequence
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import java.util.Locale

/**
 * Стандартный механизм фильтрации (Matching) Modality Worklist C-FIND согласно DICOM PS3.4 Annex C и Annex K.
 *
 * Поддерживает:
 * - Single Value Matching (нечувствительность к регистру только для PN).
 * - Wildcard Matching ('*' и '?') для текстовых полей (PN, LO, SH).
 * - Range Matching для дат DA ('YYYYMMDD-YYYYMMDD', '-YYYYMMDD', 'YYYYMMDD-') и времени TM.
 * - Sequence Matching для вложенных атрибутов Scheduled Procedure Step Sequence (0040,0100).
 * - Толерантную обработку модальности (0008,0060), если аппарат передаёт её на корневом уровне вместо (0040,0100).
 * - Universal Matching (пустые атрибуты запроса = совпадают со всеми и служат Return Keys).
 */
object MwlMatchingEngine {

    data class MatchingQuery(
        val patientName: String? = null,
        val patientId: String? = null,
        val accessionNumber: String? = null,
        val studyInstanceUid: String? = null,
        val requestedProcedureId: String? = null,
        val rootModality: String? = null,

        // Атрибуты из Scheduled Procedure Step Sequence (0040,0100)
        val spsModality: String? = null,
        val spsScheduledStationAeTitle: String? = null,
        val spsStartDateRange: String? = null,
        val spsStartTimeRange: String? = null,
        val spsId: String? = null,
        val spsPerformingPhysician: String? = null,
        val spsStationName: String? = null
    ) {
        val effectiveModality: String?
            get() = spsModality?.ifBlank { null } ?: rootModality?.ifBlank { null }
    }

    /**
     * Извлекает критерии фильтрации (Matching Keys) из DICOM Attributes C-FIND-RQ.
     */
    fun extractMatchingQuery(rqAttrs: Attributes): MatchingQuery {
        val pName = rqAttrs.getString(Tag.PatientName)?.trim()?.ifEmpty { null }
        val pId = rqAttrs.getString(Tag.PatientID)?.trim()?.ifEmpty { null }
        val acc = rqAttrs.getString(Tag.AccessionNumber)?.trim()?.ifEmpty { null }
        val studyUid = rqAttrs.getString(Tag.StudyInstanceUID)?.trim()?.ifEmpty { null }
        val reqProcId = rqAttrs.getString(Tag.RequestedProcedureID)?.trim()?.ifEmpty { null }
        val rootMod = rqAttrs.getString(Tag.Modality)?.trim()?.ifEmpty { null }

        var spsMod: String? = null
        var spsStationAe: String? = null
        var spsDateRange: String? = null
        var spsTimeRange: String? = null
        var spsIdVal: String? = null
        var spsPhysician: String? = null
        var spsStation: String? = null

        require(!rqAttrs.contains(Tag.ScheduledProcedureStepSequence) || rqAttrs.getVR(Tag.ScheduledProcedureStepSequence) == VR.SQ) { "SPS must have VR SQ" }
        val spsSeq = rqAttrs.getSequence(Tag.ScheduledProcedureStepSequence)
        require(spsSeq == null || spsSeq.size <= 1) { "SPS must contain at most one query Item" }
        if (spsSeq != null && !spsSeq.isEmpty) {
            val item = spsSeq[0]
            spsMod = item.getString(Tag.Modality)?.trim()?.ifEmpty { null }
            spsStationAe = item.getString(Tag.ScheduledStationAETitle)?.trim()?.ifEmpty { null }
            spsDateRange = item.getString(Tag.ScheduledProcedureStepStartDate)?.trim()?.ifEmpty { null }
            spsTimeRange = item.getString(Tag.ScheduledProcedureStepStartTime)?.trim()?.ifEmpty { null }
            spsIdVal = item.getString(Tag.ScheduledProcedureStepID)?.trim()?.ifEmpty { null }
            spsPhysician = item.getString(Tag.ScheduledPerformingPhysicianName)?.trim()?.ifEmpty { null }
            spsStation = item.getString(Tag.ScheduledStationName)?.trim()?.ifEmpty { null }
        }

        val combined = spsDateRange?.contains('-') == true && spsTimeRange?.contains('-') == true
        validateRange(spsDateRange, false)
        validateRange(spsTimeRange, true, checkOrder = !combined)
        if (combined) {
            // PS3.4 K.6-1: MWL's two ranges form one interval, not a daily TM window.
            // A descending clock is valid across different days, but not on the same day.
            val (from, to) = dateTimeBounds(spsDateRange!!, spsTimeRange!!)
            require(from == null || to == null || from <= to) { "Reversed combined DA/TM range" }
        }
        return MatchingQuery(
            patientName = pName,
            patientId = pId,
            accessionNumber = acc,
            studyInstanceUid = studyUid,
            requestedProcedureId = reqProcId,
            rootModality = rootMod,
            spsModality = spsMod,
            spsScheduledStationAeTitle = spsStationAe,
            spsStartDateRange = spsDateRange,
            spsStartTimeRange = spsTimeRange,
            spsId = spsIdVal,
            spsPerformingPhysician = spsPhysician,
            spsStationName = spsStation
        )
    }

    private fun validateRange(pattern: String?, time: Boolean, checkOrder: Boolean = true) {
        if (pattern == null) return
        val parts = pattern.split('-')
        require(parts.size <= 2 && parts.any(String::isNotEmpty)) { "Invalid query range" }
        for (part in parts.filter(String::isNotEmpty)) {
            val valid = if (time) ru.medeng.dicomtool.mwl.model.MwlValidation.validateTime(part, required = true)
                else ru.medeng.dicomtool.mwl.model.MwlValidation.validateDate(part, required = true)
            require(valid is ru.medeng.dicomtool.mwl.model.MwlValidation.Result.Valid) { "Invalid query DA/TM" }
        }
        if (checkOrder && parts.size == 2 && parts.all(String::isNotEmpty)) {
            val from = if (time) normalizedTime(parts[0], false) else parts[0]
            val to = if (time) normalizedTime(parts[1], true) else parts[1]
            require(from <= to) { "Reversed query range" }
        }
    }

    /**
     * Проверяет, удовлетворяет ли назначенное исследование и пациент критериям запроса C-FIND.
     */
    fun matches(
        patient: MwlPatient?,
        study: MwlScheduledStudy,
        query: MatchingQuery
    ): Boolean {
        // 1. Patient Name
        if (query.patientName != null && !matchPersonName(query.patientName, patient?.patientName ?: study.patientName)) {
            return false
        }

        // 2. Patient ID
        if (query.patientId != null && query.patientId != (patient?.patientId ?: study.patientId)) {
            return false
        }

        // 3. Accession Number
        if (query.accessionNumber != null && !matchStringWildcard(query.accessionNumber, study.accessionNumber, ignoreCase = false)) {
            return false
        }

        // 4. Study Instance UID
        if (query.studyInstanceUid != null && query.studyInstanceUid != study.studyInstanceUid) {
            return false
        }

        // 5. Requested Procedure ID
        if (query.requestedProcedureId != null && !matchStringWildcard(query.requestedProcedureId, study.requestedProcedureId, ignoreCase = false)) {
            return false
        }

        // 6. Modality (из SPS или корневого уровня)
        val modFilter = query.effectiveModality
        if (modFilter != null) {
            if (modFilter != study.modality) {
                return false
            }
        }

        // 7. Scheduled Station AE Title
        if (query.spsScheduledStationAeTitle != null) {
            if (query.spsScheduledStationAeTitle != study.scheduledStationAeTitle) {
                return false
            }
        }

        val dates = query.spsStartDateRange
        val times = query.spsStartTimeRange
        if (dates != null && times != null && '-' in dates && '-' in times) {
            if (!matchDateTimeRange(dates, times, study.spsStartDate, study.spsStartTime)) return false
        } else {
            if (dates != null && !matchDateRange(dates, study.spsStartDate)) return false
            if (times != null && !matchTimeRange(times, study.spsStartTime)) return false
        }

        // 10. SPS ID
        if (query.spsId != null) {
            if (!matchStringWildcard(query.spsId, study.spsId, ignoreCase = false)) {
                return false
            }
        }

        // 11. Scheduled Performing Physician
        if (query.spsPerformingPhysician != null) {
            if (!matchPersonName(query.spsPerformingPhysician, study.scheduledPerformingPhysician)) {
                return false
            }
        }

        // 12. Scheduled Station Name
        if (query.spsStationName != null) {
            if (!matchStringWildcard(query.spsStationName, study.scheduledStationName, ignoreCase = false)) {
                return false
            }
        }

        return true
    }

    /**
     * Сравнение строк по стандарту DICOM с поддержкой Wildcard ('*' и '?') и нечувствительности к регистру.
     */
    fun matchStringWildcard(pattern: String, value: String, ignoreCase: Boolean = true): Boolean {
        val pat = if (ignoreCase) pattern.uppercase(Locale.US) else pattern
        val str = if (ignoreCase) value.uppercase(Locale.US) else value

        if (pat == "*" || pat == str) return true
        if (!pat.contains('*') && !pat.contains('?')) {
            return pat == str
        }

        // Преобразование DICOM Wildcard ('*', '?') в регулярное выражение
        val regexPattern = StringBuilder("^")
        for (ch in pat) {
            when (ch) {
                '*' -> regexPattern.append(".*")
                '?' -> regexPattern.append(".")
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    regexPattern.append("\\").append(ch)
                }
                else -> regexPattern.append(ch)
            }
        }
        regexPattern.append("$")

        return try {
            val flags = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(regexPattern.toString(), flags).matches(str)
        } catch (_: Exception) {
            pat == str
        }
    }

    fun matchPersonName(pattern: String, value: String): Boolean {
        if (pattern.isEmpty()) return true
        if (value.isEmpty()) return pattern == "*"
        if ('*' in pattern || '?' in pattern) return matchStringWildcard(pattern, value, true)
        val expected = pattern.split('=')
        val actual = value.split('=')
        return expected.withIndex().all { (group, name) ->
            val components = name.split('^')
            val values = actual.getOrNull(group)?.split('^').orEmpty()
            components.withIndex().all { (index, part) ->
                part.isEmpty() || part.equals(values.getOrNull(index).orEmpty(), ignoreCase = true)
            }
        }
    }

    fun matchDateTimeRange(dates: String, times: String, date: String, time: String): Boolean {
        if (date.isEmpty() || time.isEmpty()) return false
        val (from, to) = dateTimeBounds(dates, times)
        val actual = date + normalizedTime(time, false)
        return (from == null || actual >= from) && (to == null || actual <= to)
    }
    private fun dateTimeBounds(dates: String, times: String): Pair<String?, String?> {
        val ds = dates.split('-', limit = 2)
        val ts = times.split('-', limit = 2)
        // An open date boundary is unbounded; an open time uses the finite boundary day's edge.
        val from = ds[0].takeIf { it.isNotEmpty() }?.let { it + ts[0].takeIf(String::isNotEmpty).let { t -> if (t == null) "000000000000" else normalizedTime(t, false) } }
        val to = ds[1].takeIf { it.isNotEmpty() }?.let { it + ts[1].takeIf(String::isNotEmpty).let { t -> if (t == null) "235960999999" else normalizedTime(t, true) } }
        return Pair(from, to)
    }
    private fun normalizedTime(time: String, upper: Boolean): String {
        val parts = time.split('.', limit = 2)
        val clock = parts[0].padEnd(6, if (upper) '9' else '0')
        return clock + parts.getOrElse(1) { "" }.padEnd(6, if (upper) '9' else '0')
    }

    /**
     * Range Matching для дат DICOM DA (формат YYYYMMDD):
     * - 'date1-date2': от date1 до date2 включительно
     * - '-date2': до date2 включительно
     * - 'date1-': от date1 включительно
     * - 'date1': точное совпадение
     */
    fun matchDateRange(rangePattern: String, actualDate: String): Boolean {
        val trimmed = rangePattern.trim()
        val actual = actualDate.trim()
        if (actual.isEmpty()) return false

        if (!trimmed.contains('-')) {
            return trimmed == actual
        }

        val parts = trimmed.split('-')
        val fromDate = parts.getOrNull(0)?.trim().orEmpty()
        val toDate = parts.getOrNull(1)?.trim().orEmpty()

        if (fromDate.isNotEmpty() && actual < fromDate) return false
        if (toDate.isNotEmpty() && actual > toDate) return false

        return true
    }

    /**
     * Range Matching для времени DICOM TM (формат HHMMSS или HHMM):
     * - 'time1-time2'
     * - '-time2'
     * - 'time1-'
     * - 'time1' (префиксное или точное)
     */
    fun matchTimeRange(rangePattern: String, actualTime: String): Boolean {
        if (actualTime.isBlank()) return false
        val actual = normalizedTime(actualTime.trim(), false)
        val parts = rangePattern.trim().split('-', limit = 2)
        val from = parts[0].takeIf(String::isNotEmpty)?.let { normalizedTime(it, false) }
        val to = (parts.getOrNull(1) ?: parts[0]).takeIf(String::isNotEmpty)?.let { normalizedTime(it, true) }
        return (from == null || actual >= from) && (to == null || actual <= to)
    }
}
