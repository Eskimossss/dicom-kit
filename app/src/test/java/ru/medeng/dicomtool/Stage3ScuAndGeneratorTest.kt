package ru.medeng.dicomtool

import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.net.NoPresentationContextException
import org.dcm4che3.net.Status
import org.dcm4che3.net.pdu.AAbort
import org.dcm4che3.net.pdu.AAssociateRJ
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.data.storage.LocalDicomStudyRepository
import ru.medeng.dicomtool.dicom.pixel.DicomPixelDataDecoder
import ru.medeng.dicomtool.dicom.pixel.DicomPixelDecodeResult
import ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer
import ru.medeng.dicomtool.dicom.scu.DicomScuClient
import ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator
import ru.medeng.dicomtool.dicom.scu.ScuDimseStatusKind
import ru.medeng.dicomtool.dicom.scu.ScuErrorCategory
import ru.medeng.dicomtool.dicom.scu.ScuOperationResult
import ru.medeng.dicomtool.dicom.scu.ScuTestImageRenderer
import ru.medeng.dicomtool.domain.model.FreehandStroke
import ru.medeng.dicomtool.domain.model.NormalizedPoint
import ru.medeng.dicomtool.domain.model.PatientSexOption
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import ru.medeng.dicomtool.domain.model.TestImageMode
import java.io.EOFException
import java.io.File
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files

/**
 * Комплексные автоматические тесты Этапа 3:
 * 1. Генерация уникальных UID и сохранение UID при повторной попытке отправки.
 * 2. Метаданные Secondary Capture (1.2.840.10008.5.1.4.1.1.7), кодировка UTF-8 (ISO_IR 192), конвертация даты ДД.ММ.ГГГГ -> YYYYMMDD и пола M/F/O/"".
 * 3. Генерация реальных 512×512 8-bit MONOCHROME2 Pixel Data в трёх режимах:
 *    - Режим A: многострочный текст с кириллицей.
 *    - Режим B: процедурный случайный смайлик (неизменность после генерации + различие при перегенерации).
 *    - Режим C: рисование пальцем/стилусом с разной толщиной линии и очисткой холста.
 * 4. Разбор успешного (0x0000), предупреждающего (0xBxxx) и ошибочного статуса DIMSE.
 * 5. Классификация всех видов сетевых и протокольных ошибок SCU без универсальной заглушки.
 * 6. Реальный сетевой цикл C-ECHO SCU и C-STORE SCU (включая A-ASSOCIATE-RJ, Connection Refused и проверку журнала SCU/SCP без PHI).
 */
