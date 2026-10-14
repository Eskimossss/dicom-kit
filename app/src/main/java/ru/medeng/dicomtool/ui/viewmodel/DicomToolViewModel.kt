package ru.medeng.dicomtool.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val engineVerifier = Dcm4cheEngineVerifier()
    private val studyRepository = DicomRuntimeRegistry.getStudyRepository(appContext)
    private val technicalLogger = DicomRuntimeRegistry.getTechnicalLogger(appContext)
    private val scpServer = DicomRuntimeRegistry.getScpServer(appContext)
    private val scuClient: DicomScuClient = DicomRuntimeRegistry.getScuClient(appContext)

    // Одноэлементный кэш декодированного кадра (исключает утечки памяти и повторное чтение 18 МБ файла при регулировке WW/WL)
    private val frameCache = SingleFrameDecodeCache()
    private var renderJob: Job? = null
    private var lastLoadedFilePath: String? = null

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), studyRepository.studiesFlow.value)

    val storageManager: DicomStorageManager? = (studyRepository as? ru.medeng.dicomtool.data.storage.LocalDicomStudyRepository)?.storageManager

    private val _storageLocationInfo = MutableStateFlow(
        storageManager?.getStorageLocationInfo(
            totalBytes = studyRepository.getTotalStorageBytes(),
            filesCount = studyRepository.getTotalSavedFilesCount(),
            studiesCount = studyRepository.studiesFlow.value.size
        ) ?: StorageLocationInfo(
            type = StorageType.INTERNAL,
            displayPath = studyRepository.storageRootDir.absolutePath,
            totalBytes = studyRepository.getTotalStorageBytes(),
            filesCount = studyRepository.getTotalSavedFilesCount(),
            studiesCount = studyRepository.studiesFlow.value.size
        )
    )
    val storageLocationInfo: StateFlow<StorageLocationInfo> = _storageLocationInfo.asStateFlow()

    private val _migrationStatusMessage = MutableStateFlow<String?>(null)
    val migrationStatusMessage: StateFlow<String?> = _migrationStatusMessage.asStateFlow()

    private val _isMigrating = MutableStateFlow(false)
    val isMigrating: StateFlow<Boolean> = _isMigrating.asStateFlow()

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun updateScpAeTitleInput(raw: String) {
        _aeTitleInput.value = raw
        val portNum = _portInput.value.toIntOrNull() ?: -1
        scpServer.updateConfiguration(raw, portNum)
    }

    fun updateScpPortInput(raw: String) {
        val digitsOnly = raw.filter { it.isDigit() }.take(5)
        _portInput.value = digitsOnly
        val portNum = digitsOnly.toIntOrNull() ?: -1
        scpServer.updateConfiguration(_aeTitleInput.value, portNum)
    }

    fun incrementScpPort(delta: Int) {
        val current = _portInput.value.toIntOrNull() ?: 11112
        val next = (current + delta).coerceIn(1024, 65535)
        _portInput.value = next.toString()
        scpServer.updateConfiguration(_aeTitleInput.value, next)
    }

    fun refreshNetworkInterface() {
        scpServer.refreshNetworkInfo()
    }

    fun toggleScpServer() {
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
            DicomScpForegroundService.startScpService(appContext, ae, port)
        }
    }

    // --- Действия раздела «Принятые исследования», DICOM Viewer и экрана тегов ---

    fun refreshReceivedStudies() {
        scpServer.refreshCountersFromRepository()
    }

    fun setModalityFilter(modality: String) {
        _selectedModalityFilter.value = modality
    }

    fun selectStudy(studyInstanceUid: String) {
        val changed = _selectedStudyUid.value != studyInstanceUid
        _selectedStudyUid.value = studyInstanceUid
        val study = studies.value.firstOrNull { it.studyInstanceUid == studyInstanceUid }
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
        val currentList = studies.value
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
        val study = getSelectedStudyOrDefault() ?: return emptyList()
        val seriesUid = _selectedSeriesUid.value ?: study.seriesList.firstOrNull()?.seriesInstanceUid
        val instIdx0 = (_selectedInstanceIndex.value - 1).coerceAtLeast(0)
        val allTags = studyRepository.getTagsForStudy(study.studyInstanceUid, seriesUid, instIdx0)
        val q = _tagSearchQuery.value.trim().lowercase()
        if (q.isEmpty()) return allTags
        return allTags.filter {
            it.tagHex.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                it.vr.lowercase().contains(q) ||
                it.value.lowercase().contains(q)
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
            triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
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
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    fun toggleViewerInvert() {
        _viewerInverted.update { !it }
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    fun applyWindowPreset(preset: DicomWindowPreset) {
        if (preset.isVoiLutSequence) {
            _viewerActiveVoiLutIndex.value = preset.voiLutIndex
        } else {
            _viewerActiveVoiLutIndex.value = null
            _viewerWindowWidth.value = preset.windowWidth.roundToInt().coerceAtLeast(1)
            _viewerWindowCenter.value = preset.windowCenter.roundToInt()
        }
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    fun setWindowWidthLevel(newWindowWidth: Int, newWindowCenter: Int) {
        _viewerActiveVoiLutIndex.value = null
        _viewerWindowWidth.value = newWindowWidth.coerceIn(1, 131072)
        _viewerWindowCenter.value = newWindowCenter.coerceIn(-65536, 131072)
        triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
    }

    /**
     * Изменение WW/WL на заданную дельту (или адаптивный шаг для 8-bit / 16-bit изображений).
     */
    fun adjustWindowWidthLevel(deltaW: Int, deltaL: Int) {
        _viewerActiveVoiLutIndex.value = null
        val ready = _viewerRenderState.value as? DicomViewerRenderState.Ready
        val adaptiveScale = if (ready != null) {
            val range = (ready.descriptor.maxModalityValue - ready.descriptor.minModalityValue).coerceAtLeast(255.0)
            max(1, (range / 250.0).roundToInt())
        } else {
            1
        }
        val scaledDeltaW = deltaW * adaptiveScale
        val scaledDeltaL = deltaL * adaptiveScale
        _viewerWindowWidth.update { (it + scaledDeltaW).coerceIn(1, 131072) }
        _viewerWindowCenter.update { (it + scaledDeltaL).coerceIn(-65536, 131072) }
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
        val externalUri = _selectedExternalFileUri.value
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
                triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
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
            triggerFrameDecodeAndRender(resetWindowToFileDefault = false)
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
        val externalUri = _selectedExternalFileUri.value
        val externalSummary = _externalFileSummary.value

        val (filePath: String, safeInstIdx: Int, totalInstances: Int) = if (externalUri != null && externalSummary != null) {
            Triple(externalUri.toString(), 1, 1)
        } else {
            val study = getSelectedStudyOrDefault() ?: run {
                _viewerRenderState.value = DicomViewerRenderState.Idle
                return
            }
            val series = study.seriesList.firstOrNull { it.seriesInstanceUid == _selectedSeriesUid.value }
                ?: study.seriesList.firstOrNull()
                ?: run {
                    _viewerRenderState.value = DicomViewerRenderState.Error("В исследовании нет доступных серий.")
                    return
                }
            val instancesCount = series.instances.size.coerceAtLeast(1)
            val instIdx = _selectedInstanceIndex.value.coerceIn(1, instancesCount)
            val instance = series.instances.getOrNull(instIdx - 1) ?: run {
                _viewerRenderState.value = DicomViewerRenderState.Error("Экземпляр изображения не найден.")
                return
            }
            Triple(instance.filePath, instIdx, instancesCount)
        }

        val frameIdx0 = (_viewerCurrentFrame.value - 1).coerceAtLeast(0)
        val isNewFile = lastLoadedFilePath != filePath

        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            if (frameCache.get(filePath, frameIdx0) == null) {
                _viewerRenderState.value = DicomViewerRenderState.Loading
            }

            val decodeRes = withContext(Dispatchers.IO) {
                if (filePath.startsWith("content://")) {
                    DicomPixelDataDecoder.decodeFrameFromSource(
                        sourcePathOrUri = filePath,
                        streamOpener = { appContext.contentResolver.openInputStream(android.net.Uri.parse(filePath)) },
                        frameIndex = frameIdx0,
                        cache = frameCache
                    )
                } else {
                    DicomPixelDataDecoder.decodeFrameFromFile(
                        file = File(filePath),
                        frameIndex = frameIdx0,
                        cache = frameCache
                    )
                }
            }

            when (decodeRes) {
                is DicomPixelDecodeResult.UnsupportedFormat -> {
                    _viewerRenderState.value = DicomViewerRenderState.UnsupportedFormat(
                        transferSyntaxUid = decodeRes.transferSyntaxUid,
                        transferSyntaxName = decodeRes.transferSyntaxName,
                        photometricInterpretation = decodeRes.photometricInterpretation,
                        bitsAllocated = decodeRes.bitsAllocated,
                        reasonRu = decodeRes.reasonRu
                    )
                }

                is DicomPixelDecodeResult.DecodeError -> {
                    _viewerRenderState.value = DicomViewerRenderState.Error(
                        reasonRu = decodeRes.reasonRu
                    )
                }

                is DicomPixelDecodeResult.Success -> {
                    val decodedFrame = decodeRes.frame
                    val desc = decodedFrame.descriptor

                    if (resetWindowToFileDefault || isNewFile) {
                        lastLoadedFilePath = filePath
                        _viewerWindowWidth.value = desc.defaultWindowWidth.roundToInt().coerceAtLeast(1)
                        _viewerWindowCenter.value = desc.defaultWindowCenter.roundToInt()
                        _viewerActiveVoiLutIndex.value = null
                    }

                    val targetWw = _viewerWindowWidth.value.toDouble()
                    val targetWc = _viewerWindowCenter.value.toDouble()
                    val targetInverted = _viewerInverted.value
                    val voiLutIdx = _viewerActiveVoiLutIndex.value

                    val renderedBitmap = withContext(Dispatchers.Default) {
                        DicomPixelDataDecoder.renderFrameToBitmap(
                            frame = decodedFrame,
                            windowWidth = targetWw,
                            windowCenter = targetWc,
                            userInverted = targetInverted,
                            useVoiLutIndex = voiLutIdx
                        )
                    }

                    _viewerRenderState.value = DicomViewerRenderState.Ready(
                        bitmap = renderedBitmap,
                        descriptor = desc,
                        currentFrame = decodedFrame.frameIndex + 1,
                        totalFrames = desc.numberOfFrames,
                        currentInstance = safeInstIdx,
                        totalInstances = totalInstances,
                        filePath = filePath
                    )
                }
            }
        }
    }

    fun deleteStudy(studyInstanceUid: String) {
        renderJob?.cancel()
        frameCache.clear()
        lastLoadedFilePath = null
        studyRepository.deleteStudy(studyInstanceUid)
        scpServer.refreshCountersFromRepository()
        if (_selectedStudyUid.value == studyInstanceUid) {
            val nextUid = studies.value.firstOrNull()?.studyInstanceUid
            _selectedStudyUid.value = nextUid
            _selectedTagForDetail.value = null
            if (nextUid != null) {
                selectStudy(nextUid)
            } else {
                _viewerRenderState.value = DicomViewerRenderState.Idle
            }
        }
    }

    fun clearAllStudies() {
        renderJob?.cancel()
        frameCache.clear()
        lastLoadedFilePath = null
        studyRepository.clearAllStudies()
        scpServer.refreshCountersFromRepository()
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

        viewModelScope.launch {
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

        viewModelScope.launch {
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

        viewModelScope.launch {
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
    }
    // --- Действия раздела «Хранилище DICOM и SAF» ---


    init {
        // Failed receptions do not update the study index. Monitor retained payloads independently.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val retained = ru.medeng.dicomtool.data.storage.RetainedIncoming.inspect(studyRepository.storageRootDir)
                _storageLocationInfo.update { it.copy(retainedIncoming = retained) }
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    fun refreshStorageInfo() {
        val count = studyRepository.getTotalSavedFilesCount()
        val bytes = studyRepository.getTotalStorageBytes()
        val studiesCount = studyRepository.studiesFlow.value.size
        _storageLocationInfo.value = (storageManager?.getStorageLocationInfo(
            totalBytes = bytes,
            filesCount = count,
            studiesCount = studiesCount
        ) ?: StorageLocationInfo(
            type = StorageType.INTERNAL,
            displayPath = studyRepository.storageRootDir.absolutePath,
            totalBytes = bytes,
            filesCount = count,
            studiesCount = studiesCount
        )).copy(retainedIncoming = ru.medeng.dicomtool.data.storage.RetainedIncoming.inspect(studyRepository.storageRootDir))
    }

    fun setCustomSafStorage(treeUri: Uri): Result<Unit> {
        val sm = storageManager ?: return Result.failure(IllegalStateException("Менеджер хранилища недоступен"))
        val result = sm.setCustomSafStorage(treeUri)
        if (result.isSuccess) {
            studyRepository.refreshFromDisk()
            refreshStorageInfo()
        }
        return result
    }

    fun resetToInternalStorage() {
        storageManager?.resetToInternalStorage()
        studyRepository.refreshFromDisk()
        refreshStorageInfo()
    }

    /**
     * Открывает активный каталог хранения в системном файловом менеджере Android (если настроен SAF).
     */
    fun openStorageFolder(context: android.content.Context) {
        val info = _storageLocationInfo.value
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

    fun migrateStudies(fromInternalToSaf: Boolean) {
        val sm = storageManager ?: return
        if (_isMigrating.value) return
        _isMigrating.value = true
        _migrationStatusMessage.value = "Выполняется безопасный перенос исследований..."
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                sm.migrateFiles(fromInternalToSaf) { current, total ->
                    _migrationStatusMessage.value = "Перенос: $current из $total файлов..."
                }
            }
            studyRepository.refreshFromDisk()
            refreshStorageInfo()
            _isMigrating.value = false
            _migrationStatusMessage.value = (if (res.failedCount == 0) {
                "Успешно перенесено ${res.successfullyMigrated} файлов."
            } else {
                "Перенесено ${res.successfullyMigrated} файлов. Ошибок: ${res.failedCount} (${res.errorDetails.firstOrNull() ?: ""})"
            }) + if (res.retainedSourceCount > 0) {
                "\nПроверенные копии сохранены, но не удалось удалить исходные файлы: ${res.retainedSourceCount}. Источники оставлены на месте. Повторите перенос после восстановления доступа; одинаковые копии не будут созданы повторно."
            } else ""
        }
    }

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
        technicalLogger.clearLogs()
        _expandedLogIds.value = emptySet()
        _expandedSessionKeys.value = emptySet()
    }

    // --- Действия раздела «DICOM-теги» (Этап 4.1) ---

    fun loadExternalDicomFile(uri: Uri) {
        _selectedExternalFileUri.value = uri
        _externalFileError.value = null
        _isExternalFileLoading.value = true
        _viewerCurrentFrame.value = 1
        _isViewerFullScreen.value = false
        lastLoadedFilePath = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parseResult = DicomFileReader.readFromUri(
                    context = appContext,
                    uri = uri,
                    forcedCharset = _externalTagEncodingOverride.value.charsetName
                )
                withContext(Dispatchers.Main) {
                    _externalFileSummary.value = parseResult.summary
                    _externalFileTags.value = parseResult.tags
                    _isExternalFileLoading.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _externalFileError.value = e.message ?: "Ошибка чтения DICOM-файла"
                    _externalFileSummary.value = null
                    _externalFileTags.value = emptyList()
                    _isExternalFileLoading.value = false
                }
            }
        }
    }

    fun clearExternalFile() {
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

    override fun onCleared() {
        sendCancellation?.request()
        scanJob?.cancel()
        super.onCleared()
        renderJob?.cancel()
        frameCache.clear()
        DicomRuntimeRegistry.clearScuTempCache(appContext)
    }
}
