package ru.medeng.dicomtool

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import ru.medeng.dicomtool.mwl.MwlRuntimeRegistry
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.ui.*
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.ui.screens.log.DicomDetailedLogRow
import ru.medeng.dicomtool.ui.screens.received.StudyListItemCard
import ru.medeng.dicomtool.ui.theme.DicomToolTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Run with -Pstage51Isolated=true; never clears application storage or existing records. */
class Alpha3AcceptanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdPatients = mutableListOf<String>()
    @Before fun isolatedOnly() { check(context.packageName.endsWith(".storagesafetytest")) }
    @After fun removeOwnSyntheticFixtures() {
        val repo = MwlRuntimeRegistry.getMwlRepository(context)
        createdPatients.forEach { repo.deletePatient(it, true) }
    }
    private fun model() = MwlViewModel(context.applicationContext as Application)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Semantics can be ready before the native render pipeline presents its first frame.
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5000)
        val config = context.resources.configuration
        val file = File(context.getExternalFilesDir(null), "alpha3-ui/${config.screenWidthDp}x${config.screenHeightDp}-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (name == "calendar-open") File(file.parentFile, "calendar-semantics.txt").writeText(compose.onAllNodes(isRoot()).onLast().printToString())
    }
    private fun noTextOverflow(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Missing text layout: $text", results.isNotEmpty())
        assertFalse("Text clipped: $text ${results.map { "${it.size} right=${it.getLineRight(0)} bottom=${it.getLineBottom(it.lineCount - 1)}" }}",
            results.any { layout -> (0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + 1f } ||
                layout.getLineBottom(layout.lineCount - 1) > layout.size.height + 1f || layout.isLineEllipsized(layout.lineCount - 1) })
    }
    private fun chooseCalendarDay(day: String) {
        val node = compose.onNodeWithText("$day,", substring = true)
        if (runCatching { node.assertIsDisplayed() }.isFailure) {
            compose.onNodeWithTag("mwl-form-content:Выбор даты").performTouchInput { swipeUp() }
        }
        node.assertIsDisplayed().performClick()
    }
    @Test fun scpStatusAndWhitelistFitAndActionsRemainReachable() {
        val vm = model()
        compose.setContent { DicomToolTheme { MwlScpTab(vm, false) } }
        compose.onNodeWithText("ОСТАНОВЛЕН").assertIsDisplayed()
        screenshot("scp-status")
        noTextOverflow("ОСТАНОВЛЕН")
        compose.onNodeWithText("Ограничения подключений (Белый список)").performScrollTo().assertIsDisplayed()
        noTextOverflow("Ограничения подключений (Белый список)")
        screenshot("scp-whitelist")
        compose.onNodeWithText("Запустить сервер MWL SCP").performScrollTo().assertIsDisplayed()
    }
    @Test fun appointmentsRequireExplicitChoiceAndFindExistingPatientWithoutDuplicating() {
        val vm = model(); val repo = MwlRuntimeRegistry.getMwlRepository(context)
        val p = MwlPatient(patientName = "СИНТЕТИЧЕСКИЙ^ВЫБОР", patientId = "ALPHA3-CHOICE")
        repo.savePatient(p); createdPatients += p.id
        val count = repo.getAllPatients().size
        compose.setContent { DicomToolTheme { MwlAppointmentsTab(vm, false) } }
        compose.onNodeWithText("Назначение").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пациент").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Тестовые").performScrollTo().assertIsDisplayed()
        screenshot("appointment-actions")
        compose.onNodeWithText("Назначение").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Выбрать пациента").performScrollTo().performClick()
        compose.onNodeWithText("Поиск: Patient Name / Patient ID").performTextInput(p.patientId)
        compose.onNodeWithText("${p.patientName} · ${p.patientId}").performClick()
        compose.onNodeWithText("Сохранить").assertIsEnabled()
        assertEquals(count, repo.getAllPatients().size)
        screenshot("patient-selected")
        compose.runOnIdle { vm.openEditPatientDialog(p) }
        compose.onNodeWithText("ФИО · PN: Фамилия^Имя^Отчество").performTextReplacement("СИНТЕТИЧЕСКИЙ^РУЧНОЙ")
        compose.onNode(hasText("Сохранить") and hasAnyAncestor(hasTestTag("mwl-form:Редактирование пациента"))).performClick()
        assertFalse(repo.getPatientById(p.id)!!.isSynthetic)
        assertFalse(repo.exportSyntheticJson().contains("СИНТЕТИЧЕСКИЙ^РУЧНОЙ"))
    }
    @Test fun stationProfileFillsFieldsAndAllowsManualOverride() {
        val (p, study) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val profile = MwlStationProfile(name = "SYNTHETIC APPARATUS", modality = "CT", scheduledStationAeTitle = "SYNTHETIC_CT",
            scheduledStationName = "ROOM-TEST", location = "FLOOR-TEST")
        val saved = AtomicReference<MwlScheduledStudy>()
        compose.setContent { DicomToolTheme {
            StudyEditorDialog(study, listOf(p), listOf(profile), {}, { MwlValidation.Result.Valid }, {}, { saved.set(it) })
        } }
        compose.onNodeWithText("SYNTHETIC APPARATUS", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("SYNTHETIC_CT").assertExists()
        compose.onNodeWithText("ROOM-TEST").assertExists()
        compose.onNodeWithText("Место проведения исследования · SH, до 16 символов").performScrollTo().performTextReplacement(" MANUAL-ROOM ")
        screenshot("station-fields")
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("MANUAL-ROOM", saved.get().spsLocation)
        assertEquals("CT", saved.get().modality); assertEquals("SYNTHETIC_CT", saved.get().scheduledStationAeTitle)
    }
    @Test fun newlyCreatedPatientIsSelectedAndCanBeEditedWithoutAppointments() {
        val vm = model(); val repo = MwlRuntimeRegistry.getMwlRepository(context)
        compose.setContent { DicomToolTheme { MwlAppointmentsTab(vm, false) } }
        compose.onNodeWithText("Назначение").performScrollTo().performClick()
        compose.onNodeWithText("Создать пациента").performScrollTo().performClick()
        compose.onNodeWithText("ФИО · PN: Фамилия^Имя^Отчество").performTextInput("СИНТЕТИЧЕСКИЙ^НОВЫЙАЛЬФА3")
        compose.onNode(hasText("Сохранить") and hasAnyAncestor(hasTestTag("mwl-form:Новый пациент"))).performClick()
        val patient = repo.getAllPatients().single { it.patientName == "СИНТЕТИЧЕСКИЙ^НОВЫЙАЛЬФА3" }
        createdPatients += patient.id
        compose.onNodeWithText("${patient.patientName} · ${patient.patientId}").assertExists()
        compose.onNodeWithText("Сохранить").assertIsEnabled()
        compose.onNodeWithText("Отмена").performClick()
        assertTrue(repo.getStudiesForPatient(patient.id).isEmpty())
        compose.onNodeWithText("Пациенты (${repo.getAllPatients().size})").performScrollTo().performClick()
        compose.onNodeWithText("Поиск по ФИО, ID, Accession, SPS…").performTextInput(patient.patientId)
        compose.onNodeWithText("Редактировать пациента").performScrollTo().performClick()
        compose.onNode(hasText(patient.patientName) and hasSetTextAction()).assertExists()
        screenshot("patient-without-appointments")
    }
    @Test fun mwlJournalUsesInlineExpansionAndSafeCopyPreview() {
        val vm = model(); val marker = "SYNTHETIC-MWL-${java.util.UUID.randomUUID()}"
        MwlRuntimeRegistry.getMwlLogger(context).recordEvent(DicomLogCategory.C_ECHO_RQ,
            associationId = "MWL-SCU-SYNTHETIC", title = marker,
            technicalDetails = mapOf("Dataset" to "SYNTHETIC-PHI-NAME", "Status" to "0x0000"))
        compose.setContent { DicomToolTheme { MwlJournalTab(vm, false) } }
        compose.onNodeWithText("Поиск по событиям, IP, AE, кодам…").performTextInput(marker)
        compose.onNode(hasText(marker) and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Dataset:").performScrollTo().assertIsDisplayed()
        screenshot("mwl-journal-inline")
        compose.onNodeWithContentDescription("Копировать событие").performScrollTo().performClick()
        compose.onNodeWithText("Предварительный просмотр:").assertExists()
        compose.onNodeWithText("Копировать сводку").performClick()
        var copied = ""
        compose.runOnIdle { copied = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text.toString() }
        assertFalse(copied.contains("SYNTHETIC-PHI-NAME")); assertTrue(copied.contains("0x0000"))
        compose.onNode(hasText(marker) and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Dataset:").assertDoesNotExist()
    }
    @Test fun calendarAndRangeAreUsableAndQueryButtonSurvivesKeyboard() {
        val vm = model()
        compose.setContent { DicomToolTheme { MwlScuTab(vm, false) } }
        compose.onNodeWithText("Диапазон дат").performScrollTo().performClick()
        compose.onNodeWithText("Начало диапазона: выбрать дату").performScrollTo().performClick()
        screenshot("calendar-open")
        // Material 3 exposes the full formatted date as Text semantics, not the displayed day number.
        chooseCalendarDay("22")
        compose.onNodeWithText("Выбрать").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("22,", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Окончание диапазона: выбрать дату").performScrollTo().performClick()
        chooseCalendarDay("23")
        compose.onNodeWithText("Выбрать").performClick()
        assertTrue(vm.scuFilter.value.validateDateSelection() is MwlValidation.Result.Valid)
        assertEquals("22", vm.scuFilter.value.startDate.takeLast(2)); assertEquals("23", vm.scuFilter.value.endDate.takeLast(2))
        screenshot("scu-range")
        compose.onNodeWithText("ФИО пациента").performScrollTo().performTextInput("SYNTHETIC*")
        compose.onNodeWithText("Получить Worklist").performScrollTo().assertIsEnabled().assertIsDisplayed()
        screenshot("scu-query-keyboard")
    }
    @Test fun journalExpandsInlineLongDatasetAndCollapsesOnSecondTap() {
        val entry = DicomLogEntry("synthetic-alpha3", "12:00:00.000", DicomLogCategory.C_ECHO_RQ,
            title = "SYNTHETIC MWL EVENT", summaryLines = listOf("C-FIND"), technicalDetails = mapOf("Dataset" to "SYNTHETIC-DATASET-".repeat(30)))
        compose.setContent { DicomToolTheme {
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                DicomDetailedLogRow(entry, expanded, { expanded = !expanded }, {})
            }
        } }
        compose.onNodeWithText("Dataset:").assertDoesNotExist()
        compose.onNodeWithText(entry.title).performClick()
        compose.onNodeWithText("Dataset:").performScrollTo().assertIsDisplayed()
        screenshot("journal-expanded")
        compose.onNodeWithText(entry.title).performScrollTo().performClick()
        compose.onNodeWithText("Dataset:").assertDoesNotExist()
    }
    @Test fun receivedCardHasFourLogicalRowsAndKeepsUidOutOfSummary() {
        val study = DicomStudyItem("1.2.826.0.1.3680043.10.999.1", "СИНТЕТИЧЕСКИЙ ПАЦИЕНТ", "SYNTHETIC-ID", "", "O", "CT", "21.10.2026 09:30", "Synthetic", "Series", 2, 10, "", "", columns = 512, rows = 512, totalBytes = 1024 * 1024)
        compose.setContent { DicomToolTheme { Box(Modifier.fillMaxSize().systemBarsPadding()) { StudyListItemCard(study, false, {}, {}) } } }
        compose.onNodeWithText(study.studyInstanceUid, substring = true).assertDoesNotExist()
        compose.onNodeWithText(study.patientName).assertIsDisplayed()
        compose.onNodeWithText("ID: SYNTHETIC-ID").assertIsDisplayed()
        compose.onNodeWithText("512×512").assertIsDisplayed()
        compose.onNodeWithText(study.studyDateFormatted).assertIsDisplayed()
        compose.onNodeWithText("Серий: 2").assertIsDisplayed()
        screenshot("received-card")
    }
}
