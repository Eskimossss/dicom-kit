package ru.medeng.dicomtool

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.ui.screens.log.DicomLogScreen
import ru.medeng.dicomtool.ui.theme.DicomToolTheme

@RunWith(AndroidJUnit4::class)
class JournalSafetyInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val phi = "СИНТЕТИЧЕСКИЙ^ИВАНОВ"
    private val entry = DicomLogEntry("synthetic-event", "12:00:00.000", DicomLogCategory.C_STORE_RSP,
        DicomTrafficDirection.INBOUND, "SCU-1", "Ошибка: $phi", listOf("PatientID=PID-SECRET"),
        mapOf("Status" to "0x0000", "Error Comment" to "$phi /patients/Иванов.dcm", "PatientID" to "PID-SECRET"),
        sessionUid = "SYNTHETIC-JOURNAL-UI")
    @Before fun isolatedPackageOnly() { check(context.packageName.endsWith(".storagesafetytest")) }
    private fun show(mode: DicomLogDisplayMode, expanded: Boolean = false, health: LoggerHealth = LoggerHealth()) {
        val session = DicomSessionGrouper.groupEvents(listOf(entry)).single()
        compose.setContent { DicomToolTheme { DicomLogScreen(mode, DicomTrafficFilter.ALL, DicomOutcomeFilter.ALL, "",
            listOf(session), listOf(entry), if (expanded) setOf(session.sessionKey) else emptySet(), emptySet(), false,
            {}, {}, {}, {}, {}, {}, {}, {}, loggerHealth = health) } }
    }
    private fun clipboard(): String {
        var text = ""
        compose.runOnIdle { text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString() ?: "" }
        return text
    }
    @Test fun eventCopyDefaultsToSafeSummaryWithPreview() {
        show(DicomLogDisplayMode.DETAILED)
        compose.onNodeWithContentDescription("Копировать событие").performClick()
        compose.onNodeWithText("Предварительный просмотр:").assertIsDisplayed()
        compose.onNodeWithText("Копировать сводку").performClick()
        val text = clipboard()
        assertTrue(text.contains("Status: 0x0000")); assertFalse(text.contains(phi)); assertFalse(text.contains("PID-SECRET"))
    }
    @Test fun fullEventCopyRequiresExplicitSelectionAndWarns() {
        show(DicomLogDisplayMode.DETAILED)
        compose.onNodeWithContentDescription("Копировать событие").performClick()
        compose.onNodeWithText("Полный технический журнал (может содержать персональные данные)").performClick()
        compose.onNodeWithText("Копировать полный текст").performClick()
        assertTrue(clipboard().contains(phi)); assertTrue(clipboard().contains("может содержать персональные данные"))
    }
    @Test fun wholeSessionCopyUsesSameSafePolicy() {
        show(DicomLogDisplayMode.BRIEF, expanded = true)
        compose.onNodeWithText("Копировать данные сеанса").performScrollTo().performClick()
        compose.onNodeWithText("Копировать сводку").performClick()
        assertTrue(clipboard().contains("Status: 0x0000")); assertFalse(clipboard().contains(phi))
    }
    @Test fun compactLoggerWarningOpensTechnicalDetails() {
        val health = LoggerHealth(state = LoggerWriteState.WRITE_FAILED, writeUnconfirmed = 3,
            criticalWriteUnconfirmed = 2, lastError = "IOException: synthetic disk unavailable")
        show(DicomLogDisplayMode.DETAILED, health = health)
        compose.onNodeWithText(health.warning).performClick()
        compose.onNodeWithText("Состояние журнала").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
    }
    @Test fun preparedExportAndFileProviderContainExactlyPreviewSnapshot() {
        val snapshot = DicomLogExportUtils.formatFullExport(emptyList(), listOf(entry), false, true)
        val file = DicomLogExportUtils.prepareExportFile(context, snapshot, true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val actual = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(snapshot, actual); assertFalse(actual.contains(phi)); assertFalse(file.name.contains(phi))
        val second = DicomLogExportUtils.prepareExportFile(context, snapshot, true)
        assertNotEquals(file.name, second.name)
    }
}
