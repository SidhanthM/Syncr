package dev.syncr.prototype.ui

import dev.syncr.prototype.notes.drawThumbnail

import dev.syncr.prototype.notes.InkPreferencesStore

import dev.syncr.prototype.notes.InkPreferences

import androidx.compose.ui.platform.LocalContext

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import dev.syncr.core.DeletedNoteSummary
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as rows
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.core.NoteSummary
import dev.syncr.core.PageFormat
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.Thumbnail
import dev.syncr.prototype.ui.theme.Syncr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Library actions; all storage happens in the repository's worker. */
class LibraryActions(
    val onFilter: (LibraryFilter) -> Unit,
    val onNew: (PageFormat) -> Unit,
    val onOpen: (String) -> Unit,
    val onPointer: () -> Unit,
    val onSettings: () -> Unit,
    val onCreateNotebook: (String) -> Unit,
    val onRenameNotebook: (String, String) -> Unit,
    val onDeleteNotebook: (String) -> Unit,
    val onMoveNote: (String, String?) -> Unit,
    val onRenameNote: (String, String) -> Unit,
    val onArchiveNote: (String, Boolean) -> Unit,
    /** Moves to Recently deleted at once (no dialog); the library then offers Undo. */
    val onDeleteNote: (String) -> Unit,
    val onRestoreNote: (String) -> Unit,
    /** Delete forever; the screen confirms first. */
    val onPurgeNote: (String) -> Unit,
    val onUndoDelete: () -> Unit,
    val onDismissDeleted: () -> Unit,
)

