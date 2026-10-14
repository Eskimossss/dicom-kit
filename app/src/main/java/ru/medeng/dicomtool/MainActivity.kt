package ru.medeng.dicomtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.medeng.dicomtool.ui.navigation.DicomToolAppRoot
import ru.medeng.dicomtool.ui.theme.DicomToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DicomToolTheme {
                DicomToolAppRoot()
            }
        }
    }
}
