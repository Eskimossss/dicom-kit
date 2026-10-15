package ru.medeng.dicomtool.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.medeng.dicomtool.data.logging.DicomDiagnosticHandbook
import ru.medeng.dicomtool.data.logging.DicomLogDisplayMode
import ru.medeng.dicomtool.data.logging.DicomLogExportUtils
import ru.medeng.dicomtool.data.logging.DicomOutcomeFilter
import ru.medeng.dicomtool.data.logging.DicomSessionGrouper
import ru.medeng.dicomtool.data.logging.DicomSessionSummary
import ru.medeng.dicomtool.data.logging.DicomTrafficFilter
import ru.medeng.dicomtool.data.logging.matches
import ru.medeng.dicomtool.dicom.Dcm4cheCompatibilityReport
import ru.medeng.dicomtool.dicom.Dcm4cheEngineVerifier
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.dicom.pixel.DicomImageDescriptor
import ru.medeng.dicomtool.dicom.pixel.DicomPixelDataDecoder
import ru.medeng.dicomtool.dicom.pixel.DicomPixelDecodeResult
import ru.medeng.dicomtool.dicom.pixel.DicomWindowPreset
import ru.medeng.dicomtool.dicom.pixel.SingleFrameDecodeCache
import ru.medeng.dicomtool.dicom.scp.DicomScpForegroundService
import ru.medeng.dicomtool.dicom.scu.DicomScuClient
import ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator
import ru.medeng.dicomtool.dicom.scu.PreparedSecondaryCaptureDicomObject
import ru.medeng.dicomtool.dicom.scu.ScuOperationResult
import ru.medeng.dicomtool.dicom.scu.ScuTestImageRenderer
import ru.medeng.dicomtool.domain.model.DicomInstanceFileItem
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomStudyItem
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import ru.medeng.dicomtool.domain.model.FreehandStroke
import ru.medeng.dicomtool.domain.model.NormalizedPoint
import ru.medeng.dicomtool.domain.model.ScpReceiverState
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import ru.medeng.dicomtool.domain.model.TestImageMode
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import ru.medeng.dicomtool.dicom.tags.DicomFileReader
import android.widget.Toast
import ru.medeng.dicomtool.data.storage.DicomStorageManager
import ru.medeng.dicomtool.data.storage.StorageLocationInfo
import ru.medeng.dicomtool.data.storage.StorageType
import ru.medeng.dicomtool.dicom.date.DicomDateUtils
import ru.medeng.dicomtool.dicom.date.DateValidationResult
import ru.medeng.dicomtool.dicom.scu.DicomFileScanner
import ru.medeng.dicomtool.domain.model.DicomEncodingOverride
import ru.medeng.dicomtool.domain.model.DicomFileSummary
import ru.medeng.dicomtool.domain.model.FileSendStatus
import ru.medeng.dicomtool.domain.model.ScuBatchProgressState
import ru.medeng.dicomtool.domain.model.ScuCandidateFile
import ru.medeng.dicomtool.domain.model.ScuSendMode
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt


/**
 * Состояние декодирования и рендеринга реального DICOM-снимка в DICOM Viewer.
 */
sealed class DicomViewerRenderState {
    data object Idle : DicomViewerRenderState()
    data object Loading : DicomViewerRenderState()

    data class Ready(
        val bitmap: Bitmap,
        val descriptor: DicomImageDescriptor,
        val currentFrame: Int,
        val totalFrames: Int,
        val currentInstance: Int,
        val totalInstances: Int,
        val filePath: String
    ) : DicomViewerRenderState()

    data class UnsupportedFormat(
        val transferSyntaxUid: String,
        val transferSyntaxName: String,
        val photometricInterpretation: String,
        val bitsAllocated: Int,
        val reasonRu: String
    ) : DicomViewerRenderState()

    data class Error(
        val reasonRu: String
    ) : DicomViewerRenderState()
}

/**
 * Основная ViewModel приложения DICOM Tool (Этап 2.1: реальный DICOM Storage SCP, настоящий декодер Pixel Data и кодировки).
 * Наследует AndroidViewModel, сохраняет состояние открытого исследования, серии, снимка, кадра, WW/WL и масштаба при поворотах экрана.
 */
class DicomToolViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val scuLeaseOwner = java.util.UUID.randomUUID().toString()
    private val engineVerifier = Dcm4cheEngineVerifier()
    private val studyRepository = DicomRuntimeRegistry.getStudyRepository(appContext)
    private val technicalLogger = DicomRuntimeRegistry.getTechnicalLogger(appContext)
    private val scpServer = DicomRuntimeRegistry.getScpServer(appContext)
    private val scuClient: DicomScuClient = DicomRuntimeRegistry.getScuClient(appContext)

    // Одноэлементный кэш декодированного кадра (исключает утечки памяти и повторное чтение 18 МБ файла при регулировке WW/WL)
    private val frameCache = SingleFrameDecodeCache()
    private var renderJob: Job? = null
    private var lastLoadedFilePath: String? = null
    private var lastLoadedFrameIndex: Int? = null
    private var preferredWindow: Pair<Double, Double>? = null // Preserve fractional DICOM defaults/presets through invert and redraw.
    private val renderRequests = ru.medeng.dicomtool.domain.model.LatestViewerRequest()
    private val metadataRequests = ru.medeng.dicomtool.domain.model.LatestViewerRequest()
    private val viewerWorkMutex = kotlinx.coroutines.sync.Mutex()
    private var externalLoadJob: Job? = null
    private var receivedTagJob: Job? = null
    private val receivedTagRequests = ru.medeng.dicomtool.domain.model.LatestViewerRequest()
    private var receivedTagPath: String? = null
    private val _receivedTags = MutableStateFlow<List<DicomTagEntry>>(emptyList())
    val receivedTags = _receivedTags.asStateFlow()
    private val _viewerSource = MutableStateFlow<ru.medeng.dicomtool.domain.model.ViewerSource>(ru.medeng.dicomtool.domain.model.ViewerSource.None)
    val viewerSource = _viewerSource.asStateFlow()

    private fun changeViewerSource(source: ru.medeng.dicomtool.domain.model.ViewerSource) {
        if (_viewerSource.value == source) return
        renderRequests.next(); renderJob?.cancel(); frameCache.clear()
        receivedTagRequests.next(); receivedTagJob?.cancel(); receivedTagPath = null; _receivedTags.value = emptyList()
        _selectedTagForDetail.value = null
        _viewerSource.value = source
        _viewerRenderState.value = DicomViewerRenderState.Idle
        _viewerCurrentFrame.value = 1; lastLoadedFilePath = null; lastLoadedFrameIndex = null
        preferredWindow = null
        _viewerZoom.value = 1f; _viewerPan.value = Offset.Zero; _viewerInverted.value = false
        _viewerActiveVoiLutIndex.value = null
    }
    fun ensureReceivedViewer() {
        val study = getSelectedStudyOrDefault() ?: return
        changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.Received(study.studyInstanceUid))
        triggerFrameDecodeAndRender()
    }
    fun ensureExternalViewer() {
        changeViewerSource(_selectedExternalFileUri.value?.let { ru.medeng.dicomtool.domain.model.ViewerSource.External(it.toString()) }
            ?: ru.medeng.dicomtool.domain.model.ViewerSource.None)
        triggerFrameDecodeAndRender()
    }

    // Результат проверки инициализации стека dcm4che3
    private val _dcm4cheReport = MutableStateFlow(engineVerifier.verifyDcm4cheStackCompatibility())
    val dcm4cheReport: StateFlow<Dcm4cheCompatibilityReport> = _dcm4cheReport.asStateFlow()

    // Состояние раздела 1: Приёмник (SCP) от реального DicomStorageScpServer
    val scpState: StateFlow<ScpReceiverState> = scpServer.state

    // Редактируемые поля AE Title и порта в UI (с проверкой допустимости)
    private val _aeTitleInput = MutableStateFlow(scpServer.state.value.callingAeTitle)
    val aeTitleInput: StateFlow<String> = _aeTitleInput.asStateFlow()

    private val _portInput = MutableStateFlow(scpServer.state.value.tcpPort.toString())
    val portInput: StateFlow<String> = _portInput.asStateFlow()

    // Состояние раздела 2: Принятые исследования из реального внутреннего хранилища
    val studies: StateFlow<List<DicomStudyItem>> = studyRepository.studiesFlow

    val storageManager: DicomStorageManager? = (studyRepository as? ru.medeng.dicomtool.data.storage.LocalDicomStudyRepository)?.storageManager

    private val archive = DicomRuntimeRegistry.getArchiveOperations(appContext)
    val archiveIndexState = studyRepository.indexState
    val storageLocationInfo = archive.info
    val isMigrating = archive.busy
    val migrationStatusMessage = archive.message
    val receptionStorageOptions = requireNotNull(storageManager).receptionOptions
    fun updateReceptionStorageOptions(options: ru.medeng.dicomtool.data.storage.ReceptionStorageOptions) = archive.setReceptionOptions(options)
    fun recoverRetainedIncoming() = archive.recoverIncoming()

    private val _selectedModalityFilter = MutableStateFlow("ALL")
    val selectedModalityFilter: StateFlow<String> = _selectedModalityFilter.asStateFlow()

    private val _selectedStudyUid = MutableStateFlow<String?>(null)
    val selectedStudyUid: StateFlow<String?> = _selectedStudyUid.asStateFlow()

    private val _selectedSeriesUid = MutableStateFlow<String?>(null)
    val selectedSeriesUid: StateFlow<String?> = _selectedSeriesUid.asStateFlow()

    // Индекс файла (Instance) внутри выбранной серии (1-based)
    private val _selectedInstanceIndex = MutableStateFlow(1)
    val selectedInstanceIndex: StateFlow<Int> = _selectedInstanceIndex.asStateFlow()

    // Индекс кадра внутри многокадрового DICOM-файла (1-based)
    private val _viewerCurrentFrame = MutableStateFlow(1)
    val viewerCurrentFrame: StateFlow<Int> = _viewerCurrentFrame.asStateFlow()

    private val _selectedTagForDetail = MutableStateFlow<DicomTagEntry?>(null)
    val selectedTagForDetail: StateFlow<DicomTagEntry?> = _selectedTagForDetail.asStateFlow()

    private val _tagSearchQuery = MutableStateFlow("")
    val tagSearchQuery: StateFlow<String> = _tagSearchQuery.asStateFlow()

    // Параметры DICOM Viewer (сохраняются при поворотах экрана)
    private val _viewerZoom = MutableStateFlow(1.0f)
    val viewerZoom: StateFlow<Float> = _viewerZoom.asStateFlow()

    private val _viewerPan = MutableStateFlow(Offset.Zero)
    val viewerPan: StateFlow<Offset> = _viewerPan.asStateFlow()

    private val _viewerInverted = MutableStateFlow(false)
    val viewerInverted: StateFlow<Boolean> = _viewerInverted.asStateFlow()

    private val _viewerWindowWidth = MutableStateFlow(16221)
    val viewerWindowWidth: StateFlow<Int> = _viewerWindowWidth.asStateFlow()

    private val _viewerWindowCenter = MutableStateFlow(8272)
    val viewerWindowCenter: StateFlow<Int> = _viewerWindowCenter.asStateFlow()

    private val _viewerActiveVoiLutIndex = MutableStateFlow<Int?>(null)
    val viewerActiveVoiLutIndex: StateFlow<Int?> = _viewerActiveVoiLutIndex.asStateFlow()

    private val _viewerRenderState = MutableStateFlow<DicomViewerRenderState>(DicomViewerRenderState.Idle)
    val viewerRenderState: StateFlow<DicomViewerRenderState> = _viewerRenderState.asStateFlow()

    // Полноэкранный режим DICOM Viewer
    private val _isViewerFullScreen = MutableStateFlow(false)
    val isViewerFullScreen: StateFlow<Boolean> = _isViewerFullScreen.asStateFlow()

    // Сохранение и восстановление пользовательских параметров PACS (Этап: Очистка параметров по умолчанию)
    private val scuPrefs = appContext.getSharedPreferences("dicom_scu_settings", Context.MODE_PRIVATE)

    // Состояние раздела 3: Отправка (SCU — Этап 3 и 4.2)
    private val _scuConfig = MutableStateFlow(
        ScuSendConfig(
            remoteIp = scuPrefs.getString("scu_remote_ip", "") ?: "",
            remotePort = scuPrefs.getInt("scu_remote_port", 0),
            calledAeTitle = scuPrefs.getString("scu_called_ae", "") ?: "",
            callingAeTitle = scuPrefs.getString("scu_calling_ae", "") ?: ""
        )
    )
    val scuConfig: StateFlow<ScuSendConfig> = _scuConfig.asStateFlow()

    private val _scuSendMode = MutableStateFlow(ScuSendMode.TEST_IMAGE)
    val scuSendMode: StateFlow<ScuSendMode> = _scuSendMode.asStateFlow()

    private val _candidateFiles = MutableStateFlow<List<ScuCandidateFile>>(emptyList())
    val candidateFiles: StateFlow<List<ScuCandidateFile>> = _candidateFiles.asStateFlow()

    private val _batchProgress = MutableStateFlow(ScuBatchProgressState())
    val batchProgress: StateFlow<ScuBatchProgressState> = _batchProgress.asStateFlow()

    private var scanJob: Job? = null
    private var sendJob: Job? = null
    private var sendCancellation: ru.medeng.dicomtool.dicom.scu.ScuCancellation? = null
    @Volatile private var scanGeneration = 0L

    private val _freehandStrokes = MutableStateFlow<List<FreehandStroke>>(emptyList())
    val freehandStrokes: StateFlow<List<FreehandStroke>> = _freehandStrokes.asStateFlow()


    // Подготовленный DICOM-объект Secondary Capture (1.2.840.10008.5.1.4.1.1.7) с уникальными UID <= 64 символов
    private val _preparedScuObject = MutableStateFlow(
        DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = _scuConfig.value,
            freehandStrokes = emptyList()
        )
    )
    val preparedScuObject: StateFlow<PreparedSecondaryCaptureDicomObject> = _preparedScuObject.asStateFlow()

    // Состояние раздела «Журнал DICOM» (Этап 4.3.2: Краткий и Подробный режимы, фильтрация, поиск)
    val logs: StateFlow<List<DicomLogEntry>> = technicalLogger.logsFlow
    val loggerHealth = technicalLogger.healthFlow

    private val _logDisplayMode = MutableStateFlow(DicomLogDisplayMode.BRIEF)
    val logDisplayMode: StateFlow<DicomLogDisplayMode> = _logDisplayMode.asStateFlow()

    private val _logTrafficFilter = MutableStateFlow(DicomTrafficFilter.ALL)
    val logTrafficFilter: StateFlow<DicomTrafficFilter> = _logTrafficFilter.asStateFlow()

    private val _logOutcomeFilter = MutableStateFlow(DicomOutcomeFilter.ALL)
    val logOutcomeFilter: StateFlow<DicomOutcomeFilter> = _logOutcomeFilter.asStateFlow()

    private val _logSearchQuery = MutableStateFlow("")
    val logSearchQuery: StateFlow<String> = _logSearchQuery.asStateFlow()

    private val _autoScrollLogs = MutableStateFlow(true)
    val autoScrollLogs: StateFlow<Boolean> = _autoScrollLogs.asStateFlow()

    private val _expandedLogIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedLogIds: StateFlow<Set<String>> = _expandedLogIds.asStateFlow()

    private val _expandedSessionKeys = MutableStateFlow<Set<String>>(emptySet())
    val expandedSessionKeys: StateFlow<Set<String>> = _expandedSessionKeys.asStateFlow()

    // Сгруппированные сеансы (вычисляются реактивно при обновлении списка логов)
    val allSessionSummaries: StateFlow<List<DicomSessionSummary>> = logs
        .combine(_logSearchQuery) { rawLogs, _ ->
            DicomSessionGrouper.groupEvents(rawLogs)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Отфильтрованные сеансы для Краткого режима
    val filteredSessionSummaries: StateFlow<List<DicomSessionSummary>> = combine(
        allSessionSummaries,
        _logTrafficFilter,
        _logOutcomeFilter,
        _logSearchQuery
    ) { summaries, traffic, outcome, query ->
        summaries.filter { it.matches(traffic, outcome, query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Отфильтрованные записи для Подробного режима
    val filteredLogs: StateFlow<List<DicomLogEntry>> = combine(
        logs,
        _logTrafficFilter,
        _logOutcomeFilter,
        _logSearchQuery
    ) { rawLogs, traffic, outcome, query ->
        rawLogs.filter { it.matches(traffic, outcome, query) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Состояние раздела «DICOM-теги» (Этап 4.1: просмотр метаданных любого файла через SAF)
    private val _selectedExternalFileUri = MutableStateFlow<Uri?>(null)
    val selectedExternalFileUri: StateFlow<Uri?> = _selectedExternalFileUri.asStateFlow()

    private val _externalFileSummary = MutableStateFlow<DicomFileSummary?>(null)
    val externalFileSummary: StateFlow<DicomFileSummary?> = _externalFileSummary.asStateFlow()

    private val _externalFileTags = MutableStateFlow<List<DicomTagEntry>>(emptyList())
    val externalFileTags: StateFlow<List<DicomTagEntry>> = _externalFileTags.asStateFlow()

    private val _isExternalFileLoading = MutableStateFlow(false)
    val isExternalFileLoading: StateFlow<Boolean> = _isExternalFileLoading.asStateFlow()

    private val _externalFileError = MutableStateFlow<String?>(null)
    val externalFileError: StateFlow<String?> = _externalFileError.asStateFlow()

    private val _externalTagEncodingOverride = MutableStateFlow(DicomEncodingOverride.AUTO)
    val externalTagEncodingOverride: StateFlow<DicomEncodingOverride> = _externalTagEncodingOverride.asStateFlow()

    private val _externalTagSearchQuery = MutableStateFlow("")
    val externalTagSearchQuery: StateFlow<String> = _externalTagSearchQuery.asStateFlow()

    // --- Действия раздела «Приёмник (SCP)» ---

    private fun persistScpConfiguration() {
        val ae = _aeTitleInput.value.trim()
        val port = _portInput.value.toIntOrNull() ?: return
        if (DicomValidationUtils.validateAeTitle(ae) is DicomValidationUtils.ValidationResult.Valid &&
            DicomValidationUtils.validateScpPort(port) is DicomValidationUtils.ValidationResult.Valid) {
            appContext.getSharedPreferences("scp_configuration", android.content.Context.MODE_PRIVATE)
                .edit().putString("ae", ae).putInt("port", port).apply()
        }
    }

    fun updateScpAeTitleInput(raw: String) {
        _aeTitleInput.value = raw
        val portNum = _portInput.value.toIntOrNull() ?: -1
        scpServer.updateConfiguration(raw, portNum)
        persistScpConfiguration()
    }

    fun updateScpPortInput(raw: String) {
        val digitsOnly = raw.filter { it.isDigit() }.take(5)
        _portInput.value = digitsOnly
        val portNum = digitsOnly.toIntOrNull() ?: -1
        scpServer.updateConfiguration(_aeTitleInput.value, portNum)
        persistScpConfiguration()
    }

    fun incrementScpPort(delta: Int) {
        val current = _portInput.value.toIntOrNull() ?: 11112
        val next = (current + delta).coerceIn(1024, 65535)
        _portInput.value = next.toString()
        scpServer.updateConfiguration(_aeTitleInput.value, next)
        persistScpConfiguration()
    }

    fun refreshNetworkInterface() {
        scpServer.refreshNetworkInfo()
    }

    fun toggleScpServer() {
        if (scpState.value.serviceStopping || scpState.value.status in listOf(ScpServerStatus.STARTING, ScpServerStatus.STOPPING)) return
        if (scpState.value.status == ScpServerStatus.RUNNING) {
            DicomScpForegroundService.stopScpService(appContext)
        } else {
            val ae = _aeTitleInput.value.trim()
            val port = _portInput.value.toIntOrNull() ?: -1
            val aeValid = DicomValidationUtils.validateAeTitle(ae)
            val portValid = DicomValidationUtils.validateScpPort(port)
            if (aeValid is DicomValidationUtils.ValidationResult.Invalid ||
                portValid is DicomValidationUtils.ValidationResult.Invalid
            ) {
                scpServer.updateConfiguration(ae, port)
                return
            }
            appContext.getSharedPreferences("scp_configuration", android.content.Context.MODE_PRIVATE)
                .edit().putString("ae", ae).putInt("port", port).apply()
            DicomScpForegroundService.startScpService(appContext, ae, port)
        }
    }

    // --- Действия раздела «Принятые исследования», DICOM Viewer и экрана тегов ---

    fun refreshReceivedStudies() {
        archive.refresh()
    }

    fun setModalityFilter(modality: String) {
        _selectedModalityFilter.value = modality
    }

    fun selectStudy(studyInstanceUid: String) {
        changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.Received(studyInstanceUid))
        val changed = _selectedStudyUid.value != studyInstanceUid
        _selectedStudyUid.value = studyInstanceUid
        val study = studyRepository.studiesFlow.value.firstOrNull { it.studyInstanceUid == studyInstanceUid }
        if (study != null && (changed || _viewerRenderState.value is DicomViewerRenderState.Idle)) {
            _selectedSeriesUid.value = study.seriesList.firstOrNull()?.seriesInstanceUid
            _selectedInstanceIndex.value = 1
            _viewerCurrentFrame.value = 1
            _viewerZoom.value = 1.0f
            _viewerPan.value = Offset.Zero
            _viewerInverted.value = false
            _viewerActiveVoiLutIndex.value = null
            _viewerWindowWidth.value = study.windowWidth.coerceAtLeast(1)
            _viewerWindowCenter.value = study.windowCenter
            lastLoadedFilePath = null
            triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
        } else if (study != null) {
            triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
        }
    }

    fun selectSeries(seriesInstanceUid: String) {
        if (_selectedSeriesUid.value != seriesInstanceUid) {
            _selectedSeriesUid.value = seriesInstanceUid
            _selectedInstanceIndex.value = 1
            _viewerCurrentFrame.value = 1
            _viewerZoom.value = 1.0f
            _viewerPan.value = Offset.Zero
            lastLoadedFilePath = null
            triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
        }
    }

    fun selectTagForDetail(tag: DicomTagEntry?) {
        _selectedTagForDetail.value = tag
    }

    fun getSelectedStudyOrDefault(): DicomStudyItem? {
        val currentList = studyRepository.studiesFlow.value
        if (currentList.isEmpty()) return null
        val uid = _selectedStudyUid.value
        return currentList.firstOrNull { it.studyInstanceUid == uid } ?: currentList.first()
    }

    fun getActiveInstanceFileItem(): DicomInstanceFileItem? {
        val study = getSelectedStudyOrDefault() ?: return null
        val series = study.seriesList.firstOrNull { it.seriesInstanceUid == _selectedSeriesUid.value }
            ?: study.seriesList.firstOrNull()
            ?: return null
        val idx0 = (_selectedInstanceIndex.value - 1).coerceIn(0, (series.instances.size - 1).coerceAtLeast(0))
        return series.instances.getOrNull(idx0)
    }

    fun getFilteredTagsForCurrentStudy(): List<DicomTagEntry> {
        return _receivedTags.value // The common browser performs recursive filtering, including nested SQ.
    }
    private fun loadReceivedTags(filePath: String) {
        if (receivedTagPath == filePath) return
        receivedTagPath = filePath; _receivedTags.value = emptyList()
        val ticket = receivedTagRequests.next(); receivedTagJob?.cancel()
        receivedTagJob = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]!!
                val check = { job.ensureActive(); receivedTagRequests.check(ticket) }
                val parsed = withContext(Dispatchers.IO) { viewerWorkMutex.withLock {
                    check()
                    val stream = if (filePath.startsWith("content://")) appContext.contentResolver.openInputStream(Uri.parse(filePath)) else File(filePath).inputStream()
                    requireNotNull(stream).use { DicomFileReader.readFromStream(it, checkCancelled = check).tags }
                } }
                check(); _receivedTags.value = parsed
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (oom: OutOfMemoryError) {
                if (receivedTagRequests.isCurrent(ticket)) _receivedTags.value = listOf(DicomTagEntry("(0000,0000)", "LT", "ReadError", "Недостаточно памяти для тегов; исходный файл не изменён"))
            } catch (e: Exception) {
                if (receivedTagRequests.isCurrent(ticket)) _receivedTags.value = listOf(DicomTagEntry("(0000,0000)", "LT", "ReadError", "Ошибка чтения тегов: ${e.message}"))
            }
        }
    }

    fun updateTagSearchQuery(query: String) {
        _tagSearchQuery.value = query
    }

    fun adjustViewerZoom(factor: Float) {
        _viewerZoom.update { (it * factor).coerceIn(0.25f, 12.0f) }
    }

    fun updateViewerPan(delta: Offset) {
        _viewerPan.update { it + delta }
    }

    /**
     * Fit to Screen (или двойное нажатие): возвращает масштаб к 100% и центрирует снимок без сброса WW/WL.
     */
    fun fitViewerToScreen() {
        _viewerZoom.value = 1.0f
        _viewerPan.value = Offset.Zero
    }

    /**
     * Reset WW/WL к исходным значениям DICOM-файла и сброс трансформации.
     */
    fun resetViewerTransform() {
        _viewerZoom.value = 1.0f
        _viewerPan.value = Offset.Zero
        _viewerInverted.value = false
        _viewerActiveVoiLutIndex.value = null
        val ready = _viewerRenderState.value as? DicomViewerRenderState.Ready
        if (ready != null) {
            _viewerWindowWidth.value = ready.descriptor.defaultWindowWidth.roundToInt().coerceAtLeast(1)
            _viewerWindowCenter.value = ready.descriptor.defaultWindowCenter.roundToInt()
            triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
        } else {
            val inst = getActiveInstanceFileItem()
            val study = getSelectedStudyOrDefault()
            _viewerWindowWidth.value = (inst?.windowWidth ?: study?.windowWidth ?: 16221).coerceAtLeast(1)
            _viewerWindowCenter.value = inst?.windowCenter ?: study?.windowCenter ?: 8272
            triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
        }
    }

    /**
     * Сброс только параметров окна яркости/контрастности (WW/WL) к исходным значениям DICOM без сброса зума.
     */
    fun resetWindowToDicomDefault() {
        _viewerInverted.value = false
        _viewerActiveVoiLutIndex.value = null
        val ready = _viewerRenderState.value as? DicomViewerRenderState.Ready
        if (ready != null) {
            _viewerWindowWidth.value = ready.descriptor.defaultWindowWidth.roundToInt().coerceAtLeast(1)
            _viewerWindowCenter.value = ready.descriptor.defaultWindowCenter.roundToInt()
        }
        triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
    }

    fun toggleViewerInvert() {
        _viewerInverted.update { !it }
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    fun applyWindowPreset(preset: DicomWindowPreset) {
        if ((_viewerRenderState.value as? DicomViewerRenderState.Ready)?.descriptor?.photometricInterpretation == "RGB") return
        if (preset.isVoiLutSequence) {
            _viewerActiveVoiLutIndex.value = preset.voiLutIndex
        } else {
            _viewerActiveVoiLutIndex.value = null
            preferredWindow = preset.windowWidth to preset.windowCenter
            _viewerWindowWidth.value = preset.windowWidth.roundToInt().coerceAtLeast(1)
            _viewerWindowCenter.value = preset.windowCenter.roundToInt()
        }
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    fun setWindowWidthLevel(newWindowWidth: Int, newWindowCenter: Int) {
        if ((_viewerRenderState.value as? DicomViewerRenderState.Ready)?.descriptor?.photometricInterpretation == "RGB") return
        _viewerActiveVoiLutIndex.value = null
        preferredWindow = null
        _viewerWindowWidth.value = newWindowWidth.coerceIn(1, 131072)
        _viewerWindowCenter.value = newWindowCenter.coerceIn(-65536, 131072)
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    /**
     * Изменение WW/WL на заданную дельту в единицах Modality Value.
     */
    fun adjustWindowWidthLevel(deltaW: Int, deltaL: Int) {
        if ((_viewerRenderState.value as? DicomViewerRenderState.Ready)?.descriptor?.photometricInterpretation == "RGB") return
        _viewerActiveVoiLutIndex.value = null
        preferredWindow = null
        // Controls choose a proportional step; do not apply a second range-dependent multiplier.
        _viewerWindowWidth.update { (it + deltaW).coerceIn(1, 131072) }
        _viewerWindowCenter.update { (it + deltaL).coerceIn(-65536, 131072) }
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    fun setViewerFullScreen(enabled: Boolean) {
        _isViewerFullScreen.value = enabled
    }

    fun toggleViewerFullScreen() {
        _isViewerFullScreen.update { !it }
    }

    /**
     * Переключение кадров многокадрового изображения (если NumberOfFrames > 1)
     * либо переключение между экземплярами (изображениями) серии.
     */
    fun stepViewerFrame(delta: Int) {
        // Если открыт внешний файл из вкладки «Теги»
        val externalUri = (_viewerSource.value as? ru.medeng.dicomtool.domain.model.ViewerSource.External)?.let { Uri.parse(it.uri) }
        val externalSummary = _externalFileSummary.value
        if (externalUri != null && externalSummary != null) {
            val totalFrames = externalSummary.numberOfFrames.coerceAtLeast(1)
            if (totalFrames > 1) {
                _viewerCurrentFrame.update { current ->
                    val next = current + delta
                    if (next < 1) {
                        totalFrames
                    } else if (next > totalFrames) {
                        1
                    } else {
                        next
                    }
                }
                triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
            }
            return
        }

        val inst = getActiveInstanceFileItem() ?: return
        val totalFrames = inst.numberOfFrames.coerceAtLeast(1)
        if (totalFrames > 1) {
            _viewerCurrentFrame.update { current ->
                val next = current + delta
                when {
                    next < 1 -> totalFrames
                    next > totalFrames -> 1
                    else -> next
                }
            }
            triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
        } else {
            stepSeriesInstance(delta)
        }
    }

    /**
     * Явное переключение между изображениями (Instance) внутри текущей серии.
     */
    fun stepSeriesInstance(delta: Int) {
        val study = getSelectedStudyOrDefault() ?: return
        val series = study.seriesList.firstOrNull { it.seriesInstanceUid == _selectedSeriesUid.value }
            ?: study.seriesList.firstOrNull()
            ?: return
        val totalInstances = series.instances.size.coerceAtLeast(1)
        if (totalInstances <= 1) return

        _selectedInstanceIndex.update { current ->
            val next = current + delta
            when {
                next < 1 -> totalInstances
                next > totalInstances -> 1
                else -> next
            }
        }
        _viewerCurrentFrame.value = 1
        triggerFrameDecodeAndRender(resetWindowToFileDefault = true)
    }

    /**
     * Запускает асинхронное декодирование выбранного кадра и пересчёт Bitmap с текущими WW/WL и инверсией.
     * Не блокирует главный UI-поток.
     * Поддерживает как принятые исследования из хранилища SCP, так и внешние файлы из раздела «Теги».
     */
    fun triggerFrameDecodeAndRender(resetWindowToFileDefault: Boolean = false) {
        val source = _viewerSource.value
        val external = source as? ru.medeng.dicomtool.domain.model.ViewerSource.External
        if (source == ru.medeng.dicomtool.domain.model.ViewerSource.None || (external != null && _externalFileSummary.value == null)) {
            renderRequests.next(); renderJob?.cancel(); _viewerRenderState.value = DicomViewerRenderState.Idle; return
        }
        val instance = if (external == null) getActiveInstanceFileItem() else null
        if (source is ru.medeng.dicomtool.domain.model.ViewerSource.Received && getSelectedStudyOrDefault()?.studyInstanceUid != source.studyInstanceUid) {
            renderRequests.next(); renderJob?.cancel(); _viewerRenderState.value = DicomViewerRenderState.Error("Выбранное исследование больше недоступно"); return
        }
        val filePath = external?.uri ?: instance?.filePath ?: run {
            _viewerRenderState.value = DicomViewerRenderState.Error("Экземпляр изображения не найден"); return
        }
        val frameIndex = _viewerCurrentFrame.value - 1
        val reset = resetWindowToFileDefault || lastLoadedFilePath != filePath || lastLoadedFrameIndex != frameIndex
        val ticket = renderRequests.next()
        val ww = preferredWindow?.first ?: _viewerWindowWidth.value.toDouble()
        val wc = preferredWindow?.second ?: _viewerWindowCenter.value.toDouble()
        val inverted = _viewerInverted.value; val voi = _viewerActiveVoiLutIndex.value
        val instanceIndex = if (external != null) 1 else _selectedInstanceIndex.value
        val totalInstances = if (external != null) 1 else getSelectedStudyOrDefault()?.seriesList
            ?.firstOrNull { it.seriesInstanceUid == _selectedSeriesUid.value }?.instances?.size ?: 1
        renderJob?.cancel()
        if (reset) { frameCache.clear(); _viewerRenderState.value = DicomViewerRenderState.Loading }
        renderJob = viewModelScope.launch {
            var unpublished: Bitmap? = null
            try {
                val job = coroutineContext[Job]!!
                val check = { job.ensureActive(); renderRequests.check(ticket) }
                val result = withContext(Dispatchers.IO) { viewerWorkMutex.withLock {
                    check()
                    val decoded = DicomPixelDataDecoder.decodeFrameFromSource(filePath,
                        streamOpener = { if (external != null || filePath.startsWith("content://")) appContext.contentResolver.openInputStream(Uri.parse(filePath)) else File(filePath).inputStream() },
                        frameIndex = frameIndex, cache = frameCache, checkCancelled = check)
                    if (decoded is DicomPixelDecodeResult.Success) {
                        val d = decoded.frame.descriptor
                        unpublished = DicomPixelDataDecoder.renderFrameToBitmap(decoded.frame,
                            if (reset) d.defaultWindowWidth else ww, if (reset) d.defaultWindowCenter else wc,
                            inverted, if (reset) d.windowPresets.firstOrNull()?.takeIf { it.isVoiLutSequence }?.voiLutIndex else voi, checkCancelled = check)
                    }
                    check(); decoded
                } }
                check()
                when (result) {
                    is DicomPixelDecodeResult.UnsupportedFormat -> _viewerRenderState.value = DicomViewerRenderState.UnsupportedFormat(
                        result.transferSyntaxUid, result.transferSyntaxName, result.photometricInterpretation, result.bitsAllocated, result.reasonRu)
                    is DicomPixelDecodeResult.DecodeError -> _viewerRenderState.value = DicomViewerRenderState.Error(result.reasonRu)
                    is DicomPixelDecodeResult.Success -> {
                        val d = result.frame.descriptor
                        if (reset) { _viewerWindowWidth.value = d.defaultWindowWidth.roundToInt().coerceAtLeast(1)
                            _viewerWindowCenter.value = d.defaultWindowCenter.roundToInt()
                            preferredWindow = d.defaultWindowWidth to d.defaultWindowCenter
                            _viewerActiveVoiLutIndex.value = d.windowPresets.firstOrNull()?.takeIf { it.isVoiLutSequence }?.voiLutIndex }
                        lastLoadedFilePath = filePath; lastLoadedFrameIndex = frameIndex
                        _viewerRenderState.value = DicomViewerRenderState.Ready(requireNotNull(unpublished), d,
                            frameIndex + 1, d.numberOfFrames, instanceIndex, totalInstances, filePath)
                        unpublished = null // Ownership is now the UI's; recycling a bitmap still drawn by Compose is unsafe.
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (oom: OutOfMemoryError) {
                frameCache.clear()
                if (renderRequests.isCurrent(ticket)) _viewerRenderState.value = DicomViewerRenderState.Error("Недостаточно памяти для Viewer")
            } catch (e: Exception) {
                if (renderRequests.isCurrent(ticket)) _viewerRenderState.value = DicomViewerRenderState.Error(e.message ?: "Ошибка Viewer")
            } finally { unpublished?.recycle() }
        }
        if (external == null) loadReceivedTags(filePath)
    }

    fun deleteStudy(studyInstanceUid: String) {
        renderRequests.next()
        renderJob?.cancel()
        frameCache.clear()
        lastLoadedFilePath = null
        archive.deleteStudy(studyInstanceUid)
        if (_selectedStudyUid.value == studyInstanceUid) {
            _selectedStudyUid.value = null
            _selectedSeriesUid.value = null
            _selectedTagForDetail.value = null
            changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.None)
        }
    }

    fun clearAllStudies() {
        renderRequests.next(); changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.None)
        renderJob?.cancel()
        frameCache.clear()
        lastLoadedFilePath = null
        archive.clearStudies()
        _selectedStudyUid.value = null
        _selectedSeriesUid.value = null
        _selectedTagForDetail.value = null
        _viewerRenderState.value = DicomViewerRenderState.Idle
    }

    fun getTotalStorageBytes(): Long = studyRepository.getTotalStorageBytes()
    fun getTotalSavedFilesCount(): Int = studyRepository.getTotalSavedFilesCount()

    // --- Действия раздела «Отправка (SCU)» ---

    fun updateScuConfig(transform: (ScuSendConfig) -> ScuSendConfig) {
        _scuConfig.update { current ->
            val updated = transform(current)
            if (updated.remoteIp != current.remoteIp ||
                updated.remotePort != current.remotePort ||
                updated.calledAeTitle != current.calledAeTitle ||
                updated.callingAeTitle != current.callingAeTitle
            ) {
                scuPrefs.edit()
                    .putString("scu_remote_ip", updated.remoteIp)
                    .putInt("scu_remote_port", updated.remotePort)
                    .putString("scu_called_ae", updated.calledAeTitle)
                    .putString("scu_calling_ae", updated.callingAeTitle)
                    .apply()
            }
            val syncedBirthDate = DicomSecondaryCaptureGenerator.displayDateToDicomDa(updated.patientBirthDateDisplay)
            // Выбор Modality никогда не меняет SOP Class тестового изображения (1.2.840.10008.5.1.4.1.1.7)
            updated.copy(
                patientBirthDate = syncedBirthDate,
                sopClassUid = DicomSecondaryCaptureGenerator.SECONDARY_CAPTURE_SOP_CLASS_UID
            )
        }
        syncPreparedScuObject(forceNewObject = false)
    }

    fun setTestImageMode(mode: TestImageMode) {
        _scuConfig.update { it.copy(imageMode = mode) }
        syncPreparedScuObject(forceNewObject = false)
    }

    /**
     * Генерирует новый случайный смайлик (Режим B).
     * После генерации изображение остаётся неизменным до следующего нажатия кнопки «Сгенерировать заново».
     */
    fun regenerateSmiley() {
        _scuConfig.update { current ->
            val nextSeed = ScuTestImageRenderer.nextRandomSmileySeed(current.smileySeed)
            current.copy(smileySeed = nextSeed)
        }
        syncPreparedScuObject(forceNewObject = false)
    }

    fun setBrushThickness(thicknessPx: Float) {
        val clamped = thicknessPx.coerceIn(2f, 28f)
        _scuConfig.update { it.copy(brushThicknessPx = clamped) }
    }

    fun addFreehandStroke(points: List<NormalizedPoint>, strokeWidthPx: Float = _scuConfig.value.brushThicknessPx) {
        if (points.isNotEmpty()) {
            val stroke = FreehandStroke(
                points = points,
                strokeWidthPx = strokeWidthPx.coerceIn(2f, 28f)
            )
            _freehandStrokes.update { it + stroke }
            syncPreparedScuObject(forceNewObject = false)
        }
    }

    fun clearFreehandCanvas() {
        _freehandStrokes.value = emptyList()
        syncPreparedScuObject(forceNewObject = false)
    }

    /**
     * Обновляет подготовленный DICOM-объект Secondary Capture:
     * - если изменилось тестовое изображение/значимые метаданные пациента или `forceNewObject == true`, создаются новые уникальные UID;
     * - если это повторная попытка отправки того же сформированного объекта, его UID сохраняются.
     */
    fun syncPreparedScuObject(forceNewObject: Boolean = false): PreparedSecondaryCaptureDicomObject {
        val currentConfig = _scuConfig.value
        val currentStrokes = _freehandStrokes.value
        val nextObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = currentConfig,
            freehandStrokes = currentStrokes,
            existingObject = _preparedScuObject.value,
            forceNewObject = forceNewObject
        )
        _preparedScuObject.value = nextObj
        return nextObj
    }

    /**
     * Запускает реальный DICOM C-ECHO SCU в фоновом потоке (Dispatchers.IO).
     * Блокирует повторный запуск операции, пока текущая выполняется.
     */
    fun triggerCEchoScu() {
        val snapshot = _scuConfig.value
        if (snapshot.isOperationInProgress) return

        val lease = ru.medeng.dicomtool.dicom.scu.ScuOperationGate.acquire(scuLeaseOwner, "C-ECHO") ?: return

        _scuConfig.update {
            it.copy(
                isOperationInProgress = true,
                activeOperationName = "C-ECHO",
                lastStatusTitle = "Выполняется C-ECHO...",
                lastStatusMessage = "Установка TCP-соединения и согласование ассоциации с ${snapshot.remoteIp}:${snapshot.remotePort} [${snapshot.callingAeTitle} → ${snapshot.calledAeTitle}]...",
                isLastOperationSuccess = null,
                isLastOperationWarning = false
            )
        }

        val operationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                scuClient.performCEcho(
                    remoteHost = snapshot.remoteIp,
                    remotePort = snapshot.remotePort,
                    callingAeTitle = snapshot.callingAeTitle,
                    calledAeTitle = snapshot.calledAeTitle
                )
            }
            applyScuOperationResult(result)
        }
        operationJob.invokeOnCompletion { ru.medeng.dicomtool.dicom.scu.ScuOperationGate.release(lease) }
    }

    /**
     * Обновляет цифры даты рождения пациента с автоматической маской и валидацией.
     */
    fun updatePatientBirthDateDigits(digits: String) {
        val clean = DicomDateUtils.extractDigits(digits)
        val validation = DicomDateUtils.validateBirthDateDigits(clean)
        _scuConfig.update {
            when (validation) {
                is DateValidationResult.Valid -> it.copy(
                    patientBirthDateDigits = clean,
                    patientBirthDateDisplay = validation.displayFormatted,
                    patientBirthDate = validation.dicomDa
                )
                is DateValidationResult.ValidEmpty -> it.copy(
                    patientBirthDateDigits = "",
                    patientBirthDateDisplay = "",
                    patientBirthDate = ""
                )
                is DateValidationResult.Incomplete, is DateValidationResult.Invalid -> it.copy(
                    patientBirthDateDigits = clean,
                    patientBirthDateDisplay = DicomDateUtils.formatDigitsToDisplay(clean),
                    patientBirthDate = ""
                )
            }
        }
        syncPreparedScuObject(forceNewObject = false)
    }

    /**
     * Создаёт новый тестовый DICOM-объект с новыми уникальными UID (Study, Series, SOP Instance UID).
     * Отдельная операция по ТЗ: «Создать новый».
     */
    fun createNewScuObject() {
        if (_scuConfig.value.imageMode == TestImageMode.EMOJI) {
            _scuConfig.update { current ->
                val nextSeed = ScuTestImageRenderer.nextRandomSmileySeed(current.smileySeed)
                current.copy(smileySeed = nextSeed)
            }
        }
        val newObj = syncPreparedScuObject(forceNewObject = true)
        _scuConfig.update {
            it.copy(
                lastStatusTitle = "Создан новый объект",
                lastStatusMessage = "Сформирован новый Secondary Capture объект (SOP Instance UID: ${newObj.uids.sopInstanceUid}). Готов к отправке.",
                isLastOperationSuccess = null,
                isLastOperationWarning = false
            )
        }
    }

    /**
     * Повторно отправляет ТОТ ЖЕ САМЫЙ ранее сформированный DICOM-объект без изменения UID, Pixel Data или метаданных.
     * По ТЗ: отдельная операция «Отправить повторно». Не считает повторную отправку успешной без получения C-STORE-RSP (0x0000).
     */
    fun triggerResendCStoreScu() {
        val snapshot = _scuConfig.value
        if (snapshot.isOperationInProgress) return

        val prepared = _preparedScuObject.value ?: syncPreparedScuObject(forceNewObject = false)

        val lease = ru.medeng.dicomtool.dicom.scu.ScuOperationGate.acquire(scuLeaseOwner, "C-STORE") ?: return

        _scuConfig.update {
            it.copy(
                isOperationInProgress = true,
                activeOperationName = "C-STORE (Повтор)",
                lastStatusTitle = "Повторная отправка C-STORE...",
                lastStatusMessage = "Повторная отправка того же объекта (SOPInstanceUID=${prepared.uids.sopInstanceUid}) на ${snapshot.remoteIp}:${snapshot.remotePort}...",
                isLastOperationSuccess = null,
                isLastOperationWarning = false
            )
        }

        val operationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                scuClient.performCStore(
                    remoteHost = snapshot.remoteIp,
                    remotePort = snapshot.remotePort,
                    callingAeTitle = snapshot.callingAeTitle,
                    calledAeTitle = snapshot.calledAeTitle,
                    preparedObject = prepared
                )
            }
            if (result is ScuOperationResult.Success) {
                _scuConfig.update { it.copy(lastSentSopInstanceUid = prepared.uids.sopInstanceUid) }
            }
            applyScuOperationResult(result)
        }
        operationJob.invokeOnCompletion { ru.medeng.dicomtool.dicom.scu.ScuOperationGate.release(lease) }
    }

    /**
     * Запускает реальный DICOM C-STORE SCU (Secondary Capture 1.2.840.10008.5.1.4.1.1.7) в фоновом потоке (Dispatchers.IO).
     * Проверяет корректность даты рождения перед отправкой.
     */
    fun triggerCStoreScu() {
        val snapshot = _scuConfig.value
        if (snapshot.isOperationInProgress) return

        // Проверка корректности даты рождения
        val dateValidation = DicomDateUtils.validateBirthDateDigits(snapshot.patientBirthDateDigits)
        if (dateValidation is DateValidationResult.Incomplete) {
            _scuConfig.update {
                it.copy(
                    lastStatusTitle = "Некорректная дата рождения",
                    lastStatusMessage = "Дата рождения заполнена не полностью (введено ${dateValidation.digitsCount} из 8 цифр). Заполните все 8 цифр ДД.ММ.ГГГГ или очистите поле.",
                    isLastOperationSuccess = false,
                    isLastOperationWarning = false
                )
            }
            return
        }
        if (dateValidation is DateValidationResult.Invalid) {
            _scuConfig.update {
                it.copy(
                    lastStatusTitle = "Некорректная дата рождения",
                    lastStatusMessage = dateValidation.messageRu,
                    isLastOperationSuccess = false,
                    isLastOperationWarning = false
                )
            }
            return
        }

        val prepared = syncPreparedScuObject(forceNewObject = false)

        val lease = ru.medeng.dicomtool.dicom.scu.ScuOperationGate.acquire(scuLeaseOwner, "C-STORE") ?: return

        _scuConfig.update {
            it.copy(
                isOperationInProgress = true,
                activeOperationName = "C-STORE",
                lastStatusTitle = "Выполняется C-STORE...",
                lastStatusMessage = "Отправка Secondary Capture (512×512, Modality=${prepared.modality}, SOPInstanceUID=${prepared.uids.sopInstanceUid}) на ${snapshot.remoteIp}:${snapshot.remotePort}...",
                isLastOperationSuccess = null,
                isLastOperationWarning = false
            )
        }

        val operationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                scuClient.performCStore(
                    remoteHost = snapshot.remoteIp,
                    remotePort = snapshot.remotePort,
                    callingAeTitle = snapshot.callingAeTitle,
                    calledAeTitle = snapshot.calledAeTitle,
                    preparedObject = prepared
                )
            }
            if (result is ScuOperationResult.Success) {
                _scuConfig.update { it.copy(lastSentSopInstanceUid = prepared.uids.sopInstanceUid) }
            }
            applyScuOperationResult(result)
        }
        operationJob.invokeOnCompletion { ru.medeng.dicomtool.dicom.scu.ScuOperationGate.release(lease) }
    }

    // --- Действия пакетной отправки DICOM-файлов (Этап 4.2) ---

    fun setScuSendMode(mode: ScuSendMode) {
        _scuSendMode.value = mode
        _scuConfig.update { it.copy(sendMode = mode) }
    }

    fun addCandidateFilesFromUris(uris: List<Uri>) {
        if (_batchProgress.value.isSending) return
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val inspected = uris.map { uri ->
                DicomFileScanner.inspectUri(context = appContext, uri = uri)
            }
            withContext(Dispatchers.Main) {
                _candidateFiles.update { current ->
                    val existingUris = current.map { it.uri }.toSet()
                    current + inspected.filter { it.uri !in existingUris }
                }
            }
        }
    }

    fun scanCandidateFolder(treeUri: Uri) {
        if (_batchProgress.value.isSending) return
        scanJob?.cancel()
        val generation = ++scanGeneration
        _batchProgress.value = ScuBatchProgressState(isScanning = true, summaryMessageRu = "Сканирование каталога...")
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val ownJob = coroutineContext[Job]!!
            try {
                val found = DicomFileScanner.scanTreeFolder(appContext, treeUri,
                    onProgress = { scanned, foundCount, _ ->
                        if (generation == scanGeneration && ownJob.isActive) _batchProgress.update {
                            it.copy(scannedCount = scanned, totalFiles = foundCount,
                                summaryMessageRu = "Сканирование: проверено $scanned файлов (найдено DICOM: $foundCount)...")
                        }
                    }, isCancelled = { !ownJob.isActive })
                withContext(Dispatchers.Main) {
                    if (generation == scanGeneration && ownJob.isActive) {
                        _candidateFiles.update { current ->
                            val existing = current.map { it.uri }.toSet()
                            current + found.filter { it.uri !in existing }
                        }
                        _batchProgress.update { it.copy(summaryMessageRu = "Сканирование завершено: найдено ${found.size} DICOM-файлов.") }
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (generation == scanGeneration) {
                        _batchProgress.update { it.copy(isScanning = false,
                            summaryMessageRu = if (!ownJob.isActive) "Сканирование отменено; чтение завершено." else it.summaryMessageRu) }; scanJob = null
                    }
                }
            }
        }
    }

    fun cancelFolderScan() {
        scanJob?.cancel()
        _batchProgress.update { it.copy(summaryMessageRu = "Запрошена отмена сканирования; ожидается завершение чтения.") }
    }
    fun removeCandidateFile(fileId: String) {
        if (_batchProgress.value.isSending) return
        _candidateFiles.update { current -> current.filter { it.id != fileId } }
    }

    fun clearCandidateFiles() {
        if (_batchProgress.value.isSending) return
        _candidateFiles.value = emptyList()
        _batchProgress.value = ScuBatchProgressState()
    }

    fun cancelBatchSend() {
        if (!_batchProgress.value.isSending) return
        _batchProgress.update { it.copy(summaryMessageRu = "Запрошена отмена; ожидается освобождение сетевых ресурсов.") }
        sendCancellation?.request()
    }

    fun triggerCStoreFileBatch() {
        if (_scuConfig.value.isOperationInProgress || _batchProgress.value.isSending ||
            _batchProgress.value.isScanning || sendJob?.isActive == true) return
        val files = _candidateFiles.value.filter { it.isValidDicom }.toList()
        if (files.isEmpty()) return
        val lease = ru.medeng.dicomtool.dicom.scu.ScuOperationGate.acquire(scuLeaseOwner, "C-STORE batch") ?: return
        val snapshot = _scuConfig.value
        val cancellation = ru.medeng.dicomtool.dicom.scu.ScuCancellation()
        sendCancellation = cancellation
        _batchProgress.value = ScuBatchProgressState(isSending = true, totalFiles = files.size,
            summaryMessageRu = "Подготовка отправки ${files.size} файлов...")
        _scuConfig.update { it.copy(isOperationInProgress = true, activeOperationName = "C-STORE",
            lastStatusTitle = "Передача файлов", isLastOperationSuccess = null, isLastOperationWarning = false) }
        _candidateFiles.update { list -> list.map { file ->
            if (files.any { it.id == file.id }) file.copy(sendStatus = FileSendStatus.PENDING, sendErrorMessage = null) else file
        } }
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val ownJob = coroutineContext[Job]!!
            try {
                val result = scuClient.performCStoreFileBatch(appContext, snapshot.remoteIp, snapshot.remotePort,
                    snapshot.callingAeTitle, snapshot.calledAeTitle, files,
                    onFileProgress = { index, file, status, message ->
                        _candidateFiles.update { list -> list.map {
                            if (it.id == file.id) it.copy(sendStatus = status, sendErrorMessage = message) else it
                        } }
                        val outcomes = _candidateFiles.value.filter { candidate -> files.any { it.id == candidate.id } }
                        _batchProgress.update { it.copy(currentIndex = index + 1, currentFileName = file.fileName,
                            sentSuccessCount = outcomes.count { f -> f.sendStatus == FileSendStatus.SUCCESS },
                            sentWarningCount = outcomes.count { f -> f.sendStatus == FileSendStatus.WARNING },
                            failedCount = outcomes.count { f -> f.sendStatus == FileSendStatus.FAILED },
                            skippedCount = outcomes.count { f -> f.sendStatus == FileSendStatus.SKIPPED },
                            cancelledCount = outcomes.count { f -> f.sendStatus == FileSendStatus.CANCELLED },
                            unconfirmedCount = outcomes.count { f -> f.sendStatus == FileSendStatus.UNCONFIRMED }) }
                    }, isCancelled = { !ownJob.isActive || cancellation.isRequested }, cancellation = cancellation)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    _batchProgress.update { it.copy(summaryMessageRu = result.summaryMessageRu,
                        sentSuccessCount = result.successCount, sentWarningCount = result.warningCount,
                        failedCount = result.failedCount, skippedCount = result.skippedCount,
                        cancelledCount = result.cancelledCount, unconfirmedCount = result.unconfirmedCount,
                        isCancelled = result.isCancelled) }
                    val confirmed = !result.isCancelled && result.totalFiles > 0 &&
                        result.successCount + result.warningCount == result.totalFiles
                    _scuConfig.update { it.copy(lastStatusTitle = when {
                        result.isCancelled -> "Передача отменена"
                        result.unconfirmedCount > 0 -> "Есть неизвестные результаты"
                        confirmed && result.warningCount == 0 -> "Все файлы успешно отправлены"
                        confirmed -> "Передача с предупреждениями"
                        else -> "Пакет завершён с ошибками"
                    }, lastStatusMessage = result.summaryMessageRu, isLastOperationSuccess = confirmed,
                        isLastOperationWarning = result.warningCount > 0 || result.unconfirmedCount > 0 || result.closureWarnings.isNotEmpty() ||
                            (!confirmed && result.successCount > 0)) }
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    _batchProgress.update { it.copy(summaryMessageRu = "Передача завершена без полного результата: ${e.message}") }
                    _scuConfig.update { it.copy(isLastOperationSuccess = false, lastStatusTitle = "Передача не подтверждена",
                        lastStatusMessage = "Результаты на PACS могут быть неизвестны: ${e.message}") }
                }
            } finally {
                cancellation.request()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    _batchProgress.update { it.copy(isSending = false) }
                    _scuConfig.update { it.copy(isOperationInProgress = false, activeOperationName = null) }
                    sendCancellation = null; sendJob = null
                }
            }
        }
        sendJob?.invokeOnCompletion { ru.medeng.dicomtool.dicom.scu.ScuOperationGate.release(lease) }
    }
    // --- Действия раздела «Хранилище DICOM и SAF» ---


    fun refreshStorageInfo() = archive.refresh()
    fun setCustomSafStorage(treeUri: Uri) = archive.setSaf(treeUri)
    fun resetToInternalStorage() = archive.resetInternal()

    /**
     * Открывает активный каталог хранения в системном файловом менеджере Android (если настроен SAF).
     */
    fun openStorageFolder(context: android.content.Context) {
        val info = storageLocationInfo.value
        if (info.type == StorageType.SAF && !info.safTreeUriString.isNullOrBlank()) {
            val uri = Uri.parse(info.safTreeUriString)
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Не удалось открыть системный файловый менеджер", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "Внутреннее хранилище доступно только для приложения: ${info.displayPath}", Toast.LENGTH_SHORT).show()
        }
    }

    fun migrateStudies(fromInternalToSaf: Boolean) = archive.migrate(fromInternalToSaf)

    private fun applyScuOperationResult(result: ScuOperationResult) {
        when (result) {
            is ScuOperationResult.Success -> {
                _scuConfig.update {
                    it.copy(
                        isOperationInProgress = false,
                        activeOperationName = null,
                        lastStatusTitle = result.shortTitleRu,
                        lastStatusMessage = result.userMessageRu,
                        isLastOperationSuccess = true,
                        isLastOperationWarning = false
                    )
                }
            }

            is ScuOperationResult.Warning -> {
                _scuConfig.update {
                    it.copy(
                        isOperationInProgress = false,
                        activeOperationName = null,
                        lastStatusTitle = result.shortTitleRu,
                        lastStatusMessage = result.userMessageRu,
                        isLastOperationSuccess = true,
                        isLastOperationWarning = true
                    )
                }
            }

            is ScuOperationResult.Failure -> {
                _scuConfig.update {
                    it.copy(
                        isOperationInProgress = false,
                        activeOperationName = null,
                        lastStatusTitle = result.shortTitleRu,
                        lastStatusMessage = result.userMessageRu,
                        isLastOperationSuccess = false,
                        isLastOperationWarning = false
                    )
                }
            }
        }
    }

    // --- Действия раздела «Журнал DICOM» (Этап 4.3.2) ---

    fun setLogDisplayMode(mode: DicomLogDisplayMode) {
        _logDisplayMode.value = mode
    }

    fun setLogTrafficFilter(filter: DicomTrafficFilter) {
        _logTrafficFilter.value = filter
    }

    fun setLogOutcomeFilter(filter: DicomOutcomeFilter) {
        _logOutcomeFilter.value = filter
    }

    fun updateLogSearchQuery(query: String) {
        _logSearchQuery.value = query
    }

    fun toggleAutoScrollLogs(enabled: Boolean) {
        _autoScrollLogs.value = enabled
    }

    fun toggleLogExpanded(logId: String) {
        _expandedLogIds.update { current ->
            if (current.contains(logId)) current - logId else current + logId
        }
    }

    fun toggleSessionExpanded(sessionKey: String) {
        _expandedSessionKeys.update { current ->
            if (current.contains(sessionKey)) current - sessionKey else current + sessionKey
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { technicalLogger.clearLogsAndWait() }
            if (cleared) { _expandedLogIds.value = emptySet(); _expandedSessionKeys.value = emptySet() }
            Toast.makeText(appContext, if (cleared) "Журнал очищен" else "Очистка журнала не подтверждена; проверьте состояние logger", Toast.LENGTH_LONG).show()
        }
    }

    // --- Действия раздела «DICOM-теги» (Этап 4.1) ---

    fun loadExternalDicomFile(uri: Uri) {
        val ticket = metadataRequests.next()
        externalLoadJob?.cancel()
        renderRequests.next(); renderJob?.cancel(); frameCache.clear()
        _selectedExternalFileUri.value = uri
        changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.External(uri.toString()))
        _viewerRenderState.value = DicomViewerRenderState.Loading
        _externalFileSummary.value = null; _externalFileTags.value = emptyList()
        _externalFileError.value = null; _isExternalFileLoading.value = true
        _viewerCurrentFrame.value = 1; _isViewerFullScreen.value = false; lastLoadedFilePath = null
        val encoding = _externalTagEncodingOverride.value.charsetName
        externalLoadJob = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]!!
                val parsed = withContext(Dispatchers.IO) { viewerWorkMutex.withLock {
                    metadataRequests.check(ticket)
                    DicomFileReader.readFromUri(appContext, uri, encoding) { job.ensureActive(); metadataRequests.check(ticket) }
                } }
                metadataRequests.check(ticket)
                _externalFileSummary.value = parsed.summary; _externalFileTags.value = parsed.tags
                _isExternalFileLoading.value = false
                if (_viewerSource.value == ru.medeng.dicomtool.domain.model.ViewerSource.External(uri.toString())) triggerFrameDecodeAndRender(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (oom: OutOfMemoryError) {
                if (metadataRequests.isCurrent(ticket)) {
                    _externalFileSummary.value = null; _externalFileTags.value = emptyList()
                    _externalFileError.value = "Недостаточно памяти для метаданных DICOM"; _isExternalFileLoading.value = false
                    if (_viewerSource.value == ru.medeng.dicomtool.domain.model.ViewerSource.External(uri.toString()))
                        _viewerRenderState.value = DicomViewerRenderState.Error(_externalFileError.value!!)
                }
            } catch (e: Exception) {
                if (metadataRequests.isCurrent(ticket)) {
                    _externalFileError.value = e.message ?: "Ошибка чтения DICOM-файла"
                    _externalFileSummary.value = null; _externalFileTags.value = emptyList(); _isExternalFileLoading.value = false
                    if (_viewerSource.value == ru.medeng.dicomtool.domain.model.ViewerSource.External(uri.toString()))
                        _viewerRenderState.value = DicomViewerRenderState.Error(_externalFileError.value!!)
                }
            }
        }
    }

    fun clearExternalFile() {
        metadataRequests.next(); externalLoadJob?.cancel(); _isExternalFileLoading.value = false
        renderRequests.next(); frameCache.clear()
        changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.None)
        renderJob?.cancel()
        _selectedExternalFileUri.value = null
        _externalFileSummary.value = null
        _externalFileTags.value = emptyList()
        _externalFileError.value = null
        _externalTagSearchQuery.value = ""
        _externalTagEncodingOverride.value = DicomEncodingOverride.AUTO
        _isViewerFullScreen.value = false
        _viewerRenderState.value = DicomViewerRenderState.Idle
        lastLoadedFilePath = null
    }

    fun setExternalTagEncodingOverride(override: DicomEncodingOverride) {
        _externalTagEncodingOverride.value = override
        val currentUri = _selectedExternalFileUri.value
        if (currentUri != null) {
            loadExternalDicomFile(currentUri)
        }
    }

    fun updateExternalTagSearchQuery(query: String) {
        _externalTagSearchQuery.value = query
    }

    init {
        viewModelScope.launch {
            var observingOtherOwner = false
            ru.medeng.dicomtool.dicom.scu.ScuOperationGate.active.collect { lease ->
                if (lease != null && lease.owner != scuLeaseOwner) {
                    observingOtherOwner = true
                    _scuConfig.update { it.copy(isOperationInProgress = true, activeOperationName = lease.operation,
                        lastStatusTitle = "Операция ещё выполняется", lastStatusMessage = "Ожидается завершение сетевой операции предыдущего экрана и освобождение ресурсов.", isLastOperationSuccess = null) }
                } else if (lease == null && observingOtherOwner) {
                    observingOtherOwner = false
                    _scuConfig.update { it.copy(isOperationInProgress = false, activeOperationName = null,
                        lastStatusTitle = "Операция завершена", lastStatusMessage = "Проверьте результаты файлов, ответы узла и состояние записи технического журнала.", isLastOperationSuccess = null) }
                }
            }
        }
        viewModelScope.launch {
            combine(studies, archive.busy, archiveIndexState) { list, busy, state -> Triple(list, busy, state) }
                .collect { (list, busy, state) ->
                    val source = _viewerSource.value as? ru.medeng.dicomtool.domain.model.ViewerSource.Received
                    if (!busy && !state.isLoading && source != null && list.none { it.studyInstanceUid == source.studyInstanceUid }) {
                        changeViewerSource(ru.medeng.dicomtool.domain.model.ViewerSource.None)
                        _selectedStudyUid.value = null
                    }
                }
        }
    }

    override fun onCleared() {
        receivedTagRequests.next(); receivedTagJob?.cancel()
        metadataRequests.next(); renderRequests.next(); externalLoadJob?.cancel()
        sendCancellation?.request()
        scanJob?.cancel()
        super.onCleared()
        renderJob?.cancel()
        frameCache.clear()
        // Each SCU operation cleans up only its own temporary file in finally.
    }
}
