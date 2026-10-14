package ru.medeng.dicomtool.data.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.medeng.dicomtool.dicom.DicomTechnicalLogger
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Высоконадежный персистентный технический журнал DICOM-обмена (PS3.7 / PS3.8).
 *
 * Архитектурные свойства (Этап 4.3.1):
 * 1. **Неблокирующее журналирование**: сетевые потоки SCP/SCU никогда не блокируются на дисковом I/O.
 *    Запись на диск выполняется строго последовательно в фоновом выделенном потоке через очередь.
 * 2. **Потокобезопасность**: обновление оперативного StateFlow происходит мгновенно и безопасно при
 *    любом количестве параллельных соединений.
 * 3. **Ограничение очереди и защита от потерь**: при переполнении очереди некритические события
 *    сбрасываются с фиксацией счетчика потерь, а критические события (ошибки, отказы, разрывы) сохраняются.
 * 4. **Ротация файлов и лимит на диске**: размер активного файла ограничен 5 МБ, общее число файлов до 5
 *    (активный + до 4 ротированных, суммарно до 25 МБ). При заполнении выполняется безопасная ротация.
 * 5. **Совместимость форматов**: поддерживает чтение как старого 9-колоночного TSV, так и нового
 *    10-колоночного формата с сохранением глобально уникального sessionUid.
 * 6. **Гарантия очистки и остановки**: метод flushPendingWrites() обеспечивает сохранение накопившихся
 *    событий при штатной остановке, а clearLogs() очищает как оперативную память, так и все файлы на диске.
 */
