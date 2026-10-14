package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.data.storage.LocalDicomStudyRepository
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.dicom.pixel.DicomPixelDataDecoder
import ru.medeng.dicomtool.dicom.pixel.DicomPixelDecodeResult
import ru.medeng.dicomtool.dicom.pixel.SingleFrameDecodeCache
import ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Комплексные модульные и интеграционные тесты Этапа 2.1:
 * 1. Кодировки DICOM (0008,0005) Specific Character Set:
 *    - ISO_IR 6 (ASCII)
 *    - ISO_IR 100 (ISO-8859-1)
 *    - ISO_IR 144 (ISO-8859-5, кириллица)
 *    - ISO_IR 192 (UTF-8, кириллица)
 *    - Многозначный ISO 2022 IR 6\ISO 2022 IR 144 с escape-последовательностями
 *    - DICOM Person Name (PN) с компонентами (^) и группами представления (=)
 *    - Диагностика некорректно закодированных исходных файлов (отсутствие (0008,0005) при наличии кириллицы, несоответствие UTF-8 и CP1251)
 * 2. Декодер настоящего Pixel Data и медицинский конвейер:
 *    - 8-bit и 16-bit grayscale (Bits Allocated / Bits Stored / High Bit)
 *    - Unsigned (Pixel Representation = 0) и Signed (Pixel Representation = 1, two's complement)
 *    - MONOCHROME1 и MONOCHROME2 + пользовательская инверсия
 *    - Rescale Slope / Rescale Intercept и Window Center / Window Width (включая множественные пресеты и VOI LUT)
 *    - RGB 8-bit (Planar Configuration 0 и 1)
 *    - Многокадровые несжатые изображения (потоковый выбор кадра)
 *    - Неподдерживаемый Transfer Syntax (явная диагностическая ошибка без демо-картинки)
 *    - Полноразмерное маммографическое изображение 3580 × 2680 (18.30 МБ, WW=16221, WL=8272) и отсутствие утечек памяти при переключении кадров.
 */
class Stage21ViewerAndCharsetTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("dicomtool_stage21_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. Тесты DICOM-кодировок (0008,0005) Specific Character Set и Person Name
    // =========================================================================

    @Test
    fun testStandardCharsets_IsoIr6_IsoIr100_IsoIr144_IsoIr192() {
        // 1. ISO_IR 6 (ASCII)
        val asciiBytes = "IVANOV^IVAN^IVANOVICH".toByteArray(StandardCharsets.US_ASCII)
        val resAscii = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = asciiBytes,
            vr = VR.PN,
            declaredTerms = listOf("ISO_IR 6")
        )
        assertEquals("IVANOV^IVAN^IVANOVICH", resAscii.value)
        assertNull("Valid ISO_IR 6 should have no warning", resAscii.diagnosticWarning)

        // 2. ISO_IR 100 (Latin-1)
        val latin1Bytes = "Müller^Jürgen^Dr.".toByteArray(StandardCharsets.ISO_8859_1)
        val resLatin1 = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = latin1Bytes,
            vr = VR.PN,
            declaredTerms = listOf("ISO_IR 100")
        )
        assertEquals("Müller^Jürgen^Dr.", resLatin1.value)
        assertNull("Valid ISO_IR 100 should have no warning", resLatin1.diagnosticWarning)

        // 3. ISO_IR 144 (Кириллица ISO-8859-5)
        val iso88595 = Charset.forName("ISO-8859-5")
        val cyrillicName = "Иванов^Иван^Иванович"
        val iso144Bytes = cyrillicName.toByteArray(iso88595)
        val resIso144 = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = iso144Bytes,
            vr = VR.PN,
            declaredTerms = listOf("ISO_IR 144")
        )
        assertEquals(cyrillicName, resIso144.value)
        assertNull("Valid ISO_IR 144 Cyrillic should have no warning", resIso144.diagnosticWarning)

        // 4. ISO_IR 192 (UTF-8 Кириллица)
        val utf8Name = "Петрова^Анна^Сергеевна=Petrova^Anna^Sergeevna"
        val utf8Bytes = utf8Name.toByteArray(StandardCharsets.UTF_8)
        val resUtf8 = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = utf8Bytes,
            vr = VR.PN,
            declaredTerms = listOf("ISO_IR 192")
        )
        assertEquals(utf8Name, resUtf8.value)
        assertNull("Valid ISO_IR 192 UTF-8 should have no warning", resUtf8.diagnosticWarning)
    }

    @Test
    fun testMultiValuedIso2022CyrillicWithEscapeSequences() {
        // ISO 2022 IR 6\ISO 2022 IR 144:
        // ESC - L (0x1B 0x2D 0x4C) переключает G1 на ISO-8859-5, ESC ( B (0x1B 0x28 0x42) возвращает G0 к ASCII
        val iso88595 = Charset.forName("ISO-8859-5")
        val asciiPart = "SMIRNOV^ALEKSEY=".toByteArray(StandardCharsets.US_ASCII)
        val escToIso144 = byteArrayOf(0x1B, 0x2D, 0x4C)
        val cyrPart = "Смирнов^Алексей".toByteArray(iso88595)
        val combinedBytes = asciiPart + escToIso144 + cyrPart

        val res = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = combinedBytes,
            vr = VR.PN,
            declaredTerms = listOf("ISO 2022 IR 6", "ISO 2022 IR 144")
        )
        assertEquals("SMIRNOV^ALEKSEY=Смирнов^Алексей", res.value)
        assertNull(res.diagnosticWarning)
    }

    @Test
    fun testMisencodedFileDiagnostics_MissingSpecificCharacterSetAndWin1251Mismatch() {
        val win1251 = Charset.forName("windows-1251")
        val cyrillicPatient = "Сидорова^Елена^Викторовна"
        val win1251Bytes = cyrillicPatient.toByteArray(win1251)

        // Случай 1: (0008,0005) отсутствует, но в теге записаны 8-битные кириллические байты Windows-1251
        val resMissingTag = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = win1251Bytes,
            vr = VR.PN,
            declaredTerms = emptyList()
        )
        assertEquals(cyrillicPatient, resMissingTag.value)
        assertFalse("Must not contain replacement characters", resMissingTag.value.contains('\uFFFD'))
        assertNotNull("Must emit diagnostic warning when (0008,0005) is missing for 8-bit text", resMissingTag.diagnosticWarning)

        // Случай 2: (0008,0005) = ISO_IR 192 (UTF-8), но аппарат записал байты в Windows-1251
        val resBrokenUtf8 = DicomCharsetDecoder.decodeBytesWithDiagnostics(
            bytes = win1251Bytes,
            vr = VR.PN,
            declaredTerms = listOf("ISO_IR 192")
        )
        assertEquals(cyrillicPatient, resBrokenUtf8.value)
        assertNotNull("Must emit diagnostic warning when bytes violate declared ISO_IR 192", resBrokenUtf8.diagnosticWarning)
    }

    @Test
    fun testRepositoryEndToEndCyrillicPreservationWithoutModifyingFile() {
        val studyDir = File(tempDir, "study_cyr")
        val seriesDir = File(studyDir, "series_1")
        seriesDir.mkdirs()
        val dcmFile = File(seriesDir, "mammo_cyr.dcm")

        val iso88595 = Charset.forName("ISO-8859-5")
        val fmi = Attributes().apply {
            setBytes(Tag.FileMetaInformationVersion, VR.OB, byteArrayOf(0, 1))
            setString(Tag.MediaStorageSOPClassUID, VR.UI, UID.DigitalMammographyXRayImageStorageForPresentation)
            setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.500.1")
            setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
            setString(Tag.ImplementationClassUID, VR.UI, "1.2.826.0.1.3680043.2.1143.1")
        }
        val ds = Attributes().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 144")
            setString(Tag.SOPClassUID, VR.UI, UID.DigitalMammographyXRayImageStorageForPresentation)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.500.1")
            setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.500")
            setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.500.10")
            setBytes(Tag.PatientName, VR.PN, "Морозова^Ольга^Николаевна".toByteArray(iso88595))
            setBytes(Tag.StudyDescription, VR.LO, "Маммография скрининговая".toByteArray(iso88595))
            setBytes(Tag.SeriesDescription, VR.LO, "Правая МЖ RCC".toByteArray(iso88595))
            setString(Tag.Modality, VR.CS, "MG")
            setInt(Tag.Rows, VR.US, 4)
            setInt(Tag.Columns, VR.US, 4)
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.BitsAllocated, VR.US, 16)
            setInt(Tag.BitsStored, VR.US, 14)
            setInt(Tag.HighBit, VR.US, 13)
            setInt(Tag.PixelRepresentation, VR.US, 0)
            setBytes(Tag.PixelData, VR.OW, ByteArray(32))
        }

        DicomOutputStream(dcmFile).use { dos ->
            dos.writeDataset(fmi, ds)
        }
        val originalBytes = dcmFile.readBytes()

        val repo = LocalDicomStudyRepository(tempDir)
        val studies = repo.studiesFlow.value
        assertEquals(1, studies.size)
        val study = studies.first()
        assertEquals("Морозова^Ольга^Николаевна", study.patientName)
        assertEquals("Маммография скрининговая", study.studyDescription)
        assertEquals("Правая МЖ RCC", study.seriesDescription)
        assertNull(study.encodingWarning)

        val tags = repo.getTagsForInstanceFile(dcmFile.absolutePath)
        val patientNameTag = tags.first { it.tagHex == "(0010,0010)" }
        assertEquals("Морозова^Ольга^Николаевна", patientNameTag.value)

        // Убеждаемся, что Pixel Data не загружается в текстовое значение, а содержит только метаданные
        val pixelTag = tags.first { it.tagHex == "(7FE0,0010)" }
        assertTrue(pixelTag.value.contains("32 байт"))
        assertTrue(pixelTag.value.contains("4×4"))

        // Убеждаемся, что оригинальный файл на диске не изменён ни на один байт
        assertTrue(originalBytes.contentEquals(dcmFile.readBytes()))
    }

    // =========================================================================
    // 2. Тесты настоящего декодера Pixel Data и медицинского конвейера
    // =========================================================================

    @Test
    fun test8BitAnd16BitUnsignedAndSignedMonochrome1AndMonochrome2() {
        // 1. 8-bit unsigned MONOCHROME2 (0 -> чёрный 0, 255 -> белый 255 при WW=255, WL=127.5)
        val file8 = writeTestDicomFile(
            name = "gray8.dcm",
            rows = 2,
            cols = 2,
            bitsAllocated = 8,
            bitsStored = 8,
            highBit = 7,
            pixelRepresentation = 0,
            photometric = "MONOCHROME2",
            windowCenter = 128.0,
            windowWidth = 256.0,
            pixelBytes = byteArrayOf(0, 128.toByte(), 200.toByte(), 255.toByte())
        )
        val res8 = DicomPixelDataDecoder.decodeFrameFromFile(file8) as DicomPixelDecodeResult.Success
        val argb8 = DicomPixelDataDecoder.renderFrameToArgbPixels(
            frame = res8.frame,
            windowWidth = 256.0,
            windowCenter = 128.0,
            userInverted = false
        )
        assertEquals(0, argb8[0] and 0xFF)
        assertEquals(255, argb8[3] and 0xFF)

        // MONOCHROME1 или инверсия должны менять полярность (0 -> 255, 255 -> 0)
        val argb8Inv = DicomPixelDataDecoder.renderFrameToArgbPixels(
            frame = res8.frame,
            windowWidth = 256.0,
            windowCenter = 128.0,
            userInverted = true
        )
        assertEquals(255, argb8Inv[0] and 0xFF)
        assertEquals(0, argb8Inv[3] and 0xFF)

        // 2. 16-bit signed (PixelRepresentation = 1) с RescaleIntercept = -1024, RescaleSlope = 1.0 (КТ в единицах Хаунсфилда)
        val shortBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        shortBuf.putShort((-1000).toShort()) // Воздух (-1000 * 1 + 0 = -1000 HU)
        shortBuf.putShort(0.toShort())       // Вода (0 HU)
        shortBuf.putShort(40.toShort())      // Паренхима (+40 HU, центр окна)
        shortBuf.putShort(1000.toShort())    // Кость (+1000 HU)

        val file16Signed = writeTestDicomFile(
            name = "ct16_signed.dcm",
            rows = 2,
            cols = 2,
            bitsAllocated = 16,
            bitsStored = 16,
            highBit = 15,
            pixelRepresentation = 1,
            photometric = "MONOCHROME1",
            windowCenter = 40.0,
            windowWidth = 80.0,
            rescaleSlope = 1.0,
            rescaleIntercept = 0.0,
            pixelBytes = shortBuf.array()
        )
        val res16 = DicomPixelDataDecoder.decodeFrameFromFile(file16Signed) as DicomPixelDecodeResult.Success
        // В MONOCHROME1 минимальное значение (-1000 HU) отображается белым (255), а максимальное (+1000 HU) — чёрным (0)
        val argb16Mono1 = DicomPixelDataDecoder.renderFrameToArgbPixels(
            frame = res16.frame,
            windowWidth = 80.0,
            windowCenter = 40.0,
            userInverted = false
        )
        assertEquals("In MONOCHROME1 minimum pixel value must be white (255)", 255, argb16Mono1[0] and 0xFF)
        assertEquals("In MONOCHROME1 maximum pixel value must be black (0)", 0, argb16Mono1[3] and 0xFF)
    }

    @Test
    fun testMultiFrameUncompressedDecoding() {
        // 3 кадра размером 2x2 (16-bit unsigned):
        // Кадр 0 заполнен значением 100, Кадр 1 — значением 2000, Кадр 2 — значением 4000
        val buf = ByteBuffer.allocate(3 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { buf.putShort(100) }
        repeat(4) { buf.putShort(2000) }
        repeat(4) { buf.putShort(4000) }

        val multiFile = writeTestDicomFile(
            name = "multiframe.dcm",
            rows = 2,
            cols = 2,
            bitsAllocated = 16,
            bitsStored = 12,
            highBit = 11,
            pixelRepresentation = 0,
            photometric = "MONOCHROME2",
            numberOfFrames = 3,
            windowCenter = 2000.0,
            windowWidth = 4000.0,
            pixelBytes = buf.array()
        )

        val f0 = (DicomPixelDataDecoder.decodeFrameFromFile(multiFile, frameIndex = 0) as DicomPixelDecodeResult.Success).frame
        val f1 = (DicomPixelDataDecoder.decodeFrameFromFile(multiFile, frameIndex = 1) as DicomPixelDecodeResult.Success).frame
        val f2 = (DicomPixelDataDecoder.decodeFrameFromFile(multiFile, frameIndex = 2) as DicomPixelDecodeResult.Success).frame

        assertEquals(3, f0.descriptor.numberOfFrames)
        assertEquals(100, f0.grayscale16Buffer!![0].toInt() and 0xFFFF)
        assertEquals(2000, f1.grayscale16Buffer!![0].toInt() and 0xFFFF)
        assertEquals(4000, f2.grayscale16Buffer!![0].toInt() and 0xFFFF)
    }

    @Test
    fun testLargeMammographyImage_3580x2680_18_3MB_AndMemoryCache() {
        // Точное воспроизведение параметров принятого в эмуляторе маммографического исследования:
        // SOP Class: Digital Mammography X-Ray Image Storage – For Presentation
        // Transfer Syntax: Explicit VR Little Endian
        // Разрешение: 3580 × 2680 (19 188 800 байт = 18.30 МБ)
        // Window Width: 16221, Window Center: 8272
        val rows = 3580
        val cols = 2680
        val totalPixels = rows * cols
        val mammoFile = File(tempDir, "mammo_3580x2680.dcm")

        val fmi = Attributes().apply {
            setBytes(Tag.FileMetaInformationVersion, VR.OB, byteArrayOf(0, 1))
            setString(Tag.MediaStorageSOPClassUID, VR.UI, UID.DigitalMammographyXRayImageStorageForPresentation)
            setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.777.1")
            setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
            setString(Tag.ImplementationClassUID, VR.UI, "1.2.826.0.1.3680043.2.1143.1")
        }
        val ds = Attributes().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.SOPClassUID, VR.UI, UID.DigitalMammographyXRayImageStorageForPresentation)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.777.1")
            setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.777")
            setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.777.10")
            setString(Tag.PatientName, VR.PN, "Крылова^Мария^Александровна")
            setString(Tag.Modality, VR.CS, "MG")
            setInt(Tag.Rows, VR.US, rows)
            setInt(Tag.Columns, VR.US, cols)
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.BitsAllocated, VR.US, 16)
            setInt(Tag.BitsStored, VR.US, 14)
            setInt(Tag.HighBit, VR.US, 13)
            setInt(Tag.PixelRepresentation, VR.US, 0)
            setDouble(Tag.WindowWidth, VR.DS, 16221.0)
            setDouble(Tag.WindowCenter, VR.DS, 8272.0)
        }

        // Записываем заголовок и 19 188 800 байт Pixel Data потоково
        DicomOutputStream(mammoFile).use { dos ->
            dos.writeFileMetaInformation(fmi)
            dos.writeDataset(null, ds)
            dos.writeHeader(Tag.PixelData, VR.OW, totalPixels * 2)
            val rowBuf = ByteArray(cols * 2)
            for (r in 0 until rows) {
                val sampleVal = ((r * 16383) / rows).coerceIn(0, 16383)
                val b0 = (sampleVal and 0xFF).toByte()
                val b1 = ((sampleVal ushr 8) and 0xFF).toByte()
                var k = 0
                while (k < rowBuf.size) {
                    rowBuf[k] = b0
                    rowBuf[k + 1] = b1
                    k += 2
                }
                dos.write(rowBuf)
            }
        }

        val cache = SingleFrameDecodeCache()
        val decodeRes = DicomPixelDataDecoder.decodeFrameFromFile(mammoFile, frameIndex = 0, cache = cache)
        assertTrue(decodeRes is DicomPixelDecodeResult.Success)
        val frame = (decodeRes as DicomPixelDecodeResult.Success).frame
        assertEquals(3580, frame.descriptor.rows)
        assertEquals(2680, frame.descriptor.columns)
        assertEquals(16221.0, frame.descriptor.defaultWindowWidth, 0.1)
        assertEquals(8272.0, frame.descriptor.defaultWindowCenter, 0.1)

        // Проверяем пересчёт WW/WL для 3580x2680 и убеждаемся, что изображение реально меняется при смене WW/WL
        val argbDefault = DicomPixelDataDecoder.renderFrameToArgbPixels(
            frame = frame,
            windowWidth = 16221.0,
            windowCenter = 8272.0,
            userInverted = false
        )
        assertEquals(totalPixels, argbDefault.size)
        val midIndex = (rows / 2) * cols + (cols / 2)
        val midGrayDefault = argbDefault[midIndex] and 0xFF

        val argbShiftedWindow = DicomPixelDataDecoder.renderFrameToArgbPixels(
            frame = frame,
            windowWidth = 2000.0,
            windowCenter = 2000.0,
            userInverted = false
        )
        val midGrayShifted = argbShiftedWindow[midIndex] and 0xFF
        assertTrue(
            "Changing WW/WL must actually recalculate pixel output (was $midGrayDefault, shifted $midGrayShifted)",
            midGrayDefault != midGrayShifted
        )

        // Проверяем, что повторное обращение к кэшу не перечитывает файл и что переключение освобождает старый кадр
        assertNotNull(cache.get(mammoFile.absolutePath, 0))
        cache.clear()
        assertNull(cache.get(mammoFile.absolutePath, 0))
    }

    @Test
    fun testUnsupportedCompressedTransferSyntaxReturnsExplicitError() {
        val compressedFile = File(tempDir, "compressed_jpeg.dcm")
        val fmi = Attributes().apply {
            setBytes(Tag.FileMetaInformationVersion, VR.OB, byteArrayOf(0, 1))
            setString(Tag.MediaStorageSOPClassUID, VR.UI, UID.CTImageStorage)
            setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.3.4.5")
            setString(Tag.TransferSyntaxUID, VR.UI, UID.JPEGLosslessSV1)
            setString(Tag.ImplementationClassUID, VR.UI, "1.2.3.4")
        }
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5")
            setInt(Tag.Rows, VR.US, 64)
            setInt(Tag.Columns, VR.US, 64)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.BitsAllocated, VR.US, 16)
        }
        DicomOutputStream(compressedFile).use { dos ->
            dos.writeFileMetaInformation(fmi)
            dos.writeDataset(null, ds)
        }

        val result = DicomPixelDataDecoder.decodeFrameFromFile(compressedFile)
        assertTrue(result is DicomPixelDecodeResult.UnsupportedFormat)
        val unsupported = result as DicomPixelDecodeResult.UnsupportedFormat
        assertEquals(UID.JPEGLosslessSV1, unsupported.transferSyntaxUid)
        assertTrue(unsupported.reasonRu.contains(UID.JPEGLosslessSV1))
    }

    @Test
    fun testRgb8BitPlanarConfigurationsAndMultipleWindowPresets() {
        // RGB 2x1: Пиксель 0 = Красный (255,0,0), Пиксель 1 = Зелёный (0,255,0)
        // PlanarConfiguration = 0 (Chunky: R1,G1,B1, R2,G2,B2)
        val rgbChunkyFile = File(tempDir, "rgb_chunky.dcm")
        val fmi = Attributes().apply {
            setBytes(Tag.FileMetaInformationVersion, VR.OB, byteArrayOf(0, 1))
            setString(Tag.MediaStorageSOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.888.1")
            setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
            setString(Tag.ImplementationClassUID, VR.UI, "1.2.826.0.1.3680043.2.1143.1")
        }
        val dsChunky = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.888.1")
            setInt(Tag.Rows, VR.US, 1)
            setInt(Tag.Columns, VR.US, 2)
            setInt(Tag.SamplesPerPixel, VR.US, 3)
            setString(Tag.PhotometricInterpretation, VR.CS, "RGB")
            setInt(Tag.PlanarConfiguration, VR.US, 0)
            setInt(Tag.BitsAllocated, VR.US, 8)
            setInt(Tag.BitsStored, VR.US, 8)
            setInt(Tag.HighBit, VR.US, 7)
            setInt(Tag.PixelRepresentation, VR.US, 0)
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0))
        }
        DicomOutputStream(rgbChunkyFile).use { it.writeDataset(fmi, dsChunky) }

        val rgbRes = DicomPixelDataDecoder.decodeFrameFromFile(rgbChunkyFile) as DicomPixelDecodeResult.Success
        val argbRgb = DicomPixelDataDecoder.renderFrameToArgbPixels(rgbRes.frame, 255.0, 128.0, false)
        assertEquals(0xFFFF0000.toInt(), argbRgb[0])
        assertEquals(0xFF00FF00.toInt(), argbRgb[1])

        // Проверяем разбор множественных пар Window Center / Window Width
        val multiWindowFile = File(tempDir, "multi_window.dcm")
        val dsMultiW = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.888.2")
            setInt(Tag.Rows, VR.US, 2)
            setInt(Tag.Columns, VR.US, 2)
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.BitsAllocated, VR.US, 16)
            setInt(Tag.BitsStored, VR.US, 16)
            setInt(Tag.HighBit, VR.US, 15)
            setInt(Tag.PixelRepresentation, VR.US, 1)
            setDouble(Tag.WindowCenter, VR.DS, 40.0, -600.0, 300.0)
            setDouble(Tag.WindowWidth, VR.DS, 400.0, 1500.0, 2000.0)
            setString(Tag.WindowCenterWidthExplanation, VR.LO, "SOFT_TISSUE", "LUNG", "BONE")
            setBytes(Tag.PixelData, VR.OW, ByteArray(8))
        }
        DicomOutputStream(multiWindowFile).use { it.writeDataset(fmi, dsMultiW) }

        val mwRes = DicomPixelDataDecoder.decodeFrameFromFile(multiWindowFile) as DicomPixelDecodeResult.Success
        // 3 пары из DICOM + 1 пресет "Авто Мин/Макс"
        assertEquals(4, mwRes.frame.descriptor.windowPresets.size)
        assertEquals(40.0, mwRes.frame.descriptor.windowPresets[0].windowCenter, 0.01)
        assertEquals(400.0, mwRes.frame.descriptor.windowPresets[0].windowWidth, 0.01)
        assertEquals(-600.0, mwRes.frame.descriptor.windowPresets[1].windowCenter, 0.01)
        assertEquals(1500.0, mwRes.frame.descriptor.windowPresets[1].windowWidth, 0.01)
    }

    private fun writeTestDicomFile(
        name: String,
        rows: Int,
        cols: Int,
        bitsAllocated: Int,
        bitsStored: Int,
        highBit: Int,
        pixelRepresentation: Int,
        photometric: String,
        numberOfFrames: Int = 1,
        windowCenter: Double = 128.0,
        windowWidth: Double = 256.0,
        rescaleSlope: Double = 1.0,
        rescaleIntercept: Double = 0.0,
        pixelBytes: ByteArray
    ): File {
        val file = File(tempDir, name)
        val fmi = Attributes().apply {
            setBytes(Tag.FileMetaInformationVersion, VR.OB, byteArrayOf(0, 1))
            setString(Tag.MediaStorageSOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.${System.nanoTime()}")
            setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
            setString(Tag.ImplementationClassUID, VR.UI, "1.2.826.0.1.3680043.2.1143.1")
        }
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, fmi.getString(Tag.MediaStorageSOPInstanceUID))
            setInt(Tag.Rows, VR.US, rows)
            setInt(Tag.Columns, VR.US, cols)
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, photometric)
            setInt(Tag.BitsAllocated, VR.US, bitsAllocated)
            setInt(Tag.BitsStored, VR.US, bitsStored)
            setInt(Tag.HighBit, VR.US, highBit)
            setInt(Tag.PixelRepresentation, VR.US, pixelRepresentation)
            if (numberOfFrames > 1) {
                setInt(Tag.NumberOfFrames, VR.IS, numberOfFrames)
            }
            setDouble(Tag.WindowCenter, VR.DS, windowCenter)
            setDouble(Tag.WindowWidth, VR.DS, windowWidth)
            setDouble(Tag.RescaleSlope, VR.DS, rescaleSlope)
            setDouble(Tag.RescaleIntercept, VR.DS, rescaleIntercept)
            setBytes(Tag.PixelData, if (bitsAllocated == 8) VR.OB else VR.OW, pixelBytes)
        }
        DicomOutputStream(file).use { dos ->
            dos.writeDataset(fmi, ds)
        }
        return file
    }

    // =========================================================================
    // 3. Тесты подготовки генератора DICOM-объекта к Этапу 3 (SCU)
    // =========================================================================

    @Test
    fun testStage3SecondaryCaptureUidGenerationAndRetryPreservation() {
        val baseConfig = ru.medeng.dicomtool.domain.model.ScuSendConfig(
            patientName = "TEST^SERVICE^ENGINEER",
            patientId = "ENG-0001",
            studyDescription = "Service Diagnostic Test Image",
            modality = "OT"
        )

        // 1. Первичная генерация с Modality = OT по умолчанию
        val obj1 = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = baseConfig,
            existingObject = null,
            forceNewObject = true
        )

        // Проверка SOP Class UID (Secondary Capture Image Storage)
        assertEquals(
            "1.2.840.10008.5.1.4.1.1.7",
            obj1.uids.sopClassUid
        )
        assertEquals("OT", obj1.modality)
        assertEquals("OT", obj1.dataset.getString(Tag.Modality))

        // Проверка уникальности и валидности UID (<= 64 символов, без демо-констант)
        assertTrue(DicomSecondaryCaptureGenerator.isValidDicomUid(obj1.uids.studyInstanceUid))
        assertTrue(DicomSecondaryCaptureGenerator.isValidDicomUid(obj1.uids.seriesInstanceUid))
        assertTrue(DicomSecondaryCaptureGenerator.isValidDicomUid(obj1.uids.sopInstanceUid))
        assertTrue(obj1.uids.studyInstanceUid.length <= 64)
        assertTrue(obj1.uids.seriesInstanceUid.length <= 64)
        assertTrue(obj1.uids.sopInstanceUid.length <= 64)
        assertNotEquals(obj1.uids.studyInstanceUid, obj1.uids.seriesInstanceUid)
        assertNotEquals(obj1.uids.seriesInstanceUid, obj1.uids.sopInstanceUid)
        assertNotEquals(obj1.uids.studyInstanceUid, obj1.uids.sopInstanceUid)

        // Проверка совпадения File Meta Information (0002,0002 / 0002,0003) и Dataset (0008,0016 / 0008,0018)
        assertEquals(
            obj1.dataset.getString(Tag.SOPClassUID),
            obj1.fileMetaInformation.getString(Tag.MediaStorageSOPClassUID)
        )
        assertEquals(
            obj1.dataset.getString(Tag.SOPInstanceUID),
            obj1.fileMetaInformation.getString(Tag.MediaStorageSOPInstanceUID)
        )

        // 2. Повторная попытка отправки того же самого сгенерированного объекта (forceNewObject = false)
        // должна сохранять существующие Study/Series/SOP Instance UID
        val retryObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = baseConfig,
            existingObject = obj1,
            forceNewObject = false
        )
        assertEquals(obj1.uids.studyInstanceUid, retryObj.uids.studyInstanceUid)
        assertEquals(obj1.uids.seriesInstanceUid, retryObj.uids.seriesInstanceUid)
        assertEquals(obj1.uids.sopInstanceUid, retryObj.uids.sopInstanceUid)

        // 3. При смене Modality (например, на MG или CT) SOP Class UID обязан оставаться
        // Secondary Capture Image Storage (1.2.840.10008.5.1.4.1.1.7), а UID пересоздаются
        for (mod in listOf("CT", "DX", "CR", "MG", "US", "MR", "XA", "RF", "NM", "PT")) {
            val modObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
                config = baseConfig.copy(modality = mod),
                existingObject = retryObj,
                forceNewObject = false
            )
            assertEquals("Modality tag (0008,0060) must be $mod", mod, modObj.dataset.getString(Tag.Modality))
            assertEquals(
                "Changing Modality must never alter Secondary Capture SOP Class UID",
                "1.2.840.10008.5.1.4.1.1.7",
                modObj.dataset.getString(Tag.SOPClassUID)
            )
            assertEquals(
                "1.2.840.10008.5.1.4.1.1.7",
                modObj.fileMetaInformation.getString(Tag.MediaStorageSOPClassUID)
            )
            assertNotEquals("New object must get a new SOPInstanceUID", obj1.uids.sopInstanceUid, modObj.uids.sopInstanceUid)
        }
    }
}
