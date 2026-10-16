package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.syncr.core.PageFormat
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.ui.theme.Syncr

fun PageFormat.label() = when (this) { PageFormat.PHONE -> "Phone pages"; PageFormat.SHEET16X10 -> "16:10 sheets" }

/**
 * "More" (design §8.3) leads with where the note is stored and its save state. The Mac part is
 * honest: there is no sync or pairing in this milestone, so there are no controls for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(state: NotesState, location: String, onDismiss: () -> Unit, onRename: () -> Unit, onMove: () -> Unit,
    onAddPage: () -> Unit, onExport: () -> Unit, onPointer: () -> Unit, onLibrary: () -> Unit,
    onArchive: (Boolean) -> Unit, onDelete: () -> Unit) {
    val c = Syncr.colors
    val snapshot = state.snapshot ?: return
    val status = saveStatus(state)
    val editable = !state.unsaved && !state.busy
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.chrome, contentColor = c.ink, scrimColor = c.desk.copy(alpha = 0.5f)) {
        SyncDialogSystemBars(c.dark)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Label("Storage", Modifier.padding(bottom = 8.dp))
            Text(status.headline, style = Syncr.type.title, color = if (status.ring == SaveRing.ATTENTION) c.attention else c.ink)
            state.error?.let { raw ->
                Text(friendlyError(raw, saving = state.unsaved), style = Syncr.type.caption, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                SelectionContainer { Text(raw, style = Syncr.type.caption, color = c.muted) }
            }
            SelectionContainer { Text(location, style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = 4.dp)) }
            if (state.unsaved) MenuRow("Export recovery", "A private archive of unsaved changes, for manual recovery", !state.busy, onExport)

            Hairline(Modifier.padding(top = 16.dp))
            SectionLabel("Note")
            Column {
                Text(snapshot.title, style = Syncr.type.title, color = c.ink)
                ulidMillis(snapshot.noteId)?.let { Text("Created ${displayDate(it)}", style = Syncr.type.caption, color = c.muted) }
            }
            IconMenuRow(MenuGlyph.FOLDER, "Move to notebook…", "In ${snapshot.notebook ?: "Inbox"}${if (snapshot.archived) " · Archived" else ""}",
                editable, onClick = onMove)
            IconMenuRow(MenuGlyph.PENCIL, "Rename…", enabled = editable, onClick = onRename)
            if (snapshot.archived) IconMenuRow(MenuGlyph.UNARCHIVE, "Unarchive", "Show it in All and its notebook again", editable) { onArchive(false) }
            else IconMenuRow(MenuGlyph.ARCHIVE, "Archive", "Keep it, out of the way, under Archived", editable) { onArchive(true) }
            // Leaving goes through the unsaved-changes guard first; the library then offers Undo.
            IconMenuRow(MenuGlyph.TRASH, "Delete", "Moves it to Recently deleted for 30 days", !state.busy, tint = c.attention, onClick = onDelete)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = snapshot.sheets.size
                Text("${if (count == 1) "1 page" else "$count pages"} · ${snapshot.pageFormat.label()}", style = Syncr.type.body,
                    color = c.ink, modifier = Modifier.weight(1f))
                LabelButton("Add page", onAddPage, enabled = !state.busy && !state.closing, description = "Add a page at the end")
            }

            Hairline(Modifier.padding(top = 16.dp))
            SectionLabel("Mac")
            Text("Mac sync and pairing arrive in a later version. This note is only on this phone.",
                style = Syncr.type.caption, color = c.muted)

            SectionLabel("Modes")
            MenuRow("Tablet mode", "Use the phone as a pen pad for any Mac app over Bluetooth. Nothing is stored.", !state.busy, onPointer)
            MenuRow("Back to library", null, !state.busy, onLibrary)

            SectionLabel("Gestures")
            Text("Pen writes; hold the side button to erase. One finger scrolls, pinch zooms, double-tap empty paper fits. " +
                "Two-finger tap undoes, three-finger tap redoes. Long-press a stroke, or use Lasso, to select; drag to move, " +
                "corners resize, tap empty paper to deselect.", style = Syncr.type.caption, color = c.muted)
        }
    }
}
