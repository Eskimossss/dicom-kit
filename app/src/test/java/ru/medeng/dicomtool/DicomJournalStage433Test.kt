package ru.medeng.dicomtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.medeng.dicomtool.data.logging.DicomSessionGrouper
import ru.medeng.dicomtool.data.logging.TransferredFileItem
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.util.UUID

/**
 * Модульные тесты для Этапа 4.3.3:
 * 1. Форматирование ФИО пациентов (DICOM Person Name PN).
 * 2. Отображение удалённого узла (ESKIMOS · 10.0.2.2:4242 и ESKIMOS).
 * 3. Отображение реальных файлов (displayTitle: b9b · DX · 18,00 МБ).
 * 4. Корректное распознавание параметров сеанса без пустых скобок `(-)`.
 */
class DicomJournalStage433Test {

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
    fun testFormatDicomPersonName_cyrillicStandardPn() {
        // Формат "Смирнова^А^В" -> "Смирнова А. В."
        val formatted1 = DicomCharsetDecoder.formatDicomPersonName("Смирнова^А^В")
        assertEquals("Смирнова А. В.", formatted1)

        // Формат "Иванов^Иван^Иванович" -> "Иванов Иван Иванович"
        val formatted2 = DicomCharsetDecoder.formatDicomPersonName("Иванов^Иван^Иванович")
        assertEquals("Иванов Иван Иванович", formatted2)

        // Формат "Петров^П" -> "Петров П."
        val formatted3 = DicomCharsetDecoder.formatDicomPersonName("Петров^П")
        assertEquals("Петров П.", formatted3)

        // Одиночное слово без кареток
        val formatted4 = DicomCharsetDecoder.formatDicomPersonName("Смирнова")
        assertEquals("Смирнова", formatted4)

        // Пустые или фиктивные значения
        assertEquals("", DicomCharsetDecoder.formatDicomPersonName(""))
        assertEquals("", DicomCharsetDecoder.formatDicomPersonName("   "))
        assertEquals("", DicomCharsetDecoder.formatDicomPersonName("N/A"))
        assertEquals("", DicomCharsetDecoder.formatDicomPersonName("ANONYMOUS"))
        assertEquals("", DicomCharsetDecoder.formatDicomPersonName("-"))
    }

    @Test
    fun testFormatDicomPersonName_latinPn() {
        // Формат "Smith^John" -> "Smith John"
        val formatted1 = DicomCharsetDecoder.formatDicomPersonName("Smith^John")
        assertEquals("Smith John", formatted1)

        // Формат "Doe^J^M" -> "Doe J. M."
        val formatted2 = DicomCharsetDecoder.formatDicomPersonName("Doe^J^M")
        assertEquals("Doe J. M.", formatted2)
    }

    @Test
    fun testTransferredFileItem_displayTitleFormatting() {
        val itemComplete = TransferredFileItem(
            fileName = "b9b.dcm",
            sopInstanceUid = "1.2.3",
            isSuccess = true,
            statusText = "Успешно",
            modality = "DX",
            fileSizeFormatted = "18,00 МБ"
        )
        assertEquals("b9b.dcm · DX · 18,00 МБ", itemComplete.displayTitle)

        val itemNoModality = TransferredFileItem(
            fileName = "test.dcm",
            sopInstanceUid = "1.2.3",
            isSuccess = true,
            statusText = "Успешно",
            fileSizeFormatted = "512 КБ"
        )
        assertEquals("test.dcm · 512 КБ", itemNoModality.displayTitle)

        val itemUnknownName = TransferredFileItem(
            fileName = "",
            sopInstanceUid = "1.2.3",
            isSuccess = true,
            statusText = "Успешно"
        )
        assertEquals("Имя файла неизвестно", itemUnknownName.displayTitle)
    }

