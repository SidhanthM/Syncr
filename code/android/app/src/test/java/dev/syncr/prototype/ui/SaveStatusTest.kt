package dev.syncr.prototype.ui

import dev.syncr.core.*
import dev.syncr.prototype.notes.InkPreview
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.PendingEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(SaveRing.SAVING, saveStatus(NotesState(creation = CreateNoteRequest("a", "b", "c", "t", null, NoteKind.NOTE))).ring)
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
        // Unknown human-written messages pass through unchanged.
        val plain = "Something new happened."
        assertEquals(plain, friendlyError(plain, saving = true))
    }

    @Test fun canvasAndRepositoryMessagesPassThroughAsWritten() {
        // notes/ writes user-facing copy at the source; it must reach the screen unchanged and jargon-free.
        listOf("Too many unsaved changes. Retry saving, export a copy or discard them before writing more.",
            "That's too many strokes at once (1,024 at most). Nothing changed.",
            "Erasing waits until your last strokes are saved.",
            "Copy exported, but you've written more since. Export again before leaving.").forEach { raw ->
            val shown = friendlyError(raw, saving = true)
            assertEquals(raw, shown)
            listOf("Recovery", "Entire", "Resolve", "receipt", "durable").forEach { word -> assertFalse(shown, shown.contains(word)) }
        }
    }

    @Test fun headlinesAreCalmAndKeepTheSafetyMeaning() {
        assertEquals("Couldn't save. Your writing is still here",
            saveStatus(NotesState(pending = listOf(pending), error = "StorageFull: x")).headline)
        assertEquals("Saving", saveStatus(NotesState(pending = listOf(pending))).headline)
    }

    @Test fun bannerOnlyWhenSomethingNeedsTheUser() {
        assertNull(bannerContent(NotesState()))
        assertNull(bannerContent(NotesState(pending = listOf(pending))))
        val failed = bannerContent(NotesState(pending = listOf(pending), error = "StorageFull: disk"))!!
        assertTrue(failed.unsaved && failed.canExport)
        assertEquals("StorageFull: disk", failed.raw)
        val exported = bannerContent(NotesState(pending = listOf(pending), recoveryExported = true))!!
        assertFalse(exported.canExport)
        assertFalse(bannerContent(NotesState(error = "Sample limit"))!!.unsaved)
    }

    @Test fun savedOnThePhoneIsTheGreyRingNotTheMacAccent() {
        assertEquals(RingKind.GREY, SaveRing.SAVED_ON_PHONE.kind())
        assertEquals(RingKind.ACCENT_DOTTED, SaveRing.SAVING.kind())
        assertEquals(RingKind.ATTENTION, SaveRing.ATTENTION.kind())
    }
}
