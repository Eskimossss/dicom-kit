package ru.medeng.dicomtool.dicom.scu

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.domain.model.ScuCandidateFile
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Сканер и верификатор DICOM-файлов для отправки через C-STORE SCU (Этап 4.2).
 *
 * - Проверяет фактическое содержимое файлов (наличие маркера DICM или валидного raw DICOM Data Set).
 * - Поддерживает файлы .dcm, .dicom и без расширения.
 * - Читает только метаданные через dcm4che (includeBulkData = NO) без загрузки Pixel Data в RAM.
 * - Извлекает SOP Class UID, SOP Instance UID, Transfer Syntax UID, Modality, Patient ID, Study Instance UID.
 * - Не генерирует новые UID и не изменяет исходные свойства файлов.
 * - Поддерживает рекурсивное сканирование папок через SAF с прогрессом и отменой.
 */
object DicomFileScanner {

    private const val BUFFER_SIZE = 131072 // 128 КБ

    private val STANDARD_VRS = setOf(
        "AE", "AS", "AT", "CS", "DA", "DS", "DT", "FD", "FL", "IS", "LO", "LT",
        "OB", "OD", "OF", "OL", "OV", "OW", "PN", "SH", "SL", "SQ", "SS", "ST",
        "SV", "TM", "UC", "UI", "UL", "UN", "UR", "US", "UT", "UV"
    )

