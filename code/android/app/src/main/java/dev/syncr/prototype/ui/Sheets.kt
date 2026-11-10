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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
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

/** Sheets and dialogs get their own window; keep its bar icons readable against our colours. */
@Composable
fun SheetSystemBars(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
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
 * The app's dialog: a centred card on the level-three surface, a title in the title style, body copy in the body
 * style and right-aligned text actions supplied by the caller. It fades in on [Motion.MENU_MS] with a slight scale
 * from 0.96 (a fade alone with animations off).
 */
@Composable
fun SyncrDialog(onDismiss: () -> Unit, title: String, confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    val reduced = Syncr.reducedMotion
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        SheetSystemBars(Syncr.colors.dark)
        AnimatedVisibility(visibleState = shown, enter = Motion.dialogIn(reduced), exit = Motion.menuOut(reduced)) {
            Column(Modifier.padding(horizontal = Space.xl).widthIn(max = 560.dp).fillMaxWidth()
                .raised(Level.THREE, Radius.card).padding(start = Space.xl, end = Space.s, top = Space.xl, bottom = Space.s)) {
                Text(title, style = Syncr.type.subheading, color = Syncr.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = Space.m).semantics { heading() })
                Box(Modifier.padding(top = Space.m, end = Space.m)) { body() }
                DialogButtons { dismiss?.invoke(); confirm() }
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
