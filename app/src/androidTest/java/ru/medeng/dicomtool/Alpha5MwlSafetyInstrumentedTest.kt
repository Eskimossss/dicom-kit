package ru.medeng.dicomtool

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.mwl.MwlRuntimeRegistry
import ru.medeng.dicomtool.mwl.ui.MwlScuTab
import ru.medeng.dicomtool.mwl.ui.MwlAppointmentsTab
import ru.medeng.dicomtool.mwl.ui.MwlViewModel
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.ui.theme.DicomToolTheme
import java.io.File

class Alpha5MwlSafetyInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedProfileSaveShowsErrorRetainsDraftAndCanBeRetriedWithoutLosingCommittedProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".storagesafetytest"))
        val profiles = MwlRuntimeRegistry.getProfileRepository(context)
        val original = profiles.getDefaultProfile()!!
        val file = File(context.filesDir, "mwl_data/mwl_scu_profiles.json")
        val originalBytes = file.readBytes()
        val blocked = File(file.parentFile, "mwl_scu_profiles.json.tmp")
        check(!blocked.exists() && blocked.mkdir())
        val model = MwlViewModel(context.applicationContext as Application)
        try {
            model.openEditProfileDialog(original)
            compose.setContent { DicomToolTheme { MwlScuTab(model, isExpandedWidth = true) } }
            compose.onNodeWithText("Название профиля").performTextReplacement("SYNTHETIC RETRY")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Не удалось сохранить профиль MWL SCU.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("SYNTHETIC RETRY").assertExists()
            assertTrue(model.showProfileDialog.value)
            assertEquals(original, profiles.getProfileById(original.id))
            assertEquals(original, model.selectedProfile.value)
            assertArrayEquals(originalBytes, file.readBytes())
            assertTrue(blocked.delete())
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitForIdle()
            assertFalse(model.showProfileDialog.value)
            assertNull(model.profileSaveError.value)
            assertEquals("SYNTHETIC RETRY", profiles.getProfileById(original.id)!!.name)
            assertEquals("SYNTHETIC RETRY", model.selectedProfile.value!!.name)
        } finally {
            if (blocked.isDirectory) check(blocked.delete())
            profiles.saveProfile(original)
        }
    }

    @Test fun importingOneAppointmentUpdatesBothCardsAndBothPatientSearchKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".storagesafetytest"))
        val repo = MwlRuntimeRegistry.getMwlRepository(context)
        val (generated, template) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val p = generated.copy(patientName = "ORIGINAL^SYNTHETIC", patientId = "ALPHA5-OLD")
        val incomingDir = File(context.cacheDir, "alpha5-import-${java.util.UUID.randomUUID()}")
        try {
            repo.savePatient(p)
            val first = repo.saveStudy(template.copy(accessionNumber = "ALPHA5-FIRST"))
            val second = repo.duplicateStudy(first.id)!!
            repo.saveStudy(second.copy(accessionNumber = "ALPHA5-SECOND"))
            val incoming = LocalMwlRepository(incomingDir, false)
            incoming.savePatient(p.copy(patientName = "UPDATED^SYNTHETIC", patientId = "ALPHA5-PATIENT"))
            incoming.saveStudy(first)
            val model = MwlViewModel(context.applicationContext as Application)
            assertEquals(1, model.importSyntheticData(incoming.exportSyntheticJson()))
            compose.setContent { DicomToolTheme { MwlAppointmentsTab(model, isExpandedWidth = true) } }
            val search = compose.onNodeWithText("Поиск по ФИО, ID, Accession, SPS…")
            search.performTextInput("ALPHA5-PATIENT")
            for (acc in listOf("ALPHA5-FIRST", "ALPHA5-SECOND")) {
                val text = "ID: ALPHA5-PATIENT · ACC: $acc"
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
                compose.onNodeWithText(text).assertIsDisplayed()
                compose.onNodeWithText(text).onParent().assert(hasAnyDescendant(hasText("UPDATED SYNTHETIC")))
            }
            compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
            compose.onNodeWithText("ALPHA5-PATIENT", substring = false).performTextReplacement("UPDATED")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("ID: ALPHA5-PATIENT · ACC: ALPHA5-SECOND"))
            compose.onNodeWithText("ID: ALPHA5-PATIENT · ACC: ALPHA5-SECOND").assertIsDisplayed()
        } finally {
            repo.deletePatient(p.id, true)
            incomingDir.deleteRecursively()
        }
    }
}
