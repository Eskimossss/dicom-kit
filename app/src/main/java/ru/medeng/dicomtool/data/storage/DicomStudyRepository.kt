package ru.medeng.dicomtool.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.ElementDictionary
import org.dcm4che3.data.Sequence
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.util.TagUtils
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.domain.model.DicomInstanceFileItem
import ru.medeng.dicomtool.domain.model.DicomSeriesGroup
import ru.medeng.dicomtool.domain.model.DicomStudyItem
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Контракт локального хранилища реально принятых DICOM-исследований.
 */
interface DicomStudyRepository {
    val studiesFlow: StateFlow<List<DicomStudyItem>>
    val storageRootDir: File
    val storageManager: DicomStorageManager? get() = null
    fun refreshFromDisk()
    fun getTagsForInstanceFile(filePath: String): List<DicomTagEntry>
    fun getTagsForStudy(studyInstanceUid: String, seriesInstanceUid: String? = null, instanceIndex: Int = 0): List<DicomTagEntry>
    fun deleteStudy(studyInstanceUid: String): Boolean
    fun clearAllStudies(): Int
    fun getTotalStorageBytes(): Long
    fun getTotalSavedFilesCount(): Int
    fun saveIncomingInstance(
        tempIncomingFile: File,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        sopInstanceUid: String
    ): DicomSaveResult
}

/**
 * Реализация репозитория принятых DICOM Part 10 файлов.
 *
 * Поддерживает:
 * - Внутреннее хранилище приложения (`storageRootDir`).
 * - Пользовательские каталоги SAF / SD-карты через [DicomStorageManager] без потери ранее сохранённых исследований.
 * - Чтение только заголовков (`readDatasetUntilPixelData`), не загружая Pixel Data в память при индексации.
 * - [DicomCharsetDecoder] для корректного декодирования Specific Character Set (0008,0005) и кириллицы.
 * - Извлечение полного списка DICOM-тегов (FMI + Dataset + SQ).
 */
