package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.syncr.core.DeletedNoteSummary
import dev.syncr.core.NoteSummary
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.notes.NewNoteChoiceCodec
import dev.syncr.prototype.notes.NewNoteLayout
import dev.syncr.prototype.notes.nextUntitled
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Radius
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr
import dev.syncr.prototype.ui.theme.floatingSurface
import kotlinx.coroutines.delay

/** Library actions; all storage happens in the repository's worker. */
class LibraryActions(
    val onFilter: (LibraryFilter) -> Unit,
    /** Creates a note with its first page's size and look, or a canvas; each opens what it made. */
    val onCreateNote: (title: String, layout: NewNoteLayout) -> Unit,
    val onCreateCanvas: (title: String) -> Unit,
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

/** Which library dialog is open; one at a time. */
private sealed interface LibraryDialog {
    data object NewNotebook : LibraryDialog
    data class RenameNotebook(val name: String) : LibraryDialog
    data class DeleteNotebook(val name: String) : LibraryDialog
    data class MoveNote(val note: NoteSummary) : LibraryDialog
    data class RenameNote(val note: NoteSummary) : LibraryDialog
    data class Purge(val note: DeletedNoteSummary) : LibraryDialog
    data object NewNote : LibraryDialog
    data object NewCanvas : LibraryDialog
}

/**
 * The library (design "Write mode on the phone", Library): a calm page with the "Notes" heading, a row of
 * notebook chips and a grid with "New note" first. Problems and Undo float at the bottom, above the gesture bar,
 * so they never push the grid around. Content scrolls behind the navigation bar; the header keeps clear of the
 * status bar and cutout.
 *
 * [ready] is false until the library has loaded once, so the empty state never flashes at launch.
 */
@Composable
fun LibraryScreen(state: NotesState, filter: LibraryFilter, ready: Boolean, actions: LibraryActions,
    banner: @Composable () -> Unit) {
    val c = Syncr.colors
    val enabled = !state.busy && !state.unsaved && state.libraryTask == null
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var overlayHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val now = remember(state.notes, state.deleted) { System.currentTimeMillis() }
    val navigation = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    val bottom = navigation.calculateBottomPadding() + with(density) { overlayHeight.toDp() } + Space.xl
    val content = PaddingValues(start = Space.page, end = Space.page, top = Space.s, bottom = bottom)

    Box(Modifier.fillMaxSize().background(c.desk)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            LibraryTopBar(tabletEnabled = !state.busy, onPointer = actions.onPointer, onSettings = actions.onSettings)
            NotebookChips(state.notebooks, filter, enabled, onFilter = actions.onFilter,
                onNewNotebook = { dialog = LibraryDialog.NewNotebook },
                onRenameNotebook = { dialog = LibraryDialog.RenameNotebook(it) },
                onDeleteNotebook = { dialog = LibraryDialog.DeleteNotebook(it) })
            Box(Modifier.weight(1f)) {
                if (filter == LibraryFilter.Deleted) RecentlyDeletedList(state.deleted, now, enabled, ready, content,
                    onRestore = actions.onRestoreNote, onPurge = { dialog = LibraryDialog.Purge(it) })
                else NoteGrid(state, filter, now, enabled, ready, content, NoteGridActions(
                    onNewNote = { dialog = LibraryDialog.NewNote }, onNewCanvas = { dialog = LibraryDialog.NewCanvas }, onOpen = actions.onOpen,
                    onMove = { dialog = LibraryDialog.MoveNote(it) }, onRename = { dialog = LibraryDialog.RenameNote(it) },
                    onArchive = { actions.onArchiveNote(it.id, !it.archived) }, onDelete = { actions.onDeleteNote(it.id) }))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = Space.page).padding(bottom = Space.l)
            .onSizeChanged { overlayHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            banner()
            UndoDeleteBar(state.recentlyDeleted, enabled = !state.busy && state.libraryTask == null,
                onUndo = actions.onUndoDelete, onTimeout = actions.onDismissDeleted)
        }
    }
    LibraryDialogs(dialog, state, filter, onClose = { dialog = null }, actions)
}

@Composable
private fun LibraryDialogs(dialog: LibraryDialog?, state: NotesState, filter: LibraryFilter, onClose: () -> Unit, actions: LibraryActions) {
    val names = state.notebooks.map { it.name }
    val context = androidx.compose.ui.platform.LocalContext.current
    when (dialog) {
        null -> Unit
        LibraryDialog.NewNote -> NewNoteScreen(filter.newNoteNotebook ?: "Inbox", defaultNoteTitle(System.currentTimeMillis()),
            remember { NewNoteChoiceCodec.get(context) }, onClose = onClose,
            onCreate = { name, choice ->
                val layout = choice.layout() ?: return@NewNoteScreen
                NewNoteChoiceCodec.set(context, choice) // the last choices are the next defaults
                onClose(); actions.onCreateNote(name, layout)
            })
        LibraryDialog.NewCanvas -> NewCanvasDialog(nextUntitled(state.notes.map { it.title }), onDismiss = onClose,
            onCreate = { onClose(); actions.onCreateCanvas(it) })
        LibraryDialog.NewNotebook -> NotebookNameDialog("New notebook", "", names, null,
            onDismiss = onClose, onSave = { onClose(); actions.onCreateNotebook(it) })
        is LibraryDialog.RenameNotebook -> NotebookNameDialog("Rename notebook", dialog.name, names, dialog.name,
            onDismiss = onClose, onSave = { onClose(); actions.onRenameNotebook(dialog.name, it) })
        is LibraryDialog.DeleteNotebook -> {
            val count = state.notebooks.find { it.name == dialog.name }?.noteCount?.toLong() ?: 0L
            DeleteNotebookDialog(dialog.name, count, onDismiss = onClose, onDelete = { onClose(); actions.onDeleteNotebook(dialog.name) })
        }
        is LibraryDialog.MoveNote -> MoveNoteDialog(dialog.note.title, dialog.note.notebook, names, onDismiss = onClose,
            onPick = { target -> onClose(); if (target != dialog.note.notebook) actions.onMoveNote(dialog.note.id, target) })
        is LibraryDialog.RenameNote -> RenameDialog(dialog.note.title, onDismiss = onClose,
            onSave = { title -> onClose(); if (title != dialog.note.title) actions.onRenameNote(dialog.note.id, title) })
        is LibraryDialog.Purge -> PurgeDialog(dialog.note.title, onCancel = onClose,
            onDelete = { onClose(); actions.onPurgeNote(dialog.note.id) })
    }
}

/**
 * "Moved to Recently deleted · Undo", floating above the content. It stays for 8 seconds, or longer when the
 * system asks for more time (TalkBack, accessibility timeout settings).
 */
@Composable
private fun UndoDeleteBar(noteId: String?, enabled: Boolean, onUndo: () -> Unit, onTimeout: () -> Unit) {
    val c = Syncr.colors
    val reduced = Syncr.reducedMotion
    val accessibility = LocalAccessibilityManager.current
    noteId?.let { id ->
        LaunchedEffect(id) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(8_000, containsIcons = false, containsText = true,
                containsControls = true) ?: 8_000)
            onTimeout()
        }
    }
    AnimatedVisibility(noteId != null, enter = Motion.appear(reduced), exit = Motion.disappear(reduced)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().floatingSurface(Radius.m)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Text("Moved to Recently deleted", style = Syncr.type.body, color = c.ink, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = Space.m))
            TextAction("Undo", onUndo, enabled = enabled, description = "Undo, put the note back")
        }
    }
}

/** Card thumbnails are 4:5, like the design's tall cards showing the top of the page. */
internal const val CARD_ASPECT = 0.8f

/** Minimum card width: two columns on a phone, more on wide or landscape screens. */
internal val CARD_MIN_WIDTH = 136.dp
