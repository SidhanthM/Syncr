package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.Brush
import dev.syncr.core.Command
import dev.syncr.core.CreateNoteRequest
import dev.syncr.core.DeletedNoteSummary
import dev.syncr.core.InkColour
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.NoteSummary
import dev.syncr.core.NotebookSummary
import dev.syncr.core.Receipt
import dev.syncr.core.SampleChannels

/** Disposable rendering data for one stroke, never a document reducer. */
data class InkPreview(val id: String, val sheetId: String, val channels: SampleChannels, val brush: Brush)

/**
 * An edit retained until a durable core receipt is represented in a snapshot. [command] is built
 * once and retried unchanged; a new stroke's command is prepared lazily from [preview] on the worker.
 */
data class PendingEdit(
    val id: String,
    val command: Command?,
    val preview: InkPreview? = null,
    /** Strokes this edit deletes, hidden until the snapshot tombstones them. */
    val hidden: Set<String> = emptySet(),
    val dependencies: List<String> = emptyList(),
    val receipt: Receipt? = null,
    /** Visual overlay of a staged selection edit until its receipt is in a snapshot. */
    val transforms: Map<String, Affine> = emptyMap(),
    val restyles: Map<String, Brush> = emptyMap(),
)

/**
 * One selection gesture = one atomic Marks command = one undo step. The eraser commits as it moves, so one
 * eraser gesture is several Delete commands sharing a [Delete.gesture]; undo and redo take them as one step.
 */
sealed interface MarkEdit {
    val ids: Set<String>

    data class Delete(override val ids: Set<String>, val gesture: String? = null) : MarkEdit

    /** Canvas-space [gesture], converted to each sheet's local space before composing with existing ink. */
    data class Transform(override val ids: Set<String>, val gesture: Affine) : MarkEdit

    data class Restyle(override val ids: Set<String>, val colour: InkColour) : MarkEdit
}

/** A metadata change to a CLOSED note from the library, run on the worker: open, submit, durable close. */
sealed interface NoteChange {
    data class Move(val notebook: String?) : NoteChange

    data class Rename(val title: String) : NoteChange

    data class Archive(val archived: Boolean) : NoteChange
}

/** A library action retained until it completes, so Retry repeats exactly the same call. */
sealed interface LibraryTask {
    /** [operationId] is allocated once; [command] is built once from the opened metadata head, then retried as is. */
    data class EditNote(
        val noteId: String,
        val operationId: String,
        val change: NoteChange,
        val command: Command? = null,
    ) : LibraryTask

    /** Core rename is per-note and not atomic; retrying the same call moves the remainder. */
    data class RenameNotebook(val oldName: String, val newName: String) : LibraryTask

    /** Moves a closed note to Recently deleted. The core call is idempotent, so a retry is the same call. */
    data class DeleteNote(val noteId: String) : LibraryTask

    data class RestoreNote(val noteId: String) : LibraryTask

    /** Delete forever, from Recently deleted only. */
    data class PurgeNote(val noteId: String) : LibraryTask
}

/** Recently deleted notes are purged after this long, on library load. */
const val DELETED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

data class NotesState(
    val notes: List<NoteSummary> = emptyList(),
    val notebooks: List<NotebookSummary> = emptyList(),
    /** Recently deleted, newest first; read back from the core. */
    val deleted: List<DeletedNoteSummary> = emptyList(),
    /** The note just moved to Recently deleted, for the "Undo" snackbar. */
    val recentlyDeleted: String? = null,
    val thumbnails: Map<String, Thumbnail> = emptyMap(),
    val libraryTask: LibraryTask? = null,
    val snapshot: NoteSnapshot? = null,
    val samples: Map<String, SampleChannels> = emptyMap(),
    val pending: List<PendingEdit> = emptyList(),
    val creation: CreateNoteRequest? = null,
    val authorId: String? = null,
    val busy: Boolean = false,
    val closing: Boolean = false,
    val error: String? = null,
    val recoveryExported: Boolean = false,
) {
    val unsaved get() = pending.isNotEmpty() || creation != null

    val status get() = when {
        unsaved && error != null -> "Save failed — unsaved changes retained"
        unsaved && recoveryExported -> "Copy exported — your changes aren't saved to the note yet"
        unsaved -> "Saving — not yet saved"
        busy -> "Working…"
        snapshot != null -> "Saved on this phone"
        else -> "Offline library"
    }
}

/** Everything needed to recover unsaved work by hand; see [writeRecovery]. */
data class RecoveryData(
    val noteId: String?,
    val authorId: String,
    val creation: CreateNoteRequest?,
    val commands: List<Command>,
    val backup: ByteArray? = null,
)
