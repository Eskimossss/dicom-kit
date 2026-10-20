package ru.medeng.dicomtool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.mwl.scp.MwlScpServer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Интеграционный тест взаимодействия MWL SCP с официальной эталонной утилитой DCMTK findscu.exe (DICOM C-FIND SCU).
 *
 * Флаг -W указывает findscu использовать Modality Worklist Information Model - FIND (1.2.840.10008.5.1.4.31).
 */
class MwlDcmtkInteroperabilityTest {

    private val dcmtkFindScu = File("C:\\DCMTK\\bin\\findscu.exe")
    private lateinit var tempDir: File
    private lateinit var logFile: File
    private lateinit var repository: LocalMwlRepository
    private lateinit var logger: MwlTechnicalLogger
    private lateinit var scpServer: MwlScpServer
    private var testPort: Int = 0

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    @Before
    fun setUp() {
        Assume.assumeTrue("DCMTK findscu.exe не найден в C:\\DCMTK\\bin\\", dcmtkFindScu.exists())

        tempDir = Files.createTempDirectory("mwl_dcmtk_test").toFile()
        logFile = File(tempDir, "mwl_dcmtk_log.tsv")
        repository = LocalMwlRepository(tempDir, autoSeedIfEmpty = false)
        logger = MwlTechnicalLogger(logFile)
        scpServer = MwlScpServer(repository, logger)
        testPort = findFreePort()

        // 1. Пациент Иванов с исследованием CR
        val p1 = MwlPatient(patientName = "ИВАНОВ^ИВАН", patientId = "PAT-DCMTK-01", isSynthetic = true)
        val s1 = MwlScheduledStudy(
            patientInternalId = p1.id,
            patientName = p1.patientName,
            patientId = p1.patientId,
            accessionNumber = "ACC-DCMTK-01",
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureId = "RP-CR-01",
            spsId = "SPS-CR-01",
            modality = "CR",
            spsStartDate = "20261020",
            spsStartTime = "090000",
            scheduledStationAeTitle = "CR_STATION_1"
        )

        // 2. Пациент Петров с исследованием CT
        val p2 = MwlPatient(patientName = "ПЕТРОВ^ПЕТР", patientId = "PAT-DCMTK-02", isSynthetic = true)
        val s2 = MwlScheduledStudy(
            patientInternalId = p2.id,
            patientName = p2.patientName,
            patientId = p2.patientId,
            accessionNumber = "ACC-DCMTK-02",
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureId = "RP-CT-02",
            spsId = "SPS-CT-02",
            modality = "CT",
            spsStartDate = "20261020",
            spsStartTime = "100000",
            scheduledStationAeTitle = "CT_SCANNER_1"
        )

        repository.addSyntheticBatch(listOf(Pair(p1, s1), Pair(p2, s2)))

        val startRes = scpServer.startServer("DCMTK_MWL_SCP", testPort)
        assertTrue(startRes.isSuccess)
        assertEquals(ScpServerStatus.RUNNING, scpServer.state.value.status)
    }

    @After
    fun tearDown() {
        if (::scpServer.isInitialized) {
            scpServer.stopServer()
        }
        if (::tempDir.isInitialized) {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDcmtkFindScuQueriesAllWorklistEntries() {
        // findscu -v -W -k "(0010,0010)" -k "(0010,0020)" -k "(0008,0050)" -k "(0040,0100)[0].(0008,0060)" 127.0.0.1 <port> -aec DCMTK_MWL_SCP -aet DCMTK_SCU
        val cmd = listOf(
            dcmtkFindScu.absolutePath,
            "-v",
            "-W", // Modality Worklist SOP Class
            "-k", "0010,0010", // Patient Name
            "-k", "0010,0020", // Patient ID
            "-k", "0008,0050", // Accession Number
            "-k", "0040,0100[0].0008,0060", // SPS Sequence Modality
            "-aec", "DCMTK_MWL_SCP",
            "-aet", "DCMTK_SCU",
            "127.0.0.1",
            testPort.toString()
        )

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }

        val completed = proc.waitFor(10, TimeUnit.SECONDS)
        assertTrue("DCMTK findscu timed out", completed)
        val exitCode = proc.exitValue()

        val logOutput = output.toString()
        println("=== DCMTK findscu output ===")
        println(logOutput)

        assertEquals("findscu exit code must be 0 (Success)", 0, exitCode)
        assertTrue("Must negotiate association", logOutput.contains("Association Accepted") || logOutput.contains("A-ASSOCIATE"))
        assertTrue("Must receive response for PAT-DCMTK-01", logOutput.contains("PAT-DCMTK-01"))
        assertTrue("Must receive response for PAT-DCMTK-02", logOutput.contains("PAT-DCMTK-02"))
        assertTrue("Must contain CR modality", logOutput.contains("CR"))
        assertTrue("Must contain CT modality", logOutput.contains("CT"))
    }

    @Test
    fun testDcmtkFindScuQueryFilteredByModalityCt() {
        // Запрос с фильтром Modality=CT
        val cmd = listOf(
            dcmtkFindScu.absolutePath,
            "-v",
            "-W",
            "-k", "0010,0010",
            "-k", "0010,0020",
            "-k", "0040,0100[0].0008,0060=CT", // Фильтрация по модальности CT
            "-aec", "DCMTK_MWL_SCP",
            "-aet", "DCMTK_SCU",
            "127.0.0.1",
            testPort.toString()
        )

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }

        val completed = proc.waitFor(10, TimeUnit.SECONDS)
        assertTrue("DCMTK findscu timed out", completed)
        val exitCode = proc.exitValue()
        val logOutput = output.toString()

        assertEquals("findscu exit code must be 0", 0, exitCode)
        assertTrue("Must match CT patient", logOutput.contains("PAT-DCMTK-02"))
        // Не должен возвращать PAT-DCMTK-01 (CR)
        org.junit.Assert.assertFalse("Must NOT match CR patient", logOutput.contains("PAT-DCMTK-01"))
    }
}
