package ru.medeng.dicomtool.data.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Утилиты форматирования для буфера обмена и экспорта технического журнала DICOM.
 */
object DicomLogExportUtils {

    /**
     * Форматирование одного события для буфера обмена.
     */
    fun formatSingleEvent(entry: DicomLogEntry, sanitizePhi: Boolean = true): String {
        if (sanitizePhi) return SafeTechnicalSummary.NOTICE + "\n" + SafeTechnicalSummary.event(entry)
        return buildString {
        appendLine("ПОЛНЫЙ ТЕКСТ: может содержать персональные данные.")
        appendLine("--- DICOM СОБЫТИЕ ---")
        appendLine("Время: ${entry.timestampFormatted}")
        appendLine("Направление: ${entry.direction.code}")
        appendLine("Сеанс: ${entry.associationId}${if (entry.sessionUid.isNotBlank()) " [${entry.sessionUid}]" else ""}")
        appendLine("Категория: ${entry.category}")
        appendLine("Заголовок: ${entry.title}")
        if (entry.summaryLines.isNotEmpty()) {
            appendLine("Сводка:")
            entry.summaryLines.forEach { line ->
                appendLine("  • ${line}")
            }
        }
        if (entry.technicalDetails.isNotEmpty()) {
            appendLine("Технические параметры:")
            entry.technicalDetails.forEach { (k, v) ->
                appendLine("  $k: ${v}")
            }
        }
        val rec = DicomDiagnosticHandbook.analyzeEntry(entry)
        if (rec != null) {
            appendLine("\nДИАГНОСТИКА:")
            appendLine("  ${rec.technicalName}")
            appendLine("  Пояснение: ${rec.explanationRu}")
            if (rec.possibleCausesRu.isNotEmpty()) {
                appendLine("  Возможные причины:")
                rec.possibleCausesRu.forEach { appendLine("    - $it") }
            }
            if (rec.engineerChecklistRu.isNotEmpty()) {
                appendLine("  Что проверить инженеру:")
                rec.engineerChecklistRu.forEach { appendLine("    - $it") }
            }
        }
    }

    }

    /**
     * Форматирование целого раскрытого сеанса для буфера обмена.
     */
    fun formatSession(session: DicomSessionSummary, sanitizePhi: Boolean = true): String {
        if (sanitizePhi) return SafeTechnicalSummary.NOTICE + "\n" + SafeTechnicalSummary.session(session)
        return buildString {
        appendLine("ПОЛНЫЙ ТЕКСТ: может содержать персональные данные.")
        appendLine("==================================================")
        appendLine("DICOM СЕАНС: ${session.role} ${session.direction.code} · ${session.associationId}")
        appendLine("ID сеанса (UID): ${session.sessionUid}")
        appendLine("Итог: ${session.status.labelRu} — ${session.statusTitle}")
        if (session.statusSubtitle.isNotBlank()) {
            appendLine("Детализация: ${session.statusSubtitle}")
        }
        appendLine("Удалённый узел: ${session.formattedRemoteNode}")
        appendLine("Calling AE: ${session.callingAeTitle}  ->  Called AE: ${session.calledAeTitle}")
        appendLine("Операция: ${session.primaryOperation}")
        appendLine("Время: ${session.startTimeFormatted} – ${session.endTimeFormatted} (продолжительность: ${session.durationFormatted})")
        appendLine("Количество файлов: ${session.filesCount}")
        appendLine("==================================================")

        if (session.filesTransferred.isNotEmpty()) {
            appendLine("\nПЕРЕДАННЫЕ / ПРИНЯТЫЕ ФАЙЛЫ:")
            session.filesTransferred.forEachIndexed { idx, f ->
                appendLine("  ${idx + 1}. ${f.displayTitle} — ${f.statusText}")
                if (f.sopInstanceUid.isNotBlank()) appendLine("     SOP UID: ${f.sopInstanceUid}")
            }
        }

        if (session.associationParameters.isNotEmpty()) {
            appendLine("\nПАРАМЕТРЫ АССОЦИАЦИИ:")
            session.associationParameters.forEach { (k, v) ->
                appendLine("  $k: $v")
            }
        }

        if (session.diagnosticRecommendations.isNotEmpty()) {
            appendLine("\nДИАГНОСТИЧЕСКИЕ РЕКОМЕНДАЦИИ:")
            session.diagnosticRecommendations.forEach { rec ->
                appendLine("  • ${rec.technicalName}")
                appendLine("    Пояснение: ${rec.explanationRu}")
                if (rec.possibleCausesRu.isNotEmpty()) {
                    appendLine("    Возможные причины:")
                    rec.possibleCausesRu.forEach { appendLine("      - $it") }
                }
                if (rec.engineerChecklistRu.isNotEmpty()) {
                    appendLine("    Что проверить инженеру:")
                    rec.engineerChecklistRu.forEach { appendLine("      - $it") }
                }
            }
        }

        appendLine("\nХРОНОЛОГИЯ СОБЫТИЙ СЕАНСА (${session.events.size}):")
        session.events.forEachIndexed { i, e ->
            val errMarker = if (e.isError) "[ERROR] " else ""
            appendLine("  [${i + 1}] ${e.timestampFormatted} ${e.direction.code} ${e.category} | $errMarker${e.title}")
            e.summaryLines.forEach { line ->
                appendLine("        ${line}")
            }
            if (e.technicalDetails.isNotEmpty()) {
                e.technicalDetails.forEach { (k, v) ->
                    appendLine("        $k: ${v}")
                }
            }
        }
    }

    }

