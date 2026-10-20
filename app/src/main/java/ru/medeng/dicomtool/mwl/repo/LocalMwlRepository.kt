package ru.medeng.dicomtool.mwl.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.medeng.dicomtool.mwl.model.MwlModalityTemplate
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.mwl.model.SyntheticMwlGenerator
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

class LocalMwlRepository(
    private val rootDir: File,
    autoSeedIfEmpty: Boolean = true
) : MwlRepository {

    private val lock = Any()
    private val patientsFile = File(rootDir, "mwl_patients.json")
    private val studiesFile = File(rootDir, "mwl_studies.json")

    private val _patientsFlow = MutableStateFlow<List<MwlPatient>>(emptyList())
    override val patientsFlow: StateFlow<List<MwlPatient>> = _patientsFlow.asStateFlow()

    private val _studiesFlow = MutableStateFlow<List<MwlScheduledStudy>>(emptyList())
    override val studiesFlow: StateFlow<List<MwlScheduledStudy>> = _studiesFlow.asStateFlow()

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        loadFromDisk()
        if (autoSeedIfEmpty && _patientsFlow.value.isEmpty() && _studiesFlow.value.isEmpty()) {
            seedDefaultSyntheticData()
        }
    }

    private fun seedDefaultSyntheticData() {
        val seeded = SyntheticMwlGenerator.createMultipleSyntheticPatients(count = 5)
        addSyntheticBatch(seeded)
    }

    override fun reload() = synchronized(lock) {
        loadFromDisk()
    }

    private fun loadFromDisk() = synchronized(lock) {
        val loadedPatients = mutableListOf<MwlPatient>()
        if (patientsFile.exists()) {
            try {
                val json = patientsFile.readText(StandardCharsets.UTF_8)
                val objects = MwlJsonUtils.parseArrayOfObjects(json)
                for (map in objects) {
                    val p = MwlPatient(
                        id = map["id"] ?: UUID.randomUUID().toString(),
                        patientName = map["patientName"] ?: "",
                        patientId = map["patientId"] ?: "",
                        patientBirthDate = map["patientBirthDate"] ?: "",
                        patientSex = map["patientSex"] ?: "O",
                        patientWeight = map["patientWeight"] ?: "",
                        medicalAlerts = map["medicalAlerts"] ?: "",
                        isSynthetic = map["isSynthetic"]?.toBoolean() ?: true,
                        createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                        updatedAt = map["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                    )
                    if (p.patientName.isNotBlank() && p.patientId.isNotBlank()) {
                        loadedPatients.add(p)
                    }
                }
            } catch (_: Exception) {}
        }

        val loadedStudies = mutableListOf<MwlScheduledStudy>()
        if (studiesFile.exists()) {
            try {
                val json = studiesFile.readText(StandardCharsets.UTF_8)
                val objects = MwlJsonUtils.parseArrayOfObjects(json)
                for (map in objects) {
                    val s = MwlScheduledStudy(
                        id = map["id"] ?: UUID.randomUUID().toString(),
                        patientInternalId = map["patientInternalId"] ?: "",
                        patientName = map["patientName"] ?: "",
                        patientId = map["patientId"] ?: "",
                        accessionNumber = map["accessionNumber"] ?: "",
                        studyInstanceUid = map["studyInstanceUid"] ?: "",
                        requestedProcedureId = map["requestedProcedureId"] ?: "",
                        requestedProcedureDescription = map["requestedProcedureDescription"] ?: "",
                        referringPhysicianName = map["referringPhysicianName"] ?: "",
                        requestingPhysician = map["requestingPhysician"] ?: "",
                        admissionId = map["admissionId"] ?: "",
                        spsId = map["spsId"] ?: "",
                        modality = map["modality"] ?: "OT",
                        spsStartDate = map["spsStartDate"] ?: "",
                        spsStartTime = map["spsStartTime"] ?: "090000",
                        scheduledStationAeTitle = map["scheduledStationAeTitle"] ?: "MWL_STATION",
                        scheduledStationName = map["scheduledStationName"] ?: "",
                        spsDescription = map["spsDescription"] ?: "",
                        scheduledPerformingPhysician = map["scheduledPerformingPhysician"] ?: "",
                        spsLocation = map["spsLocation"] ?: "",
                        spsStatus = map["spsStatus"] ?: "SCHEDULED",
                        isSynthetic = map["isSynthetic"]?.toBoolean() ?: true,
                        createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                        updatedAt = map["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                    )
                    if (s.studyInstanceUid.isNotBlank()) {
                        loadedStudies.add(s)
                    }
                }
            } catch (_: Exception) {}
        }

        _patientsFlow.value = loadedPatients
        _studiesFlow.value = loadedStudies
    }

    private fun persistPatientsToDisk(list: List<MwlPatient>) {
        val tmp = File(rootDir, "mwl_patients.json.tmp")
        val sb = StringBuilder()
        sb.append("[\n")
        list.forEachIndexed { i, p ->
            sb.append("  {")
            sb.append("\"id\":\"").append(MwlJsonUtils.escape(p.id)).append("\",")
            sb.append("\"patientName\":\"").append(MwlJsonUtils.escape(p.patientName)).append("\",")
            sb.append("\"patientId\":\"").append(MwlJsonUtils.escape(p.patientId)).append("\",")
            sb.append("\"patientBirthDate\":\"").append(MwlJsonUtils.escape(p.patientBirthDate)).append("\",")
            sb.append("\"patientSex\":\"").append(MwlJsonUtils.escape(p.patientSex)).append("\",")
            sb.append("\"patientWeight\":\"").append(MwlJsonUtils.escape(p.patientWeight)).append("\",")
            sb.append("\"medicalAlerts\":\"").append(MwlJsonUtils.escape(p.medicalAlerts)).append("\",")
            sb.append("\"isSynthetic\":").append(p.isSynthetic).append(",")
            sb.append("\"createdAt\":").append(p.createdAt).append(",")
            sb.append("\"updatedAt\":").append(p.updatedAt)
            sb.append("}")
            if (i < list.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")

        FileOutputStream(tmp).use { fos ->
            fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {}
        }
        if (patientsFile.exists()) patientsFile.delete()
        tmp.renameTo(patientsFile)
    }

    private fun persistStudiesToDisk(list: List<MwlScheduledStudy>) {
        val tmp = File(rootDir, "mwl_studies.json.tmp")
        val sb = StringBuilder()
        sb.append("[\n")
        list.forEachIndexed { i, s ->
            sb.append("  {")
            sb.append("\"id\":\"").append(MwlJsonUtils.escape(s.id)).append("\",")
            sb.append("\"patientInternalId\":\"").append(MwlJsonUtils.escape(s.patientInternalId)).append("\",")
            sb.append("\"patientName\":\"").append(MwlJsonUtils.escape(s.patientName)).append("\",")
            sb.append("\"patientId\":\"").append(MwlJsonUtils.escape(s.patientId)).append("\",")
            sb.append("\"accessionNumber\":\"").append(MwlJsonUtils.escape(s.accessionNumber)).append("\",")
            sb.append("\"studyInstanceUid\":\"").append(MwlJsonUtils.escape(s.studyInstanceUid)).append("\",")
            sb.append("\"requestedProcedureId\":\"").append(MwlJsonUtils.escape(s.requestedProcedureId)).append("\",")
            sb.append("\"requestedProcedureDescription\":\"").append(MwlJsonUtils.escape(s.requestedProcedureDescription)).append("\",")
            sb.append("\"referringPhysicianName\":\"").append(MwlJsonUtils.escape(s.referringPhysicianName)).append("\",")
            sb.append("\"requestingPhysician\":\"").append(MwlJsonUtils.escape(s.requestingPhysician)).append("\",")
            sb.append("\"admissionId\":\"").append(MwlJsonUtils.escape(s.admissionId)).append("\",")
            sb.append("\"spsId\":\"").append(MwlJsonUtils.escape(s.spsId)).append("\",")
            sb.append("\"modality\":\"").append(MwlJsonUtils.escape(s.modality)).append("\",")
            sb.append("\"spsStartDate\":\"").append(MwlJsonUtils.escape(s.spsStartDate)).append("\",")
            sb.append("\"spsStartTime\":\"").append(MwlJsonUtils.escape(s.spsStartTime)).append("\",")
            sb.append("\"scheduledStationAeTitle\":\"").append(MwlJsonUtils.escape(s.scheduledStationAeTitle)).append("\",")
            sb.append("\"scheduledStationName\":\"").append(MwlJsonUtils.escape(s.scheduledStationName)).append("\",")
            sb.append("\"spsDescription\":\"").append(MwlJsonUtils.escape(s.spsDescription)).append("\",")
            sb.append("\"scheduledPerformingPhysician\":\"").append(MwlJsonUtils.escape(s.scheduledPerformingPhysician)).append("\",")
            sb.append("\"spsLocation\":\"").append(MwlJsonUtils.escape(s.spsLocation)).append("\",")
            sb.append("\"spsStatus\":\"").append(MwlJsonUtils.escape(s.spsStatus)).append("\",")
            sb.append("\"isSynthetic\":").append(s.isSynthetic).append(",")
            sb.append("\"createdAt\":").append(s.createdAt).append(",")
            sb.append("\"updatedAt\":").append(s.updatedAt)
            sb.append("}")
            if (i < list.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")

        FileOutputStream(tmp).use { fos ->
            fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            try {
                fos.fd.sync()
            } catch (_: Exception) {}
        }
        if (studiesFile.exists()) studiesFile.delete()
        tmp.renameTo(studiesFile)
    }

    override fun getAllPatients(): List<MwlPatient> = _patientsFlow.value

    override fun getPatientById(id: String): MwlPatient? = _patientsFlow.value.firstOrNull { it.id == id }

    override fun savePatient(patient: MwlPatient): MwlPatient = synchronized(lock) {
        val current = _patientsFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == patient.id }
        val toSave = patient.copy(updatedAt = System.currentTimeMillis())
        if (existingIndex >= 0) {
            current[existingIndex] = toSave
        } else {
            current.add(0, toSave)
        }
        persistPatientsToDisk(current)
        _patientsFlow.value = current

        // Обновляем денормализованные ФИО и ID в исследованиях этого пациента, если они изменились
        val currentStudies = _studiesFlow.value.toMutableList()
        var studiesChanged = false
        for (i in currentStudies.indices) {
            if (currentStudies[i].patientInternalId == toSave.id) {
                if (currentStudies[i].patientName != toSave.patientName || currentStudies[i].patientId != toSave.patientId) {
                    currentStudies[i] = currentStudies[i].copy(
                        patientName = toSave.patientName,
                        patientId = toSave.patientId,
                        updatedAt = System.currentTimeMillis()
                    )
                    studiesChanged = true
                }
            }
        }
        if (studiesChanged) {
            persistStudiesToDisk(currentStudies)
            _studiesFlow.value = currentStudies
        }

        toSave
    }

    override fun deletePatient(patientInternalId: String, cascadeStudies: Boolean): Boolean = synchronized(lock) {
        val currentPatients = _patientsFlow.value.toMutableList()
        val removed = currentPatients.removeAll { it.id == patientInternalId }
        if (removed) {
            persistPatientsToDisk(currentPatients)
            _patientsFlow.value = currentPatients

            if (cascadeStudies) {
                val currentStudies = _studiesFlow.value.toMutableList()
                val studiesRemoved = currentStudies.removeAll { it.patientInternalId == patientInternalId }
                if (studiesRemoved) {
                    persistStudiesToDisk(currentStudies)
                    _studiesFlow.value = currentStudies
                }
            }
        }
        removed
    }

    override fun getAllStudies(): List<MwlScheduledStudy> = _studiesFlow.value

    override fun getStudyById(id: String): MwlScheduledStudy? = _studiesFlow.value.firstOrNull { it.id == id }

    override fun getStudiesForPatient(patientInternalId: String): List<MwlScheduledStudy> =
        _studiesFlow.value.filter { it.patientInternalId == patientInternalId }

    override fun saveStudy(study: MwlScheduledStudy): MwlScheduledStudy = synchronized(lock) {
        val current = _studiesFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == study.id }
        val toSave = study.copy(updatedAt = System.currentTimeMillis())
        if (existingIndex >= 0) {
            current[existingIndex] = toSave
        } else {
            current.add(0, toSave)
        }
        persistStudiesToDisk(current)
        _studiesFlow.value = current
        toSave
    }

    override fun deleteStudy(studyId: String): Boolean = synchronized(lock) {
        val current = _studiesFlow.value.toMutableList()
        val removed = current.removeAll { it.id == studyId }
        if (removed) {
            persistStudiesToDisk(current)
            _studiesFlow.value = current
        }
        removed
    }

    override fun duplicateStudy(studyId: String): MwlScheduledStudy? = synchronized(lock) {
        val existing = getStudyById(studyId) ?: return null
        val now = System.currentTimeMillis()
        val randomSuffix = (1000..9999).random()
        val newStudy = existing.copy(
            id = UUID.randomUUID().toString(),
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            accessionNumber = if (existing.accessionNumber.length <= 11) "${existing.accessionNumber}_CP" else "ACC-$randomSuffix",
            spsId = "SPS-$randomSuffix",
            requestedProcedureId = "RP-$randomSuffix",
            createdAt = now,
            updatedAt = now
        )
        saveStudy(newStudy)
        newStudy
    }

    override fun addSyntheticBatch(items: List<Pair<MwlPatient, MwlScheduledStudy>>) = synchronized(lock) {
        val currentPatients = _patientsFlow.value.toMutableList()
        val currentStudies = _studiesFlow.value.toMutableList()

        for ((p, s) in items) {
            val pIdx = currentPatients.indexOfFirst { it.id == p.id || it.patientId == p.patientId }
            if (pIdx >= 0) {
                currentPatients[pIdx] = p
            } else {
                currentPatients.add(p)
            }

            val sIdx = currentStudies.indexOfFirst { it.id == s.id }
            if (sIdx >= 0) {
                currentStudies[sIdx] = s
            } else {
                currentStudies.add(s)
            }
        }

        persistPatientsToDisk(currentPatients)
        persistStudiesToDisk(currentStudies)
        _patientsFlow.value = currentPatients
        _studiesFlow.value = currentStudies
    }

    override fun clearAll() = synchronized(lock) {
        if (patientsFile.exists()) patientsFile.delete()
        if (studiesFile.exists()) studiesFile.delete()
        _patientsFlow.value = emptyList()
        _studiesFlow.value = emptyList()
    }

    /**
     * Безопасный экспорт ТОЛЬКО синтетических данных.
     * Защита персональных данных: реальные пациенты исключаются из экспорта.
     */
    override fun exportSyntheticJson(): String = synchronized(lock) {
        val synthPatients = _patientsFlow.value.filter { it.isSynthetic }
        val synthPatientIds = synthPatients.map { it.id }.toSet()
        val synthStudies = _studiesFlow.value.filter { it.isSynthetic && it.patientInternalId in synthPatientIds }

        val sb = StringBuilder()
        sb.append("{\n  \"version\": 1,\n  \"patients\": [\n")
        synthPatients.forEachIndexed { i, p ->
            sb.append("    {")
            sb.append("\"id\":\"").append(MwlJsonUtils.escape(p.id)).append("\",")
            sb.append("\"patientName\":\"").append(MwlJsonUtils.escape(p.patientName)).append("\",")
            sb.append("\"patientId\":\"").append(MwlJsonUtils.escape(p.patientId)).append("\",")
            sb.append("\"patientBirthDate\":\"").append(MwlJsonUtils.escape(p.patientBirthDate)).append("\",")
            sb.append("\"patientSex\":\"").append(MwlJsonUtils.escape(p.patientSex)).append("\",")
            sb.append("\"isSynthetic\":true")
            sb.append("}")
            if (i < synthPatients.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n  \"studies\": [\n")
        synthStudies.forEachIndexed { i, s ->
            sb.append("    {")
            sb.append("\"id\":\"").append(MwlJsonUtils.escape(s.id)).append("\",")
            sb.append("\"patientInternalId\":\"").append(MwlJsonUtils.escape(s.patientInternalId)).append("\",")
            sb.append("\"patientName\":\"").append(MwlJsonUtils.escape(s.patientName)).append("\",")
            sb.append("\"patientId\":\"").append(MwlJsonUtils.escape(s.patientId)).append("\",")
            sb.append("\"accessionNumber\":\"").append(MwlJsonUtils.escape(s.accessionNumber)).append("\",")
            sb.append("\"studyInstanceUid\":\"").append(MwlJsonUtils.escape(s.studyInstanceUid)).append("\",")
            sb.append("\"requestedProcedureId\":\"").append(MwlJsonUtils.escape(s.requestedProcedureId)).append("\",")
            sb.append("\"requestedProcedureDescription\":\"").append(MwlJsonUtils.escape(s.requestedProcedureDescription)).append("\",")
            sb.append("\"spsId\":\"").append(MwlJsonUtils.escape(s.spsId)).append("\",")
            sb.append("\"modality\":\"").append(MwlJsonUtils.escape(s.modality)).append("\",")
            sb.append("\"spsStartDate\":\"").append(MwlJsonUtils.escape(s.spsStartDate)).append("\",")
            sb.append("\"spsStartTime\":\"").append(MwlJsonUtils.escape(s.spsStartTime)).append("\",")
            sb.append("\"scheduledStationAeTitle\":\"").append(MwlJsonUtils.escape(s.scheduledStationAeTitle)).append("\",")
            sb.append("\"spsDescription\":\"").append(MwlJsonUtils.escape(s.spsDescription)).append("\",")
            sb.append("\"isSynthetic\":true")
            sb.append("}")
            if (i < synthStudies.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n}\n")
        sb.toString()
    }

    override fun importSyntheticJson(json: String): Int = synchronized(lock) {
        val rootMap = MwlJsonUtils.parseObject(json)
        val patientsRaw = rootMap["patients"] ?: "[]"
        val studiesRaw = rootMap["studies"] ?: "[]"

        val patientMaps = MwlJsonUtils.parseArrayOfObjects(patientsRaw)
        val studyMaps = MwlJsonUtils.parseArrayOfObjects(studiesRaw)

        val newPatients = mutableListOf<MwlPatient>()
        for (m in patientMaps) {
            val name = m["patientName"] ?: ""
            val pid = m["patientId"] ?: ""
            if (name.isNotBlank() && pid.isNotBlank()) {
                newPatients.add(
                    MwlPatient(
                        id = m["id"] ?: UUID.randomUUID().toString(),
                        patientName = name,
                        patientId = pid,
                        patientBirthDate = m["patientBirthDate"] ?: "",
                        patientSex = m["patientSex"] ?: "O",
                        isSynthetic = true
                    )
                )
            }
        }

        val newStudies = mutableListOf<MwlScheduledStudy>()
        for (m in studyMaps) {
            val uid = m["studyInstanceUid"] ?: MwlValidation.generateStudyInstanceUid()
            newStudies.add(
                MwlScheduledStudy(
                    id = m["id"] ?: UUID.randomUUID().toString(),
                    patientInternalId = m["patientInternalId"] ?: "",
                    patientName = m["patientName"] ?: "",
                    patientId = m["patientId"] ?: "",
                    accessionNumber = m["accessionNumber"] ?: "",
                    studyInstanceUid = uid,
                    requestedProcedureId = m["requestedProcedureId"] ?: "",
                    requestedProcedureDescription = m["requestedProcedureDescription"] ?: "",
                    spsId = m["spsId"] ?: "SPS-01",
                    modality = m["modality"] ?: "OT",
                    spsStartDate = m["spsStartDate"] ?: MwlValidation.currentDateFormatted(),
                    spsStartTime = m["spsStartTime"] ?: "090000",
                    scheduledStationAeTitle = m["scheduledStationAeTitle"] ?: "MWL_STATION",
                    spsDescription = m["spsDescription"] ?: "",
                    isSynthetic = true
                )
            )
        }

        if (newPatients.isNotEmpty()) {
            val pairs = mutableListOf<Pair<MwlPatient, MwlScheduledStudy>>()
            for (p in newPatients) {
                val matchingStudies = newStudies.filter { it.patientInternalId == p.id }
                if (matchingStudies.isNotEmpty()) {
                    for (s in matchingStudies) {
                        pairs.add(Pair(p, s))
                    }
                } else {
                    val dummyStudy = MwlScheduledStudy(
                        patientInternalId = p.id,
                        patientName = p.patientName,
                        patientId = p.patientId,
                        accessionNumber = "ACC-IMP",
                        studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
                        requestedProcedureId = "RP-IMP",
                        spsId = "SPS-IMP",
                        modality = "OT",
                        spsStartDate = MwlValidation.currentDateFormatted()
                    )
                    pairs.add(Pair(p, dummyStudy))
                }
            }
            addSyntheticBatch(pairs)
        }
        newStudies.size
    }
}
