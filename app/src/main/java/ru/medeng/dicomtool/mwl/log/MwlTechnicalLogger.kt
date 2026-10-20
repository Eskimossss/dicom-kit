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
    private val maxMemoryEntries: Int = 1000,
    private val maxDiskBytes: Long = 2L * 1024 * 1024,
    private val beforeDiskWrite: () -> Unit = {}
) : java.io.Closeable {
    private val lock = Any()
    private val _logsFlow = MutableStateFlow<List<DicomLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<DicomLogEntry>> = _logsFlow.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private sealed class Command {
        data class Write(val entry: DicomLogEntry) : Command()
        data object Clear : Command()
        data class Barrier(val done: java.util.concurrent.CompletableFuture<Unit>) : Command()
        data object Stop : Command()
    }
    private val queue = java.util.concurrent.ArrayBlockingQueue<Command>(256)
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    @Volatile var lastWriteError: String? = null
        private set
    private val writer: Thread
    val droppedEntries: Long get() = dropped.get()

    init {
        logFile.parentFile?.mkdirs()
        loadLogsFromDisk()
        writer = Thread({
            while (true) {
                when (val command = queue.take()) {
                    is Command.Write -> appendEntryToDisk(command.entry)
                    Command.Clear -> runCatching { logFile.delete(); File(logFile.path + ".1").delete() }
                    is Command.Barrier -> command.done.complete(Unit)
                    Command.Stop -> return@Thread
                }
            }
        }, "mwl-log-writer").apply { isDaemon = true; start() }
    }

    private fun loadLogsFromDisk() = synchronized(lock) {
        val sources = listOf(File(logFile.path + ".1"), logFile).filter { it.isFile }
        if (sources.isEmpty()) return
        val list = java.util.ArrayDeque<DicomLogEntry>()
        try {
            for (source in sources) BufferedReader(InputStreamReader(FileInputStream(source), StandardCharsets.UTF_8)).use { reader ->
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

                        if (list.size >= maxMemoryEntries) list.removeFirst()
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

        _logsFlow.value = list.toList().reversed()
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
        if (closed) return@synchronized
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
        if (!closed && !queue.offer(Command.Write(entry))) dropped.incrementAndGet()
    }

    private fun appendEntryToDisk(entry: DicomLogEntry) {
        try {
            beforeDiskWrite()
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

            val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= maxDiskBytes) { "MWL log entry exceeds disk limit" }
            if (logFile.length() + bytes.size > maxDiskBytes) {
                java.nio.file.Files.move(logFile.toPath(), File(logFile.path + ".1").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            FileOutputStream(logFile, true).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: Exception) { lastWriteError = e.javaClass.simpleName }
    }

    fun flush(timeoutMs: Long = 5000): Boolean {
        if (closed) return false
        val done = java.util.concurrent.CompletableFuture<Unit>()
        return try {
            queue.offer(Command.Barrier(done), timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) &&
                run { done.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); true }
        } catch (_: Exception) { false }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        // close is a lifecycle operation, never called on the DIMSE path.
        if (!queue.offer(Command.Stop, 5, java.util.concurrent.TimeUnit.SECONDS)) writer.interrupt()
        writer.join(5000)
    }

    private fun escapeTsv(s: String): String = s.replace("\t", " ").replace("\n", " ").replace("\r", "")

    fun clearLogs() = synchronized(lock) {
        queue.removeIf { it is Command.Write }
        check(!closed && queue.offer(Command.Clear)) { "MWL logger queue is full; clear was not performed" }
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
        sb.append("Переполнение очереди (пропущено записей): $droppedEntries\n")
        lastWriteError?.let { sb.append("Последняя ошибка записи: $it\n") }
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
            sb.append("  Заголовок: ").append(if (includePhi) entry.title else entry.category.name).append("\n")
            if (entry.isError) sb.append("  СТАТУС: ОШИБКА / СБОЙ\n")

            if (includePhi && entry.summaryLines.isNotEmpty()) {
                sb.append("  Сводка:\n")
                entry.summaryLines.forEach { line ->
                    sb.append("    • ").append(sanitizePhi(line, includePhi)).append("\n")
                }
            }

            if (entry.technicalDetails.isNotEmpty()) {
                sb.append("  Технические детали:\n")
                entry.technicalDetails.forEach { (k, v) ->
                    if (includePhi || (k in SAFE_NUMERIC_FIELDS && v.matches(Regex("[0-9A-Fa-fxXHh ()]+")))) {
                        sb.append("    - %-28s : %s\n".format(k, v))
                    }
                }
            }
            sb.append("\n")
        }
        sb.append("================ КОНЕЦ ДИАГНОСТИЧЕСКОГО ОТЧЕТА ================\n")
        sb.toString()
    }

    private companion object {
        val SAFE_NUMERIC_FIELDS = setOf("Message ID", "Status", "Port", "TCP Port", "Priority", "PC ID", "Matches Sent", "Total Matches", "Pending Responses", "Received Items", "Presentation Contexts Count")
    }
    private fun sanitizePhi(text: String, includePhi: Boolean) = if (includePhi) text else "[СКРЫТО]"
}
