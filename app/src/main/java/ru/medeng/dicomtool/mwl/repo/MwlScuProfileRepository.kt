package ru.medeng.dicomtool.mwl.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.medeng.dicomtool.mwl.model.MwlScuProfile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

class MwlScuProfileRepository(private val rootDir: File) {

    private val lock = Any()
    private val profilesFile = File(rootDir, "mwl_scu_profiles.json")

    private val _profilesFlow = MutableStateFlow<List<MwlScuProfile>>(emptyList())
    val profilesFlow: StateFlow<List<MwlScuProfile>> = _profilesFlow.asStateFlow()

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        loadFromDisk()
        if (_profilesFlow.value.isEmpty()) {
            val defaultProfile = MwlScuProfile(
                id = UUID.randomUUID().toString(),
                name = "Локальный MWL SCP (по умолчанию)",
                host = "127.0.0.1",
                port = 11112,
                calledAet = "MWL_SCP",
                callingAet = "DICOMKIT_SCU",
                isDefault = true
            )
            saveProfile(defaultProfile)
        }
    }

    private fun loadFromDisk() = synchronized(lock) {
        val list = mutableListOf<MwlScuProfile>()
        if (profilesFile.exists()) {
            try {
                val json = profilesFile.readText(StandardCharsets.UTF_8)
                val objects = MwlJsonUtils.parseArrayOfObjects(json)
                for (map in objects) {
                    val p = MwlScuProfile(
                        id = map["id"] ?: UUID.randomUUID().toString(),
                        name = map["name"] ?: "MWL Server",
                        host = map["host"] ?: "127.0.0.1",
                        port = map["port"]?.toIntOrNull() ?: 11112,
                        calledAet = map["calledAet"] ?: "MWL_SCP",
                        callingAet = map["callingAet"] ?: "DICOMKIT_SCU",
                        connectionTimeoutMs = map["connectionTimeoutMs"]?.toIntOrNull() ?: 10000,
                        responseTimeoutMs = map["responseTimeoutMs"]?.toIntOrNull() ?: 15000,
                        isDefault = map["isDefault"]?.toBoolean() ?: false
                    )
                    list.add(p)
                }
            } catch (e: Exception) { throw java.io.IOException("Cannot load MWL profiles; existing file was preserved", e) }
        }
        _profilesFlow.value = list
    }

    private fun persistToDisk(list: List<MwlScuProfile>) {
        val sb = StringBuilder()
        sb.append("[\n")
        list.forEachIndexed { i, p ->
            sb.append("  {")
            sb.append("\"id\":\"").append(MwlJsonUtils.escape(p.id)).append("\",")
            sb.append("\"name\":\"").append(MwlJsonUtils.escape(p.name)).append("\",")
            sb.append("\"host\":\"").append(MwlJsonUtils.escape(p.host)).append("\",")
            sb.append("\"port\":").append(p.port).append(",")
            sb.append("\"calledAet\":\"").append(MwlJsonUtils.escape(p.calledAet)).append("\",")
            sb.append("\"callingAet\":\"").append(MwlJsonUtils.escape(p.callingAet)).append("\",")
            sb.append("\"connectionTimeoutMs\":").append(p.connectionTimeoutMs).append(",")
            sb.append("\"responseTimeoutMs\":").append(p.responseTimeoutMs).append(",")
            sb.append("\"isDefault\":").append(p.isDefault)
            sb.append("}")
            if (i < list.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")

        AtomicMwlFile.write(profilesFile, sb.toString())
    }

    fun getAllProfiles(): List<MwlScuProfile> = _profilesFlow.value

    fun getProfileById(id: String): MwlScuProfile? = _profilesFlow.value.firstOrNull { it.id == id }

    fun getDefaultProfile(): MwlScuProfile? = _profilesFlow.value.firstOrNull { it.isDefault } ?: _profilesFlow.value.firstOrNull()

    fun saveProfile(profile: MwlScuProfile): MwlScuProfile = synchronized(lock) {
        val current = _profilesFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == profile.id }
        var toSave = profile
        if (toSave.isDefault) {
            // Сбрасываем флаг у остальных
            for (i in current.indices) {
                if (current[i].id != toSave.id && current[i].isDefault) {
                    current[i] = current[i].copy(isDefault = false)
                }
            }
        } else if (current.none { it.isDefault && it.id != toSave.id }) {
            // Если профилей больше нет или ни один не по умолчанию, делаем этот по умолчанию
            toSave = toSave.copy(isDefault = true)
        }

        if (existingIndex >= 0) {
            current[existingIndex] = toSave
        } else {
            current.add(toSave)
        }
        persistToDisk(current)
        _profilesFlow.value = current
        toSave
    }

    fun deleteProfile(id: String): Boolean = synchronized(lock) {
        val current = _profilesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val removed = current.removeAt(index)
            if (removed.isDefault && current.isNotEmpty()) {
                current[0] = current[0].copy(isDefault = true)
            }
            persistToDisk(current)
            _profilesFlow.value = current
            true
        } else {
            false
        }
    }
}
