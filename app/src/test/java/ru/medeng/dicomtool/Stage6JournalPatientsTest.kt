package ru.medeng.dicomtool

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.dicom.charset.DicomPersonName
import java.io.File

class Stage6JournalPatientsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun event(patient: DicomLogPatient? = null, category: DicomLogCategory = DicomLogCategory.C_STORE_RQ) =
        DicomLogEntry("synthetic", "12:00:00.000", category, DicomTrafficDirection.INBOUND, "SCP-1", "synthetic", emptyList(),
            sessionUid = "SCP-run-synthetic-1", patients = listOfNotNull(patient))
    @Test fun personNameKeepsComponentsAlternativesAndMissingParts() {
        assertEquals("Синтетический Тест Тестович", DicomPersonName.display("СИНТЕТИЧЕСКИЙ^ТЕСТ^ТЕСТОВИЧ"))
        assertEquals("テスト 名前", DicomPersonName.display("=テスト^名前=テスト^ナマエ"))
        assertEquals("Dr. Synthetic Q. Jr.", DicomPersonName.display("Synthetic^^Q.^Dr.^Jr."))
        assertEquals("", DicomPersonName.display("^^^=="))
        assertEquals("", DicomPersonName.display(null))
    }
    @Test fun multiplePatientsSearchAndSameNameWithDifferentIdentities() {
        val a = DicomLogPatient.from("СИНТЕТИЧЕСКИЙ^ТЕСТ^ТЕСТОВИЧ", "SYNTHETIC-1")
        val b = DicomLogPatient.from(a.name, "SYNTHETIC-2")
        val summary = DicomSessionGrouper.groupEvents(listOf(event(a), event(b), event(a))).single()
        assertEquals(2, summary.patients.size)
        for (query in listOf("синтетический", "тест", "тестович", "синтетический тест тестович"))
            assertTrue(summary.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, query))
        assertTrue(DicomSessionGrouper.groupEvents(listOf(event())).single().patients.isEmpty())
    }
    @Test fun privatePersistenceReadsOldRowsAndDefaultExportNeverIncludesPatientMetadata() {
        val file = File(temp.root, "events.tsv")
        val patient = DicomLogPatient.from("СИНТЕТИЧЕСКИЙ^ИМЯ", "SYNTHETIC-ID")
        val logger = PersistentDicomTechnicalLogger(file)
        logger.recordEvent(event(patient)); assertTrue(logger.flushPendingWrites()); logger.shutdown()
        val loaded = PersistentDicomTechnicalLogger(file)
        try {
            val entry = loaded.logsFlow.value.single()
            assertEquals(patient, entry.patients.single())
            val summary = DicomSessionGrouper.groupEvents(listOf(entry)).single()
            for (brief in listOf(true, false)) {
                val safe = DicomLogExportUtils.formatFullExport(listOf(summary), listOf(entry), brief, true)
                assertFalse(safe.contains("СИНТЕТИЧЕСКИЙ")); assertFalse(safe.contains(patient.identityKey))
            }
            val old = file.readLines().first().split('\t').take(10).joinToString("\t")
            assertTrue(loaded.deserializeEntry(old)!!.patients.isEmpty())
        } finally { loaded.shutdown() }
    }
}

