package dev.syncr.prototype.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.ui.theme.Syncr

/** Small quiet label, sentence case. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Syncr.colors.muted) {
    Text(text, modifier, color = color, style = Syncr.type.label)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Label(text, modifier.semantics { heading() }.padding(top = 20.dp, bottom = 8.dp))
}

/** Hairline rounded outline for cards. Dotted lines are reserved for the lasso and selection. */
fun Modifier.hairlineBorder(color: Color, radius: Dp = 8.dp, width: Dp = 1.dp) = drawBehind {
    val w = width.toPx()
    drawRoundRect(color, topLeft = Offset(w / 2, w / 2), size = size.copy(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()), style = Stroke(w))
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Syncr.colors.dots) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height)
    }
}

/** Minimal text action with a 48dp touch target. Monochrome. */
@Composable
fun LabelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    description: String? = null) {
    TextButton(onClick, modifier.heightIn(min = 48.dp).let { m ->
        if (description != null) m.semantics { contentDescription = description } else m
    }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(text, style = Syncr.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = if (enabled) Syncr.colors.ink else Syncr.colors.muted.copy(alpha = 0.5f))
    }
}

/** A full-width tappable row for sheets and menus. */
@Composable
fun MenuRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 10.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = Syncr.type.title, color = if (enabled) Syncr.colors.ink else Syncr.colors.muted)
        if (subtitle != null) Text(subtitle, style = Syncr.type.caption,
            color = Syncr.colors.muted)
    }
}

/** A full-width tappable row with a leading line icon, for note actions in sheets. */
@Composable
fun IconMenuRow(glyph: MenuGlyph, title: String, subtitle: String? = null, enabled: Boolean = true,
    tint: Color = Syncr.colors.ink, onClick: () -> Unit) {
    val colour = if (enabled) tint else Syncr.colors.muted
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuIcon(glyph, colour)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = Syncr.type.body, color = colour)
            if (subtitle != null) Text(subtitle, style = Syncr.type.caption, color = Syncr.colors.muted)
        }
    }
}

/**
 * Compact, actionable status. Shown only when something needs the user: a failed or exported-but-unsaved
 * save (Retry / Export recovery / Resolve) or a plain message (Retry / Dismiss). Raw detail on request.
 */
@Composable
fun StatusBanner(state: NotesState, onRetry: () -> Unit, onExport: () -> Unit, onResolve: () -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val error = state.error
    val attention = state.unsaved && (error != null || state.recoveryExported)
    if (!attention && error == null) return
    var details by remember(error) { mutableStateOf(false) }
    val c = Syncr.colors
    val message = when {
        error != null -> (if (state.unsaved) null else libraryTaskFailure(state.libraryTask)) ?: friendlyError(error, saving = state.unsaved)
        else -> "Recovery exported. These changes are still not saved to the note."
    }
    Column(modifier.fillMaxWidth().background(c.chrome, RoundedCornerShape(8.dp)).hairlineBorder(c.attention, radius = 8.dp)
        .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Non-colour cue alongside the amber edge.
            Text("!", color = c.attention, style = Syncr.type.display.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(message, style = Syncr.type.body, color = c.ink)
                if (state.unsaved) Text("Unsaved changes stay on screen until saved, exported or discarded.",
                    style = Syncr.type.caption, color = c.muted)
                if (details && error != null) Text(error, style = Syncr.type.caption,
                    color = c.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (error != null && error != message) LabelButton(if (details) "Hide" else "Details", { details = !details })
            Spacer(Modifier.weight(1f))
            LabelButton("Retry", onRetry, enabled = !state.busy)
            if (state.unsaved) {
                if (!state.recoveryExported) LabelButton("Export", onExport, enabled = !state.busy, description = "Export recovery")
                LabelButton("Resolve", onResolve, enabled = !state.busy, description = "Resolve unsaved changes")
            } else LabelButton("Dismiss", onDismiss)
        }
    }
}
