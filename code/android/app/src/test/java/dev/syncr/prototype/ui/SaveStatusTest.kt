package dev.syncr.prototype.ui

import dev.syncr.core.*
import dev.syncr.prototype.notes.InkPreview
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.PendingEdit
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveStatusTest {
    private val samples = SampleChannels(listOf(1f), listOf(1f), listOf(1f), listOf(0u), null, null)
    private val pending = PendingEdit("op", null, InkPreview("m", "s", samples, Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)))

    @Test fun savedOnlyWithoutPendingWork() {
        assertEquals(SaveRing.SAVED_ON_PHONE, saveStatus(NotesState()).ring)
        assertEquals("Saved on this phone", saveStatus(NotesState()).headline)
        // A plain message (e.g. a cancelled stroke) does not make saved content unsaved.
        assertEquals(SaveRing.SAVED_ON_PHONE, saveStatus(NotesState(error = "Sample limit")).ring)
    }

    @Test fun pendingIsSavingNeverSaved() {
        assertEquals(SaveRing.SAVING, saveStatus(NotesState(pending = listOf(pending))).ring)
        // Even with a receipt, a draft not yet reflected in a snapshot is still pending.
        val receipted = pending.copy(receipt = Receipt("op", 2u, EventCursor("s", 2u), false))
        assertEquals(SaveRing.SAVING, saveStatus(NotesState(pending = listOf(receipted))).ring)
        assertEquals(SaveRing.SAVING, saveStatus(NotesState(creation = CreateNoteRequest("a", "b", "c", "t", null))).ring)
    }

    @Test fun failuresAndExportsNeedAttention() {
        assertEquals(SaveRing.ATTENTION, saveStatus(NotesState(pending = listOf(pending), error = "StorageFull: x")).ring)
        assertEquals(SaveRing.ATTENTION, saveStatus(NotesState(pending = listOf(pending), recoveryExported = true)).ring)
    }

    @Test fun friendlyErrorCopy() {
        assertEquals("Couldn't save on this phone (storage permission denied).",
            friendlyError("PermissionDenied: Storage permission denied", saving = true))
        assertEquals("Couldn't open storage on this phone (storage permission denied).",
            friendlyError("PermissionDenied: Storage permission denied", saving = false))
        assertEquals("Couldn't save on this phone (storage is full).", friendlyError("StorageFull: disk", saving = true))
        assertEquals("Couldn't save on this phone.", friendlyError("Internal: boom", saving = true))
        // Human-written messages pass through unchanged.
        val plain = "Draft limit reached. Retry, export recovery or discard before drawing more."
        assertEquals(plain, friendlyError(plain, saving = true))
    }
}
