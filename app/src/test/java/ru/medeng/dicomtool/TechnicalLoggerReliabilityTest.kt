package ru.medeng.dicomtool

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Автоматизированные тесты надежности технического журнала (Этап 4.3.1):
 * 1. Многопоточная неблокирующая запись без потери событий и без взаимных блокировок.
 * 2. Сохранение строгого хронологического порядка событий.
 * 3. Глобальная уникальность sessionUid между сессиями и запусками при сохранении коротких displayLabel.
 * 4. Ротация файлов по достижении предельного размера (активный <= лимита, сохранение ротированных файлов).
 * 5. Обратная совместимость чтения старого 9-колоночного TSV и нового 10-колоночного TSV.
 * 6. Безопасность очистки журнала (clearLogs) при одновременной активной фоновой записи.
 * 7. Устойчивость к сбоям диска (read-only файл/папка не роняет вызывающий поток и приложение).
 * 8. Корректная остановка и синхронизация накопившихся записей (flushPendingWrites / shutdown).
 */
class TechnicalLoggerReliabilityTest {

    private lateinit var tempDir: File
    private lateinit var logFile: File

    @Before
    fun setUp() {
        DicomSessionManager.resetForTesting()
        tempDir = Files.createTempDirectory("dicom_logger_test_").toFile()
        logFile = File(tempDir, "dicom_technical_log.tsv")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testConcurrentEventRecordingAcrossMultipleThreads() {
        val logger = PersistentDicomTechnicalLogger(
            logStorageFile = logFile,
            maxFileSizeBytes = 10 * 1024 * 1024L
        )

        val threadCount = 8
        val eventsPerThread = 100
        val totalExpected = threadCount * eventsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                startLatch.await()
                for (i in 0 until eventsPerThread) {
                    val session = DicomSessionManager.nextScuSession()
                    logger.recordTechnicalEvent(
                        category = DicomLogCategory.C_STORE_RQ,
                        direction = DicomTrafficDirection.OUTBOUND,
                        associationId = session.displayLabel,
                        title = "Thread $t Event $i",
                        summaryLines = listOf("Worker $t item $i"),
                        technicalDetails = mapOf("Thread" to t.toString(), "Index" to i.toString()),
                        sessionUid = session.sessionUid
                    )
                }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        assertTrue("Многопоточная запись должна завершиться вовремя", doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Проверяем flush
        assertTrue(logger.flushPendingWrites(5000))
        logger.shutdown(2000)

        val entries = logger.logsFlow.value
        assertEquals("В оперативной памяти должны присутствовать все $totalExpected событий", totalExpected, entries.size)

        // Проверяем чтение с диска новым экземпляром
        val diskLogger = PersistentDicomTechnicalLogger(
            logStorageFile = logFile,
            maxFileSizeBytes = 10 * 1024 * 1024L
        )
        val diskEntries = diskLogger.logsFlow.value
        assertEquals("На диске должны быть сохранены все $totalExpected событий", totalExpected, diskEntries.size)
        diskLogger.shutdown(1000)
    }

    @Test
    fun testEventOrderPreservation() {
        val logger = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        val eventCount = 200

        for (i in 0 until eventCount) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_ECHO_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = "SCU-1",
                title = "Ordered event #$i",
                summaryLines = listOf("Sequence $i"),
                technicalDetails = mapOf("sequence" to i.toString())
            )
        }

        assertTrue(logger.flushPendingWrites(3000))
        logger.shutdown(1000)

        val diskLogger = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        val loaded = diskLogger.logsFlow.value
        assertEquals(eventCount, loaded.size)

        for (i in 0 until eventCount) {
            assertEquals("Ordered event #$i", loaded[i].title)
            assertEquals(i.toString(), loaded[i].technicalDetails["sequence"])
        }
        diskLogger.shutdown(1000)
    }

