package dev.syncr.prototype.ui

import androidx.compose.ui.unit.dp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.newId
import dev.syncr.prototype.link.android.LinkController
import dev.syncr.prototype.notes.InkPreview
import dev.syncr.prototype.notes.NoteCanvasView
import dev.syncr.prototype.notes.NotesState
import dev.syncr.prototype.notes.TapCommand
import dev.syncr.prototype.notes.inkColour
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Space
import dev.syncr.prototype.ui.theme.Syncr

/** Tool choice and floating-chrome visibility, hoisted so it survives leaving and reopening notes. */
data class ToolState(
    val brush: Brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK),
    val eraser: Boolean = false,
    val lasso: Boolean = false,
    val barOpen: Boolean = false,
    val optionsOpen: Boolean = false,
) {
    val pen get() = !eraser && !lasso && brush.family == BrushFamily.PEN
    val highlighter get() = !eraser && !lasso && brush.family == BrushFamily.HIGHLIGHTER
    /** Folds the bar and its options back into the bubble. */
    fun folded() = copy(barOpen = false, optionsOpen = false)
}

/** What the note's chrome can ask for; the canvas itself talks to the repository directly. */
class NoteActions(
    val onTools: (ToolState) -> Unit,
    val onPaper: (NoteCanvasView) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onFit: () -> Unit,
    val onMore: () -> Unit,
)

/**
 * The note (design "Canvas, resting"): one continuous canvas, and at rest only your writing and the tool dot.
 * "Title · Notebook" appears while the bar is open (design `.tb-cap`); a banner only when something needs
 * attention, and the selection bar only while strokes are selected.
 */
@Composable
fun NoteScreen(vm: NotesViewModel, state: NotesState, dark: Boolean, tools: ToolState, actions: NoteActions,
    banner: @Composable () -> Unit) {
    val snapshot = state.snapshot ?: return
    val c = Syncr.colors
    var view by remember { mutableStateOf<NoteCanvasView?>(null) }
    var selected by remember(snapshot.noteId) { mutableIntStateOf(0) }
    var contentId by androidx.compose.runtime.saveable.rememberSaveable(snapshot.noteId) { mutableStateOf<String?>(null) }
    var response by androidx.compose.runtime.saveable.rememberSaveable(snapshot.noteId) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(c.desk).imePadding()) {
        NoteCanvas(vm, state, dark, tools, actions, onView = { view = it }, onSelection = { selected = it },
            onContent = { if (contentId == null) { contentId = it.id; response = it.response } })

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
            .padding(horizontal = Space.l, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val notebook = snapshot.notebook ?: "Inbox"
            AnimatedVisibility(tools.barOpen, enter = fadeIn(tween(Motion.FADE_MS)), exit = fadeOut(tween(Motion.FADE_MS))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("${snapshot.title} · $notebook", style = Syncr.type.label, color = c.muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            .semantics { contentDescription = "${snapshot.title}, in $notebook" })
                    LinkDot()
                }
            }
            banner()
            AnimatedVisibility(selected > 0, Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(tween(Motion.FADE_MS)), exit = fadeOut(tween(Motion.FADE_MS))) {
                SelectionBar(selected,
                    onColour = { colour -> view?.restyleSelection(colour) },
                    onDelete = { view?.deleteSelection() },
                    onDeselect = { view?.deselect() })
            }
        }

        if (contentId != null && !response) {
            Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(8.dp)) {
                androidx.compose.runtime.key(snapshot.noteId, contentId) {
                    MobileTextEditor(vm.repository, contentId?.ifEmpty { null }, view) { contentId = null }
                }
            }
        } else {
            androidx.compose.material3.FilledTonalButton(
                onClick = { contentId = ""; response = false },
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 80.dp),
                enabled = !state.closing) { Text("Type") }
            NoteBubble(state, tools, actions)
        }
    }
    contentId?.let { id ->
        if (response) ResponseInspector(vm.repository, snapshot, id) { contentId = null }
    }
}

