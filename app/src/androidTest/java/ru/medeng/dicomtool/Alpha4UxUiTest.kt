package ru.medeng.dicomtool

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.dcm4che3.data.*
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.ui.*
import ru.medeng.dicomtool.ui.components.*
import ru.medeng.dicomtool.ui.theme.DicomToolTheme
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/** Each method also runs in the phone/tablet orientation matrix; only synthetic in-memory fixtures. */
class Alpha4UxUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun isolatedOnly() { check(context.packageName.endsWith(".storagesafetytest")) }
    @After fun evidence() { screenshot("final"); File(evidenceDirectory(), "${javaClass.simpleName}-semantics.txt").writeText(compose.onAllNodes(isRoot()).onLast().printToString()) }
    private fun evidenceDirectory() = File(context.getExternalFilesDir(null), "alpha4-ui").apply { mkdirs() }
    private fun screenshot(name: String) {
        compose.waitForIdle(); compose.onAllNodes(isRoot()).onLast().captureToImage()
        instrumentation.waitForIdleSync(); instrumentation.uiAutomation.waitForIdle(100, 5000)
        val config = context.resources.configuration
        val file = File(evidenceDirectory(), "${config.screenWidthDp}x${config.screenHeightDp}-${testName.methodName}-$name.png")
        instrumentation.uiAutomation.takeScreenshot().also { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @get:Rule val testName = org.junit.rules.TestName()
    private fun noOverflow(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("Text overflow: $text", layouts.any { layout -> (0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + 1f || layout.isLineEllipsized(it) } || layout.getLineBottom(layout.lineCount - 1) > layout.size.height + 1f })
    }
    private fun aboveNativeKeyboard(text: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val ime = automation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        assertNotNull("Software keyboard must be visible in this scenario", ime)
        val keyboardBounds = android.graphics.Rect().also { ime!!.getBoundsInScreen(it) }
        val window = automation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }!!
        val windowBounds = android.graphics.Rect().also { window.getBoundsInScreen(it) }
        val action = compose.onNodeWithText(text).fetchSemanticsNode().boundsInWindow
        assertTrue("$text overlaps keyboard: $action + $windowBounds / $keyboardBounds", action.bottom + windowBounds.top <= keyboardBounds.top)
    }
    @Test fun simplePatientFieldsCreatePnAndKeepHistoricalId() {
        val p = MwlPatient(patientName = "", patientId = "SYNTH-PAT-0001")
        val saved = AtomicReference<MwlPatient>()
        compose.setContent { DicomToolTheme { PatientEditorDialog(p, {}, saved::set) } }
        compose.onNodeWithText("Фамилия").performTextInput("СИНТЕТИЧЕСКИЙ")
        compose.onNodeWithText("Имя", substring = false).performTextInput("ИВАН")
        compose.onNodeWithText("Отчество (необязательно)").performScrollTo().performTextInput("ИВАНОВИЧ")
        screenshot("keyboard")
        aboveNativeKeyboard("Сохранить")
        compose.onNodeWithText("Сохранить").assertIsDisplayed().performClick()
        assertEquals("СИНТЕТИЧЕСКИЙ^ИВАН^ИВАНОВИЧ", saved.get().patientName)
        assertEquals(p.patientId, saved.get().patientId)
        assertFalse(saved.get().isSynthetic)
    }
    @Test fun advancedPnPreservesFiveComponentsAndThreeGroups() {
        val raw = "TEST^JOHN^MIDDLE^Dr.^III=ТЕСТ^ИВАН^ИВАНОВИЧ=テスト^ジョン"
        val p = MwlPatient(patientName = raw, patientId = "SYNTH-PAT-0002")
        val saved = AtomicReference<MwlPatient>()
        compose.setContent { DicomToolTheme { PatientEditorDialog(p, {}, saved::set) } }
        compose.onNodeWithText("Исходное имя DICOM PN").assertExists()
        compose.onNodeWithText("Ввод по отдельным полям").assertIsNotEnabled()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals(raw, saved.get().patientName); assertEquals(p.patientId, saved.get().patientId)
        screenshot("raw-pn")
    }
    @Test fun openingSimplePnWithTrailingSeparatorDoesNotRewriteIt() {
        val p = MwlPatient(patientName = "TEST^JOHN^", patientId = "SYNTH-PAT-0003")
        val saved = AtomicReference<MwlPatient>()
        compose.setContent { DicomToolTheme { PatientEditorDialog(p, {}, saved::set) } }
        compose.onNodeWithText("Расширенный редактор PN").performScrollTo().performClick()
        compose.onNodeWithText("Ввод по отдельным полям").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals(p.patientName, saved.get().patientName)
    }
    @Test fun birthCalendarJumpsToPastDecadeAndSavesDa() {
        val date = AtomicReference("")
        compose.setContent { DicomToolTheme { Box(Modifier.fillMaxSize().systemBarsPadding()) { MwlBirthDateField("", date::set) } } }
        compose.onNodeWithText("Дата рождения: не указана").performClick()
        compose.onNodeWithText("Год", substring = false).performScrollTo().performTextReplacement("1960")
        compose.onNodeWithText("Год", substring = false).performImeAction()
        // The month dropdown explicitly avoids navigating hundreds of calendar pages.
        val currentMonth = LocalDate.now().month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale("ru"))
        compose.onNodeWithText(currentMonth).performScrollTo().performClick()
        compose.onNodeWithText("февраль").performClick()
        compose.onNodeWithText("Перейти к месяцу").performScrollTo().performClick()
        val saturday = compose.onNodeWithText("27,", substring = true)
        runCatching { saturday.assertIsDisplayed() }.onFailure { compose.onNodeWithTag("mwl-form-content:Дата рождения").performTouchInput { swipeUp() } }
        val cell = saturday.fetchSemanticsNode()
        val form = compose.onNodeWithTag("mwl-form:Дата рождения").fetchSemanticsNode().boundsInWindow
        assertTrue("Calendar's last column is clipped", cell.positionInWindow.x + cell.size.width <= form.right + 1f)
        saturday.performClick().assertIsSelected()
        val day = compose.onNodeWithText("29,", substring = true)
        runCatching { day.assertIsDisplayed() }.onFailure { compose.onNodeWithTag("mwl-form-content:Дата рождения").performTouchInput { swipeUp() } }
        day.performClick()
        screenshot("past-decade")
        compose.onNodeWithText("Выбрать").assertIsEnabled().performClick()
        assertEquals("19600229", date.get())
    }
    @Test fun exactBirthDateRejectsFutureAndAllowsUnknown() {
        val date = AtomicReference("unchanged")
        compose.setContent { DicomToolTheme { Box(Modifier.fillMaxSize().systemBarsPadding()) { MwlBirthDateField("19800515", date::set) } } }
        compose.onNodeWithText("Дата рождения: 15.05.1980").performClick()
        compose.onNodeWithText("Точный ввод даты").performScrollTo().performClick()
        compose.onNodeWithText("Дата DICOM DA").performTextReplacement(LocalDate.now().plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE))
        compose.onNodeWithText("Выбрать").assertIsNotEnabled()
        screenshot("future-rejected")
        compose.onNodeWithText("Дата рождения неизвестна").performScrollTo().performClick()
        assertEquals("", date.get())
    }
    @Test fun modalitySelectionAndManualCodeAreIndependentFromProfile() {
        val profile = MwlStationProfile(name = "SYNTHETIC", modality = "CR", scheduledStationAeTitle = "SYNTHETIC_AE")
        val saved = AtomicReference<MwlStationProfile>()
        compose.setContent { DicomToolTheme { MwlStationEditor(profile, {}, { saved.set(it); MwlValidation.Result.Valid }) } }
        compose.onNodeWithText("Модальность:", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("CT — Компьютерная томография").performClick()
        compose.onNodeWithText("Модальность: CT", substring = true).performClick()
        compose.onNodeWithText("Другой код — ручной ввод").performScrollTo().performClick()
        compose.onNodeWithText("Код модальности").performScrollTo().performTextReplacement("CUSTOM_1")
        screenshot("manual-modality")
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("CUSTOM_1", saved.get().modality); assertEquals("CR", profile.modality)
    }
    @Test fun helpWithKeyboardKeepsCloseActionInsideScreen() {
        compose.setContent { DicomToolTheme {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                var value by remember { mutableStateOf("") }
                DicomHelpField(value, { value = it }, "Study Instance UID", DicomFieldHelp.StudyUid)
            }
        } }
        compose.onNodeWithText("Study Instance UID").performTextInput("2.25.123456789")
        compose.onNodeWithContentDescription("Справка: Study Instance UID").performClick()
        compose.onNodeWithText("DICOM Tag: (0020,000D)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Закрыть").assertIsDisplayed()
        val close = compose.onNodeWithText("Закрыть").fetchSemanticsNode().boundsInRoot
        val root = compose.onAllNodes(isRoot()).onLast().fetchSemanticsNode().boundsInRoot
        assertTrue(close.bottom <= root.bottom && close.right <= root.right)
        screenshot("help-keyboard")
        compose.onNodeWithText("Закрыть").performClick()
        compose.onNodeWithTag("dicom-help").assertDoesNotExist()
    }
    @Test fun resultCardWrapsLongNameAndDetailsRetainSequenceItems() {
        val pn = "СИНТЕТИЧЕСКАЯ-ДВОЙНАЯФАМИЛИЯ^ДЛИННОЕИМЯ^ДЛИННОЕОТЧЕСТВО"
        val description = "Синтетическое длинное описание исследования органов грудной клетки"
        val attrs = Attributes().apply {
            setString(Tag.PatientName, VR.PN, pn)
            newSequence(Tag.ScheduledProcedureStepSequence, 2).apply { repeat(2) { i -> add(Attributes().apply { setString(Tag.ScheduledStationAETitle, VR.AE, "SYNTHETIC_$i") }) } }
        }
        val before = Attributes(attrs)
        val item = MwlQueryResultItem(id = "synthetic", patientName = pn, patientId = "SYNTH-PAT-0001", accessionNumber = "SYNTH-1234567890",
            patientSex = "F", modality = "CT", requestedProcedureDescription = description, spsStartDate = "20261021", spsStartTime = "093000",
            scheduledStationAeTitle = "SYNTHETIC_AE_123", scheduledStationName = "SYNTHETIC-ROOM", rawAttributes = attrs)
        compose.setContent { DicomToolTheme {
            var detail by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) { MwlResultCardItem(item) { detail = true } }
            if (detail) MwlResultDetailDialog(item) { detail = false }
        } }
        val displayedName = pn.replace('^', ' ')
        compose.onNodeWithText(displayedName).assertExists(); noOverflow(displayedName); noOverflow(description)
        compose.onNodeWithText("Patient ID: ${item.patientId}").assertExists()
        compose.onNodeWithText(item.accessionNumber, substring = true).assertDoesNotExist()
        screenshot("result-card")
        compose.onNodeWithText("Подробности и DICOM Dataset").performScrollTo().performClick()
        compose.onNodeWithText(item.accessionNumber).performScrollTo().assertIsDisplayed(); noOverflow(item.accessionNumber)
        screenshot("result-details")
        compose.onNodeWithText("DICOM Dataset", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("(0040,0100)", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Item #1", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SYNTHETIC_1", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(before, attrs)
        screenshot("dataset-sequence")
    }
}
