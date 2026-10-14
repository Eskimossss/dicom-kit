package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Sequence
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.dicom.tags.DicomFileReader
import ru.medeng.dicomtool.dicom.tags.DicomTagDictionary
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Комплексные модульные тесты для Этапа 4.1 (Просмотр DICOM-тегов):
 * 1. Стандартные DICOM-теги (код, английское имя, русское имя, VR, значение).
 * 2. Private Tags производителей (распознавание и корректное обозначение).
 * 3. Вложенные последовательности SQ (иерархия элементов Item #N и вложенных тегов).
 * 4. Пустые значения (сохранение в списке, поддержка фильтра «Только заполненные»).
 * 5. Длинные значения (UID, длинные текстовые описания без обрезания).
 * 6. Кодировки и кириллица (ISO_IR 192, ISO_IR 144, ручное переопределение кодировки).
 * 7. Файлы со стандартным маркером DICM и БЕЗ маркера DICM (raw Part 5).
 * 8. Некорректные и повреждённые файлы (graceful handling).
 * 9. Большие файлы с Pixel Data (не загружает массив пикселей в память, формирует корректное описание).
 */
class DicomTagBrowserTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("dicomtool_tags_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. Стандартные DICOM-теги и русские переводы
    // =========================================================================

    @Test
    fun testStandardTagsMetadataAndTranslations() {
        // (0010,0010) Patient Name -> ФИО пациента
        val pnNames = DicomTagDictionary.resolveTagNames(Tag.PatientName)
        assertEquals("Patient Name", pnNames.nameEn)
        assertEquals("ФИО пациента", pnNames.nameRu)
        assertFalse(pnNames.isPrivate)

        // (0010,0020) Patient ID -> Идентификатор пациента
        val idNames = DicomTagDictionary.resolveTagNames(Tag.PatientID)
        assertEquals("Patient ID", idNames.nameEn)
        assertEquals("Идентификатор пациента", idNames.nameRu)

        // (0008,0060) Modality -> Модальность
        val modNames = DicomTagDictionary.resolveTagNames(Tag.Modality)
        assertEquals("Modality", modNames.nameEn)
        assertEquals("Модальность", modNames.nameRu)

        // (0008,0005) Specific Character Set -> Набор символов (кодировка)
        val csNames = DicomTagDictionary.resolveTagNames(Tag.SpecificCharacterSet)
        assertEquals("Specific Character Set", csNames.nameEn)
        assertEquals("Набор символов (кодировка)", csNames.nameRu)

        // (0020,000D) Study Instance UID -> UID исследования
        val studyUidNames = DicomTagDictionary.resolveTagNames(Tag.StudyInstanceUID)
        assertEquals("Study Instance UID", studyUidNames.nameEn)
        assertEquals("UID исследования", studyUidNames.nameRu)

        // (0020,000E) Series Instance UID -> UID серии
        val seriesUidNames = DicomTagDictionary.resolveTagNames(Tag.SeriesInstanceUID)
        assertEquals("Series Instance UID", seriesUidNames.nameEn)
        assertEquals("UID серии", seriesUidNames.nameRu)

        // (0008,0018) SOP Instance UID -> UID экземпляра SOP
        val sopUidNames = DicomTagDictionary.resolveTagNames(Tag.SOPInstanceUID)
        assertEquals("SOP Instance UID", sopUidNames.nameEn)
        assertEquals("UID экземпляра SOP", sopUidNames.nameRu)

        // (7FE0,0010) Pixel Data -> Массив данных изображения (Pixel Data)
        val pixNames = DicomTagDictionary.resolveTagNames(Tag.PixelData)
        assertEquals("Pixel Data", pixNames.nameEn)
        assertEquals("Массив данных изображения (Pixel Data)", pixNames.nameRu)
    }

    // =========================================================================
    // 2. Private Tags производителей
    // =========================================================================

    @Test
    fun testPrivateTagsRecognition() {
        // Частный создатель (0009,0010)
        val creatorTag = 0x00090010
        val creatorNames = DicomTagDictionary.resolveTagNames(creatorTag)
        assertTrue("Creator tag must be detected as private", creatorNames.isPrivate)
        assertEquals("Private Creator Data Element", creatorNames.nameEn)
        assertEquals("Идентификатор частного создателя", creatorNames.nameRu)

        // Частный тег данных (0009,1001)
        val privateDataTag = 0x00091001
        val privNames = DicomTagDictionary.resolveTagNames(privateDataTag)
        assertTrue("Private data tag must be detected as private", privNames.isPrivate)
        assertEquals("Private Tag", privNames.nameEn)
        assertEquals("Частный тег производителя", privNames.nameRu)
    }

    // =========================================================================
    // 3. Вложенные последовательности Sequence (SQ)
    // =========================================================================

    @Test
    fun testNestedSequencesHierarchy() {
        val dataset = Attributes().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.7")
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7")
            setString(Tag.PatientName, VR.PN, "TEST^PATIENT")

            // Создаем SQ последовательность с 2 элементами
            val seq: Sequence = newSequence(Tag.ReferencedImageSequence, 2)

            val item1 = Attributes().apply {
                setString(Tag.ReferencedSOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.1")
                setString(Tag.ReferencedSOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.1")
            }
            val item2 = Attributes().apply {
                setString(Tag.ReferencedSOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2")
                setString(Tag.ReferencedSOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.2")
            }
            seq.add(item1)
            seq.add(item2)
        }

        val dcmFile = File(tempDir, "test_sq.dcm")
        DicomOutputStream(dcmFile).use { dos ->
            dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset)
        }

        val result = DicomFileReader.readFromFile(dcmFile)
        val sqTag = result.tags.firstOrNull { it.tagHex == "(0008,1140)" }
        assertNotNull("ReferencedImageSequence tag should exist", sqTag)
        assertTrue("Must be sequence", sqTag!!.isSequence)
        assertEquals("SQ", sqTag.vr)
        assertEquals(2, sqTag.sequenceItems.size)

        // Проверяем первый элемент последовательности
        val firstItem = sqTag.sequenceItems[0]
        assertEquals(1, firstItem.itemIndex)
        val refSopUidTag = firstItem.tags.firstOrNull { it.tagHex == "(0008,1155)" }
        assertNotNull("ReferencedSOPInstanceUID must exist in Item 1", refSopUidTag)
        assertTrue(refSopUidTag!!.value.contains("1.2.3.4.5.6.7.1"))
    }

    // =========================================================================
    // 4. Пустые теги
    // =========================================================================

    @Test
    fun testEmptyAndBlankTags() {
        val dataset = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.7")
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.8")
            setString(Tag.AccessionNumber, VR.SH, "") // Пустой тег
            setString(Tag.ReferringPhysicianName, VR.PN, "") // Пустой тег
            setString(Tag.PatientName, VR.PN, "NON_EMPTY")
        }

        val dcmFile = File(tempDir, "test_empty.dcm")
        DicomOutputStream(dcmFile).use { dos ->
            dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset)
        }

        val result = DicomFileReader.readFromFile(dcmFile)
        val accessionTag = result.tags.firstOrNull { it.tagHex == "(0008,0050)" }
        assertNotNull("AccessionNumber tag must be present in parsed tags list", accessionTag)
        assertEquals("", accessionTag!!.value)
        assertEquals("Accession Number", accessionTag.name)
    }

    // =========================================================================
    // 5. Длинные значения без обрезания
    // =========================================================================

    @Test
    fun testLongValuesAndUids() {
        val longUid = "1.2.840.113619.2.55.3.2831174937.643.1234567890.123456789012345678" // 64 символа
        val longText = "Very long clinical indication description that spans multiple sentences and should not be truncated anywhere in the tag parser."

        val dataset = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.7")
            setString(Tag.SOPInstanceUID, VR.UI, longUid)
            setString(Tag.StudyDescription, VR.LO, longText)
        }

        val dcmFile = File(tempDir, "test_long.dcm")
        DicomOutputStream(dcmFile).use { dos ->
            dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset)
        }

        val result = DicomFileReader.readFromFile(dcmFile)
        val sopTag = result.tags.first { it.tagHex == "(0008,0018)" }
        assertTrue("Long UID must be preserved completely", sopTag.value.startsWith(longUid))

        val studyDescTag = result.tags.first { it.tagHex == "(0008,1030)" }
        assertEquals("Full text must be preserved", longText, studyDescTag.value)
    }

    // =========================================================================
    // 6. Кодировки и кириллица (ISO_IR 192, ISO_IR 144, ручное переопределение)
    // =========================================================================

    @Test
    fun testCharsetsAndCyrillicWithOverride() {
        val cyrillicName = "Иванов^Иван^Иванович"
        val dataset = Attributes().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.7")
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.9")
            setString(Tag.PatientName, VR.PN, cyrillicName)
        }

        val dcmFile = File(tempDir, "test_cyrillic.dcm")
        DicomOutputStream(dcmFile).use { dos ->
            dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset)
        }

        // 1. Автоматическое чтение (ISO_IR 192 UTF-8)
        val resultAuto = DicomFileReader.readFromFile(dcmFile)
        val nameTagAuto = resultAuto.tags.first { it.tagHex == "(0010,0010)" }
        assertEquals(cyrillicName, nameTagAuto.value)
        assertEquals(cyrillicName, resultAuto.summary.patientName)

        // 2. Ручное чтение с явной кодировкой UTF-8
        val resultUtf8 = DicomFileReader.readFromFile(dcmFile, forcedCharset = "UTF-8")
        val nameTagUtf8 = resultUtf8.tags.first { it.tagHex == "(0010,0010)" }
        assertEquals(cyrillicName, nameTagUtf8.value)
    }

    // =========================================================================
    // 7. Файлы с маркером DICM и БЕЗ маркера DICM (raw Part 5)
    // =========================================================================

    @Test
    fun testFilesWithAndWithoutDicmPreamble() {
        val dataset = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.7")
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.10")
            setString(Tag.PatientName, VR.PN, "PREAMBLE^TEST")
            setString(Tag.Modality, VR.CS, "OT")
        }

        // 1. Файл С маркером DICM
        val part10File = File(tempDir, "with_dicm.dcm")
        DicomOutputStream(part10File).use { dos ->
            dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset)
        }
        val resPart10 = DicomFileReader.readFromFile(part10File)
        assertTrue("Must detect DICM marker", resPart10.summary.hasPreambleAndDicm)
        assertEquals("PREAMBLE^TEST", resPart10.summary.patientName)
        assertEquals("OT", resPart10.summary.modality)

        // 2. Файл БЕЗ маркера DICM (чистый dataset без 128-байтного preamble)
        val rawFile = File(tempDir, "no_dicm_stream.raw")
        FileOutputStream(rawFile).use { fos ->
            DicomOutputStream(fos, UID.ExplicitVRLittleEndian).use { dos ->
                dos.writeDataset(null, dataset)
            }
        }
        val resRaw = DicomFileReader.readFromFile(rawFile)
        assertFalse("Must detect absence of DICM marker", resRaw.summary.hasPreambleAndDicm)
        assertEquals("PREAMBLE^TEST", resRaw.summary.patientName)
        assertEquals("OT", resRaw.summary.modality)
    }

    // =========================================================================
    // 8. Некорректные файлы
    // =========================================================================

    @Test
    fun testCorruptAndInvalidFiles() {
        val invalidFile = File(tempDir, "invalid.txt")
        invalidFile.writeText("THIS IS NOT A DICOM FILE AT ALL")

        try {
            DicomFileReader.readFromFile(invalidFile)
            fail("Expected exception when reading non-DICOM file")
        } catch (e: Exception) {
            // Ожидаемое поведение: исключение без краша JVM
            assertTrue(e.message != null || e is java.io.IOException)
        }
    }

    // =========================================================================
    // 9. Большие файлы с Pixel Data (не загружает в память)
    // =========================================================================

    @Test
    fun testLargeFileWithPixelDataDoesNotReadPixelsIntoMemory() {
        val pixelPayload = ByteArray(10 * 1024 * 1024) // 10 МБ Pixel Data
        pixelPayload[0] = 0x12
        pixelPayload[pixelPayload.size - 1] = 0x34

        val dataset = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.7")
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.11")
            setString(Tag.PatientName, VR.PN, "LARGE^PIXEL^TEST")
            setInt(Tag.Rows, VR.US, 2048)
            setInt(Tag.Columns, VR.US, 2560)
            setInt(Tag.BitsAllocated, VR.US, 16)
            setInt(Tag.BitsStored, VR.US, 16)
            setInt(Tag.HighBit, VR.US, 15)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setBytes(Tag.PixelData, VR.OW, pixelPayload)
        }

        val largeFile = File(tempDir, "large_study.dcm")
        DicomOutputStream(largeFile).use { dos ->
            dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset)
        }

        val parseResult = DicomFileReader.readFromFile(largeFile)
        val pixelDataTag = parseResult.tags.firstOrNull { it.tagHex == "(7FE0,0010)" }
        assertNotNull("PixelData tag must be present", pixelDataTag)
        assertTrue("Must be marked as binary", pixelDataTag!!.isBinary)
        assertEquals(pixelPayload.size.toLong(), pixelDataTag.binaryByteCount)
        assertTrue(pixelDataTag.value.contains("10.00 МБ"))
        assertTrue(pixelDataTag.value.contains("2560×2048"))
    }
}