/**
 * Library (design §8.3, owner decision 10): a calm document page. A large "Notes" heading, a row of
 * notebook chips (All · Inbox · notebooks · +), and a two-column grid with "New note" first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(state: NotesState, filter: LibraryFilter, actions: LibraryActions,
    defaultFormat: PageFormat, banner: @Composable () -> Unit) {
    val c = Syncr.colors
    val enabled = !state.busy && !state.unsaved && state.libraryTask == null
    val names = state.notebooks.map { it.name }
    var creatingNotebook by remember { mutableStateOf(false) }
    var renamingNotebook by remember { mutableStateOf<String?>(null) }
    var deletingNotebook by remember { mutableStateOf<String?>(null) }
    var movingNote by remember { mutableStateOf<NoteSummary?>(null) }
    var renamingNote by remember { mutableStateOf<NoteSummary?>(null) }
    var purging by remember { mutableStateOf<DeletedNoteSummary?>(null) }
    val now = remember(state.notes, state.deleted) { System.currentTimeMillis() }
    val shown = state.notes.filter { filter.accepts(it.notebook, it.archived) }

    Column(Modifier.fillMaxSize().background(c.desk).safeDrawingPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            LabelButton("Settings", actions.onSettings, description = "Open appearance and new note settings")
            LabelButton("Tablet mode", actions.onPointer, enabled = !state.busy,
                description = "Tablet mode: use the phone as a pen pad for the Mac over Bluetooth. Nothing is stored.")
        }
        Text("Notes", style = Syncr.type.heading, color = c.ink, modifier = Modifier.semantics { heading() }.padding(bottom = 16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("All", filter == LibraryFilter.All) { actions.onFilter(LibraryFilter.All) }
            Chip("Inbox", filter == LibraryFilter.Inbox) { actions.onFilter(LibraryFilter.Inbox) }
            state.notebooks.forEach { notebook ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Chip(notebook.name, filter == LibraryFilter.Notebook(notebook.name), onLongClick = { menu = true }) {
                        actions.onFilter(LibraryFilter.Notebook(notebook.name))
                    }
                    DropdownMenu(menu, { menu = false }, containerColor = c.chrome) {
                        DropdownMenuItem({ Text("Rename…") }, enabled = enabled, onClick = { menu = false; renamingNotebook = notebook.name })
                        DropdownMenuItem({ Text("Delete…") }, enabled = enabled, onClick = { menu = false; deletingNotebook = notebook.name })
                    }
                }
            }
            Chip("+", false, description = "New notebook", enabled = enabled) { creatingNotebook = true }
            // Secondary places: quieter, after the notebooks.
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c.dots))
            Chip("Archived", filter == LibraryFilter.Archived, glyph = MenuGlyph.ARCHIVE, secondary = true) {
                actions.onFilter(LibraryFilter.Archived)
            }
            Chip("Recently deleted", filter == LibraryFilter.Deleted, glyph = MenuGlyph.TRASH, secondary = true) {
                actions.onFilter(LibraryFilter.Deleted)
            }
        }
        Hairline()
        Spacer(Modifier.height(12.dp))
        banner()
        Box(Modifier.weight(1f)) {
            if (filter == LibraryFilter.Deleted) RecentlyDeletedList(state.deleted, now, enabled,
                onRestore = actions.onRestoreNote, onPurge = { purging = it })
            else LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 88.dp)) {
                if (filter.allowsNewNotes) item(key = "new") { NewNoteCell(enabled, defaultFormat, actions.onNew) }
                items(shown, key = { it.id }) { note ->
                    NoteCard(note, state.thumbnails[note.id], showNotebook = filter == LibraryFilter.All || filter == LibraryFilter.Archived,
                        now = now, enabled = enabled,
                        onOpen = { actions.onOpen(note.id) }, onMove = { movingNote = note }, onRename = { renamingNote = note },
                        onArchive = { actions.onArchiveNote(note.id, !note.archived) }, onDelete = { actions.onDeleteNote(note.id) })
                }
                if (shown.isEmpty() && !state.busy) item(span = { GridItemSpan(2) }, key = "empty") {
                    Text(when {
                        filter == LibraryFilter.Archived -> "No archived notes. Archive a note (long-press it) to keep it out of the way without deleting it."
                        state.notes.isEmpty() -> "Nothing here yet. Notes you write stay on this phone."
                        else -> "No notes here yet."
                    }, style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            state.recentlyDeleted?.let { id ->
                UndoBar("Moved to Recently deleted", enabled = !state.busy && state.libraryTask == null,
                    onUndo = actions.onUndoDelete, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                LaunchedEffect(id) { delay(8_000); actions.onDismissDeleted() }
            }
        }
    }
    purging?.let { note ->
        PurgeDialog(note.title, onCancel = { purging = null }, onDelete = { purging = null; actions.onPurgeNote(note.id) })
    }

    if (creatingNotebook) NotebookNameDialog("New notebook", "", names, null,
        onDismiss = { creatingNotebook = false }, onSave = { creatingNotebook = false; actions.onCreateNotebook(it) })
    renamingNotebook?.let { old ->
        NotebookNameDialog("Rename notebook", old, names, old,
            onDismiss = { renamingNotebook = null }, onSave = { renamingNotebook = null; actions.onRenameNotebook(old, it) })
    }
    deletingNotebook?.let { name ->
        val count = state.notebooks.find { it.name == name }?.noteCount?.toLong() ?: 0L
        DeleteNotebookDialog(name, count, onDismiss = { deletingNotebook = null },
            onDelete = { deletingNotebook = null; actions.onDeleteNotebook(name) })
    }
    movingNote?.let { note ->
        MoveNoteDialog(note.title, note.notebook, names, onDismiss = { movingNote = null },
            onPick = { target -> movingNote = null; if (target != note.notebook) actions.onMoveNote(note.id, target) })
    }
    renamingNote?.let { note ->
        RenameDialog(note.title, onDismiss = { renamingNote = null },
            onSave = { title -> renamingNote = null; if (title != note.title) actions.onRenameNote(note.id, title) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(text: String, selected: Boolean, description: String? = null, enabled: Boolean = true,
    glyph: MenuGlyph? = null, secondary: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Syncr.colors
    val shape = RoundedCornerShape(8.dp)
    val content = when { selected -> c.desk; !enabled -> c.muted; secondary -> c.muted; else -> c.ink }
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(shape)
            .then(when { selected -> Modifier.background(c.ink, shape); secondary -> Modifier; else -> Modifier.hairlineBorder(c.dots) })
            .combinedClickable(enabled = enabled, role = Role.Tab, onLongClick = onLongClick, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description ?: text; this.selected = selected }
            .padding(horizontal = if (secondary) 10.dp else 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) { MenuIcon(glyph, content, size = 16.dp); Spacer(Modifier.width(6.dp)) }
            Text(text, style = Syncr.type.label, color = content, maxLines = 1)
        }
    }
}

/** A calm bottom bar for a reversible action, e.g. delete → Undo. Announced politely. */
@Composable
private fun UndoBar(message: String, enabled: Boolean, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = Syncr.colors
    Row(modifier.fillMaxWidth().background(c.chrome, RoundedCornerShape(8.dp)).hairlineBorder(c.dots)
        .semantics { liveRegion = LiveRegionMode.Polite }
        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = Syncr.type.body, color = c.ink, modifier = Modifier.weight(1f))
        LabelButton("Undo", onUndo, enabled = enabled, description = "Undo: restore the note")
    }
}

