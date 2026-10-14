package ru.medeng.dicomtool.dicom.scu

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.util.UIDUtils
import ru.medeng.dicomtool.domain.model.FreehandStroke
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import ru.medeng.dicomtool.domain.model.TestImageMode
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Набор уникальных идентификаторов сгенерированного тестового DICOM-объекта Secondary Capture (Этап 3).
 *
 * Гарантирует соблюдение требований DICOM PS3.5 / PS3.10:
 * - Study Instance UID (0020,000D) — уникальный, длина <= 64 символов.
 * - Series Instance UID (0020,000E) — уникальный, длина <= 64 символов.
 * - SOP Instance UID (0008,0018) — уникальный, длина <= 64 символов.
 * - SOP Class UID (0008,0016) — всегда Secondary Capture Image Storage (1.2.840.10008.5.1.4.1.1.7).
 * - Media Storage SOP Class UID (0002,0002) == SOP Class UID.
 * - Media Storage SOP Instance UID (0002,0003) == SOP Instance UID.
 */
data class GeneratedDicomObjectUids(
    val studyInstanceUid: String,
    val seriesInstanceUid: String,
    val sopInstanceUid: String,
    val sopClassUid: String = SECONDARY_CAPTURE_SOP_CLASS_UID,
    val mediaStorageSopClassUid: String = sopClassUid,
    val mediaStorageSopInstanceUid: String = sopInstanceUid
) {
    init {
        require(DicomSecondaryCaptureGenerator.isValidDicomUid(studyInstanceUid)) {
            "Некорректный StudyInstanceUID: $studyInstanceUid"
        }
        require(DicomSecondaryCaptureGenerator.isValidDicomUid(seriesInstanceUid)) {
            "Некорректный SeriesInstanceUID: $seriesInstanceUid"
        }
        require(DicomSecondaryCaptureGenerator.isValidDicomUid(sopInstanceUid)) {
            "Некорректный SOPInstanceUID: $sopInstanceUid"
        }
        require(studyInstanceUid != seriesInstanceUid && seriesInstanceUid != sopInstanceUid && studyInstanceUid != sopInstanceUid) {
            "StudyInstanceUID, SeriesInstanceUID и SOPInstanceUID должны быть попарно уникальными"
        }
        require(sopClassUid == SECONDARY_CAPTURE_SOP_CLASS_UID) {
            "SOPClassUID тестового объекта должен быть Secondary Capture Image Storage ($SECONDARY_CAPTURE_SOP_CLASS_UID)"
        }
        require(mediaStorageSopClassUid == sopClassUid) {
            "MediaStorageSOPClassUID ($mediaStorageSopClassUid) должен совпадать с SOPClassUID ($sopClassUid)"
        }
        require(mediaStorageSopInstanceUid == sopInstanceUid) {
            "MediaStorageSOPInstanceUID ($mediaStorageSopInstanceUid) должен совпадать с SOPInstanceUID ($sopInstanceUid)"
        }
    }

    companion object {
        const val SECONDARY_CAPTURE_SOP_CLASS_UID: String = UID.SecondaryCaptureImageStorage // "1.2.840.10008.5.1.4.1.1.7"
    }
}

/**
 * Сформированный в памяти DICOM-объект Secondary Capture (Dataset + File Meta Information + реальные Pixel Data + идентификаторы).
 * Сохраняет свои UID и временные метки при повторной попытке передачи того же сформированного объекта.
 */
data class PreparedSecondaryCaptureDicomObject(
    val uids: GeneratedDicomObjectUids,
    val modality: String,
    val contentSignature: String,
    val studyDate: String,
    val studyTime: String,
    val pixelDataBytes: ByteArray,
    val dataset: Attributes,
    val fileMetaInformation: Attributes
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedSecondaryCaptureDicomObject) return false
        return uids == other.uids &&
            modality == other.modality &&
            contentSignature == other.contentSignature &&
            studyDate == other.studyDate &&
            studyTime == other.studyTime &&
            pixelDataBytes.contentEquals(other.pixelDataBytes)
    }

    override fun hashCode(): Int {
        var result = uids.hashCode()
        result = 31 * result + modality.hashCode()
        result = 31 * result + contentSignature.hashCode()
        result = 31 * result + studyDate.hashCode()
        result = 31 * result + studyTime.hashCode()
        result = 31 * result + pixelDataBytes.contentHashCode()
        return result
    }
}

