package dev.syncr.prototype.notes

import dev.syncr.core.AuthorHead
import dev.syncr.core.MergeReport
import dev.syncr.core.SyncDigest

/** A note's operation set, as the link reports it. */
typealias SyncDigestInfo = SyncDigest

/** What putting a Mac copy into the library did. [how] is `installed`, `merged` or `rebased`. */
data class AppliedCopy(val seq: Long, val operationCount: Long, val digest: String, val how: String, val rejected: Int)

/**
 * Two-way sync on the phone (sync-v1.md). Worker only, like every core call: runs inside [CoreSession.background].
 * Merging only ever appends operations. A rebase, which replaces the note's file, always writes a backup first (the
 * core refuses if it cannot) and runs only when a merge reported operations it could not apply.
 */
internal class SyncOperations(private val session: CoreSession, private val editor: OpenNoteEditor) {
    /** The digest of [noteId]: through the open handle when it is the one on screen, else read-only. Null when the note is gone. */
    fun digest(noteId: String): SyncDigest? = try {
        val open = session.note?.takeIf { session.current.snapshot?.noteId == noteId }
        open?.syncDigest() ?: session.library().noteSyncDigest(noteId)
    } catch (_: dev.syncr.core.CoreException.NotFound) {
        null
    }

    /** The newest operation ID per author of [noteId], through the open handle when it is on screen. Null when the note is gone. */
    fun heads(noteId: String): List<AuthorHead>? = try {
        val open = session.note?.takeIf { session.current.snapshot?.noteId == noteId }
        open?.syncHeads() ?: session.library().noteSyncHeads(noteId)
    } catch (_: dev.syncr.core.CoreException.NotFound) {
        null
    }

    /**
     * Merges a delta the Mac sent. Only appends operations, never rebases: when it applied cleanly and left the note with
     * everything the Mac's side holds, [AppliedCopy.how] is `delta`; otherwise `delta-short` and the link asks for a whole copy
     * (which merges again and, on a conflict, rebases). A note the phone lacks cannot take a delta.
     */
    fun applyDelta(noteId: String, path: String): AppliedCopy {
        val library = session.library()
        check(library.listNotes().any { it.id == noteId }) { "no such note" }
        val open = session.note?.takeIf { session.current.snapshot?.noteId == noteId }
        val merged = if (open != null) {
            check(!session.current.unsaved) { "the note has unsaved changes" }
            open.mergeDelta(path).also { editor.refresh() }
        } else {
            val side = session.openSide(noteId)
            val result = try { side.mergeDelta(path) } finally { session.closeSide() }
            session.reloadLibrary()
            result
        }
        val whole = merged.complete && merged.report.rejected.isEmpty()
        return applied(noteId, if (whole) "delta" else "delta-short", merged.report.rejected.size)
    }

    fun apply(noteId: String, path: String): AppliedCopy {
        val library = session.library()
        if (library.listNotes().none { it.id == noteId }) {
            library.installCopy(path)
            session.reloadLibrary()
            return applied(noteId, "installed", 0)
        }
        val open = session.note?.takeIf { session.current.snapshot?.noteId == noteId }
        if (open != null) {
            check(!session.current.unsaved) { "the note has unsaved changes" }
            val report = open.mergeCopy(path)
            if (report.rejected.isEmpty()) {
                editor.refresh()
                return applied(noteId, "merged", 0)
            }
            // The Mac wins: close the editor's handle, rebuild the note as the Mac's copy plus what still applies,
            // and reopen it. The viewport belongs to the screen and is not touched.
            session.closeNote()
            val rebased = try { library.rebaseOntoCopy(noteId, path) } finally { session.openNote(noteId) }
            editor.refresh()
            return applied(noteId, "rebased", rebased.rejected.size)
        }
        val side = session.openSide(noteId)
        val report: MergeReport = try { side.mergeCopy(path) } finally { session.closeSide() }
        val rebased = if (report.rejected.isEmpty()) null else library.rebaseOntoCopy(noteId, path)
        session.reloadLibrary()
        return applied(noteId, if (rebased == null) "merged" else "rebased", rebased?.rejected?.size ?: 0)
    }

    private fun applied(noteId: String, how: String, rejected: Int): AppliedCopy {
        val digest = checkNotNull(digest(noteId)) { "note vanished" }
        val seq = if (session.note != null && session.current.snapshot?.noteId == noteId) session.current.snapshot!!.localSequence
        else session.library().listNotes().first { it.id == noteId }.localSequence
        return AppliedCopy(seq.toLong(), digest.operationCount.toLong(), digest.digest, how, rejected)
    }
}
