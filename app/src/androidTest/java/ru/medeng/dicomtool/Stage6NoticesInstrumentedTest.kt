package ru.medeng.dicomtool

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry
import ru.medeng.dicomtool.dicom.scp.DicomScpForegroundService
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.ui.components.*
import ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class Stage6NoticesInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("Timed out waiting for synthetic notice", condition())
    }
    @Test fun successExpiresButWarningsErrorsAndSameTextNewEventsRemainCorrect() {
        val event = mutableStateOf(Triple("success-1", "SYNTHETIC SUCCESS", true))
        val redraw = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.setContent { NoticePresentationHost {
            Text("Recomposition ${redraw.intValue}")
            AppNotice(event.value.first, event.value.second, event.value.third)
        } } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("SYNTHETIC SUCCESS").assertExists()
        compose.mainClock.advanceTimeBy(6100)
        compose.onNodeWithText("SYNTHETIC SUCCESS").assertDoesNotExist()
        compose.runOnIdle { event.value = Triple("warning-1", "SYNTHETIC WARNING", false) }
        compose.mainClock.advanceTimeBy(7000)
        compose.onNodeWithText("SYNTHETIC WARNING").assertExists()
        compose.onNodeWithContentDescription("Закрыть уведомление").performClick()
        compose.runOnIdle { redraw.intValue++ }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("SYNTHETIC WARNING").assertDoesNotExist()
        compose.runOnIdle { event.value = Triple("warning-2", "SYNTHETIC WARNING", false) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("SYNTHETIC WARNING").assertExists()
        compose.runOnIdle { event.value = Triple("error-1", "SYNTHETIC READ ERROR", false) }
        compose.mainClock.advanceTimeBy(10000)
        compose.onNodeWithText("SYNTHETIC READ ERROR").assertExists()
    }
    @Test fun dismissedRealEchoResultSurvivesNavigationAndRecreationThenNewEchoReappears() {
        check(context.packageName.endsWith(".storagesafetytest"))
        val server = DicomRuntimeRegistry.getScpServer(context)
        val port = ServerSocket(0).use { it.localPort }
        var model: DicomToolViewModel? = null
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get(DicomToolViewModel::class.java)
            DicomScpForegroundService.startScpService(context, "SYNTHETIC6", port) }
        try {
            await { server.state.value.status == ScpServerStatus.RUNNING }
            val vm = model!!
            compose.runOnIdle { vm.updateScuConfig { it.copy(remoteIp = "127.0.0.1", remotePort = port,
                calledAeTitle = "SYNTHETIC6", callingAeTitle = "SYNTHETIC_SCU6") } }
            compose.onNodeWithContentDescription("Отправка", useUnmergedTree = true).performClick()
            fun echo() { compose.runOnIdle { vm.triggerCEchoScu() }; await {
                !vm.scuConfig.value.isOperationInProgress && vm.scuConfig.value.isLastOperationSuccess == true } }
            echo()
            val firstId = vm.scuConfig.value.noticeId
            val title = vm.scuConfig.value.lastStatusTitle!!
            compose.onNodeWithText(title).assertExists()
            compose.onNodeWithContentDescription("Закрыть уведомление").performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithContentDescription("Журнал", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Отправка", useUnmergedTree = true).performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(title).assertDoesNotExist()
            echo()
            assertNotEquals(firstId, vm.scuConfig.value.noticeId)
            compose.onNodeWithText(title).assertExists()
            assertEquals(true, vm.scuConfig.value.isLastOperationSuccess)
        } finally {
            compose.runOnIdle { DicomScpForegroundService.stopScpService(context) }
            await { server.state.value.status == ScpServerStatus.STOPPED }
        }
    }
}
