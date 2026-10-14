package ru.medeng.dicomtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.medeng.dicomtool.data.logging.DiagnosticRecommendation
import ru.medeng.dicomtool.data.logging.DicomDiagnosticHandbook
import ru.medeng.dicomtool.data.logging.DicomLogExportUtils
import ru.medeng.dicomtool.data.logging.DicomOutcomeFilter
import ru.medeng.dicomtool.data.logging.DicomSessionGrouper
import ru.medeng.dicomtool.data.logging.DicomSessionSummary
import ru.medeng.dicomtool.data.logging.DicomTrafficFilter
import ru.medeng.dicomtool.data.logging.SessionOutcomeStatus
import ru.medeng.dicomtool.data.logging.TransferredFileItem
import ru.medeng.dicomtool.data.logging.matches
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.util.UUID

/**
 * Комплексные тесты Этапа 4.3.2: Новый диагностический технический журнал.
 */
class DicomJournalStage432Test {

    private fun makeEntry(
        category: DicomLogCategory,
        direction: DicomTrafficDirection = DicomTrafficDirection.OUTBOUND,
        associationId: String = "SCU-1",
        sessionUid: String = "SCU-R001-0001",
        title: String = "Event",
        summaryLines: List<String> = emptyList(),
        technicalDetails: Map<String, String> = emptyMap(),
        isError: Boolean = false,
        time: String = "12:00:00.000"
    ): DicomLogEntry = DicomLogEntry(
        id = UUID.randomUUID().toString(),
        timestampFormatted = time,
        category = category,
        direction = direction,
        associationId = associationId,
        title = title,
        summaryLines = summaryLines,
        technicalDetails = technicalDetails,
        isError = isError,
        sessionUid = sessionUid
    )

    @Test
    fun testSessionGroupingParallelSessions() {
        // Чередующиеся события двух параллельных сеансов SCU и SCP
        val events = listOf(
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-1", "SCU-R001-0001", "SCU Assoc RQ", time = "12:00:00.100"),
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.INBOUND, "SCP-1", "SCP-R001-0002", "SCP Assoc RQ", time = "12:00:00.150"),
            makeEntry(DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-1", "SCU-R001-0001", "SCU Send File", time = "12:00:00.200"),
            makeEntry(DicomLogCategory.C_ECHO_RQ, DicomTrafficDirection.INBOUND, "SCP-1", "SCP-R001-0002", "SCP Echo RQ", time = "12:00:00.250"),
            makeEntry(DicomLogCategory.C_STORE_RSP, DicomTrafficDirection.INBOUND, "SCU-1", "SCU-R001-0001", "SCU C-STORE-RSP: Success (0x0000)", time = "12:00:00.300"),
            makeEntry(DicomLogCategory.C_ECHO_RSP, DicomTrafficDirection.OUTBOUND, "SCP-1", "SCP-R001-0002", "SCP Echo RSP: Success (0x0000)", time = "12:00:00.350"),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.INBOUND, "SCU-1", "SCU-R001-0001", "SCU Release RP", time = "12:00:00.400"),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.OUTBOUND, "SCP-1", "SCP-R001-0002", "SCP Release RP", time = "12:00:00.450")
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals("Должно быть 2 независимых сгруппированных сеанса", 2, summaries.size)

        val scuSession = summaries.firstOrNull { it.sessionUid == "SCU-R001-0001" }
        val scpSession = summaries.firstOrNull { it.sessionUid == "SCP-R001-0002" }

