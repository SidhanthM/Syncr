package dev.syncr.prototype.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.core.DeletedNoteSummary
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget

/** Recently deleted: restore, or delete forever after confirming. Notes are removed after 30 days. */
@Composable
fun RecentlyDeletedList(deleted: List<DeletedNoteSummary>, now: Long, enabled: Boolean, ready: Boolean,
    contentPadding: PaddingValues, onRestore: (String) -> Unit, onPurge: (DeletedNoteSummary) -> Unit) {
    val c = Syncr.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "about") {
            Text("Deleted notes stay here for 30 days, then they're removed from this phone.",
                style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(bottom = Space.s))
        }
        if (ready && deleted.isEmpty()) item(key = "empty") {
            EmptyState(SyncrIcons.Trash, "Recently deleted is empty", "Notes you delete wait here before they're gone for good.",
                art = EmptyArtData.search)
        }
        items(deleted, key = { it.id }) { note ->
            DeletedRow(note, now, enabled, onRestore = { onRestore(note.id) }, onPurge = { onPurge(note) })
            Hairline()
        }
    }
}

@Composable
private fun DeletedRow(note: DeletedNoteSummary, now: Long, enabled: Boolean, onRestore: () -> Unit, onPurge: () -> Unit) {
    val c = Syncr.colors
    val shown = rememberDisplayTitle(note.title)
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            Text(shown, style = Syncr.type.noteTitle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${deletedCaption(note.deletedAtMs, now)} · ${note.notebook ?: "Inbox"}", style = Syncr.type.caption,
                color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        RowAction(SyncrIcons.Restore, "Restore", enabled, "Restore $shown", onRestore)
        IconAction(SyncrIcons.Trash, "Delete $shown forever", onPurge, enabled = enabled, tint = c.destructive)
    }
}

/** An icon with a short word, for row actions that read better labelled. */
@Composable
private fun RowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean,
    description: String, onClick: () -> Unit) {
    val colour = if (enabled) Syncr.colors.ink else Syncr.colors.faint
    Row(Modifier.heightIn(min = TouchTarget).clip(Radius.s)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
        SyncrIcon(icon, colour, size = 20.dp)
        Text(label, style = Syncr.type.label, color = colour, modifier = Modifier.padding(start = Space.s))
    }
}
