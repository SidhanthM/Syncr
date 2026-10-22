package dev.syncr.prototype.ui

import dev.syncr.prototype.notes.LibraryTask
import dev.syncr.prototype.notes.NoteChange
import dev.syncr.prototype.notes.NotesState

/**
 * Ring states for the offline milestone. Solid accent ("confirmed on the Mac") does not exist yet
 * and is deliberately not representable here.
 */
enum class SaveRing { SAVED_ON_PHONE, SAVING, ATTENTION }

data class SaveStatus(val ring: SaveRing, val headline: String)

/** Only a change the core has confirmed and that shows in a snapshot counts as saved: anything still pending is not. */
fun saveStatus(state: NotesState): SaveStatus = when {
    state.unsaved && state.error != null -> SaveStatus(SaveRing.ATTENTION, "Couldn't save. Your writing is still here")
    state.unsaved && state.recoveryExported -> SaveStatus(SaveRing.ATTENTION, "Copy exported. Not saved to the note yet")
    state.unsaved -> SaveStatus(SaveRing.SAVING, "Saving")
    else -> SaveStatus(SaveRing.SAVED_ON_PHONE, "Saved on this phone")
}

/**
 * Human copy for a repository or canvas message. Core diagnostics arrive as `KindName: detail`; the canvas and
 * repository's own messages are reworded here when known. Anything else passes through unchanged, and the raw
 * text stays available to the caller as the detail.
 */
fun friendlyError(raw: String, saving: Boolean): String {
    val kind = Regex("^([A-Za-z]+): ").find(raw)?.groupValues?.get(1) ?: return raw
    val verb = if (saving) "Couldn't save on this phone" else "Couldn't open storage on this phone"
    return when (kind) {
        "PermissionDenied" -> "$verb (storage permission denied)."
        "StorageFull" -> "$verb (storage is full)."
        "StorageIo" -> "$verb (storage error)."
        "NoteLocked" -> "This note is open somewhere else."
        "NotFound" -> "Couldn't find this note on the phone."
        "ResourceLimit" -> "This note has reached a size limit."
        "Closed" -> "This note was closed before the change finished."
        else -> if (saving) "Couldn't save on this phone." else "Something went wrong on this phone."
    }
}

/** What a failed library action means for the note, in words; null for actions with no special wording. */
fun libraryTaskFailure(task: LibraryTask?): String? = when (task) {
    is LibraryTask.DeleteNote -> "Couldn't move the note to Recently deleted. It's still in your library."
    is LibraryTask.RestoreNote -> "Couldn't restore the note. It's still in Recently deleted."
    is LibraryTask.PurgeNote -> "Couldn't delete the note forever. It's still in Recently deleted."
    is LibraryTask.EditNote -> when (val change = task.change) {
        is NoteChange.Archive ->
            if (change.archived) "Couldn't archive the note. Nothing changed." else "Couldn't unarchive the note. Nothing changed."
        else -> null
    }
    else -> null
}
