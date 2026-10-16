package dev.syncr.prototype.notes

/**
 * UI-side notebook name checks. The core stores names exactly and compares them case-sensitively,
 * rejecting untrimmed names; the UI trims first and also blocks case-insensitive duplicates so
 * "Physics" and "physics" never coexist by accident. Returns a person-readable reason, or null.
 * [renaming] is the notebook being renamed: changing only its case is allowed.
 */
fun notebookNameProblem(raw: String, existing: Collection<String>, renaming: String? = null): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> "Enter a name."
        name.toByteArray(Charsets.UTF_8).size > 100 -> "That name is too long."
        name.any { it == '/' || it == '\\' || it.isISOControl() } -> "Names can't contain / or \\."
        name == "." || name == ".." -> "Choose a different name."
        name.equals("Inbox", ignoreCase = true) || name.equals("All", ignoreCase = true) -> "\"$name\" is reserved."
        renaming != null && name == renaming -> "That's the current name."
        existing.any { it.equals(name, ignoreCase = true) && it != renaming } -> "A notebook with that name already exists."
        else -> null
    }
}

/**
 * Which notes the library shows. New notes go to the selected notebook; All and Inbox create in Inbox.
 * Archived notes appear only under [Archived]. [Deleted] (Recently deleted) lists no library notes:
 * it shows the core's deleted list instead.
 */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Inbox : LibraryFilter
    data class Notebook(val name: String) : LibraryFilter
    data object Archived : LibraryFilter
    data object Deleted : LibraryFilter
    fun accepts(notebook: String?, archived: Boolean = false) = when (this) {
        All -> !archived
        Inbox -> !archived && notebook == null
        is Notebook -> !archived && notebook == name
        Archived -> archived
        Deleted -> false
    }
    val newNoteNotebook get() = (this as? Notebook)?.name
    /** Archived and Recently deleted are views of existing notes, not places to write. */
    val allowsNewNotes get() = this != Archived && this != Deleted
}
