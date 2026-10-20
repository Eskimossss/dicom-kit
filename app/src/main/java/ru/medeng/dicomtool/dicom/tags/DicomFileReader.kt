package ru.medeng.dicomtool.dicom.tags

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.ElementDictionary
import org.dcm4che3.data.Sequence
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.util.TagUtils
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.domain.model.DicomFileSummary
import ru.medeng.dicomtool.domain.model.DicomSequenceItem
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Результат разбора DICOM-файла.
 */
data class DicomFileParseResult(
    val summary: DicomFileSummary,
    val tags: List<DicomTagEntry>,
    val warningMessage: String? = null
)

/**
 * Читатель метаданных DICOM-файлов (PS3.5 / PS3.10) с поддержкой:
 * - Файлов с 128-байтным preamble и маркером DICM, а также файлов БЕЗ preamble/DICM.
 * - Любых расширений (.dcm, .dicom, без расширения).
 * - Безопасного чтения через ContentResolver (SAF) без копирования в постоянное хранилище.
 * - Исключения загрузки Pixel Data в память (includeBulkData = NO).
 * - Полного дерева атрибутов: FMI (0002), стандартные, частные (Private), пустые, бинарные и вложенные SQ.
 * - Автоматического определения кодировок (0008,0005) и ручного переопределения (forcedCharset).
 */
object DicomFileReader {

    private const val BUFFER_SIZE = 131072 // 128 КБ буфер для mark/reset

    private val STANDARD_VRS = setOf(
        "AE", "AS", "AT", "CS", "DA", "DS", "DT", "FD", "FL", "IS", "LO", "LT",
        "OB", "OD", "OF", "OL", "OV", "OW", "PN", "SH", "SL", "SQ", "SS", "ST",
        "SV", "TM", "UC", "UI", "UL", "UN", "UR", "US", "UT", "UV"
    )

    /**
     * Читает метаданные DICOM-файла из Uri через ContentResolver.
     */
    fun readFromUri(
        context: Context,
        uri: Uri,
        forcedCharset: String? = null,
        checkCancelled: () -> Unit = {}
    ): DicomFileParseResult {
        val (displayName, sizeBytes) = queryUriMetadata(context.contentResolver, uri)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть поток для чтения Uri: $uri")

        return stream.use { rawIn ->
            readFromStream(
                inputStream = rawIn,
                fileName = displayName,
                fileSizeBytes = sizeBytes,
                forcedCharset = forcedCharset, checkCancelled = checkCancelled
            )
        }
    }

    /**
     * Читает метаданные DICOM-файла из File на диске.
     */
    fun readFromFile(
        file: File,
        forcedCharset: String? = null
    ): DicomFileParseResult {
        require(file.exists() && file.isFile) { "Файл не существует или не является обычным файлом: ${file.absolutePath}" }
        val stream = FileInputStream(file)
        return stream.use { rawIn ->
            readFromStream(
                inputStream = rawIn,
                fileName = file.name,
                fileSizeBytes = file.length(),
                forcedCharset = forcedCharset
            )
        }
    }

