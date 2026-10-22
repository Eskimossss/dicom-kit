package ru.medeng.dicomtool

import android.graphics.Bitmap
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.data.logging.LoggerHealth
import ru.medeng.dicomtool.data.logging.LoggerWriteState
import ru.medeng.dicomtool.ui.screens.log.LoggerHealthDialog
import java.io.File

class LoggerHealthDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolatedOnly() { check(context.packageName.endsWith(".storagesafetytest")) }
    private fun text(id: Int) = context.getString(id)
    private fun visible(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        android.os.SystemClock.sleep(600) // Separate dialog-window resize/theme animations use the system clock.
        val dir = File(context.getExternalFilesDir(null), "logger-health").apply { mkdirs() }
        // Dialogs have a separate Android window; capture the display, not the activity's Compose layer.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun noClipping(value: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertEquals(context.resources.configuration.fontScale, layouts.first().layoutInput.density.fontScale, 0.01f)
        assertFalse(value, layouts.any { layout ->
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) || layout.getLineLeft(it) < -1f || layout.getLineRight(it) > layout.size.width + 1f } ||
                layout.getLineBottom(layout.lineCount - 1) > layout.size.height + 1f
        })
    }
    @Test fun healthyDialogGroupsEventCountersKeepsCrashNoteInformationalAndCloses() {
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (open) LoggerHealthDialog(LoggerHealth(accepted = 47, persisted = 47), { open = false }) } }
        visible(R.string.logger_health_active); visible(R.string.logger_health_ok)
        screenshot("healthy-${context.resources.configuration.fontScale}")
        compose.onNodeWithText("state: ACTIVE").assertDoesNotExist()
        for (label in listOf(R.string.logger_health_events, R.string.logger_health_accepted, R.string.logger_health_persisted,
            R.string.logger_health_errors, R.string.logger_health_extra, R.string.logger_health_rotation)) visible(label)
        compose.onAllNodesWithText("Нет").assertCountEquals(3)
        visible(R.string.logger_health_information); visible(R.string.logger_health_crash_note)
        compose.onNodeWithText(text(R.string.logger_health_issues)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.logger_health_close)).performClick()
        compose.onNodeWithText(text(R.string.logger_health_title)).assertDoesNotExist()
    }
    @Test fun activeStateCannotHideLossesErrorsOrUnconfirmedCriticalEvents() {
        var health by mutableStateOf(LoggerHealth())
        compose.setContent { MaterialTheme { LoggerHealthDialog(health, {}) } }
        val cases = listOf(
            LoggerHealth(droppedNormal = 1), LoggerHealth(droppedCritical = 1), LoggerHealth(writeUnconfirmed = 1),
            LoggerHealth(criticalWriteUnconfirmed = 1), LoggerHealth(readFailures = 1), LoggerHealth(rejectedAfterStop = 1),
            LoggerHealth(rejectedCriticalAfterStop = 1), LoggerHealth(controlFailures = 1), LoggerHealth(controlTimeouts = 1),
            LoggerHealth(controlRejected = 1), LoggerHealth(rotationPending = true), LoggerHealth(lastError = "Synthetic IOException"),
            LoggerHealth(droppedNormal = Long.MAX_VALUE, droppedCritical = Long.MAX_VALUE)
        )
        for (candidate in cases) {
            compose.runOnIdle { health = candidate }
            visible(R.string.logger_health_active); visible(R.string.logger_health_issues)
            compose.onNodeWithText(text(R.string.logger_health_ok)).assertDoesNotExist()
        }
    }
    @Test fun everyStateLoadingQueueAndRotationHaveAccurateLocalizedDiagnosis() {
        var health by mutableStateOf(LoggerHealth())
        compose.setContent { MaterialTheme { LoggerHealthDialog(health, {}) } }
        val cases = listOf(
            Triple(LoggerHealth(), R.string.logger_health_active, R.string.logger_health_ok),
            Triple(LoggerHealth(state = LoggerWriteState.DEGRADED), R.string.logger_health_degraded, R.string.logger_health_issues),
            Triple(LoggerHealth(state = LoggerWriteState.WRITE_FAILED), R.string.logger_health_write_failed, R.string.logger_health_issues),
            Triple(LoggerHealth(state = LoggerWriteState.STOPPING), R.string.logger_health_stopping, R.string.logger_health_stopping_summary),
            Triple(LoggerHealth(state = LoggerWriteState.STOPPED), R.string.logger_health_stopped, R.string.logger_health_stopped_summary),
            Triple(LoggerHealth(historyLoading = true), R.string.logger_health_active, R.string.logger_health_loading),
            Triple(LoggerHealth(queueDepth = 2, accepted = 3), R.string.logger_health_active, R.string.logger_health_pending),
            Triple(LoggerHealth(accepted = 1), R.string.logger_health_active, R.string.logger_health_pending),
            Triple(LoggerHealth(rotationPending = true), R.string.logger_health_active, R.string.logger_health_issues)
        )
        for ((candidate, state, summary) in cases) {
            compose.runOnIdle { health = candidate }
            visible(state); visible(summary)
        }
        visible(R.string.logger_health_rotation)
        compose.onNodeWithText("Да").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logger_health_technical)).performScrollTo().performClick()
        compose.onNodeWithText("state: ACTIVE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("rotationPending: true").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("historyLoading: false").performScrollTo().assertIsDisplayed()
    }
    @Test fun longDiagnosticsAndLargeCountersWrapInBothThemesAndLargeFonts() {
        var dark by mutableStateOf(false)
        var width by mutableStateOf(320)
        val error = "IOException: синтетическая ошибка записи в длинный тестовый путь /synthetic/logger/" + "diagnostics".repeat(12)
        val health = LoggerHealth(state = LoggerWriteState.WRITE_FAILED, accepted = Long.MAX_VALUE, persisted = Long.MAX_VALUE,
            droppedNormal = Long.MAX_VALUE, droppedCritical = Long.MAX_VALUE, writeUnconfirmed = 1234567890123456789,
            criticalWriteUnconfirmed = 123456789, rotationPending = true, lastError = error)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                LoggerHealthDialog(health, {}, Modifier.widthIn(max = width.dp))
            }
        }
        val scale = context.resources.configuration.fontScale
        for (night in listOf(false, true)) for (size in listOf(320, 560)) {
            compose.runOnIdle { dark = night; width = size }
            noClipping(text(R.string.logger_health_issues))
            screenshot("$size-${if (night) "dark" else "light"}-$scale-top")
            noClipping("18446744073709551614")
            noClipping(error)
            noClipping(text(R.string.logger_health_technical))
            visible(R.string.logger_health_information); noClipping(text(R.string.logger_health_crash_note))
            screenshot("$size-${if (night) "dark" else "light"}-$scale-bottom")
            compose.onNodeWithText(text(R.string.logger_health_close)).assertIsDisplayed()
        }
        assertEquals(error, health.lastError)
        assertEquals(Long.MAX_VALUE, health.droppedNormal)
    }
}
