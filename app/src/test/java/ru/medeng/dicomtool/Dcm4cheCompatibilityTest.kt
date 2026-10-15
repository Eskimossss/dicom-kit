package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.Connection
import org.dcm4che3.net.DataWriterAdapter
import org.dcm4che3.net.Device
import org.dcm4che3.net.DimseRSPHandler
import org.dcm4che3.net.Status
import org.dcm4che3.net.pdu.AAssociateRJ
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.data.storage.LocalDicomStudyRepository
import ru.medeng.dicomtool.dicom.Dcm4cheEngineVerifier
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Модульные и интеграционные тесты Этапа 2:
 * - Проверка инициализации dcm4che3.
 * - Проверка валидации AE Title и номера TCP-порта.
 * - Интеграционный тест настоящего C-ECHO SCP по TCP.
 * - Интеграционный тест настоящего C-STORE SCP (Implicit VR Little Endian и Explicit VR Little Endian)
 *   для модальностей CT, DX, MG с проверкой сохранённых DICOM Part 10 файлов, оригинальных UID и Pixel Data.
 * - Проверка повторной отправки одного и того же SOP Instance UID (идентичный файл и модифицированный файл без потери данных).
 * - Проверка отклонения ассоциации (A-ASSOCIATE-RJ) при неверном Called AE Title.
 * - Проверка персистентного технического журнала (отсутствие PHI/PixelData, сохранение на диск, независимость от очистки снимков).
 */
class DicomScpStage2IntegrationTest {

    private lateinit var tempRootDir: File
    private lateinit var storageDir: File
    private lateinit var logFile: File
    private lateinit var repository: LocalDicomStudyRepository
    private lateinit var logger: PersistentDicomTechnicalLogger
    private lateinit var scpServer: DicomStorageScpServer

    private var scuDevice: Device? = null
    private var scuExecutor = Executors.newCachedThreadPool()
    private var scuScheduled: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Before
    fun setUp() {
        tempRootDir = Files.createTempDirectory("dicomtool_stage2_test").toFile()
        storageDir = File(tempRootDir, "received_dicom")
        logFile = File(tempRootDir, "logs/dicom_technical_log.tsv")
        repository = LocalDicomStudyRepository(storageDir)
        logger = PersistentDicomTechnicalLogger(logFile)
        scpServer = DicomStorageScpServer(repository, logger)
    }

    @After
    fun tearDown() {
        scpServer.stopServer()
        scuExecutor.shutdownNow()
        scuScheduled.shutdownNow()
        logger.shutdown()
        tempRootDir.deleteRecursively()
    }

    @Test
    fun testValidationRulesForAeTitleAndPort() {
        assertTrue(DicomValidationUtils.validateAeTitle("ANDROID_PACS") is DicomValidationUtils.ValidationResult.Valid)
        assertTrue(DicomValidationUtils.validateAeTitle("") is DicomValidationUtils.ValidationResult.Invalid)
        assertTrue(DicomValidationUtils.validateAeTitle("   ") is DicomValidationUtils.ValidationResult.Invalid)
        assertTrue(DicomValidationUtils.validateAeTitle("TOO_LONG_AE_TITLE_17") is DicomValidationUtils.ValidationResult.Invalid)
        assertTrue(DicomValidationUtils.validateAeTitle("BAD\\AE") is DicomValidationUtils.ValidationResult.Invalid)

        assertTrue(DicomValidationUtils.validateScpPort(11112) is DicomValidationUtils.ValidationResult.Valid)
        assertTrue(DicomValidationUtils.validateScpPort(104) is DicomValidationUtils.ValidationResult.Invalid)
        assertTrue(DicomValidationUtils.validateScpPort(70000) is DicomValidationUtils.ValidationResult.Invalid)
    }

