package dev.syncr.prototype.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.notebookNameProblem
import dev.syncr.prototype.ui.theme.Syncr

/*
 * The unsaved-changes guard, discard confirmation and recovery export confirmation, restyled.
 * Their decisions (when closure is allowed) live in the caller and the repository, unchanged.
 */

@Composable
private fun DialogText(text: String) = Text(text, style = Syncr.type.body, color = Syncr.colors.ink)

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(onDismiss, containerColor = Syncr.colors.chrome, title = { Label("Rename note", color = Syncr.colors.ink) },
        text = { OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true) },
        confirmButton = { LabelButton("Save", { onSave(title.trim()) }, enabled = title.isNotBlank()) },
        dismissButton = { LabelButton("Cancel", onDismiss) })
}

@Composable
fun GuardDialog(state: NotesState, onStay: () -> Unit, onRetry: () -> Unit, onExport: () -> Unit, onLeaveAfterExport: () -> Unit,
    onDiscard: () -> Unit) {
    AlertDialog(onStay, containerColor = Syncr.colors.chrome,
        title = { Label("Unsaved changes. Closing is blocked", color = Syncr.colors.attention) },
        text = {
            Column {
                state.error?.let { Text(friendlyError(it, saving = true), style = Syncr.type.caption, color = Syncr.colors.muted) }
                DialogText(if (state.recoveryExported) "Recovery archive written. It is not a saved note and needs manual recovery. You can leave now."
                    else "These changes are not saved yet. Retry saving, export a private recovery archive, or discard them. Leaving the app in the background never discards them.")
            }
        },
        confirmButton = { Row {
            LabelButton("Retry", onRetry, enabled = !state.busy)
            if (state.recoveryExported) LabelButton("Leave", onLeaveAfterExport, enabled = !state.busy, description = "Leave after export")
            else LabelButton("Export", onExport, enabled = !state.busy, description = "Export recovery")
        } },
        dismissButton = { Row {
            LabelButton("Discard…", onDiscard, enabled = !state.busy, description = "Discard unsaved changes")
            LabelButton("Stay", onStay)
        } })
}

@Composable
fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onKeep, containerColor = Syncr.colors.chrome, title = { Label("Discard unsaved changes?", color = Syncr.colors.ink) },
        text = { DialogText("The unsaved strokes and edits are lost. Changes already saved on this phone stay. This can't be undone.") },
        confirmButton = { LabelButton("Discard and leave", onDiscard) },
        dismissButton = { LabelButton("Keep changes", onKeep) })
}

@Composable
fun ExportDialog(onCancel: () -> Unit, onChoose: () -> Unit) {
    AlertDialog(onCancel, containerColor = Syncr.colors.chrome, title = { Label("Export recovery archive", color = Syncr.colors.ink) },
        text = { DialogText("Contains your private handwriting and pending changes, plus a backup of the note when available. Choose a place you trust. This is not a normal backup and is not imported automatically. A failed or cancelled export doesn't allow closing.") },
        confirmButton = { LabelButton("Choose destination", onChoose) },
        dismissButton = { LabelButton("Cancel", onCancel) })
}

/** Name entry for a new or renamed notebook. Trims, and blocks reserved names and case-insensitive duplicates. */
@Composable
fun NotebookNameDialog(heading: String, initial: String, existing: List<String>, renaming: String?, onDismiss: () -> Unit,
    onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    val problem = notebookNameProblem(name, existing, renaming)
    AlertDialog(onDismiss, containerColor = Syncr.colors.chrome, title = { Label(heading, color = Syncr.colors.ink) },
        text = {
            OutlinedTextField(name, { name = it; touched = true }, label = { Text("Name") }, singleLine = true,
                isError = touched && problem != null,
                supportingText = { if (touched && problem != null) Text(problem) })
        },
        confirmButton = { LabelButton("Save", { onSave(name.trim()) }, enabled = problem == null) },
        dismissButton = { LabelButton("Cancel", onDismiss) })
}

/** Deleting is only allowed for an empty notebook; otherwise explain what to do instead. */
@Composable
fun DeleteNotebookDialog(name: String, noteCount: Long, onDismiss: () -> Unit, onDelete: () -> Unit) {
    if (noteCount > 0) AlertDialog(onDismiss, containerColor = Syncr.colors.chrome,
        title = { Label("Can't delete \"$name\"", color = Syncr.colors.ink) },
        text = { DialogText("It still holds ${if (noteCount == 1L) "1 note" else "$noteCount notes"}. Move them to Inbox or another notebook first (long-press a note).") },
        confirmButton = { LabelButton("OK", onDismiss) })
    else AlertDialog(onDismiss, containerColor = Syncr.colors.chrome,
        title = { Label("Delete \"$name\"?", color = Syncr.colors.ink) },
        text = { DialogText("The notebook is empty. No notes are affected.") },
        confirmButton = { LabelButton("Delete", onDelete) },
        dismissButton = { LabelButton("Cancel", onDismiss) })
}

/** Inbox or any notebook; the current one is marked. */
@Composable
fun MoveNoteDialog(title: String, current: String?, notebooks: List<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(onDismiss, containerColor = Syncr.colors.chrome, title = { Label("Move \"$title\"", color = Syncr.colors.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<String?>(null) + notebooks).forEach { target ->
                    val selected = target == current
                    Text((target ?: "Inbox") + if (selected) "  ✓" else "", style = Syncr.type.body,
                        color = if (selected) Syncr.colors.muted else Syncr.colors.ink,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onPick(target) }
                            .semantics { this.selected = selected }.padding(vertical = 12.dp))
                }
                if (notebooks.isEmpty()) Text("Create notebooks with + in the library.", style = Syncr.type.caption, color = Syncr.colors.muted)
            }
        },
        confirmButton = { LabelButton("Cancel", onDismiss) })
}

/** The only permanent step: confirmed every time, from Recently deleted. */
@Composable
fun PurgeDialog(title: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onCancel, containerColor = Syncr.colors.chrome,
        title = { Label("Delete \"$title\" forever?", color = Syncr.colors.ink) },
        text = { DialogText("The note and its handwriting are removed from this phone. This can't be undone.") },
        confirmButton = { TextButtonAttention("Delete forever", onDelete) },
        dismissButton = { LabelButton("Cancel", onCancel) })
}

@Composable
private fun TextButtonAttention(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick, Modifier.heightIn(min = 48.dp)) {
        Text(text, style = Syncr.type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Syncr.colors.attention)
    }
}