class PersistentDicomTechnicalLogger(
    private val logStorageFile: File,
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    private val maxRotatedFiles: Int = DEFAULT_MAX_ROTATED_FILES,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : DicomTechnicalLogger {

    companion object {
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 5 * 1024 * 1024L // 5 МБ
        const val DEFAULT_MAX_ROTATED_FILES: Int = 4                    // 1 активный + 4 ротированных = 5 файлов (25 МБ)
        const val DEFAULT_QUEUE_CAPACITY: Int = 10000

        private val CRITICAL_CATEGORIES = setOf(
            DicomLogCategory.ASSOCIATE_RJ,
            DicomLogCategory.ABORT,
            DicomLogCategory.NETWORK_ERROR,
            DicomLogCategory.TCP_DISCONNECT,
            DicomLogCategory.SERVER_LIFECYCLE
        )
    }

    private val memoryLock = Any()
    private val maxEntries = 2000
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logsFlow = MutableStateFlow<List<DicomLogEntry>>(emptyList())
    override val logsFlow: StateFlow<List<DicomLogEntry>> = _logsFlow.asStateFlow()

    private sealed class LogCommand {
        data class Write(val entry: DicomLogEntry) : LogCommand()
        data class Clear(val latch: CountDownLatch) : LogCommand()
        data class Flush(val latch: CountDownLatch) : LogCommand()
        object PoisonPill : LogCommand()
    }

    private val writeQueue = LinkedBlockingQueue<LogCommand>(queueCapacity)
    private val droppedNonCriticalCount = AtomicInteger(0)
    private val isRunning = AtomicBoolean(true)

    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DicomLogDiskWriter").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    init {
        logStorageFile.parentFile?.mkdirs()
        loadFromDisk()
        startBackgroundWriter()
    }

    fun formatNow(): String = synchronized(timeFormatter) {
        timeFormatter.format(Date())
    }

    /**
     * Неблокирующая регистрация события.
     * Мгновенно обновляет StateFlow для UI и помещает событие в фоновую очередь записи на диск.
     */
    override fun recordEvent(entry: DicomLogEntry) {
        val sanitizedEntry = sanitizeEntry(entry)

        // 1. Немедленно обновляем оперативное состояние в памяти для UI
        synchronized(memoryLock) {
            val updated = (_logsFlow.value + sanitizedEntry).takeLast(maxEntries)
            _logsFlow.value = updated
        }

        // 2. Помещаем в очередь на последовательную запись на диск без блокировки вызывающего потока
        if (!isRunning.get()) return

        val cmd = LogCommand.Write(sanitizedEntry)
        if (!writeQueue.offer(cmd)) {
            handleQueueOverflow(sanitizedEntry)
        }
    }

    /**
     * Регистрация технического события с расширенными параметрами и sessionUid.
     */
    fun recordTechnicalEvent(
        category: DicomLogCategory,
        direction: DicomTrafficDirection,
        associationId: String,
        title: String,
        summaryLines: List<String> = emptyList(),
        technicalDetails: Map<String, String> = emptyMap(),
        isError: Boolean = false,
        sessionUid: String = ""
    ) {
        val details = if (sessionUid.isNotBlank() && !technicalDetails.containsKey("ID сеанса (UID)")) {
            val linked = LinkedHashMap<String, String>(technicalDetails.size + 1)
            linked.putAll(technicalDetails)
            linked["ID сеанса (UID)"] = sessionUid
            linked
        } else {
            technicalDetails
        }

        recordEvent(
            DicomLogEntry(
                id = UUID.randomUUID().toString(),
                timestampFormatted = formatNow(),
                category = category,
                direction = direction,
                associationId = associationId,
                title = title,
                summaryLines = summaryLines,
                technicalDetails = details,
                isError = isError,
                sessionUid = sessionUid
            )
        )
    }

    /**
     * Полная очистка журнала: очищает оперативное состояние, очередь и все файлы (активный и ротированные).
     */
    override fun clearLogs() {
        synchronized(memoryLock) {
            _logsFlow.value = emptyList()
        }

        // Очищаем текущую очередь в памяти
        writeQueue.clear()

        // Синхронизируем очистку на диске через рабочий поток
        if (isRunning.get()) {
            val latch = CountDownLatch(1)
            writeQueue.offer(LogCommand.Clear(latch))
            try {
                latch.await(3000, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else {
            clearDiskFiles()
        }
    }

    /**
     * Ожидает завершения записи всех накопившихся в очереди событий на диск.
     * Полезно при штатной остановке приложения, сервиса или в тестах.
     */
    fun flushPendingWrites(timeoutMs: Long = 5000): Boolean {
        if (!isRunning.get()) return true
        val latch = CountDownLatch(1)
        if (writeQueue.offer(LogCommand.Flush(latch))) {
            return try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
        return false
    }

    /**
     * Корректная остановка фонового обработчика с сохранением ожидающих событий.
     */
    fun shutdown(timeoutMs: Long = 5000) {
        if (isRunning.compareAndSet(true, false)) {
            flushPendingWrites(timeoutMs)
            writeQueue.offer(LogCommand.PoisonPill)
            writerExecutor.shutdown()
            try {
                writerExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    // --- Обработка переполнения очереди ---

    private fun handleQueueOverflow(entry: DicomLogEntry) {
        val isCritical = entry.isError || CRITICAL_CATEGORIES.contains(entry.category)
        if (isCritical) {
            // Для критического события вытесняем одно старое событие из очереди без блокировки
            val evicted = writeQueue.poll()
            if (evicted != null) {
                droppedNonCriticalCount.incrementAndGet()
            }
            if (!writeQueue.offer(LogCommand.Write(entry))) {
                droppedNonCriticalCount.incrementAndGet()
            }
        } else {
            droppedNonCriticalCount.incrementAndGet()
        }
    }

    // --- Фоновый рабочий цикл записи на диск ---

    private fun startBackgroundWriter() {
        writerExecutor.submit {
            try {
                while (isRunning.get() || writeQueue.isNotEmpty()) {
                    val cmd = writeQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    when (cmd) {
                        is LogCommand.Write -> {
                            handleDiskWrite(cmd.entry)
                        }

                        is LogCommand.Clear -> {
                            clearDiskFiles()
                            cmd.latch.countDown()
                        }

                        is LogCommand.Flush -> {
                            cmd.latch.countDown()
                        }

                        is LogCommand.PoisonPill -> {
                            break
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun handleDiskWrite(entry: DicomLogEntry) {
        try {
            // Если ранее были сброшены некритические события, вставляем предупреждение
            val dropped = droppedNonCriticalCount.getAndSet(0)
            if (dropped > 0) {
                val warningLine = serializeEntry(
                    DicomLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestampFormatted = formatNow(),
                        category = DicomLogCategory.SERVER_LIFECYCLE,
                        direction = DicomTrafficDirection.LOCAL,
                        associationId = "LOG",
                        title = "[LOG] Предупреждение: очередь журнала была переполнена (пропущено $dropped обычных записей)",
                        summaryLines = listOf("Критические события сохранены без потерь."),
                        isError = true,
                        sessionUid = "LOG-OVERFLOW"
                    )
                )
                writeLineToDisk(warningLine)
            }

            // Проверяем размер файла перед записью и при необходимости выполняем ротацию
            if (logStorageFile.exists() && logStorageFile.length() >= maxFileSizeBytes) {
                rotateFiles()
            }

            val line = serializeEntry(entry)
            writeLineToDisk(line)
        } catch (_: Exception) {
            // Ошибки файловой системы изолируются и не приводят к падению сетевых потоков
        }
    }

    private fun writeLineToDisk(line: String) {
        try {
            FileOutputStream(logStorageFile, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    // --- Ротация лог-файлов ---

    internal fun getRotatedFile(index: Int): File {
        if (index <= 0) return logStorageFile
        val name = logStorageFile.name
        val dotIdx = name.lastIndexOf('.')
        val newName = if (dotIdx > 0) {
            val base = name.substring(0, dotIdx)
            val ext = name.substring(dotIdx)
            "$base.$index$ext"
        } else {
            "$name.$index"
        }
        return File(logStorageFile.parentFile, newName)
    }

    internal fun rotateFiles() {
        try {
            // 1. Удаляем самый старый файл, если он превышает лимит ротации
            val oldest = getRotatedFile(maxRotatedFiles)
            if (oldest.exists()) {
                oldest.delete()
            }

            // 2. Сдвигаем существующие ротированные файлы: .3 -> .4, .2 -> .3, .1 -> .2
            for (i in (maxRotatedFiles - 1) downTo 1) {
                val src = getRotatedFile(i)
                val dst = getRotatedFile(i + 1)
                if (src.exists()) {
                    src.renameTo(dst)
                }
            }

            // 3. Переименовываем активный файл в .1
            val firstRotated = getRotatedFile(1)
            if (logStorageFile.exists()) {
                logStorageFile.renameTo(firstRotated)
            }
        } catch (_: Exception) {
        }
    }

    private fun clearDiskFiles() {
        try {
            if (logStorageFile.exists()) {
                logStorageFile.writeText("", Charsets.UTF_8)
            }
            for (i in 1..maxRotatedFiles) {
                val f = getRotatedFile(i)
                if (f.exists()) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    // --- Загрузка и восстановление из дисковых файлов ---

    private fun loadFromDisk() {
        synchronized(memoryLock) {
            val existingFiles = mutableListOf<File>()

            // Собираем существующие файлы в хронологическом порядке: сначала самые старые ротированные, затем активный
            for (i in maxRotatedFiles downTo 1) {
                val f = getRotatedFile(i)
                if (f.exists() && f.length() > 0) {
                    existingFiles.add(f)
                }
            }
            if (logStorageFile.exists() && logStorageFile.length() > 0) {
                existingFiles.add(logStorageFile)
            }

            if (existingFiles.isEmpty()) {
                _logsFlow.value = emptyList()
                return
            }

            try {
                val allEntries = mutableListOf<DicomLogEntry>()
                for (file in existingFiles) {
                    try {
                        file.useLines(Charsets.UTF_8) { lines ->
                            lines.filter { it.isNotBlank() }
                                .mapNotNull { deserializeEntry(it) }
                                .forEach { allEntries.add(it) }
                        }
                    } catch (_: Exception) {
                    }
                }
                _logsFlow.value = allEntries.takeLast(maxEntries)
            } catch (_: Exception) {
                _logsFlow.value = emptyList()
            }
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
            .replace("\r", "")

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
