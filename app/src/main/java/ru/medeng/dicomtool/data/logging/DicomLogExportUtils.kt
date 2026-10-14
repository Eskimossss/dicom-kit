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

    private val PHI_TAGS_REGEX = Regex(
        """(?iu)(patient\s*name|patientid|patient\s*id|birth\s*date|пациент|фио|др|дата\s*рождения|\(0010,0010\)|\(0010,0020\)|\(0010,0030\))[:=]\s*([^,\n\r;\t]+)"""
    )
    private val DATE_PATTERN = Regex("""\b(19\d{2}|20\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\b""")
    private val DOT_DATE_PATTERN = Regex("""\b(0[1-9]|[12]\d|3[01])\.(0[1-9]|1[0-2])\.(19\d{2}|20\d{2})\b""")

    /**
     * Форматирование одного события для буфера обмена.
     */
    fun formatSingleEvent(entry: DicomLogEntry, sanitizePhi: Boolean = false): String = buildString {
        appendLine("--- DICOM СОБЫТИЕ ---")
        appendLine("Время: ${entry.timestampFormatted}")
        appendLine("Направление: ${entry.direction.code}")
        appendLine("Сеанс: ${entry.associationId}${if (entry.sessionUid.isNotBlank()) " [${entry.sessionUid}]" else ""}")
        appendLine("Категория: ${entry.category}")
        appendLine("Заголовок: ${maybeSanitize(entry.title, sanitizePhi)}")
        if (entry.summaryLines.isNotEmpty()) {
            appendLine("Сводка:")
            entry.summaryLines.forEach { line ->
                appendLine("  • ${maybeSanitize(line, sanitizePhi)}")
            }
        }
        if (entry.technicalDetails.isNotEmpty()) {
            appendLine("Технические параметры:")
            entry.technicalDetails.forEach { (k, v) ->
                appendLine("  $k: ${maybeSanitizeDetail(k, v, sanitizePhi)}")
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

    /**
     * Форматирование целого раскрытого сеанса для буфера обмена.
     */
    fun formatSession(session: DicomSessionSummary, sanitizePhi: Boolean = false): String = buildString {
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
            appendLine("  [${i + 1}] ${e.timestampFormatted} ${e.direction.code} ${e.category} | $errMarker${maybeSanitize(e.title, sanitizePhi)}")
            e.summaryLines.forEach { line ->
                appendLine("        ${maybeSanitize(line, sanitizePhi)}")
            }
            if (e.technicalDetails.isNotEmpty()) {
                e.technicalDetails.forEach { (k, v) ->
                    appendLine("        $k: ${maybeSanitizeDetail(k, v, sanitizePhi)}")
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
        appendLine("Конфиденциальность: ${if (sanitizePhi) "Обезличенный (без ПДн)" else "Полный технический"}")
        if (sanitizePhi) {
            appendLine("ВНИМАНИЕ: Применено эвристическое маскирование персональных данных (ФИО, Patient ID, даты рождения).")
        }
        appendLine("Всего сеансов: ${sessions.size}, всего событий: ${allFilteredEntries.size}")
        appendLine("==================================================\n")

        if (isBriefMode) {
            sessions.forEach { session ->
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
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (sanitizePhi) "sanitized" else "full"
        val fileName = "dicom_kit_log_${timeStamp}_$suffix.txt"

        val exportDir = File(context.cacheDir, "log_exports")
        exportDir.mkdirs()
        val file = File(exportDir, fileName)
        file.writeText(content, Charsets.UTF_8)

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

    /**
     * Эвристическое обезличивание строки.
     */
    fun sanitizePhi(raw: String): String {
        var s = raw
        // Замена ключевых полей DICOM тегов пациента
        s = PHI_TAGS_REGEX.replace(s) { match ->
            "${match.groupValues[1]}: *** ANONYMIZED ***"
        }
        // Маскирование дат рождения (YYYYMMDD или DD.MM.YYYY)
        s = DATE_PATTERN.replace(s, "********")
        s = DOT_DATE_PATTERN.replace(s, "**.**.****")
        return s
    }

    private fun maybeSanitize(text: String, sanitizePhi: Boolean): String {
        return if (sanitizePhi) sanitizePhi(text) else text
    }

    private fun maybeSanitizeDetail(key: String, value: String, sanitizePhi: Boolean): String {
        if (!sanitizePhi) return value
        val lk = key.lowercase(Locale.US)
        if (lk.contains("patient") || lk.contains("пациент") || lk.contains("birth") || lk.contains("0010,")) {
            return "*** ANONYMIZED ***"
        }
        return sanitizePhi(value)
    }
}
