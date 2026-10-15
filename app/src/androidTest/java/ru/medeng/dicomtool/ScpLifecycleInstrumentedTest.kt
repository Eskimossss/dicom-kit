package ru.medeng.dicomtool

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.dcm4che3.net.Association
import org.dcm4che3.net.Device
import org.dcm4che3.net.Connection
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.DataWriterAdapter
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.data.UID
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputStream
import ru.medeng.dicomtool.dicom.scu.DicomScuClient
import ru.medeng.dicomtool.dicom.scu.ScuOperationResult
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry
import ru.medeng.dicomtool.dicom.scp.DicomScpForegroundService
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Runs only in the isolated synthetic app, never clears the regular installation. */
class ScpLifecycleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val server get() = DicomRuntimeRegistry.getScpServer(context)
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var port = 0
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun activeWakeLocks(): String = shell("dumpsys power").substringAfter("Wake Locks:", "").substringBefore("Suspend Blockers:")
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        while (!condition() && System.nanoTime() < end) Thread.sleep(50)
        assertTrue("Timed out: ${server.state.value}", condition())
    }
    @Before fun prepare() {
        check(context.packageName.endsWith(".storagesafetytest"))
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }
    private fun start() {
        port = ServerSocket(0).use { it.localPort }
        compose.runOnIdle { DicomScpForegroundService.startScpService(context, "ANDROID_SYNTH55", port) }
        await { server.state.value.status == ScpServerStatus.RUNNING && nm.activeNotifications.any { it.id == 11112 } }
    }
    private fun stop() {
        compose.runOnIdle { DicomScpForegroundService.stopScpService(context) }
        await { server.state.value.status in listOf(ScpServerStatus.STOPPED, ScpServerStatus.ERROR) && !server.state.value.serviceStopping && nm.activeNotifications.none { it.id == 11112 } }
    }
    @After fun cleanup() {
        shell("input keyevent KEYCODE_WAKEUP")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        if (server.state.value.status == ScpServerStatus.RUNNING) stop()
    }
    @Test fun notificationStopClosesClientAndReleasesLocksThenRestarts() {
        start()
        await { activeWakeLocks().contains("DICOMTool:ScpWakeLock") }
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            await { server.state.value.activeConnectionsCount == 1 }
            val notification = nm.activeNotifications.single { it.id == 11112 }.notification
            notification.actions.single { it.title.toString() == "Остановить" }.actionIntent.send()
            await { server.state.value.status == ScpServerStatus.STOPPED && !server.state.value.serviceStopping && nm.activeNotifications.none { it.id == 11112 } }
            assertEquals(-1, socket.getInputStream().read())
        }
        assertFalse(activeWakeLocks().contains("DICOMTool:ScpWakeLock"))
        // WifiLock active list is distinct from historical battery attribution in dumpsys wifi.
        val wifi = shell("dumpsys wifi")
        val active = wifi.substringAfter("Locks held:", "").substringBefore("WifiMulticastLockManager")
        assertFalse(active.contains("DICOMTool:ScpWifiLock"))
        start(); stop()
    }
    @Test fun recreationAndRotationKeepSameLiveListener() {
        start(); val identity = server
        compose.activityRule.scenario.recreate()
        assertSame(identity, server)
        val old = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            assertSame(identity, server); assertEquals(ScpServerStatus.RUNNING, server.state.value.status)
            Socket("127.0.0.1", port).close()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = old }
            await { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            instrumentation.waitForIdleSync()
        }
        stop()
    }
    @Test fun backgroundAndScreenLockRetainListenerThenReturn() {
        start()
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            shell("input keyevent KEYCODE_SLEEP"); Thread.sleep(1000)
            assertEquals(ScpServerStatus.RUNNING, server.state.value.status)
            Socket("127.0.0.1", port).close()
            assertTrue(activeWakeLocks().contains("DICOMTool:ScpWakeLock"))
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            instrumentation.waitForIdleSync()
        }
        stop()
    }
    @Test fun android15SystemTimeoutStopsWithoutAutomaticRestart() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 35)
        val old = shell("device_config get activity_manager data_sync_fgs_timeout_duration").trim()
        try {
            shell("device_config put activity_manager data_sync_fgs_timeout_duration 2000")
            start()
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { server.state.value.errorMessage?.contains("Android остановил dataSync") == true }
            await { server.state.value.status == ScpServerStatus.STOPPED && !server.state.value.serviceStopping && nm.activeNotifications.none { it.id == 11112 } }
            assertFalse(activeWakeLocks().contains("DICOMTool:ScpWakeLock"))
            assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        } finally {
            if (old == "null" || old.isBlank()) shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
            else shell("device_config put activity_manager data_sync_fgs_timeout_duration $old")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            instrumentation.waitForIdleSync()
        }
    }
    @Test fun configurationSurvivesActivityRecreationWithoutStartingListener() {
        compose.runOnIdle {
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity).get(ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel::class.java)
            vm.updateScpAeTitleInput("SYNTHETIC55")
            vm.updateScpPortInput("55155")
        }
        compose.activityRule.scenario.recreate()
        val prefs = context.getSharedPreferences("scp_configuration", Context.MODE_PRIVATE)
        assertEquals("SYNTHETIC55", prefs.getString("ae", null)); assertEquals(55155, prefs.getInt("port", -1))
        assertFalse(server.state.value.status == ScpServerStatus.RUNNING)
        assertTrue(nm.activeNotifications.none { it.id == 11112 })
    }
    @Test fun foregroundServiceReceivesRealEchoAndStoreWithUnchangedPixels() {
        start()
        val logger = DicomRuntimeRegistry.getTechnicalLogger(context)
        val client = DicomScuClient(logger)
        assertTrue(client.performCEcho("127.0.0.1", port, "SYNTHETIC55", "ANDROID_SYNTH55") is ScuOperationResult.Success)
        val exec = java.util.concurrent.Executors.newCachedThreadPool()
        val sched = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        var assoc: Association? = null
        val uid = "1.2.826.0.1.55.${System.nanoTime()}"
        val pixels = byteArrayOf(12, 34, 56, 78)
        try {
            val device = Device("synthetic55")
            val conn = Connection().apply { connectTimeout = 2000; responseTimeout = 5000; releaseTimeout = 2000 }
            val ae = ApplicationEntity("SYNTHETIC55")
            ae.addConnection(conn); device.addConnection(conn); device.addApplicationEntity(ae)
            device.executor = exec; device.scheduledExecutor = sched
            val rq = AAssociateRQ().apply {
                callingAET = "SYNTHETIC55"; calledAET = "ANDROID_SYNTH55"
                addPresentationContext(PresentationContext(1, UID.SecondaryCaptureImageStorage, UID.ExplicitVRLittleEndian))
            }
            val ds = Attributes().apply {
                setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
                setString(Tag.SOPInstanceUID, VR.UI, uid)
                setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.55.100")
                setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.55.100.1")
                setString(Tag.PatientName, VR.PN, "SYNTHETIC^ONLY")
                setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
                setInt(Tag.Rows, VR.US, 2); setInt(Tag.Columns, VR.US, 2)
                setInt(Tag.SamplesPerPixel, VR.US, 1)
                setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8)
                setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 0)
                setBytes(Tag.PixelData, VR.OB, pixels)
            }
            assoc = ae.connect(conn, Connection("scp", "127.0.0.1", port), rq)
            val rsp = assoc.cstore(UID.SecondaryCaptureImageStorage, uid, 0, DataWriterAdapter(ds), UID.ExplicitVRLittleEndian)
            assertTrue(rsp.next()); assertEquals(0, rsp.command.getInt(Tag.Status, -1))
            val item = DicomRuntimeRegistry.getStudyRepository(context).studiesFlow.value
                .flatMap { it.seriesList }.flatMap { it.instances }.single { it.sopInstanceUid == uid }
            val input = if (item.filePath.startsWith("content:")) context.contentResolver.openInputStream(android.net.Uri.parse(item.filePath))!!
                else java.io.FileInputStream(item.filePath)
            DicomInputStream(input).use {
                it.readFileMetaInformation(); assertArrayEquals(pixels, it.readDataset().getBytes(Tag.PixelData))
            }
            assoc.release(); assoc.waitForSocketClose()
        } finally {
            assoc?.socket?.close(); exec.shutdownNow(); sched.shutdownNow()
        }
        stop()
    }
}
