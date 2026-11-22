package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.syncr.prototype.ui.theme.Level
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.raised
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

/**
 * The app's bottom sheet: the level-two surface with a faint top edge in dark mode, 16dp top corners, a quiet handle, full height (no half state) and the
 * same side margins everywhere. Material's sheet already follows predictive back and keeps its content above
 * the navigation bar. [title] is optional and announced as a heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncrSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Syncr.colors
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = c.surface2, contentColor = c.ink, scrimColor = c.scrim, tonalElevation = 0.dp,
        modifier = Modifier.sheetEdge(c.dark),
        dragHandle = {
            Box(Modifier.padding(top = Space.s, bottom = Space.xs).size(width = 36.dp, height = 4.dp)
                .background(c.lineStrong, Radius.pill))
        }) {
        SheetSystemBars(c.dark)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xl).padding(top = Space.s, bottom = Space.xl)) {
            if (title != null) Text(title, style = Syncr.type.subheading, color = c.ink,
                modifier = Modifier.semantics { heading() }.padding(bottom = Space.xs))
            content()
        }
    }
}

/** Sheets/dialogs have separate windows: keep the status bar hidden there too. */
@Composable
fun SheetSystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}

/** The sheet's 1 dp top-edge highlight in dark mode (white at 6%), drawn once with the sheet. */
private fun Modifier.sheetEdge(dark: Boolean): Modifier =
    if (!dark) this else this.drawWithContent {
        drawContent()
        drawRect(Color.White.copy(alpha = 0.06f), size = Size(size.width, 1.dp.toPx()))
    }

/**
 * How a dialog leaves: it fades out the way it faded in, and only then does what was asked (close it, save, delete). A
 * second request while it is leaving is ignored, and a request that leaves the dialog open brings it back.
 */
internal class DialogExit(private val shown: MutableTransitionState<Boolean>, private val scope: CoroutineScope) {
    private var leaving = false
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        shown.targetState = false
        scope.launch {
            snapshotFlow { shown.isIdle && !shown.currentState }.first { it }
            then()
            // Still here (the action did not close it): fade back in.
            leaving = false; shown.targetState = true
        }
    }
}

/** The dialog's exit, for what its body does on its own (a keyboard's Done); null outside a dialog. */
internal val LocalDialogExit = staticCompositionLocalOf<DialogExit?> { null }
/** True inside a dialog's own button row: [TextAction] and [PrimaryAction] there leave the dialog before acting. */
internal val LocalInDialogButtons = staticCompositionLocalOf { false }

/** [onClick] run after the dialog has left, when this is a dialog button; unchanged anywhere else. */
@Composable
internal fun rememberDialogAction(onClick: () -> Unit): () -> Unit {
    val exit = LocalDialogExit.current
    val inButtons = LocalInDialogButtons.current
    val latest = rememberUpdatedState(onClick)
    return if (exit != null && inButtons) ({ exit.leave { latest.value() } }) else onClick
}

/**
 * The app's dialog: a centred card on the level-three surface, a title in the title style, body copy in the body
 * style and right-aligned text actions supplied by the caller. It fades in on [Motion.MENU_MS] with a slight scale
 * from 0.96 and leaves on the same fade (both a fade alone with animations off): the window stays up until the fade
 * is done, then [onDismiss] or the button's action runs.
 */
@Composable
fun SyncrDialog(onDismiss: () -> Unit, title: String, confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    val reduced = Syncr.reducedMotion
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val scope = rememberCoroutineScope()
    val exit = remember { DialogExit(shown, scope) }
    val request = rememberUpdatedState(onDismiss)
    Dialog({ exit.leave { request.value() } }, DialogProperties(usePlatformDefaultWidth = false)) {
        SheetSystemBars(Syncr.colors.dark)
        CompositionLocalProvider(LocalDialogExit provides exit) {
            AnimatedVisibility(visibleState = shown, enter = Motion.dialogIn(reduced), exit = Motion.menuOut(reduced)) {
                Column(Modifier.padding(horizontal = Space.xl).widthIn(max = 560.dp).fillMaxWidth()
                    .raised(Level.THREE, Radius.card).padding(start = Space.xl, end = Space.s, top = Space.xl, bottom = Space.s)) {
                    Text(title, style = Syncr.type.subheading, color = Syncr.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = Space.m).semantics { heading() })
                    Box(Modifier.padding(top = Space.m, end = Space.m)) { body() }
                    CompositionLocalProvider(LocalInDialogButtons provides true) { DialogButtons { dismiss?.invoke(); confirm() } }
                }
            }
        }
    }
}

/** Body copy for [SyncrDialog]. */
@Composable
fun DialogText(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = Syncr.type.body, color = Syncr.colors.ink)

/** Secondary copy for [SyncrDialog]. */
@Composable
fun DialogDetail(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = Syncr.type.caption, color = Syncr.colors.muted)

/** Stacks dialog buttons end-aligned, wrapping on narrow screens and large fonts. */
@Composable
fun DialogButtons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.End,
        itemVerticalAlignment = Alignment.CenterVertically) { content() }
}
