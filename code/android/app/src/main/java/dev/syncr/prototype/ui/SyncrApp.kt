package dev.syncr.prototype.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.notes.NoteCanvasView
import dev.syncr.core.PageFormat

/**
 * Screen switching plus the closure guard. Every path that leaves a note goes through [closeOrGuard];
 * only the repository decides whether leaving is allowed (receipt, export or confirmed discard).
 */
@Composable
fun SyncrApp(vm: NotesViewModel = viewModel(), dark: Boolean, appearance: Appearance, defaultFormat: PageFormat,
    onAppearance: (Appearance) -> Unit, onDefaultFormat: (PageFormat) -> Unit,
    onFinish: () -> Unit, onPointer: () -> Unit) {
    val repo = vm.repository
    val state by repo.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    var paper by remember { mutableStateOf<NoteCanvasView?>(null) }
    var filter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }
    var tools by remember { mutableStateOf(ToolState()) }
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var guard by remember { mutableStateOf(false) }
    var discardConfirm by remember { mutableStateOf(false) }
    var exportConfirm by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    fun closeOrGuard(after: () -> Unit) {
        paper?.cancelGesture()
        leaving = after
        if (state.unsaved) guard = true
        else vm.run { if (repo.leave()) { after(); leaving = null } }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) paper?.cancelGesture()
            if (event == Lifecycle.Event.ON_RESUME) vm.run { repo.refresh() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); paper?.cancelGesture() }
    }
    BackHandler {
        val finishLibrary: () -> Unit = { vm.run { repo.releaseLibrary(); onFinish() } }
        closeOrGuard(if (state.snapshot != null) ({}) else finishLibrary)
    }
    // Back from Archived or Recently deleted returns to All rather than leaving the app.
    BackHandler(enabled = state.snapshot == null && (filter == LibraryFilter.Archived || filter == LibraryFilter.Deleted)) {
        filter = LibraryFilter.All
    }
    // Registered later, so it wins: back first folds away the tool bar.
    BackHandler(enabled = state.snapshot != null && tools.barOpen) { tools = tools.copy(barOpen = false, optionsOpen = false) }

    val snapshot = state.snapshot
    LaunchedEffect(snapshot == null) {
        if (snapshot == null) { more = false; renaming = false; moving = false; paper = null
            tools = tools.copy(barOpen = false, optionsOpen = false) }
    }
    // A notebook that no longer exists (renamed elsewhere, deleted) falls back to All.
    LaunchedEffect(state.notebooks) {
        val current = filter
        if (current is LibraryFilter.Notebook && state.notebooks.none { it.name == current.name }) filter = LibraryFilter.All
    }
    // Card thumbnails: missing or stale ones are read on the worker while the library is showing.
    LaunchedEffect(snapshot == null, state.notes) { if (snapshot == null) repo.loadThumbnails() }

    val banner: @Composable () -> Unit = {
        StatusBanner(state,
            onRetry = { vm.run { if (state.unsaved || state.libraryTask != null) repo.retry() else repo.refresh() } },
            onExport = { exportConfirm = true },
            onResolve = { leaving = {}; guard = true },
            onDismiss = { repo.dismissMessage() })
    }
    if (snapshot == null) {
        LibraryScreen(state, filter, LibraryActions(
            onFilter = { filter = it },
            onNew = { format -> vm.run { repo.create(defaultNoteTitle(System.currentTimeMillis()), format, filter.newNoteNotebook) } },
            onOpen = { id -> vm.run { repo.open(id) } },
            onPointer = { closeOrGuard(onPointer) },
            onSettings = { settings = true },
            onCreateNotebook = { name -> vm.run { repo.createNotebook(name) } },
            onRenameNotebook = { old, new -> vm.run {
                repo.renameNotebook(old, new)
                if (filter == LibraryFilter.Notebook(old) && repo.state.value.notebooks.any { it.name == new }) filter = LibraryFilter.Notebook(new)
            } },
            onDeleteNotebook = { name -> vm.run { repo.deleteNotebook(name) } },
            onMoveNote = { id, notebook -> vm.run { repo.moveNote(id, notebook) } },
            onRenameNote = { id, title -> vm.run { repo.renameNote(id, title) } },
            onArchiveNote = { id, archived -> vm.run { repo.archiveNote(id, archived) } },
            onDeleteNote = { id -> vm.run { repo.deleteNote(id) } },
            onRestoreNote = { id -> vm.run { repo.restoreNote(id) } },
            onPurgeNote = { id -> vm.run { repo.purgeNote(id) } },
            onUndoDelete = { vm.run { repo.undoDelete() } },
            onDismissDeleted = { repo.dismissDeleted() },
        ), defaultFormat = defaultFormat, banner = banner)
    } else {
        NoteScreen(vm, state, dark, tools, onTools = { tools = it }, onPaper = { paper = it },
            onUndo = { paper?.cancelGesture(); paper?.deselect(); vm.run { repo.history(false) } },
            onRedo = { paper?.cancelGesture(); paper?.deselect(); vm.run { repo.history(true) } },
            onFit = { paper?.fit() },
            onMore = { tools = tools.copy(barOpen = false, optionsOpen = false); more = true },
            banner = banner)
    }

    if (settings && snapshot == null) SettingsSheet(appearance, defaultFormat, vm.location, onAppearance, onDefaultFormat,
        onDismiss = { settings = false })
    if (more && snapshot != null) MoreSheet(state, vm.location,
        onDismiss = { more = false },
        onRename = { renaming = true },
        onMove = { moving = true },
        onAddPage = { vm.run { repo.addSheet() } },
        onExport = { exportConfirm = true },
        onPointer = { more = false; closeOrGuard(onPointer) },
        onLibrary = { more = false; closeOrGuard {} },
        onArchive = { archived -> vm.run { repo.archiveOpenNote(archived) } },
        // Close through the guard (unsaved changes), then delete the now-closed note; the library offers Undo.
        onDelete = { val id = snapshot.noteId; more = false; closeOrGuard { vm.run { repo.deleteNote(id) } } })
    if (renaming && snapshot != null) RenameDialog(snapshot.title, onDismiss = { renaming = false },
        onSave = { title -> renaming = false; vm.run { repo.rename(title) } })
    if (moving && snapshot != null) MoveNoteDialog(snapshot.title, snapshot.notebook, state.notebooks.map { it.name },
        onDismiss = { moving = false },
        onPick = { target -> moving = false; if (target != snapshot.notebook) vm.run { repo.moveOpenNote(target) } })
    if (guard) GuardDialog(state,
        onStay = { guard = false; leaving = null },
        onRetry = { vm.run { repo.retry(); if (!repo.state.value.unsaved && repo.leave()) {
            guard = false; leaving?.invoke(); leaving = null
        } } },
        onExport = { exportConfirm = true },
        onLeaveAfterExport = { vm.run { if (repo.leave()) { guard = false; leaving?.invoke(); leaving = null } } },
        onDiscard = { discardConfirm = true })
    if (discardConfirm) DiscardDialog(onKeep = { discardConfirm = false }, onDiscard = {
        discardConfirm = false
        vm.run { if (repo.leave(discard = true)) { guard = false; leaving?.invoke(); leaving = null } }
    })
    if (exportConfirm) ExportDialog(onCancel = { exportConfirm = false }, onChoose = {
        exportConfirm = false; export.launch("Syncr-${snapshot?.noteId ?: "unsaved"}.syncr-recovery.json")
    })
}
