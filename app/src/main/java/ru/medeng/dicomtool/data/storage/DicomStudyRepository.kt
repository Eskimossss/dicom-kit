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
    context: Context? = null,
    override val storageManager: DicomStorageManager? = null,
    eagerLoad: Boolean = true,
    private val onHeaderRead: () -> Unit = {}
) : DicomStudyRepository {

    private val context = context?.applicationContext as? android.app.Application
    private val lock = ReentrantLock()
    private val coordinator by lazy { StorageCoordinator.forRoot(storageRootDir) }
    private val records = LinkedHashMap<String, ParsedRecord>()
    private val _indexState = MutableStateFlow(ArchiveIndexState(isLoading = !eagerLoad))
    val indexState: StateFlow<ArchiveIndexState> = _indexState.asStateFlow()
    private val _studiesFlow = MutableStateFlow<List<DicomStudyItem>>(emptyList())
    override val studiesFlow: StateFlow<List<DicomStudyItem>> = _studiesFlow.asStateFlow()

    init {
        if (eagerLoad) refreshFromDisk()
    }

    override fun saveIncomingInstance(
        tempIncomingFile: File,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        sopInstanceUid: String
    ): DicomSaveResult = saveWithOptions(tempIncomingFile, studyInstanceUid, seriesInstanceUid, sopInstanceUid,
        storageManager?.receptionOptions?.value ?: ReceptionStorageOptions(duplicates = IncomingDuplicatePolicy.DEDUPLICATE))

    internal fun saveRetainedIncoming(file: File, study: String, series: String, sop: String): DicomSaveResult =
        saveWithOptions(file, study, series, sop, (storageManager?.receptionOptions?.value ?: ReceptionStorageOptions()).copy(duplicates = IncomingDuplicatePolicy.DEDUPLICATE))

    private fun saveWithOptions(tempIncomingFile: File, studyInstanceUid: String, seriesInstanceUid: String,
                                sopInstanceUid: String, options: ReceptionStorageOptions): DicomSaveResult = coordinator.receiving {
        val folder = if (options.patientFolderNames) runCatching {
            DicomInputStream(tempIncomingFile).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                ru.medeng.dicomtool.dicom.tags.BoundedDicomRead.install(dis)
                dis.readFileMetaInformation()
                val ds = dis.readDatasetUntilPixelData()
                StudyFolderNames.create(DicomCharsetDecoder.decodeStringAttribute(ds, Tag.PatientName, VR.PN, "").value, studyInstanceUid)
            }
        }.getOrDefault(studyInstanceUid) else studyInstanceUid
        val result = if (storageManager != null) {
            storageManager.saveIncomingDicomFile(
                tempIncomingFile = tempIncomingFile,
                studyInstanceUid = studyInstanceUid,
                seriesInstanceUid = seriesInstanceUid,
                sopInstanceUid = sopInstanceUid,
                duplicatePolicy = options.duplicates, studyFolderName = folder
            )
        } else {
            VerifiedDicomStore(FileStorageAccess(storageRootDir), DocumentOwnership.forRoot(storageRootDir),
                StorageCoordinator.forRoot(storageRootDir)).save(tempIncomingFile.storageSource(),
                studyInstanceUid, seriesInstanceUid, sopInstanceUid, options.duplicates, folder)
        }
        if (result is DicomSaveResult.Success) {
            // Read only the verified destination; indexing is not a second C-STORE transaction.
            runCatching {
                val path = result.destinationDescription
                val rec = if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    parseSummaryFromSource(path, uri.lastPathSegment ?: "received.dcm", result.savedSizeBytes,
                        System.currentTimeMillis()) { context?.contentResolver?.openInputStream(uri) }
                } else {
                    val file = File(path)
                    parseSummaryFromSource(path, file.name, result.savedSizeBytes, file.lastModified()) { FileInputStream(file) }
                }
                lock.withLock {
                    if (rec != null) {
                        records[path] = rec
                        _indexState.value = _indexState.value.copy(indexedFiles = records.size)
                        publishRecords(records.values.filter { it.studyInstanceUid == rec.studyInstanceUid }, rec.studyInstanceUid)
                    } else _indexState.value = _indexState.value.copy(warning = "Файл сохранён, но заголовок не включён в индекс: неподдерживаемые или повреждённые metadata")
                }
            }.onFailure { _indexState.value = _indexState.value.copy(warning = "Файл сохранён; индекс требует обновления: ${it.javaClass.simpleName}") }
        }
        result
    }

    override fun refreshFromDisk() = coordinator.maintenance {
        _indexState.value = ArchiveIndexState(isLoading = true)
        try { lock.withLock {
            storageRootDir.mkdirs()
            val parsedRecords = ArrayList<ParsedRecord>()
            var unreadable = 0

            // 1. Сканируем внутреннее хранилище (если существует)
            if (storageRootDir.exists()) {
                val internalFiles = StoragePaths.dicomFiles(storageRootDir).toList()

                for (file in internalFiles) {
                    val rec = parseSummaryFromSource(
                        path = file.absolutePath,
                        displayName = file.name,
                        fileSize = file.length(),
                        lastModified = file.lastModified(),
                        streamOpener = { FileInputStream(file) }
                    )
                    if (rec != null) parsedRecords.add(rec) else unreadable++
                }
            }

            // 2. Сканируем настроенный SAF каталог (если настроен, доступен и context != null)
            val safUri = storageManager?.getSavedSafTreeUri()
            var warning: String? = null
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
                                    if (rec != null) parsedRecords.add(rec) else unreadable++
                                }
                            }
                        }
                    }
                } catch (e: Exception) { warning = "Индекс SAF неполон: ${e.javaClass.simpleName}" }
            } else if (safUri != null) warning = "SAF недоступен: доступ отозван или провайдер отключён. Показаны только доступные внутренние файлы."
            records.clear()
            parsedRecords.forEach { records[it.path] = it }
            publishRecords(parsedRecords)
            _indexState.value = ArchiveIndexState(warning = warning ?: if (unreadable > 0) "Не прочитаны заголовки: $unreadable. Файлы не изменены." else null, indexedFiles = parsedRecords.size)
        } } catch (e: Exception) {
            _indexState.value = ArchiveIndexState(warning = "Обновление индекса не завершено: ${e.javaClass.simpleName}")
        }
    }

    private fun publishRecords(parsedRecords: Collection<ParsedRecord>, affectedStudy: String? = null) {
            // Группируем по StudyInstanceUID
            val groupedByStudy = parsedRecords.groupBy { it.studyInstanceUid }

            val studyItems = groupedByStudy.map { (studyUid, studyRecords) ->
                val primary = studyRecords.first()
                val groupedBySeries = studyRecords.groupBy { it.seriesInstanceUid }

                val seriesGroups = groupedBySeries.map { (seriesUid, seriesRecords) ->
                    val firstInSeries = seriesRecords.first()
                    val duplicateCounts = seriesRecords.groupingBy { it.sopInstanceUid }.eachCount()
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
                                duplicateCopyCount = (duplicateCounts[rec.sopInstanceUid] ?: 1) - 1
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

            _studiesFlow.value = if (affectedStudy == null) studyItems else
                (_studiesFlow.value.filterNot { it.studyInstanceUid == affectedStudy } + studyItems).sortedByDescending { it.studyDateFormatted }
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
            rawStream.use { ru.medeng.dicomtool.dicom.tags.DicomFileReader.readFromStream(it,
                fileName = file?.name ?: "received.dcm", fileSizeBytes = file?.length() ?: 0).tags }
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

    override fun deleteStudy(studyInstanceUid: String): Boolean = coordinator.maintenance {
        lock.withLock {
            val study = _studiesFlow.value.firstOrNull { it.studyInstanceUid == studyInstanceUid }
                ?: return@withLock false
            var removed = (storageManager?.clearOwnedSaf(studyInstanceUid) ?: 0) > 0
            for (inst in study.seriesList.flatMap { it.instances }) {
                if (inst.filePath.startsWith("content://")) continue // SAF deletion only through ownership ledger.
                val file = runCatching { StoragePaths.contained(storageRootDir, File(inst.filePath)) }.getOrNull() ?: continue
                if (file.isFile && file.extension.equals("dcm", true) && file.delete()) removed = true
            }
            refreshFromDisk()
            removed
        }
    }

    override fun clearAllStudies(): Int = coordinator.maintenance {
        lock.withLock {
            var removed = storageManager?.clearOwnedSaf() ?: 0
            // The internal root is private app storage. Never follow paths outside it or delete directories.
            StoragePaths.dicomFiles(storageRootDir).forEach { candidate ->
                val file = runCatching { StoragePaths.contained(storageRootDir, candidate) }.getOrNull()
                if (file != null && file.delete()) removed++
            }
            refreshFromDisk()
            removed
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
            onHeaderRead()
            val rawStream = streamOpener() ?: return null
            DicomInputStream(BufferedInputStream(rawStream, 65536)).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                ru.medeng.dicomtool.dicom.tags.BoundedDicomRead.install(dis)
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

}
