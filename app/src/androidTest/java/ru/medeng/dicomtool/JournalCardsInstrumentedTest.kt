package ru.medeng.dicomtool

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.ui.screens.log.DicomDetailedLogRow
import ru.medeng.dicomtool.ui.theme.DicomToolTheme
import java.io.File

/** Synthetic events; checks wrapping and actions at phone/tablet widths without changing device settings. */
class JournalCardsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val longUid = "1.2.238.03.127.0.2907.20261022090340.12345678901234567890"
    private fun event(i: Int) = DicomLogEntry("event-$i", "12:00:00.000", DicomLogCategory.C_STORE_RSP,
        DicomTrafficDirection.INBOUND, "SCP-SYNTHETIC-LONG-ASSOCIATION-$i", "Событие $i",
        listOf("SOP Instance UID: $longUid"), mapOf("SOP Instance UID" to longUid,
            "DICOM Status" to if (i == 2) "0x0110" else "0x0000") +
            if (i == 1) mapOf("UID Warning" to "(0008,0018): leading zero, preserved verbatim") else emptyMap(),
        isError = i == 2)

    @Test fun cardsWrapLongValuesKeepActionsAndScrollThroughDenseEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".storagesafetytest"))
        var width by mutableStateOf(320)
        var expanded by mutableStateOf(emptySet<Int>())
        var copied = 0
        compose.setContent {
            DicomToolTheme {
                LazyColumn(Modifier.width(width.dp).fillMaxHeight(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items((0..79).toList(), key = { it }) { i ->
                        Box(Modifier.testTag("card-$i")) {
                            DicomDetailedLogRow(event(i), i in expanded,
                                { expanded = if (i in expanded) expanded - i else expanded + i }, { copied++ })
                        }
                    }
                }
            }
        }
        for (size in listOf(320, 800)) {
            compose.runOnIdle { width = size }
            compose.onNodeWithText("Событие 0").performClick()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(longUid).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse(layouts.joinToString { "width=$size layout=${it.size} lines=${it.lineCount} overflow=${it.hasVisualOverflow} right=${(0 until it.lineCount).map(it::getLineRight)} bottom=${it.getLineBottom(it.lineCount - 1)}" },
                layouts.any { (0 until it.lineCount).any { line -> it.isLineEllipsized(line) || it.getLineRight(line) > it.size.width + 1f } || it.getLineBottom(it.lineCount - 1) > it.size.height + 1f })
            compose.onAllNodesWithContentDescription("Копировать событие").onFirst().performClick()
            compose.runOnIdle { assertEquals(if (size == 320) 1 else 2, copied) }
            compose.onNodeWithText("Событие 0").performScrollTo().performClick()
            println("Journal collapsed event at $size dp: ${compose.onNodeWithTag("card-0").fetchSemanticsNode().boundsInRoot.height} px")
            compose.onAllNodesWithText("Успешно").onFirst().assertExists()
            compose.onNodeWithText("Успешно · с предупреждениями").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Ошибка").performScrollTo().assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            val dir = File(context.getExternalFilesDir(null), "journal-cards").apply { mkdirs() }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(dir, "$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(79)
            compose.onNodeWithText("Событие 79").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        }
    }

    @Test fun localizedStorageWarningShowsOriginalAndKeepsAccessibleActionsAtPhoneAndTabletWidths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".storagesafetytest"))
        var width by mutableStateOf(320)
        var expanded by mutableStateOf(false)
        var copies = 0
        val original = "C-STORE storage verified (18.30 MB); response pending"
        val warning = "SOP Instance UID (0008,0018): non-conformant UID (leading zero), preserved verbatim: $longUid"
        val source = event(1).copy(title = original, summaryLines = listOf("SOP Instance UID: $longUid", "No exception reported", "Предупреждение UID: $warning"),
            technicalDetails = mapOf("DICOM Status" to "0x0000", "Storage Result" to "VERIFIED", "UID Warning" to warning, "SOP Instance UID" to longUid))
        compose.setContent {
            DicomToolTheme {
                Column(Modifier.width(width.dp).fillMaxHeight()) {
                    // The scroll container models expanded technical details on a phone.
                    LazyColumn {
                        item { DicomDetailedLogRow(source, expanded, { expanded = !expanded }, { copies++ }) }
                    }
                }
            }
        }
        for (size in listOf(320, 800)) {
            compose.runOnIdle { width = size; expanded = false }
            compose.onNodeWithText("Успешно · с предупреждениями").assertIsDisplayed()
            compose.onNodeWithText("DICOM-файл сохранён и проверен (18,30 МБ); ответ ещё не отправлен").assertIsDisplayed()
            val localizedLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("DICOM-файл сохранён и проверен (18,30 МБ); ответ ещё не отправлен")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(localizedLayouts) }
            assertTrue(localizedLayouts.isNotEmpty())
            assertFalse(localizedLayouts.any { layout ->
                (0 until layout.lineCount).any { layout.isLineEllipsized(it) || layout.getLineRight(it) > layout.size.width + 1f } ||
                    layout.getLineBottom(layout.lineCount - 1) > layout.size.height + 1f
            })
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.onNodeWithContentDescription("Копировать событие").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle { assertEquals(if (size == 320) 1 else 2, copies) }
            compose.onNodeWithContentDescription("Раскрыть событие").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("Оригинальный технический текст").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Предупреждение UID: $warning").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Свернуть событие").performScrollTo().performClick()
            compose.onNodeWithText(original).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val dir = File(context.getExternalFilesDir(null), "journal-cards").apply { mkdirs() }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(dir, "$size-localized.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