/** The canvas view, wired to the repository and the tool state. */
@Composable
private fun NoteCanvas(vm: NotesViewModel, state: NotesState, dark: Boolean, tools: ToolState, actions: NoteActions,
    onView: (NoteCanvasView) -> Unit, onSelection: (Int) -> Unit,
    onContent: (dev.syncr.prototype.notes.ContentTarget) -> Unit) {
    val repo = vm.repository
    val context = LocalContext.current
    val gestures by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    val link = remember(context) { LinkController.get(context) }
    val mirror = link.mirror
    LaunchedEffect(tools.brush) { link.usePen(tools.brush) }
    AndroidView(factory = { ctx -> NoteCanvasView(ctx).also { canvas ->
        canvas.restoredViewport = vm.canvasViewport
        canvas.onViewportChanged = { id, mapping -> vm.canvasViewport = id to mapping }
        onView(canvas); actions.onPaper(canvas)
    } }, modifier = Modifier.fillMaxSize(),
        update = { canvas ->
            canvas.brush = tools.brush; canvas.eraser = tools.eraser; canvas.lasso = tools.lasso
            canvas.sideButtonErases = gestures.sideButton == SideButtonAction.ERASE
            canvas.doubleTapFit = gestures.doubleTapFit; canvas.palmMs = gestures.palmDelay.millis
            canvas.onError = repo::message
            canvas.mirror = mirror
            canvas.onStroke = { sheetId, channels, usedBrush ->
                // A mirrored stroke is saved under the ID the Mac's preview already carries.
                val preview = InkPreview(canvas.mirroredStrokeId ?: newId(), sheetId, channels, usedBrush)
                if (repo.stageStroke(preview)) {
                    vm.run { repo.retry() }; preview
                } else null
            }
            canvas.onMarks = { edit -> repo.stageMarks(edit).also { staged -> if (staged) vm.run { repo.retry() } } }
            canvas.onTap = { command ->
                when (command) {
                    TapCommand.UNDO -> if (gestures.twoFingerUndo) { canvas.deselect(); vm.run { repo.history(false) } }
                    TapCommand.REDO -> { canvas.deselect(); vm.run { repo.history(true) } }
                    TapCommand.FIT -> Unit // already handled by the view
                }
            }
            canvas.onContent = onContent
            canvas.onSelection = onSelection
            canvas.onTouchStart = { if (tools.barOpen) actions.onTools(tools.folded()) }
            canvas.update(state, dark)
        })
}

/** The draggable tool bubble and its bar. Choosing a tool folds the bar; tapping the active tool (or a long press) opens its options. */
@Composable
private fun NoteBubble(state: NotesState, tools: ToolState, actions: NoteActions) {
    val snapshot = state.snapshot ?: return
    val c = Syncr.colors
    val status = saveStatus(state)
    val canUndo = dev.syncr.prototype.notes.handwritingHistoryAvailable(snapshot, false) && !state.unsaved && !state.busy
    val canRedo = dev.syncr.prototype.notes.handwritingHistoryAvailable(snapshot, true) && !state.unsaved && !state.busy
    val onTools = actions.onTools
    fun choose(next: ToolState) = onTools(next.folded())
    fun options(family: BrushFamily) = onTools(tools.copy(eraser = false, lasso = false,
        brush = tools.brush.copy(family = family), optionsOpen = true))
    val pip = if (tools.eraser || tools.lasso) null else Color(inkColour(tools.brush, c.dark))
    FloatingChrome(open = tools.barOpen, optionsOpen = tools.optionsOpen && !tools.eraser && !tools.lasso,
        description = bubbleDescription(tools.brush, tools.eraser, tools.lasso, status), stateDescription = status.headline,
        onOpen = { onTools(tools.copy(barOpen = true, optionsOpen = false)) },
        ring = { BubbleRing(status.ring.kind(), pip) },
        glyph = { ToolGlyph(tools.brush, tools.eraser, tools.lasso) },
        bar = { drag, release -> NoteToolBar(tools.brush, tools.eraser,
            canUndo = canUndo, canRedo = canRedo,
            onPen = { if (tools.pen) onTools(tools.copy(optionsOpen = !tools.optionsOpen))
                else choose(tools.copy(eraser = false, lasso = false, brush = tools.brush.copy(family = BrushFamily.PEN))) },
            onHighlighter = { if (tools.highlighter) onTools(tools.copy(optionsOpen = !tools.optionsOpen))
                else choose(tools.copy(eraser = false, lasso = false, brush = tools.brush.copy(family = BrushFamily.HIGHLIGHTER))) },
            onEraser = { choose(tools.copy(eraser = true, lasso = false)) },
            onUndo = actions.onUndo, onRedo = actions.onRedo, onFit = actions.onFit, onMore = actions.onMore,
            lasso = tools.lasso, onLasso = { choose(tools.copy(lasso = true, eraser = false)) },
            onPenOptions = { options(BrushFamily.PEN) }, onHighlighterOptions = { options(BrushFamily.HIGHLIGHTER) },
            onDrag = drag, onRelease = release) },
        options = { ToolOptions(tools.brush,
            onSize = { onTools(tools.copy(brush = tools.brush.copy(size = it))) },
            onColour = { onTools(tools.copy(brush = tools.brush.copy(colour = it))) }) })
}
