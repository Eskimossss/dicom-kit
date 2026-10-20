package ru.medeng.dicomtool.mwl.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class MwlTechnicalLogger(
    private val logFile: File,
    private val maxMemoryEntries: Int = 1000
) {
    private val lock = Any()
    private val _logsFlow = MutableStateFlow<List<DicomLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<DicomLogEntry>> = _logsFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    init {
        logFile.parentFile?.mkdirs()
        loadLogsFromDisk()
    }

    private fun loadLogsFromDisk() = synchronized(lock) {
        if (!logFile.exists()) return
        val list = mutableListOf<DicomLogEntry>()
        try {
            BufferedReader(InputStreamReader(FileInputStream(logFile), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim() ?: continue
                    if (l.isEmpty() || l.startsWith("#")) continue
                    val parts = l.split('\t')
                    if (parts.size >= 6) {
                        val id = parts[0]
                        val ts = parts[1]
                        val catStr = parts[2]
                        val dirStr = parts[3]
                        val assocId = parts[4]
                        val isErr = parts.getOrNull(5)?.toBoolean() ?: false
                        val title = parts.getOrNull(6) ?: ""
                        val sessionUid = parts.getOrNull(7) ?: assocId

                        val category = try {
                            DicomLogCategory.valueOf(catStr)
                        } catch (_: Exception) {
                            DicomLogCategory.SERVER_LIFECYCLE
                        }

                        val direction = when (dirStr) {
                            "IN" -> DicomTrafficDirection.INBOUND
                            "OUT" -> DicomTrafficDirection.OUTBOUND
                            else -> DicomTrafficDirection.LOCAL
                        }

                        val details = mutableMapOf<String, String>()
                        if (parts.size >= 9 && parts[8].isNotEmpty()) {
                            val pairs = parts[8].split("|||")
                            for (p in pairs) {
                                val idx = p.indexOf(":::")
                                if (idx > 0) {
                                    val k = p.substring(0, idx)
                                    val v = p.substring(idx + 3)
                                    details[k] = v
                                }
                            }
                        }

                        val summary = if (parts.size >= 10 && parts[9].isNotEmpty()) {
                            parts[9].split("|||")
                        } else emptyList()

                        list.add(
                            DicomLogEntry(
                                id = id,
                                timestampFormatted = ts,
                                category = category,
                                direction = direction,
                                associationId = assocId,
                                title = title,
                                summaryLines = summary,
                                technicalDetails = details,
                                isError = isErr,
                                sessionUid = sessionUid
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        _logsFlow.value = list.takeLast(maxMemoryEntries)
    }

    fun recordEvent(
        category: DicomLogCategory,
        direction: DicomTrafficDirection = DicomTrafficDirection.LOCAL,
        associationId: String = "MWL",
        sessionUid: String = associationId,
        title: String,
        summaryLines: List<String> = emptyList(),
        technicalDetails: Map<String, String> = emptyMap(),
        isError: Boolean = false
    ) = synchronized(lock) {
        val entry = DicomLogEntry(
            id = UUID.randomUUID().toString(),
            timestampFormatted = timeFormat.format(Date()),
            category = category,
            direction = direction,
            associationId = associationId,
            sessionUid = sessionUid,
            title = title,
            summaryLines = summaryLines,
            technicalDetails = technicalDetails,
            isError = isError
        )

        val current = _logsFlow.value.toMutableList()
        current.add(0, entry)
        if (current.size > maxMemoryEntries) {
            current.removeAt(current.size - 1)
        }
        _logsFlow.value = current

        // Запись в файл на диск
        appendEntryToDisk(entry)
    }

    private fun appendEntryToDisk(entry: DicomLogEntry) {
        try {
            val sb = java.lang.StringBuilder()
            sb.append(entry.id).append('\t')
            sb.append(entry.timestampFormatted).append('\t')
            sb.append(entry.category.name).append('\t')
            sb.append(entry.direction.code).append('\t')
            sb.append(entry.associationId).append('\t')
            sb.append(entry.isError).append('\t')
            sb.append(escapeTsv(entry.title)).append('\t')
            sb.append(entry.sessionUid).append('\t')

            val detailsStr = entry.technicalDetails.entries.joinToString("|||") { "${escapeTsv(it.key)}:::${escapeTsv(it.value)}" }
            sb.append(detailsStr).append('\t')

            val summaryStr = entry.summaryLines.joinToString("|||") { escapeTsv(it) }
            sb.append(summaryStr).append('\n')

            FileOutputStream(logFile, true).use { fos ->
                fos.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun escapeTsv(s: String): String = s.replace("\t", " ").replace("\n", " ").replace("\r", "")

    fun clearLogs() = synchronized(lock) {
        if (logFile.exists()) logFile.delete()
        _logsFlow.value = emptyList()
    }

    /**
     * Формирует диагностический отчет для сервисного инженера.
     * По умолчанию includePhi = false (скрывает ФИО, Patient ID, даты рождения).
     */
    fun exportDiagnosticReport(includePhi: Boolean = false): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.append("======================================================================\n")
        sb.append("            DICOM KIT — ДИАГНОСТИЧЕСКИЙ ЖУРНАЛ WORKLIST (MWL)         \n")
        sb.append("======================================================================\n")
        sb.append("Дата экспорта: ${exportDateFormat.format(Date())}\n")
        sb.append("Режим экспорта PHI: ").append(if (includePhi) "ВКЛЮЧЕН (реальные данные пациентов)" else "ОБЕЗЛИЧЕН (PHI скрыты)").append("\n")
        sb.append("Всего записей в журнале: ${_logsFlow.value.size}\n")
        sb.append("----------------------------------------------------------------------\n\n")

        val entries = _logsFlow.value.reversed() // В хронологическом порядке
        for ((idx, entry) in entries.withIndex()) {
            sb.append("[#%04d] %s | %-4s | %-12s | %s\n".format(
                idx + 1,
                entry.timestampFormatted,
                entry.direction.code,
                entry.associationId,
                entry.category.name
            ))
            sb.append("  Заголовок: ").append(sanitizePhi(entry.title, includePhi)).append("\n")
            if (entry.isError) sb.append("  СТАТУС: ОШИБКА / СБОЙ\n")

            if (entry.summaryLines.isNotEmpty()) {
                sb.append("  Сводка:\n")
                entry.summaryLines.forEach { line ->
                    sb.append("    • ").append(sanitizePhi(line, includePhi)).append("\n")
                }
            }

            if (entry.technicalDetails.isNotEmpty()) {
                sb.append("  Технические детали:\n")
                entry.technicalDetails.forEach { (k, v) ->
                    val sanitizedKey = k
                    val sanitizedVal = if (isPhiField(k)) sanitizePhi(v, includePhi) else v
                    sb.append("    - %-28s : %s\n".format(sanitizedKey, sanitizedVal))
                }
            }
            sb.append("\n")
        }
        sb.append("================ КОНЕЦ ДИАГНОСТИЧЕСКОГО ОТЧЕТА ================\n")
        sb.toString()
    }

    private fun isPhiField(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        return lower.contains("patient") || lower.contains("name") || lower.contains("birth") ||
                lower.contains("id") && lower.contains("patient") || lower.contains("фио")
    }

    private fun sanitizePhi(text: String, includePhi: Boolean): String {
        if (includePhi) return text
        // Маскирование ключевых паттернов ФИО и Patient ID
        var result = text
        // Если текст содержит явные теги или паттерны пациентов
        result = result.replace(Regex("Patient Name:\\s*[^|\\n]+", RegexOption.IGNORE_CASE), "Patient Name: [ОБЕЗЛИЧЕНО]")
        result = result.replace(Regex("Patient ID:\\s*[^|\\n]+", RegexOption.IGNORE_CASE), "Patient ID: [СКРЫТО]")
        result = result.replace(Regex("Birth Date:\\s*[^|\\n]+", RegexOption.IGNORE_CASE), "Birth Date: [СКРЫТО]")
        result = result.replace(Regex("ФИО:\\s*[^|\\n]+", RegexOption.IGNORE_CASE), "ФИО: [ОБЕЗЛИЧЕНО]")
        return result
    }
}
