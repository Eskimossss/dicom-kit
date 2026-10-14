package ru.medeng.dicomtool

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.dcm4che3.data.*
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.net.*
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.AbstractDicomService
import org.dcm4che3.net.service.DicomServiceRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.dicom.scu.*
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.storagefixtures.StorageGrantReceiverActivity
import ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel
import java.io.*
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.*

@RunWith(AndroidJUnit4::class)
class ScuSafetyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun original(ts: String): ByteArray {
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.840.999.501")
            setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.999.500")
            setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.999.502")
            setString(Tag.PatientName, VR.PN, "SYNTHETIC^ANDROID^SCU")
            setInt(Tag.Rows, VR.US, 2); setInt(Tag.Columns, VR.US, 2); setInt(Tag.SamplesPerPixel, VR.US, 1)
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8); setInt(Tag.HighBit, VR.US, 7)
            setInt(Tag.PixelRepresentation, VR.US, 0); setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(1, 2, 3, 4))
        }
        return ByteArrayOutputStream().also { out -> DicomOutputStream(out, UID.ExplicitVRLittleEndian).use {
            it.writeDataset(ds.createFileMetaInformation(ts), ds)
        } }.toByteArray()
    }
    private fun source(bytes: ByteArray): Uri {
        check(context.packageName.endsWith(".storagesafetytest")) { "Use -Pstage51Isolated=true" }
        StorageGrantReceiverActivity.ready = CountDownLatch(1); StorageGrantReceiverActivity.granted = null
        StorageGrantReceiverActivity.error = null
        instrumentation.startActivitySync(Intent(context, StorageGrantReceiverActivity::class.java)
            .putExtra("root", "normal_${UUID.randomUUID()}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(StorageGrantReceiverActivity.ready.await(15, TimeUnit.SECONDS))
        assertNull(StorageGrantReceiverActivity.error)
        val root = DocumentFile.fromTreeUri(context, requireNotNull(StorageGrantReceiverActivity.granted))!!
        assertTrue(root.exists()) // Materialize this synthetic provider root before creating its first child.
        val uri = root.createFile("application/dicom", "synthetic-scu.dcm")!!.uri
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        return uri
    }
    private fun logger() = PersistentDicomTechnicalLogger(File(context.cacheDir, "stage52-${UUID.randomUUID()}.tsv"))
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for Android state", predicate())
    }
    @Test fun contentResolverDeflatedBytesAndOriginalArePreserved() {
        val original = original(UID.DeflatedExplicitVRLittleEndian); val uri = source(original)
        val offset = 144 + (0..3).fold(0) { value, i -> value or ((original[140 + i].toInt() and 255) shl (i * 8)) }
        Peer().use { peer -> val logger = logger(); try {
            val candidate = DicomFileScanner.inspectUri(context, uri)
            val result = DicomScuClient(logger).performCStoreFileBatch(context, "127.0.0.1", peer.port,
                "TEST_SCU", "TEST_PACS", listOf(candidate), { _, _, _, _ -> }, { false })
            assertEquals(result.toString(), 1, result.successCount)
            assertArrayEquals(original.copyOfRange(offset, original.size), peer.received.single())
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally { logger.shutdown() } }
    }
    @Test fun inaccessibleContentUriFailsBeforeSending() {
        val bytes = original(UID.ExplicitVRLittleEndian); val uri = source(bytes)
        val candidate = DicomFileScanner.inspectUri(context, uri)
        assertTrue(DocumentFile.fromSingleUri(context, uri)!!.delete())
        Peer().use { peer -> val logger = logger(); try {
            val result = DicomScuClient(logger).performCStoreFileBatch(context, "127.0.0.1", peer.port,
                "TEST_SCU", "TEST_PACS", listOf(candidate), { _, _, _, _ -> }, { false })
            assertEquals(1, result.failedCount); assertNull(result.fileResults.single().responseStatus)
            assertTrue(peer.received.isEmpty())
        } finally { logger.shutdown() } }
    }
    @Test fun viewModelCancelWaitsForCleanupAndCanRetry() {
        val uri = source(original(UID.ExplicitVRLittleEndian))
        val store = ViewModelStore()
        lateinit var vm: DicomToolViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                .get(DicomToolViewModel::class.java)
            vm.addCandidateFilesFromUris(listOf(uri))
        }
        try {
            await { vm.candidateFiles.value.size == 1 }
            Peer(noResponse = true).use { peer ->
                instrumentation.runOnMainSync {
                    vm.updateScuConfig { it.copy(remoteIp = "127.0.0.1", remotePort = peer.port,
                        callingAeTitle = "TEST_SCU", calledAeTitle = "TEST_PACS") }
                    vm.triggerCStoreFileBatch()
                }
                assertTrue(peer.seen.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    vm.cancelBatchSend()
                    assertTrue(vm.batchProgress.value.isSending)
                    vm.triggerCStoreFileBatch() // Cannot start a replacement while cleanup is pending.
                }
                await { !vm.batchProgress.value.isSending }
                assertFalse(vm.scuConfig.value.isOperationInProgress)
                assertEquals(false, vm.scuConfig.value.isLastOperationSuccess)
                assertTrue(vm.batchProgress.value.isCancelled)
                assertEquals(FileSendStatus.UNCONFIRMED, vm.candidateFiles.value.single().sendStatus)
                assertEquals(1, peer.received.size)
            }
            Peer().use { peer ->
                instrumentation.runOnMainSync {
                    vm.updateScuConfig { it.copy(remotePort = peer.port) }; vm.triggerCStoreFileBatch()
                }
                await { !vm.batchProgress.value.isSending }
                assertEquals(FileSendStatus.SUCCESS, vm.candidateFiles.value.single().sendStatus)
                assertEquals(true, vm.scuConfig.value.isLastOperationSuccess)
            }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
    @Test fun cancelledScanDoesNotPublishIntoNewScan() {
        val uri = source(original(UID.ExplicitVRLittleEndian))
        val tree = requireNotNull(StorageGrantReceiverActivity.granted)
        val store = ViewModelStore()
        lateinit var vm: DicomToolViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                .get(DicomToolViewModel::class.java)
            vm.scanCandidateFolder(tree); vm.cancelFolderScan(); vm.scanCandidateFolder(tree)
        }
        try {
            await { !vm.batchProgress.value.isScanning }
            assertEquals(listOf(uri), vm.candidateFiles.value.map { it.uri })
            assertTrue(vm.batchProgress.value.summaryMessageRu!!.contains("завершено"))
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    private class Peer(val noResponse: Boolean = false) : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val seen = CountDownLatch(1)
        val received = CopyOnWriteArrayList<ByteArray>()
        private val executor = Executors.newCachedThreadPool()
        private val scheduler = Executors.newSingleThreadScheduledExecutor()
        private val device = Device("synthetic-android-pacs")
        init {
            device.executor = executor; device.scheduledExecutor = scheduler
            val conn = Connection().apply { hostname = "127.0.0.1"; this.port = this@Peer.port; socketCloseDelay = 0 }
            device.addConnection(conn)
            device.addApplicationEntity(ApplicationEntity("TEST_PACS").apply {
                isAssociationAcceptor = true; addConnection(conn)
                addTransferCapability(TransferCapability(null, "*", TransferCapability.Role.SCP, "*"))
            })
            device.dimseRQHandler = DicomServiceRegistry().apply { addDicomService(object : AbstractDicomService("*") {
                override fun onDimseRQ(a: Association, pc: PresentationContext, dimse: Dimse, cmd: Attributes, data: Attributes) { error("Streaming expected") }
                override fun onDimseRQ(a: Association, pc: PresentationContext, dimse: Dimse, cmd: Attributes, data: PDVInputStream) {
                    received.add(data.readBytes()); seen.countDown()
                    if (!noResponse) a.writeDimseRSP(pc, Commands.mkCStoreRSP(cmd, 0))
                }
            }) }
            device.bindConnections()
        }
        override fun close() { device.unbindConnections(); executor.shutdownNow(); scheduler.shutdownNow() }
    }
}
