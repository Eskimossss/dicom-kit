package ru.medeng.dicomtool.data.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.medeng.dicomtool.dicom.DicomTechnicalLogger
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded, ordered admission without disk I/O on producers. Loss is counted, never guaranteed away. */
class PersistentDicomTechnicalLogger(
    private val logStorageFile: File,
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    private val maxRotatedFiles: Int = DEFAULT_MAX_ROTATED_FILES,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val disk: LoggerDisk = FileLoggerDisk(),
    private val deferHistoryLoad: Boolean = false,
    private val beforeHistoryLoad: () -> Unit = {}
) : DicomTechnicalLogger {
    companion object {
        const val DEFAULT_MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L
        const val DEFAULT_MAX_ROTATED_FILES = 4
        const val DEFAULT_QUEUE_CAPACITY = 10000
        private val CRITICAL_CATEGORIES = setOf(DicomLogCategory.ASSOCIATE_RJ, DicomLogCategory.ABORT,
            DicomLogCategory.NETWORK_ERROR, DicomLogCategory.TCP_DISCONNECT, DicomLogCategory.SERVER_LIFECYCLE,
            DicomLogCategory.C_STORE_RSP, DicomLogCategory.C_ECHO_RSP, DicomLogCategory.RELEASE_RP)
    }
    private class Ack {
        val latch = CountDownLatch(1)
        @Volatile var success = false
        fun finish(result: Boolean) { success = result; latch.countDown() }
        fun await(ms: Long): Boolean = try { latch.await(ms, TimeUnit.MILLISECONDS) && success }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
    private sealed class Command {
        data class Write(val sequence: Long, val entry: DicomLogEntry) : Command()
        data class Flush(val ack: Ack) : Command()
        data class Clear(val sequence: Long, val ack: Ack) : Command()
    }
    private val gate = java.lang.Object()
    private val queue = ArrayDeque<Command>()
    private var queuedWrites = 0
    private var queuedControls = 0
    private val memory = ArrayDeque<Pair<Long, DicomLogEntry>>()
    private var sequence = 0L
    private var accepting = true
    private var stopping = false
    private var stopped = false
    private var diskFailed = false
    private val stoppedLatch = CountDownLatch(1)
    private var health = LoggerHealth(historyLoading = deferHistoryLoad)
    private val _healthFlow = MutableStateFlow(health)
    val healthFlow: StateFlow<LoggerHealth> = _healthFlow.asStateFlow()
    private val _logsFlow = MutableStateFlow<List<DicomLogEntry>>(emptyList())
    override val logsFlow: StateFlow<List<DicomLogEntry>> = _logsFlow.asStateFlow()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val runUid = "LOG-${UUID.randomUUID()}"
    private var savedLossKey = ""
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "DicomLogDiskWriter").apply { isDaemon = true } }

    init {
        require(queueCapacity > 0 && maxRotatedFiles >= 1 && maxFileSizeBytes > 0)
        if (!deferHistoryLoad) loadFromDisk()
        writer.submit {
            if (deferHistoryLoad) loadFromDisk()
            writerLoop()
        }
    }
    fun formatNow(): String = synchronized(timeFormatter) { timeFormatter.format(Date()) }
    private fun publishHealth() {
        val state = when {
            stopped -> LoggerWriteState.STOPPED
            stopping -> LoggerWriteState.STOPPING
            diskFailed -> LoggerWriteState.WRITE_FAILED
            health.copy(state = LoggerWriteState.ACTIVE).needsAttention -> LoggerWriteState.DEGRADED
            else -> LoggerWriteState.ACTIVE
        }
        health = health.copy(state = state, queueDepth = queuedWrites)
        _healthFlow.value = health
    }
    private fun remember(seq: Long, entry: DicomLogEntry) {
        memory.addLast(seq to entry)
        while (memory.size > 2000) memory.removeFirst()
        _logsFlow.value = memory.map { it.second }
    }
    private fun critical(entry: DicomLogEntry) = entry.isError || entry.category in CRITICAL_CATEGORIES
    override fun recordEvent(entry: DicomLogEntry) {
        val snapshot = sanitizeEntry(entry).copy(summaryLines = entry.summaryLines.toList())
        synchronized(gate) {
            remember(++sequence, snapshot)
            if (!accepting) {
                health = health.copy(rejectedAfterStop = health.rejectedAfterStop + 1,
                    rejectedCriticalAfterStop = health.rejectedCriticalAfterStop + if (critical(snapshot)) 1 else 0)
                publishHealth(); return
            }
            if (queuedWrites == queueCapacity && critical(snapshot)) {
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    if (item is Command.Write && !critical(item.entry)) {
                        iterator.remove(); queuedWrites--
                        health = health.copy(droppedNormal = health.droppedNormal + 1)
                        break
                    }
                }
            }
            if (queuedWrites == queueCapacity) {
                health = if (critical(snapshot)) health.copy(droppedCritical = health.droppedCritical + 1)
                    else health.copy(droppedNormal = health.droppedNormal + 1)
            } else {
                queue.addLast(Command.Write(sequence, snapshot)); queuedWrites++
                health = health.copy(accepted = health.accepted + 1)
                gate.notifyAll()
            }
            publishHealth()
        }
    }
    fun recordTechnicalEvent(category: DicomLogCategory, direction: DicomTrafficDirection, associationId: String,
        title: String, summaryLines: List<String> = emptyList(), technicalDetails: Map<String, String> = emptyMap(),
        isError: Boolean = false, sessionUid: String = "") {
        val details = if (sessionUid.isNotBlank() && !technicalDetails.containsKey("ID сеанса (UID)"))
            technicalDetails + ("ID сеанса (UID)" to sessionUid) else technicalDetails
        recordEvent(DicomLogEntry(UUID.randomUUID().toString(), formatNow(), category, direction, associationId,
            title, summaryLines, details, isError, sessionUid))
    }
    private fun control(command: Command): Boolean = synchronized(gate) {
        if (!accepting || queuedControls >= 32) {
            health = health.copy(controlRejected = health.controlRejected + 1,
                lastError = "Управляющая команда журнала не принята")
            publishHealth(); false
        } else { queue.addLast(command); queuedControls++; gate.notifyAll(); true }
    }
    override fun clearLogs() { clearLogsAndWait() }
    private fun awaitControl(ack: Ack, ms: Long): Boolean {
        val result = ack.await(ms)
        if (!result && ack.latch.count > 0) synchronized(gate) {
            health = health.copy(controlTimeouts = health.controlTimeouts + 1,
                lastError = "Управляющая операция не завершена в срок; она может завершиться позже")
            publishHealth()
        }
        return result
    }
    fun clearLogsAndWait(timeoutMs: Long = 5000): Boolean {
        val ack = Ack()
        val accepted = synchronized(gate) { control(Command.Clear(sequence, ack)) }
        return accepted && awaitControl(ack, timeoutMs)
    }
    fun flushPendingWrites(timeoutMs: Long = 5000): Boolean {
        synchronized(gate) { if (stopped) return !health.needsAttention && !diskFailed }
        val ack = Ack()
        return control(Command.Flush(ack)) && awaitControl(ack, timeoutMs)
    }
    fun shutdown(timeoutMs: Long = 5000): Boolean {
        synchronized(gate) { accepting = false; stopping = true; publishHealth(); gate.notifyAll() }
        val complete = try { stoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!complete) synchronized(gate) {
            health = health.copy(lastError = "Остановка writer не подтверждена; возможна незавершённая запись")
            publishHealth()
        }
        return complete && synchronized(gate) { !health.needsAttention && !diskFailed }
    }
    private fun failure(e: Exception, unconfirmed: Boolean = false, critical: Boolean = false) = synchronized(gate) {
        diskFailed = true
        health = health.copy(writeUnconfirmed = health.writeUnconfirmed + if (unconfirmed) 1 else 0,
            criticalWriteUnconfirmed = health.criticalWriteUnconfirmed + if (unconfirmed && critical) 1 else 0,
            lastError = "${e.javaClass.simpleName}: ${e.message}")
        publishHealth()
    }
    private fun append(entry: DicomLogEntry) {
        val bytes = (serializeEntry(entry) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= maxFileSizeBytes) { "Запись превышает ограничение файла журнала; запись на диск не подтверждена" }
        if (logStorageFile.isFile && logStorageFile.length() > 0 && logStorageFile.length() + bytes.size > maxFileSizeBytes)
            rotateFiles()
        disk.append(logStorageFile, bytes)
    }
    private fun lossKey(h: LoggerHealth) = "${h.droppedNormal}:${h.droppedCritical}:${h.writeUnconfirmed}:${h.criticalWriteUnconfirmed}:${h.rejectedAfterStop}:${h.readFailures}:${h.controlRejected}:${h.controlTimeouts}:${h.controlFailures}:${h.rotationPending}"
    private fun persistHealthIfNeeded() {
        val snapshot = synchronized(gate) { health }
        if (!snapshot.needsAttention || lossKey(snapshot) == savedLossKey) return
        val notice = DicomLogEntry(UUID.randomUUID().toString(), formatNow(), DicomLogCategory.SERVER_LIFECYCLE,
            DicomTrafficDirection.LOCAL, "LOG", "[LOG] Потери или неподтверждённая запись журнала", listOf(snapshot.warning),
            mapOf("Logger Health" to "1", "Dropped Normal" to snapshot.droppedNormal.toString(),
                "Dropped Critical" to snapshot.droppedCritical.toString(), "Write Unconfirmed" to snapshot.writeUnconfirmed.toString(),
                "Critical Write Unconfirmed" to snapshot.criticalWriteUnconfirmed.toString(),
                "Rejected After Stop" to snapshot.rejectedAfterStop.toString(), "Rejected Critical After Stop" to snapshot.rejectedCriticalAfterStop.toString(),
                "Read Failures" to snapshot.readFailures.toString(),
                "Control Rejected" to snapshot.controlRejected.toString(), "Control Timeouts" to snapshot.controlTimeouts.toString(),
                "Control Failures" to snapshot.controlFailures.toString(),
                "Rotation Pending" to snapshot.rotationPending.toString(),
                "Logger Error" to (snapshot.lastError ?: "")), true, runUid)
        try {
            append(notice)
            savedLossKey = lossKey(snapshot)
            synchronized(gate) { diskFailed = false; health = health.copy(lastError = null); remember(Long.MIN_VALUE, notice); publishHealth() }
        } catch (e: Exception) { failure(e) }
    }
    private fun writerLoop() {
        var inFlight: Command? = null
        try {
            while (true) {
                val command = synchronized(gate) {
                    if (queue.isEmpty() && accepting) gate.wait(200)
                    if (queue.isEmpty()) null else queue.removeFirst().also {
                        if (it is Command.Write) queuedWrites-- else queuedControls--
                        publishHealth()
                    }
                }
                inFlight = command
                if (command == null) {
                    persistHealthIfNeeded()
                    if (!synchronized(gate) { accepting }) break
                    continue
                }
                when (command) {
                    is Command.Write -> {
                        try {
                            append(command.entry)
                            synchronized(gate) { diskFailed = false; health = health.copy(persisted = health.persisted + 1, lastError = null); publishHealth() }
                        } catch (e: Exception) { failure(e, unconfirmed = true, critical = critical(command.entry)) }
                        inFlight = null // The original write has now either persisted or been counted once.
                        persistHealthIfNeeded()
                    }
                    is Command.Flush -> {
                        persistHealthIfNeeded()
                        command.ack.finish(synchronized(gate) { !health.needsAttention && !diskFailed })
                    }
                    is Command.Clear -> {
                        try {
                            clearDiskFiles()
                            synchronized(gate) {
                                val remaining = memory.filter { it.first > command.sequence }
                                memory.clear(); memory.addAll(remaining); _logsFlow.value = memory.map { it.second }
                                diskFailed = false; health = health.copy(lastError = null, rotationPending = false); publishHealth()
                            }
                            savedLossKey = ""; command.ack.finish(true)
                        } catch (e: Exception) {
                            synchronized(gate) { health = health.copy(controlFailures = health.controlFailures + 1) }
                            failure(e); command.ack.finish(false)
                        }
                    }
                }
                inFlight = null
            }
        } catch (e: Throwable) {
            val current = inFlight as? Command.Write
            when (val control = inFlight) {
                is Command.Flush -> control.ack.finish(false)
                is Command.Clear -> control.ack.finish(false)
                else -> Unit
            }
            if (inFlight is Command.Flush || inFlight is Command.Clear) synchronized(gate) {
                health = health.copy(controlFailures = health.controlFailures + 1)
            }
            failure(if (e is Exception) e else java.io.IOException("Writer остановлен: ${e.javaClass.simpleName}"),
                unconfirmed = current != null, critical = current?.entry?.let { critical(it) } == true)
        } finally {
            synchronized(gate) {
                accepting = false
                for (item in queue) when (item) {
                    is Command.Write -> health = health.copy(writeUnconfirmed = health.writeUnconfirmed + 1,
                        criticalWriteUnconfirmed = health.criticalWriteUnconfirmed + if (critical(item.entry)) 1 else 0)
                    is Command.Flush -> item.ack.finish(false)
                    is Command.Clear -> item.ack.finish(false)
                }
                queue.clear(); queuedWrites = 0; queuedControls = 0; stopped = true; publishHealth()
            }
            stoppedLatch.countDown(); writer.shutdown()
        }
    }
    internal fun getRotatedFile(index: Int): File {
        if (index <= 0) return logStorageFile
        val dot = logStorageFile.name.lastIndexOf('.')
        val name = if (dot > 0) logStorageFile.name.substring(0, dot) + ".$index" + logStorageFile.name.substring(dot)
            else logStorageFile.name + ".$index"
        return File(logStorageFile.parentFile, name)
    }
    internal fun rotateFiles() {
        val backup = rotationBackup()
        check(!backup.exists()) { "Незавершённая ротация: резервный файл сохранён для проверки" }
        val moves = mutableListOf<Pair<File, File>>()
        fun move(from: File, to: File) { disk.move(from, to); moves.add(from to to) }
        try {
            val oldest = getRotatedFile(maxRotatedFiles)
            if (oldest.exists()) move(oldest, backup)
            for (i in (maxRotatedFiles - 1) downTo 1) {
                val from = getRotatedFile(i)
                if (from.exists()) move(from, getRotatedFile(i + 1))
            }
            if (logStorageFile.exists()) move(logStorageFile, getRotatedFile(1))
            if (backup.exists()) disk.delete(backup) // Retention expires only after successful canonical moves.
        } catch (failure: Exception) {
            for ((from, to) in moves.asReversed()) {
                try { if (to.exists() && !from.exists()) disk.move(to, from) }
                catch (rollback: Exception) { failure.addSuppressed(rollback) }
            }
            synchronized(gate) { health = health.copy(rotationPending = backup.exists()) }
            throw failure
        }
    }
    internal fun rotationBackup() = File(logStorageFile.parentFile, logStorageFile.name + ".rotation-retained")
    private fun clearDiskFiles() {
        if (logStorageFile.exists()) disk.clear(logStorageFile)
        for (i in 1..maxRotatedFiles) if (getRotatedFile(i).exists()) disk.delete(getRotatedFile(i))
        if (rotationBackup().exists()) disk.delete(rotationBackup())
    }
    private fun loadFromDisk() {
        beforeHistoryLoad()
        val loaded = ArrayDeque<DicomLogEntry>()
        var recovered = LoggerHealth()
        if (rotationBackup().exists()) recovered = recovered.copy(rotationPending = true,
            lastError = "Найдена незавершённая ротация; резервный файл прочитан и не удаляется автоматически")
        val files = listOf(rotationBackup()) + (maxRotatedFiles downTo 0).map { getRotatedFile(it) }
        for (file in files) {
            if (!file.exists()) continue
            try {
                file.useLines(Charsets.UTF_8) { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
                    val entry = deserializeEntry(line)
                    if (entry == null) recovered = recovered.copy(readFailures = recovered.readFailures + 1)
                    else {
                        loaded.addLast(entry)
                        while (loaded.size > 2000) loaded.removeFirst()
                        if (entry.technicalDetails["Logger Health"] == "1") {
                            fun number(key: String) = entry.technicalDetails[key]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                            recovered = recovered.copy(droppedNormal = number("Dropped Normal"), droppedCritical = number("Dropped Critical"),
                                writeUnconfirmed = number("Write Unconfirmed"), criticalWriteUnconfirmed = number("Critical Write Unconfirmed"),
                                rejectedAfterStop = number("Rejected After Stop"), rejectedCriticalAfterStop = number("Rejected Critical After Stop"),
                                readFailures = maxOf(recovered.readFailures, number("Read Failures")), controlRejected = number("Control Rejected"),
                                controlTimeouts = number("Control Timeouts"), controlFailures = number("Control Failures"))
                        }
                    }
                } }
            } catch (e: Exception) { recovered = recovered.copy(readFailures = recovered.readFailures + 1, lastError = "Чтение журнала: ${e.javaClass.simpleName}") }
        }
        synchronized(gate) {
            val live = memory.toList()
            memory.clear()
            var historicalSequence = -loaded.size.toLong()
            loaded.forEach { memory.addLast(historicalSequence++ to it) }
            live.forEach { memory.addLast(it) }
            while (memory.size > 2000) memory.removeFirst()
            health = health.copy(historyLoading = false,
                droppedNormal = health.droppedNormal + recovered.droppedNormal,
                droppedCritical = health.droppedCritical + recovered.droppedCritical,
                writeUnconfirmed = health.writeUnconfirmed + recovered.writeUnconfirmed,
                criticalWriteUnconfirmed = health.criticalWriteUnconfirmed + recovered.criticalWriteUnconfirmed,
                rejectedAfterStop = health.rejectedAfterStop + recovered.rejectedAfterStop,
                rejectedCriticalAfterStop = health.rejectedCriticalAfterStop + recovered.rejectedCriticalAfterStop,
                readFailures = health.readFailures + recovered.readFailures,
                controlRejected = health.controlRejected + recovered.controlRejected,
                controlTimeouts = health.controlTimeouts + recovered.controlTimeouts,
                controlFailures = health.controlFailures + recovered.controlFailures,
                rotationPending = health.rotationPending || recovered.rotationPending,
                lastError = health.lastError ?: recovered.lastError)
            _logsFlow.value = memory.map { it.second }
            publishHealth()
        }
    }

    // --- Защита PHI (Patient Health Information) ---

    private fun sanitizeEntry(entry: DicomLogEntry): DicomLogEntry {
        val forbiddenKeys = setOf(
            "patientname", "patient name", "(0010,0010)",
            "patientid", "patient id", "(0010,0020)",
            "patientbirthdate", "patient birth date", "(0010,0030)",
            "pixeldata", "pixel data", "(7fe0,0010)"
        )
        val filteredDetails = entry.technicalDetails.filterKeys { key ->
            val normalized = key.trim().lowercase(Locale.ROOT)
            forbiddenKeys.none { normalized.contains(it) }
        }
        return entry.copy(technicalDetails = filteredDetails)
    }

    // --- Сериализация и десериализация TSV (с обратной совместимостью) ---

    private fun escapeField(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u001F", "\\f")
            .replace("\u001E", "\\e")

    private fun unescapeField(escaped: String): String {
        val sb = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c == '\\' && i + 1 < escaped.length) {
                when (val next = escaped[i + 1]) {
                    '\\' -> sb.append('\\')
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    'f' -> sb.append('\u001F')
                    'e' -> sb.append('\u001E')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun serializeEntry(e: DicomLogEntry): String {
        val summaryJoined = e.summaryLines.joinToString("\u001F") { escapeField(it) }
        val detailsJoined = e.technicalDetails.entries.joinToString("\u001F") { (k, v) ->
            "${escapeField(k)}\u001E${escapeField(v)}"
        }
        return listOf(
            escapeField(e.id),
            escapeField(e.timestampFormatted),
            e.category.name,
            e.direction.name,
            escapeField(e.associationId),
            e.isError.toString(),
            escapeField(e.title),
            summaryJoined,
            detailsJoined,
            escapeField(e.sessionUid) // 10-я колонка: глобально уникальный ID сеанса
        ).joinToString("\t")
    }

    internal fun deserializeEntry(line: String): DicomLogEntry? {
        return try {
            val parts = line.split("\t")
            if (parts.size < 9) return null
            val id = unescapeField(parts[0])
            val ts = unescapeField(parts[1])
            val category = runCatching { DicomLogCategory.valueOf(parts[2]) }
                .getOrDefault(DicomLogCategory.SERVER_LIFECYCLE)
            val direction = runCatching { DicomTrafficDirection.valueOf(parts[3]) }
                .getOrDefault(DicomTrafficDirection.INBOUND)
            val assocId = unescapeField(parts[4])
            val isError = parts[5].toBoolean()
            val title = unescapeField(parts[6])
            val summaryLines = if (parts[7].isEmpty()) {
                emptyList()
            } else {
                parts[7].split("\u001F").map { unescapeField(it) }
            }
            val details = LinkedHashMap<String, String>()
            if (parts[8].isNotEmpty()) {
                parts[8].split("\u001F").forEach { kv ->
                    val kvParts = kv.split("\u001E", limit = 2)
                    if (kvParts.size == 2) {
                        details[unescapeField(kvParts[0])] = unescapeField(kvParts[1])
                    }
                }
            }
            // Обратная совместимость: если строка старого 9-колоночного формата, sessionUid = assocId
            val sessionUid = if (parts.size >= 10) unescapeField(parts[9]) else assocId

            DicomLogEntry(
                id = id,
                timestampFormatted = ts,
                category = category,
                direction = direction,
                associationId = assocId,
                title = title,
                summaryLines = summaryLines,
                technicalDetails = details,
                isError = isError,
                sessionUid = sessionUid
            )
        } catch (_: Exception) {
            null
        }
    }
}