/**
 * Генератор DICOM UID и полноценных наборов атрибутов Secondary Capture Image Storage (Этап 3).
 * Не использует демонстрационные константы для экземпляров и строго ограничивает длину UID до 64 символов.
 */
object DicomSecondaryCaptureGenerator {

    const val MAX_DICOM_UID_LENGTH = 64
    const val SECONDARY_CAPTURE_SOP_CLASS_UID = UID.SecondaryCaptureImageStorage // "1.2.840.10008.5.1.4.1.1.7"
    const val DEFAULT_ROWS = 512
    const val DEFAULT_COLUMNS = 512

    /**
     * Допустимые стандартные значения Modality (0008,0060) согласно DICOM PS3.3 C.7.3.1.1.1.
     * Выбор любого значения Modality меняет только тег (0008,0060) и НЕ изменяет SOP Class UID (1.2.840.10008.5.1.4.1.1.7).
     */
    val SUPPORTED_MODALITIES: List<Pair<String, String>> = listOf(
        "OT" to "OT — Other (Прочее, по умолчанию)",
        "CT" to "CT — Computed Tomography (КТ)",
        "DX" to "DX — Digital Radiography (Цифровой рентген)",
        "CR" to "CR — Computed Radiography (КР)",
        "MG" to "MG — Mammography (Маммография)",
        "US" to "US — Ultrasound (УЗИ)",
        "MR" to "MR — Magnetic Resonance (МРТ)",
        "XA" to "XA — X-Ray Angiography (Ангиография)",
        "RF" to "RF — Radiofluoroscopy (Рентгеноскопия)",
        "NM" to "NM — Nuclear Medicine (Радионуклидная)",
        "PT" to "PT — Positron Emission Tomography (ПЭТ)"
    )

