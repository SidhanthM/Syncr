package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.syncr.core.NoteKind
import dev.syncr.prototype.notes.LibraryFilter
import dev.syncr.prototype.notes.NoteCanvasView
import dev.syncr.prototype.notes.NoteViewStore
import dev.syncr.prototype.notes.PageViewSettings
import dev.syncr.prototype.notes.NotesState
import kotlinx.coroutines.delay

/** Which sheet or note dialog is open over the app; one at a time. */
private enum class Overlay { NONE, SETTINGS, MORE, RENAME, MOVE }

/**
 * Screen switching, the sheets, and the closure guard. Every path that leaves a note goes through
 * [ClosureGuard.close]; only the repository decides whether leaving is allowed.
 */
@Composable
fun SyncrApp(vm: NotesViewModel = viewModel(), dark: Boolean, appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onFinish: () -> Unit, onPointer: () -> Unit) {
    val repo = vm.repository
    StippleDebug.Listen()
    CanvasDebugHooks(vm)
    val state by repo.state.collectAsStateWithLifecycle()
    val guard = remember(vm) { ClosureGuard(vm) }
    var paper by remember { mutableStateOf<NoteCanvasView?>(null) }
    var filter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }
    var tools by remember { mutableStateOf(ToolState()) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var sidebar by remember { mutableStateOf(false) }
    var currentSheet by remember { mutableStateOf<String?>(null) }
    val libraryReady = rememberLibraryReady(state)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    fun closeNote(after: () -> Unit) {
        paper?.cancelGesture()
        guard.close(state, after)
    }
    ReleaseGesturesWhenHidden(vm) { paper }

    BackHandler {
        val finishLibrary: () -> Unit = { vm.run { repo.releaseLibrary(); onFinish() } }
        closeNote(if (state.snapshot != null) ({}) else finishLibrary)
    }
    // Back from Archived or Recently deleted returns to All rather than leaving the app.
    BackHandler(enabled = state.snapshot == null && (filter == LibraryFilter.Archived || filter == LibraryFilter.Deleted)) {
        filter = LibraryFilter.All
    }
    // Registered later, so it wins: back first folds away the tool bar.
    BackHandler(enabled = state.snapshot != null && tools.barOpen) { tools = tools.folded() }

    val snapshot = state.snapshot
    val context = androidx.compose.ui.platform.LocalContext.current
    // How this note's pages are laid out and read on this phone: kept on the device, never in the note.
    var viewSettings by remember(snapshot?.noteId) { mutableStateOf(snapshot?.let { NoteViewStore.get(context, it.noteId) } ?: PageViewSettings()) }
    LaunchedEffect(snapshot == null) {
        if (snapshot == null) {
            if (overlay != Overlay.SETTINGS) overlay = Overlay.NONE
            sidebar = false; currentSheet = null
            paper = null; vm.canvasViewport = null
            tools = tools.folded()
        }
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
            onExport = { guard.confirmExport = true },
            onResolve = guard::resolve,
            onDismiss = { repo.dismissMessage() })
    }
    if (snapshot == null) {
        LibraryScreen(state, filter, libraryReady, libraryActions(vm, filter, onFilter = { filter = it },
            onPointer = { closeNote(onPointer) }, onSettings = { overlay = Overlay.SETTINGS }), banner = banner)
    } else {
        val actions = NoteActions(
            onTools = { tools = it }, onPaper = { paper = it },
            onUndo = { paper?.cancelGesture(); paper?.deselect(); vm.run { repo.history(false) } },
            onRedo = { paper?.cancelGesture(); paper?.deselect(); vm.run { repo.history(true) } },
            onFit = { paper?.fit() },
            onMore = { tools = tools.folded(); overlay = Overlay.MORE }, onPage = { currentSheet = it })
        if (snapshot.kind == dev.syncr.core.NoteKind.CANVAS) CanvasEditorScreen(vm, state, dark, tools, actions, banner = banner)
        else NoteScreen(vm, state, dark, tools, viewSettings, actions, banner = banner)
        if (sidebar && snapshot.kind == NoteKind.NOTE) {
            BackHandler { sidebar = false }
            PageSidebar(state, currentSheet ?: snapshot.pages.firstOrNull { !it.tombstone }?.sheetId, dark, PageSidebarActions(
                onClose = { sidebar = false },
                onGoTo = { paper?.goToPage(it) },
                onAdd = { after ->
                    val page = snapshot.pages.firstOrNull { it.sheetId == after }
                    val (w, h) = page?.takeIf { !it.autoHeight }?.let { it.width to it.height } ?: NoteCanvasView.A4
                    vm.run { repo.addPage(after, w, h)?.let { paper?.goToWhenAdded(it) } }
                },
                onDuplicate = { ids -> vm.run { repo.duplicatePages(ids) } },
                onRotate = { ids, turn -> vm.run { repo.rotatePages(ids, turn) } },
                onSize = { id, w, h -> vm.run { repo.resizePage(id, w, h) } },
                onDelete = { ids -> vm.run { repo.deletePages(ids) } },
                onMove = { ids, after -> vm.run { repo.movePages(ids, after) } }))
        }
    }

    when {
        overlay == Overlay.SETTINGS && snapshot == null -> SettingsSheet(appearance, vm.location, onAppearance,
            onDismiss = { overlay = Overlay.NONE })
        snapshot == null -> Unit
        overlay == Overlay.MORE -> MoreSheet(state, MoreActions(
            onDismiss = { overlay = Overlay.NONE },
            onRename = { overlay = Overlay.RENAME },
            onMove = { overlay = Overlay.MOVE },
            onExport = { guard.confirmExport = true },
            onPointer = { overlay = Overlay.NONE; closeNote(onPointer) },
            onFit = { overlay = Overlay.NONE; paper?.fit() },
            onLibrary = { overlay = Overlay.NONE; closeNote {} },
            onArchive = { archived -> vm.run { repo.archiveOpenNote(archived) } },
            // Close through the guard (unsaved changes), then delete the now-closed note; the library offers Undo.
            onDelete = { val id = snapshot.noteId; overlay = Overlay.NONE; closeNote { vm.run { repo.deleteNote(id) } } },
            view = viewSettings,
            onView = { next -> viewSettings = next; NoteViewStore.set(context, snapshot.noteId, next) },
            onAddPage = { overlay = Overlay.NONE; paper?.addPage() },
            onFitPage = { fit -> overlay = Overlay.NONE; paper?.fitPage(fit) },
            onZoom = { up -> paper?.zoomStep(up) },
            zoomPercent = { paper?.zoomPercent() ?: 100 },
            onPageList = { overlay = Overlay.NONE; sidebar = true }))
        overlay == Overlay.RENAME -> RenameDialog(snapshot.title, onDismiss = { overlay = Overlay.NONE },
            onSave = { title -> overlay = Overlay.NONE; vm.run { repo.rename(title) } })
        overlay == Overlay.MOVE -> MoveNoteDialog(snapshot.title, snapshot.notebook, state.notebooks.map { it.name },
            onDismiss = { overlay = Overlay.NONE },
            onPick = { target -> overlay = Overlay.NONE; if (target != snapshot.notebook) vm.run { repo.moveOpenNote(target) } })
    }
    ClosureGuardDialogs(guard, state, onChooseExport = {
        export.launch("Syncr-${snapshot?.noteId ?: "unsaved"}.syncr-recovery.json")
    })
}

