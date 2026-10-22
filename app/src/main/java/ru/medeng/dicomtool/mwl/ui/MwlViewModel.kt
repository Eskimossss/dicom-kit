package ru.medeng.dicomtool.mwl.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.mwl.MwlRuntimeRegistry
import ru.medeng.dicomtool.mwl.model.MwlModalityTemplate
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlQueryResultItem
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import ru.medeng.dicomtool.mwl.model.MwlScuFilter
import ru.medeng.dicomtool.mwl.model.MwlScuProfile
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.mwl.model.SyntheticMwlGenerator
import ru.medeng.dicomtool.mwl.scp.MwlScpForegroundService
import ru.medeng.dicomtool.mwl.scp.MwlScpState
import java.io.IOException

enum class MwlSubTab(val titleRu: String) {
    SCP_SERVER("Сервер (SCP)"),
    APPOINTMENTS("Назначения"),
    SCU_CLIENT("Клиент (SCU)"),
    JOURNAL("Журнал")
}

class MwlViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MwlRuntimeRegistry.getMwlRepository(application)
    private val profileRepository = MwlRuntimeRegistry.getProfileRepository(application)
    private val scpServer = MwlRuntimeRegistry.getMwlScpServer(application)
    private val scuClient = MwlRuntimeRegistry.getMwlScuClient(application)
    private val logger = MwlRuntimeRegistry.getMwlLogger(application)

    val scpState: StateFlow<MwlScpState> = scpServer.state
    val patients: StateFlow<List<MwlPatient>> = repository.patientsFlow
    val studies: StateFlow<List<MwlScheduledStudy>> = repository.studiesFlow
    val repositoryReadError = repository.readErrorFlow
    private val stationRepository = MwlRuntimeRegistry.getStationProfiles(application)
    val stationProfiles = stationRepository.profilesFlow
    val stationProfileReadError = stationRepository.readError
    fun saveStationProfile(profile: ru.medeng.dicomtool.mwl.model.MwlStationProfile): MwlValidation.Result {
        val validation = profile.validate()
        if (validation is MwlValidation.Result.Invalid) return validation
        return try { stationRepository.save(profile); MwlValidation.Result.Valid }
        catch (_: Exception) { MwlValidation.Result.Invalid("storage", "Не удалось сохранить профиль; прежние настройки сохранены") }
    }
    val scuProfiles: StateFlow<List<MwlScuProfile>> = profileRepository.profilesFlow
    val logs: StateFlow<List<DicomLogEntry>> = logger.logsFlow

    private val _selectedTab = MutableStateFlow(MwlSubTab.SCP_SERVER)
    val selectedTab: StateFlow<MwlSubTab> = _selectedTab.asStateFlow()

    // SCP input state
    val scpAeInput = MutableStateFlow(scpState.value.aeTitle)
    val scpPortInput = MutableStateFlow(scpState.value.tcpPort.toString())
    val scpWhitelistMode = MutableStateFlow(scpState.value.whitelistMode)
    val scpAllowedAetsInput = MutableStateFlow(scpState.value.allowedCallingAets.joinToString(", "))
    val scpAllowedIpsInput = MutableStateFlow(scpState.value.allowedIps.joinToString(", "))
    val scpCharset = MutableStateFlow(scpState.value.characterSet)

    // Appointments search
    val appointmentSearchQuery = MutableStateFlow("")
    val appointmentModalityFilter = MutableStateFlow("ALL")

    // SCU state
    private val _selectedProfile = MutableStateFlow<MwlScuProfile?>(null)
    val selectedProfile: StateFlow<MwlScuProfile?> = _selectedProfile.asStateFlow()

    val scuFilter = MutableStateFlow(MwlScuFilter())
    private val _scuResults = MutableStateFlow<List<MwlQueryResultItem>>(emptyList())
    val scuResults: StateFlow<List<MwlQueryResultItem>> = _scuResults.asStateFlow()

    private val _isQueryRunning = MutableStateFlow(false)
    val isQueryRunning: StateFlow<Boolean> = _isQueryRunning.asStateFlow()

    private val _queryCount = MutableStateFlow(0)
    val queryCount: StateFlow<Int> = _queryCount.asStateFlow()

    private val _queryStatusMessage = MutableStateFlow<String?>(null)
    val queryStatusMessage: StateFlow<String?> = _queryStatusMessage.asStateFlow()

    // Dialog states
    val editingPatient = MutableStateFlow<MwlPatient?>(null)
    val showPatientDialog = MutableStateFlow(false)

    val editingStudy = MutableStateFlow<MwlScheduledStudy?>(null)
    val showStudyDialog = MutableStateFlow(false)

    val showSyntheticDialog = MutableStateFlow(false)
    val studyToDelete = MutableStateFlow<MwlScheduledStudy?>(null)
    val patientToDelete = MutableStateFlow<MwlPatient?>(null)
    val selectedResultDetail = MutableStateFlow<MwlQueryResultItem?>(null)

    val editingProfile = MutableStateFlow<MwlScuProfile?>(null)
    val showProfileDialog = MutableStateFlow(false)
    val profileSaveError = MutableStateFlow<String?>(null)
    val profileToDelete = MutableStateFlow<MwlScuProfile?>(null)

    val showExportReportDialog = MutableStateFlow(false)
    val showDataExportDialog = MutableStateFlow(false)
    val showDataImportDialog = MutableStateFlow(false)

    init {
        _selectedProfile.value = profileRepository.getDefaultProfile()
        viewModelScope.launch {
            scpState.collect { s ->
                if (s.status == ScpServerStatus.STOPPED) {
                    scpAeInput.value = s.aeTitle
                    scpPortInput.value = s.tcpPort.toString()
                    scpWhitelistMode.value = s.whitelistMode
                    scpAllowedAetsInput.value = s.allowedCallingAets.joinToString(", ")
                    scpAllowedIpsInput.value = s.allowedIps.joinToString(", ")
                    scpCharset.value = s.characterSet
                }
            }
        }
    }

    fun selectTab(tab: MwlSubTab) {
        _selectedTab.value = tab
    }

    // --- SCP Methods ---

    fun refreshNetworkInfo() {
        scpServer.refreshNetworkInfo()
    }

    fun toggleScpServer() {
        val currentStatus = scpState.value.status
        if (currentStatus == ScpServerStatus.RUNNING) {
            MwlScpForegroundService.stopMwlScpService(getApplication())
        } else if (currentStatus == ScpServerStatus.STOPPED || currentStatus == ScpServerStatus.ERROR) {
            val ae = scpAeInput.value.trim().ifEmpty { "MWL_SCP" }
            val port = scpPortInput.value.toIntOrNull() ?: 11112
            val charset = scpCharset.value
            val whitelist = scpWhitelistMode.value
            val aets = scpAllowedAetsInput.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val ips = scpAllowedIpsInput.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

            MwlRuntimeRegistry.saveMwlScpConfig(
                getApplication(),
                ae = ae,
                port = port,
                charset = charset,
                whitelistMode = whitelist,
                allowedAets = aets,
                allowedIps = ips
            )
            MwlScpForegroundService.startMwlScpService(getApplication(), ae, port)
        }
    }

    // --- Appointments & Patients Methods ---

    fun openNewPatientDialog() {
        editingPatient.value = MwlPatient(
            patientName = "",
            patientId = "PAT-%04d".format((1..9999).random()),
            patientBirthDate = "",
            patientSex = "M",
            isSynthetic = false
        )
        showPatientDialog.value = true
    }

    fun openEditPatientDialog(patient: MwlPatient) {
        editingPatient.value = patient
        showPatientDialog.value = true
    }

    fun savePatient(patient: MwlPatient): MwlValidation.Result {
        val nameCheck = MwlValidation.validatePatientName(patient.patientName)
        if (nameCheck is MwlValidation.Result.Invalid) return nameCheck

        val idCheck = MwlValidation.validatePatientId(patient.patientId)
        if (idCheck is MwlValidation.Result.Invalid) return idCheck

        val dateCheck = MwlValidation.validateDate(patient.patientBirthDate, "patientBirthDate", required = false)
        if (dateCheck is MwlValidation.Result.Invalid) return dateCheck

        val completeCheck = MwlValidation.validatePatient(patient)
        if (completeCheck is MwlValidation.Result.Invalid) return completeCheck
        try { repository.savePatient(patient) } catch (_: Exception) { return MwlValidation.Result.Invalid("storage", "Не удалось сохранить пациента; предыдущие данные сохранены") }
        if (showStudyDialog.value) editingStudy.value = editingStudy.value?.copy(
            patientInternalId = patient.id, patientName = patient.patientName, patientId = patient.patientId,
            isSynthetic = patient.isSynthetic
        )
        showPatientDialog.value = false
        editingPatient.value = null
        return MwlValidation.Result.Valid
    }

    fun confirmDeletePatient(patient: MwlPatient) {
        patientToDelete.value = patient
    }

    fun executeDeletePatient(cascade: Boolean = true) {
        val p = patientToDelete.value ?: return
        repository.deletePatient(p.id, cascadeStudies = cascade)
        patientToDelete.value = null
    }

    fun openNewStudyDialog(patient: MwlPatient? = null) {
        val today = MwlValidation.currentDateFormatted()
        val now = MwlValidation.currentTimeFormatted()
        val randomNum = (1000..9999).random()

        editingStudy.value = MwlScheduledStudy(
            patientInternalId = patient?.id.orEmpty(),
            patientName = patient?.patientName.orEmpty(),
            patientId = patient?.patientId.orEmpty(),
            accessionNumber = "ACC-$randomNum",
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureId = "RP-$randomNum",
            requestedProcedureDescription = "Рентгенография органов грудной клетки",
            spsId = "SPS-$randomNum",
            modality = "CR",
            spsStartDate = today,
            spsStartTime = now,
            scheduledStationAeTitle = "CR_STATION_01",
            scheduledStationName = "Кабинет 101",
            spsDescription = "Chest X-Ray PA",
            isSynthetic = patient?.isSynthetic ?: false
        )
        showStudyDialog.value = true
    }

    fun openEditStudyDialog(study: MwlScheduledStudy) {
        editingStudy.value = study
        showStudyDialog.value = true
    }

    fun saveStudy(study: MwlScheduledStudy): MwlValidation.Result {
        if (repository.getPatientById(study.patientInternalId) == null) {
            return MwlValidation.Result.Invalid("patientInternalId", "Выберите пациента из локальной базы")
        }
        val uidCheck = MwlValidation.validateStudyInstanceUid(study.studyInstanceUid)
        if (uidCheck is MwlValidation.Result.Invalid) return uidCheck

        val accCheck = MwlValidation.validateAccessionNumber(study.accessionNumber)
        if (accCheck is MwlValidation.Result.Invalid) return accCheck

        val modCheck = MwlValidation.validateModality(study.modality)
        if (modCheck is MwlValidation.Result.Invalid) return modCheck

        val aeCheck = MwlValidation.validateAeTitle(study.scheduledStationAeTitle, "scheduledStationAeTitle")
        if (aeCheck is MwlValidation.Result.Invalid) return aeCheck

        val dateCheck = MwlValidation.validateDate(study.spsStartDate, "spsStartDate", required = true)
        if (dateCheck is MwlValidation.Result.Invalid) return dateCheck

        val timeCheck = MwlValidation.validateTime(study.spsStartTime, "spsStartTime", required = false)
        if (timeCheck is MwlValidation.Result.Invalid) return timeCheck

        val completeCheck = MwlValidation.validateStudy(study)
        if (completeCheck is MwlValidation.Result.Invalid) return completeCheck
        try { repository.saveStudy(study) } catch (_: Exception) { return MwlValidation.Result.Invalid("storage", "Не удалось сохранить назначение; предыдущие данные сохранены") }
        showStudyDialog.value = false
        editingStudy.value = null
        return MwlValidation.Result.Valid
    }

    fun duplicateStudy(study: MwlScheduledStudy) {
        repository.duplicateStudy(study.id)
    }

    fun confirmDeleteStudy(study: MwlScheduledStudy) {
        studyToDelete.value = study
    }

    fun executeDeleteStudy() {
        val s = studyToDelete.value ?: return
        repository.deleteStudy(s.id)
        studyToDelete.value = null
    }

    fun generateSynthetic(
        template: MwlModalityTemplate,
        mode: Int, // 0: single, 1: multiple patients, 2: multiple studies
        count: Int = 5,
        stationAe: String? = null
    ) {
        when (mode) {
            0 -> {
                val pair = SyntheticMwlGenerator.createSingleSyntheticPatient(
                    template = template,
                    index = (patients.value.size + 1),
                    stationAeOverride = stationAe
                )
                repository.addSyntheticBatch(listOf(pair))
            }
            1 -> {
                val list = SyntheticMwlGenerator.createMultipleSyntheticPatients(
                    count = count,
                    startIndex = (patients.value.size + 1),
                    stationAeOverride = stationAe
                )
                repository.addSyntheticBatch(list)
            }
            2 -> {
                val pair = SyntheticMwlGenerator.createPatientWithMultipleStudies(
                    patientIndex = (patients.value.size + 1),
                    stationAeOverride = stationAe
                )
                val batch = pair.second.map { Pair(pair.first, it) }
                repository.addSyntheticBatch(batch)
            }
        }
        showSyntheticDialog.value = false
    }

    // --- SCU Methods ---

    fun selectProfile(profile: MwlScuProfile) {
        _selectedProfile.value = profile
    }

    fun openNewProfileDialog() {
        profileSaveError.value = null
        editingProfile.value = MwlScuProfile(
            name = "Новый MWL сервер",
            host = "192.168.1.100",
            port = 11112,
            calledAet = "PACS_MWL",
            callingAet = "DICOMKIT_SCU"
        )
        showProfileDialog.value = true
    }

    fun openEditProfileDialog(profile: MwlScuProfile) {
        profileSaveError.value = null
        editingProfile.value = profile
        showProfileDialog.value = true
    }

    fun saveProfile(profile: MwlScuProfile): MwlValidation.Result {
        profileSaveError.value = null
        if (profile.name.isBlank()) return MwlValidation.Result.Invalid("name", "Название профиля не может быть пустым")
        if (profile.host.isBlank()) return MwlValidation.Result.Invalid("host", "Хост/IP адрес не может быть пустым")
        val portCheck = DicomValidationUtils.validateScpPort(profile.port)
        if (portCheck is DicomValidationUtils.ValidationResult.Invalid) return MwlValidation.Result.Invalid("port", portCheck.messageRu)
        val calledCheck = MwlValidation.validateAeTitle(profile.calledAet, "calledAet")
        if (calledCheck is MwlValidation.Result.Invalid) return calledCheck
        val callingCheck = MwlValidation.validateAeTitle(profile.callingAet, "callingAet")
        if (callingCheck is MwlValidation.Result.Invalid) return callingCheck

        val saved = try { profileRepository.saveProfile(profile) } catch (_: IOException) {
            val message = "Не удалось сохранить профиль MWL SCU. Прежние настройки сохранены; проверьте доступное место и повторите попытку."
            profileSaveError.value = message
            return MwlValidation.Result.Invalid("storage", message)
        }
        _selectedProfile.value = saved
        showProfileDialog.value = false
        editingProfile.value = null
        return MwlValidation.Result.Valid
    }

    fun deleteProfile(profile: MwlScuProfile) {
        profileRepository.deleteProfile(profile.id)
        _selectedProfile.value = profileRepository.getDefaultProfile()
        profileToDelete.value = null
    }

    fun queryWorklist() {
        if (_isQueryRunning.value) return
        val queryFilter = scuFilter.value
        val dateValidation = queryFilter.validateDateSelection()
        if (dateValidation is MwlValidation.Result.Invalid) {
            _queryStatusMessage.value = dateValidation.messageRu
            return
        }
        val prof = _selectedProfile.value ?: run {
            _queryStatusMessage.value = "Выберите профиль сервера"
            return
        }

        _isQueryRunning.value = true
        viewModelScope.launch {
            _queryCount.value = 0
            _queryStatusMessage.value = "Подключение к ${prof.host}:${prof.port}…"
            _scuResults.value = emptyList()

            val result = try {
                withContext(Dispatchers.IO) {
                    scuClient.queryWorklist(prof, queryFilter) { count -> _queryCount.value = count }
                }
            } finally { _isQueryRunning.value = false }
            val outcome = result.getOrNull()
            if (outcome != null) {
                _scuResults.value = outcome.items
                _queryStatusMessage.value = when {
                    outcome.cancelConfirmed -> "Поиск отменён сервером (FE00). Частичных записей: ${outcome.size}"
                    !outcome.completedSuccessfully -> "Поиск прерван локально; отмена сервером не подтверждена. Частичных записей: ${outcome.size}"
                    outcome.isEmpty() -> "Поиск завершён: совпадений не найдено (0 записей)"
                    else -> "Успешно получено записей: ${outcome.size}"
                }
            } else {
                val ex = result.exceptionOrNull()
                val partial = (ex as? ru.medeng.dicomtool.mwl.model.MwlQueryException)?.partialResults.orEmpty()
                _scuResults.value = partial
                _queryStatusMessage.value = "Ошибка запроса: ${ex?.message}. Частичных записей: ${partial.size}"
            }
        }
    }

    fun cancelWorklistQuery() {
        scuClient.cancelQuery()
        _queryStatusMessage.value = "Отмена поиска запрошена; ожидается подтверждение…"
    }

    fun openResultDetail(item: MwlQueryResultItem) {
        selectedResultDetail.value = item
    }

    override fun onCleared() { scuClient.cancelQuery(); super.onCleared() }

    // --- Journal & Diagnostics ---

    fun clearLogs() {
        logger.clearLogs()
    }

    fun exportDiagnosticReport(includePhi: Boolean = false): String {
        return logger.exportDiagnosticReport(includePhi)
    }

    fun exportSyntheticData(): String {
        return repository.exportSyntheticJson()
    }

    fun importSyntheticData(json: String): Int {
        return repository.importSyntheticJson(json)
    }
}
