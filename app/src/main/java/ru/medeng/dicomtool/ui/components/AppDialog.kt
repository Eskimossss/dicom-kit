package ru.medeng.dicomtool.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Constrains dialog content to the native visible frame, including the software keyboard.
 * Some Android/Compose combinations measure a floating dialog against the entire display while
 * reporting already-adjusted IME insets. Using both that measurement and imePadding overestimates
 * the available space. The native visible frame provides one consistent boundary instead.
 */
@Composable
internal fun AppDialog(onDismiss: () -> Unit, padding: PaddingValues = PaddingValues(16.dp), content: @Composable BoxWithConstraintsScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val density = LocalDensity.current
        var viewport by remember(view) { mutableStateOf(DialogViewport()) }
        DisposableEffect(view) {
            val update = {
                val frame = Rect()
                view.getWindowVisibleDisplayFrame(frame)
                val origin = IntArray(2).also { view.getLocationOnScreen(it) }
                if (frame.width() > 0 && frame.height() > 0) viewport = DialogViewport(
                    (frame.left - origin[0]).coerceAtLeast(0), (frame.top - origin[1]).coerceAtLeast(0), frame.width(), frame.height())
            }
            val observer = ViewTreeObserver.OnGlobalLayoutListener { update() }
            val tree = view.viewTreeObserver
            tree.addOnGlobalLayoutListener(observer)
            view.post { update() }
            onDispose { if (tree.isAlive) tree.removeOnGlobalLayoutListener(observer) }
        }
        val maxWidth = if (viewport.width > 0) with(density) { viewport.width.toDp() } else Dp.Infinity
        val maxHeight = if (viewport.height > 0) with(density) { viewport.height.toDp() } else Dp.Infinity
        val compact = maxHeight < 240.dp
        val contentPadding = PaddingValues(
            start = padding.calculateLeftPadding(LayoutDirection.Ltr), end = padding.calculateRightPadding(LayoutDirection.Ltr),
            top = if (compact) 4.dp else padding.calculateTopPadding(), bottom = if (compact) 4.dp else padding.calculateBottomPadding())
        // Keep the window measurement stable; constrain its content rather than resizing the window
        // in response to its own visible frame (which would create a resize/IME feedback loop).
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.offset(x = with(density) { viewport.left.toDp() }, y = with(density) { viewport.top.toDp() })
                .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight).fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center, content = content)
        }
    }
}

private data class DialogViewport(val left: Int = 0, val top: Int = 0, val width: Int = 0, val height: Int = 0)
