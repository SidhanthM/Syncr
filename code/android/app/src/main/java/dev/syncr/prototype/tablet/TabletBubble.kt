package dev.syncr.prototype.tablet

import androidx.activity.compose.BackHandler
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
import dev.syncr.prototype.ui.BubbleBarScope
import dev.syncr.prototype.ui.BubbleChip
import dev.syncr.prototype.ui.BubbleRing
import dev.syncr.prototype.ui.MorphingBubble
import dev.syncr.prototype.ui.PenGlyph
import dev.syncr.prototype.ui.RingKind

class TabletBubbleActions(
    val open: () -> Unit,
    val close: () -> Unit,
    val toggleMapping: () -> Unit,
    val toggleLocal: () -> Unit,
    val recentre: () -> Unit,
    val selectMac: () -> Unit,
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
 * the active area. It offers only what works for the Pointer link. While it is open a scrim takes every touch,
 * so nothing reaches the pad; the activity also disables pad input, which releases a held button.
 */
@Composable
fun TabletBubble(state: TabletUiState, open: Boolean, actions: TabletBubbleActions) {
    val area = state.area ?: return
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
            glyph = { PenGlyph() },
            bar = { TabletBar(state, actions) })
    }
}

@Composable
private fun BubbleBarScope.TabletBar(state: TabletUiState, actions: TabletBubbleActions) {
    val pointer = state.linkChoice == TabletLink.POINTER
    val actual = pointer && state.mode == MappingMode.ACTUAL_SIZE
    val count = if (pointer) (if (actual) 5 else 4) else 3
    var i = 0
    Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pointer) BubbleChip(if (actual) "Actual size" else "Whole screen", i++, count,
            description = "Mapping: ${if (actual) "Actual size" else "Whole screen"}. Switch to ${if (actual) "Whole screen" else "Actual size"}",
            onClick = actions.toggleMapping)
        if (!pointer) BubbleChip(if (state.streamLocal) "Local" else "Whole view", i++, count,
            description = if (state.streamLocal) "The pad writes inside a frame on the Mac's page; two fingers move it, a pinch resizes it. Switch to the whole view"
            else "The pad covers the Mac's whole view. Switch to Local, a frame you move with two fingers",
            onClick = actions.toggleLocal)
        if (actual) BubbleChip("Recentre", i++, count, description = "Recentre the window on the Mac screen", onClick = actions.recentre)
        if (pointer) BubbleChip(if (state.knownHost && state.host == null) "Reconnect" else "Choose Mac", i++, count,
            description = if (state.knownHost && state.host == null) "Reconnect to your Mac" else "Choose your paired Mac", onClick = actions.selectMac)
        BubbleChip("Settings", i++, count, description = "Tablet settings", onClick = actions.settings)
        BubbleChip("Leave", i++, count, description = "Leave Tablet mode", onClick = actions.leave)
    }
}
