package ru.medeng.dicomtool

import org.junit.*
import org.junit.Assert.*
import org.dcm4che3.data.*
import org.dcm4che3.net.*
import org.dcm4che3.net.pdu.*
import org.dcm4che3.net.service.*
import ru.medeng.dicomtool.mwl.engine.MwlDatasetBuilder
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.*
import ru.medeng.dicomtool.mwl.scp.MwlScpServer
import ru.medeng.dicomtool.mwl.scu.MwlScuClient
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class MwlAuditNetworkRegressionTest {
    private val dir = Files.createTempDirectory("mwl-audit-net").toFile()
    private val logger = MwlTechnicalLogger(File(dir, "log.tsv"))
    private val repo = LocalMwlRepository(File(dir, "data"), false)
    private val resources = mutableListOf<AutoCloseable>()
    private fun freePort() = ServerSocket(0).use { it.localPort }
    private val filter = MwlScuFilter(dateOption = MwlDateFilterOption.ANY)
    private fun profile(port: Int, timeout: Int = 1000) = MwlScuProfile(name = "Audit", host = "127.0.0.1", port = port, calledAet = "AUDIT_SCP", callingAet = "AUDIT_SCU", connectionTimeoutMs = timeout, responseTimeoutMs = timeout)
    @Before fun seed() { repo.addSyntheticBatch(listOf(SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY))) }
    @After fun cleanup() { resources.reversed().forEach { runCatching { it.close() } }; logger.close(); dir.deleteRecursively() }
    private fun start(repository: MwlRepository = repo, charset: String = "ISO_IR 192", whitelist: Boolean = false): Pair<MwlScpServer, Int> {
        val port = freePort()
        val server = MwlScpServer(repository, logger)
        server.updateConfiguration(aeTitle = "AUDIT_SCP", port = port, characterSet = charset, whitelistMode = whitelist, allowedCallingAets = if (whitelist) listOf("ALLOWED") else emptyList())
        assertTrue(server.startServer().isSuccess)
        resources.add(AutoCloseable { server.stopServer() })
        return server to port
    }
    private fun await(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!test() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(test())
    }
    private inner class Peer(port: Int, aet: String = "AUDIT_SCP", val ts: String = UID.ImplicitVRLittleEndian) : AutoCloseable {
        val device = Device("audit-peer")
        private val executor = Executors.newCachedThreadPool()
        private val scheduler = Executors.newSingleThreadScheduledExecutor()
        val association: Association
        init {
            resources.add(this)
            val conn = Connection().apply { connectTimeout = 1000; acceptTimeout = 1000; responseTimeout = 3000; releaseTimeout = 1000; socketCloseDelay = 0 }
            val ae = ApplicationEntity("AUDIT_SCU").apply { isAssociationInitiator = true; addConnection(conn) }
            device.addConnection(conn); device.addApplicationEntity(ae); device.executor = executor; device.scheduledExecutor = scheduler
            val rq = AAssociateRQ().apply { callingAET = "AUDIT_SCU"; calledAET = aet; addPresentationContext(PresentationContext(1, UID.ModalityWorklistInformationModelFind, ts)); addPresentationContext(PresentationContext(3, UID.Verification, ts)) }
            try { association = ae.connect(conn, Connection().apply { hostname = "127.0.0.1"; this.port = port }, rq) }
            catch (e: Exception) { executor.shutdownNow(); scheduler.shutdownNow(); throw e }
        }
        fun find(keys: Attributes = MwlDatasetBuilder.buildMwlScuQueryDataset()): DimseRSP = association.cfind(UID.ModalityWorklistInformationModelFind, 0, keys, ts, 0)
        override fun close() {

            runCatching { if (association.isReadyForDataTransfer) { association.release(); association.waitForSocketClose() } }
            executor.shutdownNow(); scheduler.shutdownNow()
        }
    }
    private fun statuses(rsp: DimseRSP): List<Int> = buildList { while (rsp.next()) add(rsp.command.getInt(Tag.Status, -1)) }
    private fun fake(handler: (Association, PresentationContext, Attributes) -> Unit): Int {
        val port = freePort(); val d = Device("independent-audit-scp")
        val ex = Executors.newCachedThreadPool(); val sch = Executors.newSingleThreadScheduledExecutor()
        val c = Connection("listen", "127.0.0.1", port).apply { releaseTimeout = 1000; socketCloseDelay = 0 }
        val ae = ApplicationEntity("AUDIT_SCP").apply { isAssociationAcceptor = true; addConnection(c); addTransferCapability(TransferCapability(null, UID.ModalityWorklistInformationModelFind, TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian)) }
        d.addConnection(c); d.addApplicationEntity(ae); d.executor = ex; d.scheduledExecutor = sch
        val registry = DicomServiceRegistry()
        registry.addDicomService(object : BasicCFindSCP(UID.ModalityWorklistInformationModelFind) {
            override fun onDimseRQ(a: Association, pc: PresentationContext, dimse: Dimse, cmd: Attributes, keys: Attributes) { handler(a, pc, cmd) }
        })
        d.dimseRQHandler = registry; d.bindConnections()
        resources.add(AutoCloseable { d.unbindConnections(); d.listOpenAssociations().forEach { it.abort() }; ex.shutdownNow(); sch.shutdownNow() })
        return port
    }
    @Test fun audit01_cancelIsAssociationScopedEvenWithIdenticalMessageIds() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val requests = AtomicInteger()
        val slow = object : MwlRepository by repo {
            override fun snapshot(): MwlSnapshot {
                if (requests.incrementAndGet() == 1) { entered.countDown(); check(unblock.await(5, TimeUnit.SECONDS)) }
                return repo.snapshot()
            }
        }
        val (_, port) = start(slow)
        try {
            val a = Peer(port); val pendingA = a.find()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val b = Peer(port); val pendingB = b.find()
            pendingA.cancel(a.association)
            await { logger.logsFlow.value.any { it.title == "C-CANCEL-FIND received" } }
            unblock.countDown()
            assertEquals(listOf(Status.Cancel), statuses(pendingA))
            assertEquals(listOf(Status.Pending, Status.Success), statuses(pendingB))
        } finally { unblock.countDown() }
    }
    @Test fun audit01_dcmtkCancelStopsActiveFindWithFe00() {
        val executable = File("C:\\DCMTK\\bin\\findscu.exe")
        Assume.assumeTrue("DCMTK is unavailable", executable.isFile)
        val unblock = CountDownLatch(1)
        val study = repo.getAllStudies().single()
        val slow = object : MwlRepository by repo {
            override fun snapshot() = MwlSnapshot(repo.getAllPatients(), object : AbstractList<MwlScheduledStudy>() {
                override val size = 10
                override fun get(index: Int): MwlScheduledStudy {
                    if (index == 1) check(unblock.await(5, TimeUnit.SECONDS))
                    return study
                }
            })
        }
        val (_, port) = start(slow)
        val output = File(dir, "dcmtk-cancel.txt")
        val process = ProcessBuilder(executable.path, "-v", "-W", "--cancel", "1", "-ta", "3", "-td", "3", "-k", "0010,0020", "-k", "0040,0100[0].0008,0060", "-aec", "AUDIT_SCP", "127.0.0.1", port.toString()).redirectErrorStream(true).redirectOutput(output).start()
        try {
            await { logger.logsFlow.value.any { it.title == "C-CANCEL-FIND received" } }
            unblock.countDown()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val text = output.readText()
            println(text)
            assertEquals(0, process.exitValue())
            assertTrue(text.contains("Received Final Find Response (Cancel"))
            assertEquals(1, Regex("Find Response: [0-9]+ \\(Pending").findAll(text).count())
        } finally { unblock.countDown(); if (process.isAlive) process.destroyForcibly() }
    }
    @Test fun audit02_failureRetainsPartialResultsAndNeverBecomesSuccess() {
        val ds = MwlDatasetBuilder.buildMwlResponseDataset(repo.getAllPatients().single(), repo.getAllStudies().single())
        val port = fake { a, pc, cmd -> a.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Pending), ds); a.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, 0xA700)) }
        val result = MwlScuClient(logger).queryWorklist(profile(port), filter)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as MwlQueryException
        assertEquals(0xA700, error.dimseStatus)
        assertEquals(1, error.partialResults.size)
    }
    @Test fun audit02_cancelIsActuallySentAndRequiresRemoteConfirmation() {
        val request = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val port = fake { a, pc, cmd ->
            val id = cmd.getInt(Tag.MessageID, 0)
            a.addCancelRQHandler(id) { cancelled.countDown() }
            a.applicationEntity.device.execute {
                try { request.countDown(); check(cancelled.await(3, TimeUnit.SECONDS)); a.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Cancel)) }
                finally { a.removeCancelRQHandler(id) }
            }
        }
        val client = MwlScuClient(logger); val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Result<MwlQueryOutcome>> { client.queryWorklist(profile(port, 2000), filter) }
            assertTrue(request.await(2, TimeUnit.SECONDS)); client.cancelQuery()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            val outcome = future.get(3, TimeUnit.SECONDS).getOrThrow()
            assertTrue(outcome.cancelConfirmed); assertFalse(outcome.completedSuccessfully)
        } finally { executor.shutdownNow() }
    }
    @Test fun audit02_missingStatusCannotConfirmSuccess() {
        val port = fake { a, pc, cmd ->
            val bad = Commands.mkCFindRSP(cmd, Status.Success).apply { remove(Tag.Status) }
            a.writeDimseRSP(pc, bad)
        }
        assertTrue(MwlScuClient(logger).queryWorklist(profile(port), filter).isFailure)
    }
    @Test fun audit09_pendingWithoutFinalTimesOutAndKeepsPartialRecords() {
        val ds = MwlDatasetBuilder.buildMwlResponseDataset(repo.getAllPatients().single(), repo.getAllStudies().single())
        val port = fake { a, pc, cmd -> a.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Pending), ds) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Result<MwlQueryOutcome>> { MwlScuClient(logger).queryWorklist(profile(port, 200), filter) }.get(3, TimeUnit.SECONDS)
            assertTrue(result.isFailure)
            assertEquals(1, (result.exceptionOrNull() as MwlQueryException).partialResults.size)
        } finally { executor.shutdownNow() }
    }
    @Test fun audit07_whitelistSendsRjWithCallingAeReason() {
        val (_, port) = start(whitelist = true)
        val rejection = assertThrows(AAssociateRJ::class.java) { Peer(port) }
        assertEquals(AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED, rejection.reason)
        assertEquals(AAssociateRJ.SOURCE_SERVICE_USER, rejection.source)
    }
    @Test fun audit09_associationBlackholeTimesOutAndCanBeCancelled() {
        val listener = ServerSocket(0); resources.add(listener)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = MwlScuClient(logger)
            val timed = executor.submit<Result<MwlQueryOutcome>> { client.queryWorklist(profile(listener.localPort, 200), filter) }
            listener.accept().use { assertTrue(timed.get(2, TimeUnit.SECONDS).isFailure) }
            val cancelled = executor.submit<Result<MwlQueryOutcome>> { client.queryWorklist(profile(listener.localPort, 5000), filter) }
            listener.accept().use {
                client.cancelQuery()
                val outcome = cancelled.get(2, TimeUnit.SECONDS).getOrThrow()
                assertTrue(outcome.cancelRequested); assertFalse(outcome.cancelConfirmed); assertFalse(outcome.completedSuccessfully)
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun audit10_malformedSpsReturnsFailureAndNoPending() {
        val (_, port) = start()
        val keys = MwlDatasetBuilder.buildMwlScuQueryDataset()
        keys.getSequence(Tag.ScheduledProcedureStepSequence).add(Attributes())
        assertEquals(listOf(Status.IdentifierDoesNotMatchSOPClass), statuses(Peer(port).find(keys)))
    }
    @Test fun audit04_unrepresentableResponseReturnsFailureNotCorruptedPending() {
        val (_, port) = start(charset = "ISO_IR 100")
        val result = statuses(Peer(port).find())
        assertEquals(1, result.size); assertFalse(Status.isPending(result.single())); assertNotEquals(Status.Success, result.single())
    }
    @Test fun audit13_scuPreservesUnknownAttributesAndNestedSequences() {
        val ds = MwlDatasetBuilder.buildMwlResponseDataset(repo.getAllPatients().single(), repo.getAllStudies().single())
        ds.setString(0x77770010, VR.LO, "UNKNOWN")
        ds.newSequence(Tag.RequestedProcedureCodeSequence, 1).add(Attributes().apply { setString(Tag.CodeValue, VR.SH, "PRIVATE-CODE") })
        val port = fake { a, pc, cmd -> a.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Pending), ds); a.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Success)) }
        val outcome = MwlScuClient(logger).queryWorklist(profile(port), filter).getOrThrow()
        assertEquals("UNKNOWN", outcome.single().rawAttributes!!.getString(0x77770010))
        assertEquals("PRIVATE-CODE", outcome.single().rawAttributes!!.getNestedDataset(Tag.RequestedProcedureCodeSequence).getString(Tag.CodeValue))
    }
    @Test fun lifecycleEchoAbortNetworkRefreshAndPortReleaseRemainIndependent() {
        val (server, port) = start()
        val echo = Peer(port).association.cecho()
        assertTrue(echo.next()); assertEquals(Status.Success, echo.command.getInt(Tag.Status, -1))
        val aborted = Peer(port); aborted.association.abort()
        await { server.state.value.activeConnectionsCount <= 1 }
        server.refreshNetworkInfo()
        assertEquals(listOf(Status.Pending, Status.Success), statuses(Peer(port, ts = UID.ExplicitVRLittleEndian).find()))
        server.stopServer()
        ServerSocket(port).close()
    }
}

