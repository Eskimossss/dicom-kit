package ru.medeng.dicomtool.data.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned archive work survives Activity/ViewModel disposal; it never retains an Activity. */
class ArchiveOperations(private val repository: LocalDicomStudyRepository, private val server: DicomStorageScpServer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val infoGate = Any()
    private val refreshPending = AtomicBoolean(false)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _info = MutableStateFlow(StorageLocationInfo(StorageType.INTERNAL,
        repository.storageRootDir.absolutePath, warningMessage = "Проверка хранилища…"))
    val info = _info.asStateFlow()

    init {
        refresh()
        scope.launch {
            repository.studiesFlow.collect { studies ->
                _info.update { it.copy(totalBytes = studies.sumOf { it.totalBytes }, filesCount = studies.sumOf { it.instancesCount }, studiesCount = studies.size) }
                server.refreshCountersFromRepository(rescan = false)
            }
        }
        scope.launch {
            while (isActive) {
                delay(5000)
                if (!active.get()) runCatching { updateInfo(generation.get()) }
                    .onFailure { _message.value = "Проверка хранилища не завершена: ${it.javaClass.simpleName}" }
            }
        }
        // Retained payload warnings must not wait behind a slow/unavailable SAF provider
        // or a migration. This probe touches only the private incoming directory.
        scope.launch {
            while (isActive) {
                runCatching { RetainedIncoming.inspect(repository.storageRootDir) }
                    .onSuccess { retained -> _info.update { it.copy(retainedIncoming = retained) } }
                    .onFailure { _message.value = "Не удалось проверить сохранённые входящие файлы: ${it.javaClass.simpleName}" }
                delay(5000)
            }
        }
    }
    private fun run(refresh: Boolean = false, operation: () -> Unit) {
        if (!active.compareAndSet(false, true)) {
            if (refresh) refreshPending.set(true)
            else _message.value = "Операция с архивом уже выполняется; дождитесь завершения."
            return
        }
        val ticket = synchronized(infoGate) { generation.incrementAndGet() }
        _busy.value = true
        scope.launch {
            try { operation(); updateInfo(ticket) }
            catch (e: Exception) { _message.value = "Операция с архивом не завершена: ${e.javaClass.simpleName}: ${e.message}" }
            finally {
                _busy.value = false; active.set(false)
                if (refreshPending.getAndSet(false)) refresh()
            }
        }
    }
    fun refresh() = run(refresh = true) { repository.refreshFromDisk() }
    fun deleteStudy(uid: String) = run { repository.deleteStudy(uid) }
    fun clearStudies() = run { repository.clearAllStudies() }
    fun setReceptionOptions(options: ReceptionStorageOptions) = run {
        repository.storageManager?.setReceptionOptions(options)
    }
    fun recoverIncoming() = run {
        val folder = java.io.File(repository.storageRootDir, ".incoming_tmp")
        val files = IncomingPayloadTracker.inactiveFiles(folder).filter {
            it.extension == "part" && it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                runCatching { StoragePaths.contained(repository.storageRootDir, it) }.isSuccess
        }
        var recovered = 0; var retained = 0
        for (file in files) {
            val result = runCatching {
                // Full structural read skips bulk data but detects truncated values; never rewrites the source.
                val ids = org.dcm4che3.io.DicomInputStream(object : java.io.FilterInputStream(file.inputStream().buffered()) {
                    // FileInputStream.skip may seek beyond EOF. Consume actual bytes so truncated bulk data fails validation.
                    override fun skip(n: Long): Long {
                        var remaining = n
                        val buffer = ByteArray(65536)
                        while (remaining > 0) {
                            val count = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (count < 0) break
                            remaining -= count
                        }
                        return n - remaining
                    }
                }).use { input ->
                    input.includeBulkData = org.dcm4che3.io.DicomInputStream.IncludeBulkData.NO
                    ru.medeng.dicomtool.dicom.tags.BoundedDicomRead.install(input)
                    val fmi = requireNotNull(input.readFileMetaInformation())
                    val ds = input.readDataset()
                    val sop = requireNotNull(ds.getString(org.dcm4che3.data.Tag.SOPInstanceUID))
                    require(sop == fmi.getString(org.dcm4che3.data.Tag.MediaStorageSOPInstanceUID))
                    require(ds.getString(org.dcm4che3.data.Tag.SOPClassUID) == fmi.getString(org.dcm4che3.data.Tag.MediaStorageSOPClassUID))
                    Triple(requireNotNull(ds.getString(org.dcm4che3.data.Tag.StudyInstanceUID)),
                        requireNotNull(ds.getString(org.dcm4che3.data.Tag.SeriesInstanceUID)), sop)
                }
                repository.saveRetainedIncoming(file, ids.first, ids.second, ids.third)
            }.getOrNull()
            if (result is DicomSaveResult.Success && !result.sourceRetained) recovered++ else retained++
        }
        _message.value = "Повторно сохранено/проверено: $recovered. Осталось непроверенных источников: $retained. " +
            "Исходные данные не исправлялись. Непроверенные файлы не удалены."
        _info.update { it.copy(retainedIncoming = RetainedIncoming.inspect(repository.storageRootDir)) }
    }
    fun setSaf(uri: android.net.Uri) = run {
        StorageCoordinator.forRoot(repository.storageRootDir).maintenance {
            repository.storageManager?.setCustomSafStorage(uri)?.getOrThrow()
            repository.refreshFromDisk()
        }
    }
    fun resetInternal() = run {
        StorageCoordinator.forRoot(repository.storageRootDir).maintenance {
            repository.storageManager?.resetToInternalStorage()
            repository.refreshFromDisk()
        }
    }
    fun migrate(toSaf: Boolean) = run {
        val result = repository.storageManager?.migrateFiles(toSaf) { current, total -> _message.value = "Перенос: $current из $total файлов…" }
            ?: error("Менеджер хранилища недоступен")
        repository.refreshFromDisk()
        _message.value = "Перенесено ${result.successfullyMigrated}, ошибок ${result.failedCount}." +
            (result.errorDetails.firstOrNull()?.let { " $it" } ?: "") +
            if (result.retainedSourceCount > 0) " Источники оставлены на месте: ${result.retainedSourceCount}. Повторный перенос проверит содержимое без создания одинаковых копий." else ""
    }
    private fun updateInfo(ticket: Long) {
        val manager = repository.storageManager
        val checked = manager?.getStorageLocationInfo(repository.getTotalStorageBytes(), repository.getTotalSavedFilesCount(), repository.studiesFlow.value.size) ?: StorageLocationInfo(StorageType.INTERNAL, repository.storageRootDir.absolutePath)
        synchronized(infoGate) {
            if (ticket != generation.get()) return
            _info.update { current ->
                val studies = repository.studiesFlow.value
                checked.copy(totalBytes = studies.sumOf { it.totalBytes }, filesCount = studies.sumOf { it.instancesCount },
                    studiesCount = studies.size, retainedIncoming = current.retainedIncoming)
            }
        }
    }
    internal fun closeForTesting() { scope.cancel() }
}
