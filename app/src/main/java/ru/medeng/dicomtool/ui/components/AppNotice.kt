package ru.medeng.dicomtool.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Presentation only. Dismissing never mutates results, diagnostics, files or journal records. */
class NoticePresentationState {
    val dismissed = mutableStateListOf<String>()
    val firstShown = mutableMapOf<String, Long>()
    fun dismiss(id: String) { if (id !in dismissed) dismissed.add(id) }
    companion object {
        val saver = Saver<NoticePresentationState, List<String>>(
            save = { state -> state.dismissed.map { "D:$it" } + state.firstShown.map { "T:${it.value}:${it.key}" } },
            restore = { rows -> NoticePresentationState().apply { rows.forEach { row ->
                if (row.startsWith("D:")) dismissed.add(row.substring(2))
                else if (row.startsWith("T:")) {
                    val fields = row.substring(2).split(':', limit = 2)
                    if (fields.size == 2) fields[0].toLongOrNull()?.let { firstShown[fields[1]] = it }
                }
            } } })
    }
}
private val LocalNotices = staticCompositionLocalOf<NoticePresentationState?> { null }

@Composable
fun NoticePresentationHost(content: @Composable () -> Unit) {
    val state = rememberSaveable(saver = NoticePresentationState.saver) { NoticePresentationState() }
    CompositionLocalProvider(LocalNotices provides state, content = content)
}

@Composable
fun NoticeVisibility(eventId: String, autoHideSuccess: Boolean = false, content: @Composable (() -> Unit) -> Unit) {
    val fallback = rememberSaveable(saver = NoticePresentationState.saver) { NoticePresentationState() }
    val state = LocalNotices.current ?: fallback
    val shown = remember(eventId) { state.firstShown.getOrPut(eventId) { android.os.SystemClock.elapsedRealtime() } }
    LaunchedEffect(eventId, autoHideSuccess) {
        if (autoHideSuccess && eventId !in state.dismissed) {
            delay((6000 - (android.os.SystemClock.elapsedRealtime() - shown).coerceAtLeast(0)).coerceIn(0, 6000))
            state.dismiss(eventId)
        }
    }
    if (eventId !in state.dismissed) content { state.dismiss(eventId) }
}

@Composable
fun NoticeCloseButton(onDismiss: () -> Unit) {
    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Close, "Закрыть уведомление", modifier = Modifier.size(18.dp))
    }
}

@Composable
fun AppNotice(eventId: String, text: String, success: Boolean = false, error: Boolean = false, modifier: Modifier = Modifier) {
    NoticeVisibility(eventId, success) { dismiss ->
        Surface(modifier.fillMaxWidth(), color = when { success -> Color(0xFFECFDF5); error -> MaterialTheme.colorScheme.errorContainer; else -> Color(0xFFFFF4CE) },
            shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.Top) {
                Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                NoticeCloseButton(dismiss)
            }
        }
    }
}
