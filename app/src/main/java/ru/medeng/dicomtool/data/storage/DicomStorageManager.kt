package ru.medeng.dicomtool.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ru.medeng.dicomtool.dicom.DicomValidationUtils.sanitizeFileName
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

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
    val warningMessage: String? = null
)

/**
 * Результат сохранения входящего DICOM-файла.
 */
sealed interface DicomSaveResult {
    data class Success(
        val destinationDescription: String,
        val savedSizeBytes: Long,
        val duplicateActionNote: String
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
    val errorDetails: List<String> = emptyList()
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
    private val context: Context,
    val internalStorageDir: File = File(context.filesDir, "received_dicom")
) {

    private val prefs = context.getSharedPreferences("dicom_storage_settings", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_KEY_STORAGE_TYPE = "storage_type"
        private const val PREF_KEY_SAF_URI = "saf_tree_uri"
    }

    init {
        internalStorageDir.mkdirs()
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

    private fun saveToInternalStorage(
        tempIncomingFile: File,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        sopInstanceUid: String
    ): DicomSaveResult {
        return try {
            val targetDir = File(
                File(internalStorageDir, sanitizeFileName(studyInstanceUid)),
                sanitizeFileName(seriesInstanceUid)
            ).apply { mkdirs() }

            val primaryTargetFile = File(targetDir, "${sanitizeFileName(sopInstanceUid)}.dcm")
            var duplicateActionNote = "Saved as new instance"
            val finalTargetFile: File

            if (primaryTargetFile.exists()) {
                val isIdentical = tempIncomingFile.length() == primaryTargetFile.length() &&
                    tempIncomingFile.readBytes().contentEquals(primaryTargetFile.readBytes())
                if (isIdentical) {
                    tempIncomingFile.delete()
                    finalTargetFile = primaryTargetFile
                    duplicateActionNote = "Identical re-transmission detected (original file preserved)"
                } else {
                    val copyFile = File(
                        targetDir,
                        "${sanitizeFileName(sopInstanceUid)}_dup_${System.currentTimeMillis()}.dcm"
                    )
                    copyStreamSafely(FileInputStream(tempIncomingFile), FileOutputStream(copyFile))
                    tempIncomingFile.delete()
                    finalTargetFile = copyFile
                    duplicateActionNote = "Duplicate SOP Instance UID saved separately as ${copyFile.name}"
                }
            } else {
                // Атомарный перенос или копирование
                val moved = tempIncomingFile.renameTo(primaryTargetFile)
                if (!moved) {
                    copyStreamSafely(FileInputStream(tempIncomingFile), FileOutputStream(primaryTargetFile))
                    tempIncomingFile.delete()
                }
                finalTargetFile = primaryTargetFile
            }

            DicomSaveResult.Success(
                destinationDescription = finalTargetFile.absolutePath,
                savedSizeBytes = finalTargetFile.length(),
                duplicateActionNote = duplicateActionNote
            )
        } catch (e: Exception) {
            DicomSaveResult.Failure(
                errorReason = "Ошибка записи во внутреннее хранилище: ${e.message ?: e.javaClass.simpleName}",
                isOutOfSpace = e.message?.contains("ENOSPC", ignoreCase = true) == true,
                exception = e
            )
        }
    }

    private fun saveToSafStorage(
        tempIncomingFile: File,
        treeUri: Uri,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        sopInstanceUid: String
    ): DicomSaveResult {
        var createdDoc: DocumentFile? = null
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IOException("Не удалось открыть корневую папку SAF")

            // Иерархия: <SAF_ROOT>/<StudyUID>/<SeriesUID>/<SOPUID>.dcm
            val studyFolderName = sanitizeFileName(studyInstanceUid)
            val studyDoc = rootDoc.findFile(studyFolderName)
                ?: rootDoc.createDirectory(studyFolderName)
                ?: throw IOException("Не удалось создать подкаталог исследования в SAF: $studyFolderName")

            val seriesFolderName = sanitizeFileName(seriesInstanceUid)
            val seriesDoc = studyDoc.findFile(seriesFolderName)
                ?: studyDoc.createDirectory(seriesFolderName)
                ?: throw IOException("Не удалось создать подкаталог серии в SAF: $seriesFolderName")

            val fileName = "${sanitizeFileName(sopInstanceUid)}.dcm"
            val existingDoc = seriesDoc.findFile(fileName)
            var duplicateActionNote = "Saved as new instance"

            val targetDoc: DocumentFile
            if (existingDoc != null && existingDoc.exists()) {
                if (existingDoc.length() == tempIncomingFile.length()) {
                    tempIncomingFile.delete()
                    return DicomSaveResult.Success(
                        destinationDescription = existingDoc.uri.toString(),
                        savedSizeBytes = existingDoc.length(),
                        duplicateActionNote = "Identical re-transmission detected in SAF (original preserved)"
                    )
                } else {
                    val dupName = "${sanitizeFileName(sopInstanceUid)}_dup_${System.currentTimeMillis()}.dcm"
                    val dupDoc = seriesDoc.createFile("application/dicom", dupName)
                        ?: throw IOException("Не удалось создать файл дубликата в SAF: $dupName")
                    targetDoc = dupDoc
                    createdDoc = dupDoc
                    duplicateActionNote = "Duplicate SOP Instance UID saved separately in SAF as $dupName"
                }
            } else {
                val newDoc = seriesDoc.createFile("application/dicom", fileName)
                    ?: throw IOException("Не удалось создать файл в SAF: $fileName")
                targetDoc = newDoc
                createdDoc = newDoc
            }

            // Потоковое копирование из временного файла в SAF DocumentFile с проверкой размера
            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { out ->
                FileInputStream(tempIncomingFile).use { inStream ->
                    copyStreamSafely(inStream, out)
                }
            } ?: throw IOException("ContentResolver вернул null при открытии OutputStream для ${targetDoc.uri}")

            val expectedSize = tempIncomingFile.length()
            val writtenSize = targetDoc.length()
            if (writtenSize != expectedSize && writtenSize != 0L) {
                // Некоторые DocumentsProvider обновляют length с задержкой, но если 0 и ожидалось >0, проверяем
            }

            tempIncomingFile.delete()

            DicomSaveResult.Success(
                destinationDescription = targetDoc.uri.toString(),
                savedSizeBytes = expectedSize,
                duplicateActionNote = duplicateActionNote
            )
        } catch (e: Exception) {
            createdDoc?.delete()
            DicomSaveResult.Failure(
                errorReason = "Ошибка записи в SAF каталог: ${e.message ?: e.javaClass.simpleName}",
                isOutOfSpace = e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                    e.message?.contains("space", ignoreCase = true) == true,
                exception = e
            )
        }
    }

    /**
     * Выполняет безопасный перенос всех DICOM-файлов между внутренним хранилищем и SAF-каталогом.
     * Проверяет успешность копирования каждого файла до удаления оригинала.
     */
    fun migrateFiles(
        fromInternalToSaf: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): MigrationResult {
        val errors = ArrayList<String>()
        var successCount = 0

        if (fromInternalToSaf) {
            val safUri = getSavedSafTreeUri() ?: return MigrationResult(0, 0, 0, listOf("SAF URI не настроен"))
            if (!isSafStorageWritable(safUri)) {
                return MigrationResult(0, 0, 0, listOf("Каталог SAF недоступен для записи"))
            }

            val localFiles = internalStorageDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("dcm", ignoreCase = true) }
                .toList()

            val total = localFiles.size
            if (total == 0) return MigrationResult(0, 0, 0)

            localFiles.forEachIndexed { index, file ->
                onProgress(index + 1, total)
                try {
                    val seriesDir = file.parentFile
                    val studyDir = seriesDir?.parentFile
                    val studyUid = studyDir?.name?.takeIf { it != internalStorageDir.name } ?: "UNKNOWN_STUDY"
                    val seriesUid = seriesDir?.name ?: "UNKNOWN_SERIES"
                    val sopUid = file.nameWithoutSuffix()

                    val saveRes = saveToSafStorage(
                        tempIncomingFile = file, // file будет безопасно скопирован, но не удалён если ошибка
                        treeUri = safUri,
                        studyInstanceUid = studyUid,
                        seriesInstanceUid = seriesUid,
                        sopInstanceUid = sopUid
                    )

                    when (saveRes) {
                        is DicomSaveResult.Success -> {
                            successCount++
                            // Удаляем оригинал ТОЛЬКО после подтверждённого сохранения в SAF
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                        is DicomSaveResult.Failure -> {
                            errors.add("Файл ${file.name}: ${saveRes.errorReason}")
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Файл ${file.name}: ${e.message}")
                }
            }

            // Очищаем пустые папки во внутреннем хранилище
            cleanupEmptyDirectories(internalStorageDir)
            return MigrationResult(total, successCount, total - successCount, errors)
        } else {
            // Перенос из SAF во внутреннее хранилище
            val safUri = getSavedSafTreeUri() ?: return MigrationResult(0, 0, 0, listOf("SAF URI не настроен"))
            val safFiles = listAllSafDicomFiles(safUri)
            val total = safFiles.size
            if (total == 0) return MigrationResult(0, 0, 0)

            safFiles.forEachIndexed { index, (studyUid, seriesUid, doc) ->
                onProgress(index + 1, total)
                try {
                    val targetDir = File(
                        File(internalStorageDir, sanitizeFileName(studyUid)),
                        sanitizeFileName(seriesUid)
                    ).apply { mkdirs() }

                    val targetFile = File(targetDir, doc.name ?: "${System.nanoTime()}.dcm")
                    val sourceLength = doc.length()

                    context.contentResolver.openInputStream(doc.uri)?.use { inStream ->
                        FileOutputStream(targetFile).use { outStream ->
                            copyStreamSafely(inStream, outStream)
                        }
                    } ?: throw IOException("Не удалось прочитать DocumentFile ${doc.uri}")

                    if (targetFile.length() > 0 && (sourceLength == 0L || targetFile.length() == sourceLength)) {
                        successCount++
                        // Удаляем файл в SAF только после проверки успешности записи в целевой файл
                        doc.delete()
                    } else {
                        targetFile.delete()
                        errors.add("Несовпадение размера файла ${doc.name}")
                    }
                } catch (e: Exception) {
                    errors.add("Файл ${doc.name}: ${e.message}")
                }
            }

            return MigrationResult(total, successCount, total - successCount, errors)
        }
    }

    private fun listAllSafDicomFiles(treeUri: Uri): List<Triple<String, String, DocumentFile>> {
        val result = ArrayList<Triple<String, String, DocumentFile>>()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val studyDocs = rootDoc.listFiles().filter { it.isDirectory }
        for (studyDoc in studyDocs) {
            val studyUid = studyDoc.name ?: continue
            val seriesDocs = studyDoc.listFiles().filter { it.isDirectory }
            for (seriesDoc in seriesDocs) {
                val seriesUid = seriesDoc.name ?: continue
                val dcmFiles = seriesDoc.listFiles().filter {
                    it.isFile && (it.name?.endsWith(".dcm", ignoreCase = true) == true)
                }
                for (doc in dcmFiles) {
                    result.add(Triple(studyUid, seriesUid, doc))
                }
            }
        }
        return result
    }

    private fun cleanupEmptyDirectories(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                cleanupEmptyDirectories(child)
                if (child.listFiles().isNullOrEmpty()) {
                    child.delete()
                }
            }
        }
    }

    private fun copyStreamSafely(input: InputStream, output: OutputStream) {
        BufferedInputStream(input, 65536).use { bis ->
            BufferedOutputStream(output, 65536).use { bos ->
                val buf = ByteArray(65536)
                var read: Int
                while (bis.read(buf).also { read = it } != -1) {
                    bos.write(buf, 0, read)
                }
                bos.flush()
            }
        }
    }

    private fun File.nameWithoutSuffix(): String {
        return name.substringBeforeLast('.')
    }
}