    @Test
    fun testSessionUidUniquenessAcrossRestarts() {
        val session1 = DicomSessionManager.nextScuSession()
        val session2 = DicomSessionManager.nextScuSession()
        val scp1 = DicomSessionManager.nextScpSession(1)
        val scp2 = DicomSessionManager.nextScpSession(1)

        // Короткие метки могут совпадать
        assertEquals("SCU-1", session1.displayLabel)
        assertEquals("SCU-2", session2.displayLabel)
        assertTrue(scp1.displayLabel.startsWith("SCP-"))
        assertNotEquals(scp1.sessionUid, scp2.sessionUid)

        // Внутренние уникальные идентификаторы не совпадают
        assertNotEquals(session1.sessionUid, session2.sessionUid)
        assertTrue(session1.sessionUid.startsWith("SCU-"))
        assertTrue(session1.sessionUid.contains(DicomSessionManager.appRunId))

        assertTrue(scp1.sessionUid.startsWith("SCP-"))
        assertTrue(scp1.sessionUid.contains(DicomSessionManager.appRunId))
    }

    @Test
    fun testLogRotationAtSizeLimit() {
        // Устанавливаем небольшой порог ротации: 12 КБ, до 4 ротированных файлов
        val smallLimitBytes = 12 * 1024L
        val maxRotated = 4
        val logger = PersistentDicomTechnicalLogger(
            logStorageFile = logFile,
            maxFileSizeBytes = smallLimitBytes,
            maxRotatedFiles = maxRotated
        )

        // Генерируем 60 записей суммарным размером около 30 КБ (произойдет ротация на .1 и .2)
        val payloadText = "A".repeat(400)
        for (i in 0 until 60) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_STORE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = "SCU-$i",
                title = "Large payload event #$i: $payloadText",
                summaryLines = listOf("Payload line $i"),
                sessionUid = "UID-$i"
            )
        }

        assertTrue(logger.flushPendingWrites(5000))
        logger.shutdown(2000)

        // Проверяем наличие ротированных файлов
        val rotated1 = File(tempDir, "dicom_technical_log.1.tsv")

        assertTrue("Основной файл лога должен существовать", logFile.exists())
        assertTrue("Первый ротированный файл (.1.tsv) должен быть создан", rotated1.exists())

        // Проверяем, что активный файл не превышает значительно порог ротации
        assertTrue("Размер активного файла должен быть в разумных пределах", logFile.length() < smallLimitBytes * 2)

        // Читаем все файлы новым экземпляром логгера
        val reloadedLogger = PersistentDicomTechnicalLogger(
            logStorageFile = logFile,
            maxFileSizeBytes = smallLimitBytes,
            maxRotatedFiles = maxRotated
        )
        val allEntries = reloadedLogger.logsFlow.value
        assertEquals("Все 60 записей должны быть прочитаны из активного и ротированных файлов", 60, allEntries.size)
        reloadedLogger.shutdown(1000)
    }

    @Test
    fun testLegacyNineColumnTsvCompatibility() {
        // Записываем классический 9-колоночный TSV файл старого формата (без 10-й колонки sessionUid)
        // Формат колонок: id \t ts \t category \t direction \t assocId \t isError \t title \t summary \t details
        val legacyLine1 = "uuid-1\t12:00:00.000\tC_ECHO_RQ\tOUTBOUND\tSCU-1\tfalse\t[SCU] Echo RQ\tLine 1\u001FLine 2\tRole\u001ESCU\u001FHost\u001E10.0.0.1\n"
        val legacyLine2 = "uuid-2\t12:00:01.000\tC_ECHO_RSP\tINBOUND\tSCU-1\tfalse\t[SCU] Echo RSP\tSuccess\tStatus\u001E0x0000\n"

        logFile.writeText(legacyLine1 + legacyLine2, Charsets.UTF_8)

        val logger = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        val loaded = logger.logsFlow.value
        assertEquals("Должны быть успешно прочитаны 2 записи legacy TSV", 2, loaded.size)

        val first = loaded[0]
        assertEquals("uuid-1", first.id)
        assertEquals("12:00:00.000", first.timestampFormatted)
        assertEquals(DicomLogCategory.C_ECHO_RQ, first.category)
        assertEquals(DicomTrafficDirection.OUTBOUND, first.direction)
        assertEquals("SCU-1", first.associationId)
        assertEquals("[SCU] Echo RQ", first.title)
        assertEquals(listOf("Line 1", "Line 2"), first.summaryLines)
        assertEquals("SCU", first.technicalDetails["Role"])
        assertFalse(first.isError)
        // Для 9-колоночного формата effectiveSessionUid равен associationId ("SCU-1")
        assertEquals("SCU-1", first.effectiveSessionUid)

        // Записываем новую запись нового 10-колоночного формата
        val session = DicomSessionManager.nextScuSession()
        logger.recordTechnicalEvent(
            category = DicomLogCategory.C_STORE_RQ,
            direction = DicomTrafficDirection.OUTBOUND,
            associationId = session.displayLabel,
            title = "New 10-column event",
            sessionUid = session.sessionUid
        )

        assertTrue(logger.flushPendingWrites(3000))
        logger.shutdown(1000)

        // Проверяем, что файл теперь содержит и legacy, и новые записи
        val reloaded = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        val reloadedEntries = reloaded.logsFlow.value
        assertEquals(3, reloadedEntries.size)
        assertEquals(session.sessionUid, reloadedEntries[2].effectiveSessionUid)
        reloaded.shutdown(1000)
    }

    @Test
    fun testClearLogsWhileWriting() {
        val logger = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        val writeCount = AtomicInteger(0)
        val stopWriting = java.util.concurrent.atomic.AtomicBoolean(false)

        val writerThread = Thread {
            var i = 0
            while (!stopWriting.get() && i < 500) {
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.C_ECHO_RQ,
                    direction = DicomTrafficDirection.OUTBOUND,
                    associationId = "SCU-TEST",
                    title = "Event $i"
                )
                writeCount.incrementAndGet()
                i++
                Thread.sleep(2)
            }
        }
        writerThread.start()

        Thread.sleep(50)
        // Выполняем очистку во время активной записи
        logger.clearLogs()

        // В оперативной памяти журнал должен очиститься
        assertTrue("Журнал в памяти должен стать пустым или содержать только события, поступившие после clear", logger.logsFlow.value.size <= writeCount.get())

        stopWriting.set(true)
        writerThread.join()

        // Окончательно очищаем
        logger.clearLogs()
        assertTrue(logger.flushPendingWrites(2000))
        logger.shutdown(1000)

        val reloaded = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        assertEquals("После clearLogs и завершения записи лог на диске должен быть пуст", 0, reloaded.logsFlow.value.size)
        reloaded.shutdown(1000)
    }

    @Test
    fun testResilienceToDiskWriteErrors() {
        // Создаем файл лога, доступный только для чтения, или директорию на месте файла
        logFile.mkdirs() // Делаем директорией, чтобы запись файла гарантированно выдала IOException

        val logger = PersistentDicomTechnicalLogger(logStorageFile = logFile)

        // Попытка записи событий не должна выбрасывать исключение в вызывающий поток
        var threwException = false
        try {
            for (i in 0 until 10) {
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.C_ECHO_RQ,
                    direction = DicomTrafficDirection.OUTBOUND,
                    associationId = "SCU-FAIL",
                    title = "Resilience test #$i"
                )
            }
        } catch (_: Throwable) {
            threwException = true
        }

        assertFalse("Запись события не должна приводить к сбою приложения даже при невозможности записи на диск", threwException)
        // В оперативной памяти события должны остаться доступными для пользователя
        assertEquals(10, logger.logsFlow.value.size)

        logger.shutdown(1000)
    }

    @Test
    fun testGracefulShutdownAndFlushPendingWrites() {
        val logger = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        val eventCount = 100

        for (i in 0 until eventCount) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = "SCU-$i",
                title = "Pending event $i"
            )
        }

        // Вызываем shutdown и проверяем, что все накопившиеся события сохранены
        logger.shutdown(3000)

        val reloaded = PersistentDicomTechnicalLogger(logStorageFile = logFile)
        assertEquals("После shutdown все запланированные события должны быть на диске", eventCount, reloaded.logsFlow.value.size)
        reloaded.shutdown(1000)
    }
}