    /**
     * Форматирование полного журнала (всех отфильтрованных сеансов/событий) для экспорта в файл.
     */
    fun formatFullExport(
        sessions: List<DicomSessionSummary>,
        allFilteredEntries: List<DicomLogEntry>,
        isBriefMode: Boolean,
        sanitizePhi: Boolean
    ): String = buildString {
        val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("==================================================")
        appendLine("DICOM Kit — Экспорт технического журнала")
        appendLine("Дата выгрузки: $nowFormatted")
        appendLine("Режим: ${if (isBriefMode) "Краткий (по сеансам)" else "Подробный (по событиям)"}")
        appendLine("Конфиденциальность: ${if (sanitizePhi) "Безопасная техническая сводка" else "Полный технический журнал: может содержать персональные данные"}")
        if (sanitizePhi) {
            appendLine(SafeTechnicalSummary.NOTICE)
        }
        appendLine("Экспортируется выбранный оперативный список; он может не включать всю дисковую историю и потерянные события.")
        appendLine("Всего сеансов: ${sessions.size}, всего событий: ${allFilteredEntries.size}")
        appendLine("==================================================\n")

        if (isBriefMode) {
            sessions.forEachIndexed { index, session ->
                if (sanitizePhi) appendLine("Сеанс экспорта ${index + 1}")
                appendLine(formatSession(session, sanitizePhi))
                appendLine()
            }
        } else {
            allFilteredEntries.forEach { entry ->
                appendLine(formatSingleEvent(entry, sanitizePhi))
                appendLine()
            }
        }
    }

    /**
     * Создание временного текстового файла журнала и отправка через Android Share Sheet.
     */
    fun shareExportFile(
        context: Context,
        content: String,
        sanitizePhi: Boolean
    ) {
        sharePreparedFile(context, prepareExportFile(context, content, sanitizePhi), sanitizePhi)
    }

    fun prepareExportFile(context: Context, content: String, sanitizePhi: Boolean): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (sanitizePhi) "technical-summary" else "full"
        val fileName = "dicom_kit_log_${timeStamp}_${java.util.UUID.randomUUID()}_$suffix.txt"

        val exportDir = File(context.cacheDir, "log_exports")
        exportDir.mkdirs()
        val file = File(exportDir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun sharePreparedFile(context: Context, file: File, sanitizePhi: Boolean) {
        val suffix = if (sanitizePhi) "technical-summary" else "full"

        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "DICOM Kit Technical Log ($suffix)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "Экспорт журнала DICOM")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

}
