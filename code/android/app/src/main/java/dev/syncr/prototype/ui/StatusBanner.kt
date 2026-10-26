package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

/** What the banner says and offers, worked out from the repository state; null when nothing needs the user. */
data class BannerContent(val message: String, val detail: String?, val raw: String?, val unsaved: Boolean,
    val canExport: Boolean)

fun bannerContent(state: NotesState): BannerContent? {
    val error = state.error
    val attention = state.unsaved && (error != null || state.recoveryExported)
    if (!attention && error == null) return null
    val message = when {
        error != null -> (if (state.unsaved) null else libraryTaskFailure(state.libraryTask)) ?: friendlyError(error, saving = state.unsaved)
        else -> "Copy exported. These changes aren't saved to the note yet."
    }
    return BannerContent(message,
        detail = if (state.unsaved) "Your writing stays on screen until it's saved, exported or discarded." else null,
        raw = error?.takeIf { it != message }, unsaved = state.unsaved, canExport = state.unsaved && !state.recoveryExported)
}

/**
 * The one place problems are shown: a save that failed or was only exported (Retry, Export a copy, Options)
 * or a plain message (Retry, Dismiss). The amber mark and the circled "!" carry the state together, the copy is
 * announced politely, and the technical detail is one tap away. It grows in place rather than jumping.
 */
@Composable
fun StatusBanner(state: NotesState, onRetry: () -> Unit, onExport: () -> Unit, onResolve: () -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val content = bannerContent(state)
    val reduced = Syncr.reducedMotion
    // Keep the last content while the banner animates away.
    val last = remember { LastContent() }
    if (content != null) last.value = content
    AnimatedVisibility(content != null, modifier, enter = Motion.appear(reduced), exit = Motion.disappear(reduced)) {
        last.value?.let { BannerCard(it, state.busy, onRetry, onExport, onResolve, onDismiss) }
    }
}

private class LastContent { var value: BannerContent? = null }

@Composable
private fun BannerCard(content: BannerContent, busy: Boolean, onRetry: () -> Unit, onExport: () -> Unit,
    onResolve: () -> Unit, onDismiss: () -> Unit) {
    val c = Syncr.colors
    var details by remember(content.raw) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.chrome, Radius.m).background(c.attentionSoft, Radius.m)
        .border(1.dp, c.attention.copy(alpha = 0.5f), Radius.m)
        .padding(start = Space.l, end = Space.xs, top = Space.m, bottom = Space.xs)) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
            SyncrIcon(SyncrIcons.Attention, c.attention, size = 20.dp, modifier = Modifier.padding(top = 2.dp),
                description = "Needs attention")
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(content.message, style = Syncr.type.body, color = c.ink)
                content.detail?.let { Text(it, style = Syncr.type.caption, color = c.muted) }
                if (details && content.raw != null) Text(content.raw, style = Syncr.type.caption, color = c.muted,
                    modifier = Modifier.padding(top = Space.xs))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
            if (content.raw != null) TextAction(if (details) "Hide details" else "Details", { details = !details })
            TextAction("Retry", onRetry, enabled = !busy)
            if (content.unsaved) {
                if (content.canExport) TextAction("Export a copy", onExport, enabled = !busy)
                TextAction("Options", onResolve, enabled = !busy, description = "More options for unsaved changes")
            } else TextAction("Dismiss", onDismiss)
        }
    }
}
