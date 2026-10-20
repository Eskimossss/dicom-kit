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
 * - Single Value Matching (включая нечувствительность к регистру для PN, LO, CS).
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

        val spsSeq = rqAttrs.getSequence(Tag.ScheduledProcedureStepSequence)
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

    /**
     * Проверяет, удовлетворяет ли назначенное исследование и пациент критериям запроса C-FIND.
     */
    fun matches(
        patient: MwlPatient?,
        study: MwlScheduledStudy,
        query: MatchingQuery
    ): Boolean {
        // 1. Patient Name
        if (query.patientName != null && !matchStringWildcard(query.patientName, study.patientName, ignoreCase = true)) {
            return false
        }

        // 2. Patient ID
        if (query.patientId != null && !matchStringWildcard(query.patientId, study.patientId, ignoreCase = true)) {
            return false
        }

        // 3. Accession Number
        if (query.accessionNumber != null && !matchStringWildcard(query.accessionNumber, study.accessionNumber, ignoreCase = true)) {
            return false
        }

        // 4. Study Instance UID
        if (query.studyInstanceUid != null && query.studyInstanceUid != study.studyInstanceUid) {
            return false
        }

        // 5. Requested Procedure ID
        if (query.requestedProcedureId != null && !matchStringWildcard(query.requestedProcedureId, study.requestedProcedureId, ignoreCase = true)) {
            return false
        }

        // 6. Modality (из SPS или корневого уровня)
        val modFilter = query.effectiveModality
        if (modFilter != null) {
            if (!matchStringWildcard(modFilter, study.modality, ignoreCase = true)) {
                return false
            }
        }

        // 7. Scheduled Station AE Title
        if (query.spsScheduledStationAeTitle != null) {
            if (!matchStringWildcard(query.spsScheduledStationAeTitle, study.scheduledStationAeTitle, ignoreCase = true)) {
                return false
            }
        }

        // 8. SPS Start Date Range (DA)
        if (query.spsStartDateRange != null) {
            if (!matchDateRange(query.spsStartDateRange, study.spsStartDate)) {
                return false
            }
        }

        // 9. SPS Start Time Range (TM)
        if (query.spsStartTimeRange != null) {
            if (!matchTimeRange(query.spsStartTimeRange, study.spsStartTime)) {
                return false
            }
        }

        // 10. SPS ID
        if (query.spsId != null) {
            if (!matchStringWildcard(query.spsId, study.spsId, ignoreCase = true)) {
                return false
            }
        }

        // 11. Scheduled Performing Physician
        if (query.spsPerformingPhysician != null) {
            if (!matchStringWildcard(query.spsPerformingPhysician, study.scheduledPerformingPhysician, ignoreCase = true)) {
                return false
            }
        }

        // 12. Scheduled Station Name
        if (query.spsStationName != null) {
            if (!matchStringWildcard(query.spsStationName, study.scheduledStationName, ignoreCase = true)) {
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
            // Для PN в DICOM допускается совпадение первого компонента (фамилии), если остальные не указаны
            if (pat.contains('^') || str.contains('^')) {
                val patParts = pat.split('^').filter { it.isNotEmpty() }
                val strParts = str.split('^').filter { it.isNotEmpty() }
                if (patParts.isNotEmpty() && strParts.isNotEmpty()) {
                    var allMatched = true
                    for (i in patParts.indices) {
                        if (i >= strParts.size || !matchStringWildcard(patParts[i], strParts[i], ignoreCase)) {
                            allMatched = false
                            break
                        }
                    }
                    if (allMatched) return true
                }
            }
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
        val trimmed = rangePattern.trim().replace(":", "")
        val actual = actualTime.trim().replace(":", "").padEnd(6, '0').take(6)
        if (actual.isEmpty()) return false

        if (!trimmed.contains('-')) {
            val patPadded = trimmed.padEnd(6, '0').take(6)
            return actual.startsWith(trimmed) || actual == patPadded
        }

        val parts = trimmed.split('-')
        val fromTime = parts.getOrNull(0)?.trim()?.let { if (it.isNotEmpty()) it.padEnd(6, '0').take(6) else "" }.orEmpty()
        val toTime = parts.getOrNull(1)?.trim()?.let { if (it.isNotEmpty()) it.padEnd(6, '9').take(6) else "" }.orEmpty()

        if (fromTime.isNotEmpty() && actual < fromTime) return false
        if (toTime.isNotEmpty() && actual > toTime) return false

        return true
    }
}
