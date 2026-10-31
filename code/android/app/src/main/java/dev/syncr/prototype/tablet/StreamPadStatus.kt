package dev.syncr.prototype.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.ui.MacLinkSection
import dev.syncr.prototype.ui.SyncrSheet
import dev.syncr.prototype.ui.TextAction
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

/**
 * Stream's recovery action, floating over the pad's top edge so it stays reachable without the tool bubble.
 * The caption already names the problem, so the message shows only when it says more than that.
 */
@Composable
fun StreamPadStatus(state: StreamPadState, modifier: Modifier = Modifier, onAction: (StreamAction) -> Unit) {
    val message = state.message.takeIf { it != state.problem && state.problem != null }
    if (message == null && state.action == null) return
    val c = Syncr.colors
    val shape = RoundedCornerShape(50)
    Row(modifier.background(c.chrome, shape).border(1.dp, c.lineStrong, shape)
        .padding(start = if (message != null) Space.l else Space.xs, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically) {
        message?.let { Text(it, style = Syncr.type.caption, color = c.muted) }
        state.action?.let { action -> TextAction(action.label, { onAction(action) }) }
    }
}

@Composable
fun TabletMacLinkSettings(onDismiss: () -> Unit) {
    SyncrSheet(onDismiss, title = "Mac link") { MacLinkSection() }
}
