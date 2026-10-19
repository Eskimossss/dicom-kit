package ru.medeng.dicomtool

import org.junit.Test
import org.junit.Assert.*
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*

class Stage6ScpOutcomesTest {
    private fun event(category: DicomLogCategory, details: Map<String,String> = emptyMap(), error: Boolean = false) =
        DicomLogEntry("synthetic", "12:00:00.000", category, DicomTrafficDirection.INBOUND, "SCP-1", "synthetic", emptyList(), details, error, "SCP-synthetic-run-1")
    private fun file(id: Int, storage: String? = "VERIFIED", status: String? = "0x0000", write: String = "LOCAL_COMPLETED") = buildList {
        add(event(DicomLogCategory.C_STORE_RQ, mapOf("Message ID" to "$id", "SOP Instance UID" to "1.2.840.999.6.1")))
        storage?.let { add(event(DicomLogCategory.SERVER_LIFECYCLE, mapOf("Message ID Being Responded To" to "$id", "Storage Result" to it))) }
        if (status != null) add(event(if (write == "FAILED") DicomLogCategory.NETWORK_ERROR else DicomLogCategory.C_STORE_RSP,
            mapOf("Message ID Being Responded To" to "$id", "DICOM Status" to status, "Response Write" to write), write == "FAILED"))
    }
    private fun summary(events: List<DicomLogEntry>) = DicomSessionGrouper.groupEvents(events + event(DicomLogCategory.TCP_DISCONNECT)).single()
    @Test fun storedFilesAndZeroDimseStatusAreSuccessfulWithoutApplicationAck() {
        val s = summary((1..4).flatMap { file(it) })
        assertEquals(SessionOutcomeStatus.SUCCESS, s.status); assertEquals(4, s.filesCount)
        assertTrue(s.statusTitle.contains("4")); assertFalse(s.statusSubtitle.contains("не подтверждено"))
        assertTrue(s.filesTransferred.all { it.storageResult == "VERIFIED" && it.dimseStatus == 0 })
    }
    @Test fun dimseWarningAndFailureUseActualCodeRegardlessOfErrorFlag() {
        assertEquals(SessionOutcomeStatus.WARNING, summary(file(1, status = "0xB000")).status)
        assertEquals(SessionOutcomeStatus.FAILURE, summary(file(1, "FAILED", "0xA700")).status)
        assertEquals(SessionOutcomeStatus.WARNING, summary(file(1) + file(2, "FAILED", "0x0110")).status)
    }
    @Test fun failedResponseDoesNotUndoStoredFileOrPretendRemoteReceipt() {
        val s = summary(file(1, write = "FAILED"))
        assertEquals(SessionOutcomeStatus.WARNING, s.status)
        assertEquals("VERIFIED", s.filesTransferred.single().storageResult)
        assertEquals("UNCONFIRMED", s.filesTransferred.single().scuOutcome)
        assertTrue(s.statusTitle.contains("Ошибка передачи ответа"))
        assertEquals(SessionOutcomeStatus.UNKNOWN, summary(file(1, storage = null, status = null)).status)
    }
    @Test fun sameUidResponsesRemainMatchedByMessageIdAndLegacyMissingStorageRemainsUnknown() {
        val s = summary(file(1) + file(2, "FAILED", "0x0110"))
        assertEquals(listOf(0, 0x0110), s.filesTransferred.map { it.dimseStatus })
        assertEquals(listOf("VERIFIED", "FAILED"), s.filesTransferred.map { it.storageResult })
        assertNull(summary(file(1, storage = null)).filesTransferred.single().storageResult)
    }
}
