package ru.medeng.dicomtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry
import ru.medeng.dicomtool.ui.navigation.DicomToolAppRoot
import ru.medeng.dicomtool.ui.theme.DicomToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        setContent {
            DicomToolTheme {
                if (ready.value) ru.medeng.dicomtool.ui.components.NoticePresentationHost { DicomToolAppRoot() }
                else Text(error.value ?: "Подготовка настроек и архива…")
            }
        }
        val app = applicationContext
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // SharedPreferences getters may wait for XML disk reads on a cold process.
                    // Warm them before creating the UI's ViewModel; no SAF query runs on main.
                    listOf("dicom_storage_settings", "scp_configuration", "dicom_scu_settings", "mwl_scp_config").forEach {
                        app.getSharedPreferences(it, MODE_PRIVATE).all
                    }
                    DicomRuntimeRegistry.getArchiveOperations(app)
                    DicomRuntimeRegistry.getScuClient(app)
                    ru.medeng.dicomtool.mwl.MwlRuntimeRegistry.getMwlRepository(app)
                }
                ready.value = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error.value = "Не удалось подготовить приложение: ${failure.javaClass.simpleName}. Повторите запуск."
            }
        }
    }
}
