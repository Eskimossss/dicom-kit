package ru.medeng.dicomtool.mwl.repo

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.medeng.dicomtool.mwl.model.MwlStationProfile
import ru.medeng.dicomtool.mwl.model.MwlValidation

/** Separate additive settings file. Never rewrites the patient/appointment snapshot. */
class MwlStationProfileRepository(
    rootDir: File,
    private val writer: (File, String) -> Unit = { file, text -> AtomicMwlFile.write(file, text) }
) {
    private val file = File(rootDir, "mwl_station_profiles.json")
    private val state = MutableStateFlow<List<MwlStationProfile>>(emptyList())
    val profilesFlow = state.asStateFlow()
    var readError: String? = null
        private set
    init {
        check(rootDir.isDirectory || rootDir.mkdirs())
        if (file.exists()) try {
            state.value = MwlJsonUtils.parseArrayOfObjects(file.readText(Charsets.UTF_8)).map { m ->
                MwlStationProfile(requireNotNull(m["id"]), requireNotNull(m["name"]),
                    requireNotNull(m["modality"]), requireNotNull(m["scheduledStationAeTitle"]),
                    m["scheduledStationName"].orEmpty(), m["location"].orEmpty()).also {
                    MwlValidation.requireValid(it.validate())
                }
            }.also { list -> require(list.map { it.id }.distinct().size == list.size) }
        } catch (_: Exception) {
            readError = "Не удалось прочитать профили аппаратов. Исходный файл сохранён; запись заблокирована до восстановления настроек."
        }
    }
    @Synchronized fun save(profile: MwlStationProfile): MwlStationProfile {
        check(readError == null) { "Existing station profiles could not be read; file preserved" }
        MwlValidation.requireValid(profile.validate())
        require(profile.id.isNotBlank())
        val next = state.value.filterNot { it.id == profile.id } + profile
        val text = next.joinToString(",", "[", "]") { p ->
            mapOf("id" to p.id, "name" to p.name, "modality" to p.modality,
                "scheduledStationAeTitle" to p.scheduledStationAeTitle,
                "scheduledStationName" to p.scheduledStationName, "location" to p.location)
                .entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${MwlJsonUtils.escape(v)}\"" }
        }
        writer(file, text)
        state.value = next
        return profile
    }
}
