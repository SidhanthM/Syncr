package dev.syncr.prototype.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget
import dev.syncr.prototype.ui.theme.floatingSurface

/**
 * Read mode's only chrome: where the tool bubble would be, a small pill that says the note cannot be changed here and
 * goes back to editing on a tap. No tools, no save ring: nothing is being written.
 */
@Composable
fun ReadingPill(onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Row(modifier.floatingSurface().clip(Radius.pill).heightIn(min = TouchTarget)
        .clickable(role = Role.Button, onClick = onEdit)
        .semantics { contentDescription = "Reading. Nothing can be changed. Tap to edit" }
        .padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
        SyncrIcon(SyncrIcons.Eye, c.ink, size = 20.dp)
        Spacer(Modifier.width(Space.s))
        Text("Reading", style = Syncr.type.label, color = c.ink, maxLines = 1)
        Spacer(Modifier.width(Space.s))
        Text("Edit", style = Syncr.type.caption, color = c.muted, maxLines = 1)
    }
}
