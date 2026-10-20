package ru.medeng.dicomtool

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.data.storage.*
import ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer
import ru.medeng.dicomtool.dicom.scu.ScuOperationGate
import ru.medeng.dicomtool.storagefixtures.StorageGrantReceiverActivity
import ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.*

class ArchiveLifecycleStage56Test {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < until) Thread.sleep(20)
        assertTrue("Timed out", condition())
    }
    @Before fun guard() { check(context.packageName.endsWith(".storagesafetytest")) }

    @Test fun slowSafQueryKeepsMainResponsiveAndArchiveWorkSurvivesActivityRecreation() {
        val rootId = "stage56query_${UUID.randomUUID()}"
        StorageGrantReceiverActivity.ready = CountDownLatch(1)
        StorageGrantReceiverActivity.granted = null; StorageGrantReceiverActivity.error = null
        instrumentation.startActivitySync(Intent(context, StorageGrantReceiverActivity::class.java)
            .putExtra("root", rootId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(StorageGrantReceiverActivity.ready.await(15, TimeUnit.SECONDS))
        assertNull(StorageGrantReceiverActivity.error)
        val uri = requireNotNull(StorageGrantReceiverActivity.granted)
        assertTrue(DocumentFile.fromTreeUri(context, uri)!!.exists())
        val root = File(context.cacheDir, "stage56-${UUID.randomUUID()}")
        val manager = DicomStorageManager(context, root)
        assertTrue(manager.setCustomSafStorage(uri).isSuccess)
        val repo = LocalDicomStudyRepository(root, context, manager, eagerLoad = false)
        val logger = PersistentDicomTechnicalLogger(File(root, "events.tsv"), deferHistoryLoad = true)
        val server = DicomStorageScpServer(repo, logger)
        context.contentResolver.call(uri, "stage56-block", rootId, null)
        val operations = ArchiveOperations(repo, server)
        try {
            await { context.contentResolver.call(uri, "stage56-status", rootId, null)?.getBoolean("started") == true }
            assertTrue(operations.busy.value); assertTrue(repo.indexState.value.isLoading)
            val retained = File(root, ".incoming_tmp/synthetic-retained.part").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            // Even while SAF is blocked, the Stage 5.1 warning must remain live.
            await { operations.info.value.retainedIncoming.filesCount == 1 }
            assertEquals(4L, operations.info.value.retainedIncoming.totalBytes)
            assertNotNull(operations.info.value.retainedIncoming.warningMessage)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), retained.readBytes())
            val delays = (1..10).map {
                val ack = CountDownLatch(1); val begin = System.nanoTime()
                Handler(Looper.getMainLooper()).post { ack.countDown() }
                assertTrue("Main blocked by provider query", ack.await(2, TimeUnit.SECONDS))
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
            }
            android.util.Log.i("STAGE56", "blocked_saf_main_ping_ms=$delays")
            compose.activityRule.scenario.recreate()
            assertTrue(operations.busy.value)
            context.contentResolver.call(uri, "stage56-release", rootId, null)
            await { !operations.busy.value }
            assertFalse(repo.indexState.value.isLoading)
            assertEquals(StorageType.SAF, operations.info.value.type)
            assertEquals(1, operations.info.value.retainedIncoming.filesCount)
            // Losing a persisted grant must remove inaccessible entries and make the warning explicit.
            context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            operations.refresh(); await { !operations.busy.value }
            assertNotNull(repo.indexState.value.warning)
            assertTrue(repo.studiesFlow.value.isEmpty())
        } finally {
            context.contentResolver.call(uri, "stage56-release", rootId, null)
            manager.resetToInternalStorage(); operations.closeForTesting(); logger.shutdown()
        }
    }
    @Test fun clearedViewModelCannotLetNewViewModelOverlapBlockedAssociation() {
        val stores = mutableListOf<ViewModelStore>()
        fun model(): Pair<ViewModelStore, DicomToolViewModel> {
            val store = ViewModelStore().also { stores.add(it) }
            return store to ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))
                .get(DicomToolViewModel::class.java)
        }
        ServerSocket(0).use { peer ->
            peer.soTimeout = 10000
            lateinit var old: Pair<ViewModelStore, DicomToolViewModel>
            compose.runOnIdle {
                old = model()
                old.second.updateScuConfig { it.copy(remoteIp = "127.0.0.1", remotePort = peer.localPort, callingAeTitle = "SYNTHETIC56", calledAeTitle = "TEST_PACS") }
                old.second.triggerCEchoScu()
            }
            peer.accept().use { socket ->
                socket.soTimeout = 5000
                assertTrue(socket.getInputStream().read() >= 0) // An actual A-ASSOCIATE has started.
                val lease = requireNotNull(ScuOperationGate.active.value)
                lateinit var next: DicomToolViewModel
                compose.runOnIdle { old.first.clear(); next = model().second }
                await { next.scuConfig.value.isOperationInProgress }
                compose.runOnIdle { next.triggerCEchoScu() }
                assertSame(lease, ScuOperationGate.active.value)
                socket.close() // Release the deliberately nonresponsive peer.
                await { ScuOperationGate.active.value == null && !next.scuConfig.value.isOperationInProgress }
                assertNull(next.scuConfig.value.isLastOperationSuccess)
            }
        }
        compose.runOnIdle { stores.forEach { it.clear() } }
    }
}
