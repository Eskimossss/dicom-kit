package ru.medeng.dicomtool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.mwl.engine.MwlMatchingEngine
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.MwlDateFilterOption
import ru.medeng.dicomtool.mwl.model.MwlModalityTemplate
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import ru.medeng.dicomtool.mwl.model.MwlScuFilter
import ru.medeng.dicomtool.mwl.model.MwlScuProfile
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.mwl.model.SyntheticMwlGenerator
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.mwl.scp.MwlScpServer
import ru.medeng.dicomtool.mwl.scu.MwlScuClient
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class MwlScpAndScuDimseTest {

    private lateinit var tempDir: File
    private lateinit var logFile: File
    private lateinit var repository: LocalMwlRepository
    private lateinit var logger: MwlTechnicalLogger
    private lateinit var scpServer: MwlScpServer
    private lateinit var scuClient: MwlScuClient
    private var testPort: Int = 0

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mwl_dimse_test").toFile()
        logFile = File(tempDir, "mwl_log.tsv")
        repository = LocalMwlRepository(tempDir, autoSeedIfEmpty = false)
        logger = MwlTechnicalLogger(logFile)
        scpServer = MwlScpServer(repository, logger)
        scuClient = MwlScuClient(logger)
        testPort = findFreePort()

        // Создаем тестовых пациентов и исследования (CR и CT)
        val p1 = MwlPatient(patientName = "ИВАНОВ^ИВАН", patientId = "PAT-001", isSynthetic = true)
        val s1 = MwlScheduledStudy(
            patientInternalId = p1.id,
            patientName = p1.patientName,
            patientId = p1.patientId,
            accessionNumber = "ACC-CR-01",
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureDescription = "TEST PROCEDURE",
            requestedProcedureId = "RP-CR-01",
            spsId = "SPS-CR-01",
            modality = "CR",
            spsStartDate = "20261020",
            spsStartTime = "090000",
            scheduledStationAeTitle = "CR_STATION_1"
        )

        val p2 = MwlPatient(patientName = "ПЕТРОВ^ПЕТР", patientId = "PAT-002", isSynthetic = true)
        val s2 = MwlScheduledStudy(
            patientInternalId = p2.id,
            patientName = p2.patientName,
            patientId = p2.patientId,
            accessionNumber = "ACC-CT-02",
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureDescription = "TEST PROCEDURE",
            requestedProcedureId = "RP-CT-02",
            spsId = "SPS-CT-02",
            modality = "CT",
            spsStartDate = "20261020",
            spsStartTime = "100000",
            scheduledStationAeTitle = "CT_SCANNER_1"
        )

        repository.addSyntheticBatch(listOf(Pair(p1, s1), Pair(p2, s2)))
    }

    @After
    fun tearDown() {
        scpServer.stopServer()
        logger.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testServerLifecycleAndPortRelease() {
        val startResult = scpServer.startServer("TEST_MWL_SCP", testPort)
        assertTrue(startResult.isSuccess)
        assertEquals(ScpServerStatus.RUNNING, scpServer.state.value.status)

        scpServer.stopServer()
        assertEquals(ScpServerStatus.STOPPED, scpServer.state.value.status)

        // Порт должен быть освобожден и снова доступен
        val restartResult = scpServer.startServer("TEST_MWL_SCP", testPort)
        assertTrue(restartResult.isSuccess)
        assertEquals(ScpServerStatus.RUNNING, scpServer.state.value.status)
    }

    @Test
    fun testCFindQueryAllMatches() {
        scpServer.startServer("TEST_MWL_SCP", testPort)

        val profile = MwlScuProfile(
            name = "Local Test",
            host = "127.0.0.1",
            port = testPort,
            calledAet = "TEST_MWL_SCP",
            callingAet = "TEST_SCU"
        )

        val filter = MwlScuFilter(dateOption = MwlDateFilterOption.ANY)
        val result = scuClient.queryWorklist(profile, filter)
        if (result.isFailure) {
            result.exceptionOrNull()?.printStackTrace()
            throw result.exceptionOrNull()!!
        }

        assertTrue(result.isSuccess)
        val items = result.getOrNull().orEmpty()
        assertEquals(2, items.size)
        val modalities = items.map { it.modality }.toSet()
        assertTrue(modalities.contains("CR"))
        assertTrue(modalities.contains("CT"))
    }

    @Test
    fun testCFindQueryFilterByModality() {
        scpServer.startServer("TEST_MWL_SCP", testPort)

        val profile = MwlScuProfile(
            name = "Local Test",
            host = "127.0.0.1",
            port = testPort,
            calledAet = "TEST_MWL_SCP",
            callingAet = "TEST_SCU"
        )

        // Ищем только модальность CR
        val filterCr = MwlScuFilter(dateOption = MwlDateFilterOption.ANY, modality = "CR")
        val resultCr = scuClient.queryWorklist(profile, filterCr)
        assertTrue(resultCr.isSuccess)
        val itemsCr = resultCr.getOrNull().orEmpty()
        assertEquals(1, itemsCr.size)
        assertEquals("CR", itemsCr[0].modality)
        assertEquals("ИВАНОВ^ИВАН", itemsCr[0].patientName)

        // Ищем только модальность CT
        val filterCt = MwlScuFilter(dateOption = MwlDateFilterOption.ANY, modality = "CT")
        val resultCt = scuClient.queryWorklist(profile, filterCt)
        assertTrue(resultCt.isSuccess)
        val itemsCt = resultCt.getOrNull().orEmpty()
        assertEquals(1, itemsCt.size)
        assertEquals("CT", itemsCt[0].modality)
        assertEquals("ПЕТРОВ^ПЕТР", itemsCt[0].patientName)

        // Ищем отсутствующую модальность MG (должен вернуть 0 совпадений со статусом Success)
        val filterMg = MwlScuFilter(dateOption = MwlDateFilterOption.ANY, modality = "MG")
        val resultMg = scuClient.queryWorklist(profile, filterMg)
        assertTrue(resultMg.isSuccess)
        assertEquals(0, resultMg.getOrNull()?.size)
    }

    @Test
    fun testCFindQueryFilterByPatientNameWildcard() {
        scpServer.startServer("TEST_MWL_SCP", testPort)

        val profile = MwlScuProfile(
            name = "Local Test",
            host = "127.0.0.1",
            port = testPort,
            calledAet = "TEST_MWL_SCP",
            callingAet = "TEST_SCU"
        )

        val filter = MwlScuFilter(dateOption = MwlDateFilterOption.ANY, patientName = "ИВАН*")
        val result = scuClient.queryWorklist(profile, filter)
        assertTrue(result.isSuccess)
        val items = result.getOrNull().orEmpty()
        assertEquals(1, items.size)
        assertEquals("ИВАНОВ^ИВАН", items[0].patientName)
    }

    @Test
    fun testWhitelistCallingAeRejection() {
        scpServer.updateConfiguration(
            aeTitle = "TEST_MWL_SCP",
            port = testPort,
            whitelistMode = true,
            allowedCallingAets = listOf("ALLOWED_AE")
        )
        scpServer.startServer("TEST_MWL_SCP", testPort)

        // 1. Попытка подключения с запрещённым Calling AE Title
        val unauthorizedProfile = MwlScuProfile(
            name = "Unauthorized SCU",
            host = "127.0.0.1",
            port = testPort,
            calledAet = "TEST_MWL_SCP",
            callingAet = "UNAUTHORIZED_AE"
        )
        val unauthResult = scuClient.queryWorklist(unauthorizedProfile, MwlScuFilter(dateOption = MwlDateFilterOption.ANY))
        assertFalse(unauthResult.isSuccess) // Должен быть отказ в ассоциации (A-ASSOCIATE-RJ)

        // 2. Попытка подключения с разрешённым Calling AE Title
        val authorizedProfile = MwlScuProfile(
            name = "Authorized SCU",
            host = "127.0.0.1",
            port = testPort,
            calledAet = "TEST_MWL_SCP",
            callingAet = "ALLOWED_AE"
        )
        val authResult = scuClient.queryWorklist(authorizedProfile, MwlScuFilter(dateOption = MwlDateFilterOption.ANY))
        assertTrue(authResult.isSuccess)
        assertEquals(2, authResult.getOrNull()?.size)
    }

    @Test
    fun testDiagnosticLoggerRecording() {
        scpServer.startServer("TEST_MWL_SCP", testPort)

        val profile = MwlScuProfile(
            name = "Local Test",
            host = "127.0.0.1",
            port = testPort,
            calledAet = "TEST_MWL_SCP",
            callingAet = "TEST_SCU"
        )

        scuClient.queryWorklist(profile, MwlScuFilter(dateOption = MwlDateFilterOption.ANY))

        val recordedLogs = logger.logsFlow.value
        assertTrue(recordedLogs.isNotEmpty())
        assertTrue(recordedLogs.any { it.title.contains("C-FIND") })

        val reportAnonymized = logger.exportDiagnosticReport(includePhi = false)
        assertTrue(reportAnonymized.contains("ОБЕЗЛИЧЕН"))

        val reportWithPhi = logger.exportDiagnosticReport(includePhi = true)
        assertTrue(reportWithPhi.contains("ВКЛЮЧЕН"))
    }
}
