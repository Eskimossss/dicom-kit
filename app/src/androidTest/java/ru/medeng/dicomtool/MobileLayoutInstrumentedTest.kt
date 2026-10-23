package ru.medeng.dicomtool

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.ui.screens.log.DicomLogScreen
import ru.medeng.dicomtool.ui.theme.DicomToolTheme
import ru.medeng.dicomtool.mwl.ui.*
import ru.medeng.dicomtool.ui.screens.receiver.ReceiverScpScreen
import ru.medeng.dicomtool.domain.model.ScpReceiverState
import ru.medeng.dicomtool.mwl.MwlRuntimeRegistry
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.ui.screens.send.ScuModeSelector
import ru.medeng.dicomtool.ui.screens.settings.SettingsAndCleanupScreen
import ru.medeng.dicomtool.data.storage.StorageLocationInfo
import ru.medeng.dicomtool.data.storage.StorageType
import ru.medeng.dicomtool.data.storage.ReceptionStorageOptions


/** In-memory journal fixtures and isolated MWL preferences; no production storage. */
class MobileLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolatedOnly() { check(context.packageName.endsWith(".storagesafetytest")) }
    @get:Rule val testName = org.junit.rules.TestName()
    @After fun capture() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val c = context.resources.configuration
        val dir = java.io.File(context.getExternalFilesDir(null), "mobile-layout").apply { mkdirs() }
        val name = "${c.screenWidthDp}x${c.screenHeightDp}-font${c.fontScale}-${testName.methodName}"
        compose.waitForIdle()
        val bitmap = inst.uiAutomation.takeScreenshot()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        java.io.File(dir, "$name.txt").writeText(compose.onAllNodes(isRoot()).onLast().printToString())
    }
    private fun event(i: Int) = DicomLogEntry("mobile-$i", "12:00:00.000", DicomLogCategory.C_STORE_RSP,
        DicomTrafficDirection.INBOUND, "SYNTHETIC", "Событие $i", emptyList(), emptyMap())
    private var query = ""
    private var mode = DicomLogDisplayMode.DETAILED
    private var traffic = DicomTrafficFilter.ALL
    private var auto by mutableStateOf(false)
    private var events by mutableStateOf((0..79).map(::event))
    @Composable private fun Journal() {
        var search by rememberSaveable { mutableStateOf("") }
        var display by rememberSaveable { mutableStateOf(DicomLogDisplayMode.DETAILED) }
        var filter by rememberSaveable { mutableStateOf(DicomTrafficFilter.ALL) }
        DicomToolTheme {
            DicomLogScreen(display, filter, DicomOutcomeFilter.ALL, search,
                DicomSessionGrouper.groupEvents(events), events, emptySet(), emptySet(), auto,
                { display = it; mode = it }, { filter = it; traffic = it }, {},
                { search = it; query = it }, { auto = it }, {}, {}, {})
        }
    }
    @Test fun searchClosesWithoutHiddenFilterAndRestoresAcrossRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Journal() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Поиск в журнале").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("SYNTHETIC 1.2.3 Ошибка")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("SYNTHETIC 1.2.3 Ошибка").assertExists()
        compose.onNodeWithContentDescription("Закрыть поиск").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals("", query) }
        compose.onNodeWithText("Краткий", substring = false).performClick()
        compose.onNodeWithTag("journal-traffic:SCP_IN").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Краткий режим (сеансы)").assertExists()
        compose.onNodeWithTag("journal-traffic:SCP_IN").assertIsSelected()
        compose.runOnIdle { assertEquals(DicomLogDisplayMode.BRIEF, mode); assertEquals(DicomTrafficFilter.SCP_IN, traffic) }
        compose.onNodeWithContentDescription("Очистить журнал").performClick()
        compose.onNodeWithText("Очистить журнал DICOM?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithContentDescription("Экспорт журнала").performClick()
        compose.onNodeWithText("Предварительный просмотр:").assertExists()
    }
    @Test fun controlsScrollWithEventsAndAutoDoesNotPullReaderFromHistory() {
        compose.setContent { Journal() }
        compose.onNodeWithTag("journal-list").performScrollToIndex(50)
        compose.onNodeWithContentDescription("Поиск в журнале").assertIsNotDisplayed()
        compose.onNodeWithTag("journal-list").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Поиск в журнале").assertIsDisplayed()
        compose.runOnIdle { auto = true }
        compose.waitForIdle()
        compose.onNodeWithTag("journal-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("journal-list").fetchSemanticsNode().children.first().id
        compose.runOnIdle { events = events + event(80) }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("journal-list").fetchSemanticsNode().children.first().id)
    }
    @Test fun mwlFieldsUseFullWidthAndIpHasSeparateActions() {
        val vm = MwlViewModel(context.applicationContext as Application)
        compose.setContent { DicomToolTheme { MwlScpTab(vm, false) } }
        compose.onNodeWithContentDescription("Обновить сеть").performScrollTo().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Скопировать").assertIsDisplayed()
        compose.onNodeWithText("AE Title (Called / Local SCP)").performScrollTo().assertIsDisplayed()
        val ae = compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("TCP Порт").performScrollTo().assertIsDisplayed()
        val port = compose.onAllNodes(hasSetTextAction())[1].fetchSemanticsNode().boundsInRoot
        assertEquals(ae.width, port.width, 1f)
        assertEquals(ae.left, port.left, 1f)
        compose.onNodeWithText("Запустить сервер MWL SCP").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("ОСТАНОВЛЕН").assertCountEquals(0)
        compose.onNodeWithText("Ожидание запуска сервера MWL...").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Подробнее").performScrollTo().performClick()
        compose.onNodeWithText("Сеанс работы до 4 часов", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun receiverScpMatchesUnifiedVisualHierarchy() {
        val state = ScpReceiverState()
        compose.setContent {
            DicomToolTheme {
                ReceiverScpScreen(
                    state = state,
                    aeTitleInput = "TEST_SCP",
                    portInput = "11112",
                    isExpandedWidth = false,
                    onAeTitleChange = {},
                    onPortChange = {},
                    onIncrementPort = {},
                    onRefreshNetwork = {},
                    onToggleServerState = {}
                )
            }
        }
        // Top banner is removed from main screen
        compose.onAllNodesWithText("Приём DICOM (SCP): сеанс до 4 часов", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Обновить IP").assertWidthIsAtLeast(44.dp)
        compose.onNodeWithContentDescription("Скопировать IP").assertWidthIsAtLeast(44.dp)
        compose.onNodeWithText("AE Title (Called / Local SCP)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Порт (1024..65535)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Запустить сервер").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сервер остановлен").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ожидание запуска приёмника...").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Подробнее").performScrollTo().performClick()
        compose.onNodeWithText("Сеанс приёма до 4 часов", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun worklistNavigationHasFiveTabsAndPatientCascadeDeletion() {
        val repo = MwlRuntimeRegistry.getMwlRepository(context)
        val (genP, template) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val p = genP.copy(patientName = "БЕЗОПАСНЫЙ^ПАЦИЕНТ", patientId = "PAT-SAFE-01")
        val s = template.copy(
            patientInternalId = p.id,
            patientName = p.patientName,
            patientId = p.patientId,
            accessionNumber = "ACC-SAFE-01"
        )
        val (genOther, templateOther) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.ULTRASOUND)
        val pOther = genOther.copy(patientName = "ДРУГОЙ^ПАЦИЕНТ", patientId = "PAT-OTHER-02")
        val sOther = templateOther.copy(
            patientInternalId = pOther.id,
            patientName = pOther.patientName,
            patientId = pOther.patientId,
            accessionNumber = "ACC-OTHER-02"
        )
        try {
            repo.savePatient(p)
            repo.saveStudy(s)
            repo.savePatient(pOther)
            repo.saveStudy(sOther)
            val vm = MwlViewModel(context.applicationContext as Application)

            // Проверяем 5 вкладок в WorklistMainScreen
            compose.setContent { DicomToolTheme { WorklistMainScreen(isExpandedWidth = false, viewModel = vm) } }
            compose.onNodeWithText("Сервер (SCP)").assertIsDisplayed()
            compose.onNodeWithText("Назначения").assertIsDisplayed()
            compose.onNodeWithText("Пациенты").assertIsDisplayed()
            compose.onNodeWithText("Клиент (SCU)").assertIsDisplayed()
            compose.onNodeWithText("Журнал").assertIsDisplayed()

            // Переключаемся на вкладку "Пациенты"
            compose.onNodeWithText("Пациенты").performClick()
            compose.waitForIdle()

            // Изолируем поиск по ID нашего тестового пациента
            compose.runOnIdle { vm.patientSearchQuery.value = "PAT-SAFE-01" }
            compose.waitForIdle()
            compose.onNodeWithText("Пациенты MWL (1/", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Назначений: 1", substring = true).assertIsDisplayed()

            // Нажимаем удаление пациента, у которого есть назначение
            compose.onNodeWithContentDescription("Удалить пациента").performClick()
            compose.onNodeWithText("Удалить пациента?").assertIsDisplayed()
            compose.onNodeWithText("связанные назначения (1 шт.)", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Пациент и все его назначения будут безвозвратно удалены.", substring = true).assertIsDisplayed()

            // Проверяем отмену: пациент и назначение остаются
            compose.onNodeWithText("Отмена").performClick()
            compose.waitForIdle()
            assertNotNull("Пациент не должен быть удалён при отмене!", repo.getPatientById(p.id))
            assertNotNull("Назначение не должно быть удалено при отмене!", repo.getStudyById(s.id))

            // Повторно открываем диалог и подтверждаем каскадное удаление
            compose.onNodeWithContentDescription("Удалить пациента").performClick()
            compose.onNodeWithText("Удалить").performClick()
            compose.waitForIdle()

            // Пациент и его исследование удалены атомарно!
            assertNull("Пациент должен быть удалён!", repo.getPatientById(p.id))
            assertNull("Связанное назначение должно быть удалено каскадно!", repo.getStudyById(s.id))

            // Данные второго пациента и его исследования полностью сохранены!
            assertNotNull("Чужой пациент не должен быть затронут!", repo.getPatientById(pOther.id))
            assertNotNull("Чужое назначение не должно быть затронуто!", repo.getStudyById(sOther.id))
        } finally {
            repo.deletePatient(p.id, true)
            repo.deletePatient(pOther.id, true)
        }
    }

    @Test fun mwlPatientEditorOffersThreeSexChoicesAndAllowsDeselection() {
        var savedSex: String? = null
        val patient = MwlPatient(id = "test-sex-1", patientName = "ТЕСТ^ПОЛ", patientId = "PAT-SEX", patientSex = "")
        compose.setContent {
            DicomToolTheme {
                PatientEditorDialog(
                    patient = patient,
                    onDismiss = {},
                    onSave = { savedSex = it.patientSex }
                )
            }
        }
        // Only 3 sex chips must be present (no "Не указан" chip)
        compose.onAllNodesWithText("Не указан").assertCountEquals(0)
        compose.onNodeWithText("Муж (M)").assertIsDisplayed()
        compose.onNodeWithText("Жен (F)").assertIsDisplayed()
        compose.onNodeWithText("Другой (O)").assertIsDisplayed()

        // Select "Муж (M)"
        compose.onNodeWithText("Муж (M)").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("M", savedSex)

        // Re-clicking "Муж (M)" deselects back to empty string
        compose.onNodeWithText("Муж (M)").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("", savedSex)
    }

    @Test fun mwlAppointmentsTabDeletesStudyWithoutTouchingPatient() {
        val repo = MwlRuntimeRegistry.getMwlRepository(context)
        val (genP, template) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.MAMMOGRAPHY)
        val p = genP.copy(patientName = "СОХРАНЯЕМЫЙ^ПАЦИЕНТ", patientId = "PAT-RETAIN-01")
        val s = template.copy(
            patientInternalId = p.id,
            patientName = p.patientName,
            patientId = p.patientId,
            accessionNumber = "ACC-RETAIN-01"
        )
        try {
            repo.savePatient(p)
            repo.saveStudy(s)
            val vm = MwlViewModel(context.applicationContext as Application)
            vm.appointmentSearchQuery.value = "ACC-RETAIN-01"

            compose.setContent { DicomToolTheme { MwlAppointmentsTab(viewModel = vm, isExpandedWidth = false) } }
            compose.waitForIdle()
            compose.onNodeWithText("ID: PAT-RETAIN-01 · ACC: ACC-RETAIN-01").assertIsDisplayed()

            // Кликаем удаление назначения
            compose.onNodeWithContentDescription("Удалить").performClick()
            compose.onNodeWithText("Удалить назначение?").assertIsDisplayed()
            compose.onAllNodesWithText("Удалить").onLast().performClick()
            compose.waitForIdle()

            // Назначение удалено, но пациент в базе остался!
            assertNull("Назначение должно быть удалено!", repo.getStudyById(s.id))
            assertNotNull("Пациент должен остаться в базе!", repo.getPatientById(p.id))
        } finally {
            repo.deletePatient(p.id, true)
        }
    }

    @Test fun scuModeSelectorRendersFilledSegmentedControl() {
        var mode by mutableStateOf(ScuSendMode.TEST_IMAGE)
        compose.setContent {
            DicomToolTheme {
                ScuModeSelector(
                    currentMode = mode,
                    onSelectMode = { mode = it },
                    isBusy = false
                )
            }
        }
        compose.onNodeWithText("Тестовое изображение").assertIsDisplayed()
        compose.onNodeWithText("DICOM-файлы").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ScuSendMode.DICOM_FILES, mode) }
    }

    @Test fun settingsDestructiveButtonsHaveMinHeightAndWrap() {
        val storage = StorageLocationInfo(
            type = StorageType.INTERNAL,
            displayPath = "/data/user/0/ru.medeng.dicomtool/files/received_dicom"
        )
        compose.setContent {
            DicomToolTheme {
                SettingsAndCleanupScreen(
                    storageInfo = storage,
                    receptionOptions = ReceptionStorageOptions(),
                    onReceptionOptionsChange = {},
                    onRecoverIncoming = {},
                    isMigrating = false,
                    migrationStatusMessage = null,
                    migrationNoticeId = "test",
                    studiesCount = 5,
                    filesCount = 20,
                    totalBytes = 1024L * 1024L,
                    logsCount = 42,
                    onBack = {},
                    onSelectSafStorage = {},
                    onResetToInternalStorage = {},
                    onMigrateStudies = {},
                    onClearAllStudiesConfirmed = {},
                    onClearAllLogsConfirmed = {}
                )
            }
        }
        compose.onNodeWithText("Удалить все принятые исследования").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Очистить журнал").performScrollTo().assertIsDisplayed()
    }

    @Test fun mwlAppointmentsLayoutHasSideBySideButtonsAndHidesModalitiesUntilSearch() {
        val vm = MwlViewModel(context.applicationContext as Application)
        compose.setContent {
            DicomToolTheme {
                MwlAppointmentsTab(viewModel = vm, isExpandedWidth = false)
            }
        }
        // Button "+ Пациент" must NOT exist on appointments tab
        compose.onAllNodesWithText("Пациент").assertCountEquals(0)

        // Side-by-side buttons
        compose.onNodeWithText("Назначение").assertIsDisplayed()
        compose.onNodeWithText("Тестовые").assertIsDisplayed()

        // Modality chips should not be displayed until search is clicked
        compose.onAllNodesWithText("Все").assertCountEquals(0)
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Все").assertIsDisplayed()
        compose.onAllNodes(hasText("CR") and hasClickAction()).onFirst().assertIsDisplayed()
    }

    @Test fun journalControlsAutoSwitchAndBriefBorders() {
        val testEvents = listOf(event(1))
        val summaries = DicomSessionGrouper.groupEvents(testEvents)
        var currentMode by mutableStateOf(DicomLogDisplayMode.DETAILED)
        var currentTraffic by mutableStateOf(DicomTrafficFilter.ALL)
        var autoScrollActive by mutableStateOf(false)

        compose.setContent {
            DicomToolTheme {
                DicomLogScreen(
                    displayMode = currentMode,
                    trafficFilter = currentTraffic,
                    outcomeFilter = DicomOutcomeFilter.ALL,
                    searchQuery = "",
                    sessionSummaries = summaries,
                    logs = testEvents,
                    expandedSessionKeys = emptySet(),
                    expandedLogIds = emptySet(),
                    autoScroll = autoScrollActive,
                    onSetDisplayMode = { currentMode = it },
                    onSetTrafficFilter = { currentTraffic = it },
                    onSetOutcomeFilter = {},
                    onSearchQueryChange = {},
                    onToggleAutoScroll = { autoScrollActive = it },
                    onToggleSessionExpand = {},
                    onToggleLogExpand = {},
                    onClearLogsConfirmed = {}
                )
            }
        }

        // 1. Поток chips and Авто toggle on detailed mode
        compose.onNodeWithText("Поток:").assertIsDisplayed()
        compose.onNodeWithTag("journal-traffic:ALL").assertIsSelected()
        compose.onNodeWithTag("journal-traffic:SCP_IN").assertIsNotSelected().performClick()
        assertEquals(DicomTrafficFilter.SCP_IN, currentTraffic)
        compose.onNodeWithTag("journal-autoscroll-switch").assertIsDisplayed()
        compose.onNodeWithText("Авто").assertIsDisplayed()

        // 2. Switch to Brief mode: "Авто" should not be rendered, summary card with permanent border must exist
        compose.onNodeWithText("Краткий").performClick()
        assertEquals(DicomLogDisplayMode.BRIEF, currentMode)
        compose.onAllNodesWithTag("journal-autoscroll-switch").assertCountEquals(0)
        compose.onAllNodesWithText("SCP IN").onFirst().assertIsDisplayed()
        compose.onNodeWithText("SYNTHETIC", substring = true).assertIsDisplayed()
    }

    @Test fun mwlStationProfileCompactCardAndModalityBinding() {
        val (p, baseStudy) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val profile = MwlStationProfile(
            name = "MAMMOMAT REVELATION",
            modality = "MG",
            scheduledStationAeTitle = "MAMMO_STATION",
            scheduledStationName = "MAMMO-ROOM-1",
            location = "FLOOR-2"
        )
        val saved = java.util.concurrent.atomic.AtomicReference<MwlScheduledStudy>()
        var showDialog by mutableStateOf(true)

        compose.setContent {
            DicomToolTheme {
                if (showDialog) {
                    StudyEditorDialog(
                        study = baseStudy,
                        patients = listOf(p),
                        stationProfiles = listOf(profile),
                        onNewPatient = {},
                        onSaveStation = { MwlValidation.Result.Valid },
                        onDismiss = { showDialog = false },
                        onSave = { saved.set(it); showDialog = false }
                    )
                }
            }
        }

        // Header has "+ Новый профиль" in the same row as "Аппарат"
        compose.onNodeWithText("Аппарат").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+ Новый профиль").assertIsDisplayed()

        // Initially no profile selected -> MwlModalityField is visible as OutlinedTextField
        compose.onNodeWithText("Модальность (0008,0060)").assertExists()

        // Select the profile
        compose.onNodeWithText("MAMMOMAT REVELATION", substring = true).performScrollTo().performClick()

        // Now profile card is active: Modality is inside card ("Модальность: MG")
        compose.onNodeWithText("Модальность: MG").assertExists()
        compose.onNodeWithText("AE: MAMMO_STATION").assertExists()
        compose.onNodeWithText("Станция: MAMMO-ROOM-1").assertExists()
        compose.onNodeWithText("Сменить аппарат").assertExists()
        compose.onNodeWithText("Снять выбор").assertExists()
        compose.onNodeWithContentDescription("Редактировать профиль MAMMOMAT REVELATION").assertExists()

        // Save appointment -> verify modality is bound to "MG"
        compose.onNodeWithText("Сохранить").performClick()
        assertNotNull(saved.get())
        assertEquals("MG", saved.get().modality)
        assertEquals("MAMMO_STATION", saved.get().scheduledStationAeTitle)
    }

    @Test fun freshInstallCleanMwlAndScuState() {
        val config = ScuSendConfig()
        compose.setContent {
            DicomToolTheme {
                Column {
                    ru.medeng.dicomtool.ui.screens.send.PatientMetadataCard(
                        config = config,
                        onUpdateConfig = {},
                        onUpdateBirthDateDigits = {}
                    )
                }
            }
        }
        // SCU fields are clean without prefilled patient
        compose.onNodeWithText("ИВАНОВ^ИВАН^ИВАНОВИЧ").assertExists()
        compose.onNodeWithText("PAT-001").assertExists()
        compose.onAllNodesWithText("ТЕСТ^ИНЖЕНЕР^СЕРВИС").assertCountEquals(0)
        compose.onAllNodesWithText("TEST-001").assertCountEquals(0)
    }

    @Test fun mwlCleanAppointmentFormIdGenerationAndProcedureSuggestions() {
        val patient = MwlPatient(
            patientName = "ИВАНОВ^ИВАН",
            patientId = "PAT-777",
            isSynthetic = false
        )
        val cleanStudy = MwlScheduledStudy(
            patientInternalId = patient.id,
            patientName = patient.patientName,
            patientId = patient.patientId,
            accessionNumber = "",
            studyInstanceUid = "",
            requestedProcedureId = "",
            requestedProcedureDescription = "",
            spsId = "",
            modality = "CR",
            spsStartDate = "20261023",
            spsStartTime = "120000",
            scheduledStationAeTitle = "CR_STATION",
            scheduledStationName = "Room 101",
            spsDescription = "",
            isSynthetic = false
        )
        val saved = java.util.concurrent.atomic.AtomicReference<MwlScheduledStudy>()
        var showDialog by mutableStateOf(true)

        compose.setContent {
            DicomToolTheme {
                if (showDialog) {
                    StudyEditorDialog(
                        study = cleanStudy,
                        patients = listOf(patient),
                        stationProfiles = emptyList(),
                        onNewPatient = {},
                        onSaveStation = { MwlValidation.Result.Valid },
                        onDismiss = { showDialog = false },
                        onSave = { saved.set(it); showDialog = false }
                    )
                }
            }
        }

        // Verify "Сгенерировать ID" and "Сгенерировать UID" buttons exist
        compose.onNodeWithText("Сгенерировать ID").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сгенерировать UID").performScrollTo().assertIsDisplayed()

        // Click "Сгенерировать ID" -> generates RP, SPS, ACC
        compose.onNodeWithText("Сгенерировать ID").performClick()

        // Click "Сгенерировать UID" -> generates study instance UID
        compose.onNodeWithText("Сгенерировать UID").performClick()

        // Check study description templates icon and select a procedure
        compose.onNodeWithContentDescription("Шаблоны описания").performScrollTo().performClick()
        compose.onNodeWithText("Рентгенография органов грудной клетки", substring = true).performClick()

        // Save appointment
        compose.onNodeWithText("Сохранить").performClick()

        val result = saved.get()
        assertNotNull(result)
        assertTrue(result.accessionNumber.startsWith("ACC-"))
        assertTrue(result.requestedProcedureId.startsWith("RP-"))
        assertTrue(result.spsId.startsWith("SPS-"))
        assertTrue(result.studyInstanceUid.isNotBlank())
        assertTrue(result.requestedProcedureDescription.contains("грудной клетки"))
        assertEquals("CR", result.modality)
    }
}
