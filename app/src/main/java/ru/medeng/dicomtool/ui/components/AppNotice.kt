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
    val firstShown = linkedMapOf<String, Long>()
    fun registeredAt(id: String, now: Long): Long {
        firstShown[id]?.let { return it }
        // Bound saved instance state for long engineering sessions. Old, off-screen events may reappear on revisit.
        while (firstShown.size >= 256) {
            val oldest = firstShown.keys.first()
            firstShown.remove(oldest); dismissed.remove(oldest)
        }
        firstShown[id] = now
        return now
    }
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
    // Saved UI state contains no source URI, patient text or raw diagnostic message.
    val token = remember(eventId) { java.security.MessageDigest.getInstance("SHA-256")
        .digest(eventId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
    val shown = remember(token) { state.registeredAt(token, android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(token, autoHideSuccess) {
        if (autoHideSuccess && token !in state.dismissed) {
            delay((6000 - (android.os.SystemClock.elapsedRealtime() - shown).coerceAtLeast(0)).coerceIn(0, 6000))
            state.dismiss(token)
        }
    }
    if (token !in state.dismissed) content { state.dismiss(token) }
}

@Composable
fun NoticeCloseButton(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onDismiss, modifier = modifier.size(40.dp)) {
        Icon(Icons.Default.Close, "Закрыть уведомление", modifier = Modifier.size(18.dp))
    }
}

@Composable
fun AppNotice(eventId: String, text: String, modifier: Modifier = Modifier, success: Boolean = false, error: Boolean = false) {
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