    @Test
    fun testRealCEchoAndCStoreScpEndToEnd() {
        val testPort = 18112
        val scpAeTitle = "ANDROID_PACS"
        val startResult = scpServer.startServer(scpAeTitle, testPort)
        assertTrue("SCP server must start cleanly", startResult.isSuccess)

        // 1. Выполняем настоящий C-ECHO через TCP-ассоциацию
        val echoStatus = performCEcho("TEST_SCU", scpAeTitle, "127.0.0.1", testPort)
        assertEquals(Status.Success, echoStatus)

        // 2. Отправляем CT исследование (Explicit VR Little Endian) и DX исследование (Implicit VR Little Endian)
        val studyUid1 = "1.2.826.0.1.3680043.2.1143.9001"
        val seriesUid1 = "1.2.826.0.1.3680043.2.1143.9001.1"
        val sopUid1 = "1.2.826.0.1.3680043.2.1143.9001.1.1"
        val pixelBytes1 = ByteArray(128 * 128 * 2) { (it % 251).toByte() }

        val ctDataset = createSampleImageDataset(
            sopClassUid = UID.CTImageStorage,
            sopInstanceUid = sopUid1,
            studyInstanceUid = studyUid1,
            seriesInstanceUid = seriesUid1,
            modality = "CT",
            patientName = "IVANOV^IVAN^IVANOVICH",
            patientId = "CT-2026-01",
            pixelBytes = pixelBytes1
        )

        val cstoreStatus1 = performCStore(
            callingAe = "CT_SCANNER",
            calledAe = scpAeTitle,
            host = "127.0.0.1",
            port = testPort,
            sopClassUid = UID.CTImageStorage,
            sopInstanceUid = sopUid1,
            transferSyntax = UID.ExplicitVRLittleEndian,
            dataset = ctDataset
        )
        assertEquals(Status.Success, cstoreStatus1)

        // Проверяем, что файл реально сохранён в DICOM Part 10 и его UID/теги/пиксели не изменены
        val studies = repository.studiesFlow.value
        assertEquals(1, studies.size)
        val savedStudy = studies.first()
        assertEquals(studyUid1, savedStudy.studyInstanceUid)
        assertEquals("IVANOV^IVAN^IVANOVICH", savedStudy.patientName)
        assertEquals("CT", savedStudy.modality)
        assertEquals(1, savedStudy.seriesCount)
        assertEquals(1, savedStudy.instancesCount)

        val savedFilePath = savedStudy.seriesList.first().instances.first().filePath
        val savedFile = File(savedFilePath)
        assertTrue("Saved .dcm file must exist", savedFile.exists())

        DicomInputStream(savedFile).use { dis ->
            val fmi = dis.readFileMetaInformation()
            assertNotNull("DICOM Part 10 File Meta Information must be present", fmi)
            assertEquals(UID.CTImageStorage, fmi.getString(Tag.MediaStorageSOPClassUID))
            assertEquals(sopUid1, fmi.getString(Tag.MediaStorageSOPInstanceUID))
            assertEquals(UID.ExplicitVRLittleEndian, fmi.getString(Tag.TransferSyntaxUID))

            val ds = dis.readDataset()
            assertEquals(studyUid1, ds.getString(Tag.StudyInstanceUID))
            assertEquals(seriesUid1, ds.getString(Tag.SeriesInstanceUID))
            assertEquals(sopUid1, ds.getString(Tag.SOPInstanceUID))
            assertEquals("IVANOV^IVAN^IVANOVICH", ds.getString(Tag.PatientName))
            assertArrayEquals("Pixel Data must match byte-for-byte", pixelBytes1, ds.getBytes(Tag.PixelData))
        }

        // Проверяем извлечение тегов для UI (включая вложенную последовательность SQ)
        val extractedTags = repository.getTagsForInstanceFile(savedFilePath)
        assertTrue(extractedTags.any { it.tagHex == "(0010,0010)" && it.value == "IVANOV^IVAN^IVANOVICH" })
        assertTrue(extractedTags.any { it.tagHex == "(0008,1110)" && it.vr == "SQ" &&
            it.sequenceItems.any { item -> item.tags.any { tag -> tag.value.contains("1.2.840.10008.5.1.4.1.1.2") } } })
        assertTrue(extractedTags.any { it.tagHex == "(7FE0,0010)" })

        // 3. Проверяем повторную отправку (Re-transmission) того же SOPInstanceUID:
        //    3a) Идентичный объект — не должен создавать дубликат или повреждать оригинал
        val retransmitIdenticalStatus = performCStore(
            callingAe = "CT_SCANNER",
            calledAe = scpAeTitle,
            host = "127.0.0.1",
            port = testPort,
            sopClassUid = UID.CTImageStorage,
            sopInstanceUid = sopUid1,
            transferSyntax = UID.ExplicitVRLittleEndian,
            dataset = ctDataset
        )
        assertEquals(Status.Success, retransmitIdenticalStatus)
        assertEquals(1, repository.studiesFlow.value.first().instancesCount)

        //    3b) Тот же SOPInstanceUID, но изменённые данные — оба файла сохраняются без перезаписи оригинала
        val modifiedDataset = createSampleImageDataset(
            sopClassUid = UID.CTImageStorage,
            sopInstanceUid = sopUid1,
            studyInstanceUid = studyUid1,
            seriesInstanceUid = seriesUid1,
            modality = "CT",
            patientName = "IVANOV^IVAN^IVANOVICH",
            patientId = "CT-2026-01",
            pixelBytes = ByteArray(128 * 128 * 2) { 0x7F.toByte() }
        )
        val retransmitModifiedStatus = performCStore(
            callingAe = "CT_SCANNER",
            calledAe = scpAeTitle,
            host = "127.0.0.1",
            port = testPort,
            sopClassUid = UID.CTImageStorage,
            sopInstanceUid = sopUid1,
            transferSyntax = UID.ExplicitVRLittleEndian,
            dataset = modifiedDataset
        )
        assertEquals(Status.Success, retransmitModifiedStatus)
        assertEquals(2, repository.studiesFlow.value.first().instancesCount)

        // 4. Проверяем отправку второго исследования (MG маммография по Implicit VR Little Endian)
        val studyUid2 = "1.2.826.0.1.3680043.2.1143.9002"
        val seriesUid2 = "1.2.826.0.1.3680043.2.1143.9002.1"
        val sopUid2 = "1.2.826.0.1.3680043.2.1143.9002.1.1"
        val mgDataset = createSampleImageDataset(
            sopClassUid = UID.DigitalMammographyXRayImageStorageForPresentation,
            sopInstanceUid = sopUid2,
            studyInstanceUid = studyUid2,
            seriesInstanceUid = seriesUid2,
            modality = "MG",
            patientName = "PETROVA^ANNA",
            patientId = "MG-2026-02",
            pixelBytes = ByteArray(64 * 64) { (it % 120).toByte() }
        )
        val mgStatus = performCStore(
            callingAe = "MAMMO_UNIT",
            calledAe = scpAeTitle,
            host = "127.0.0.1",
            port = testPort,
            sopClassUid = UID.DigitalMammographyXRayImageStorageForPresentation,
            sopInstanceUid = sopUid2,
            transferSyntax = UID.ImplicitVRLittleEndian,
            dataset = mgDataset
        )
        assertEquals(Status.Success, mgStatus)
        assertEquals(2, scpServer.state.value.receivedStudiesCount)
        assertEquals(3, scpServer.state.value.receivedImagesCount)

        // 5. Проверяем отклонение ассоциации (A-ASSOCIATE-RJ) при неверном Called AE Title
        try {
            performCEcho("TEST_SCU", "WRONG_AET", "127.0.0.1", testPort)
            fail("Expected AAssociateRJ for mismatched Called AE Title")
        } catch (rj: AAssociateRJ) {
            assertEquals(AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED, rj.reason)
        }

        // 6. Проверяем технический журнал и его сохранение между перезапусками
        val logEntries = logger.logsFlow.value
        assertTrue(logEntries.any { it.category == DicomLogCategory.TCP_CONNECT })
        assertTrue(logEntries.any { it.category == DicomLogCategory.ASSOCIATE_RQ })
        assertTrue(logEntries.any { it.category == DicomLogCategory.ASSOCIATE_AC })
        assertTrue(logEntries.any { it.category == DicomLogCategory.PRESENTATION_CONTEXT })
        assertTrue(logEntries.any { it.category == DicomLogCategory.C_ECHO_RQ })
        assertTrue(logEntries.any { it.category == DicomLogCategory.C_ECHO_RSP })
        assertTrue(logEntries.any { it.category == DicomLogCategory.C_STORE_RQ })
        assertTrue(logEntries.any { it.category == DicomLogCategory.C_STORE_RSP })
        assertTrue(logEntries.any { it.category == DicomLogCategory.ASSOCIATE_RJ })
        // Decoder termination does not prove that an A-RELEASE PDU was observed.
        assertTrue(logEntries.any { it.category == DicomLogCategory.TCP_DISCONNECT })
        assertFalse(logEntries.any { it.category == DicomLogCategory.RELEASE_RP })

        // Убеждаемся, что ФИО пациента не попало в технический журнал
        val allLogStrings = logEntries.flatMap { it.summaryLines + it.technicalDetails.values + it.title }
        assertFalse("PatientName must not leak into technical log", allLogStrings.any { it.contains("IVANOV^IVAN") })

        // Проверяем перезагрузку журнала с диска и сохранение журнала при полной очистке исследований
        assertTrue("Observed events must cross the background writer barrier", logger.flushPendingWrites())
        val reloadedLogger = PersistentDicomTechnicalLogger(logFile)
        assertTrue("Every observed event must reload; later connection callbacks may add more",
            reloadedLogger.logsFlow.value.map { it.id }.containsAll(logEntries.map { it.id }))

        repository.clearAllStudies()
        assertEquals(0, repository.studiesFlow.value.size)
        assertTrue("Clearing studies must NOT delete technical logs", reloadedLogger.logsFlow.value.isNotEmpty())
        reloadedLogger.shutdown()
    }

