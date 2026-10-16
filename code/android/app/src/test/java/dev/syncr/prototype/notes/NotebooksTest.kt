package dev.syncr.prototype.notes

import org.junit.Assert.*
import org.junit.Test

class NotebooksTest {
    private val existing = listOf("Physics", "Journal")

    @Test fun acceptsTrimmedNewNames() {
        assertNull(notebookNameProblem("Chemistry", existing))
        assertNull(notebookNameProblem("  Chemistry  ", existing))
    }
    @Test fun rejectsWhatTheCoreRejectsAndReservedNames() {
        assertNotNull(notebookNameProblem("   ", existing))
        assertNotNull(notebookNameProblem("a/b", existing)); assertNotNull(notebookNameProblem("a\\b", existing))
        assertNotNull(notebookNameProblem("tab\there", existing))
        assertNotNull(notebookNameProblem(".", existing)); assertNotNull(notebookNameProblem("..", existing))
        assertNotNull(notebookNameProblem("inbox", existing)); assertNotNull(notebookNameProblem("ALL", existing))
        assertNotNull(notebookNameProblem("é".repeat(51), existing)) // 102 UTF-8 bytes
        assertNull(notebookNameProblem("é".repeat(50), existing))
    }
    @Test fun blocksCaseInsensitiveDuplicatesButAllowsRecasingTheSameNotebook() {
        assertNotNull(notebookNameProblem("physics", existing))
        assertNotNull(notebookNameProblem("Journal", existing, renaming = "Physics"))
        assertNull(notebookNameProblem("PHYSICS", existing, renaming = "Physics"))
        assertNotNull(notebookNameProblem("Physics", existing, renaming = "Physics"))
    }
    @Test fun filtersAndTargetNotebookForNewNotes() {
        assertTrue(LibraryFilter.All.accepts(null)); assertTrue(LibraryFilter.All.accepts("Physics"))
        assertTrue(LibraryFilter.Inbox.accepts(null)); assertFalse(LibraryFilter.Inbox.accepts("Physics"))
        assertTrue(LibraryFilter.Notebook("Physics").accepts("Physics")); assertFalse(LibraryFilter.Notebook("Physics").accepts("physics"))
        assertNull(LibraryFilter.All.newNoteNotebook); assertNull(LibraryFilter.Inbox.newNoteNotebook)
        assertEquals("Physics", LibraryFilter.Notebook("Physics").newNoteNotebook)
    }
    @Test fun archivedNotesShowOnlyUnderArchivedAndRecentlyDeletedListsNoLibraryNotes() {
        for (filter in listOf(LibraryFilter.All, LibraryFilter.Inbox, LibraryFilter.Notebook("Physics"))) {
            assertFalse(filter.accepts(null, archived = true)); assertFalse(filter.accepts("Physics", archived = true))
            assertTrue(filter.allowsNewNotes)
        }
        assertTrue(LibraryFilter.Archived.accepts(null, archived = true)); assertTrue(LibraryFilter.Archived.accepts("Physics", archived = true))
        assertFalse(LibraryFilter.Archived.accepts(null)); assertFalse(LibraryFilter.Deleted.accepts(null))
        assertFalse(LibraryFilter.Deleted.accepts(null, archived = true))
        assertFalse(LibraryFilter.Archived.allowsNewNotes); assertFalse(LibraryFilter.Deleted.allowsNewNotes)
        assertNull(LibraryFilter.Archived.newNoteNotebook)
    }
}