    private val IGNORED_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "webp",
        "txt", "pdf", "doc", "docx", "xls", "xlsx",
        "zip", "rar", "7z", "tar", "gz",
        "mp3", "mp4", "avi", "mkv",
        "json", "xml", "html", "htm", "css", "js"
    )

    /**
     * Выполняет предварительную проверку и извлечение метаданных для одного Uri.
     */
    fun inspectUri(
        context: Context,
        uri: Uri,
        fallbackName: String? = null,
        fallbackSize: Long = 0L
    ): ScuCandidateFile {
        val (resolvedName, resolvedSize) = queryUriMetadata(context.contentResolver, uri)
        val fileName = resolvedName ?: fallbackName ?: "image.dcm"
        val fileSize = if (resolvedSize > 0L) resolvedSize else fallbackSize

        val inStream: InputStream
        try {
            inStream = context.contentResolver.openInputStream(uri)
                ?: return ScuCandidateFile(
                    uri = uri,
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    isValidDicom = false,
                    validationError = "Не удалось открыть файл для чтения (доступ заблокирован или файл удалён)"
                )
        } catch (e: Exception) {
            return ScuCandidateFile(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = fileSize,
                isValidDicom = false,
                validationError = "Ошибка доступа к файлу: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        return inStream.use { rawIn ->
            inspectInputStream(rawIn, uri, fileName, fileSize)
        }
    }

    /**
     * Проверяет и извлекает метаданные из произвольного InputStream без использования Android Context.
     */
    fun inspectInputStream(
        rawIn: InputStream,
        uri: Uri? = null,
        fileName: String,
        fileSize: Long
    ): ScuCandidateFile {
        val bufferedIn = BufferedInputStream(rawIn, BUFFER_SIZE)
        bufferedIn.mark(BUFFER_SIZE)

        // 1. Проверяем наличие преамбулы (128 байт) и маркера 'DICM'
        val preamble = ByteArray(132)
        var readBytes = 0
        while (readBytes < 132) {
            val r = bufferedIn.read(preamble, readBytes, 132 - readBytes)
            if (r <= 0) break
            readBytes += r
        }

        val hasDicm = readBytes >= 132 &&
            preamble[128] == 'D'.code.toByte() &&
            preamble[129] == 'I'.code.toByte() &&
            preamble[130] == 'C'.code.toByte() &&
            preamble[131] == 'M'.code.toByte()

        bufferedIn.reset()

        return try {
            if (hasDicm) {
                parsePart10File(bufferedIn, uri, fileName, fileSize)
            } else {
                parseRawStream(bufferedIn, preamble.copyOf(readBytes), uri, fileName, fileSize)
            }
        } catch (e: Exception) {
            ScuCandidateFile(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = fileSize,
                isValidDicom = false,
                validationError = "Не является DICOM или ошибка разбора: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun parsePart10File(
        bufferedIn: BufferedInputStream,
        uri: Uri?,
        fileName: String,
        fileSize: Long
    ): ScuCandidateFile {
        DicomInputStream(bufferedIn).use { dis ->
            dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
            val fmi: Attributes? = dis.readFileMetaInformation()
            val dataset: Attributes = dis.readDatasetUntilPixelData()

            val fmiTs = fmi?.getString(Tag.TransferSyntaxUID)
            val detectedTs = dis.transferSyntax ?: fmiTs ?: UID.ExplicitVRLittleEndian

            return extractCandidateData(
                uri = uri,
                fileName = fileName,
                fileSize = fileSize,
                fmi = fmi,
                dataset = dataset,
                transferSyntaxUid = detectedTs
            )
        }
    }

    private fun parseRawStream(
        bufferedIn: BufferedInputStream,
        headerSample: ByteArray,
        uri: Uri?,
        fileName: String,
        fileSize: Long
    ): ScuCandidateFile {
        val isExplicitVR = isExplicitVRSample(headerSample)
        val guessedSyntax = if (isExplicitVR) UID.ExplicitVRLittleEndian else UID.ImplicitVRLittleEndian

        return try {
            DicomInputStream(bufferedIn, guessedSyntax).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                val fmi: Attributes? = dis.readFileMetaInformation()
                val dataset: Attributes = dis.readDatasetUntilPixelData()

                extractCandidateData(
                    uri = uri,
                    fileName = fileName,
                    fileSize = fileSize,
                    fmi = fmi,
                    dataset = dataset,
                    transferSyntaxUid = dis.transferSyntax ?: guessedSyntax
                )
            }
        } catch (e: Exception) {
            // Если предположение Explicit VR не удалось, пробуем Implicit VR
            if (isExplicitVR) {
                bufferedIn.reset()
                DicomInputStream(bufferedIn, UID.ImplicitVRLittleEndian).use { dis ->
                    dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                    val fmi: Attributes? = dis.readFileMetaInformation()
                    val dataset: Attributes = dis.readDatasetUntilPixelData()

                    extractCandidateData(
                        uri = uri,
                        fileName = fileName,
                        fileSize = fileSize,
                        fmi = fmi,
                        dataset = dataset,
                        transferSyntaxUid = dis.transferSyntax ?: UID.ImplicitVRLittleEndian
                    )
                }
            } else {
                throw e
            }
        }
    }

    private fun isExplicitVRSample(sample: ByteArray): Boolean {
        if (sample.size < 6) return false
        val vrStr = String(sample, 4, 2, StandardCharsets.US_ASCII)
        return vrStr in STANDARD_VRS
    }

    private fun extractCandidateData(
        uri: Uri?,
        fileName: String,
        fileSize: Long,
        fmi: Attributes?,
        dataset: Attributes,
        transferSyntaxUid: String
    ): ScuCandidateFile {
        val sopClassUid = dataset.getString(Tag.SOPClassUID)
            ?: fmi?.getString(Tag.MediaStorageSOPClassUID)

        val sopInstanceUid = dataset.getString(Tag.SOPInstanceUID)
            ?: fmi?.getString(Tag.MediaStorageSOPInstanceUID)

        if (sopClassUid.isNullOrBlank() || sopInstanceUid.isNullOrBlank()) {
            return ScuCandidateFile(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = fileSize,
                isValidDicom = false,
                validationError = "Отсутствует SOP Class UID (0008,0016) или SOP Instance UID (0008,0018)"
            )
        }

        val studyInstanceUid = dataset.getString(Tag.StudyInstanceUID)
        if (studyInstanceUid.isNullOrBlank()) {
            return ScuCandidateFile(
                uri = uri,
                fileName = fileName,
                fileSizeBytes = fileSize,
                isValidDicom = false,
                validationError = "Отсутствует обязательный Study Instance UID (0020,000D)"
            )
        }

        val seriesInstanceUid = dataset.getString(Tag.SeriesInstanceUID)
        val modality = dataset.getString(Tag.Modality) ?: "OT"
        val patientId = dataset.getString(Tag.PatientID) ?: "N/A"

        val decodedPatientName = DicomCharsetDecoder.decodeStringAttribute(
            attrs = dataset,
            tag = Tag.PatientName,
            vr = org.dcm4che3.data.VR.PN,
            defaultVal = "N/A"
        ).value

        val sopClassName = resolveUidName(sopClassUid)
        val transferSyntaxName = resolveUidName(transferSyntaxUid)

        return ScuCandidateFile(
            uri = uri,
            fileName = fileName,
            fileSizeBytes = fileSize,
            isValidDicom = true,
            sopClassUid = sopClassUid,
            sopClassName = sopClassName,
            sopInstanceUid = sopInstanceUid,
            studyInstanceUid = studyInstanceUid,
            seriesInstanceUid = seriesInstanceUid,
            transferSyntaxUid = transferSyntaxUid,
            transferSyntaxName = transferSyntaxName,
            modality = modality,
            patientId = patientId,
            patientName = decodedPatientName,
            validationError = null
        )
    }

    private fun resolveUidName(uid: String): String {
        return try {
            UID.nameOf(uid) ?: uid
        } catch (_: Exception) {
            uid
        }
    }

    private fun queryUriMetadata(resolver: ContentResolver, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size: Long = 0L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        } else if (uri.scheme == "file") {
            name = uri.lastPathSegment
            uri.path?.let { p ->
                val f = java.io.File(p)
                if (f.exists()) size = f.length()
            }
        }
        return Pair(name, size)
    }

    /**
     * Выполняет рекурсивное сканирование каталога через Storage Access Framework (SAF).
     * Безопасно обходит вложенные папки, фильтрует не-DICOM файлы и уведомляет о прогрессе.
     */
    fun scanTreeFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (scanned: Int, foundDicom: Int, currentFolder: String?) -> Unit,
        isCancelled: () -> Boolean
    ): List<ScuCandidateFile> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val results = mutableListOf<ScuCandidateFile>()
        val queue = ArrayDeque<DocumentFile>()
        queue.add(rootDoc)

        var totalScanned = 0

        while (queue.isNotEmpty() && !isCancelled()) {
            val currentDir = queue.removeFirst()
            val folderName = currentDir.name
            onProgress(totalScanned, results.size, folderName)

            val children = try {
                currentDir.listFiles()
            } catch (_: Exception) {
                emptyArray()
            }

            for (child in children) {
                if (isCancelled()) break
                if (child.isDirectory) {
                    queue.add(child)
                } else if (child.isFile) {
                    totalScanned++
                    val childName = child.name ?: "file"
                    val ext = childName.substringAfterLast('.', "").lowercase()

                    // Пропускаем заведомо не-DICOM форматы
                    if (ext in IGNORED_EXTENSIONS) {
                        onProgress(totalScanned, results.size, folderName)
                        continue
                    }

                    val candidate = inspectUri(
                        context = context,
                        uri = child.uri,
                        fallbackName = childName,
                        fallbackSize = child.length()
                    )

                    // В папках собираем только валидные DICOM-файлы, не засоряя список системным мусором
                    if (candidate.isValidDicom) {
                        results.add(candidate)
                    }

                    onProgress(totalScanned, results.size, folderName)
                }
            }
        }

        return results
    }
}