/** Recently deleted: restore, or delete forever after confirming. Notes are removed after 30 days. */
@Composable
private fun RecentlyDeletedList(deleted: List<DeletedNoteSummary>, now: Long, enabled: Boolean,
    onRestore: (String) -> Unit, onPurge: (DeletedNoteSummary) -> Unit) {
    val c = Syncr.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)) {
        item(key = "about") {
            Text("Deleted notes stay here for 30 days, then they're removed from this phone.",
                style = Syncr.type.caption, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (deleted.isEmpty()) item(key = "empty") {
            Text("Nothing in Recently deleted.", style = Syncr.type.body, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        }
        rows(deleted, key = { it.id }) { note ->
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                        Text(note.title, style = Syncr.type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${deletedCaption(note.deletedAtMs, now)} · ${note.notebook ?: "Inbox"}", style = Syncr.type.caption,
                            color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconAction(MenuGlyph.RESTORE, "Restore", c.ink, enabled, "Restore ${note.title}") { onRestore(note.id) }
                    IconAction(MenuGlyph.TRASH, "Delete forever", c.attention, enabled, "Delete ${note.title} forever") { onPurge(note) }
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun IconAction(glyph: MenuGlyph, label: String, tint: Color, enabled: Boolean, description: String, onClick: () -> Unit) {
    val colour = if (enabled) tint else Syncr.colors.muted.copy(alpha = 0.5f)
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuIcon(glyph, colour, size = 18.dp)
        Text(label, style = Syncr.type.label, color = colour, modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewNoteCell(enabled: Boolean, defaultFormat: PageFormat, onNew: (PageFormat) -> Unit) {
    val c = Syncr.colors
    var menu by remember { mutableStateOf(false) }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(8.dp)).hairlineBorder(c.dots)
            .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = { menu = true }) { onNew(defaultFormat) }
            .semantics { contentDescription = "New note with ${defaultFormat.label()}. Long-press for page formats" },
            contentAlignment = Alignment.Center) {
            Text("+", style = Syncr.type.heading, color = if (enabled) c.muted else c.dots)
            Box(Modifier.align(Alignment.TopEnd)) {
                Box(Modifier.size(48.dp).combinedClickable(enabled = enabled, role = Role.Button) { menu = true }
                    .semantics { contentDescription = "Choose page format" }, contentAlignment = Alignment.Center) {
                    Text("⌄", style = Syncr.type.title, color = c.muted)
                }
                DropdownMenu(menu, { menu = false }, containerColor = c.chrome) {
                    DropdownMenuItem({ Column { Text("Phone pages"); Text("Tall pages that scroll", style = Syncr.type.caption, color = c.muted) } },
                        onClick = { menu = false; onNew(PageFormat.PHONE) })
                    DropdownMenuItem({ Column { Text("16:10 sheets"); Text("The shape of a Mac screen", style = Syncr.type.caption, color = c.muted) } },
                        onClick = { menu = false; onNew(PageFormat.SHEET16X10) })
                }
            }
        }
        Text("New note", style = Syncr.type.title, color = if (enabled) c.ink else c.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: NoteSummary, thumbnail: Thumbnail?, showNotebook: Boolean, now: Long, enabled: Boolean,
    onOpen: () -> Unit, onMove: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    val c = Syncr.colors
    var menu by remember { mutableStateOf(false) }
    val date = relativeDate(note.modifiedAtMs, now)
    val notebook = note.notebook ?: "Inbox"
    val image = rememberThumbnail(thumbnail, c.dark)
    Box {
        Column(Modifier.combinedClickable(enabled = enabled, role = Role.Button, onLongClick = { menu = true }, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Note ${note.title}, edited $date, in $notebook${if (note.archived) ", archived" else ""}. Long-press for options" }) {
            Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(8.dp)).background(c.paper).hairlineBorder(c.dots)) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            }
            Text(note.title, style = Syncr.type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Text(if (showNotebook) "$date · $notebook" else date, style = Syncr.type.caption, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        DropdownMenu(menu, { menu = false }, containerColor = c.chrome) {
            val tint = if (enabled) c.ink else c.muted
            DropdownMenuItem({ Text("Move to notebook…") }, enabled = enabled, onClick = { menu = false; onMove() },
                leadingIcon = { MenuIcon(MenuGlyph.FOLDER, tint) })
            DropdownMenuItem({ Text("Rename…") }, enabled = enabled, onClick = { menu = false; onRename() },
                leadingIcon = { MenuIcon(MenuGlyph.PENCIL, tint) })
            DropdownMenuItem({ Text(if (note.archived) "Unarchive" else "Archive") }, enabled = enabled,
                onClick = { menu = false; onArchive() },
                leadingIcon = { MenuIcon(if (note.archived) MenuGlyph.UNARCHIVE else MenuGlyph.ARCHIVE, tint) })
            DropdownMenuItem({ Text("Delete", color = if (enabled) c.attention else c.muted) }, enabled = enabled,
                onClick = { menu = false; onDelete() },
                leadingIcon = { MenuIcon(MenuGlyph.TRASH, if (enabled) c.attention else c.muted) })
        }
    }
}

private const val CARD_ASPECT = 1.25f
private const val THUMB_W = 400
private const val THUMB_H = 320

/** Renders the decoded first-page ink into a small bitmap off the main thread (no FFI here). */
@Composable
private fun rememberThumbnail(thumbnail: Thumbnail?, dark: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val ink by remember(context) { InkPreferencesStore.state(context) }.collectAsState()
    return produceState<ImageBitmap?>(null, thumbnail, dark, ink) {
        value = thumbnail?.takeIf { it.strokes.isNotEmpty() }?.let { t ->
            withContext(Dispatchers.Default) { renderThumbnail(t, dark, ink).asImageBitmap() }
        }
    }.value
}

private fun renderThumbnail(t: Thumbnail, dark: Boolean, ink: InkPreferences): Bitmap {
    val bitmap = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    // Width-fit with a small margin, top-aligned: the top of the first page.
    val margin = THUMB_W * 0.04f
    val scale = (THUMB_W - 2 * margin) / t.pageWidth
    canvas.translate(margin, margin)
    // Same Ink brushes as the page, so the card matches the note (drawThumbnail applies the scale).
    drawThumbnail(canvas, t, dark, scale, ink)
    return bitmap
}
