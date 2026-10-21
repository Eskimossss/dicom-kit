package ru.medeng.dicomtool.mwl.model

/** Scheduling attributes, not a network endpoint or a Calling AE routing policy. */
data class MwlStationProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val modality: String,
    val scheduledStationAeTitle: String,
    val scheduledStationName: String = "",
    val location: String = ""
) {
    fun applyTo(study: MwlScheduledStudy) = study.copy(
        modality = modality, scheduledStationAeTitle = scheduledStationAeTitle,
        scheduledStationName = scheduledStationName, spsLocation = location
    )

    fun validate(): MwlValidation.Result = when {
        name.isBlank() || name.length > 120 || name.any { it.isISOControl() } ->
            MwlValidation.Result.Invalid("name", "Введите название профиля (до 120 символов)")
        else -> listOf(
            MwlValidation.validateModality(modality),
            MwlValidation.validateAeTitle(scheduledStationAeTitle, "scheduledStationAeTitle"),
            MwlValidation.validateShortString(scheduledStationName, "scheduledStationName"),
            MwlValidation.validateShortString(location, "spsLocation")
        ).firstOrNull { it is MwlValidation.Result.Invalid } ?: MwlValidation.Result.Valid
    }
}
