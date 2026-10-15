package ru.medeng.dicomtool.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import org.dcm4che3.data.Tag
import org.dcm4che3.io.DicomInputStream

/**
 * Тип используемого каталога для хранения принятых исследований.
 */
enum class StorageType {
    /**
     * Стандартное внутреннее приватное хранилище приложения (context.filesDir/received_dicom).
     */
    INTERNAL,

    /**
     * Пользовательский каталог или SD-карта через Android Storage Access Framework (SAF / ACTION_OPEN_DOCUMENT_TREE).
     */
    SAF
}

/**
 * Информация о текущем состоянии и параметрах хранилища для экрана настроек и SCP-сервера.
 */
data class StorageLocationInfo(
    val type: StorageType,
    val displayPath: String,
    val folderName: String = "",
    val readableLocation: String = "",
    val safTreeUriString: String? = null,
    val isAccessible: Boolean = true,
    val canWrite: Boolean = true,
    val totalBytes: Long = 0L,
    val filesCount: Int = 0,
    val studiesCount: Int = 0,
    val warningMessage: String? = null,
    val retainedIncoming: RetainedIncoming = RetainedIncoming()
)

/**
 * Результат сохранения входящего DICOM-файла.
 */
sealed interface DicomSaveResult {
    data class Success(
        val destinationDescription: String,
        val savedSizeBytes: Long,
        val duplicateActionNote: String,
        val sourceRetained: Boolean = false
    ) : DicomSaveResult

    data class Failure(
        val errorReason: String,
        val isOutOfSpace: Boolean = false,
        val exception: Throwable? = null
    ) : DicomSaveResult
}

/**
 * Результат миграции существующих DICOM-файлов между внутренним хранилищем и SAF.
 */
data class MigrationResult(
    val totalFound: Int,
    val successfullyMigrated: Int,
    val failedCount: Int,
    val errorDetails: List<String> = emptyList(),
    val retainedSourceCount: Int = 0
)

/**
 * Менеджер хранилища DICOM с поддержкой внутреннего хранилища и Storage Access Framework (SAF).
 *
 * Требования (Этап 3.1):
 * - По умолчанию внутреннее хранилище.
 * - Для пользовательских каталогов и SD-карт — ACTION_OPEN_DOCUMENT_TREE и takePersistableUriPermission.
 * - Проверка возможности записи и доступности (отключение SD-карты, отзыв разрешений).
 * - Не использовать MANAGE_EXTERNAL_STORAGE.
 * - Учитывать особенности DocumentsProvider: потоковая запись без предположения о java.io.File пути или атомарном rename.
 * - Приём C-STORE завершается успехом только после надёжного сохранения файла.
 * - При ошибке записи (недостаток места, отказ прав) возвращается подробная ошибка для DICOM-статуса и журнала.
 * - Перенос файлов с проверкой успешности копирования до удаления оригинала.
 */