class Stage3ScuAndGeneratorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("dicomtool_stage3_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testUidUniqueness_RetryPreservation_AndMetadataChangeRegeneration() {
        val baseConfig = ScuSendConfig(
            patientName = "ТЕСТ^ИНЖЕНЕР^СЕРВИС",
            patientId = "TEST-001",
            patientBirthDateDisplay = "15.05.1985",
            patientBirthDate = "19850515",
            patientSex = "M",
            modality = "OT",
            studyDescription = "ТЕСТОВОЕ ИССЛЕДОВАНИЕ",
            seriesDescription = "СЕРИЯ 1",
            imageMode = TestImageMode.TEXT,
            customText = "СТРОКА 1\nСТРОКА 2"
        )

        val obj1 = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = baseConfig,
            existingObject = null,
            forceNewObject = true
        )

        // 1. Все три UID уникальны и валидны (<= 64 символов)
        assertTrue(DicomSecondaryCaptureGenerator.isValidDicomUid(obj1.uids.studyInstanceUid))
        assertTrue(DicomSecondaryCaptureGenerator.isValidDicomUid(obj1.uids.seriesInstanceUid))
        assertTrue(DicomSecondaryCaptureGenerator.isValidDicomUid(obj1.uids.sopInstanceUid))
        assertNotEquals(obj1.uids.studyInstanceUid, obj1.uids.seriesInstanceUid)
        assertNotEquals(obj1.uids.seriesInstanceUid, obj1.uids.sopInstanceUid)
        assertNotEquals(obj1.uids.studyInstanceUid, obj1.uids.sopInstanceUid)

        // 2. Повторная попытка отправки того же объекта сохраняет UID и Pixel Data
        val retryObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = baseConfig,
            existingObject = obj1,
            forceNewObject = false
        )
        assertEquals(obj1.uids.studyInstanceUid, retryObj.uids.studyInstanceUid)
        assertEquals(obj1.uids.seriesInstanceUid, retryObj.uids.seriesInstanceUid)
        assertEquals(obj1.uids.sopInstanceUid, retryObj.uids.sopInstanceUid)
        assertArrayEquals(obj1.pixelDataBytes, retryObj.pixelDataBytes)

        // 3. При изменении текста изображения или значимых метаданных создаются новые UID
        val modifiedTextObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            config = baseConfig.copy(customText = "ДРУГОЙ ТЕКСТ\nНОВАЯ СТРОКА"),
            existingObject = retryObj,
            forceNewObject = false
        )
        assertNotEquals(obj1.uids.sopInstanceUid, modifiedTextObj.uids.sopInstanceUid)
        assertNotEquals(obj1.uids.studyInstanceUid, modifiedTextObj.uids.studyInstanceUid)
        assertNotEquals(obj1.uids.seriesInstanceUid, modifiedTextObj.uids.seriesInstanceUid)
    }

    @Test
    fun testSecondaryCaptureMetadata_Utf8Cyrillic_DateAndSexFormatting_AndPixelDataDecoding() {
        // Проверка конвертации даты рождения ДД.ММ.ГГГГ <-> YYYYMMDD
        assertEquals("19900101", DicomSecondaryCaptureGenerator.displayDateToDicomDa("01.01.1990"))
        assertEquals("19841225", DicomSecondaryCaptureGenerator.displayDateToDicomDa("25.12.1984"))
        assertEquals("", DicomSecondaryCaptureGenerator.displayDateToDicomDa(""))
        assertEquals("01.01.1990", DicomSecondaryCaptureGenerator.dicomDaToDisplayDate("19900101"))

        // Проверка вариантов пола пациента (M, F, O, "")
        assertEquals("M", DicomSecondaryCaptureGenerator.normalizePatientSex(PatientSexOption.MALE.dicomCode))
        assertEquals("F", DicomSecondaryCaptureGenerator.normalizePatientSex(PatientSexOption.FEMALE.dicomCode))
        assertEquals("O", DicomSecondaryCaptureGenerator.normalizePatientSex(PatientSexOption.OTHER.dicomCode))
        assertEquals("", DicomSecondaryCaptureGenerator.normalizePatientSex(PatientSexOption.UNSPECIFIED.dicomCode))

        val config = ScuSendConfig(
            patientName = "ИВАНОВ^ТЕСТ^ТЕСТОВИЧ",
            patientId = "ENG-777",
            patientBirthDateDisplay = "20.04.1988",
            patientBirthDate = DicomSecondaryCaptureGenerator.displayDateToDicomDa("20.04.1988"),
            patientSex = "M",
            modality = "MG",
            studyDescription = "Проверка SCU Кириллица",
            seriesDescription = "Тестовая серия SC",
            imageMode = TestImageMode.TEXT,
            customText = "ТЕСТ КИРИЛЛИЦЫ\n512x512 MONOCHROME2"
        )

        val prepared = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config)
        val ds = prepared.dataset
        val fmi = prepared.fileMetaInformation

        // SOP Class UID обязан оставаться Secondary Capture (1.2.840.10008.5.1.4.1.1.7) даже при Modality = MG
        assertEquals(UID.SecondaryCaptureImageStorage, ds.getString(Tag.SOPClassUID))
        assertEquals(UID.SecondaryCaptureImageStorage, fmi.getString(Tag.MediaStorageSOPClassUID))
        assertEquals(prepared.uids.sopInstanceUid, fmi.getString(Tag.MediaStorageSOPInstanceUID))
        assertEquals(UID.ExplicitVRLittleEndian, fmi.getString(Tag.TransferSyntaxUID))

        assertEquals("ISO_IR 192", ds.getString(Tag.SpecificCharacterSet))
        assertEquals("MG", ds.getString(Tag.Modality))
        assertEquals("WSD", ds.getString(Tag.ConversionType))
        assertEquals("19880420", ds.getString(Tag.PatientBirthDate))
        assertEquals("M", ds.getString(Tag.PatientSex))
        assertEquals(512, ds.getInt(Tag.Rows, 0))
        assertEquals(512, ds.getInt(Tag.Columns, 0))
        assertEquals(1, ds.getInt(Tag.SamplesPerPixel, 0))
        assertEquals("MONOCHROME2", ds.getString(Tag.PhotometricInterpretation))
        assertEquals(8, ds.getInt(Tag.BitsAllocated, 0))
        assertEquals(8, ds.getInt(Tag.BitsStored, 0))
        assertEquals(7, ds.getInt(Tag.HighBit, 0))
        assertEquals(0, ds.getInt(Tag.PixelRepresentation, -1))
        assertEquals(512 * 512, prepared.pixelDataBytes.size)

        // Записываем во временный кэш и проверяем чтение реальным декодером и репозиторием
        val cacheDir = File(tempDir, "scu_cache")
        val dcmFile = DicomSecondaryCaptureGenerator.writeToTempCacheFile(prepared, cacheDir)
        assertTrue(dcmFile.exists())

        val decodeRes = DicomPixelDataDecoder.decodeFrameFromFile(dcmFile)
        assertTrue(decodeRes is DicomPixelDecodeResult.Success)
        val frame = (decodeRes as DicomPixelDecodeResult.Success).frame
        assertEquals(512, frame.descriptor.rows)
        assertEquals(512, frame.descriptor.columns)
        assertEquals(8, frame.descriptor.bitsAllocated)

        // Проверяем декодирование UTF-8 кириллицы без потерь
        val repoDir = File(tempDir, "repo_verify/study1/series1").apply { mkdirs() }
        val copiedFile = File(repoDir, dcmFile.name)
        dcmFile.copyTo(copiedFile, overwrite = true)
        val repo = LocalDicomStudyRepository(File(tempDir, "repo_verify"))
        val study = repo.studiesFlow.value.first()
        assertEquals("ИВАНОВ^ТЕСТ^ТЕСТОВИЧ", study.patientName)
        assertEquals("Проверка SCU Кириллица", study.studyDescription)
        assertEquals("Тестовая серия SC", study.seriesDescription)
        assertNull(study.encodingWarning)

        // Очистка временного кэша
        DicomSecondaryCaptureGenerator.clearTempCache(cacheDir)
        assertTrue(cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun testAllThreeGeneratorModesProduceValid512x512GrayscalePixelData() {
        // 1. Режим A: Многострочный текст с кириллицей
        val textPixels1 = ScuTestImageRenderer.renderTextModePixels(" ТЕСТОВАЯ СТРОКА 1\nСТРОКА 2 КИРИЛЛИЦА\nLINE 3 ASCII ")
        val textPixels2 = ScuTestImageRenderer.renderTextModePixels("СОВЕРШЕННО ДРУГОЙ ТЕКСТ")
        assertEquals(512 * 512, textPixels1.size)
        val whitePixelsText1 = textPixels1.count { (it.toInt() and 0xFF) > 32 }
        assertTrue("Text mode must render visible white pixels on black background", whitePixelsText1 > 200)
        assertFalse("Different text strings must produce different Pixel Data", textPixels1.contentEquals(textPixels2))

        // 2. Режим B: Процедурный генератор случайных смайликов
        val seed1 = 20261012L
        val smiley1A = ScuTestImageRenderer.renderProceduralSmileyPixels(seed1)
        val smiley1B = ScuTestImageRenderer.renderProceduralSmileyPixels(seed1)
        assertEquals(512 * 512, smiley1A.size)
        assertTrue("Smiley must contain visible white pixels", smiley1A.count { (it.toInt() and 0xFF) > 64 } > 500)
        assertArrayEquals("Smiley must remain identical for the same seed before sending", smiley1A, smiley1B)

        val seed2 = ScuTestImageRenderer.nextRandomSmileySeed(seed1)
        assertNotEquals(seed1, seed2)
        val smiley2 = ScuTestImageRenderer.renderProceduralSmileyPixels(seed2)
        assertFalse("Regenerated smiley with new seed must produce a different image", smiley1A.contentEquals(smiley2))

        // 3. Режим C: Рисование пальцем/стилусом и настройка толщины линии
        val emptyCanvas = ScuTestImageRenderer.renderFreehandPixels(emptyList())
        assertEquals(0, emptyCanvas.count { it.toInt() != 0 })

        val thinStroke = listOf(
            FreehandStroke(
                points = listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.9f, 0.5f)),
                strokeWidthPx = 4f
            )
        )
        val thickStroke = listOf(
            FreehandStroke(
                points = listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.9f, 0.5f)),
                strokeWidthPx = 18f
            )
        )
        val thinPixels = ScuTestImageRenderer.renderFreehandPixels(thinStroke)
        val thickPixels = ScuTestImageRenderer.renderFreehandPixels(thickStroke)
        val thinWhiteCount = thinPixels.count { (it.toInt() and 0xFF) > 100 }
        val thickWhiteCount = thickPixels.count { (it.toInt() and 0xFF) > 100 }
        assertTrue("Thin stroke must draw white pixels on canvas", thinWhiteCount > 400)
        assertTrue(
            "Thick stroke ($thickWhiteCount px) must cover more pixels than thin stroke ($thinWhiteCount px)",
            thickWhiteCount > thinWhiteCount * 2
        )
    }

    @Test
    fun testDimseStatusEvaluation_Success_Warning_Failure() {
        val ok = DicomScuClient.evaluateDimseStatus(Status.Success)
        assertEquals(ScuDimseStatusKind.SUCCESS, ok.kind)
        assertEquals("0x0000", ok.statusHex)

        val warnCoercion = DicomScuClient.evaluateDimseStatus(0xB000, "Coercion of Data Elements")
        assertEquals(ScuDimseStatusKind.WARNING, warnCoercion.kind)
        assertEquals("0xB000", warnCoercion.statusHex)

        val warnDiscarded = DicomScuClient.evaluateDimseStatus(0xB006)
        assertEquals(ScuDimseStatusKind.WARNING, warnDiscarded.kind)
        assertEquals("0xB006", warnDiscarded.statusHex)

        val errOutOfResources = DicomScuClient.evaluateDimseStatus(0xA700)
        assertEquals(ScuDimseStatusKind.FAILURE, errOutOfResources.kind)
        assertEquals("0xA700", errOutOfResources.statusHex)

        val errSopNotSupported = DicomScuClient.evaluateDimseStatus(0x0122)
        assertEquals(ScuDimseStatusKind.FAILURE, errSopNotSupported.kind)
        assertEquals("0x0122", errSopNotSupported.statusHex)

        val errCannotUnderstand = DicomScuClient.evaluateDimseStatus(0xC001)
        assertEquals(ScuDimseStatusKind.FAILURE, errCannotUnderstand.kind)
        assertEquals("0xC001", errCannotUnderstand.statusHex)
    }

    @Test
    fun testGranularNetworkErrorClassification() {
        // 1. TCP Connection Refused
        val refused = DicomScuClient.classifyException(
            ConnectException("Connection refused"),
            "10.0.2.2", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.TCP_CONNECTION_REFUSED, refused.category)

        // 2. TCP Timeout
        val timeout = DicomScuClient.classifyException(
            SocketTimeoutException("connect timed out"),
            "10.0.2.2", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.TCP_TIMEOUT, timeout.category)

        // 3. DNS Resolution Error
        val dns = DicomScuClient.classifyException(
            UnknownHostException("nonexistent-pacs.local"),
            "nonexistent-pacs.local", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.DNS_RESOLUTION_ERROR, dns.category)

        // 4. A-ASSOCIATE-RJ
        val rj = DicomScuClient.classifyException(
            AAssociateRJ(
                AAssociateRJ.RESULT_REJECTED_PERMANENT,
                AAssociateRJ.SOURCE_SERVICE_USER,
                AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED
            ),
            "10.0.2.2", 4242, "ANDROID_SCU", "WRONG_AE"
        )
        assertEquals(ScuErrorCategory.ASSOCIATE_RJ, rj.category)
        assertTrue(rj.shortUserMessageRu.contains("7 - called-AE-title-not-recognized"))

        // 5. Presentation Context Rejected
        val pcRej = DicomScuClient.classifyException(
            NoPresentationContextException(UID.SecondaryCaptureImageStorage),
            "10.0.2.2", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.PRESENTATION_CONTEXT_REJECTED, pcRej.category)

        // 6. A-ABORT
        val abort = DicomScuClient.classifyException(
            AAbort(AAbort.UL_SERIVE_PROVIDER, AAbort.UNEXPECTED_PDU),
            "10.0.2.2", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.ASSOCIATION_ABORTED, abort.category)

        // 7. Connection Reset
        val reset = DicomScuClient.classifyException(
            SocketException("Connection reset"),
            "10.0.2.2", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.CONNECTION_RESET, reset.category)

        val eofReset = DicomScuClient.classifyException(
            EOFException("Unexpected EOF while reading PDU"),
            "10.0.2.2", 4242, "ANDROID_SCU", "ESKIMOS"
        )
        assertEquals(ScuErrorCategory.CONNECTION_RESET, eofReset.category)
    }

    @Test
    fun testEndToEndScuToScpLoopback_CEcho_CStore_Retry_AssociateRj_AndConnectionRefused() {
        val port = ServerSocket(0).use { it.localPort }
        val repoDir = File(tempDir, "scp_storage")
        val logFile = File(tempDir, "logs/dicom_log.tsv")
        val tempCacheDir = File(tempDir, "scu_temp_cache")

        val repository = LocalDicomStudyRepository(repoDir)
        val logger = PersistentDicomTechnicalLogger(logFile)
        val scpServer = DicomStorageScpServer(repository, logger)
        val scuClient = DicomScuClient(logger, tempCacheDir)

        try {
            val startRes = scpServer.startServer(aeTitle = "ESKIMOS", port = port)
            assertTrue(startRes.isSuccess)

            // 1. Успешный C-ECHO SCU -> SCP
            val echoRes = scuClient.performCEcho(
                remoteHost = "127.0.0.1",
                remotePort = port,
                callingAeTitle = "ANDROID_SCU",
                calledAeTitle = "ESKIMOS"
            )
            assertTrue("C-ECHO must succeed, got: $echoRes", echoRes is ScuOperationResult.Success)
            assertEquals("0x0000", (echoRes as ScuOperationResult.Success).statusHex)

            // 2. Успешный C-STORE SCU (режим Смайлик + Кириллица в ФИО)
            val scuConfig = ScuSendConfig(
                remoteIp = "127.0.0.1",
                remotePort = port,
                calledAeTitle = "ESKIMOS",
                callingAeTitle = "ANDROID_SCU",
                patientName = "ТЕСТОВ^СЕРГЕЙ^ИНЖЕНЕР",
                patientId = "SCU-TEST-100",
                patientBirthDateDisplay = "12.04.1980",
                patientBirthDate = "19800412",
                patientSex = "M",
                modality = "OT",
                studyDescription = "ТЕСТ C-STORE SCU",
                seriesDescription = "СМАЙЛИК 512X512",
                imageMode = TestImageMode.EMOJI,
                smileySeed = 777888999L
            )
            val preparedObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(scuConfig)
            val storeRes1 = scuClient.performCStore(
                remoteHost = "127.0.0.1",
                remotePort = port,
                callingAeTitle = "ANDROID_SCU",
                calledAeTitle = "ESKIMOS",
                preparedObject = preparedObj
            )
            assertTrue("C-STORE must succeed, got: $storeRes1", storeRes1 is ScuOperationResult.Success)
            assertEquals(1, repository.studiesFlow.value.size)
            val savedStudy = repository.studiesFlow.value.first()
            assertEquals("ТЕСТОВ^СЕРГЕЙ^ИНЖЕНЕР", savedStudy.patientName)
            assertEquals(preparedObj.uids.studyInstanceUid, savedStudy.studyInstanceUid)
            assertEquals("OT", savedStudy.modality)

            // 3. Повторная отправка того же подготовленного объекта (UID сохраняются)
            val storeResRetry = scuClient.performCStore(
                remoteHost = "127.0.0.1",
                remotePort = port,
                callingAeTitle = "ANDROID_SCU",
                calledAeTitle = "ESKIMOS",
                preparedObject = preparedObj
            )
            assertTrue(storeResRetry is ScuOperationResult.Success)

            // 4. Неверный Called AE Title -> A-ASSOCIATE-RJ
            val rjRes = scuClient.performCEcho(
                remoteHost = "127.0.0.1",
                remotePort = port,
                callingAeTitle = "ANDROID_SCU",
                calledAeTitle = "WRONG_AE"
            )
            assertTrue(rjRes is ScuOperationResult.Failure)
            assertEquals(ScuErrorCategory.ASSOCIATE_RJ, (rjRes as ScuOperationResult.Failure).errorCategory)

            // 5. Останавливаем сервер и проверяем TCP Connection Refused
            scpServer.stopServer()
            val closedPort = ServerSocket(0).use { it.localPort }
            val refusedRes = scuClient.performCEcho(
                remoteHost = "127.0.0.1",
                remotePort = closedPort,
                callingAeTitle = "ANDROID_SCU",
                calledAeTitle = "ESKIMOS"
            )
            assertTrue(refusedRes is ScuOperationResult.Failure)
            assertEquals(
                ScuErrorCategory.TCP_CONNECTION_REFUSED,
                (refusedRes as ScuOperationResult.Failure).errorCategory
            )

            // 6. Проверяем подробность технического журнала (наличие ролей SCU и SCP, отсутствие PHI)
            val allLogs = logger.logsFlow.value
            assertTrue(allLogs.any { it.associationId.startsWith("SCU-") && it.technicalDetails["Роль"] == "SCU" })
            assertTrue(allLogs.any { it.associationId.startsWith("SCP-") })
            assertFalse(
                "Technical log must never contain patient personal name",
                allLogs.any { entry ->
                    entry.technicalDetails.values.any { it.contains("ТЕСТОВ^СЕРГЕЙ") }
                }
            )
        } finally {
            scpServer.stopServer()
        }
    }

    @Test
    fun testDefaultEmptyPacsParametersAndValidation() {
        // 1. По умолчанию параметры PACS и демонстрационные данные пациента в ScuSendConfig пусты
        val defaultConfig = ScuSendConfig()
        assertEquals("", defaultConfig.remoteIp)
        assertEquals(0, defaultConfig.remotePort)
        assertEquals("", defaultConfig.calledAeTitle)
        assertEquals("", defaultConfig.callingAeTitle)
        assertEquals("", defaultConfig.patientName)
        assertEquals("", defaultConfig.patientId)
        assertEquals("", defaultConfig.patientBirthDateDigits)
        assertEquals("", defaultConfig.patientBirthDateDisplay)
        assertEquals("", defaultConfig.patientBirthDate)
        assertEquals("", defaultConfig.patientSex)
        assertEquals("", defaultConfig.studyDescription)
        assertEquals("", defaultConfig.seriesDescription)

        // Проверка запрета создания DICOM при незаполненных обязательных полях
        val errName = try {
            DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(defaultConfig)
            null
        } catch (e: IllegalArgumentException) { e.message }
        assertNotNull(errName)
        assertTrue(errName!!.contains("ФИО пациента"))

        val errId = try {
            DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(defaultConfig.copy(patientName = "ИВАНОВ^ИВАН"))
            null
        } catch (e: IllegalArgumentException) { e.message }
        assertNotNull(errId)
        assertTrue(errId!!.contains("ID пациента"))

        // При заполнении обязательных полей создаётся корректный DICOM
        val validObj = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(
            defaultConfig.copy(patientName = "ИВАНОВ^ИВАН", patientId = "PAT-001")
        )
        assertNotNull(validObj)
        assertEquals("ИВАНОВ^ИВАН", validObj.dataset.getString(org.dcm4che3.data.Tag.PatientName))
        assertEquals("PAT-001", validObj.dataset.getString(org.dcm4che3.data.Tag.PatientID))

        val logger = PersistentDicomTechnicalLogger(File(tempDir, "scu_val.tsv"))
        val scuClient = DicomScuClient(logger, File(tempDir, "scu_cache"))

        // 2. Валидация при пустом IP
        val emptyIpRes = scuClient.performCEcho(
            remoteHost = "",
            remotePort = 104,
            callingAeTitle = "SCU",
            calledAeTitle = "PACS"
        )
        assertTrue(emptyIpRes is ScuOperationResult.Failure)
        assertEquals(ScuErrorCategory.INVALID_PARAMETERS, (emptyIpRes as ScuOperationResult.Failure).errorCategory)
        assertTrue(emptyIpRes.userMessageRu.contains("Не указан IP-адрес"))

        // 3. Валидация при пустом/нулевом порте
        val emptyPortRes = scuClient.performCEcho(
            remoteHost = "192.168.1.10",
            remotePort = 0,
            callingAeTitle = "SCU",
            calledAeTitle = "PACS"
        )
        assertTrue(emptyPortRes is ScuOperationResult.Failure)
        assertEquals(ScuErrorCategory.INVALID_PARAMETERS, (emptyPortRes as ScuOperationResult.Failure).errorCategory)
        assertTrue(emptyPortRes.userMessageRu.contains("Не указан TCP-порт"))

        // 4. Валидация при пустом Called AE Title
        val emptyCalledAeRes = scuClient.performCEcho(
            remoteHost = "192.168.1.10",
            remotePort = 104,
            callingAeTitle = "SCU",
            calledAeTitle = ""
        )
        assertTrue(emptyCalledAeRes is ScuOperationResult.Failure)
        assertEquals(ScuErrorCategory.INVALID_PARAMETERS, (emptyCalledAeRes as ScuOperationResult.Failure).errorCategory)
        assertTrue(emptyCalledAeRes.userMessageRu.contains("Called AE Title"))

        // 5. Валидация при пустом Calling AE Title
        val emptyCallingAeRes = scuClient.performCEcho(
            remoteHost = "192.168.1.10",
            remotePort = 104,
            callingAeTitle = "",
            calledAeTitle = "PACS"
        )
        assertTrue(emptyCallingAeRes is ScuOperationResult.Failure)
        assertEquals(ScuErrorCategory.INVALID_PARAMETERS, (emptyCallingAeRes as ScuOperationResult.Failure).errorCategory)
        assertTrue(emptyCallingAeRes.userMessageRu.contains("Calling AE Title"))
    }
}
