package ru.medeng.dicomtool.mwl.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.medeng.dicomtool.mwl.model.*
import java.io.File
import java.io.IOException
import java.util.UUID

/** Patients and appointments are committed together in one atomically replaced snapshot. */
class LocalMwlRepository(
    private val rootDir: File,
    autoSeedIfEmpty: Boolean = true,
    private val snapshotWriter: (File, String) -> Unit = { file, text -> AtomicMwlFile.write(file, text) }
) : MwlRepository {
    private val lock = Any()
    private val snapshotFile = File(rootDir, "mwl_snapshot.json")
    private val patientsFile = File(rootDir, "mwl_patients.json")
    private val studiesFile = File(rootDir, "mwl_studies.json")
    private val _patientsFlow = MutableStateFlow<List<MwlPatient>>(emptyList())
    override val patientsFlow: StateFlow<List<MwlPatient>> = _patientsFlow.asStateFlow()
    private val _studiesFlow = MutableStateFlow<List<MwlScheduledStudy>>(emptyList())
    override val studiesFlow: StateFlow<List<MwlScheduledStudy>> = _studiesFlow.asStateFlow()
    private val _readErrorFlow = MutableStateFlow<String?>(null)
    override val readErrorFlow: StateFlow<String?> = _readErrorFlow.asStateFlow()

    init {
        check(rootDir.isDirectory || rootDir.mkdirs()) { "Cannot create MWL data directory" }
        reload()
        if (autoSeedIfEmpty && _readErrorFlow.value == null && !snapshotFile.exists() && !patientsFile.exists() && !studiesFile.exists()) {
            addSyntheticBatch(SyntheticMwlGenerator.createMultipleSyntheticPatients(count = 5))
        }
    }
    private fun patient(m: Map<String, String>): MwlPatient {
        return MwlPatient(
            id = m["id"] ?: UUID.randomUUID().toString(),
            patientName = m["patientName"] ?: "",
            patientId = m["patientId"] ?: "",
            patientBirthDate = m["patientBirthDate"] ?: "",
            patientSex = m["patientSex"] ?: "O",
            patientWeight = m["patientWeight"] ?: "",
            medicalAlerts = m["medicalAlerts"] ?: "",
            isSynthetic = m["isSynthetic"]?.toBoolean() ?: true,
            createdAt = m["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
            updatedAt = m["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        )
    }
    private fun study(m: Map<String, String>): MwlScheduledStudy {
        return MwlScheduledStudy(
            id = m["id"] ?: UUID.randomUUID().toString(),
            patientInternalId = m["patientInternalId"] ?: "",
            patientName = m["patientName"] ?: "",
            patientId = m["patientId"] ?: "",
            accessionNumber = m["accessionNumber"] ?: "",
            studyInstanceUid = m["studyInstanceUid"] ?: "",
            requestedProcedureId = m["requestedProcedureId"] ?: "",
            requestedProcedureDescription = m["requestedProcedureDescription"] ?: "",
            referringPhysicianName = m["referringPhysicianName"] ?: "",
            requestingPhysician = m["requestingPhysician"] ?: "",
            admissionId = m["admissionId"] ?: "",
            spsId = m["spsId"] ?: "",
            modality = m["modality"] ?: "OT",
            spsStartDate = m["spsStartDate"] ?: "",
            spsStartTime = m["spsStartTime"] ?: "090000",
            scheduledStationAeTitle = m["scheduledStationAeTitle"] ?: "MWL_STATION",
            scheduledStationName = m["scheduledStationName"] ?: "",
            spsDescription = m["spsDescription"] ?: "",
            scheduledPerformingPhysician = m["scheduledPerformingPhysician"] ?: "",
            spsLocation = m["spsLocation"] ?: "",
            spsStatus = m["spsStatus"] ?: "SCHEDULED",
            isSynthetic = m["isSynthetic"]?.toBoolean() ?: true,
            createdAt = m["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
            updatedAt = m["updatedAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        )
    }
    override fun reload() = synchronized(lock) {
        try {
            val root = if (snapshotFile.exists()) MwlJsonUtils.parseRawObject(snapshotFile.readText(Charsets.UTF_8)).also {
                require(it["version"] == "1") { "Unsupported MWL snapshot version" }
                require(it.containsKey("patients") && it.containsKey("studies")) { "Incomplete MWL snapshot" }
            } else null
            // A present snapshot never falls back to obsolete legacy files, even if it is damaged.
            val ps = root?.getValue("patients") ?: if (patientsFile.exists()) patientsFile.readText(Charsets.UTF_8) else "[]"
            val ss = root?.getValue("studies") ?: if (studiesFile.exists()) studiesFile.readText(Charsets.UTF_8) else "[]"
            val patientRecords = MwlJsonUtils.parseArrayOfObjects(ps)
            val studyRecords = MwlJsonUtils.parseArrayOfObjects(ss)
            if (root != null) {
                require(patientRecords.all { !it["id"].isNullOrBlank() } && studyRecords.all { !it["id"].isNullOrBlank() && it.containsKey("patientInternalId") }) { "Invalid MWL snapshot identity" }
            }
            val patients = patientRecords.map(::patient)
            val studies = studyRecords.map(::study)
            require(patients.map { it.id }.distinct().size == patients.size && studies.map { it.id }.distinct().size == studies.size) { "Duplicate MWL snapshot identifiers" }
            _patientsFlow.value = patients
            _studiesFlow.value = studies
            _readErrorFlow.value = null
        } catch (_: Exception) {
            // Do not publish half a load, reseed, migrate, or overwrite a file we could not read.
            _readErrorFlow.value = "Не удалось прочитать базу MWL: файл повреждён, недоступен или имеет неподдерживаемую структуру/версию. Исходные файлы сохранены; запись, экспорт и выдача назначений заблокированы до восстановления базы."
        }
    }
    private fun requireReadable() { _readErrorFlow.value?.let { throw IOException(it) } }
    private fun json(ps: List<MwlPatient>, ss: List<MwlScheduledStudy>) =
        "{\"version\":1,\"patients\":" + encodePatients(ps) + ",\"studies\":" + encodeStudies(ss) + "}"

    private fun commit(ps: List<MwlPatient>, ss: List<MwlScheduledStudy>) {
        requireReadable()
        snapshotWriter(snapshotFile, json(ps, ss))
        _patientsFlow.value = ps.toList()
        _studiesFlow.value = ss.toList()
        // Legacy files are obsolete only after the complete snapshot has been committed.
        listOf(patientsFile, studiesFile).forEach { if (it.isFile) it.delete() }
    }
    override fun snapshot(): MwlSnapshot = synchronized(lock) { requireReadable(); MwlSnapshot(_patientsFlow.value, _studiesFlow.value) }
    override fun getAllPatients() = _patientsFlow.value
    override fun getPatientById(id: String) = getAllPatients().firstOrNull { it.id == id }
    override fun getAllStudies() = _studiesFlow.value
    override fun getStudyById(id: String) = getAllStudies().firstOrNull { it.id == id }
    override fun getStudiesForPatient(patientInternalId: String) = getAllStudies().filter { it.patientInternalId == patientInternalId }

    override fun savePatient(patient: MwlPatient): MwlPatient = synchronized(lock) {
        MwlValidation.requireValid(MwlValidation.validatePatient(patient))
        val saved = patient.copy(updatedAt = System.currentTimeMillis())
        val ps = getAllPatients().filterNot { it.id == saved.id } + saved
        val ss = getAllStudies().map { if (it.patientInternalId == saved.id) it.copy(patientName = saved.patientName, patientId = saved.patientId) else it }
        commit(ps, ss); saved
    }
    override fun saveStudy(study: MwlScheduledStudy): MwlScheduledStudy = synchronized(lock) {
        val patient = requireNotNull(getPatientById(study.patientInternalId)) { "Appointment patient does not exist" }
        val saved = study.copy(patientName = patient.patientName, patientId = patient.patientId,
            isSynthetic = study.isSynthetic && patient.isSynthetic, updatedAt = System.currentTimeMillis())
        MwlValidation.requireValid(MwlValidation.validateStudy(saved))
        commit(getAllPatients(), getAllStudies().filterNot { it.id == saved.id } + saved); saved
    }
    override fun deletePatient(patientInternalId: String, cascadeStudies: Boolean): Boolean = synchronized(lock) {
        val ps = getAllPatients().filterNot { it.id == patientInternalId }
        if (ps.size == getAllPatients().size) return false
        commit(ps, if (cascadeStudies) getAllStudies().filterNot { it.patientInternalId == patientInternalId } else getAllStudies()); true
    }
    override fun deleteStudy(studyId: String): Boolean = synchronized(lock) {
        val ss = getAllStudies().filterNot { it.id == studyId }
        if (ss.size == getAllStudies().size) return false
        commit(getAllPatients(), ss); true
    }
    override fun duplicateStudy(studyId: String): MwlScheduledStudy? = synchronized(lock) {
        val existing = getStudyById(studyId) ?: return null
        val suffix = UUID.randomUUID().toString().take(8)
        saveStudy(existing.copy(id = UUID.randomUUID().toString(), studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            accessionNumber = "ACC-$suffix", spsId = "SPS-$suffix", requestedProcedureId = "RP-$suffix", createdAt = System.currentTimeMillis()))
    }
    override fun addSyntheticBatch(items: List<Pair<MwlPatient, MwlScheduledStudy>>) = synchronized(lock) {
        val ps = getAllPatients().associateBy { it.id }.toMutableMap()
        val ss = getAllStudies().associateBy { it.id }.toMutableMap()
        items.forEach { (p, s) -> MwlValidation.requireValid(MwlValidation.validatePatient(p)); MwlValidation.requireValid(MwlValidation.validateStudy(s)); ps[p.id] = p; ss[s.id] = s }
        commit(ps.values.toList(), ss.values.toList())
    }
    override fun clearAll() = synchronized(lock) { commit(emptyList(), emptyList()) }
    override fun exportSyntheticJson(): String = synchronized(lock) {
        requireReadable()
        val ps = getAllPatients().filter { it.isSynthetic }
        val ids = ps.map { it.id }.toSet()
        json(ps, getAllStudies().filter { it.isSynthetic && it.patientInternalId in ids })
    }
    override fun importSyntheticJson(json: String): Int = synchronized(lock) {
        require(json.length <= 8 * 1024 * 1024) { "MWL import exceeds 8 MiB" }
        val root = MwlJsonUtils.parseObject(json)
        require(root["version"] == "1") { "Unsupported MWL JSON version" }
        val ps = MwlJsonUtils.parseArrayOfObjects(requireNotNull(root["patients"])).map { patient(it).copy(isSynthetic = true) }
        val ss = MwlJsonUtils.parseArrayOfObjects(requireNotNull(root["studies"])).map { study(it).copy(isSynthetic = true) }
        require(ps.map { it.id }.distinct().size == ps.size && ss.map { it.id }.distinct().size == ss.size) { "Duplicate MWL identifiers" }
        ps.forEach { MwlValidation.requireValid(MwlValidation.validatePatient(it)) }
        ss.forEach { MwlValidation.requireValid(MwlValidation.validateStudy(it)) }
        val ids = ps.map { it.id }.toSet()
        require(ss.all { it.patientInternalId in ids }) { "Appointment has no imported patient" }
        val mergedPatients = getAllPatients().associateBy { it.id }.toMutableMap()
        val mergedStudies = getAllStudies().associateBy { it.id }.toMutableMap()
        require(ps.none { p -> mergedPatients[p.id]?.isSynthetic == false } && ss.none { s -> mergedStudies[s.id]?.isSynthetic == false }) { "Import cannot replace real records" }
        ps.forEach { mergedPatients[it.id] = it }; ss.forEach { mergedStudies[it.id] = it }
        // Patient records own PN/Patient ID; appointment copies serve cards/search/export.
        // Refresh even appointments absent from the incoming batch, in the same disk commit.
        val consistentStudies = mergedStudies.values.map { study ->
            if (study.patientInternalId in ids) {
                val patient = mergedPatients.getValue(study.patientInternalId)
                study.copy(patientName = patient.patientName, patientId = patient.patientId)
            } else study
        }
        commit(mergedPatients.values.toList(), consistentStudies); ss.size
    }
    private fun encodePatients(list: List<MwlPatient>): String {
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

        return sb.toString()
    }
    private fun encodeStudies(list: List<MwlScheduledStudy>): String {
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

        return sb.toString()
    }
}
