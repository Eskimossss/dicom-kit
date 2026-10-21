package ru.medeng.dicomtool

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Assert.*

/** Complements standalone screen tests with the real scaffold and bottom navigation. */
class Alpha3NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun captureActualScaffold() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = context.resources.configuration
        val directory = java.io.File(context.getExternalFilesDir(null), "alpha3-ui").apply { mkdirs() }
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).onFirst().captureToImage()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(directory, "${configuration.screenWidthDp}x${configuration.screenHeightDp}-main-scaffold.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        java.io.File(directory, "main-scaffold-semantics.txt").writeText(compose.onAllNodes(isRoot()).onFirst().printToString())
    }
    private fun tab(text: String) {
        val node = compose.onNodeWithText(text)
        try { node.performScrollTo() } catch (error: AssertionError) {
            if (!error.message.orEmpty().contains("no parent layout with a Scroll")) throw error
        }
        node.performClick()
    }
    @Test fun worklistActionsRemainAboveBottomNavigationWithKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".storagesafetytest"))
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Worklist", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Worklist", useUnmergedTree = true).performClick()
        tab("Клиент (SCU)")
        compose.onNodeWithText("ФИО пациента").performScrollTo().performTextInput("SYNTHETIC*")
        compose.onNodeWithText("Получить Worklist").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Получить Worklist").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithContentDescription("Worklist", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top)
        var keyboardVisible = false
        compose.runOnIdle { keyboardVisible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        if (keyboardVisible) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        tab("Назначения")
        compose.onNodeWithText("Назначение").performScrollTo().assertIsDisplayed()
        tab("Сервер (SCP)")
        compose.onNodeWithText("ОСТАНОВЛЕН").assertIsDisplayed()
    }
}