class DicomStorageManager(
    context: Context,
    val internalStorageDir: File = File(context.filesDir, "received_dicom")
) {

    private val context = context.applicationContext as android.app.Application
    private val prefs by lazy { context.getSharedPreferences("dicom_storage_settings", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_KEY_STORAGE_TYPE = "storage_type"
        private const val PREF_KEY_SAF_URI = "saf_tree_uri"
    }


    /**
     * Возвращает текущий тип активного хранилища (INTERNAL или SAF).
     */
    fun getCurrentStorageType(): StorageType {
        val typeStr = prefs.getString(PREF_KEY_STORAGE_TYPE, StorageType.INTERNAL.name)
        return try {
            StorageType.valueOf(typeStr ?: StorageType.INTERNAL.name)
        } catch (_: Exception) {
            StorageType.INTERNAL
        }
    }

    /**
     * Возвращает сохранённый URI пользовательского каталога SAF (если настроен).
     */
    fun getSavedSafTreeUri(): Uri? {
        val uriStr = prefs.getString(PREF_KEY_SAF_URI, null) ?: return null
        return runCatching { Uri.parse(uriStr) }.getOrNull()
    }

    /**
     * Проверяет доступность и права на запись в настроенный SAF-каталог.
     */
    fun isSafStorageWritable(treeUri: Uri? = getSavedSafTreeUri()): Boolean {
        if (treeUri == null) return false
        return try {
            val hasPersistedPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isWritePermission
            }
            if (!hasPersistedPermission) return false
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            doc != null && doc.exists() && doc.isDirectory && doc.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Переключает активное хранилище на пользовательский каталог SAF с сохранением прав.
     */
    fun setCustomSafStorage(treeUri: Uri): Result<Unit> {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            }

            val doc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.failure(IllegalStateException("Не удалось получить DocumentFile для выбранного каталога"))

            if (!doc.exists() || !doc.isDirectory) {
                return Result.failure(IllegalStateException("Выбранный путь не существует или не является папкой"))
            }
            if (!doc.canWrite()) {
                return Result.failure(SecurityException("В выбранной папке отсутствуют права на запись"))
            }

            prefs.edit()
                .putString(PREF_KEY_STORAGE_TYPE, StorageType.SAF.name)
                .putString(PREF_KEY_SAF_URI, treeUri.toString())
                .apply()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Сбрасывает активное хранилище обратно на внутреннее приватное хранилище приложения.
     */
    fun resetToInternalStorage() {
        prefs.edit()
            .putString(PREF_KEY_STORAGE_TYPE, StorageType.INTERNAL.name)
            .apply()
    }

    /**
     * Формирует читаемое описание пути каталога SAF для интерфейса настроек.
     */
    /**
     * Возвращает отображаемое имя папки SAF (например, DicomImages).
     */
    fun getSafFolderDisplayName(treeUri: Uri): String {
        return try {
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            val name = doc?.name
            if (!name.isNullOrBlank()) return name
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val clean = docId.substringAfterLast(":")
            if (clean.isNotBlank()) clean else "Пользовательская папка"
        } catch (_: Exception) {
            "Пользовательская папка"
        }
    }

    /**
     * Возвращает человекочитаемое расположение каталога SAF (например, Внутренний накопитель / DicomImages).
     */
    fun getSafFolderHumanReadableLocation(treeUri: Uri): String {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            if (docId.startsWith("primary:", ignoreCase = true)) {
                val sub = docId.substringAfter("primary:")
                if (sub.isBlank()) "Внутренний накопитель (корень)"
                else "Внутренний накопитель / $sub"
            } else if (docId.contains(":")) {
                val volume = docId.substringBefore(":")
                val sub = docId.substringAfter(":")
                "Внешний накопитель ($volume) / $sub"
            } else {
                docId
            }
        } catch (_: Exception) {
            val uriDecoded = Uri.decode(treeUri.toString())
            uriDecoded.substringAfter("document/", "").substringAfter("tree/", "")
        }
    }

    /**
     * Формирует читаемое описание пути каталога SAF для интерфейса настроек.
     */
    fun getReadablePathForSaf(treeUri: Uri): String {
        val folder = getSafFolderDisplayName(treeUri)
        val location = getSafFolderHumanReadableLocation(treeUri)
        return if (location.isNotBlank() && location != folder) {
            "$folder ($location)"
        } else {
            folder
        }
    }

    /**
     * Возвращает актуальную информацию о текущем хранилище.
     */
    fun getStorageLocationInfo(
        totalBytes: Long,
        filesCount: Int,
        studiesCount: Int
    ): StorageLocationInfo {
        val type = getCurrentStorageType()
        return if (type == StorageType.SAF) {
            val uri = getSavedSafTreeUri()
            if (uri != null) {
                val writable = isSafStorageWritable(uri)
                val folder = getSafFolderDisplayName(uri)
                val location = getSafFolderHumanReadableLocation(uri)
                val display = getReadablePathForSaf(uri)
                val warning = if (!writable) {
                    "Внимание: Выбранная папка или SD-карта недоступна (отключена или отозвано разрешение). Новые файлы временно не могут быть записаны в SAF."
                } else null
                StorageLocationInfo(
                    type = StorageType.SAF,
                    displayPath = display,
                    folderName = folder,
                    readableLocation = location,
                    safTreeUriString = uri.toString(),
                    isAccessible = writable,
                    canWrite = writable,
                    totalBytes = totalBytes,
                    filesCount = filesCount,
                    studiesCount = studiesCount,
                    warningMessage = warning
                )
            } else {
                StorageLocationInfo(
                    type = StorageType.INTERNAL,
                    displayPath = internalStorageDir.absolutePath,
                    folderName = "received_dicom",
                    readableLocation = "Внутреннее хранилище приложения",
                    isAccessible = true,
                    canWrite = true,
                    totalBytes = totalBytes,
                    filesCount = filesCount,
                    studiesCount = studiesCount,
                    warningMessage = "SAF каталог не настроен. Используется внутреннее хранилище."
                )
            }
        } else {
            StorageLocationInfo(
                type = StorageType.INTERNAL,
                displayPath = internalStorageDir.absolutePath,
                folderName = "received_dicom",
                readableLocation = "Внутреннее хранилище приложения",
                isAccessible = true,
                canWrite = true,
                totalBytes = totalBytes,
                filesCount = filesCount,
                studiesCount = studiesCount,
                warningMessage = null
            )
        }
    }

    /**
     * Надёжно сохраняет входящий DICOM-файл из временного файла [tempIncomingFile]
     * в активное хранилище (Internal File или SAF DocumentFile) с проверкой успешности записи.
     */
    fun saveIncomingDicomFile(
        tempIncomingFile: File,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        sopInstanceUid: String
    ): DicomSaveResult {
        val currentType = getCurrentStorageType()
        return if (currentType == StorageType.SAF) {
            val safUri = getSavedSafTreeUri()
            if (safUri != null && isSafStorageWritable(safUri)) {
                saveToSafStorage(tempIncomingFile, safUri, studyInstanceUid, seriesInstanceUid, sopInstanceUid)
            } else {
                DicomSaveResult.Failure(
                    errorReason = "Пользовательский SAF-каталог недоступен (SD-карта отключена или отсутствуют права на запись)",
                    isOutOfSpace = false
                )
            }
        } else {
            saveToInternalStorage(tempIncomingFile, studyInstanceUid, seriesInstanceUid, sopInstanceUid)
        }
    }

    private val coordinator by lazy { StorageCoordinator.forRoot(internalStorageDir) }
    private val ownership by lazy { DocumentOwnership.forRoot(internalStorageDir) }

    private fun store(access: StorageAccess) = VerifiedDicomStore(access, ownership, coordinator)

    private fun saveToInternalStorage(file: File, study: String, series: String, sop: String): DicomSaveResult =
        store(FileStorageAccess(internalStorageDir)).save(file.storageSource(), study, series, sop)

    private fun saveToSafStorage(file: File, treeUri: Uri, study: String, series: String, sop: String): DicomSaveResult = try {
        store(SafStorageAccess(context, treeUri)).save(file.storageSource(), study, series, sop)
    } catch (e: Exception) { DicomSaveResult.Failure("SAF unavailable: ${e.message}", exception = e) }

    /** Only ledger-backed documents may be destructively operated on in an arbitrary SAF tree. */
    internal fun ownedSafDocuments(treeUri: Uri): List<OwnedDocument> = ownership.owned(treeUri.toString())

    fun clearOwnedSaf(studyInstanceUid: String? = null): Int {
        val treeUri = getSavedSafTreeUri() ?: return 0
        return try { store(SafStorageAccess(context, treeUri)).clearOwned(studyInstanceUid) }
        catch (_: Exception) { 0 }
    }

    /** Resume by content identity. A crash leaves the source or a verified destination (possibly both). */
    fun migrateFiles(fromInternalToSaf: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): MigrationResult =
        coordinator.keyed("migration") { migrateFilesLocked(fromInternalToSaf, onProgress) }

    // Opposite migrations must not each deduplicate against the other's source and then delete both.
    // This lock serializes migrations only; independent C-STORE operations still use shared receiving locks.
    private fun migrateFilesLocked(fromInternalToSaf: Boolean, onProgress: (Int, Int) -> Unit): MigrationResult {
        val treeUri = getSavedSafTreeUri() ?: return MigrationResult(0, 0, 0, listOf("SAF URI не настроен"))
        if (!isSafStorageWritable(treeUri)) return MigrationResult(0, 0, 0, listOf("Каталог SAF недоступен"))
        val errors = ArrayList<String>()
        var succeeded = 0
        var retainedSources = 0
        return try {
            val saf = SafStorageAccess(context, treeUri)
            if (fromInternalToSaf) {
                val sources = StoragePaths.dicomFiles(internalStorageDir).toList()
                sources.forEachIndexed { index, file ->
                    onProgress(index + 1, sources.size)
                    coordinator.receiving { coordinator.keyed("source:${file.canonicalPath}") {
                        try {
                            val ids = readIdentity { FileInputStream(file) }
                            val result = store(saf).save(file.storageSource(), ids[0], ids[1], ids[2])
                            if (result is DicomSaveResult.Success && result.sourceRetained) retainedSources++
                            if (result is DicomSaveResult.Success && !file.exists()) succeeded++
                            else errors.add("${file.name}: ${if (result is DicomSaveResult.Failure) result.errorReason else RETAINED_SOURCE_WARNING}")
                        } catch (e: Exception) { errors.add("${file.name}: ${e.message}") }
                    } }
                }
                MigrationResult(sources.size, succeeded, sources.size - succeeded, errors, retainedSources)
            } else {
                // Existing legacy/user documents are not provably ours: do not remove or migrate them.
                val sources = ownedSafDocuments(treeUri)
                sources.forEachIndexed { index, doc ->
                    onProgress(index + 1, sources.size)
                    coordinator.receiving { coordinator.keyed("source:${doc.scope}:${doc.id}") {
                        try {
                            val node = StorageNode(doc.id, doc.name, false)
                            require(VerifiedDicomStore.fingerprint { saf.read(node) } == doc.digest) { "SAF source changed; retained" }
                            var removed = false
                            val source = StorageSource({ saf.read(node) }, {
                                if (VerifiedDicomStore.fingerprint { saf.read(node) } != doc.digest) false
                                else saf.deleteFile(node).also { removed = it }
                            })
                            val result = store(FileStorageAccess(internalStorageDir)).save(source, doc.study, doc.series, doc.sop)
                            if (result is DicomSaveResult.Success && result.sourceRetained) retainedSources++
                            if (result is DicomSaveResult.Success && removed) {
                                ownership.forget(doc.scope, doc.id); succeeded++
                            } else errors.add("${doc.name}: ${if (result is DicomSaveResult.Failure) result.errorReason else RETAINED_SOURCE_WARNING}")
                        } catch (e: Exception) { errors.add("${doc.name}: ${e.message}") }
                    } }
                }
                MigrationResult(sources.size, succeeded, sources.size - succeeded, errors, retainedSources)
            }
        } catch (e: Exception) { MigrationResult(0, 0, 1, listOf(e.message ?: "Ошибка миграции")) }
    }

    private fun readIdentity(open: () -> InputStream): List<String> = open().use { input ->
        DicomInputStream(input).use { dis ->
            dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
            dis.readFileMetaInformation()
            val attrs = dis.readDatasetUntilPixelData()
            listOf(Tag.StudyInstanceUID, Tag.SeriesInstanceUID, Tag.SOPInstanceUID).map {
                StoragePaths.uid(attrs.getString(it) ?: throw IOException("Missing DICOM UID"))
            }
        }
    }
}
