package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*
import java.io.File
import java.util.concurrent.*

class JournalStartupStage56Test {
    @get:Rule val temp = TemporaryFolder()
    private fun entry(id: String, session: String = "SCU-Rold-abcdef-1") = DicomLogEntry(
        id, "12:00:00", DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND,
        "SCU-1", "Synthetic", emptyList(), sessionUid = session)

    @Test fun blockedHistoryDoesNotBlockProducerOrLoseLiveEventsAndClearWorks() {
        val file = File(temp.root, "history.tsv")
        PersistentDicomTechnicalLogger(file).let { old -> old.recordEvent(entry("old")); assertTrue(old.shutdown()) }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val logger = PersistentDicomTechnicalLogger(file, deferHistoryLoad = true, beforeHistoryLoad = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        val producer = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(logger.healthFlow.value.historyLoading)
            producer.submit { repeat(100) { logger.recordEvent(entry("live$it")) } }.get(2, TimeUnit.SECONDS)
            assertEquals(100, logger.logsFlow.value.size)
            release.countDown(); assertTrue(logger.flushPendingWrites())
            assertFalse(logger.healthFlow.value.historyLoading)
            assertEquals(listOf("old") + (0 until 100).map { "live$it" }, logger.logsFlow.value.map { it.id })
            assertTrue(logger.clearLogsAndWait())
            assertTrue(logger.logsFlow.value.isEmpty())
            logger.recordEvent(entry("after-clear")); assertTrue(logger.flushPendingWrites())
            assertEquals(listOf("after-clear"), logger.logsFlow.value.map { it.id })
        } finally { release.countDown(); producer.shutdownNow(); logger.shutdown() }
        val restored = PersistentDicomTechnicalLogger(file)
        try { assertEquals(listOf("after-clear"), restored.logsFlow.value.map { it.id }) }
        finally { restored.shutdown() }
    }
    @Test fun unfinishedPriorProcessIsUnknownButCurrentProcessRemainsInProgress() {
        val old = DicomSessionGrouper.groupEvents(listOf(entry("old"))).single()
        assertEquals(SessionOutcomeStatus.UNKNOWN, old.status)
        val session = DicomSessionManager.nextScuSession()
        val current = DicomSessionGrouper.groupEvents(listOf(entry("current", session.sessionUid))).single()
        assertEquals(SessionOutcomeStatus.IN_PROGRESS, current.status)
    }
}
