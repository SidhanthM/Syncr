package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.ui.theme.Space

/**
 * The closure guard. Every path that leaves a note goes through [close]; only the repository decides whether
 * leaving is allowed (everything saved, a copy exported, or a confirmed discard). This class only holds which
 * dialog is showing and what to do once leaving succeeds.
 */
@Stable
class ClosureGuard(private val vm: NotesViewModel) {
    private val repo get() = vm.repository
    /** The "Changes not saved yet" dialog. */
    var guardOpen by mutableStateOf(false)
        private set
    var confirmDiscard by mutableStateOf(false)
    var confirmExport by mutableStateOf(false)
    /** What runs once the note has actually been left. */
    private var leaving: (() -> Unit)? = null

    /** Leave the note now if nothing is unsaved; otherwise ask, and run [after] only once leaving succeeds. */
    fun close(state: NotesState, after: () -> Unit) {
        leaving = after
        if (state.unsaved) guardOpen = true
        else vm.run { if (repo.leave()) { after(); leaving = null } }
    }

    /** The banner's "Options": the same dialog, staying on the note afterwards. */
    fun resolve() { leaving = {}; guardOpen = true }

    fun stay() { guardOpen = false; leaving = null }

    fun retry() = vm.run { repo.retry(); if (!repo.state.value.unsaved && repo.leave()) finish() }

    fun leaveAfterExport() = vm.run { if (repo.leave()) finish() }

    fun discard() {
        confirmDiscard = false
        vm.run { if (repo.leave(discard = true)) finish() }
    }

    private fun finish() { guardOpen = false; leaving?.invoke(); leaving = null }
}

/** The guard's dialogs. [onChooseExport] opens the system file picker. */
@Composable
fun ClosureGuardDialogs(guard: ClosureGuard, state: NotesState, onChooseExport: () -> Unit) {
    if (guard.guardOpen) GuardDialog(state, onStay = guard::stay, onRetry = guard::retry,
        onExport = { guard.confirmExport = true }, onLeaveAfterExport = guard::leaveAfterExport,
        onDiscard = { guard.confirmDiscard = true })
    if (guard.confirmDiscard) DiscardDialog(onKeep = { guard.confirmDiscard = false }, onDiscard = guard::discard)
    if (guard.confirmExport) ExportDialog(onCancel = { guard.confirmExport = false },
        onChoose = { guard.confirmExport = false; onChooseExport() })
}

@Composable
fun GuardDialog(state: NotesState, onStay: () -> Unit, onRetry: () -> Unit, onExport: () -> Unit,
    onLeaveAfterExport: () -> Unit, onDiscard: () -> Unit) {
    SyncrDialog(onStay, title = "Changes not saved yet", confirm = {
        DialogButtons {
            TextAction("Discard…", onDiscard, enabled = !state.busy, tone = ActionTone.DESTRUCTIVE,
                description = "Discard unsaved changes")
            TextAction("Stay", onStay)
            TextAction("Retry", onRetry, enabled = !state.busy, description = "Retry saving")
            if (state.recoveryExported) TextAction("Leave", onLeaveAfterExport, enabled = !state.busy, description = "Leave the note")
            else TextAction("Export a copy", onExport, enabled = !state.busy)
        }
    }) {
        Column {
            DialogText(if (state.recoveryExported)
                "A copy is exported. It isn't a saved note: to use it later you'd restore it by hand. You can leave now."
            else "Your writing is still here. Retry saving, export a copy to keep it safe, or discard the changes. " +
                "Switching apps never discards them.")
            state.error?.let { DialogDetail(friendlyError(it, saving = true), Modifier.padding(top = Space.s)) }
        }
    }
}

@Composable
fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    SyncrDialog(onKeep, title = "Discard unsaved changes?",
        confirm = { TextAction("Discard and leave", onDiscard, tone = ActionTone.DESTRUCTIVE) },
        dismiss = { TextAction("Keep changes", onKeep) }) {
        DialogText("The strokes and edits that haven't saved will be lost. Everything already saved stays. This can't be undone.")
    }
}

@Composable
fun ExportDialog(onCancel: () -> Unit, onChoose: () -> Unit) {
    SyncrDialog(onCancel, title = "Export a copy",
        confirm = { TextAction("Choose where to save", onChoose) },
        dismiss = { TextAction("Cancel", onCancel) }) {
        Column {
            DialogText("The file holds your handwriting and the changes that haven't saved, plus a backup of the note " +
                "when there is one. Save it somewhere private.")
            DialogDetail("It isn't a regular backup and won't be imported automatically. If the export fails or you " +
                "cancel, the note stays open.", Modifier.padding(top = Space.s))
        }
    }
}