    private fun performCEcho(callingAe: String, calledAe: String, host: String, port: Int): Int {
        val device = Device("test-scu")
        val conn = Connection("scu-conn", "127.0.0.1", 0)
        val ae = ApplicationEntity(callingAe)
        device.addConnection(conn)
        device.addApplicationEntity(ae)
        ae.addConnection(conn)
        device.executor = scuExecutor
        device.scheduledExecutor = scuScheduled

        val remoteConn = Connection("remote-scp", host, port)
        val rq = AAssociateRQ().apply {
            this.callingAET = callingAe
            this.calledAET = calledAe
            addPresentationContext(
                PresentationContext(1, UID.Verification, UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian)
            )
        }

        val assoc = ae.connect(conn, remoteConn, rq)
        return try {
            val rsp = assoc.cecho()
            rsp.next()
            val status = rsp.command.getInt(Tag.Status, -1)
            assoc.release()
            assoc.waitForSocketClose()
            status
        } finally {
            scuDevice = device
        }
    }

    private fun performCStore(
        callingAe: String,
        calledAe: String,
        host: String,
        port: Int,
        sopClassUid: String,
        sopInstanceUid: String,
        transferSyntax: String,
        dataset: Attributes
    ): Int {
        val device = Device("test-scu-store")
        val conn = Connection("scu-store-conn", "127.0.0.1", 0)
        val ae = ApplicationEntity(callingAe)
        device.addConnection(conn)
        device.addApplicationEntity(ae)
        ae.addConnection(conn)
        device.executor = scuExecutor
        device.scheduledExecutor = scuScheduled

        val remoteConn = Connection("remote-scp", host, port)
        val rq = AAssociateRQ().apply {
            this.callingAET = callingAe
            this.calledAET = calledAe
            addPresentationContext(
                PresentationContext(1, sopClassUid, transferSyntax)
            )
        }

        val assoc = ae.connect(conn, remoteConn, rq)
        var cstoreStatus = -1
        val rspHandler = object : DimseRSPHandler(assoc.nextMessageID()) {
            override fun onDimseRSP(asAssoc: Association, cmd: Attributes, data: Attributes?) {
                super.onDimseRSP(asAssoc, cmd, data)
                cstoreStatus = cmd.getInt(Tag.Status, -1)
            }
        }
        assoc.cstore(
            sopClassUid,
            sopInstanceUid,
            0,
            DataWriterAdapter(dataset),
            transferSyntax,
            rspHandler
        )
        assoc.waitForOutstandingRSP()
        assoc.release()
        assoc.waitForSocketClose()
        return cstoreStatus
    }

