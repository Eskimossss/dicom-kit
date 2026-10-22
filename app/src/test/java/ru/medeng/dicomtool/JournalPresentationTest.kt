package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Test
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*

class JournalPresentationTest {
    private val uid = "1.2.238.03.127.0.2907.20261022090340"
    private fun event(title: String = "C-STORE storage verified (18.30 MB); response pending",
                      details: Map<String, String> = emptyMap(), category: DicomLogCategory = DicomLogCategory.SERVER_LIFECYCLE) =
        DicomLogEntry("synthetic", "12:00:00.000", category, DicomTrafficDirection.INBOUND, "SCP-SYNTHETIC",
            title, listOf("No exception reported"), details, sessionUid = "synthetic-journal")
    private fun shown(e: DicomLogEntry) = DicomEventPresentation.present(e)

    @Test fun knownTemplatesTranslateOnlyDescriptionsAndKeepCapturedIdentifiers() {
        val cases = mapOf(
            "C-STORE-RQ receiving (Message ID: 17)" to "Получен запрос C-STORE-RQ (Message ID: 17)",
            "C-ECHO-RQ received (Message ID: 18)" to "Получен запрос C-ECHO-RQ (Message ID: 18)",
            "C-STORE storage verified (18.30 MB); response pending" to "DICOM-файл сохранён и проверен (18,30 МБ); ответ ещё не отправлен",
            "C_STORE_RSP local write attempted" to "Начата запись ответа C-STORE-RSP в соединение",
            "C_ECHO_RSP local write completed; peer receipt unconfirmed" to "Ответ C-ECHO-RSP записан в соединение; получение удалённой стороной не подтверждено",
            "C_STORE_RSP local write failed; peer result unknown" to "Ошибка записи ответа C-STORE-RSP; результат удалённой стороны неизвестен",
            "TCP connected from 10.20.30.40:11112" to "Установлено TCP-соединение с 10.20.30.40:11112",
            "DICOM Storage SCP started on 10.20.30.40:11112 (AE: SYNTHETIC_AE)" to "Storage SCP запущен: 10.20.30.40:11112 (AE Title: SYNTHETIC_AE)",
            "C-FIND-RSP final FF00" to "Итоговый ответ C-FIND-RSP: FF00",
            "C-CANCEL-FIND received" to "Получен запрос отмены C-CANCEL-FIND"
        )
        cases.forEach { (raw, translated) -> assertEquals(raw, translated, shown(event(raw)).title) }
    }
    @Test fun unknownTextFallsBackVerbatimIncludingEmbeddedKnownPhrases() {
        val raw = "Vendor context: C-STORE storage verified (18.30 MB); response pending, custom payload"
        val e = event(raw, mapOf("Vendor diagnostic" to raw)).copy(summaryLines = listOf(raw))
        assertEquals(raw, shown(e).title); assertEquals(listOf(raw), shown(e).summary)
        assertEquals(listOf("Vendor diagnostic" to raw), shown(e).details)
    }
    @Test fun successfulStorageWithUidWarningRemainsSuccessfulAndPreservesTags() {
        val warning = "Study Instance UID (0020,000D): non-conformant UID (leading zero), preserved verbatim: $uid"
        val e = event(details = mapOf("Storage Result" to "VERIFIED", "DICOM Status" to "0x0000 (Success)", "UID Warning" to warning))
        val p = shown(e)
        assertEquals(EventOutcome.SUCCESS_WITH_WARNINGS, p.outcome)
        val translated = p.details.single { it.first == "Предупреждение UID" }.second
        for (word in listOf("Study Instance UID", "(0020,000D)", uid, "ведущий ноль", "без изменений")) assertTrue(translated, translated.contains(word))
        assertEquals(warning, e.technicalDetails["UID Warning"])
    }
    @Test fun outcomeUsesStructuredEvidenceAndDoesNotTreatPlannedResponseAsSuccess() {
        val base = event("Success failed warning 0x0110")
        assertEquals(EventOutcome.INFO, shown(base).outcome)
        assertEquals(EventOutcome.INFO, shown(base.copy(technicalDetails = mapOf("DICOM Status" to "0x0000", "Response Write" to "ATTEMPTED"))).outcome)
        assertEquals(EventOutcome.ERROR, shown(base.copy(technicalDetails = mapOf("DICOM Status" to "0x0000", "Response Write" to "FAILED"))).outcome)
        assertEquals(EventOutcome.ERROR, shown(base.copy(technicalDetails = mapOf("Storage Result" to "FAILED"))).outcome)
        assertEquals(EventOutcome.WARNING, shown(base.copy(technicalDetails = mapOf("UID Warning" to "synthetic"))).outcome)
        assertEquals(EventOutcome.SUCCESS, shown(base.copy(technicalDetails = mapOf("Storage Result" to "VERIFIED"))).outcome)
        assertEquals(EventOutcome.SUCCESS_WITH_WARNINGS, shown(base.copy(technicalDetails = mapOf("Storage Result" to "VERIFIED", "DICOM Status" to "0xB000"))).outcome)
        assertEquals(EventOutcome.ERROR, shown(base.copy(isError = true, technicalDetails = mapOf("Storage Result" to "VERIFIED"))).outcome)
    }
    @Test fun statusFormatsPendingCancelAndWarningsAreClassifiedWithoutGuessingFromTitle() {
        for (status in listOf("0x0000", "0000", "0000H Success", "0X0000 (Success)"))
            assertEquals(status, EventOutcome.SUCCESS, shown(event("failed", mapOf("Status" to status), DicomLogCategory.C_ECHO_RSP)).outcome)
        for ((status, outcome) in mapOf("0110" to EventOutcome.ERROR, "A700" to EventOutcome.ERROR,
            "B000" to EventOutcome.WARNING, "FE00" to EventOutcome.WARNING, "FF00" to EventOutcome.INFO, "FF01" to EventOutcome.INFO))
            assertEquals(status, outcome, shown(event("success", mapOf("Status" to status))).outcome)
    }
    @Test fun russianAndOriginalSearchFindSameEventAndSessionWithTechnicalIdentifiers() {
        val e = event(details = mapOf("Storage Result" to "VERIFIED", "SOP Instance UID" to uid,
            "Remote IP" to "10.20.30.40", "Calling AE Title" to "SYNTHETIC_AE"))
        val session = DicomSessionGrouper.groupEvents(listOf(e)).single()
        for (query in listOf("сохранён и проверен", "storage verified", "18,30 МБ", "No exception reported",
            "Исключений не зарегистрировано", "SOP Instance UID", uid, "10.20.30.40", "SYNTHETIC_AE", "Результат сохранения")) {
            assertTrue(query, e.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, query))
            assertTrue(query, session.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, query))
        }
        assertFalse(e.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "never seen synthetic value"))
    }
    @Test fun presentationSearchRespectsTrafficAndOutcomeFilters() {
        val e = event("C-ECHO-RQ received (Message ID: 17)")
        assertTrue(e.matches(DicomTrafficFilter.SCP_IN, DicomOutcomeFilter.ALL, "Получен запрос"))
        assertFalse(e.matches(DicomTrafficFilter.SCU_OUT, DicomOutcomeFilter.ALL, "Получен запрос"))
        val scu = e.copy(direction = DicomTrafficDirection.OUTBOUND, associationId = "SCU-SYNTHETIC")
        assertTrue(scu.matches(DicomTrafficFilter.SCU_OUT, DicomOutcomeFilter.ALL, "Получен запрос"))
        assertFalse(scu.matches(DicomTrafficFilter.SCP_IN, DicomOutcomeFilter.ALL, "Получен запрос"))
        assertFalse(e.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ERRORS, "Получен запрос"))
    }
    @Test fun displayingAndSearchingDoNotChangeEventFullExportOrSafeCopy() {
        val e = event(details = linkedMapOf("DICOM Status" to "0x0000", "SOP Instance UID" to uid,
            "UID Warning" to "SOP Instance UID (0008,0018): non-conformant UID (leading zero), preserved verbatim: $uid"))
        val snapshot = e.copy(summaryLines = e.summaryLines.toList(), technicalDetails = e.technicalDetails.toMap())
        val full = DicomLogExportUtils.formatSingleEvent(e, false)
        val safe = DicomLogExportUtils.formatSingleEvent(e, true)
        val export = DicomLogExportUtils.formatFullExport(emptyList(), listOf(e), false, false)
        shown(e); e.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "ведущий ноль")
        assertEquals(snapshot, e)
        assertEquals(full, DicomLogExportUtils.formatSingleEvent(e, false))
        assertEquals(safe, DicomLogExportUtils.formatSingleEvent(e, true))
        assertEquals(export, DicomLogExportUtils.formatFullExport(emptyList(), listOf(e), false, false))
        for (value in listOf(e.title, uid, "UID Warning", "non-conformant UID", "0x0000")) assertTrue(value, full.contains(value))
        assertFalse(safe.contains(uid))
    }
}