class LocalDicomStudyRepository(
    override val storageRootDir: File,
    val context: Context? = null,
    override val storageManager: DicomStorageManager? = null
) : DicomStudyRepository {

    private val lock = ReentrantLock()
    private val _studiesFlow = MutableStateFlow<List<DicomStudyItem>>(emptyList())
    override val studiesFlow: StateFlow<List<DicomStudyItem>> = _studiesFlow.asStateFlow()

    init {
        storageRootDir.mkdirs()
        refreshFromDisk()
    }

    override fun saveIncomingInstance(
        tempIncomingFile: File,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        sopInstanceUid: String
    ): DicomSaveResult {
        return if (storageManager != null) {
            storageManager.saveIncomingDicomFile(
                tempIncomingFile = tempIncomingFile,
                studyInstanceUid = studyInstanceUid,
                seriesInstanceUid = seriesInstanceUid,
                sopInstanceUid = sopInstanceUid
            )
        } else {
            // Резервное сохранение во внутреннее хранилище
            val targetDir = File(
                File(storageRootDir, sanitizeFolder(studyInstanceUid)),
                sanitizeFolder(seriesInstanceUid)
            ).apply { mkdirs() }
            val primaryTarget = File(targetDir, "${sanitizeFolder(sopInstanceUid)}.dcm")
            val finalTarget: File
            val duplicateNote: String

            if (primaryTarget.exists()) {
                val isIdentical = tempIncomingFile.length() == primaryTarget.length() &&
                    tempIncomingFile.readBytes().contentEquals(primaryTarget.readBytes())
                if (isIdentical) {
                    tempIncomingFile.delete()
                    finalTarget = primaryTarget
                    duplicateNote = "Identical re-transmission detected"
                } else {
                    val copyFile = File(targetDir, "${sanitizeFolder(sopInstanceUid)}_dup_${System.currentTimeMillis()}.dcm")
                    val moved = tempIncomingFile.renameTo(copyFile)
                    if (!moved) {
                        tempIncomingFile.copyTo(copyFile, overwrite = true)
                        tempIncomingFile.delete()
                    }
                    finalTarget = copyFile
                    duplicateNote = "Duplicate SOP Instance UID saved separately as ${copyFile.name}"
                }
            } else {
                val moved = tempIncomingFile.renameTo(primaryTarget)
                if (!moved) {
                    tempIncomingFile.copyTo(primaryTarget, overwrite = true)
                    tempIncomingFile.delete()
                }
                finalTarget = primaryTarget
                duplicateNote = "Saved as new instance"
            }

            DicomSaveResult.Success(
                destinationDescription = finalTarget.absolutePath,
                savedSizeBytes = finalTarget.length(),
                duplicateActionNote = duplicateNote
            )
        }
    }

    override fun refreshFromDisk() {
        lock.withLock {
            val parsedRecords = ArrayList<ParsedRecord>()

            // 1. Сканируем внутреннее хранилище (если существует)
            if (storageRootDir.exists()) {
                val internalFiles = storageRootDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("dcm", ignoreCase = true) }
                    .toList()

                for (file in internalFiles) {
                    val rec = parseSummaryFromSource(
                        path = file.absolutePath,
                        displayName = file.name,
                        fileSize = file.length(),
                        lastModified = file.lastModified(),
                        streamOpener = { FileInputStream(file) }
                    )
                    if (rec != null) parsedRecords.add(rec)
                }
            }

            // 2. Сканируем настроенный SAF каталог (если настроен, доступен и context != null)
            val safUri = storageManager?.getSavedSafTreeUri()
            if (context != null && safUri != null && storageManager?.isSafStorageWritable(safUri) == true) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, safUri)
                    if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                        for (studyDoc in rootDoc.listFiles().filter { it.isDirectory }) {
                            for (seriesDoc in studyDoc.listFiles().filter { it.isDirectory }) {
                                val dcmDocs = seriesDoc.listFiles().filter {
                                    it.isFile && (it.name?.endsWith(".dcm", ignoreCase = true) == true)
                                }
                                for (doc in dcmDocs) {
                                    val rec = parseSummaryFromSource(
                                        path = doc.uri.toString(),
                                        displayName = doc.name ?: "image.dcm",
                                        fileSize = doc.length(),
                                        lastModified = doc.lastModified(),
                                        streamOpener = { context.contentResolver.openInputStream(doc.uri) }
                                    )
                                    if (rec != null) parsedRecords.add(rec)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ошибки сканирования SAF (например временное отключение карты) не ломают чтение внутреннего хранилища
                }
            }

            if (parsedRecords.isEmpty()) {
                _studiesFlow.value = emptyList()
                return
            }

            // Группируем по StudyInstanceUID
            val groupedByStudy = parsedRecords.groupBy { it.studyInstanceUid }

            val studyItems = groupedByStudy.map { (studyUid, studyRecords) ->
                val primary = studyRecords.first()
                val groupedBySeries = studyRecords.groupBy { it.seriesInstanceUid }

                val seriesGroups = groupedBySeries.map { (seriesUid, seriesRecords) ->
                    val firstInSeries = seriesRecords.first()
                    val sortedInstances = seriesRecords
                        .sortedWith(compareBy({ it.instanceNumber }, { it.sopInstanceUid }, { it.displayName }))
                        .map { rec ->
                            DicomInstanceFileItem(
                                sopInstanceUid = rec.sopInstanceUid,
                                sopClassUid = rec.sopClassUid,
                                transferSyntaxUid = rec.transferSyntaxUid,
                                instanceNumber = rec.instanceNumber,
                                rows = rec.rows,
                                columns = rec.columns,
                                bitsAllocated = rec.bitsAllocated,
                                bitsStored = rec.bitsStored,
                                highBit = rec.highBit,
                                pixelRepresentation = rec.pixelRepresentation,
                                samplesPerPixel = rec.samplesPerPixel,
                                photometricInterpretation = rec.photometricInterpretation,
                                planarConfiguration = rec.planarConfiguration,
                                numberOfFrames = rec.numberOfFrames,
                                windowWidth = rec.windowWidth,
                                windowCenter = rec.windowCenter,
                                rescaleSlope = rec.rescaleSlope,
                                rescaleIntercept = rec.rescaleIntercept,
                                specificCharacterSet = rec.specificCharacterSet,
                                encodingWarning = rec.encodingWarning,
                                filePath = rec.path,
                                fileSizeBytes = rec.fileSize,
                                duplicateCopyCount = seriesRecords.count { it.sopInstanceUid == rec.sopInstanceUid } - 1
                            )
                        }
                    DicomSeriesGroup(
                        seriesInstanceUid = seriesUid,
                        seriesNumber = firstInSeries.seriesNumber,
                        modality = firstInSeries.modality,
                        seriesDescription = firstInSeries.seriesDescription,
                        instances = sortedInstances
                    )
                }.sortedBy { it.seriesNumber }

                val totalBytes = studyRecords.sumOf { it.fileSize }
                val firstEncodingWarning = studyRecords.firstNotNullOfOrNull { it.encodingWarning }

                DicomStudyItem(
                    studyInstanceUid = studyUid,
                    patientName = primary.patientName,
                    patientId = primary.patientId,
                    patientBirthDate = primary.patientBirthDate,
                    patientSex = primary.patientSex,
                    modality = primary.modality,
                    studyDateFormatted = primary.studyDateFormatted,
                    studyDescription = primary.studyDescription,
                    seriesDescription = primary.seriesDescription,
                    seriesCount = seriesGroups.size,
                    instancesCount = studyRecords.size,
                    manufacturer = primary.manufacturer,
                    institutionName = primary.institutionName,
                    rows = primary.rows,
                    columns = primary.columns,
                    bitsAllocated = primary.bitsAllocated,
                    bitsStored = primary.bitsStored,
                    highBit = primary.highBit,
                    pixelRepresentation = primary.pixelRepresentation,
                    photometricInterpretation = primary.photometricInterpretation,
                    windowWidth = primary.windowWidth,
                    windowCenter = primary.windowCenter,
                    rescaleSlope = primary.rescaleSlope,
                    rescaleIntercept = primary.rescaleIntercept,
                    specificCharacterSet = primary.specificCharacterSet,
                    encodingWarning = firstEncodingWarning,
                    sopClassUid = primary.sopClassUid,
                    sopInstanceUid = primary.sopInstanceUid,
                    seriesInstanceUid = primary.seriesInstanceUid,
                    totalBytes = totalBytes,
                    seriesList = seriesGroups
                )
            }.sortedByDescending { it.studyDateFormatted }

            _studiesFlow.value = studyItems
        }
    }

    override fun getTagsForStudy(
        studyInstanceUid: String,
        seriesInstanceUid: String?,
        instanceIndex: Int
    ): List<DicomTagEntry> {
        val study = _studiesFlow.value.firstOrNull { it.studyInstanceUid == studyInstanceUid }
            ?: return emptyList()
        val series = if (seriesInstanceUid != null) {
            study.seriesList.firstOrNull { it.seriesInstanceUid == seriesInstanceUid }
        } else {
            study.seriesList.firstOrNull()
        } ?: return emptyList()

        val instance = series.instances.getOrNull(instanceIndex.coerceIn(0, (series.instances.size - 1).coerceAtLeast(0)))
            ?: series.instances.firstOrNull()
            ?: return emptyList()

        return getTagsForInstanceFile(instance.filePath)
    }

    override fun getTagsForInstanceFile(filePath: String): List<DicomTagEntry> {
        val file = if (!filePath.startsWith("content://")) File(filePath) else null
        if (file != null && (!file.exists() || !file.isFile)) return emptyList()

        val streamOpener: () -> InputStream? = if (filePath.startsWith("content://") && context != null) {
            { context.contentResolver.openInputStream(Uri.parse(filePath)) }
        } else {
            { file?.let { FileInputStream(it) } }
        }

        return try {
            val rawStream = streamOpener() ?: return emptyList()
            DicomInputStream(BufferedInputStream(rawStream, 65536)).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                val fmi: Attributes? = dis.readFileMetaInformation()
                val dataset: Attributes = dis.readDatasetUntilPixelData()
                val pixelDataTagPresent = dis.tag() == Tag.PixelData
                val pixelDataVr = if (pixelDataTagPresent) dis.vr()?.name ?: "OW" else null
                val pixelDataLength = if (pixelDataTagPresent) dis.unsignedLength() else -1L

                val declaredCharsetTerms = DicomCharsetDecoder.getDeclaredSpecificCharacterSets(dataset)
                val result = ArrayList<DicomTagEntry>()
                val dict = ElementDictionary.getStandardElementDictionary()

                if (fmi != null) {
                    extractAttributesToTagList(fmi, dict, emptyList(), result)
                }
                extractAttributesToTagList(dataset, dict, declaredCharsetTerms, result)

                if (pixelDataTagPresent) {
                    val rows = dataset.getInt(Tag.Rows, 0)
                    val cols = dataset.getInt(Tag.Columns, 0)
                    val frames = dataset.getInt(Tag.NumberOfFrames, 1).coerceAtLeast(1)
                    val bitsAlloc = dataset.getInt(Tag.BitsAllocated, 16)
                    val bitsStored = dataset.getInt(Tag.BitsStored, bitsAlloc)
                    val highBit = dataset.getInt(Tag.HighBit, bitsStored - 1)
                    val pixRep = if (dataset.getInt(Tag.PixelRepresentation, 0) == 1) "signed" else "unsigned"
                    val photo = dataset.getString(Tag.PhotometricInterpretation, "MONOCHROME2").trim()
                    val tsuid = (fmi?.getString(Tag.TransferSyntaxUID) ?: dis.transferSyntax ?: UID.ImplicitVRLittleEndian).trim()
                    val tsName = runCatching { UID.nameOf(tsuid) }.getOrDefault(tsuid)

                    val sizeDesc = if (pixelDataLength >= 0) {
                        val mb = String.format(Locale.US, "%.2f", pixelDataLength / (1024.0 * 1024.0))
                        "$pixelDataLength байт ($mb МБ)"
                    } else {
                        "Инкапсулированный поток"
                    }
                    val contentSummary = buildString {
                        append("Размер Pixel Data: $sizeDesc | ")
                        append("Разрешение: ${cols}×${rows}, кадров: $frames | ")
                        append("Формат: $photo, $bitsAlloc/$bitsStored бит (HighBit=$highBit, $pixRep) | ")
                        append("Transfer Syntax: $tsuid ($tsName)")
                    }
                    result.add(
                        DicomTagEntry(
                            tagHex = "(7FE0,0010)",
                            vr = pixelDataVr ?: "OW",
                            name = "Pixel Data",
                            nameRu = "Массив данных изображения (Pixel Data)",
                            value = contentSummary,
                            tagInt = Tag.PixelData,
                            isBinary = true,
                            binaryByteCount = pixelDataLength.coerceAtLeast(0)
                        )
                    )
                }
                result
            }
        } catch (e: Exception) {
            listOf(
                DicomTagEntry(
                    tagHex = "(0000,0000)",
                    vr = "LT",
                    name = "ReadError",
                    nameRu = "Ошибка чтения",
                    value = "Ошибка чтения DICOM-файла: ${e.message}"
                )
            )
        }
    }

    override fun deleteStudy(studyInstanceUid: String): Boolean {
        lock.withLock {
            val study = _studiesFlow.value.firstOrNull { it.studyInstanceUid == studyInstanceUid }
                ?: return false
            var deletedAny = false
            study.seriesList.flatMap { it.instances }.forEach { inst ->
                if (inst.filePath.startsWith("content://") && context != null) {
                    try {
                        val uri = Uri.parse(inst.filePath)
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        if (doc != null && doc.delete()) {
                            deletedAny = true
                        }
                    } catch (_: Exception) {}
                } else {
                    val f = File(inst.filePath)
                    val seriesDir = f.parentFile
                    val studyDir = seriesDir?.parentFile
                    if (f.exists() && f.delete()) {
                        deletedAny = true
                    }
                    if (seriesDir != null && seriesDir.exists() && seriesDir.listFiles().isNullOrEmpty()) {
                        seriesDir.delete()
                    }
                    if (studyDir != null && studyDir != storageRootDir && studyDir.exists() && studyDir.listFiles().isNullOrEmpty()) {
                        studyDir.delete()
                    }
                }
            }
            refreshFromDisk()
            return deletedAny
        }
    }

    override fun clearAllStudies(): Int {
        lock.withLock {
            var deletedCount = 0
            if (storageRootDir.exists()) {
                storageRootDir.walkBottomUp().forEach { file ->
                    if (file != storageRootDir) {
                        if (file.isFile && file.extension.equals("dcm", ignoreCase = true)) {
                            if (file.delete()) deletedCount++
                        } else if (file.isDirectory) {
                            file.delete()
                        }
                    }
                }
            }

            // Очищаем SAF-файлы, если настроены
            val safUri = storageManager?.getSavedSafTreeUri()
            if (context != null && safUri != null && storageManager?.isSafStorageWritable(safUri) == true) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, safUri)
                    rootDoc?.listFiles()?.forEach { studyDoc ->
                        if (studyDoc.isDirectory) {
                            studyDoc.listFiles().forEach { seriesDoc ->
                                if (seriesDoc.isDirectory) {
                                    seriesDoc.listFiles().forEach { dcmDoc ->
                                        if (dcmDoc.isFile && dcmDoc.name?.endsWith(".dcm", ignoreCase = true) == true) {
                                            if (dcmDoc.delete()) deletedCount++
                                        }
                                    }
                                    seriesDoc.delete()
                                }
                            }
                            studyDoc.delete()
                        }
                    }
                } catch (_: Exception) {}
            }

            refreshFromDisk()
            return deletedCount
        }
    }

    override fun getTotalStorageBytes(): Long {
        return _studiesFlow.value.sumOf { it.totalBytes }
    }

    override fun getTotalSavedFilesCount(): Int {
        return _studiesFlow.value.sumOf { it.instancesCount }
    }

    private data class ParsedRecord(
        val path: String,
        val displayName: String,
        val fileSize: Long,
        val studyInstanceUid: String,
        val seriesInstanceUid: String,
        val sopInstanceUid: String,
        val sopClassUid: String,
        val transferSyntaxUid: String,
        val patientName: String,
        val patientId: String,
        val patientBirthDate: String,
        val patientSex: String,
        val modality: String,
        val studyDateFormatted: String,
        val studyDescription: String,
        val seriesDescription: String,
        val seriesNumber: Int,
        val instanceNumber: Int,
        val manufacturer: String,
        val institutionName: String,
        val rows: Int,
        val columns: Int,
        val bitsAllocated: Int,
        val bitsStored: Int,
        val highBit: Int,
        val pixelRepresentation: Int,
        val samplesPerPixel: Int,
        val photometricInterpretation: String,
        val planarConfiguration: Int,
        val numberOfFrames: Int,
        val windowWidth: Int,
        val windowCenter: Int,
        val rescaleSlope: Double,
        val rescaleIntercept: Double,
        val specificCharacterSet: String,
        val encodingWarning: String?
    )

    private fun parseSummaryFromSource(
        path: String,
        displayName: String,
        fileSize: Long,
        lastModified: Long,
        streamOpener: () -> InputStream?
    ): ParsedRecord? {
        return try {
            val rawStream = streamOpener() ?: return null
            DicomInputStream(BufferedInputStream(rawStream, 65536)).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                val fmi = dis.readFileMetaInformation()
                val ds = dis.readDatasetUntilPixelData()

                val studyUid = ds.getString(Tag.StudyInstanceUID)?.takeIf { it.isNotBlank() }
                    ?: "UNKNOWN_STUDY"
                val seriesUid = ds.getString(Tag.SeriesInstanceUID)?.takeIf { it.isNotBlank() }
                    ?: "UNKNOWN_SERIES"
                val sopUid = ds.getString(Tag.SOPInstanceUID)
                    ?: fmi?.getString(Tag.MediaStorageSOPInstanceUID)
                    ?: displayName.substringBeforeLast('.')
                val sopClass = ds.getString(Tag.SOPClassUID)
                    ?: fmi?.getString(Tag.MediaStorageSOPClassUID)
                    ?: ""
                val tsuid = fmi?.getString(Tag.TransferSyntaxUID)
                    ?: dis.transferSyntax
                    ?: UID.ImplicitVRLittleEndian

                val charsetDiag = DicomCharsetDecoder.analyzeDatasetCharset(ds)
                val patientNameDecoded = DicomCharsetDecoder.decodeStringAttribute(ds, Tag.PatientName, VR.PN, "ANONYMOUS")
                val patientIdDecoded = DicomCharsetDecoder.decodeStringAttribute(ds, Tag.PatientID, VR.LO, "-")
                val studyDescDecoded = DicomCharsetDecoder.decodeStringAttribute(ds, Tag.StudyDescription, VR.LO, "-")
                val seriesDescDecoded = DicomCharsetDecoder.decodeStringAttribute(ds, Tag.SeriesDescription, VR.LO, "-")
                val manufacturerDecoded = DicomCharsetDecoder.decodeStringAttribute(ds, Tag.Manufacturer, VR.LO, "-")
                val institutionDecoded = DicomCharsetDecoder.decodeStringAttribute(ds, Tag.InstitutionName, VR.LO, "-")

                val birthDate = ds.getString(Tag.PatientBirthDate)?.takeIf { it.isNotBlank() } ?: "-"
                val sex = ds.getString(Tag.PatientSex)?.takeIf { it.isNotBlank() } ?: "-"
                val modality = ds.getString(Tag.Modality)?.takeIf { it.isNotBlank() } ?: "OT"

                val studyDateRaw = ds.getString(Tag.StudyDate, "")
                val studyTimeRaw = ds.getString(Tag.StudyTime, "")
                val formattedDate = formatDicomDateTime(studyDateRaw, studyTimeRaw, lastModified)

                val seriesNo = ds.getInt(Tag.SeriesNumber, 1)
                val instanceNo = ds.getInt(Tag.InstanceNumber, 1)
                val rows = ds.getInt(Tag.Rows, 256)
                val cols = ds.getInt(Tag.Columns, 256)
                val bitsAllocated = ds.getInt(Tag.BitsAllocated, 16)
                val bitsStored = ds.getInt(Tag.BitsStored, bitsAllocated)
                val highBit = ds.getInt(Tag.HighBit, bitsStored - 1)
                val pixelRepresentation = ds.getInt(Tag.PixelRepresentation, 0)
                val samplesPerPixel = ds.getInt(Tag.SamplesPerPixel, 1)
                val photometric = ds.getString(Tag.PhotometricInterpretation, "MONOCHROME2").trim()
                val planarConfiguration = ds.getInt(Tag.PlanarConfiguration, 0)
                val numberOfFrames = ds.getInt(Tag.NumberOfFrames, 1).coerceAtLeast(1)

                val maxDefaultWindow = ((1 shl bitsStored.coerceIn(8, 16)) - 1).coerceAtLeast(255)
                val defaultWwFallback = if (bitsStored > 8) 4096.0 else 255.0
                val defaultWcFallback = if (bitsStored > 8) 2048.0 else 128.0
                val ww = ds.getDouble(Tag.WindowWidth, defaultWwFallback.coerceAtMost(maxDefaultWindow.toDouble()))
                    .roundToInt()
                    .coerceAtLeast(1)
                val wc = ds.getDouble(Tag.WindowCenter, defaultWcFallback).roundToInt()
                val slope = ds.getDouble(Tag.RescaleSlope, 1.0).let { if (it == 0.0) 1.0 else it }
                val intercept = ds.getDouble(Tag.RescaleIntercept, 0.0)

                val combinedWarning = listOfNotNull(
                    patientNameDecoded.diagnosticWarning,
                    studyDescDecoded.diagnosticWarning,
                    seriesDescDecoded.diagnosticWarning,
                    institutionDecoded.diagnosticWarning,
                    charsetDiag.warningMessage
                ).firstOrNull()

                ParsedRecord(
                    path = path,
                    displayName = displayName,
                    fileSize = fileSize,
                    studyInstanceUid = studyUid,
                    seriesInstanceUid = seriesUid,
                    sopInstanceUid = sopUid,
                    sopClassUid = sopClass,
                    transferSyntaxUid = tsuid,
                    patientName = patientNameDecoded.value.ifBlank { "ANONYMOUS" },
                    patientId = patientIdDecoded.value.ifBlank { "-" },
                    patientBirthDate = birthDate,
                    patientSex = sex,
                    modality = modality,
                    studyDateFormatted = formattedDate,
                    studyDescription = studyDescDecoded.value.ifBlank { "-" },
                    seriesDescription = seriesDescDecoded.value.ifBlank { "-" },
                    seriesNumber = seriesNo,
                    instanceNumber = instanceNo,
                    manufacturer = manufacturerDecoded.value.ifBlank { "-" },
                    institutionName = institutionDecoded.value.ifBlank { "-" },
                    rows = rows,
                    columns = cols,
                    bitsAllocated = bitsAllocated,
                    bitsStored = bitsStored,
                    highBit = highBit,
                    pixelRepresentation = pixelRepresentation,
                    samplesPerPixel = samplesPerPixel,
                    photometricInterpretation = photometric,
                    planarConfiguration = planarConfiguration,
                    numberOfFrames = numberOfFrames,
                    windowWidth = ww,
                    windowCenter = wc,
                    rescaleSlope = slope,
                    rescaleIntercept = intercept,
                    specificCharacterSet = charsetDiag.effectiveCharsetDescription,
                    encodingWarning = combinedWarning
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAttributesToTagList(
        attrs: Attributes,
        dict: ElementDictionary,
        inheritedCharsetTerms: List<String>,
        out: MutableList<DicomTagEntry>,
        depth: Int = 0
    ) {
        val localCharsetTerms = DicomCharsetDecoder.getDeclaredSpecificCharacterSets(attrs)
            .ifEmpty { inheritedCharsetTerms }

        for (tag in attrs.tags()) {
            val vr = attrs.getVR(tag) ?: dict.vrOf(tag) ?: VR.UN
            val tagHex = formatTagHex(tag)
            val isPrivate = TagUtils.isPrivateTag(tag)
            val creator = if (isPrivate) attrs.privateCreatorOf(tag) else null
            val tagNames = ru.medeng.dicomtool.dicom.tags.DicomTagDictionary.resolveTagNames(tag, creator)

            if (vr == VR.SQ) {
                val seq: Sequence? = attrs.getSequence(tag)
                val seqItems = ArrayList<ru.medeng.dicomtool.domain.model.DicomSequenceItem>()
                val fullValueStr = formatAttributeValueFull(attrs, tag, vr, dict, localCharsetTerms, depth = depth)
                if (!seq.isNullOrEmpty()) {
                    seq.forEachIndexed { idx, itemAttrs ->
                        val itemTags = ArrayList<DicomTagEntry>()
                        extractAttributesToTagList(itemAttrs, dict, localCharsetTerms, itemTags, depth + 1)
                        seqItems.add(ru.medeng.dicomtool.domain.model.DicomSequenceItem(idx + 1, itemTags))
                    }
                }
                out.add(
                    DicomTagEntry(
                        tagHex = tagHex,
                        vr = "SQ",
                        name = tagNames.nameEn,
                        value = fullValueStr,
                        nameRu = tagNames.nameRu,
                        tagInt = tag,
                        isPrivate = isPrivate,
                        isSequence = true,
                        sequenceItems = seqItems,
                        isBinary = false,
                        depth = depth
                    )
                )
                continue
            }

            val valueStr = formatAttributeValueFull(attrs, tag, vr, dict, localCharsetTerms, depth = depth)
            val isBinary = vr == VR.OB || vr == VR.OW || vr == VR.UN || vr == VR.OF || vr == VR.OD
            val byteCount = if (isBinary) runCatching { attrs.getBytes(tag)?.size?.toLong() ?: 0L }.getOrDefault(0L) else 0L

            out.add(
                DicomTagEntry(
                    tagHex = tagHex,
                    vr = vr.name,
                    name = tagNames.nameEn,
                    value = valueStr,
                    nameRu = tagNames.nameRu,
                    tagInt = tag,
                    isPrivate = isPrivate,
                    isSequence = false,
                    sequenceItems = emptyList(),
                    isBinary = isBinary,
                    binaryByteCount = byteCount,
                    depth = depth
                )
            )
        }
    }

    private fun formatAttributeValueFull(
        attrs: Attributes,
        tag: Int,
        vr: VR,
        dict: ElementDictionary,
        charsetTerms: List<String>,
        depth: Int
    ): String {
        if (vr == VR.SQ) {
            val seq: Sequence? = attrs.getSequence(tag)
            if (seq.isNullOrEmpty()) return "Empty Sequence (0 items)"
            val indent = "  ".repeat(depth + 1)
            val sb = StringBuilder("Sequence (${seq.size} items):")
            seq.forEachIndexed { idx, item ->
                val itemCharset = DicomCharsetDecoder.getDeclaredSpecificCharacterSets(item)
                    .ifEmpty { charsetTerms }
                sb.append("\n$indent[Item #${idx + 1}]")
                for (subTag in item.tags()) {
                    val subVr = item.getVR(subTag) ?: dict.vrOf(subTag) ?: VR.UN
                    val subHex = formatTagHex(subTag)
                    val subName = dict.keywordOf(subTag).takeIf { !it.isNullOrBlank() } ?: "PrivateTag"
                    val subVal = if (depth < 4) {
                        formatAttributeValueFull(item, subTag, subVr, dict, itemCharset, depth + 1)
                    } else {
                        decodeSingleTagString(item, subTag, subVr, itemCharset)
                    }
                    sb.append("\n$indent  $subHex [$subVr] $subName = $subVal")
                }
            }
            return sb.toString()
        }

        if (DicomCharsetDecoder.isCharsetAffectedVr(vr)) {
            val rawBytes = runCatching { attrs.getBytes(tag) }.getOrNull()
            if (rawBytes != null) {
                val decoded = DicomCharsetDecoder.decodeBytesWithDiagnostics(rawBytes, vr, charsetTerms)
                return if (decoded.diagnosticWarning != null) {
                    "${decoded.value}  [⚠ ${decoded.diagnosticWarning}]"
                } else {
                    decoded.value
                }
            }
        }

        val strings = attrs.getStrings(tag)
        if (!strings.isNullOrEmpty()) {
            val rawJoined = strings.joinToString("\\")
            if (vr == VR.UI && strings.size == 1) {
                val uidVal = strings[0].trim { it <= ' ' || it == '\u0000' }
                val uidName = runCatching { UID.nameOf(uidVal) }.getOrNull()
                if (!uidName.isNullOrBlank() && uidName != "?" && uidName != uidVal) {
                    return "$uidVal ($uidName)"
                }
            }
            return rawJoined
        }

        val bytes = runCatching { attrs.getBytes(tag) }.getOrNull()
        if (bytes != null) {
            if (bytes.isEmpty()) return ""
            val previewCount = bytes.size.coerceAtMost(64)
            val hex = bytes.take(previewCount).joinToString(" ") { "%02X".format(it) }
            return if (bytes.size > previewCount) {
                "$hex ... (${bytes.size} байт)"
            } else {
                "$hex (${bytes.size} байт)"
            }
        }

        return ""
    }

    private fun decodeSingleTagString(
        attrs: Attributes,
        tag: Int,
        vr: VR,
        charsetTerms: List<String>
    ): String {
        if (DicomCharsetDecoder.isCharsetAffectedVr(vr)) {
            val rawBytes = runCatching { attrs.getBytes(tag) }.getOrNull()
            if (rawBytes != null) {
                val decoded = DicomCharsetDecoder.decodeBytesWithDiagnostics(rawBytes, vr, charsetTerms)
                return decoded.value
            }
        }
        return attrs.getString(tag, "")
    }

    private fun formatTagHex(tag: Int): String {
        return TagUtils.toString(tag).uppercase(Locale.ROOT)
    }

    private fun formatDicomDateTime(dateRaw: String, timeRaw: String, fileLastModified: Long): String {
        val cleanDate = dateRaw.trim().filter { it.isDigit() }
        val cleanTime = timeRaw.trim().filter { it.isDigit() }
        if (cleanDate.length == 8) {
            val yyyy = cleanDate.substring(0, 4)
            val mm = cleanDate.substring(4, 6)
            val dd = cleanDate.substring(6, 8)
            val timePart = if (cleanTime.length >= 4) {
                val hh = cleanTime.substring(0, 2)
                val min = cleanTime.substring(2, 4)
                val ss = if (cleanTime.length >= 6) cleanTime.substring(4, 6) else "00"
                " $hh:$min:$ss"
            } else ""
            return "$dd.$mm.$yyyy$timePart"
        }
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(fileLastModified))
    }

    private fun sanitizeFolder(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
    }
}
