package ru.medeng.dicomtool

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class AcceptanceNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nestedBackReturnsAndRootRequiresTwoConsecutiveBackActions() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".storagesafetytest"))
        val activity = compose.activity
        compose.onNodeWithContentDescription("Настройки и очистка данных").performClick()
        compose.onNodeWithText("DICOM Kit — Действия и настройки").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle(); assertFalse(activity.isFinishing)
        compose.onNodeWithText("DICOM Kit — Действия и настройки").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle(); assertFalse(activity.isFinishing)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!activity.isFinishing && System.nanoTime() < until) Thread.sleep(20)
        assertTrue(activity.isFinishing)
    }
}
