package ru.medeng.dicomtool.data.logging

import org.dcm4che3.data.UID
import ru.medeng.dicomtool.domain.model.DicomLogEntry

/** Closed output schema. No raw titles, names, paths, instance UIDs, AE, timestamps or exception text. */
object SafeTechnicalSummary {
    const val NOTICE = "Безопасная техническая сводка: только разрешённые поля. Это не гарантия юридической анонимизации."
    private val syntaxes = setOf(UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian, UID.ExplicitVRBigEndian,
        UID.DeflatedExplicitVRLittleEndian, UID.JPEGBaseline8Bit, UID.JPEGExtended12Bit, UID.JPEGLossless,
        UID.JPEGLosslessSV1, UID.JPEGLSLossless, UID.JPEGLSNearLossless, UID.JPEG2000Lossless, UID.JPEG2000, UID.RLELossless)
    private val classes = setOf(UID.Verification, UID.SecondaryCaptureImageStorage, UID.CTImageStorage,
        UID.EnhancedCTImageStorage, UID.MRImageStorage, UID.EnhancedMRImageStorage, UID.UltrasoundImageStorage,
        UID.ComputedRadiographyImageStorage, UID.DigitalXRayImageStorageForPresentation,
        UID.DigitalXRayImageStorageForProcessing, UID.NuclearMedicineImageStorage, UID.PositronEmissionTomographyImageStorage)
    private val outcomes = setOf("SUCCESS", "WARNING", "FAILED", "SKIPPED", "CANCELLED", "UNCONFIRMED")
    private fun unsigned(value: String, max: Long): String? = value.takeIf {
        it.matches(Regex("[0-9]{1,18}")) && (it.toLongOrNull()?.let { n -> n in 0..max } == true)
    }?.toLong()?.toString()
    private fun status(value: String): String? = when {
        value == "0x0000 (Success)" -> "0x0000"
        value == "0x0110 (ProcessingFailure)" -> "0x0110"
        value.matches(Regex("0x[0-9a-fA-F]{4}")) -> "0x" + value.substring(2).uppercase()
        value.matches(Regex("[0-9a-fA-F]{4}[Hh]")) -> "0x" + value.take(4).uppercase()
        else -> null
    }
    fun event(entry: DicomLogEntry): String = buildString {
        appendLine("Категория: ${entry.category.name}; направление: ${entry.direction.name}; ошибка: ${entry.isError}")
        for ((key, value) in entry.technicalDetails) {
            val field = when (key) {
                "Status", "Статус", "Код ответа", "DICOM Status" -> status(value)?.let { "Status: $it" }
                "Message ID", "Message ID Being Responded To", "Message ID Responded To" -> unsigned(value, 65535)?.let { "$key: $it" }
                "Presentation Context ID" -> unsigned(value, 255)?.takeIf { it.toInt() % 2 == 1 }?.let { "Presentation Context ID: $it" }
                "Max PDU Length", "Max PDU Length (AC)", "Max PDU Length (RQ)" -> unsigned(value, 4_294_967_295)?.let { "$key: $it" }
                "Transfer Syntax UID", "Accepted Transfer Syntax" -> value.takeIf { it in syntaxes }?.let { "Transfer Syntax UID: $it" }
                "SOP Class UID", "Affected SOP Class UID", "Abstract Syntax UID" -> value.takeIf { it in classes }?.let { "SOP Class UID: $it" }
                "SCU File Result" -> value.takeIf { it in outcomes }?.let { "Результат файла: $it" }
                "Response Valid", "Rotation Pending", "SCU Cancellation", "SCU Association Complete", "SCU Batch Complete", "SCU Closure Warning" ->
                    value.takeIf { it == "true" || it == "false" }?.let { "$key: $it" }
                "Dataset Bytes", "Expected File Outcomes", "Dropped Normal", "Dropped Critical", "Write Unconfirmed", "Critical Write Unconfirmed", "Rejected After Stop", "Rejected Critical After Stop", "Read Failures", "Control Rejected", "Control Timeouts", "Control Failures" ->
                    unsigned(value, Long.MAX_VALUE)?.let { "$key: $it" }
                "Роль" -> value.takeIf { it in setOf("SCU", "SCP", "SYS") }?.let { "Роль: $it" }
                else -> null
            }
            if (field != null) appendLine(field)
        }
    }
    fun session(session: DicomSessionSummary): String = buildString {
        appendLine("Итог выбранного сеанса: ${session.status.name}")
        appendLine("Событий в выборке: ${session.events.size}")
        session.events.forEachIndexed { index, entry -> appendLine("Событие ${index + 1}"); append(event(entry)) }
    }
}
