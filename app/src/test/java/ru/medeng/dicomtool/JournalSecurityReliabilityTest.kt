package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*
import java.io.File
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class JournalSecurityReliabilityTest {
    @get:Rule val temp = TemporaryFolder()
    private val phi = "СИНТЕТИЧЕСКИЙ^ИВАНОВ ☃ ' & <PATIENT>"
    private fun event(id: String = "1", error: Boolean = false) = DicomLogEntry(id, "Иванов-$id",
        DicomLogCategory.C_STORE_RSP, DicomTrafficDirection.INBOUND, "SCU-$phi", "Ошибка $phi",
        listOf("Exception: /storage/$phi.dcm; PatientID=PID-SECRET; AccessionNumber=ACC-SECRET"),
        mapOf("PatientName" to phi, "PatientID" to "PID-SECRET", "AccessionNumber" to "ACC-SECRET",
            "Имя файла" to "$phi.dcm", "Error Comment" to "Ошибка $phi", "Status" to "0x0000",
            "SOP Instance UID" to "1.2.840.999.12345", "Transfer Syntax UID" to "1.2.840.10008.1.2.1.99",
            "Affected SOP Class UID" to "1.2.840.10008.5.1.4.1.1.7", "Message ID Being Responded To" to "7"), error, "SESSION-$phi-$id")
    private fun session(id: String = "1"): DicomSessionSummary = DicomSessionGrouper.groupEvents(listOf(event(id))).single().copy(
        statusTitle = phi, statusSubtitle = phi, remoteHostAndPort = phi, calledAeTitle = phi,
        associationParameters = mapOf("Путь" to "/study/$phi"), filesTransferred = listOf(
            TransferredFileItem("$phi.dcm", sopInstanceUid = "1.2.840.999.12345", statusText = phi, isSuccess = true)))
    private fun assertNoPhi(text: String) {
        for (needle in listOf("ИВАНОВ", "Иванов", "PATIENT", "PID-SECRET", "ACC-SECRET", "12345", "/storage/", "/study/", "Exception:"))
            assertFalse("Unsafe field escaped schema: $needle", text.contains(needle))
        assertFalse(text.contains("ANONYMIZED"))
    }
    @Test fun defaultEventCopyExcludesFreeTextIdentifiersAndExceptions() {
        val source = event(); val snapshot = source.copy()
        val text = DicomLogExportUtils.formatSingleEvent(source)
        assertNoPhi(text); assertTrue(text.contains("Status: 0x0000")); assertTrue(text.contains("1.2.840.10008.1.2.1.99"))
        assertEquals(snapshot, source)
    }
    @Test fun sessionCopyExcludesDerivedFilenamesAndAssociationText() {
        val source = session(); val snapshot = source.copy()
        assertNoPhi(DicomLogExportUtils.formatSession(source)); assertEquals(snapshot, source)
    }
    @Test fun allExportModesUseSameAllowlistForMultipleSessions() {
        val sessions = listOf(session("1"), session("2"))
        for (brief in listOf(true, false)) {
            val text = DicomLogExportUtils.formatFullExport(sessions, sessions.flatMap { it.events }, brief, true)
            assertNoPhi(text); assertTrue(text.contains("всего событий: 2"))
        }
    }
    @Test fun fullCopiesRetainStoredDataAndWarnExplicitly() {
        assertTrue(DicomLogExportUtils.formatSingleEvent(event(), false).contains(phi))
        val text = DicomLogExportUtils.formatSession(session(), false)
        assertTrue(text.contains(phi)); assertTrue(text.contains("может содержать персональные данные"))
    }
    @Test fun forgedAllowlistedFieldsWithFreeTextAreExcludedRatherThanMasked() {
        val source = event().copy(technicalDetails = mapOf("Status" to "0x0000 $phi", "Message ID" to "7 $phi",
            "SOP Class UID" to "1.2.3.12345", "Transfer Syntax UID" to "1.2.840.10008.1.2.1 $phi",
            "SCU File Result" to "SUCCESS $phi", "Remote Socket" to "10.0.0.1 $phi"))
        val text = DicomLogExportUtils.formatSingleEvent(source)
        assertNoPhi(text); assertFalse(text.contains("Status:")); assertFalse(text.contains("1.2.3"))
    }
    @Test fun validTechnicalOutcomesSurviveProjection() {
        for (status in listOf("SUCCESS", "WARNING", "FAILED", "SKIPPED", "CANCELLED", "UNCONFIRMED")) {
            val text = DicomLogExportUtils.formatSingleEvent(event().copy(technicalDetails = mapOf("SCU File Result" to status)))
            assertTrue(text.contains(status))
        }
    }
    private fun file() = File(temp.newFolder(), "events.tsv")
    private fun plain(id: Int, critical: Boolean = false) = DicomLogEntry("$id", "12:00:00.000",
        if (critical) DicomLogCategory.C_STORE_RSP else DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.LOCAL,
        "SCU-1", "event-$id", emptyList(), sessionUid = "run-session")
    private class HeldDisk : FileLoggerDisk() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        override fun append(file: File, bytes: ByteArray) {
            if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            super.append(file, bytes)
        }
    }
    @Test fun saturatedCriticalQueueDoesNotEvictControlOrPromiseNoLoss() {
        val disk = HeldDisk(); val logger = PersistentDicomTechnicalLogger(file(), queueCapacity = 2, disk = disk)
        val executor = Executors.newSingleThreadExecutor()
        try {
            logger.recordEvent(plain(0, true)); assertTrue(disk.entered.await(1, TimeUnit.SECONDS))
            logger.recordEvent(plain(1, true)); logger.recordEvent(plain(2, true))
            val flush = executor.submit<Boolean> { logger.flushPendingWrites(3000) }
            logger.recordEvent(plain(3, true))
            assertEquals(1, logger.healthFlow.value.droppedCritical)
            disk.release.countDown(); assertFalse(flush.get(4, TimeUnit.SECONDS))
            assertEquals(0, logger.healthFlow.value.controlRejected)
            assertEquals(0, logger.healthFlow.value.controlTimeouts)
            assertTrue(logger.healthFlow.value.needsAttention)
        } finally { disk.release.countDown(); logger.shutdown(); executor.shutdownNow() }
    }
    @Test fun criticalAdmissionEvictsOnlyNormalWriteAndPreservesRemainingOrder() {
        val disk = HeldDisk(); val storage = file(); val logger = PersistentDicomTechnicalLogger(storage, queueCapacity = 2, disk = disk)
        try {
            logger.recordEvent(plain(0)); assertTrue(disk.entered.await(1, TimeUnit.SECONDS))
            logger.recordEvent(plain(1)); logger.recordEvent(plain(2, true)); logger.recordEvent(plain(3, true))
            assertEquals(1, logger.healthFlow.value.droppedNormal); assertEquals(0, logger.healthFlow.value.droppedCritical)
            disk.release.countDown(); logger.shutdown()
            val rows = logger.logsFlow.value // Disk is independently checked below, memory intentionally retains even evicted events.
            assertTrue(rows.any { it.id == "1" })
            val reloaded = PersistentDicomTechnicalLogger(storage)
            try { assertEquals(listOf("0", "2", "3"), reloaded.logsFlow.value.filter { it.id in setOf("0", "1", "2", "3") }.map { it.id }) }
            finally { reloaded.shutdown() }
        } finally { disk.release.countDown(); logger.shutdown() }
    }
    @Test fun producerDoesNotWaitForBlockedDiskOrFullQueue() {
        val disk = HeldDisk(); val logger = PersistentDicomTechnicalLogger(file(), queueCapacity = 2, disk = disk)
        try {
            logger.recordEvent(plain(0)); assertTrue(disk.entered.await(1, TimeUnit.SECONDS))
            val start = System.nanoTime()
            repeat(300) { logger.recordEvent(plain(it + 1)) }
            assertTrue("Producer waited on disk", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            println("Stage 5.3: 300 admissions with blocked disk took ${(System.nanoTime() - start) / 1_000_000.0} ms")
            assertEquals(298, logger.healthFlow.value.droppedNormal)
        } finally { disk.release.countDown(); logger.shutdown() }
    }
    @Test fun shutdownWithFullQueueDrainsRetainedEventsWithoutPoisonEviction() {
        val disk = HeldDisk(); val storage = file(); val logger = PersistentDicomTechnicalLogger(storage, queueCapacity = 2, disk = disk)
        val executor = Executors.newSingleThreadExecutor()
        try {
            logger.recordEvent(plain(0)); assertTrue(disk.entered.await(1, TimeUnit.SECONDS))
            logger.recordEvent(plain(1)); logger.recordEvent(plain(2))
            val stop = executor.submit<Boolean> { logger.shutdown(3000) }; disk.release.countDown()
            assertTrue(stop.get(4, TimeUnit.SECONDS)); assertEquals(LoggerWriteState.STOPPED, logger.healthFlow.value.state)
            val reloaded = PersistentDicomTechnicalLogger(storage)
            try { assertEquals(listOf("0", "1", "2"), reloaded.logsFlow.value.map { it.id }) } finally { reloaded.shutdown() }
        } finally { disk.release.countDown(); logger.shutdown(); executor.shutdownNow() }
    }
    @Test fun diskFailureIsVisibleAndRecoveryKeepsHistoricalUnconfirmedCount() {
        val failing = AtomicBoolean(true)
        val disk = object : FileLoggerDisk() { override fun append(file: File, bytes: ByteArray) {
            if (failing.get()) throw IOException("ENOSPC synthetic") else super.append(file, bytes)
        } }
        val storage = file(); val logger = PersistentDicomTechnicalLogger(storage, disk = disk)
        try {
            logger.recordEvent(plain(0)); assertFalse(logger.flushPendingWrites())
            assertEquals(LoggerWriteState.WRITE_FAILED, logger.healthFlow.value.state)
            assertEquals(1, logger.healthFlow.value.writeUnconfirmed)
            failing.set(false); logger.recordEvent(plain(1)); assertFalse(logger.flushPendingWrites())
            assertEquals(LoggerWriteState.DEGRADED, logger.healthFlow.value.state)
            assertEquals(1, logger.healthFlow.value.writeUnconfirmed)
            logger.shutdown()
            val reloaded = PersistentDicomTechnicalLogger(storage)
            try { assertEquals(1, reloaded.healthFlow.value.writeUnconfirmed) } finally { reloaded.shutdown() }
        } finally { logger.shutdown() }
    }
    @Test fun failedRotationPreservesOriginalAndReportsFailure() {
        val storage = file()
        val disk = object : FileLoggerDisk() { override fun move(from: File, to: File) { throw IOException("Synthetic rename failure") } }
        val logger = PersistentDicomTechnicalLogger(storage, maxFileSizeBytes = 1024, disk = disk)
        try {
            logger.recordEvent(plain(0).copy(title = "A".repeat(950))); assertTrue(logger.flushPendingWrites())
            val original = storage.readBytes()
            logger.recordEvent(plain(1)); assertFalse(logger.flushPendingWrites())
            assertArrayEquals(original, storage.readBytes()); assertEquals(1, logger.healthFlow.value.writeUnconfirmed)
        } finally { logger.shutdown() }
    }
    @Test fun postShutdownEventsAreExplicitlyRejected() {
        val logger = PersistentDicomTechnicalLogger(file())
        assertTrue(logger.shutdown()); logger.recordEvent(plain(0, true))
        assertEquals(1, logger.healthFlow.value.rejectedAfterStop); assertTrue(logger.healthFlow.value.needsAttention)
        assertEquals(1, logger.healthFlow.value.rejectedCriticalAfterStop)
    }
    @Test fun sameShortLegacySessionLabelsDoNotMergeDifferentRecordsOrFiles() {
        val storage = file()
        val legacy = "old-1\t12:00:00.000\tC_ECHO_RSP\tINBOUND\tSCU-1\tfalse\tSuccess\t\tStatus\u001E0x0000\n"
        storage.writeText(legacy.replace("old-1", "old-2")); File(storage.parentFile, "events.1.tsv").writeText(legacy)
        val logger = PersistentDicomTechnicalLogger(storage)
        try {
            assertEquals(2, logger.logsFlow.value.size)
            assertEquals(2, DicomSessionGrouper.groupEvents(logger.logsFlow.value).size)
            assertTrue(DicomSessionGrouper.groupEvents(logger.logsFlow.value).all { it.status == SessionOutcomeStatus.SUCCESS })
            assertEquals(legacy.replace("old-1", "old-2"), storage.readText())
        } finally { logger.shutdown() }
    }
    @Test fun uniqueSessionUidsStillGroupWithUnchangedDisplayLabelsAndRsp() {
        val one = plain(1, true).copy(sessionUid = "run-a", technicalDetails = mapOf("Status" to "0x0000"))
        val two = plain(2, true).copy(sessionUid = "run-b", technicalDetails = mapOf("Status" to "0xC000"))
        val sessions = DicomSessionGrouper.groupEvents(listOf(one, two))
        assertEquals(2, sessions.size); assertTrue(sessions.all { it.associationId == "SCU-1" })
        assertEquals("0x0000", one.technicalDetails["Status"])
    }
    @Test fun specialCharactersRoundTripWithoutMutatingStoredTitleSummaryOrDetails() {
        val storage = file(); val value = "Синтетический\t\\n\n\r\u001F\u001E ☃"
        val original = plain(0).copy(title = value, summaryLines = listOf(value), technicalDetails = mapOf("Custom" to value))
        val logger = PersistentDicomTechnicalLogger(storage)
        logger.recordEvent(original); assertTrue(logger.shutdown())
        val reader = PersistentDicomTechnicalLogger(storage)
        try { assertEquals(original, reader.logsFlow.value.single()) } finally { reader.shutdown() }
    }
    @Test fun malformedOldRowsAreNotRewrittenAndReadFailureIsVisible() {
        val storage = file(); val original = "synthetic broken tail\n"
        storage.writeText(original)
        val logger = PersistentDicomTechnicalLogger(storage)
        try { assertEquals(1, logger.healthFlow.value.readFailures); assertTrue(storage.readText().startsWith(original)) }
        finally { logger.shutdown() }
    }
    @Test fun failedRotationDeleteDoesNotOverwriteOriginals() {
        val storage = file(); val oldest = File(storage.parentFile, "events.4.tsv")
        val retained = "old\t12:00:00.000\tC_ECHO_RSP\tINBOUND\tSCU-1\tfalse\tSuccess\t\tStatus\u001E0x0000\n"
        oldest.writeText(retained)
        val disk = object : FileLoggerDisk() { override fun delete(file: File) { throw IOException("Synthetic delete failure") } }
        val logger = PersistentDicomTechnicalLogger(storage, maxFileSizeBytes = 1024, disk = disk)
        try {
            logger.recordEvent(plain(0).copy(title = "A".repeat(950))); assertTrue(logger.flushPendingWrites())
            val original = storage.readBytes(); logger.recordEvent(plain(1)); assertFalse(logger.flushPendingWrites())
            assertArrayEquals(original, storage.readBytes()); assertEquals(retained, oldest.readText())
        } finally { logger.shutdown() }
    }
    @Test fun clearAndFlushControlSurviveCriticalOverflow() {
        val storage = file(); val disk = HeldDisk(); val logger = PersistentDicomTechnicalLogger(storage, queueCapacity = 1, disk = disk)
        val executor = Executors.newSingleThreadExecutor()
        try {
            logger.recordEvent(plain(0, true)); assertTrue(disk.entered.await(1, TimeUnit.SECONDS))
            logger.recordEvent(plain(1, true))
            val clear = executor.submit<Boolean> { logger.clearLogsAndWait(3000) }
            logger.recordEvent(plain(2, true)); disk.release.countDown()
            assertTrue(clear.get(4, TimeUnit.SECONDS)); assertFalse(logger.flushPendingWrites())
            assertEquals(1, logger.healthFlow.value.droppedCritical)
            assertFalse(storage.readText().contains("event-0")); assertFalse(storage.readText().contains("event-1"))
        } finally { disk.release.countDown(); logger.shutdown(); executor.shutdownNow() }
    }
    @Test fun unexpectedWriterTerminationIsReportedWithInFlightCriticalOutcome() {
        val disk = object : FileLoggerDisk() { override fun append(file: File, bytes: ByteArray) { throw AssertionError("Synthetic worker failure") } }
        val logger = PersistentDicomTechnicalLogger(file(), disk = disk)
        logger.recordEvent(plain(0, true)); assertFalse(logger.shutdown())
        assertEquals(LoggerWriteState.STOPPED, logger.healthFlow.value.state)
        assertEquals(1, logger.healthFlow.value.writeUnconfirmed)
        assertEquals(1, logger.healthFlow.value.criticalWriteUnconfirmed)
        assertTrue(logger.healthFlow.value.needsAttention)
    }
    @Test fun failingDiagnosticMarkerDoesNotDoubleCountOriginalWrite() {
        var calls = 0
        val disk = object : FileLoggerDisk() { override fun append(file: File, bytes: ByteArray) {
            if (++calls == 1) throw IOException("Synthetic original failure") else throw AssertionError("Synthetic marker failure")
        } }
        val logger = PersistentDicomTechnicalLogger(file(), disk = disk)
        logger.recordEvent(plain(0, true)); assertFalse(logger.shutdown())
        assertEquals(1, logger.healthFlow.value.writeUnconfirmed)
        assertEquals(1, logger.healthFlow.value.criticalWriteUnconfirmed)
    }
    @Test fun fatalClearFailureCompletesItsAcknowledgementWithoutSilentTimeout() {
        val disk = object : FileLoggerDisk() { override fun clear(file: File) { throw AssertionError("Synthetic clear failure") } }
        val logger = PersistentDicomTechnicalLogger(file(), disk = disk)
        logger.recordEvent(plain(0)); assertTrue(logger.flushPendingWrites())
        assertFalse(logger.clearLogsAndWait(2000)); assertFalse(logger.shutdown())
        assertEquals(1, logger.healthFlow.value.controlFailures)
        assertEquals(0, logger.healthFlow.value.controlTimeouts)
    }
    @Test fun reservedControlCapacityIsBoundedAndRejectionIsExplicit() {
        val disk = HeldDisk(); val logger = PersistentDicomTechnicalLogger(file(), queueCapacity = 1, disk = disk)
        val executor = Executors.newFixedThreadPool(33)
        try {
            logger.recordEvent(plain(0)); assertTrue(disk.entered.await(1, TimeUnit.SECONDS))
            val start = CountDownLatch(1)
            val flushes = (1..33).map { executor.submit<Boolean> { start.await(); logger.flushPendingWrites(3000) } }
            start.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (logger.healthFlow.value.controlRejected == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, logger.healthFlow.value.controlRejected)
            disk.release.countDown(); flushes.forEach { assertFalse(it.get(4, TimeUnit.SECONDS)) }
            assertEquals(0, logger.healthFlow.value.controlTimeouts)
        } finally { disk.release.countDown(); logger.shutdown(); executor.shutdownNow() }
    }
    @Test fun incompleteModernSelectionCannotClaimWholeSessionSuccess() {
        val outcome = plain(0).copy(category = DicomLogCategory.SERVER_LIFECYCLE,
            technicalDetails = mapOf("SCU File Result" to "SUCCESS", "Status" to "0x0000", "Message ID" to "1"))
        val complete = plain(1).copy(category = DicomLogCategory.SERVER_LIFECYCLE,
            technicalDetails = mapOf("SCU Association Complete" to "true", "Expected File Outcomes" to "2"))
        val summary = DicomSessionGrouper.groupEvents(listOf(outcome, complete)).single()
        assertEquals(SessionOutcomeStatus.UNKNOWN, summary.status)
        assertTrue(summary.filesTransferred.single().isSuccess)
        assertEquals(SessionOutcomeStatus.SUCCESS, DicomSessionGrouper.groupEvents(listOf(outcome,
            complete.copy(technicalDetails = complete.technicalDetails + ("Expected File Outcomes" to "1")))).single().status)
    }
    @Test fun partialRotationFailureRollsBackBeforeExpiringOldestData() {
        val storage = file()
        val originals = (1..4).associate { index ->
            val file = File(storage.parentFile, "events.$index.tsv")
            val line = "old-$index\t12:00:00.000\tC_ECHO_RSP\tINBOUND\tSCU-1\tfalse\tSuccess\t\tStatus\u001E0x0000\n"
            file.writeText(line); file to line
        }
        val disk = object : FileLoggerDisk() { override fun move(from: File, to: File) {
            if (from.name == "events.2.tsv" && to.name == "events.3.tsv") throw IOException("Synthetic partial rotation failure")
            super.move(from, to)
        } }
        val logger = PersistentDicomTechnicalLogger(storage, maxFileSizeBytes = 1024, disk = disk)
        try {
            logger.recordEvent(plain(0).copy(title = "A".repeat(950))); assertTrue(logger.flushPendingWrites())
            val active = storage.readBytes(); logger.recordEvent(plain(1)); assertFalse(logger.flushPendingWrites())
            assertArrayEquals(active, storage.readBytes()); originals.forEach { (file, bytes) -> assertEquals(bytes, file.readText()) }
            assertFalse(logger.rotationBackup().exists())
        } finally { logger.shutdown() }
    }
    @Test fun interruptedRotationBackupIsReadAndFlaggedWithoutAutomaticDeletion() {
        val storage = file(); val backup = File(storage.parentFile, storage.name + ".rotation-retained")
        val original = "old\t12:00:00.000\tC_ECHO_RSP\tINBOUND\tSCU-1\tfalse\tSuccess\t\tStatus\u001E0x0000\n"
        backup.writeText(original)
        val logger = PersistentDicomTechnicalLogger(storage)
        try {
            assertTrue(logger.healthFlow.value.rotationPending)
            assertTrue(logger.logsFlow.value.any { it.id == "old" })
            assertEquals(original, backup.readText())
        } finally { logger.shutdown() }
    }
}
