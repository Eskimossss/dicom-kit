package ru.medeng.dicomtool

import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.dcm4che3.data.*
import org.dcm4che3.io.DicomOutputStream
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel
import java.io.File
import java.util.UUID

/** Bounded audit through the real scaffold. Never starts a server, sends data or clears an archive. */
class Alpha4AppAuditUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun isolatedOnly() { check(context.packageName.endsWith(".storagesafetytest")) }
    private fun tab(name: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(name, useUnmergedTree = true).performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); compose.onAllNodes(isRoot()).onFirst().captureToImage()
        instrumentation.waitForIdleSync(); instrumentation.uiAutomation.waitForIdle(100, 5000)
        val config = context.resources.configuration
        val dir = File(context.getExternalFilesDir(null), "alpha4-ui").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "${config.screenWidthDp}x${config.screenHeightDp}-audit-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun noOverflow(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse(text, layouts.any { layout -> (0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + 1f || layout.isLineEllipsized(it) } || layout.getLineBottom(layout.lineCount - 1) > layout.size.height + 1f })
    }
    @Test fun receiverReceivedSendJournalSettingsAndAboutRemainReachable() {
        tab("Приёмник")
        compose.onNodeWithText("Сервер остановлен").performScrollTo().assertIsDisplayed()
        screenshot("receiver")
        tab("Принятые"); screenshot("received")
        tab("Отправка")
        compose.onNodeWithText("Параметры PACS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("IP-адрес или хост").performClick()
        screenshot("send-keyboard")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        tab("Журнал"); screenshot("journal")
        compose.onNodeWithContentDescription("Настройки и очистка данных").performClick()
        compose.onNodeWithText("DICOM Kit — Действия и настройки").assertExists()
        compose.onNodeWithText("О приложении DICOM Kit").performScrollTo().assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithText("О приложении DICOM Kit").performClick()
        compose.onNodeWithText(BuildConfig.VERSION_NAME, substring = false).performScrollTo().assertIsDisplayed(); noOverflow(BuildConfig.VERSION_NAME)
        compose.onNodeWithText(BuildConfig.GIT_BRANCH).performScrollTo().assertIsDisplayed(); noOverflow(BuildConfig.GIT_BRANCH)
        compose.onNodeWithText("Копировать информацию о сборке").performScrollTo().assertIsDisplayed()
        screenshot("about")
    }
    @Test fun syntheticDicomTagsKeepSearchAndEncodingActionsReachable() {
        val file = File(context.cacheDir, "alpha4-audit-${UUID.randomUUID()}.dcm")
        val attrs = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, "2.25.12345001"); setString(Tag.StudyInstanceUID, VR.UI, "2.25.12345002"); setString(Tag.SeriesInstanceUID, VR.UI, "2.25.12345003")
            setString(Tag.PatientName, VR.PN, "SYNTHETIC^UIAUDIT"); setString(Tag.PatientID, VR.LO, "SYNTH-UI-AUDIT"); setString(Tag.Modality, VR.CS, "OT")
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.Rows, VR.US, 2); setInt(Tag.Columns, VR.US, 2); setInt(Tag.SamplesPerPixel, VR.US, 1)
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8); setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 0)
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(0, 64, 96, 127))
        }
        DicomOutputStream(file).use { it.writeDataset(attrs.createFileMetaInformation(UID.ExplicitVRLittleEndian), attrs) }
        val model = ViewModelProvider(compose.activity)[DicomToolViewModel::class.java]
        try {
            tab("Теги")
            compose.runOnIdle { model.loadExternalDicomFile(Uri.fromFile(file)) }
            compose.waitUntil(15000) { !model.isExternalFileLoading.value }
            assertNull(model.externalFileError.value)
            assertNotNull(model.externalFileSummary.value)
            compose.onNodeWithText("DICOM-теги", substring = false).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Только заполненные").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Только заполненные").assertIsDisplayed()
            compose.onNodeWithContentDescription("Поиск").assertIsDisplayed()
            screenshot("tags-toolbar")
            compose.onNodeWithContentDescription("Поиск").performClick()
            compose.onNodeWithText("Поиск: (0010,0010), код, название, значение...").performTextInput("PatientName")
            screenshot("tags-keyboard")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally { compose.runOnIdle { model.clearExternalFile() }; file.delete() }
    }
}
