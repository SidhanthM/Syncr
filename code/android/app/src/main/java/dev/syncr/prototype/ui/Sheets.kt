package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
 * The app's bottom sheet: card colour, 16dp top corners, a quiet handle, full height (no half state) and the
 * same side margins everywhere. Material's sheet already follows predictive back and keeps its content above
 * the navigation bar. [title] is optional and announced as a heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncrSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Syncr.colors
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = c.chrome, contentColor = c.ink, scrimColor = c.scrim, tonalElevation = 0.dp,
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

/**
 * The app's alert dialog: card colour, card radius, a title in the title style and body copy in the body style.
 * Buttons are [TextAction]s supplied by the caller.
 */
@Composable
fun SyncrDialog(onDismiss: () -> Unit, title: String, confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    val c = Syncr.colors
    AlertDialog(onDismiss, confirmButton = confirm, dismissButton = dismiss,
        shape = Radius.card, containerColor = c.chrome, tonalElevation = 0.dp,
        title = { Text(title, style = Syncr.type.subheading, color = c.ink,
            maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = body)
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
    FlowRow(horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) { content() }
}