        assertNotNull(scuSession)
        assertNotNull(scpSession)
        assertEquals("SCU сеанс должен содержать ровно 4 своих события", 4, scuSession!!.events.size)
        assertEquals("SCP сеанс должен содержать ровно 4 своих события", 4, scpSession!!.events.size)
        assertEquals(SessionOutcomeStatus.SUCCESS, scuSession.status)
        assertEquals(SessionOutcomeStatus.SUCCESS, scpSession.status)
    }

    @Test
    fun testSuccessfulCEchoSession() {
        val events = listOf(
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-1", "SCU-ECHO-1", "A-ASSOCIATE-RQ sent", time = "14:10:00.000"),
            makeEntry(DicomLogCategory.ASSOCIATE_AC, DicomTrafficDirection.INBOUND, "SCU-1", "SCU-ECHO-1", "A-ASSOCIATE-AC received", time = "14:10:00.050"),
            makeEntry(DicomLogCategory.C_ECHO_RQ, DicomTrafficDirection.OUTBOUND, "SCU-1", "SCU-ECHO-1", "C-ECHO-RQ sent", time = "14:10:00.100"),
            makeEntry(
                DicomLogCategory.C_ECHO_RSP, DicomTrafficDirection.INBOUND, "SCU-1", "SCU-ECHO-1",
                "C-ECHO-RSP received (Success: 0x0000)",
                technicalDetails = mapOf("Статус" to "0x0000 (Success)"),
                time = "14:10:00.150"
            ),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.INBOUND, "SCU-1", "SCU-ECHO-1", "A-RELEASE-RP received", time = "14:10:00.200")
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val s = summaries.first()
        assertEquals(SessionOutcomeStatus.SUCCESS, s.status)
        assertEquals("C-ECHO", s.primaryOperation)
        assertTrue(s.statusTitle.contains("Успешная проверка связи (C-ECHO)"))
        assertEquals("200 мс", s.durationFormatted)
    }

    @Test
    fun testSuccessfulCStoreSessionWith0x0000() {
        val events = listOf(
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-2", "SCU-STORE-1", "A-ASSOCIATE-RQ sent", time = "15:00:00.000"),
            makeEntry(DicomLogCategory.ASSOCIATE_AC, DicomTrafficDirection.INBOUND, "SCU-2", "SCU-STORE-1", "A-ASSOCIATE-AC received", time = "15:00:00.100"),
            makeEntry(
                DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-2", "SCU-STORE-1",
                "C-STORE-RQ (файл: chest_ct.dcm)",
                technicalDetails = mapOf("Файл" to "chest_ct.dcm", "SOP Instance UID" to "1.2.3.4.5"),
                time = "15:00:00.200"
            ),
            makeEntry(
                DicomLogCategory.C_STORE_RSP, DicomTrafficDirection.INBOUND, "SCU-2", "SCU-STORE-1",
                "C-STORE-RSP: Success (0x0000)",
                technicalDetails = mapOf("Статус" to "0x0000", "SOP Instance UID" to "1.2.3.4.5"),
                time = "15:00:14.500"
            ),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.INBOUND, "SCU-2", "SCU-STORE-1", "A-RELEASE-RP received", time = "15:00:14.700")
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val s = summaries.first()
        assertEquals(SessionOutcomeStatus.SUCCESS, s.status)
        assertEquals("Успешная отправка исследования", s.statusTitle)
        assertEquals(1, s.filesCount)
        assertEquals("chest_ct.dcm", s.filesTransferred.first().fileName)
        assertTrue(s.filesTransferred.first().isSuccess)
        assertEquals("14,7 с", s.durationFormatted)
    }

    @Test
    fun testMissingCStoreRspNotClassifiedAsPacsRejection() {
        // Важное требование ТЗ: если сервер не прислал ответ, не делать вывод, что PACS отклонил файл
        val events = listOf(
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-4", "SCU-TIMEOUT-1", "A-ASSOCIATE-RQ sent", time = "16:00:00.000"),
            makeEntry(DicomLogCategory.ASSOCIATE_AC, DicomTrafficDirection.INBOUND, "SCU-4", "SCU-TIMEOUT-1", "A-ASSOCIATE-AC received", time = "16:00:00.050"),
            makeEntry(
                DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-4", "SCU-TIMEOUT-1",
                "C-STORE-RQ: отправка файла 00038908.dic",
                technicalDetails = mapOf("Файл" to "00038908.dic", "SOP Instance UID" to "1.2.840.111"),
                time = "16:00:00.100"
            ),
            makeEntry(
                DicomLogCategory.TCP_DISCONNECT, DicomTrafficDirection.INBOUND, "SCU-4", "SCU-TIMEOUT-1",
                "Таймаут или отсутствие ответа C-STORE-RSP от PACS: сервер не прислал подтверждение сохранения",
                summaryLines = listOf("Соединение закрыто без получения C-STORE-RSP."),
                isError = true,
                time = "16:00:30.000"
            )
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val s = summaries.first()

        assertEquals(SessionOutcomeStatus.FAILURE, s.status)
        assertEquals("Не получено подтверждение отправки", s.statusTitle)
        assertEquals("C-STORE-RSP не получен от PACS", s.statusSubtitle)
        assertFalse("Не должно утверждаться, что PACS отклонил файл", s.statusTitle.contains("отклонил"))
        assertEquals("00038908.dic", s.filesTransferred.first().fileName)
        assertEquals("C-STORE-RSP не получен", s.filesTransferred.first().statusText)

        // Проверка рекомендации из справочника
        assertTrue("Должна быть рекомендация по таймауту C-STORE-RSP", s.diagnosticRecommendations.any { it.technicalName.contains("C-STORE-RSP Timeout") })
    }

    @Test
    fun testAssociationRejectedDiagnosis() {
        val events = listOf(
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-5", "SCU-RJ-1", "A-ASSOCIATE-RQ sent", time = "17:00:00.000"),
            makeEntry(
                DicomLogCategory.ASSOCIATE_RJ, DicomTrafficDirection.INBOUND, "SCU-5", "SCU-RJ-1",
                "A-ASSOCIATE-RJ received (Called AE Title Not Recognized)",
                summaryLines = listOf("Calling AE: ANDROID_SCU | Called AE: WRONG_PACS", "Reason: Called AE Title Not Recognized"),
                technicalDetails = mapOf("Причина отказа" to "Called AE Title Not Recognized", "RJ Reason" to "7"),
                isError = true,
                time = "17:00:00.040"
            )
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val s = summaries.first()

        assertEquals(SessionOutcomeStatus.FAILURE, s.status)
        assertEquals("Ассоциация отклонена (A-ASSOCIATE-RJ)", s.statusTitle)
        assertTrue(s.diagnosticRecommendations.any { it.technicalName.contains("A-ASSOCIATE-RJ") })
        val rec = s.diagnosticRecommendations.first { it.technicalName.contains("A-ASSOCIATE-RJ") }
        assertTrue(rec.engineerChecklistRu.any { it.contains("Called AE Title") })
    }

    @Test
    fun testMixedBatchSendPartialSuccess() {
        // 2 файла успешно (0x0000), 1 файл ошибка (отказ Presentation Context)
        val events = listOf(
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-6", "SCU-BATCH-1", "A-ASSOCIATE-RQ sent", time = "18:00:00.000"),
            makeEntry(DicomLogCategory.ASSOCIATE_AC, DicomTrafficDirection.INBOUND, "SCU-6", "SCU-BATCH-1", "A-ASSOCIATE-AC received", time = "18:00:00.050"),
            // Файл 1
            makeEntry(DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-6", "SCU-BATCH-1", "C-STORE-RQ (файл: f1.dcm)", technicalDetails = mapOf("Файл" to "f1.dcm", "SOP Instance UID" to "uid1"), time = "18:00:00.100"),
            makeEntry(DicomLogCategory.C_STORE_RSP, DicomTrafficDirection.INBOUND, "SCU-6", "SCU-BATCH-1", "C-STORE-RSP: Success (0x0000)", technicalDetails = mapOf("Статус" to "0x0000", "SOP Instance UID" to "uid1"), time = "18:00:00.200"),
            // Файл 2
            makeEntry(DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-6", "SCU-BATCH-1", "C-STORE-RQ: отклонен контекст представления для файла", technicalDetails = mapOf("Файл" to "f2.dcm", "SOP Instance UID" to "uid2"), isError = true, time = "18:00:00.300"),
            // Файл 3
            makeEntry(DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-6", "SCU-BATCH-1", "C-STORE-RQ (файл: f3.dcm)", technicalDetails = mapOf("Файл" to "f3.dcm", "SOP Instance UID" to "uid3"), time = "18:00:00.400"),
            makeEntry(DicomLogCategory.C_STORE_RSP, DicomTrafficDirection.INBOUND, "SCU-6", "SCU-BATCH-1", "C-STORE-RSP: Success (0x0000)", technicalDetails = mapOf("Статус" to "0x0000", "SOP Instance UID" to "uid3"), time = "18:00:00.500"),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.INBOUND, "SCU-6", "SCU-BATCH-1", "A-RELEASE-RP received", time = "18:00:00.600")
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val s = summaries.first()

        assertEquals(SessionOutcomeStatus.WARNING, s.status)
        assertEquals("Частичная отправка файлов", s.statusTitle)
        assertEquals("Успешно: 2 из 3, ошибок: 1", s.statusSubtitle)
        assertEquals(3, s.filesTransferred.size)
        assertEquals(2, s.filesTransferred.count { it.isSuccess })
        assertEquals(1, s.filesTransferred.count { it.isError })
    }

    @Test
    fun testLegacyLogsWithoutSessionUidNoFalseMerge() {
        // Старые записи без sessionUid (пустая строка): два разных сеанса SCU-1, разделенные временем
        val events = listOf(
            // Сеанс 1
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-1", "", "A-ASSOCIATE-RQ sent", time = "10:00:00.000"),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.INBOUND, "SCU-1", "", "A-RELEASE-RP received", time = "10:00:01.000"),
            // Сеанс 2 (спустя 2 часа после перезапуска с тем же associationId SCU-1)
            makeEntry(DicomLogCategory.ASSOCIATE_RQ, DicomTrafficDirection.OUTBOUND, "SCU-1", "", "A-ASSOCIATE-RQ sent", time = "12:00:00.000"),
            makeEntry(DicomLogCategory.RELEASE_RP, DicomTrafficDirection.INBOUND, "SCU-1", "", "A-RELEASE-RP received", time = "12:00:01.000")
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals("Старые сеансы не должны ошибочно сливаться в один", 2, summaries.size)
        assertTrue(summaries[0].sessionKey.startsWith("LEGACY-"))
        assertTrue(summaries[1].sessionKey.startsWith("LEGACY-"))
        assertFalse(summaries[0].sessionKey == summaries[1].sessionKey)
    }

    @Test
    fun testDiagnosticHandbookCoverage() {
        // 1. Connection refused
        val e1 = makeEntry(DicomLogCategory.NETWORK_ERROR, title = "Connection refused (ECONNREFUSED)", isError = true)
        val r1 = DicomDiagnosticHandbook.analyzeEntry(e1)
        assertNotNull(r1)
        assertTrue(r1!!.technicalName.contains("ECONNREFUSED"))
        assertTrue(r1.engineerChecklistRu.isNotEmpty())

        // 2. Socket read timeout
        val e2 = makeEntry(DicomLogCategory.NETWORK_ERROR, title = "Socket read timed out", isError = true)
        val r2 = DicomDiagnosticHandbook.analyzeEntry(e2)
        assertNotNull(r2)
        assertTrue(r2!!.technicalName.contains("Read Timeout"))

        // 3. Status 0xA700 (Out of Resources)
        val e3 = makeEntry(DicomLogCategory.C_STORE_RSP, title = "C-STORE-RSP: Failure (0xA700)", technicalDetails = mapOf("Статус" to "0xA700"), isError = true)
        val r3 = DicomDiagnosticHandbook.analyzeEntry(e3)
        assertNotNull(r3)
        assertTrue(r3!!.technicalName.contains("Out of Resources"))

        // 4. Status 0xC001 (Processing Failure)
        val e4 = makeEntry(DicomLogCategory.C_STORE_RSP, title = "C-STORE-RSP: Failure (0xC001)", technicalDetails = mapOf("Статус" to "0xC001"), isError = true)
        val r4 = DicomDiagnosticHandbook.analyzeEntry(e4)
        assertNotNull(r4)
        assertTrue(r4!!.technicalName.contains("Processing Failure"))

        // 5. Unknown error (без выдуманной расшифровки)
        val e5 = makeEntry(DicomLogCategory.SERVER_LIFECYCLE, title = "Custom system warning without code", isError = true)
        val r5 = DicomDiagnosticHandbook.analyzeEntry(e5)
        assertEquals("Для неизвестных ошибок не должна генерироваться ложная расшифровка", null, r5)
    }

    @Test
    fun testFilterAndSearchFunctionality() {
        val s1 = DicomSessionSummary(
            sessionKey = "S1",
            sessionUid = "SCU-R1-0001",
            associationId = "SCU-1",
            direction = DicomTrafficDirection.OUTBOUND,
            role = "SCU",
            primaryOperation = "C-STORE",
            status = SessionOutcomeStatus.SUCCESS,
            statusTitle = "Успешная отправка исследования",
            statusSubtitle = "1 файл",
            remoteAeTitle = "ORTHANC",
            calledAeTitle = "ORTHANC",
            callingAeTitle = "ANDROID_SCU",
            remoteHostAndPort = "192.168.1.100:4242",
            filesCount = 1,
            filesTransferred = listOf(TransferredFileItem("ct_chest.dcm", statusText = "Success (0x0000)", isSuccess = true)),
            durationFormatted = "1,2 с",
            startTimeFormatted = "12:00:00.000",
            endTimeFormatted = "12:00:01.200",
            associationParameters = emptyMap(),
            events = emptyList(),
            diagnosticRecommendations = emptyList()
        )

        val s2 = DicomSessionSummary(
            sessionKey = "S2",
            sessionUid = "SCP-R1-0002",
            associationId = "SCP-1",
            direction = DicomTrafficDirection.INBOUND,
            role = "SCP",
            primaryOperation = "C-STORE",
            status = SessionOutcomeStatus.FAILURE,
            statusTitle = "Ошибка приёма",
            statusSubtitle = "Out of Resources (0xA700)",
            remoteAeTitle = "CT_SCANNER",
            calledAeTitle = "ANDROID_SCP",
            callingAeTitle = "CT_SCANNER",
            remoteHostAndPort = "192.168.1.200:51000",
            filesCount = 1,
            filesTransferred = listOf(TransferredFileItem("brain_mr.dcm", statusText = "0xA700", isSuccess = false, isError = true)),
            durationFormatted = "0,5 с",
            startTimeFormatted = "12:05:00.000",
            endTimeFormatted = "12:05:00.500",
            associationParameters = emptyMap(),
            events = emptyList(),
            diagnosticRecommendations = emptyList()
        )

        // 1. Фильтр направления
        assertTrue(s1.matches(DicomTrafficFilter.SCU_OUT, DicomOutcomeFilter.ALL, ""))
        assertFalse(s1.matches(DicomTrafficFilter.SCP_IN, DicomOutcomeFilter.ALL, ""))
        assertTrue(s2.matches(DicomTrafficFilter.SCP_IN, DicomOutcomeFilter.ALL, ""))

        // 2. Фильтр результата
        assertTrue(s1.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.SUCCESS, ""))
        assertFalse(s1.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ERRORS, ""))
        assertTrue(s2.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ERRORS, ""))

        // 3. Поисковый запрос по AE Title
        assertTrue(s1.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "orthanc"))
        assertFalse(s2.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "orthanc"))

        // 4. Поиск по имени файла
        assertTrue(s1.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "ct_chest"))
        assertTrue(s2.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "brain_mr"))

        // 5. Поиск по IP
        assertTrue(s1.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "192.168.1.100"))
        assertTrue(s2.matches(DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "192.168.1.200"))
    }

    @Test
    fun testClipboardAndSanitizedExportFormatting() {
        val entry = makeEntry(
            category = DicomLogCategory.C_STORE_RQ,
            title = "C-STORE-RQ: PatientName=Иванов И.И. PatientID=12345",
            summaryLines = listOf("Пациент: Иванов И.И., ДР: 19800512, Файл: 001.dcm"),
            technicalDetails = mapOf("PatientName" to "Иванов И.И.", "PatientID" to "12345", "BirthDate" to "19800512")
        )

        // Полный экспорт (без санитайзера)
        val fullText = DicomLogExportUtils.formatSingleEvent(entry, sanitizePhi = false)
        assertTrue("Полный экспорт содержит исходные данные", fullText.contains("Иванов И.И."))
        assertTrue("Полный экспорт содержит PatientID", fullText.contains("12345"))
        assertTrue("Полный экспорт содержит дату", fullText.contains("19800512"))

        // Обезличенный экспорт (с санитайзером)
        val sanitizedText = DicomLogExportUtils.formatSingleEvent(entry, sanitizePhi = true)
        assertFalse("Обезличенный экспорт НЕ должен содержать ФИО", sanitizedText.contains("Иванов И.И."))
        assertFalse("Обезличенный экспорт НЕ должен содержать 19800512", sanitizedText.contains("19800512"))
        assertTrue("Должна присутствовать маска анонимизации", sanitizedText.contains("ANONYMIZED") || sanitizedText.contains("********"))
    }

    @Test
    fun testPerformanceOnLargeLog() {
        // Создаем 2000 записей (типовой лимит журнала)
        val largeList = mutableListOf<DicomLogEntry>()
        for (i in 1..2000) {
            val sessionIdx = (i - 1) / 5 + 1
            largeList.add(
                makeEntry(
                    category = if (i % 5 == 0) DicomLogCategory.RELEASE_RP else DicomLogCategory.C_STORE_RQ,
                    associationId = "SCU-$sessionIdx",
                    sessionUid = "SCU-RPERF-$sessionIdx",
                    title = "Event #$i in session $sessionIdx",
                    time = "12:00:${String.format("%02d", (i / 60) % 60)}.${String.format("%03d", i % 1000)}"
                )
            )
        }

        val startTime = System.currentTimeMillis()
        val summaries = DicomSessionGrouper.groupEvents(largeList)
        val filterResult = summaries.filter { it.matches(DicomTrafficFilter.SCU_OUT, DicomOutcomeFilter.ALL, "session 10") }
        val elapsedMs = System.currentTimeMillis() - startTime

        assertEquals("2000 событий по 5 в сеансе должны сгруппироваться в 400 сеансов", 400, summaries.size)
        assertTrue("Группировка и фильтрация 2000 событий должны занимать менее 200 мс (заняло $elapsedMs мс)", elapsedMs < 200)
    }
}
