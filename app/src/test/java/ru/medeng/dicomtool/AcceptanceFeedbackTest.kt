package ru.medeng.dicomtool

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.storage.*
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*
import java.io.File

class AcceptanceFeedbackTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun activeIncomingIsNotReportedAsAbandonedAndReleasedSourceSurvivesCleanup() {
        val root = temp.newFolder(); val directory = File(root, ".incoming_tmp").apply { mkdirs() }
        val file = IncomingPayloadTracker.create(directory, "scp_synthetic_")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try { assertEquals(0, RetainedIncoming.inspect(root).filesCount) }
        finally { IncomingPayloadTracker.release(file) }
        assertEquals(1, RetainedIncoming.inspect(root).filesCount)
        LocalDicomStudyRepository(root).clearAllStudies()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
    }
    @Test fun safeBriefExportIncludesValidatedSessionLabelsButNoInjectedText() {
        fun entry(label: String) = DicomLogEntry("synthetic", "PHI-TIMESTAMP", DicomLogCategory.C_ECHO_RSP,
            DicomTrafficDirection.OUTBOUND, label, "SYNTHETIC^PHI", emptyList(), sessionUid = "synthetic-run")
        val valid = DicomSessionGrouper.groupEvents(listOf(entry("SCP-3"))).single()
        val safe = DicomLogExportUtils.formatFullExport(listOf(valid), valid.events, true, true)
        assertTrue(safe.contains("Сеанс: SCP-3")); assertTrue(safe.contains("Сеанс экспорта 1"))
        assertFalse(safe.contains("PHI-TIMESTAMP")); assertFalse(safe.contains("SYNTHETIC^PHI"))
        assertFalse(SafeTechnicalSummary.event(entry("SCP-3\nPatientName=SECRET")).contains("SECRET"))
        assertFalse(SafeTechnicalSummary.event(entry("SCP-00042")).contains("SCP-00042"))
    }
}