/** Library actions, each a call on the repository's worker. */
private fun libraryActions(vm: NotesViewModel, filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit,
    onPointer: () -> Unit, onSettings: () -> Unit): LibraryActions {
    val repo = vm.repository
    return LibraryActions(
        onFilter = onFilter,
        onCreateNote = { title, layout -> vm.run { repo.create(title, filter.newNoteNotebook, NoteKind.NOTE, layout) } },
        onCreateCanvas = { title -> vm.run { repo.create(title, filter.newNoteNotebook, NoteKind.CANVAS) } },
        onOpen = { id -> vm.run { repo.open(id) } },
        onPointer = onPointer,
        onSettings = onSettings,
        onCreateNotebook = { name -> vm.run { repo.createNotebook(name) } },
        onRenameNotebook = { old, new -> vm.run {
            repo.renameNotebook(old, new)
            if (filter == LibraryFilter.Notebook(old) && repo.state.value.notebooks.any { it.name == new }) onFilter(LibraryFilter.Notebook(new))
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
    )
}

/** Cancels a half-drawn stroke when the app goes to the background, and refreshes the library on return. */
@Composable
private fun ReleaseGesturesWhenHidden(vm: NotesViewModel, paper: () -> NoteCanvasView?) {
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) paper()?.cancelGesture()
            if (event == Lifecycle.Event.ON_RESUME) vm.run { vm.repository.refresh() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); paper()?.cancelGesture() }
    }
}

/**
 * True once the library has loaded at least once, so the first frame shows placeholders rather than
 * "No notes yet". Loading starts on a worker, so the first frames can be neither busy nor loaded: anything in the
 * library, an error, having seen it busy, or a short grace period all count as ready.
 */
@Composable
private fun rememberLibraryReady(state: NotesState): Boolean {
    var ready by rememberSaveable { mutableStateOf(false) }
    var sawBusy by remember { mutableStateOf(false) }
    var graceOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600); graceOver = true }
    LaunchedEffect(state.busy) { if (state.busy) sawBusy = true }
    val loaded = !state.busy && (sawBusy || graceOver || state.error != null || state.notes.isNotEmpty() ||
        state.notebooks.isNotEmpty() || state.deleted.isNotEmpty())
    LaunchedEffect(loaded) { if (loaded) ready = true }
    return ready || loaded
}