    /**
     * Читает метаданные DICOM-файла из InputStream.
     */
    fun readFromStream(
        inputStream: InputStream,
        fileName: String = "image.dcm",
        fileSizeBytes: Long = 0L,
        forcedCharset: String? = null,
        checkCancelled: () -> Unit = {}
    ): DicomFileParseResult {
        val bufferedIn = BufferedInputStream(object : java.io.FilterInputStream(inputStream) {
            override fun read(): Int { checkCancelled(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { checkCancelled(); return `in`.read(b, off, len) }
            override fun skip(n: Long): Long { checkCancelled(); return super.skip(n) }
        }, BUFFER_SIZE)
        bufferedIn.mark(BUFFER_SIZE)

        // 1. Проверяем наличие маркера DICM на смещении 128
        val preambleBuffer = ByteArray(132)
        var readHeaderBytes = 0
        while (readHeaderBytes < 132) {
            val r = bufferedIn.read(preambleBuffer, readHeaderBytes, 132 - readHeaderBytes)
            if (r <= 0) break
            readHeaderBytes += r
        }

        val hasDicm = readHeaderBytes >= 132 &&
            preambleBuffer[128] == 'D'.code.toByte() &&
            preambleBuffer[129] == 'I'.code.toByte() &&
            preambleBuffer[130] == 'C'.code.toByte() &&
            preambleBuffer[131] == 'M'.code.toByte()

        bufferedIn.reset()

        return if (hasDicm) {
            // Стандартный Part 10 файл
            readPart10Dicom(
                bufferedIn = bufferedIn,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                hasDicm = true,
                forcedCharset = forcedCharset, checkCancelled = checkCancelled
            )
        } else {
            // Файл без маркера DICM (ACR-NEMA или raw stream без 128-байтного preamble)
            readRawDicomStream(
                bufferedIn = bufferedIn,
                headerSample = preambleBuffer.copyOf(readHeaderBytes),
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                forcedCharset = forcedCharset, checkCancelled = checkCancelled
            )
        }
    }

    private fun readPart10Dicom(
        bufferedIn: BufferedInputStream,
        fileName: String,
        fileSizeBytes: Long,
        hasDicm: Boolean,
        forcedCharset: String?,
        checkCancelled: () -> Unit
    ): DicomFileParseResult {
        DicomInputStream(bufferedIn).use { dis ->
            dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
            BoundedDicomRead.install(dis, checkCancelled)
            val fmi: Attributes? = dis.readFileMetaInformation()
            val dataset: Attributes = dis.readDatasetUntilPixelData()
            val pixelDataPresent = dis.tag() == Tag.PixelData
            val pixelDataVr = if (pixelDataPresent) dis.vr()?.name ?: "OW" else null
            val pixelDataLength = if (pixelDataPresent && dis.length() != -1) dis.unsignedLength() else -1L
            if (pixelDataPresent) {
                dis.readValue(dis, dataset) // Skip Pixel Data, retain tags following it.
                dis.readAttributes(dataset, -1L, -1) // Keep the same charset scope for post-Pixel attributes.
                dataset.remove(Tag.PixelData) // A single explicit descriptor is added below, not a null placeholder.
            }

            return buildParseResult(
                fmi = fmi,
                dataset = dataset,
                dis = dis,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                hasDicm = hasDicm,
                pixelDataPresent = pixelDataPresent,
                pixelDataVr = pixelDataVr,
                pixelDataLength = pixelDataLength,
                forcedCharset = forcedCharset
            )
        }
    }

    private fun readRawDicomStream(
        bufferedIn: BufferedInputStream,
        headerSample: ByteArray,
        fileName: String,
        fileSizeBytes: Long,
        forcedCharset: String?,
        checkCancelled: () -> Unit
    ): DicomFileParseResult {
        // Определяем синтаксис (Explicit VR Little Endian vs Implicit VR Little Endian)
        val isExplicitVR = isExplicitVRSample(headerSample)
        val guessedSyntax = if (isExplicitVR) UID.ExplicitVRLittleEndian else UID.ImplicitVRLittleEndian

        return try {
            DicomInputStream(bufferedIn, guessedSyntax).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                BoundedDicomRead.install(dis, checkCancelled)
                val fmi: Attributes? = dis.readFileMetaInformation()
                val dataset: Attributes = dis.readDatasetUntilPixelData()
                val pixelDataPresent = dis.tag() == Tag.PixelData
                val pixelDataVr = if (pixelDataPresent) dis.vr()?.name ?: "OW" else null
                val pixelDataLength = if (pixelDataPresent && dis.length() != -1) dis.unsignedLength() else -1L
                if (pixelDataPresent) { dis.readValue(dis, dataset); dis.readAttributes(dataset, -1L, -1); dataset.remove(Tag.PixelData) }

                buildParseResult(
                    fmi = fmi,
                    dataset = dataset,
                    dis = dis,
                    fileName = fileName,
                    fileSizeBytes = fileSizeBytes,
                    hasDicm = false,
                    pixelDataPresent = pixelDataPresent,
                    pixelDataVr = pixelDataVr,
                    pixelDataLength = pixelDataLength,
                    forcedCharset = forcedCharset
                )
            }
        } catch (e: Exception) {
            // Do not retry a consumed/closed stream or reinterpret malformed explicit VR as implicit.
            throw e
        }
    }

    private fun isExplicitVRSample(sample: ByteArray): Boolean {
        if (sample.size < 6) return false
        val vrStr = String(sample, 4, 2, StandardCharsets.US_ASCII)
        return vrStr in STANDARD_VRS
    }

    private fun buildParseResult(
        fmi: Attributes?,
        dataset: Attributes,
        dis: DicomInputStream,
        fileName: String,
        fileSizeBytes: Long,
        hasDicm: Boolean,
        pixelDataPresent: Boolean,
        pixelDataVr: String?,
        pixelDataLength: Long,
        forcedCharset: String?
    ): DicomFileParseResult {
        val declaredCharsetTerms = if (forcedCharset != null) {
            listOf(forcedCharset)
        } else {
            DicomCharsetDecoder.getDeclaredSpecificCharacterSets(dataset)
        }

        val charsetDiag = DicomCharsetDecoder.analyzeDatasetCharset(dataset)
        val allTags = ArrayList<DicomTagEntry>()

        // 1. Метаинформация файла FMI (группа 0002)
        if (fmi != null && !fmi.isEmpty) {
            extractAttributes(
                attrs = fmi,
                charsetTerms = emptyList(),
                forcedCharset = forcedCharset,
                depth = 0,
                out = allTags
            )
        }

        // 2. Основной набор данных (Dataset)
        extractAttributes(
            attrs = dataset,
            charsetTerms = declaredCharsetTerms,
            forcedCharset = forcedCharset,
            depth = 0,
            out = allTags
        )

        // 3. Pixel Data (7FE0,0010) — если присутствует
        val rows = dataset.getInt(Tag.Rows, 0)
        val cols = dataset.getInt(Tag.Columns, 0)
        val frames = dataset.getInt(Tag.NumberOfFrames, 1).coerceAtLeast(1)
        val bitsAlloc = dataset.getInt(Tag.BitsAllocated, 16)
        val bitsStored = dataset.getInt(Tag.BitsStored, bitsAlloc)
        val highBit = dataset.getInt(Tag.HighBit, bitsStored - 1)
        val pixRep = if (dataset.getInt(Tag.PixelRepresentation, 0) == 1) "Signed" else "Unsigned"
        val photo = dataset.getString(Tag.PhotometricInterpretation, "MONOCHROME2").trim()
        val tsuid = (fmi?.getString(Tag.TransferSyntaxUID) ?: dis.transferSyntax ?: UID.ImplicitVRLittleEndian).trim()
        val tsName = runCatching { UID.nameOf(tsuid) }.getOrDefault(tsuid)

        if (pixelDataPresent) {
            val sizeDesc = if (pixelDataLength >= 0) {
                val mb = String.format(Locale.US, "%.2f", pixelDataLength / (1024.0 * 1024.0))
                "$pixelDataLength байт ($mb МБ)"
            } else {
                "Инкапсулированный поток (Encapsulated)"
            }
            val pixelSummary = buildString {
                append("Размер Pixel Data: $sizeDesc\n")
                append("Разрешение: ${cols}×${rows}, кадров: $frames\n")
                append("Формат: $photo, $bitsAlloc/$bitsStored бит (HighBit=$highBit, $pixRep)\n")
                append("Transfer Syntax: $tsuid ($tsName)")
            }
            allTags.add(
                DicomTagEntry(
                    tagHex = "(7FE0,0010)",
                    vr = pixelDataVr ?: "OW",
                    name = "Pixel Data",
                    nameRu = "Массив данных изображения (Pixel Data)",
                    value = pixelSummary,
                    tagInt = Tag.PixelData,
                    isPrivate = false,
                    isSequence = false,
                    isBinary = true,
                    binaryByteCount = pixelDataLength.coerceAtLeast(0),
                    depth = 0
                )
            )
        }

        // Формируем краткую сводку файла
        val patientName = decodeAttributeText(dataset, Tag.PatientName, VR.PN, declaredCharsetTerms, forcedCharset)
            .ifBlank { "ANONYMOUS" }
        val patientId = decodeAttributeText(dataset, Tag.PatientID, VR.LO, declaredCharsetTerms, forcedCharset)
            .ifBlank { "-" }
        val modality = dataset.getString(Tag.Modality)?.takeIf { it.isNotBlank() } ?: "OT"
        val studyDateRaw = dataset.getString(Tag.StudyDate, "")
        val studyTimeRaw = dataset.getString(Tag.StudyTime, "")
        val formattedDate = formatDicomDateTime(studyDateRaw, studyTimeRaw)
        val studyDesc = decodeAttributeText(dataset, Tag.StudyDescription, VR.LO, declaredCharsetTerms, forcedCharset)
            .ifBlank { "-" }
        val seriesDesc = decodeAttributeText(dataset, Tag.SeriesDescription, VR.LO, declaredCharsetTerms, forcedCharset)
            .ifBlank { "-" }
        val sopClassUid = dataset.getString(Tag.SOPClassUID)
            ?: fmi?.getString(Tag.MediaStorageSOPClassUID) ?: ""
        val sopClassName = runCatching { UID.nameOf(sopClassUid) }.getOrDefault(sopClassUid)

        val charsetDescription = when {
            forcedCharset != null -> "Ручной выбор: $forcedCharset"
            else -> charsetDiag.effectiveCharsetDescription
        }

        val summary = DicomFileSummary(
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            hasPreambleAndDicm = hasDicm,
            patientName = patientName,
            patientId = patientId,
            modality = modality,
            studyDateFormatted = formattedDate,
            studyDescription = studyDesc,
            seriesDescription = seriesDesc,
            rows = rows,
            columns = cols,
            numberOfFrames = frames,
            bitsAllocated = bitsAlloc,
            transferSyntaxUid = tsuid,
            transferSyntaxName = tsName,
            sopClassUid = sopClassUid,
            sopClassName = sopClassName,
            specificCharacterSet = charsetDescription,
            encodingWarning = if (forcedCharset == null) charsetDiag.warningMessage else null
        )

        return DicomFileParseResult(
            summary = summary,
            tags = allTags,
            warningMessage = if (forcedCharset == null) charsetDiag.warningMessage else null
        )
    }

    private fun extractAttributes(
        attrs: Attributes,
        charsetTerms: List<String>,
        forcedCharset: String?,
        depth: Int,
        out: MutableList<DicomTagEntry>
    ) {
        val dict = ElementDictionary.getStandardElementDictionary()
        val localCharsetTerms = if (forcedCharset != null) {
            listOf(forcedCharset)
        } else {
            DicomCharsetDecoder.getDeclaredSpecificCharacterSets(attrs).ifEmpty { charsetTerms }
        }

        for (tag in attrs.tags()) {
            val vr = attrs.getVR(tag) ?: dict.vrOf(tag) ?: VR.UN
            val tagHex = TagUtils.toString(tag).uppercase(Locale.ROOT)
            val isPrivate = TagUtils.isPrivateTag(tag)
            val creator = if (isPrivate) attrs.privateCreatorOf(tag) else null
            val tagNames = DicomTagDictionary.resolveTagNames(tag, creator)

            if (vr == VR.SQ) {
                val seq: Sequence? = attrs.getSequence(tag)
                val seqItems = ArrayList<DicomSequenceItem>()
                val sbValue = StringBuilder()

                if (seq.isNullOrEmpty()) {
                    sbValue.append("Пустая последовательность (0 элементов)")
                } else {
                    sbValue.append("Последовательность (${seq.size} эл.):")
                    val indent = "  ".repeat(depth + 1)
                    seq.forEachIndexed { itemIdx, itemAttrs ->
                        val itemTags = ArrayList<DicomTagEntry>()
                        extractAttributes(
                            attrs = itemAttrs,
                            charsetTerms = localCharsetTerms,
                            forcedCharset = forcedCharset,
                            depth = depth + 1,
                            out = itemTags
                        )
                        seqItems.add(
                            DicomSequenceItem(
                                itemIndex = itemIdx + 1,
                                tags = itemTags
                            )
                        )
                        sbValue.append("\n$indent[Элемент #${itemIdx + 1}]")
                        // The full values live in the tree; do not duplicate nested strings at every level.
                        if (sbValue.length < 512) sbValue.append(": ${itemTags.size} тегов (раскройте Item)")
                    }
                }

                out.add(
                    DicomTagEntry(
                        tagHex = tagHex,
                        vr = "SQ",
                        name = tagNames.nameEn,
                        value = sbValue.toString(),
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

            // Текстовые или простые атрибуты
            val (valStr, isBinary, byteCount) = formatAttributeValue(
                attrs = attrs,
                tag = tag,
                vr = vr,
                charsetTerms = localCharsetTerms,
                forcedCharset = forcedCharset
            )

            out.add(
                DicomTagEntry(
                    tagHex = tagHex,
                    vr = vr.name,
                    name = tagNames.nameEn,
                    value = valStr,
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

    private fun formatAttributeValue(
        attrs: Attributes,
        tag: Int,
        vr: VR,
        charsetTerms: List<String>,
        forcedCharset: String?
    ): Triple<String, Boolean, Long> {
        if (vr in setOf(VR.OB, VR.OW, VR.UN, VR.OF, VR.OD, VR.OL, VR.OV)) {
            val bytes = runCatching { attrs.getBytes(tag) }.getOrNull() ?: return Triple("Бинарные данные не загружены", true, 0)
            val hex = bytes.take(32).joinToString(" ") { "%02X".format(it) }
            return Triple("$hex${if (bytes.size > 32) " ..." else ""} (${bytes.size} байт)", true, bytes.size.toLong())
        }
        // 1. Текстовые поля с учётом кодировок
        if (DicomCharsetDecoder.isCharsetAffectedVr(vr)) {
            val rawBytes = runCatching { attrs.getBytes(tag) }.getOrNull()
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val decoded = if (forcedCharset != null) {
                    val cs = runCatching { Charset.forName(forcedCharset) }.getOrElse { StandardCharsets.UTF_8 }
                    String(rawBytes, cs).trim { it <= ' ' || it == '\u0000' }
                } else {
                    val dec = DicomCharsetDecoder.decodeBytesWithDiagnostics(rawBytes, vr, charsetTerms)
                    if (dec.diagnosticWarning != null) {
                        "${dec.value}  [⚠ ${dec.diagnosticWarning}]"
                    } else {
                        dec.value
                    }
                }
                return Triple(decoded, false, 0L)
            }
        }

        // 2. Строковые массивы
        val strings = runCatching { attrs.getStrings(tag) }.getOrNull()
        if (!strings.isNullOrEmpty()) {
            val joined = strings.joinToString("\\")
            if (vr == VR.UI && strings.size == 1) {
                val uidVal = strings[0].trim { it <= ' ' || it == '\u0000' }
                val uidName = runCatching { UID.nameOf(uidVal) }.getOrNull()
                if (!uidName.isNullOrBlank() && uidName != "?" && uidName != uidVal) {
                    return Triple("$uidVal ($uidName)", false, 0L)
                }
            }
            return Triple(joined, false, 0L)
        }

        // 3. Бинарные поля (OB, OW, UN, OF, OD)
        val bytes = runCatching { attrs.getBytes(tag) }.getOrNull()
        if (bytes != null) {
            if (bytes.isEmpty()) return Triple("", false, 0L)
            val byteSize = bytes.size.toLong()
            val previewCount = bytes.size.coerceAtMost(32)
            val hex = bytes.take(previewCount).joinToString(" ") { "%02X".format(it) }
            val formatted = if (bytes.size > previewCount) {
                "$hex ... ($byteSize байт)"
            } else {
                "$hex ($byteSize байт)"
            }
            return Triple(formatted, true, byteSize)
        }

        return Triple("", false, 0L)
    }

    private fun decodeAttributeText(
        attrs: Attributes,
        tag: Int,
        vr: VR,
        charsetTerms: List<String>,
        forcedCharset: String?
    ): String {
        if (!attrs.contains(tag)) return ""
        val bytes = runCatching { attrs.getBytes(tag) }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            return if (forcedCharset != null) {
                val cs = runCatching { Charset.forName(forcedCharset) }.getOrElse { StandardCharsets.UTF_8 }
                String(bytes, cs).trim { it <= ' ' || it == '\u0000' }
            } else {
                DicomCharsetDecoder.decodeBytesWithDiagnostics(bytes, vr, charsetTerms).value
            }
        }
        return attrs.getString(tag, "")
    }

    private fun formatDicomDateTime(dateRaw: String, timeRaw: String): String {
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
        return if (cleanDate.isNotEmpty()) cleanDate else "-"
    }

    private fun queryUriMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "dicom_file.dcm"
        var size = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.let { name = it }
                    }
                    if (sizeIdx >= 0) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return name to size
    }
}
