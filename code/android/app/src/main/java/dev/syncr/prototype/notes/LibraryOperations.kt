package dev.syncr.prototype.notes

import dev.syncr.core.Base
import dev.syncr.core.Command
import dev.syncr.core.CommandBody
import dev.syncr.core.NoteSummary
import dev.syncr.core.OperationLookup
import java.io.File

/**
 * Library-level work on CLOSED notes: retained [LibraryTask]s, expiry of Recently deleted, and
 * library card thumbnails. Worker only. Any note opened here is a side handle that is closed
 * durably before anything else opens.
 */
internal class LibraryOperations(
    private val session: CoreSession,
    private val core: CoreAdapter,
    private val thumbnailDir: File?,
    private val now: () -> Long,
) {
    /**
     * Housekeeping on load: deletes forever what has been in Recently deleted for 30 days, then drops
     * cached cards of notes that no longer exist. A failure is not shown: expired notes simply stay
     * listed in Recently deleted and are retried on the next load.
     */
    fun purgeExpired() {
        val cutoff = now() - DELETED_RETENTION_MS
        try {
            if (session.current.deleted.any { it.deletedAtMs < cutoff }) session.library().purgeDeletedOlderThan(cutoff)
        } catch (_: Exception) {
            // Retried on the next load.
        }
        try {
            session.reloadLibrary()
            val state = session.current
            val keep = (state.notes.map { it.id } + state.deleted.map { it.id }).toSet()
            session.update { it.copy(thumbnails = it.thumbnails.filterKeys { id -> id in keep }) }
            ThumbnailCache.retainOnly(thumbnailDir, keep)
        } catch (_: Exception) {
            // Stale cards are harmless; the next load tries again.
        }
    }

    /** Runs [task] to completion. Only then is it cleared, and the library is read back from the core. */
    fun run(task: LibraryTask) {
        when (task) {
            is LibraryTask.RenameNotebook -> session.library().renameNotebook(task.oldName, task.newName)
            is LibraryTask.DeleteNote -> {
                // The core refuses while a side handle (thumbnail read) has this note open.
                session.closeSide()
                session.library().deleteNote(task.noteId)
                session.update { it.copy(recentlyDeleted = task.noteId) }
            }
            is LibraryTask.RestoreNote -> {
                session.library().restoreNote(task.noteId)
                session.update { if (it.recentlyDeleted == task.noteId) it.copy(recentlyDeleted = null) else it }
            }
            is LibraryTask.PurgeNote -> {
                session.library().purgeNote(task.noteId)
                session.update { it.copy(thumbnails = it.thumbnails - task.noteId) }
                ThumbnailCache.delete(thumbnailDir, task.noteId)
            }
            is LibraryTask.EditNote -> editClosedNote(task)
        }
        session.update { it.copy(libraryTask = null) }
        session.reloadLibrary()
    }

    /**
     * Fills one card thumbnail, from the disk cache or by opening the note. Returns false when the
     * pass should stop because the user has started something else.
     */
    fun loadThumbnail(summary: NoteSummary): Boolean {
        val state = session.current
        if (session.note != null || state.unsaved || state.libraryTask != null) return false
        val thumbnail = ThumbnailCache.read(thumbnailDir, summary.id, summary.localSequence) ?: run {
            val handle = session.openSide(summary.id)
            val built = buildThumbnail(handle.snapshot(), { mark -> core.decode(handle.samples(mark)) })
            session.closeSide()
            ThumbnailCache.write(thumbnailDir, summary.id, built)
            built
        }
        session.update { it.copy(thumbnails = it.thumbnails + (summary.id to thumbnail)) }
        return true
    }

    /** Publishes a card thumbnail and caches it on disk. */
    fun storeThumbnail(noteId: String, thumbnail: Thumbnail) {
        session.update { it.copy(thumbnails = it.thumbnails + (noteId to thumbnail)) }
        ThumbnailCache.write(thumbnailDir, noteId, thumbnail)
    }

    /** Open, submit one metadata command (built once, then retried as is), durable close. */
    private fun editClosedNote(task: LibraryTask.EditNote) {
        val handle = session.openSide(task.noteId)
        val before = handle.snapshot()
        val command = task.command ?: buildCommand(task, before.metadataHead.operationId).also { built ->
            session.update { it.copy(libraryTask = task.copy(command = built)) }
        }
        if (handle.lookup(command.operationId) is OperationLookup.NotRecorded) handle.submit(command)
        val after = handle.snapshot().localSequence
        // Metadata only: an up-to-date thumbnail stays valid at the new sequence.
        val old = session.current.thumbnails[task.noteId]
        if (old != null && old.localSequence == before.localSequence) {
            storeThumbnail(task.noteId, Thumbnail(after, old.width, old.viewHeight, old.strokes))
        }
        session.closeSide()
    }

    private fun buildCommand(task: LibraryTask.EditNote, head: String): Command {
        val base = Base.Operation(head)
        val body = when (val change = task.change) {
            is NoteChange.Move -> CommandBody.SetNotebook(change.notebook, base)
            is NoteChange.Rename -> CommandBody.RenameNote(change.title, base)
            is NoteChange.Archive -> CommandBody.SetArchived(change.archived, base)
        }
        return Command(task.operationId, listOf(head), body)
    }
}