    @Test
    fun testSessionSummaryBuilder_buildsRemoteNodeAndFiles() {
        val events = listOf(
            makeEntry(
                category = DicomLogCategory.TCP_CONNECT,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = "SCU-1",
                sessionUid = "SESSION-1",
                title = "[SCU] Установка TCP-соединения с 10.0.2.2:4242",
                technicalDetails = linkedMapOf(
                    "Remote Socket" to "10.0.2.2:4242",
                    "Calling AE Title" to "DICOMTOOL",
                    "Called AE Title" to "ESKIMOS"
                )
            ),
            makeEntry(
                category = DicomLogCategory.C_STORE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = "SCU-1",
                sessionUid = "SESSION-1",
                title = "[SCU] Отправка C-STORE-RQ (файл 1/1: patient_study.dcm)",
                technicalDetails = linkedMapOf(
                    "Имя файла" to "patient_study.dcm",
                    "Модальность" to "CT",
                    "Размер файла" to "2,40 МБ",
                    "Affected SOP Instance UID" to "1.2.840.10008.5.1.4.1.1.2.12345"
                )
            ),
            makeEntry(
                category = DicomLogCategory.C_STORE_RSP,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "SCU-1",
                sessionUid = "SESSION-1",
                title = "[SCU] Получен C-STORE-RSP (0000H: Success)",
                technicalDetails = linkedMapOf(
                    "Status" to "0000H (Success)",
                    "Affected SOP Instance UID" to "1.2.840.10008.5.1.4.1.1.2.12345"
                )
            ),
            makeEntry(
                category = DicomLogCategory.RELEASE_RP,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "SCU-1",
                sessionUid = "SESSION-1",
                title = "[SCU] Получен A-RELEASE-RP: ассоциация успешно закрыта"
            )
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val summary = summaries.first()

        assertEquals("ESKIMOS · 10.0.2.2:4242", summary.formattedRemoteNode)
        assertFalse(summary.formattedRemoteNode.contains("(-)"))
        assertEquals("C-STORE", summary.primaryOperation)
        assertEquals(1, summary.filesCount)
        assertEquals(1, summary.filesTransferred.size)

        val fileItem = summary.filesTransferred.first()
        assertEquals("patient_study.dcm · CT · 2,40 МБ", fileItem.displayTitle)
        assertEquals("Успешно (0x0000)", fileItem.statusText)
        assertTrue(fileItem.isSuccess)
    }

    @Test
    fun testSessionSummaryBuilder_remoteNodeWithoutSocket() {
        val events = listOf(
            makeEntry(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = "SCU-2",
                sessionUid = "SESSION-2",
                title = "[SCU] Отправка A-ASSOCIATE-RQ (DICOMTOOL → ESKIMOS)",
                technicalDetails = linkedMapOf(
                    "Calling AE Title" to "DICOMTOOL",
                    "Called AE Title" to "ESKIMOS"
                )
            ),
            makeEntry(
                category = DicomLogCategory.RELEASE_RP,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "SCU-2",
                sessionUid = "SESSION-2",
                title = "[SCU] Получен A-RELEASE-RP: ассоциация закрыта"
            )
        )

        val summaries = DicomSessionGrouper.groupEvents(events)
        assertEquals(1, summaries.size)
        val summary = summaries.first()

        // Проверяем, что нет "(-)" или "-" в имени узла
        assertEquals("ESKIMOS", summary.formattedRemoteNode)
        assertFalse(summary.formattedRemoteNode.contains("(-)"))
        assertFalse(summary.formattedRemoteNode.contains("-"))
    }
    @Test
    fun testJournalTrafficFilterLabels() {
        assertEquals("Все", ru.medeng.dicomtool.data.logging.DicomTrafficFilter.ALL.labelRu)
        assertEquals("SCP IN", ru.medeng.dicomtool.data.logging.DicomTrafficFilter.SCP_IN.labelRu)
        assertEquals("SCU OUT", ru.medeng.dicomtool.data.logging.DicomTrafficFilter.SCU_OUT.labelRu)
    }
}
