package ru.medeng.dicomtool.mwl.model

import org.dcm4che3.data.Attributes

enum class MwlDateFilterOption(val labelRu: String) {
    TODAY("Сегодня"),
    TOMORROW("Завтра"),
    CUSTOM_DATE("Выбранная дата"),
    DATE_RANGE("Диапазон дат"),
    ANY("Любая дата")
}

data class MwlScuFilter(
    val dateOption: MwlDateFilterOption = MwlDateFilterOption.TODAY,
    val specificDate: String = "", // YYYYMMDD
    val startDate: String = "",    // YYYYMMDD
    val endDate: String = "",      // YYYYMMDD
    val modality: String = "",     // CR, DX, CT, MG, US, MR или пусто (любая)
    val scheduledStationAe: String = "",
    val patientName: String = "",
    val patientId: String = "",
    val accessionNumber: String = "",
    val requestedProcedureId: String = ""
) {
    /**
     * Преобразует фильтр даты в стандартную строку DICOM DA Range.
     */
    fun buildDateRangePattern(): String {
        val today = MwlValidation.currentDateFormatted()
        return when (dateOption) {
            MwlDateFilterOption.TODAY -> today
            MwlDateFilterOption.TOMORROW -> {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(cal.time)
            }
            MwlDateFilterOption.CUSTOM_DATE -> specificDate.trim().ifEmpty { today }
            MwlDateFilterOption.DATE_RANGE -> {
                val s = startDate.trim()
                val e = endDate.trim()
                when {
                    s.isNotEmpty() && e.isNotEmpty() -> "$s-$e"
                    s.isNotEmpty() -> "$s-"
                    e.isNotEmpty() -> "-$e"
                    else -> ""
                }
            }
            MwlDateFilterOption.ANY -> ""
        }
    }

    fun isFilterEmpty(): Boolean {
        return dateOption == MwlDateFilterOption.ANY &&
                modality.isBlank() &&
                scheduledStationAe.isBlank() &&
                patientName.isBlank() &&
                patientId.isBlank() &&
                accessionNumber.isBlank() &&
                requestedProcedureId.isBlank()
    }
}

/**
 * Найденная запись в удалённом Modality Worklist.
 */
data class MwlQueryResultItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val patientName: String,
    val patientId: String,
    val patientBirthDate: String = "",
    val patientSex: String = "",
    val accessionNumber: String = "",
    val studyInstanceUid: String = "",
    val requestedProcedureId: String = "",
    val requestedProcedureDescription: String = "",
    val referringPhysicianName: String = "",
    val spsId: String = "",
    val modality: String = "",
    val spsStartDate: String = "",
    val spsStartTime: String = "",
    val scheduledStationAeTitle: String = "",
    val scheduledStationName: String = "",
    val spsDescription: String = "",
    val scheduledPerformingPhysician: String = "",
    val spsLocation: String = "",
    val specificCharacterSet: String = "",
    val rawAttributes: Attributes? = null
)
