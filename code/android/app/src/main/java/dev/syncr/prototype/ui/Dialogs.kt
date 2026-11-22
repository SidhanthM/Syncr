package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.notes.notebookNameProblem
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.TouchTarget

/* Note and notebook dialogs. Their decisions (what is allowed) live in the repository and the caller. */

/** Quotes a user-supplied name for a dialog title. */
internal fun quoted(name: String) = "“$name”"

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    NameDialog("Rename note", "Title", initial, problem = { if (it.isBlank()) "" else null }, onDismiss, onSave)
}

/** "New canvas": only a name, the next untitled one filled in and selected; Create makes the canvas and opens it. */
@Composable
fun NewCanvasDialog(initial: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    NameDialog("New canvas", "Name", initial, problem = { if (it.isBlank()) "" else null }, onDismiss, onCreate, confirmLabel = "Create")
}

/** Name entry for a new or renamed notebook. Trims, and blocks reserved names and case-insensitive duplicates. */
@Composable
fun NotebookNameDialog(heading: String, initial: String, existing: List<String>, renaming: String?, onDismiss: () -> Unit,
    onSave: (String) -> Unit) {
    NameDialog(heading, "Name", initial, problem = { notebookNameProblem(it, existing, renaming) }, onDismiss, onSave)
}

/**
 * One text field, focused with its text selected. [problem] returns why the name can't be saved (an empty string
 * blocks saving without showing a message), shown only once the user has typed.
 */
@Composable
private fun NameDialog(heading: String, fieldLabel: String, initial: String, problem: (String) -> String?,
    onDismiss: () -> Unit, onSave: (String) -> Unit, confirmLabel: String = "Save") {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var touched by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val issue = problem(value.text)
    val save = { if (issue == null) onSave(value.text.trim()) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SyncrDialog(onDismiss, title = heading,
        confirm = { TextAction(confirmLabel, save, enabled = issue == null) },
        dismiss = { TextAction("Cancel", onDismiss) }) {
        SyncrTextField(value, { value = it; touched = true }, Modifier.fillMaxWidth().focusRequester(focus),
            label = fieldLabel, singleLine = true, error = if (touched && !issue.isNullOrEmpty()) issue else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }))
    }
}

/** Deleting is only allowed for an empty notebook; otherwise explain what to do instead. */
@Composable
fun DeleteNotebookDialog(name: String, noteCount: Long, onDismiss: () -> Unit, onDelete: () -> Unit) {
    if (noteCount > 0) SyncrDialog(onDismiss, title = "Can't delete ${quoted(name)}",
        confirm = { TextAction("OK", onDismiss) }) {
        DialogText("It still has ${if (noteCount == 1L) "1 note" else "$noteCount notes"}. Move them to Inbox or another " +
            "notebook first: long-press a note to move it.")
    }
    else SyncrDialog(onDismiss, title = "Delete ${quoted(name)}?",
        confirm = { TextAction("Delete", onDelete, tone = ActionTone.DESTRUCTIVE) },
        dismiss = { TextAction("Cancel", onDismiss) }) {
        DialogText("It's empty, so no notes are affected.")
    }
}

/** Inbox or any notebook, as a radio list; the current one is checked. */
@Composable
fun MoveNoteDialog(title: String, current: String?, notebooks: List<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = Syncr.colors
    SyncrDialog(onDismiss, title = "Move ${quoted(title)}", confirm = { TextAction("Cancel", onDismiss) }) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            (listOf<String?>(null) + notebooks).forEach { target ->
                val selected = target == current
                Row(Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                    .selectable(selected, role = Role.RadioButton) { onPick(target) }
                    .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SyncrIcon(SyncrIcons.Folder, c.muted, size = 20.dp)
                    Text(target ?: "Inbox", style = Syncr.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.m))
                    if (selected) SyncrIcon(SyncrIcons.Check, c.ink, size = 20.dp)
                }
            }
            if (notebooks.isEmpty()) DialogDetail("Make a notebook with + in the library.", Modifier.padding(top = Space.s))
        }
    }
}

/** The only permanent step: confirmed every time, from Recently deleted. */
@Composable
fun PurgeDialog(title: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    SyncrDialog(onCancel, title = "Delete ${quoted(title)} forever?",
        confirm = { TextAction("Delete forever", onDelete, tone = ActionTone.DESTRUCTIVE) },
        dismiss = { TextAction("Cancel", onCancel) }) {
        DialogText("The note and its handwriting are removed from this phone. This can't be undone.")
    }
}