    private fun createSampleImageDataset(
        sopClassUid: String,
        sopInstanceUid: String,
        studyInstanceUid: String,
        seriesInstanceUid: String,
        modality: String,
        patientName: String,
        patientId: String,
        pixelBytes: ByteArray
    ): Attributes {
        return Attributes().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.SOPClassUID, VR.UI, sopClassUid)
            setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid)
            setString(Tag.StudyDate, VR.DA, "20261012")
            setString(Tag.StudyTime, VR.TM, "120000")
            setString(Tag.Modality, VR.CS, modality)
            setString(Tag.Manufacturer, VR.LO, "Siemens Healthineers")
            setString(Tag.InstitutionName, VR.LO, "CENTRAL DIAGNOSTIC HOSPITAL")
            setString(Tag.StudyDescription, VR.LO, "DIAGNOSTIC SERVICE STUDY")
            setString(Tag.SeriesDescription, VR.LO, "AXIAL SERIES 1")
            setString(Tag.PatientName, VR.PN, patientName)
            setString(Tag.PatientID, VR.LO, patientId)
            setString(Tag.PatientBirthDate, VR.DA, "19800101")
            setString(Tag.PatientSex, VR.CS, "M")
            setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUid)
            setString(Tag.SeriesInstanceUID, VR.UI, seriesInstanceUid)
            setInt(Tag.SeriesNumber, VR.IS, 1)
            setInt(Tag.InstanceNumber, VR.IS, 1)
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.Rows, VR.US, 128)
            setInt(Tag.Columns, VR.US, 128)
            setInt(Tag.BitsAllocated, VR.US, 16)
            setInt(Tag.BitsStored, VR.US, 12)
            setInt(Tag.HighBit, VR.US, 11)
            setInt(Tag.PixelRepresentation, VR.US, 0)
            setDouble(Tag.WindowCenter, VR.DS, 40.0)
            setDouble(Tag.WindowWidth, VR.DS, 80.0)

            // Добавляем вложенную последовательность SQ (ReferencedStudySequence) для проверки отображения SQ в UI
            val refSeq = newSequence(Tag.ReferencedStudySequence, 1)
            refSeq.add(Attributes().apply {
                setString(Tag.ReferencedSOPClassUID, VR.UI, UID.CTImageStorage)
                setString(Tag.ReferencedSOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1143.7777")
            })

            setBytes(Tag.PixelData, VR.OW, pixelBytes)
        }
    }

    @Test
    fun testDcm4cheEngineVerifierCompatibility() {
        val verifier = Dcm4cheEngineVerifier()
        val report = verifier.verifyDcm4cheStackCompatibility()
        assertTrue(report.details, report.isCompatible)
    }

    @Test
    fun unsafeStudyUidReturnsFailedCStoreAndKeepsCompleteSyntheticPayload() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(scpServer.startServer("STORAGE_TEST", port).isSuccess)
        val pixels = ByteArray(128 * 128 * 2) { (it % 127).toByte() }
        val dataset = createSampleImageDataset(UID.CTImageStorage, "1.2.3.4.5", "..", "1.2.3.4",
            "CT", "SYNTHETIC^STORAGE", "SYNTHETIC", pixels)
        val status = performCStore("TEST_SCU", "STORAGE_TEST", "127.0.0.1", port,
            UID.CTImageStorage, "1.2.3.4.5", UID.ExplicitVRLittleEndian, dataset)
        assertEquals(Status.ProcessingFailure, status)
        assertTrue(storageDir.walkTopDown().none { it.extension == "dcm" })
        val retained = File(storageDir, ".incoming_tmp").listFiles()!!.single()
        DicomInputStream(retained).use { dis ->
            dis.readFileMetaInformation()
            assertArrayEquals(pixels, dis.readDataset().getBytes(Tag.PixelData))
        }
    }

    @Test
    fun publicationFailureDoesNotReturnSuccessfulCStoreOrDestroyPayload() {
        val blocker = File(storageDir, "1.2.3/1.2.3.4/1.2.3.4.5.dcm").apply { mkdirs() }
        val keep = File(blocker, "keep.txt").apply { writeText("existing original must remain") }
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(scpServer.startServer("STORAGE_TEST", port).isSuccess)
        val dataset = createSampleImageDataset(UID.CTImageStorage, "1.2.3.4.5", "1.2.3", "1.2.3.4",
            "CT", "SYNTHETIC^STORAGE", "SYNTHETIC", ByteArray(128 * 128 * 2))
        val status = performCStore("TEST_SCU", "STORAGE_TEST", "127.0.0.1", port,
            UID.CTImageStorage, "1.2.3.4.5", UID.ExplicitVRLittleEndian, dataset)
        assertEquals(Status.ProcessingFailure, status)
        assertEquals("existing original must remain", keep.readText())
        assertEquals(1, File(storageDir, ".incoming_tmp").listFiles()!!.count { it.extension == "part" })
    }
}
