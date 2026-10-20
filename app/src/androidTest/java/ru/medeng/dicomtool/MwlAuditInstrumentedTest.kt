package ru.medeng.dicomtool

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry
import ru.medeng.dicomtool.dicom.scp.DicomScpForegroundService
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.mwl.MwlRuntimeRegistry
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.scp.MwlScpForegroundService
import ru.medeng.dicomtool.mwl.scu.MwlScuClient
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Uses the existing isolated package; never resets the regular installation or its data. */
class MwlAuditInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < end) Thread.sleep(30)
        assertTrue("Timed out waiting for MWL lifecycle", condition())
    }
    @Before fun isolated() { check(context.packageName.endsWith(".storagesafetytest")); if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS") }
    @After fun stop() {
        shell("input keyevent KEYCODE_WAKEUP")
        compose.runOnIdle { MwlScpForegroundService.stopMwlScpService(context); DicomScpForegroundService.stopScpService(context) }
        await { MwlRuntimeRegistry.getMwlScpServer(context).state.value.status == ScpServerStatus.STOPPED }
    }
    @Test fun audit14_serviceTypeScreenOffNotificationStopAndStorageIndependence() {
        val mwl = MwlRuntimeRegistry.getMwlScpServer(context)
        val storage = DicomRuntimeRegistry.getScpServer(context)
        val port = ServerSocket(0).use { it.localPort }
        val storagePort = ServerSocket(0).use { it.localPort }
        val repo = MwlRuntimeRegistry.getMwlRepository(context)
        val (generated, template) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val patient = generated.copy(patientId = "AUDIT-DEVICE")
        repo.addSyntheticBatch(listOf(patient to template.copy(patientId = patient.patientId)))
        try {
            compose.runOnIdle {
                mwl.updateConfiguration(aeTitle = "AUDIT_MWL", port = port)
                MwlScpForegroundService.startMwlScpService(context, "AUDIT_MWL", port)
                DicomScpForegroundService.startScpService(context, "AUDIT_STORAGE", storagePort)
            }
            await { mwl.state.value.status == ScpServerStatus.RUNNING && storage.state.value.status == ScpServerStatus.RUNNING }
            val service = context.packageManager.getServiceInfo(android.content.ComponentName(context, MwlScpForegroundService::class.java), 0)
            if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
            await { shell("dumpsys power").contains("DICOMTool:MwlScpWakeLock") }
            shell("input keyevent KEYCODE_SLEEP")
            val result = MwlScuClient(MwlRuntimeRegistry.getMwlLogger(context)).queryWorklist(
                MwlScuProfile(name = "device", host = "127.0.0.1", port = port, calledAet = "AUDIT_MWL", callingAet = "AUDIT_SCU"),
                MwlScuFilter(dateOption = MwlDateFilterOption.ANY, patientId = patient.patientId)
            ).getOrThrow()
            assertTrue(result.completedSuccessfully); assertEquals(1, result.size)
            shell("input keyevent KEYCODE_WAKEUP")
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.activeNotifications.single { it.id == 11114 }.notification.actions.single { it.title.toString() == "Остановить" }.actionIntent.send()
            await { mwl.state.value.status == ScpServerStatus.STOPPED && manager.activeNotifications.none { it.id == 11114 } }
            await { !shell("dumpsys power").substringAfter("Wake Locks:").substringBefore("Suspend Blockers:").contains("DICOMTool:MwlScpWakeLock") }
            assertEquals(ScpServerStatus.RUNNING, storage.state.value.status)
            ServerSocket(port).close()
        } finally { repo.deletePatient(patient.id, true) }
    }
    @Test fun aboutDisplaysBuildVersionBranchAndCommit() {
        compose.onNodeWithContentDescription("Настройки и очистка данных").performClick()
        compose.onNodeWithText("О приложении DICOM Kit", substring = true).performScrollTo().performClick()
        compose.onAllNodesWithText(BuildConfig.VERSION_NAME, substring = true).onFirst().assertExists()
        compose.onAllNodesWithText(BuildConfig.GIT_BRANCH, substring = true).onFirst().assertExists()
        compose.onAllNodesWithText(BuildConfig.GIT_COMMIT_SHA, substring = true).onFirst().assertExists()
    }
}
