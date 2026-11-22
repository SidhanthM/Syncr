package dev.syncr.prototype.tablet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.syncr.core.PenLook
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.notes.InkPreferencesStore
import dev.syncr.prototype.notes.InkTool
import dev.syncr.prototype.ui.BubbleHead
import dev.syncr.prototype.ui.Head
import dev.syncr.prototype.ui.BubbleBarScope
import dev.syncr.prototype.ui.BubbleChip
import dev.syncr.prototype.ui.BubbleRing
import dev.syncr.prototype.ui.MorphingBubble
import dev.syncr.prototype.ui.PenGlyph
import dev.syncr.prototype.ui.SyncrIcon
import dev.syncr.prototype.ui.SyncrIcons
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.RingKind

class TabletBubbleActions(
    val open: () -> Unit,
    val close: () -> Unit,
    val toggleLocal: () -> Unit,
    val toggleRead: () -> Unit,
    val toggleTrackpad: () -> Unit,
    val settings: () -> Unit,
    val leave: () -> Unit,
)

fun LinkState.ring() = when (this) {
    LinkState.CONNECTED -> RingKind.ACCENT
    LinkState.NOT_CONNECTED -> RingKind.GREY
    LinkState.ATTENTION -> RingKind.ATTENTION
}

/**
 * Tablet mode's tool bubble: the same morphing bubble as in a note, docked in the black margin to the right of
 * the active area. It offers the Local or whole-view choice, the settings and Leave. While it is open a scrim takes every touch,
 * so nothing reaches the pad; the activity also disables pad input, which drops a stroke in progress.
 */
@Composable
fun TabletBubble(state: TabletUiState, open: Boolean, actions: TabletBubbleActions) {
    val area = state.area ?: return
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    val head = Head.valueOf(ink.penLook.name)
    BackHandler(open) { actions.close() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cutoutRight = WindowInsets.displayCutout.getRight(density, LayoutDirection.Ltr)
        val width = constraints.maxWidth.toFloat()
        val disc = with(density) { 22.dp.toPx() }
        val center = Offset(((area.right.toFloat() + width - cutoutRight) / 2).coerceAtMost(width - disc * 1.5f),
            area.bottom.toFloat() - disc)
        if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                waitForUpOrCancellation()?.consume()
                actions.close()
            }
        })
        MorphingBubble(open, center,
            description = "Tablet mode options. ${state.caption}", stateDescription = state.caption,
            onOpen = actions.open,
            ring = { BubbleRing(state.link.ring()) },
            glyph = { if (state.trackpad) SyncrIcon(SyncrIcons.Trackpad, Syncr.colors.ink, size = 22.dp) else PenGlyph(head) },
            bar = { TabletBar(state, actions) })
    }
}

@Composable
private fun BubbleBarScope.TabletBar(state: TabletUiState, actions: TabletBubbleActions) {
    val pen = !state.trackpad
    val heads = if (pen) PenLook.entries.size else 0
    val count = if (state.trackpad) 3 else 5 + heads
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        // The pad writes with the same heads as the note: choosing one restores that head's remembered ink and size.
        if (pen) PenLook.entries.forEachIndexed { i, look ->
            val head = Head.valueOf(look.name)
            BubbleHead(head, i, count, ink.penLook == look) {
                InkPreferencesStore.update(context) { it.copy(penLook = look) }
                LinkController.get(context).usePen(ink.brushFor(InkTool.of(look)))
            }
        }
        BubbleChip(if (state.trackpad) "Trackpad" else "Pen", heads, count,
            description = "Switch between pen tablet and finger trackpad", onClick = actions.toggleTrackpad)
        if (!state.trackpad) BubbleChip(if (state.streamRead) "Read" else "Edit", heads + 1, count,
            description = if (state.streamRead) "The pad shows the Mac's view: a finger or the pen scrolls it, two fingers zoom it, nothing is written. Switch to Edit"
            else "The pad writes into the Mac's note. Switch to Read, which scrolls and zooms the Mac's view without writing",
            onClick = actions.toggleRead)
        if (!state.trackpad) BubbleChip(if (state.streamLocal) "Local" else "Whole view", heads + 2, count,
            description = if (state.streamLocal) "The pad writes inside a frame on the Mac's page; two fingers move it, a pinch resizes it. Switch to the whole view"
            else "The pad covers the Mac's whole view. Switch to Local, a frame you move with two fingers",
            onClick = actions.toggleLocal)
        BubbleChip("Settings", count - 2, count, description = "Tablet settings", onClick = actions.settings)
        BubbleChip("Leave", count - 1, count, description = "Leave Tablet mode", onClick = actions.leave)
    }
}
