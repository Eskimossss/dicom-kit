package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Test
import ru.medeng.dicomtool.mwl.engine.MwlMatchingEngine
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.domain.model.*
import java.io.File
import java.nio.file.Files
import java.io.IOException
import ru.medeng.dicomtool.mwl.repo.AtomicMwlFile
import ru.medeng.dicomtool.mwl.repo.MwlJsonUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MwlAuditDataRegressionTest {
    @Test fun audit05_failedReplacementPreservesBothCommittedFileAndMemory() {
        val dir = Files.createTempDirectory("mwl-atomic").toFile()
        try {
            val repo = LocalMwlRepository(dir, false)
            val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
            repo.addSyntheticBatch(listOf(p to s))
            val file = File(dir, "mwl_snapshot.json")
            val original = file.readBytes()
            val failing = LocalMwlRepository(dir, false) { target, text ->
                AtomicMwlFile.write(target, text) { _, _ -> throw IOException("injected replacement failure") }
            }
            assertThrows(IOException::class.java) { failing.savePatient(p.copy(patientName = "CHANGED")) }
            assertArrayEquals(original, file.readBytes())
            assertEquals(p.patientName, failing.getPatientById(p.id)?.patientName)
            val reopened = LocalMwlRepository(dir, false)
            assertEquals(p.patientName, reopened.getPatientById(p.id)?.patientName)
            assertEquals(p.patientName, reopened.getStudyById(s.id)?.patientName)
            assertFalse(File(dir, "mwl_snapshot.json.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun audit05_legacyDataMigratesAndClearDoesNotReseedOnRestart() {
        val dir = Files.createTempDirectory("mwl-legacy").toFile()
        try {
            val source = LocalMwlRepository(File(dir, "source"), false)
            val pair = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
            source.addSyntheticBatch(listOf(pair))
            val root = MwlJsonUtils.parseObject(source.exportSyntheticJson())
            val legacy = File(dir, "legacy").apply { mkdirs() }
            File(legacy, "mwl_patients.json").writeText(root.getValue("patients"))
            File(legacy, "mwl_studies.json").writeText(root.getValue("studies"))
            val migrated = LocalMwlRepository(legacy, false)
            assertEquals(pair.first, migrated.getPatientById(pair.first.id))
            migrated.savePatient(pair.first.copy(patientName = "UPDATED"))
            val reopened = LocalMwlRepository(legacy, false)
            assertEquals("UPDATED", reopened.getStudyById(pair.second.id)?.patientName)
            reopened.clearAll()
            assertTrue(LocalMwlRepository(legacy, true).getAllPatients().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun audit05_profileWriteFailureKeepsPreviousProfile() {
        val dir = Files.createTempDirectory("mwl-profile-failure").toFile()
        try {
            val profiles = ru.medeng.dicomtool.mwl.repo.MwlScuProfileRepository(dir)
            val original = profiles.getDefaultProfile()!!
            File(dir, "mwl_scu_profiles.json.tmp").mkdir()
            assertThrows(IOException::class.java) { profiles.saveProfile(original.copy(name = "CHANGED")) }
            assertEquals(original, profiles.getDefaultProfile())
            assertEquals(original, ru.medeng.dicomtool.mwl.repo.MwlScuProfileRepository(dir).getDefaultProfile())
        } finally { dir.deleteRecursively() }
    }

    @Test fun audit12_malformedOrPartialImportCannotChangeCommittedData() {
        val dir = Files.createTempDirectory("mwl-bad-import").toFile()
        try {
            val repo = LocalMwlRepository(dir, false)
            repo.addSyntheticBatch(listOf(SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)))
            val original = repo.exportSyntheticJson()
            assertThrows(IllegalArgumentException::class.java) { repo.importSyntheticJson(original.dropLast(1)) }
            assertEquals(original, repo.exportSyntheticJson())
            assertThrows(IllegalArgumentException::class.java) { repo.importSyntheticJson("{\"version\":1,\"patients\":[],\"studies\":[{}]}") }
            assertEquals(original, repo.exportSyntheticJson())
        } finally { dir.deleteRecursively() }
    }

    @Test fun audit15_diskStallCannotBlockRecordAndQueueIsBounded() {
        val dir = Files.createTempDirectory("mwl-log-stall").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logger = MwlTechnicalLogger(File(dir, "log.tsv"), 20, 2048) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            logger.recordEvent(DicomLogCategory.C_ECHO_RQ, title = "first")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                executor.submit { repeat(1000) { logger.recordEvent(DicomLogCategory.C_ECHO_RQ, title = "entry $it") } }.get(2, TimeUnit.SECONDS)
                assertEquals(20, logger.logsFlow.value.size)
                assertTrue(logger.droppedEntries > 0)
            } finally { executor.shutdownNow() }
        } finally { release.countDown(); logger.close(); dir.deleteRecursively() }
    }

    @Test fun audit15_logRotationAndRestartAreBounded() {
        val dir = Files.createTempDirectory("mwl-log-rotate").toFile()
        val file = File(dir, "log.tsv")
        try {
            MwlTechnicalLogger(file, 5, 1024).use { logger ->
                repeat(40) { logger.recordEvent(DicomLogCategory.C_ECHO_RQ, title = "entry $it " + "X".repeat(80)) }
                assertTrue(logger.flush())
                assertNull(logger.lastWriteError)
            }
            assertTrue(file.length() <= 1024)
            assertTrue(File(file.path + ".1").length() in 1..1024)
            MwlTechnicalLogger(file, 3, 1024).use { assertTrue(it.logsFlow.value.size <= 3); assertTrue(it.logsFlow.value.first().title.startsWith("entry 39")) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun audit12_exportImportPreservesEveryFieldAndUnattachedPatient() {
        val dir = Files.createTempDirectory("mwl-roundtrip").toFile()
        try {
            val source = LocalMwlRepository(File(dir, "source"), false)
            val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
            source.addSyntheticBatch(listOf(p.copy(patientWeight = "75.5", medicalAlerts = "Тест \"A\" B") to s))
            source.savePatient(p.copy(id = "unattached", patientId = "SOLO"))
            val restored = LocalMwlRepository(File(dir, "restored"), false)
            assertEquals(1, restored.importSyntheticJson(source.exportSyntheticJson()))
            assertEquals(source.getAllPatients().toSet(), restored.getAllPatients().toSet())
            assertEquals(source.getAllStudies().toSet(), restored.getAllStudies().toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun audit06_anonymizedReportCannotContainAccessionOrBarePatientName() {
        val dir = Files.createTempDirectory("mwl-phi").toFile()
        try {
            val logger = MwlTechnicalLogger(File(dir, "log.tsv"))
            logger.recordEvent(DicomLogCategory.C_ECHO_RQ, title = "C-FIND", summaryLines = listOf("Accession: SECRET-ACC"), technicalDetails = mapOf("PatientName" to "SECRET-NAME"))
            val report = logger.exportDiagnosticReport(false)
            assertFalse(report.contains("SECRET-ACC"))
            assertFalse(report.contains("SECRET-NAME"))
            assertTrue(logger.exportDiagnosticReport(true).contains("SECRET-NAME"))
        } finally { dir.deleteRecursively() }
    }
}
