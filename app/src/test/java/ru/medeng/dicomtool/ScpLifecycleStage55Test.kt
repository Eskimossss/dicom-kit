package ru.medeng.dicomtool

import org.dcm4che3.data.*
import org.dcm4che3.net.*
import org.dcm4che3.net.pdu.*
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.data.storage.*
import ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer
import ru.medeng.dicomtool.domain.model.*
import java.io.File
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*

/** Real loopback sockets, real dcm4che DIMSE and entirely synthetic instances. */
class ScpLifecycleStage55Test {
    private val root = Files.createTempDirectory("stage55-").toFile()
    private val repo = LocalDicomStudyRepository(File(root, "dicom"))
    private val logger = PersistentDicomTechnicalLogger(File(root, "events.tsv"))
    private var server = DicomStorageScpServer(repo, logger)
    private val exec = Executors.newCachedThreadPool()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val clients = mutableListOf<Association>()
    private var port = 0
    private fun start() {
        port = ServerSocket(0).use { it.localPort }
        assertTrue(server.startServer("STAGE55", port).isSuccess)
    }
    private fun connect(): Association {
        val device = Device("synthetic-scu")
        val local = Connection().apply { connectTimeout = 2000; responseTimeout = 2000; releaseTimeout = 1000 }
        val ae = ApplicationEntity("SYNTHETIC")
        ae.addConnection(local); device.addConnection(local); device.addApplicationEntity(ae)
        device.executor = exec; device.scheduledExecutor = sched
        val remote = Connection("remote", "127.0.0.1", port)
        val rq = AAssociateRQ().apply {
            callingAET = "SYNTHETIC"; calledAET = "STAGE55"
            addPresentationContext(PresentationContext(1, UID.Verification, UID.ImplicitVRLittleEndian))
            addPresentationContext(PresentationContext(3, UID.SecondaryCaptureImageStorage, UID.ExplicitVRLittleEndian))
        }
        return ae.connect(local, remote, rq).also { clients.add(it) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Timed out", condition())
    }
    private fun echo(a: Association) {
        val rsp = a.cecho(); assertTrue(rsp.next()); assertEquals(Status.Success, rsp.command.getInt(Tag.Status, -1))
    }
    private fun data() = Attributes().apply {
        setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
        setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.55.3")
        setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.55")
        setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.55.2")
        setString(Tag.PatientName, VR.PN, "SYNTHETIC^ONLY")
        setInt(Tag.Rows, VR.US, 1); setInt(Tag.Columns, VR.US, 2)
        setInt(Tag.SamplesPerPixel, VR.US, 1); setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
        setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8)
        setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 0)
        setBytes(Tag.PixelData, VR.OB, byteArrayOf(12, 34))
    }
    private fun store(a: Association, ds: Attributes = data()): Int {
        val rsp = a.cstore(UID.SecondaryCaptureImageStorage, ds.getString(Tag.SOPInstanceUID), 0,
            DataWriterAdapter(ds), UID.ExplicitVRLittleEndian)
        assertTrue(rsp.next()); return rsp.command.getInt(Tag.Status, -1)
    }
    @After fun cleanup() {
        clients.forEach { runCatching { it.socket.close() } }
        server.stopServer(); exec.shutdownNow(); sched.shutdownNow(); logger.shutdown()
        root.deleteRecursively()
    }
    @Test fun nodeHistorySurvivesDisconnectUpdatesTimeAndResetsOnRestart() {
        start(); val first = connect(); echo(first); first.release()
        await { server.state.value.activeConnectionsCount == 0 }
        val before = server.state.value.connectedNodes.single()
        assertTrue(before.contains("SYNTHETIC (127.0.0.1)"))
        assertTrue(before.contains("последнее обращение"))
        Thread.sleep(1100)
        val second = connect(); echo(second); second.release()
        await { server.state.value.activeConnectionsCount == 0 }
        assertNotEquals(before, server.state.value.connectedNodes.single())
        server.stopServer(); await { server.state.value.status == ScpServerStatus.STOPPED }
        start(); assertTrue(server.state.value.connectedNodes.isEmpty())
    }
    @Test fun restartClosesOldAssociationAndReleasesPort() {
        port = ServerSocket(0).use { it.localPort }
        repeat(3) {
            assertTrue(server.startServer("STAGE55", port).isSuccess)
            val a = connect(); echo(a); server.stopServer()
            await { server.state.value.status == ScpServerStatus.STOPPED }
            assertEquals(0, server.state.value.activeConnectionsCount)
            assertTrue(runCatching { echo(a) }.isFailure)
            ServerSocket(port).use { assertTrue(it.isBound) }
        }
    }
    @Test fun stopClosesUnnegotiatedSlowTcpClient() {
        start()
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 4000
            await { server.state.value.activeConnectionsCount == 1 }
            val begin = System.nanoTime(); server.stopServer()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 3500)
            assertEquals(-1, s.getInputStream().read())
        }
        await { server.state.value.status == ScpServerStatus.STOPPED }
    }
    @Test fun parallelConnectionsUseOneIdentityFromTcpThroughDimse() {
        start(); val connections = (1..6).map { connect() }
        connections.map { a -> exec.submit { echo(a); val status = store(a); assertEquals(logger.logsFlow.value.filter { it.isError }.joinToString { it.technicalDetails.toString() }, 0, status) } }.forEach { it.get(10, TimeUnit.SECONDS) }
        connections.forEach { it.release() }
        await { server.state.value.activeConnectionsCount == 0 }
        val logs = logger.logsFlow.value
        val tcp = logs.filter { it.category == DicomLogCategory.TCP_CONNECT }
        assertEquals(6, tcp.map { it.sessionUid }.distinct().size)
        tcp.forEach { e ->
            assertTrue(e.associationId.startsWith("SCP-"))
            val group = logs.filter { it.sessionUid == e.sessionUid }
            assertEquals(1, group.count { it.category == DicomLogCategory.ASSOCIATE_RQ })
            assertEquals(1, group.count { it.category == DicomLogCategory.C_ECHO_RSP })
            assertEquals(1, group.count { it.category == DicomLogCategory.C_STORE_RSP })
        }
        assertFalse(logs.any { it.category == DicomLogCategory.RELEASE_RP || it.category == DicomLogCategory.RELEASE_RQ })
    }
    @Test fun parallelDifferentPixelsWithSameUidPreserveEveryObjectAndLeaveNoParts() {
        start()
        repeat(3) { batch ->
            val connections = (0 until 8).map { connect() }
            connections.mapIndexed { n, a -> exec.submit {
                val ds = data().apply { setBytes(Tag.PixelData, VR.OB, byteArrayOf((batch * 8 + n).toByte(), 42)) }
                assertEquals(0, store(a, ds))
            } }.forEach { it.get(15, TimeUnit.SECONDS) }
            connections.forEach { it.release() }
            await { server.state.value.activeConnectionsCount == 0 }
        }
        val files = repo.storageRootDir.walkTopDown().filter { it.isFile && it.extension == "dcm" }.toList()
        assertEquals(24, files.size)
        val pixels = files.map { file -> org.dcm4che3.io.DicomInputStream(file).use { input ->
            input.readFileMetaInformation(); input.readDataset().getBytes(Tag.PixelData)[0].toInt()
        } }.toSet()
        assertEquals((0 until 24).toSet(), pixels)
        assertEquals(24, repo.getTotalSavedFilesCount())
        assertTrue(File(repo.storageRootDir, ".incoming_tmp").listFiles().orEmpty().isEmpty())
    }
    @Test fun shortInstanceUidCannotViolateTemporaryFilePrefixContract() {
        start(); val a = connect()
        assertEquals(0, store(a, data().apply { setString(Tag.SOPInstanceUID, VR.UI, "1") }))
        assertEquals(1, repo.getTotalSavedFilesCount())
        assertTrue(File(repo.storageRootDir, ".incoming_tmp").listFiles().orEmpty().isEmpty())
    }
    @Test fun reusedSourcePortGetsNewIdentity() {
        start()
        val sourcePort = ServerSocket(0).use { it.localPort }
        repeat(3) {
            Socket().use { s ->
                s.reuseAddress = true; s.bind(InetSocketAddress("127.0.0.1", sourcePort))
                s.connect(InetSocketAddress("127.0.0.1", port)); s.setSoLinger(true, 0)
                await { logger.logsFlow.value.count { it.category == DicomLogCategory.TCP_CONNECT } == it + 1 }
            }
            await { server.state.value.activeConnectionsCount == 0 }
        }
        assertEquals(3, logger.logsFlow.value.filter { it.category == DicomLogCategory.TCP_CONNECT }.map { it.sessionUid }.distinct().size)
    }
    @Test fun resourceFailurePreservesStatusAndCompletePart() {
        server = DicomStorageScpServer(object : DicomStudyRepository by repo {
            override fun saveIncomingInstance(tempIncomingFile: File, studyInstanceUid: String, seriesInstanceUid: String, sopInstanceUid: String) =
                DicomSaveResult.Failure("Synthetic disk full", isOutOfSpace = true)
        }, logger)
        start(); val a = connect(); assertEquals(Status.OutOfResources, store(a))
        a.release(); await { server.state.value.activeConnectionsCount == 0 }
        assertEquals(1, File(repo.storageRootDir, ".incoming_tmp").listFiles()!!.size)
        val rsp = logger.logsFlow.value.single { it.category == DicomLogCategory.C_STORE_RSP }
        assertEquals("0xA700", rsp.technicalDetails["DICOM Status"])
        assertTrue(rsp.isError)
        assertFalse(logger.logsFlow.value.any { it.technicalDetails["DICOM Status"]?.startsWith("0x0110") == true })
    }
    @Test fun stoppingDuringStorageWaitsForActualCleanupAndKeepsPayload() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        server = DicomStorageScpServer(object : DicomStudyRepository by repo {
            override fun saveIncomingInstance(tempIncomingFile: File, studyInstanceUid: String, seriesInstanceUid: String, sopInstanceUid: String): DicomSaveResult {
                entered.countDown()
                while (finish.count > 0) try { finish.await() } catch (_: InterruptedException) { /* synthetic uninterruptible provider */ }
                return DicomSaveResult.Failure("Synthetic retained source")
            }
        }, logger)
        start(); val a = connect()
        val send = exec.submit { runCatching { store(a) } }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            assertEquals(0, RetainedIncoming.inspect(repo.storageRootDir).filesCount)
            server.stopServer()
            assertEquals(ScpServerStatus.STOPPING, server.state.value.status)
            assertTrue(server.startServer("STAGE55", port).isFailure)
            assertEquals(1, File(repo.storageRootDir, ".incoming_tmp").listFiles()!!.size)
        } finally { finish.countDown() }
        send.get(5, TimeUnit.SECONDS)
        await { server.state.value.status == ScpServerStatus.STOPPED }
        assertFalse(logger.logsFlow.value.any { it.category == DicomLogCategory.C_STORE_RSP })
        assertTrue(logger.logsFlow.value.any { it.technicalDetails["Response Write"] == "FAILED" })
        val summary = DicomSessionGrouper.groupEvents(logger.logsFlow.value).first { it.primaryOperation == "C-STORE" }
        assertEquals(SessionOutcomeStatus.UNKNOWN, summary.status)
        assertEquals("UNCONFIRMED", summary.filesTransferred.single().scuOutcome)
        assertEquals(1, RetainedIncoming.inspect(repo.storageRootDir).filesCount)
        start(); echo(connect())
    }
    @Test fun peerAbortDoesNotInventReleaseResponse() {
        start(); val a = connect(); echo(a); a.abort()
        await { server.state.value.activeConnectionsCount == 0 }
        assertFalse(logger.logsFlow.value.any { it.category == DicomLogCategory.RELEASE_RP })
        assertTrue(logger.logsFlow.value.any { it.category == DicomLogCategory.TCP_DISCONNECT })
        assertTrue(logger.logsFlow.value.any { it.category == DicomLogCategory.ABORT })
        assertEquals(SessionOutcomeStatus.WARNING, DicomSessionGrouper.groupEvents(logger.logsFlow.value)
            .first { it.primaryOperation == "C-ECHO" }.status)
    }
    @Test fun bindFailureDoesNotLeakAndCanRetry() {
        ServerSocket(0).use { blocked -> assertTrue(server.startServer("STAGE55", blocked.localPort).isFailure) }
        await { server.state.value.status != ScpServerStatus.STOPPING }
        start(); echo(connect())
    }
    @Test fun sameSerialAndPortNeverProveSessionIdentity() {
        val a = DicomSessionManager.nextScpSession(77); val b = DicomSessionManager.nextScpSession(77)
        assertNotEquals(a.sessionUid, b.sessionUid)
        assertNotEquals(DicomSessionManager.scpTcpSession(1000).sessionUid, DicomSessionManager.scpTcpSession(1000).sessionUid)
    }
    @Test fun severalStoresOnOneAssociationKeepOneSessionAndAllResponses() {
        start(); val a = connect()
        repeat(4) { assertEquals(0, store(a)) }
        echo(a); a.release(); await { server.state.value.activeConnectionsCount == 0 }
        val logs = logger.logsFlow.value
        val uid = logs.single { it.category == DicomLogCategory.TCP_CONNECT }.sessionUid
        assertEquals(4, logs.count { it.sessionUid == uid && it.category == DicomLogCategory.C_STORE_RSP })
        val summary = DicomSessionGrouper.groupEvents(logs).single { it.sessionUid == uid }
        assertEquals(4, summary.filesTransferred.size)
        assertEquals(SessionOutcomeStatus.SUCCESS, summary.status)
        assertTrue(summary.statusSubtitle.contains("не подтверждено"))
    }
    @Test fun stopDuringEchoLoopEndsOperationAndPermitsRetry() {
        start(); val a = connect(); val began = CountDownLatch(1)
        val send = exec.submit {
            began.countDown()
            runCatching { repeat(300) { echo(a) } }
        }
        assertTrue(began.await(2, TimeUnit.SECONDS)); server.stopServer()
        send.get(4, TimeUnit.SECONDS)
        await { server.state.value.status == ScpServerStatus.STOPPED }
        assertTrue(runCatching { echo(a) }.isFailure)
        start(); echo(connect())
    }
    @Test fun failedResponseAfterVerifiedSaveDoesNotUndoSavedInstanceOrClaimPeerSuccess() {
        val saved = CountDownLatch(1); val finish = CountDownLatch(1)
        server = DicomStorageScpServer(object : DicomStudyRepository by repo {
            override fun saveIncomingInstance(tempIncomingFile: File, studyInstanceUid: String, seriesInstanceUid: String, sopInstanceUid: String): DicomSaveResult {
                val result = repo.saveIncomingInstance(tempIncomingFile, studyInstanceUid, seriesInstanceUid, sopInstanceUid)
                saved.countDown(); assertTrue(finish.await(4, TimeUnit.SECONDS)); return result
            }
        }, logger)
        start(); val a = connect(); val send = exec.submit { runCatching { store(a) } }
        try {
            assertTrue(saved.await(4, TimeUnit.SECONDS)); a.socket.setSoLinger(true, 0); a.socket.close()
        } finally { finish.countDown() }
        send.get(5, TimeUnit.SECONDS)
        await { server.state.value.activeConnectionsCount == 0 }
        val file = repo.storageRootDir.walkTopDown().single { it.isFile && it.extension == "dcm" }
        org.dcm4che3.io.DicomInputStream(file).use { input ->
            input.readFileMetaInformation(); assertArrayEquals(byteArrayOf(12, 34), input.readDataset().getBytes(Tag.PixelData))
        }
        assertTrue(logger.logsFlow.value.any { it.technicalDetails["Response Write"] == "FAILED" })
        assertFalse(logger.logsFlow.value.any { it.category == DicomLogCategory.C_STORE_RSP })
        assertEquals(SessionOutcomeStatus.UNKNOWN, DicomSessionGrouper.groupEvents(logger.logsFlow.value)
            .first { it.primaryOperation == "C-STORE" }.status)
    }
    @Test fun osStopCanCloseListenerWhileStartupIndexingIsBlocked() {
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        server = DicomStorageScpServer(object : DicomStudyRepository by repo {
            override fun refreshFromDisk() {
                if (block.get()) { entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)) }
                repo.refreshFromDisk()
            }
        }, logger)
        block.set(true); port = ServerSocket(0).use { it.localPort }
        val start = exec.submit<Result<Unit>> { server.startServer("STAGE55", port) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            server.requestNetworkStop()
            assertEquals(ScpServerStatus.STOPPING, server.state.value.status)
            assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        } finally { block.set(false); finish.countDown() }
        assertTrue(start.get(5, TimeUnit.SECONDS).isFailure)
        await { server.state.value.status != ScpServerStatus.STOPPING }
        start(); echo(connect())
    }
    @Test fun parallelChurnDoesNotAccumulateLiveConnections() {
        repeat(10) {
            start()
            val group = (1..6).map { connect() }
            group.map { a -> exec.submit { repeat(2) { echo(a); assertEquals(0, store(a)) }; a.release() } }
                .forEach { it.get(10, TimeUnit.SECONDS) }
            await { server.state.value.activeConnectionsCount == 0 }
            server.stopServer()
            await { server.state.value.status == ScpServerStatus.STOPPED }
        }
        assertEquals(60, logger.logsFlow.value.count { it.category == DicomLogCategory.TCP_CONNECT })
        assertEquals(60, logger.logsFlow.value.count { it.category == DicomLogCategory.TCP_DISCONNECT })
        assertEquals(120, logger.logsFlow.value.count { it.category == DicomLogCategory.C_STORE_RSP })
    }
}