    /**
     * Преобразует пользовательскую строку даты рождения (ДД.ММ.ГГГГ или YYYYMMDD)
     * в формат DICOM DA (YYYYMMDD). При пустом поле возвращает пустую строку.
     */
    fun displayDateToDicomDa(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        // Формат ДД.ММ.ГГГГ (или ДД/ММ/ГГГГ, ДД-ММ-ГГГГ)
        val dotParts = trimmed.split('.', '/', '-')
        if (dotParts.size == 3) {
            val p0 = dotParts[0].trim()
            val p1 = dotParts[1].trim()
            val p2 = dotParts[2].trim()
            if (p0.length in 1..2 && p1.length in 1..2 && p2.length == 4) {
                val day = p0.toIntOrNull()
                val month = p1.toIntOrNull()
                val year = p2.toIntOrNull()
                if (day != null && month != null && year != null &&
                    year in 1850..2100 && month in 1..12 && day in 1..31
                ) {
                    return String.format(Locale.US, "%04d%02d%02d", year, month, day)
                }
            } else if (p0.length == 4 && p1.length in 1..2 && p2.length in 1..2) {
                val year = p0.toIntOrNull()
                val month = p1.toIntOrNull()
                val day = p2.toIntOrNull()
                if (day != null && month != null && year != null &&
                    year in 1850..2100 && month in 1..12 && day in 1..31
                ) {
                    return String.format(Locale.US, "%04d%02d%02d", year, month, day)
                }
            }
        }

        // Если уже введено 8 цифр YYYYMMDD или ДДММГГГГ
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length == 8) {
            val first4 = digits.substring(0, 4).toIntOrNull() ?: 0
            val mid2 = digits.substring(4, 6).toIntOrNull() ?: 0
            val last2 = digits.substring(6, 8).toIntOrNull() ?: 0
            if (first4 in 1850..2100 && mid2 in 1..12 && last2 in 1..31) {
                return digits
            }
            val dd = digits.substring(0, 2).toIntOrNull() ?: 0
            val mm = digits.substring(2, 4).toIntOrNull() ?: 0
            val yyyy = digits.substring(4, 8).toIntOrNull() ?: 0
            if (yyyy in 1850..2100 && mm in 1..12 && dd in 1..31) {
                return String.format(Locale.US, "%04d%02d%02d", yyyy, mm, dd)
            }
        }
        return ""
    }

    /**
     * Преобразует строку DICOM DA (YYYYMMDD) в пользовательский вид ДД.ММ.ГГГГ.
     */
    fun dicomDaToDisplayDate(dicomDa: String): String {
        val digits = dicomDa.trim().filter { it.isDigit() }
        if (digits.length == 8) {
            val yyyy = digits.substring(0, 4)
            val mm = digits.substring(4, 6)
            val dd = digits.substring(6, 8)
            return "$dd.$mm.$yyyy"
        }
        return dicomDa.trim()
    }

    /**
     * Нормализует код пола пациента для записи в DICOM (0010,0040) CS:
     * допустимы только "M", "F", "O" или "" (пустое значение).
     */
    fun normalizePatientSex(sexInput: String): String {
        return when (sexInput.trim().uppercase(Locale.ROOT)) {
            "M", "МУЖСКОЙ", "МУЖ" -> "M"
            "F", "ЖЕНСКИЙ", "ЖЕН" -> "F"
            "O", "ДРУГОЙ" -> "O"
            else -> ""
        }
    }

    /**
     * Генерирует стандартный уникальный DICOM UID через dcm4che UIDUtils.createUID()
     * с проверкой ограничения длины до 64 символов (DICOM VR UI).
     */
    fun createStandardUid(): String {
        var uid = UIDUtils.createUID()
        if (uid.length > MAX_DICOM_UID_LENGTH) {
            uid = uid.take(MAX_DICOM_UID_LENGTH).trimEnd('.')
        }
        check(isValidDicomUid(uid)) { "Сгенерированный UID не прошёл валидацию DICOM PS3.5: $uid" }
        return uid
    }

    /**
     * Проверяет соответствие строки правилам DICOM UID (PS3.5 Section 9):
     * - длина от 1 до 64 символов;
     * - только цифры '0'..'9' и разделитель '.';
     * - не начинается и не заканчивается точкой, нет пустых компонентов '..'.
     */
    fun isValidDicomUid(uid: String): Boolean {
        if (uid.isEmpty() || uid.length > MAX_DICOM_UID_LENGTH) return false
        if (uid.first() == '.' || uid.last() == '.') return false
        if (uid.contains("..")) return false
        return uid.all { it in '0'..'9' || it == '.' }
    }

    /**
     * Создаёт новую тройку уникальных UID (StudyInstanceUID, SeriesInstanceUID, SOPInstanceUID)
     * для нового тестового изображения со стандартным SOP Class Secondary Capture (1.2.840.10008.5.1.4.1.1.7).
     */
    fun createNewObjectUids(): GeneratedDicomObjectUids {
        val studyUid = createStandardUid()
        var seriesUid = createStandardUid()
        while (seriesUid == studyUid) {
            seriesUid = createStandardUid()
        }
        var sopUid = createStandardUid()
        while (sopUid == studyUid || sopUid == seriesUid) {
            sopUid = createStandardUid()
        }
        return GeneratedDicomObjectUids(
            studyInstanceUid = studyUid,
            seriesInstanceUid = seriesUid,
            sopInstanceUid = sopUid,
            sopClassUid = SECONDARY_CAPTURE_SOP_CLASS_UID,
            mediaStorageSopClassUid = SECONDARY_CAPTURE_SOP_CLASS_UID,
            mediaStorageSopInstanceUid = sopUid
        )
    }

    /**
     * Вычисляет сигнатуру содержимого тестового объекта (значимые метаданные пациента + параметры и пиксели изображения).
     * Позволяет точно определить, изменил ли пользователь тестовое изображение/метаданные или повторяет отправку того же объекта.
     */
    fun computeContentSignature(
        config: ScuSendConfig,
        freehandStrokes: List<FreehandStroke> = emptyList(),
        freehandPointsCount: Int = 0
    ): String {
        val dicomBirthDate = displayDateToDicomDa(
            config.patientBirthDateDisplay.ifBlank { config.patientBirthDate }
        )
        val normalizedSex = normalizePatientSex(config.patientSex)
        val normalizedModality = config.modality.trim().uppercase(Locale.ROOT).ifEmpty { "OT" }
        val effectiveText = if (config.customText.isNotEmpty()) {
            config.customText
        } else {
            listOf(config.customTextLine1, config.customTextLine2)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        val strokesDigest = if (freehandStrokes.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            for (s in freehandStrokes) {
                md.update(s.strokeWidthPx.toBits().toByte())
                for (pt in s.points) {
                    md.update((pt.x * 1000f).toInt().toByte())
                    md.update((pt.y * 1000f).toInt().toByte())
                }
            }
            md.digest().take(8).joinToString("") { "%02x".format(it) }
        } else {
            "pts:$freehandPointsCount"
        }

        val modeSpecificPayload = when (config.imageMode) {
            TestImageMode.TEXT -> "TEXT:$effectiveText"
            TestImageMode.EMOJI -> "EMOJI:${config.smileySeed}"
            TestImageMode.FREEHAND -> "FREEHAND:$strokesDigest"
        }

        return listOf(
            config.patientName.trim(),
            config.patientId.trim(),
            dicomBirthDate,
            normalizedSex,
            normalizedModality,
            config.studyDescription.trim(),
            config.seriesDescription.trim(),
            modeSpecificPayload
        ).joinToString("|")
    }

    /**
     * Формирует полноценный подготовленный DICOM-объект Secondary Capture Image Storage (1.2.840.10008.5.1.4.1.1.7)
     * с реальными 8-битными Pixel Data (512 × 512, MONOCHROME2):
     * - Если `existingObject == null`, `forceNewObject == true` или изменилось изображение/значимые метаданные (`contentSignature`),
     *   создаются НОВЫЕ уникальные StudyInstanceUID, SeriesInstanceUID и SOPInstanceUID.
     * - Если выполняется повторная попытка отправки ТОГО ЖЕ подготовленного объекта (`forceNewObject == false`
     *   и `contentSignature` не изменилась), сохраняются прежние UID, дата/время и пиксельные данные объекта.
     */
    fun prepareSecondaryCaptureObject(
        config: ScuSendConfig,
        freehandStrokes: List<FreehandStroke> = emptyList(),
        freehandPointsCount: Int = freehandStrokes.sumOf { it.points.size },
        existingObject: PreparedSecondaryCaptureDicomObject? = null,
        forceNewObject: Boolean = false,
        transferSyntaxUid: String = UID.ExplicitVRLittleEndian
    ): PreparedSecondaryCaptureDicomObject {
        val signature = computeContentSignature(config, freehandStrokes, freehandPointsCount)
        val reuseExisting = !forceNewObject && existingObject != null && existingObject.contentSignature == signature

        val uids = if (reuseExisting) {
            existingObject!!.uids
        } else {
            createNewObjectUids()
        }

        val now = Date()
        val daFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val tmFormat = SimpleDateFormat("HHmmss", Locale.US)
        val studyDate = if (reuseExisting) existingObject!!.studyDate else daFormat.format(now)
        val studyTime = if (reuseExisting) existingObject!!.studyTime else tmFormat.format(now)

        val pixelBytes = if (reuseExisting && existingObject!!.pixelDataBytes.size == DEFAULT_ROWS * DEFAULT_COLUMNS) {
            existingObject.pixelDataBytes
        } else {
            ScuTestImageRenderer.renderPixelData(config, freehandStrokes)
        }

        val normalizedModality = config.modality.trim().uppercase(Locale.ROOT).ifEmpty { "OT" }
        val normalizedBirthDate = displayDateToDicomDa(
            config.patientBirthDateDisplay.ifBlank { config.patientBirthDate }
        )
        val normalizedSex = normalizePatientSex(config.patientSex)

        val dataset = Attributes().apply {
            // Важно: устанавливаем SpecificCharacterSet = ISO_IR 192 (UTF-8) ДО строковых атрибутов,
            // чтобы dcm4che3 кодировал кириллицу в PatientName / StudyDescription / SeriesDescription в UTF-8.
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.ImageType, VR.CS, "DERIVED", "SECONDARY")
            setString(Tag.SOPClassUID, VR.UI, SECONDARY_CAPTURE_SOP_CLASS_UID)
            setString(Tag.SOPInstanceUID, VR.UI, uids.sopInstanceUid)
            setString(Tag.StudyDate, VR.DA, studyDate)
            setString(Tag.StudyTime, VR.TM, studyTime)
            setString(Tag.ContentDate, VR.DA, studyDate)
            setString(Tag.ContentTime, VR.TM, studyTime)
            setString(Tag.AccessionNumber, VR.SH, "")
            setString(Tag.Modality, VR.CS, normalizedModality)
            setString(Tag.ConversionType, VR.CS, "WSD")
            setString(Tag.Manufacturer, VR.LO, "DICOM Kit Android")
            setString(Tag.ReferringPhysicianName, VR.PN, "")
            setString(Tag.PatientName, VR.PN, config.patientName.trim())
            setString(Tag.PatientID, VR.LO, config.patientId.trim())
            setString(Tag.PatientBirthDate, VR.DA, normalizedBirthDate)
            setString(Tag.PatientSex, VR.CS, normalizedSex)
            setString(Tag.StudyInstanceUID, VR.UI, uids.studyInstanceUid)
            setString(Tag.SeriesInstanceUID, VR.UI, uids.seriesInstanceUid)
            setString(Tag.StudyID, VR.SH, "1")
            setInt(Tag.SeriesNumber, VR.IS, 1)
            setInt(Tag.InstanceNumber, VR.IS, 1)
            setString(Tag.PatientOrientation, VR.CS, "")
            setString(Tag.StudyDescription, VR.LO, config.studyDescription.trim())
            setString(Tag.SeriesDescription, VR.LO, config.seriesDescription.trim())
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.Rows, VR.US, DEFAULT_ROWS)
            setInt(Tag.Columns, VR.US, DEFAULT_COLUMNS)
            setInt(Tag.BitsAllocated, VR.US, 8)
            setInt(Tag.BitsStored, VR.US, 8)
            setInt(Tag.HighBit, VR.US, 7)
            setInt(Tag.PixelRepresentation, VR.US, 0)
            setDouble(Tag.WindowCenter, VR.DS, 128.0)
            setDouble(Tag.WindowWidth, VR.DS, 256.0)
            setBytes(Tag.PixelData, VR.OB, pixelBytes)
        }

        val fmi = dataset.createFileMetaInformation(transferSyntaxUid)
        check(fmi.getString(Tag.MediaStorageSOPClassUID) == SECONDARY_CAPTURE_SOP_CLASS_UID) {
            "MediaStorageSOPClassUID должен совпадать с SOPClassUID ($SECONDARY_CAPTURE_SOP_CLASS_UID)"
        }
        check(fmi.getString(Tag.MediaStorageSOPInstanceUID) == uids.sopInstanceUid) {
            "MediaStorageSOPInstanceUID должен совпадать с SOPInstanceUID (${uids.sopInstanceUid})"
        }

        return PreparedSecondaryCaptureDicomObject(
            uids = uids,
            modality = normalizedModality,
            contentSignature = signature,
            studyDate = studyDate,
            studyTime = studyTime,
            pixelDataBytes = pixelBytes,
            dataset = dataset,
            fileMetaInformation = fmi
        )
    }

    /**
     * Записывает подготовленный объект Secondary Capture во временный файл внутри приватного кэша приложения
     * (используется исключительно для отправки/повторной отправки SCU и никогда не попадает в список принятых исследований).
     */
    fun writeToTempCacheFile(
        preparedObject: PreparedSecondaryCaptureDicomObject,
        tempCacheDir: File
    ): File {
        if (!tempCacheDir.exists()) {
            tempCacheDir.mkdirs()
        }
        // Удаляем устаревшие временные файлы других объектов из кэша SCU
        val currentFileName = "scu_prepared_${preparedObject.uids.sopInstanceUid}.dcm"
        tempCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != currentFileName) {
                runCatching { file.delete() }
            }
        }
        val targetFile = File(tempCacheDir, currentFileName)
        DicomOutputStream(targetFile).use { dos ->
            dos.writeDataset(preparedObject.fileMetaInformation, preparedObject.dataset)
        }
        return targetFile
    }

    /**
     * Полная очистка временного кэша подготовленных тестовых объектов SCU.
     */
    fun clearTempCache(tempCacheDir: File) {
        if (tempCacheDir.exists()) {
            tempCacheDir.listFiles()?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }
        }
    }
}
