package ru.medeng.dicomtool

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.dicom.scu.SyntheticDicomEncoding
import ru.medeng.dicomtool.ui.viewmodel.DicomToolViewModel

class Stage6GeneratorInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun selectorRejectsCyrillicAsciiWithoutStalePreparedObjectAndPersistsSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".storagesafetytest"))
        lateinit var model: DicomToolViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get(DicomToolViewModel::class.java) }
        val original = model.scuConfig.value
        try {
            compose.runOnIdle { model.updateScuConfig { it.copy(syntheticEncoding = SyntheticDicomEncoding.UTF8,
                patientName = "ТЕСТ^СИНТЕТИКА", patientId = "SYNTHETIC", studyDescription = "SYNTHETIC", seriesDescription = "SYNTHETIC") } }
            val pixels = model.preparedScuObject.value!!.pixelDataBytes.clone()
            compose.onNodeWithContentDescription("Отправка", useUnmergedTree = true).performClick()
            compose.onNodeWithText(SyntheticDicomEncoding.UTF8.label).performScrollTo().performClick()
            compose.onNodeWithText(SyntheticDicomEncoding.ASCII.label).performClick()
            assertEquals(SyntheticDicomEncoding.ASCII, model.scuConfig.value.syntheticEncoding)
            assertNull(model.preparedScuObject.value)
            compose.onNodeWithText("C-STORE").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { model.triggerCStoreScu() }
            assertEquals(false, model.scuConfig.value.isLastOperationSuccess)
            assertFalse(model.scuConfig.value.isOperationInProgress)
            assertTrue(model.scuConfig.value.lastStatusMessage!!.contains("несовместимы"))
            compose.runOnIdle { model.updateScuConfig { it.copy(patientName = "SYNTHETIC^ASCII") } }
            assertNotNull(model.preparedScuObject.value)
            assertArrayEquals(pixels, model.preparedScuObject.value!!.pixelDataBytes)
            compose.onNodeWithText("C-STORE").performScrollTo().assertIsEnabled()
            assertEquals("ASCII", context.getSharedPreferences("dicom_scu_settings", 0).getString("synthetic_encoding", null))
            compose.activityRule.scenario.recreate()
            assertEquals(SyntheticDicomEncoding.ASCII, model.scuConfig.value.syntheticEncoding)
            // A new VM reads persisted encoding safely even though default Cyrillic fields need editing.
            compose.runOnIdle {
                val fresh = ViewModelProvider(compose.activity).get("synthetic-encoding-restored", DicomToolViewModel::class.java)
                assertEquals(SyntheticDicomEncoding.ASCII, fresh.scuConfig.value.syntheticEncoding)
                assertNull(fresh.preparedScuObject.value)
            }
        } finally { compose.runOnIdle { model.updateScuConfig { original } } }
    }
}
