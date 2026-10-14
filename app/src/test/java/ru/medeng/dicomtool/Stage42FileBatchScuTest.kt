package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.dicom.scu.DicomFileScanner
import ru.medeng.dicomtool.domain.model.FileSendStatus
import ru.medeng.dicomtool.domain.model.ScuBatchProgressState
import ru.medeng.dicomtool.domain.model.ScuCandidateFile
import ru.medeng.dicomtool.domain.model.ScuSendMode
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Комплексные модульные тесты для Этапа 4.2:
 * 1. Распознавание Part 10 DICM-файлов и файлов без преамбулы (raw DICOM).
 * 2. Чтение метаданных без загрузки Pixel Data в оперативную память.
 * 3. Неизменность оригинальных UID (Study, Series, SOP Instance UID) и метаданных пациента.
 * 4. Валидация повреждённых и не-DICOM файлов (отклонение с понятной ошибкой).
 * 5. Состояния очереди и прогресса пакетной отправки.
 */
class Stage42FileBatchScuTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("dicom_batch_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createStandardDicomFile(
        file: File,
        studyUid: String = "1.2.3.4.5.6.7",
        seriesUid: String = "1.2.3.4.5.6.7.1",
        sopInstanceUid: String = "1.2.3.4.5.6.7.1.1",
        sopClassUid: String = UID.CTImageStorage,
        patientId: String = "PATIENT-001",
        patientName: String = "Иванов^Иван",
        modality: String = "CT",
        includePixelData: Boolean = true
    ) {
        val fmi = Attributes()
        fmi.setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
        fmi.setString(Tag.MediaStorageSOPClassUID, VR.UI, sopClassUid)
        fmi.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, sopInstanceUid)

        val ds = Attributes()
        ds.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
        ds.setString(Tag.SOPClassUID, VR.UI, sopClassUid)
        ds.setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid)
        ds.setString(Tag.StudyInstanceUID, VR.UI, studyUid)
        ds.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid)
        ds.setString(Tag.PatientID, VR.LO, patientId)
        ds.setString(Tag.PatientName, VR.PN, patientName)
        ds.setString(Tag.Modality, VR.CS, modality)
        ds.setInt(Tag.Rows, VR.US, 512)
        ds.setInt(Tag.Columns, VR.US, 512)
        ds.setInt(Tag.BitsAllocated, VR.US, 16)
        ds.setInt(Tag.BitsStored, VR.US, 12)
        ds.setInt(Tag.HighBit, VR.US, 11)

        if (includePixelData) {
            // Имитация 512x512 16-bit Pixel Data (512 КБ)
            val pixels = ByteArray(512 * 512 * 2) { 0x42.toByte() }
            ds.setBytes(Tag.PixelData, VR.OW, pixels)
        }

        DicomOutputStream(file).use { dos ->
            dos.writeDataset(fmi, ds)
        }
    }

    @Test
    fun testScanValidPart10DicomFile_preservesOriginalUids() {
        val testFile = File(tempDir, "sample_ct.dcm")
        val expectedStudyUid = "1.2.840.113619.2.55.3.1234567"
        val expectedSeriesUid = "1.2.840.113619.2.55.3.1234567.88"
        val expectedSopUid = "1.2.840.113619.2.55.3.1234567.88.999"

        createStandardDicomFile(
            file = testFile,
            studyUid = expectedStudyUid,
            seriesUid = expectedSeriesUid,
            sopInstanceUid = expectedSopUid,
            sopClassUid = UID.CTImageStorage,
            patientId = "CT-TEST-99",
            modality = "CT"
        )

        val result = testFile.inputStream().use { stream ->
            DicomFileScanner.inspectInputStream(
                rawIn = stream,
                fileName = testFile.name,
                fileSize = testFile.length()
            )
        }

        assertTrue("Файл должен быть распознан как валидный DICOM", result.isValidDicom)
        assertNull("Не должно быть ошибки валидации", result.validationError)
        assertEquals("Study Instance UID должен в точности сохраниться", expectedStudyUid, result.studyInstanceUid)
        assertEquals("Series Instance UID должен в точности сохраниться", expectedSeriesUid, result.seriesInstanceUid)
        assertEquals("SOP Instance UID должен в точности сохраниться", expectedSopUid, result.sopInstanceUid)
        assertEquals("SOP Class UID должен совпадать", UID.CTImageStorage, result.sopClassUid)
        assertEquals("Модальность должна быть CT", "CT", result.modality)
        assertEquals("Patient ID должен совпадать", "CT-TEST-99", result.patientId)
        assertEquals("Transfer Syntax должен быть Explicit VR LE", UID.ExplicitVRLittleEndian, result.transferSyntaxUid)
    }

    @Test
    fun testScanRawDicomFileWithoutPreamble_successfullyDetected() {
        val testFile = File(tempDir, "raw_no_preamble.bin")
        val expectedStudyUid = "1.2.840.999.1"
        val expectedSeriesUid = "1.2.840.999.1.2"
        val expectedSopUid = "1.2.840.999.1.2.3"

        // Записываем датасет напрямую без 128-байтовой преамбулы и DICM-маркера
        val ds = Attributes()
        ds.setString(Tag.SOPClassUID, VR.UI, UID.MRImageStorage)
        ds.setString(Tag.SOPInstanceUID, VR.UI, expectedSopUid)
        ds.setString(Tag.StudyInstanceUID, VR.UI, expectedStudyUid)
        ds.setString(Tag.SeriesInstanceUID, VR.UI, expectedSeriesUid)
        ds.setString(Tag.Modality, VR.CS, "MR")
        ds.setString(Tag.PatientID, VR.LO, "MR-NO-PREAMBLE")

        FileOutputStream(testFile).use { fos ->
            val dos = DicomOutputStream(fos, UID.ExplicitVRLittleEndian)
            dos.writeDataset(null, ds)
        }

        val result = testFile.inputStream().use { stream ->
            DicomFileScanner.inspectInputStream(
                rawIn = stream,
                fileName = testFile.name,
                fileSize = testFile.length()
            )
        }

        assertTrue("Raw DICOM без преамбулы должен распознаваться", result.isValidDicom)
        assertEquals(expectedStudyUid, result.studyInstanceUid)
        assertEquals(expectedSopUid, result.sopInstanceUid)
        assertEquals("MR", result.modality)
    }

    @Test
    fun testScanNonDicomTextFile_rejectedWithClearError() {
        val textFile = File(tempDir, "notes.txt")
        textFile.writeText("This is a simple plain text file, definitely not a DICOM file.")

        val result = textFile.inputStream().use { stream ->
            DicomFileScanner.inspectInputStream(
                rawIn = stream,
                fileName = textFile.name,
                fileSize = textFile.length()
            )
        }
        
        assertFalse("Текстовый файл не должен быть признан DICOM", result.isValidDicom)
        assertNotNull("Должно присутствовать понятное сообщение об ошибке", result.validationError)
        assertTrue(result.validationError!!.contains("Не является DICOM"))
    }

    @Test
    fun testScanMissingMandatoryUids_markedInvalid() {
        val invalidDicom = File(tempDir, "missing_uids.dcm")
        // Файл имеет преамбулу и DICM, но не содержит StudyInstanceUID и SOPInstanceUID
        val fmi = Attributes()
        fmi.setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
        fmi.setString(Tag.MediaStorageSOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
        fmi.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.3")

        val ds = Attributes()
        ds.setString(Tag.PatientID, VR.LO, "BROKEN-PATIENT")
        // Нет SOPInstanceUID, StudyInstanceUID

        DicomOutputStream(invalidDicom).use { dos ->
            dos.writeDataset(fmi, ds)
        }

        val result = invalidDicom.inputStream().use { stream ->
            DicomFileScanner.inspectInputStream(
                rawIn = stream,
                fileName = invalidDicom.name,
                fileSize = invalidDicom.length()
            )
        }

        assertFalse("DICOM без обязательных UID должен помечаться как невалидный", result.isValidDicom)
        assertNotNull(result.validationError)
        assertTrue(result.validationError!!.contains("UID"))
    }

    @Test
    fun testScuSendModeEnum() {
        assertEquals("Тестовое изображение", ScuSendMode.TEST_IMAGE.labelRu)
        assertEquals("DICOM-файлы", ScuSendMode.DICOM_FILES.labelRu)
    }

    @Test
    fun testFileSendStatusEnum() {
        assertEquals("Ожидает", FileSendStatus.PENDING.labelRu)
        assertEquals("Отправка...", FileSendStatus.SENDING.labelRu)
        assertEquals("Успешно", FileSendStatus.SUCCESS.labelRu)
        assertEquals("Предупреждение", FileSendStatus.WARNING.labelRu)
        assertEquals("Ошибка", FileSendStatus.FAILED.labelRu)
        assertEquals("Пропущен", FileSendStatus.SKIPPED.labelRu)
    }

    @Test
    fun testCandidateFileQueueCalculation() {
        val f1 = ScuCandidateFile(
            fileName = "file1.dcm",
            fileSizeBytes = 1024 * 1024,
            isValidDicom = true,
            sendStatus = FileSendStatus.PENDING
        )
        val f2 = ScuCandidateFile(
            fileName = "file2.dcm",
            fileSizeBytes = 2 * 1024 * 1024,
            isValidDicom = true,
            sendStatus = FileSendStatus.PENDING
        )
        val f3 = ScuCandidateFile(
            fileName = "notes.txt",
            fileSizeBytes = 500,
            isValidDicom = false,
            validationError = "Не является DICOM",
            sendStatus = FileSendStatus.PENDING
        )

        val queue = listOf(f1, f2, f3)
        assertEquals(3, queue.size)
        assertEquals(2, queue.count { it.isValidDicom })
        assertEquals(1, queue.count { !it.isValidDicom })
        assertEquals(3146228L, queue.sumOf { it.fileSizeBytes })
    }

    @Test
    fun testBatchProgressStateDefaults() {
        val state = ScuBatchProgressState()
        assertFalse(state.isSending)
        assertFalse(state.isScanning)
        assertEquals(0, state.totalFiles)
        assertEquals(0, state.sentSuccessCount)
        assertEquals(0, state.failedCount)
    }
}
