package dev.syncr.prototype.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withClip
import dev.syncr.prototype.canvas.CanvasMode
import dev.syncr.prototype.canvas.UnitRect
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.SampleChannels
import dev.syncr.prototype.link.InkMirror
import dev.syncr.prototype.ui.theme.DustyRose
import dev.syncr.prototype.ui.theme.PaperPalette
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One continuous note canvas. Fingers navigate; the pen draws, erases or lassos. New ink freezes the
 * canvas mapping at pen-down and always targets the first sheet; existing ink keeps its sheet-local
 * samples. AndroidX Ink strokes are disposable caches: canonical samples and sheet origins come from
 * the core, and new samples go out through [onStroke].
 *
 * This view routes input and draws; the work is in its collaborators: [CanvasScene] (what to show),
 * [CanvasViewport] and [PanZoomTracker] (navigation), [PenCapture] (pen gestures), [CanvasSelection],
 * [DryInkLayer] (rendering cache) and [WetInkBridge] (front-buffer wet ink).
 */
class NoteCanvasView(context: Context) : FrameLayout(context) {
    /** A finished stroke; returns the staged preview, or null when it was not staged. */
    var onStroke: (sheetId: String, SampleChannels, Brush) -> InkPreview? = { _, _, _ -> null }

    /** An eraser or selection edit; returns whether it was staged. */
    var onMarks: (MarkEdit) -> Boolean = { false }
    var onError: (String) -> Unit = {}

    /** Finger-tap commands (undo/redo). FIT is handled here and also reported. */
    var onTap: (TapCommand) -> Unit = {}
    var onContent: (ContentTarget) -> Unit = {}
    /** A tap on a rendered task box: the text block and the line to flip. */
    var onTask: (String, Int) -> Unit = { _, _ -> }
    private val textInput = CanvasTextInput(this)
    private var lastTextTap: Pair<Float, Float>? = null
    /** Where a tap on empty paper (or the Type tool) wants the caret; read once when the field opens. */
    private var caretWish: CaretWish? = null
    /** The field opened on a newline the tap added itself: nothing is stored unless something is typed. */
    internal var startedOnNewLine = false
        private set
    /** Where the caret was when typing last ended, for the Type tool to return to. */
    private var lastCaret: Int? = null
    private var editingBlock: String? = null
    private var editorBottom = 0f
    private var textSnapshot: dev.syncr.core.NoteSnapshot? = null
    /** Where the text sits on the paper (its sheet's page place), and the other sheets' places relative to it, for free text. */
    private var textOffset: Placement = 0f to 0f
    private var textOffsets: Map<String, Placement> = emptyMap()

    /** Reading settings for this note on this device (Single, Double, Cover; scroll or flip); setting them lays the pages out again. */
    var viewSettings = PageViewSettings()
        set(value) {
            if (field == value) return
            val before = currentPage()?.sheetId
            field = value
            layoutPages(); refreshGeometry()
            // A new layout shows the whole row of the page you were on (both pages of a pair), fitted to the width.
            before?.let { id ->
                goToPage(id)
                val spot = spots.firstOrNull { it.sheetId == id }
                if (spot != null && width > 0) viewport.setMapping(PageNav.fitRow(spots.filter { it.row == spot.row }, width, height))
            }
        }
    private var spots = emptyList<PageSpot>()
    private var layoutKey: Triple<dev.syncr.core.NoteKind, List<dev.syncr.core.PageView>, PageViewSettings>? = null
    private var focusRow = 0
    private var flipAnimating = false
    /** Hears the page in view (index, count, sheet ID) whenever it changes. */
    var onPages: (Int, Int, String?) -> Unit = { _, _, _ -> }
    private var reportedPage = Triple(-2, -2, null as String?)
    /** The page after which a new one goes, and its size: the current page's (or A4 under the legacy column). */
    var onAddPage: (after: String?, width: Float, height: Float) -> Unit = { _, _, _ -> }
    private var goToSheet: String? = null
    private val paper = PagePaper()
    private var inkSheet: String? = null
    private var inkShift: Placement = 0f to 0f
    private var padShift: Placement = 0f to 0f
    private var inkGroupsFor: List<InkPreview>? = null
    private var inkGroupsSpots: List<PageSpot>? = null
    private var inkGroups = emptyMap<String, List<InkPreview>>()
    private var pullArmed = false
    private var lastMoveTime = 0L
    private var pullAnimator: android.animation.ValueAnimator? = null
    private val textView = CullRect()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 14f * context.resources.displayMetrics.scaledDensity }
    private var titleDisplay: TitleDisplay? = null

    /**
     * Starts typing in block [draft] (the note's document when it has no ID). Like Obsidian's reading view, the page is
     * zoomed so the text column fills the screen when the text would otherwise be under 15 sp; the zoom stays.
     */
    internal fun beginTextEditing(draft: TextDraft, value: String, state: (dev.syncr.prototype.editor.TextSel, Boolean, Boolean) -> Unit) {
        textInput.onState = state
        textInput.onPan = { dy -> viewport.navigate(0f, dy, 1f, 0f, 0f); invalidate() }
        textInput.onFling = { vy -> fling(0f, vy) }
        textInput.onExtent = { bottom -> editorBottom = if (bottom > 0f) bottom + 900f else 0f; refreshGeometry() }
        val block = textSnapshot?.textBlocks?.firstOrNull { it.id == draft.blockId }
        val document = block == null || block.role == dev.syncr.core.TextRole.DOCUMENT
        val place = draft.place?.takeIf { draft.blockId == null }
        val rect = draft.blockId?.let(text::bounds) ?: if (place != null) {
            // A new free block: its field sits where the tap was, the text 45 units below the block's origin as drawn.
            val (dx, dy) = textOffsets[place.sheetId] ?: (0f to 0f)
            RectF(place.x + dx, place.y + dy + 45f, place.x + dx + place.width, place.y + dy + 45f + 64f)
        } else if (document) {
            RectF(100f, 260f, (block?.width ?: 1600f) - 100f, 324f)
        } else {
            RectF(block!!.originX + block.transform.tx.toFloat(), block.originY + block.transform.ty.toFloat(),
                block.originX + block.transform.tx.toFloat() + block.width, block.originY + block.transform.ty.toFloat() + 64f)
        }
        editingBlock = draft.blockId ?: place?.blockId ?: textSnapshot?.textBlocks?.firstOrNull { it.role == dev.syncr.core.TextRole.DOCUMENT && !it.tombstone }?.id
        // Where the tap landed on the page, kept in canvas units so a zoom changes nothing about it.
        val sheetTap = lastTextTap?.let { textMapping()?.toSheet(it.first, it.second) } ?: (rect.left to rect.top)
        legibleZoom(rect, sheetTap)
        val extras = text.extras()?.takeIf { it.third == value }
        val placed = caretWish?.let { TapRouter.place(it, value) }
        startedOnNewLine = placed?.third == true
        textInput.begin(placed?.first ?: value, rect, sheetTap, dev.syncr.prototype.editor.EditorExtras(extras?.first.orEmpty(), extras?.second.orEmpty()), contentDark, placed?.second)
        lastTextTap = null; caretWish = null
        text.show(textSnapshot, contentDark, editingBlock, titleDisplay, textOffsets)
    }

    /** Zooms so the text column fills the width with a 16 dp gutter when body text would be under 15 sp (§5.3). */
    private fun legibleZoom(rect: RectF, anchor: Pair<Float, Float>) {
        val m = viewport.mapping ?: return
        val tm = textMapping() ?: return
        if (MacMetrics.BODY * m.scale / density >= LEGIBLE_SP) return
        val target = (width - 2f * GUTTER_DP * density) / rect.width()
        if (target <= m.scale) return
        viewport.fitText(target, GUTTER_DP * density - (rect.left + textOffset.first) * target, anchor.second + textOffset.second, tm.toScreen(anchor.first, anchor.second).second)
        invalidate()
    }

    /** The IDs and sizes of the page's own lines are rebuilt for the new text once it is saved. */
    internal fun textExtent() = text.extras()

    internal fun setTextExtras(extras: dev.syncr.prototype.editor.EditorExtras) = textInput.setExtras(extras)
    internal fun applyText(next: dev.syncr.prototype.editor.TextSel, step: Boolean = true) = textInput.apply(next, step)
    internal fun currentText() = textInput.current()
    internal fun undoText() = textInput.undo()
    internal fun redoText() = textInput.redo()
    internal fun resetText(value: String) = textInput.reset(value)
    internal fun setTextInset(px: Int) { textInput.bottomInset = px }

    /** A new free text block the next editor opens on (set by a tap on a page other than the one the document lives on). */
    private var freePlace: FreePlace? = null
    private var pendingFreeTap: FreePlace? = null
    internal fun takeFreePlace(): FreePlace? = freePlace.also { freePlace = null }

    /** Where a tap on empty paper makes a free block: on a page that does not hold the document, at the tap. */
    private fun freePlaceAt(x: Float, y: Float): FreePlace? {
        val snapshot = textSnapshot ?: return null
        val (cx, cy) = viewport.mapping?.toSheet(x, y) ?: return null
        val page = spots.firstOrNull { it.contains(cx, cy) } ?: return null
        if (page.sheetId == PageNav.textSheet(snapshot, spots)) return null
        val ox = (cx - page.x).coerceIn(40f, maxOf(40f, page.width - 340f))
        val oy = (cy - page.y - 45f).coerceIn(0f, maxOf(0f, page.height - 140f))
        return FreePlace(page.sheetId, ox, oy, minOf(900f, page.width - ox - 40f).coerceAtLeast(300f), dev.syncr.core.newId())
    }

    /** The Type tool: typing starts where the caret was last, or on a new line at the end of the document. */
    fun startTyping() {
        if (reading) return
        if (canvasMode != null) {
            val m = viewport.mapping ?: return
            val (cx, cy) = m.toSheet(width * 0.1f, height * 0.3f)
            newFreeText(cx, cy)
            return
        }
        caretWish = lastCaret?.let { CaretWish.At(it) } ?: CaretWish.EndOfDocumentNewLine
        onContent(ContentTarget("", false))
    }

    fun endTextEditing() { if (textInput.active) lastCaret = textInput.current().end; textInput.end(); editingBlock = null; editorBottom = 0f; textSnapshot?.let { text.show(it, contentDark, null, titleDisplay, textOffsets) }
        refreshGeometry() }
    fun setTextEditingEnabled(enabled: Boolean) = textInput.setEnabled(enabled)

    /** Any new touch on the paper, e.g. to collapse floating tool chrome. */
    var onTouchStart: () -> Unit = {}

    /** Transient viewport retained by the ViewModel across Activity recreation, never note data. */
    var restoredViewport: Pair<String, SheetMapping>? = null
    var onViewportChanged: (String, SheetMapping) -> Unit = { _, _ -> }

    /** Number of selected strokes; 0 when nothing is selected. */
    var onSelection: (Int) -> Unit = {}

    /**
     * The live mirror to the Mac (wifi-link-v0 §3.4), or null. The pen thread only hands it samples; it never waits
     * (see [dev.syncr.prototype.link.PenFeed]).
     */
    var mirror: InkMirror? = null

    /** The ID the mirrored stroke in progress will be saved under, valid while [onStroke] runs; null if it is not mirrored. */
    var mirroredStrokeId: String? = null
        private set

    var brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
    var eraser = false
    var lasso = false
    /** The Type tool is active: finger taps type whatever the last pen tool was, and the pen still draws with it. */
    var typing = false
    /** The tool bar, its options or a menu is open (set by the screen); a tap that lands while it is only dismisses it. */
    var chromeOpen = false
    /** Hears whether the page's own title is on screen (the title chip hides while it is). */
    var onTitleVisible: (Boolean) -> Unit = {}
    private var titleVisible = true
    /** Hears whether the page is scrolled, so that content sits under the status bar. */
    var onContentUnderTop: (Boolean) -> Unit = {}
    private var underTop = false
    /** Hears the page scroll: positive pixels when it moves toward the end of the note (reading down), negative back up. */
    var onScrolled: (Float) -> Unit = {}
    private var lastTy = Float.NaN
    /** Hears the pen touch down or lift, so the sticky title can step aside. */
    var onPenContact: (down: Boolean) -> Unit = {}
    /**
     * Read mode: the pen pans and zooms like a finger and nothing is written, erased, selected, typed or flipped. Set by the screen
     * from the note's per-device choice; turning it on abandons whatever gesture, selection or open field there was.
     */
    var reading = false
        set(value) {
            if (field == value) return
            field = value
            if (value) { cancelGesture(); selection.clear(); if (textInput.active) endTextEditing() }
            invalidate()
        }
    private var penTouching = false
    /** Screen y of the last pen contact or typing tap: where the writer's attention was, for the title band to keep clear. */
    var lastWriteY = -1f
        private set
    private fun penContact(down: Boolean) { if (penTouching != down) { penTouching = down; onPenContact(down) } }
    private var chromeAtDown = false
    private var selectionAtDown = false
    private var fitTookTap = false
    private val startTypingNow = Runnable { startTypingPending() }
    private var pendingWish: CaretWish? = null

    // Settings → Pen & gestures (ui/AppPreferences.kt), pushed in by NoteScreen.
    var sideButtonErases = true
        set(value) {
            field = value
            if (!value) setTemporaryEraser(false)
        }
    /** Transient hardware override only; never changes the owner's selected tool. */
    var onTemporaryEraser: (Boolean) -> Unit = {}
    private var temporaryEraser = false
    private fun setTemporaryEraser(active: Boolean) {
        if (temporaryEraser == active) return
        temporaryEraser = active
        onTemporaryEraser(active)
    }
    private fun updateTemporaryEraser(event: MotionEvent) {
        if (reading) return
        val pen = (0 until event.pointerCount).any { isPenTool(event.getToolType(it)) }
        if (pen) setTemporaryEraser(sideButtonErases &&
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
    }
    var doubleTapFit = true
    var palmMs = PALM_MS

    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val accent = DustyRose.toArgb()
    private var dark = false
    /** Whether what is written on the paper (ink and text) uses the dark theme's colours: the theme's, unless a page has a custom paper colour, which decides. */
    private var contentDark = false

    private val scene = CanvasScene()
    private val viewport = CanvasViewport()

    /** The note's text and the companion's comments under the ink, built and recorded off the main thread. */
    private var textBottom = 0f
    private val text = TextLayer(invalidate = ::invalidate, onExtent = { bottom ->
        textBottom = bottom
        refreshGeometry()
        canvasContentChanged()
        invalidate()
    })
    private val panZoom = PanZoomTracker()
    private val pen = PenCapture(scene, lassoStepPx = density)
    private val selection = CanvasSelection(
        scene,
        density,
        slop,
        onMarks = { onMarks(it) },
        onCount = { onSelection(it) },
    )
    private val taps = ViewConfiguration.get(context).let {
        FingerTapClassifier(
            it.scaledTouchSlop.toFloat(),
            it.scaledDoubleTapSlop.toFloat(),
            doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
        )
    }
    private var scope = mainScope()
    private var prefs = InkPreferencesStore.state(context).value
    private val dry = DryInkLayer(
        scope = { scope },
        isHandedOff = scene::hasStrokeHandoff,
        invalidate = ::invalidate,
    )
    private val wetBridge = WetInkBridge(context, onFinished = dry::adoptWetStroke, invalidate = ::invalidate)
    private val pad = FrontInkPad(context, onFinished = dry::adoptWetStroke, invalidate = ::invalidate)

    /** The layer drawing the stroke under the pen: chosen at each pen-down, the bridge until the first one. */
    private var wet: WetInk = wetBridge
    private val hoverIcon = StylusHoverIcon(density)

    // Touch routing
    private enum class FingerMode { NONE, PAN, SELECTION }

    /** Pointer ID of the pen in contact, or -1. */
    private var activePointer = -1

    /** An ink stroke is being sent to [mirror]. */
    private var mirroring = false
    private var lastPenUp = Long.MIN_VALUE / 2
    /** The eraser gesture in progress (its commands undo as one step) and when it last committed. */
    private var eraseGesture: String? = null
    private var lastEraseCommit = 0L

    /** Fingers are ignored until they all lift (palm rule, pen contact, cancel). */
    private var palmBlocked = false
    private var fingerMode = FingerMode.NONE
    private var tapStart: Triple<Float, Float, Long>? = null

    /** The finger is still within tap slop and time: not a pan yet. */
    private var tapCandidate = false
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val longPress = Runnable { onLongPress() }

    // Drawing
    private val pageRect = RectF()

    /** The visible canvas in canvas units, refreshed each draw (reused, no per-frame allocation). */
    private val viewRect = CullRect()
    private val outlinePath = Path()
    private val dottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(1.5f * density, 4.5f * density), 0f)
        color = accent
    }
    private val outlineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x12d39999
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        color = accent
    }

    init {
        setWillNotDraw(false)
        addView(wetBridge.view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Hidden until the setting is on, so with it off there is no second surface at all.
        addView(pad.view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentDescription = "Note canvas. Stylus draws; one finger scrolls, pinch zooms. " +
            "Two-finger tap undoes, three-finger tap redoes, double-tap empty paper to fit the page, long-press selects a stroke."
        isFocusable = true
    }

    // ---- The unbounded canvas (canvas kind) ----

    /** Set once by the canvas editor: this view then has no page, no clip and no navigation limits (see [CanvasMode]). */
    internal var canvasMode: CanvasMode? = null
        private set
    /** Hears the zoom as a percentage whenever it changes, for the toolbar. */
    var onZoomPercent: (Int) -> Unit = {}
    private var initialViewPending = false
    private var autoMapping: SheetMapping? = null
    private var autoBounds: UnitRect? = null
    private var shownPercent = -1
    private var minimapDrag = false
    private var viewSaved: SheetMapping? = null
    private val saveView = Runnable { saveViewNow() }

    internal fun attachCanvas(mode: CanvasMode) {
        canvasMode = mode
        viewport.setGeometry(CanvasGeometry)
        text.canvasMode = true
        doubleTapFit = false
    }

    /** "Fit all": the content plus 48 xnotes px of padding; an empty canvas goes to 100% at the origin. */
    fun fitAll() {
        val mode = canvasMode ?: return
        if (activePointer != -1 || width == 0) return
        val m = viewport.mapping ?: SheetMapping(1f, 0f, 0f)
        scroller.forceFinished(true)
        viewport.setMapping(mode.fitAll(m, width, height))
        invalidate()
    }

    /** The current view as a waypoint called [name]. */
    fun currentWaypoint(name: String): dev.syncr.core.Waypoint? {
        val m = viewport.mapping ?: return null
        return canvasMode?.waypoint(m, width, height, name)
    }

    fun goTo(waypoint: dev.syncr.core.Waypoint) {
        val mode = canvasMode ?: return
        val m = viewport.mapping ?: return
        scroller.forceFinished(true)
        viewport.setMapping(mode.goTo(m, width, height, waypoint))
        invalidate()
    }

    fun setMinimap(on: Boolean) { canvasMode?.minimapOn = on; invalidate() }

    /** The first view of an opened canvas: the one kept on this device, else all the content, else the origin. */
    private fun placeInitialView() {
        val mode = canvasMode ?: return
        val id = scene.noteId ?: return
        if (!initialViewPending || width == 0 || height == 0 || textSnapshot?.noteId != id) return
        initialViewPending = false
        val saved = mode.savedView(id, width, height)
        val placed = saved ?: mode.fitAll(SheetMapping(1f, 0f, 0f), width, height)
        viewport.setMapping(placed)
        // With nothing kept, the first fit is redone when the words arrive (they are laid out off the main thread).
        autoMapping = if (saved == null) viewport.mapping else null
        autoBounds = mode.contentBounds()
    }

    /** Ink or text changed: rebuild the item boxes, and redo the first fit while the view is still the one it made. */
    private fun canvasContentChanged() {
        val mode = canvasMode ?: return
        mode.sync(scene, text.boxes(), text.version)
        val auto = autoMapping
        if (auto != null && auto == viewport.mapping && mode.contentBounds() != autoBounds) {
            autoBounds = mode.contentBounds()
            viewport.setMapping(mode.fitAll(auto, width, height))
            autoMapping = viewport.mapping
        }
    }

    private fun saveViewNow() {
        val id = scene.noteId ?: return
        val m = viewport.mapping ?: return
        if (m == viewSaved) return
        viewSaved = m
        canvasMode?.saveView(id, m, width, height)
    }

    // ---- API ----

    fun update(state: NotesState, night: Boolean) {
        val noteId = state.snapshot?.noteId
        if (noteId != scene.noteId) startNote(noteId)
        dark = night
        textSnapshot = state.snapshot
        layoutPages()
        contentDark = paperDark(night)
        titleDisplay = state.snapshot?.let { snap -> titleDisplayOf(snap.title, snap.notebook, state.notes.firstOrNull { it.id == snap.noteId }?.modifiedAtMs) }
        scene.load(state)
        selection.dropIfStale()
        refreshGeometry()
        goToPending()
        text.penBusy = { activePointer != -1 || android.os.SystemClock.uptimeMillis() - lastPenUp < dev.syncr.prototype.ui.theme.Motion.PEN_QUIET_MS }
        text.reduced = runCatching { dev.syncr.prototype.ui.theme.Motion.reducedMotion(context) }.getOrDefault(false)
        text.cellUnits = dev.syncr.prototype.stipple.Stipple.cellUnits(viewport.mapping?.scale ?: 1f, density)
        text.show(state.snapshot, contentDark, editingBlock, titleDisplay, textOffsets)
        dry.show(scene.previews, contentDark)
        canvasMode?.let {
            canvasContentChanged()
            placeInitialView()
        }
        invalidate()
    }

    // ---- Pages ----

    /** Places the note's pages (the core's layout) and every sheet's offset. Cheap; the geometry follows in [refreshGeometry]. */
    private fun layoutPages() {
        val snapshot = textSnapshot
        // The core lays the pages out only when they, the kind or the reading settings change; every edit refreshes the snapshot.
        val key = snapshot?.let { Triple(it.kind, it.pages, viewSettings) }
        if (key != layoutKey) { spots = if (snapshot == null) emptyList() else PageSpots.of(snapshot, viewSettings); layoutKey = key }
        val placement = if (snapshot == null) emptyMap() else PageNav.placements(snapshot, spots)
        scene.placement = placement
        val sheet = snapshot?.let { PageNav.textSheet(it, spots) }
        textOffset = sheet?.let(placement::get) ?: (0f to 0f)
        text.paper = sheet?.let { id -> spots.firstOrNull { it.sheetId == id } }?.resolved?.resolvedStyle?.paper?.argb()
        textOffsets = placement.mapValues { (_, v) -> (v.first - textOffset.first) to (v.second - textOffset.second) }
        focusRow = focusRow.coerceIn(0, maxOf(0, PageSpots.rows(spots).size - 1))
    }

    /** The paper's rules for the viewport: the legacy column, the page list, or the canvas, whichever the note is. */
    private fun refreshGeometry() {
        val next = when {
            textSnapshot?.kind == dev.syncr.core.NoteKind.CANVAS -> CanvasGeometry
            spots.isEmpty() -> PagedGeometry(maxOf(scene.lowestInk(), textBottom, editorBottom))
            else -> PagedGeometry(0f, spots, if (viewSettings.flip && !flipAnimating) focusRow else null)
        }
        viewport.setGeometry(next)
    }

    /** Ink and text follow the paper: a custom paper colour decides light or dark ink, else the theme does. */
    private fun paperDark(night: Boolean): Boolean {
        val paper = spots.firstOrNull()?.resolved?.resolvedStyle?.paper ?: return night
        return (0.299f * paper.r.toInt() + 0.587f * paper.g.toInt() + 0.114f * paper.b.toInt()) / 255f < 0.5f
    }

    /** Text space (the text's own coordinates) to screen. */
    private fun textMapping(m: SheetMapping? = viewport.mapping): SheetMapping? =
        m?.let { SheetMapping(it.scale, it.tx + textOffset.first * it.scale, it.ty + textOffset.second * it.scale) }

    private fun SheetMapping.shifted(by: Placement) = SheetMapping(scale, tx + by.first * scale, ty + by.second * scale)

    /** The page in view, or null on a legacy column or a canvas. */
    fun currentPage(): PageSpot? = viewport.mapping?.let { PageNav.currentPage(spots, it, width, height) }

    fun pageCount() = spots.size

    /** Shows page [sheetId] from its top at the current zoom. */
    fun goToPage(sheetId: String) {
        val spot = spots.firstOrNull { it.sheetId == sheetId } ?: return
        val m = viewport.mapping ?: return
        scroller.forceFinished(true)
        if (viewSettings.flip) { focusRow = spot.row; refreshGeometry() }
        viewport.setMapping(PageNav.showing(spot, m.scale, width, height))
        invalidate()
    }

    /** Asks for the page to be shown once the next snapshot has it (a page that was just added). */
    fun goToWhenAdded(sheetId: String) { goToSheet = sheetId; goToPending() }

    private fun goToPending() {
        val id = goToSheet ?: return
        if (spots.none { it.sheetId == id } || width == 0) return
        goToSheet = null
        goToPage(id)
    }

    fun fitPage(fit: PageFit) {
        val page = currentPage() ?: run { fit(); return }
        if (activePointer != -1) return
        scroller.forceFinished(true)
        viewport.setMapping(PageNav.showing(page, PageNav.fitScale(page, fit, width, height), width, height))
        invalidate()
    }

    /** One zoom button (on a canvas: the canvas's own step): ×1.25 about the view's centre, within 12%..1600%, snapping to fit-width within 5%. */
    fun zoomStep(up: Boolean) {
        val m = viewport.mapping ?: return
        canvasMode?.let { mode ->
            // A canvas: one step about the centre, within the canvas's own range, with no snap to a fit.
            scroller.forceFinished(true)
            viewport.setMapping(mode.step(m, width, height, up))
            invalidate()
            return
        }
        if (activePointer != -1) return
        scroller.forceFinished(true)
        val range = viewport.geometry.scaleRange(viewport.fitScale)
        var target = (m.scale * (if (up) Xn.ZOOM_STEP else 1f / Xn.ZOOM_STEP)).coerceIn(range)
        if (spots.isNotEmpty()) target = PageNav.snapToFit(target, viewport.fitScale)
        viewport.zoomTo(target, width / 2f, height / 2f)
        invalidate()
    }

    /** Zoom as xnotes percent, for the label. */
    fun zoomPercent(): Int = Math.round(Xn.zoomOf(viewport.mapping?.scale ?: 1f) * 100f)

    /** Adds a page after the current one, sized like it (A4 under a legacy column). */
    fun addPage() = requestAddPage(currentPage())

    private fun requestAddPage(after: PageSpot?) {
        if (reading) return
        val last = after ?: spots.lastOrNull()
        val sheet = last?.sheetId ?: scene.firstSheet ?: return
        val size = last?.takeIf { !it.autoHeight }?.let { it.width to it.height } ?: A4
        onAddPage(sheet, size.first, size.second)
    }

    private fun addPageAtEnd() = requestAddPage(spots.lastOrNull())

    /** The title band's words: the stamp title humanised, and "Notebook · Edited ..." under it. */
    private fun titleDisplayOf(title: String, notebook: String?, editedMs: Long?): TitleDisplay {
        val now = System.currentTimeMillis()
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val edited = editedMs?.let { "Edited " + dev.syncr.prototype.ui.friendlyTime(it, now, h24) }
        return TitleDisplay(dev.syncr.prototype.ui.displayTitle(title, now, h24), listOfNotNull(notebook ?: "Inbox", edited).joinToString(" · "))
    }

    /** Back to the phone column. Ignored while the pen is down. */
    fun fit() {
        if (canvasMode != null) { fitAll(); return }
        if (activePointer != -1 || width == 0) return
        scroller.forceFinished(true)
        viewport.fitColumn()
        invalidate()
    }

    /**
     * Ends any gesture without committing it (pause, undo, leaving). Fingers stay blocked until they lift.
     * The eraser is the exception: what it already swept has visibly gone, so that is committed too.
     */
    fun cancelGesture() {
        setTemporaryEraser(false)
        if (pen.mode == PenMode.ERASE && !pen.rejected) commitErase(android.os.SystemClock.uptimeMillis(), force = true)
        eraseGesture = null
        cancelWet()
        pen.cancel()
        cancelMirror()
        penContact(false)
        activePointer = -1
        selection.cancelGesture()
        fingerMode = FingerMode.NONE
        removeCallbacks(longPress)
        panZoom.reanchor()
        palmBlocked = true
        taps.cancel()
        scroller.forceFinished(true)
        tapCandidate = false
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    fun deselect() {
        selection.clear()
        invalidate()
    }

    fun restyleSelection(colour: InkColour) {
        selection.restyle(colour)
        invalidate()
    }

    fun deleteSelection() {
        selection.delete()
        invalidate()
    }

    /** Test hook: canvas units to view pixels. */
    internal fun canvasMapping(): SheetMapping? = viewport.mapping

    private fun startNote(noteId: String?) {
        cancelGesture()
        clearWet()
        scene.clearHandoffs()
        dry.clearHandoffInk()
        deselect()
        viewport.reset(if (canvasMode != null) null else restoredViewport?.takeIf { it.first == noteId }?.second)
        if (canvasMode != null) { initialViewPending = true; autoMapping = null; viewSaved = null }
    }

    // ---- Lifecycle ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelGesture()
        viewport.resize(w, h, oldw)
        placeInitialView()
        if (textInput.active) post { textInput.ensureCaretVisible() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = mainScope()
            dry.forgetPreviews()
        }
        scope.launch { InkPreferencesStore.state(context).collect { prefs = it; pad.settingOn = it.frontBufferInk } }
        post { wetBridge.eagerInit() } // children attach after this callback
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(saveView); saveViewNow()
        removeCallbacks(startTypingNow)
        text.clear()
        cancelGesture()
        clearWet()
        dry.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            viewport.scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
            postInvalidateOnAnimation()
        } else if (flipAnimating) {
            flipAnimating = false
            refreshGeometry()
            postInvalidateOnAnimation()
        }
    }

    /** Haptic tick when a pull crosses the point where letting go will act. */
    private fun checkPullHaptic() {
        val flip = viewSettings.flip && spots.isNotEmpty()
        val armed = if (flip) kotlin.math.abs(viewport.pullX) >= FlipPolicy.pullPx(width.toFloat(), density, Xn.zoomOf(viewport.mapping?.scale ?: 1f) * 100f)
            else FlipPolicy.armed(viewport.pullY)
        if (armed && !pullArmed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        pullArmed = armed
    }

    /**
     * The finger lifted after a pan. Past the end of the document, a deep enough pull adds a page (vertical), and in paginated
     * mode a pull past the row's edge flips by [FlipPolicy]; either way the stretch springs back. True when the lift was used
     * that way, so no fling follows.
     */
    private fun liftPull(vx: Float, now: Long): Boolean {
        val px = viewport.pullX; val py = viewport.pullY
        val flip = viewSettings.flip && spots.isNotEmpty()
        val used = if (flip) px != 0f else py > 0f
        if (!used) { viewport.setPull(0f, 0f); return false }
        if (flip) {
            val rows = PageSpots.rows(spots).size
            val zoom = Xn.zoomOf(viewport.mapping?.scale ?: 1f) * 100f
            when (FlipPolicy.decide(px, -vx, now - lastMoveTime, focusRow >= rows - 1, width.toFloat(), density, zoom)) {
                Flip.NEXT -> { flipRow(+1); return true }
                Flip.PREVIOUS -> { flipRow(-1); return true }
                Flip.ADD -> addPageAtEnd()
                Flip.STAY -> Unit
            }
        } else if (FlipPolicy.armed(py)) addPageAtEnd()
        springBack()
        return true
    }

    private fun springBack() {
        pullAnimator?.cancel()
        val x0 = viewport.pullX; val y0 = viewport.pullY
        if (x0 == 0f && y0 == 0f) return
        if (dev.syncr.prototype.ui.theme.Motion.reducedMotion(context)) { viewport.setPull(0f, 0f); invalidate(); return }
        pullAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180
            addUpdateListener { viewport.setPull(x0 * it.animatedValue as Float, y0 * it.animatedValue as Float); invalidate() }
            start()
        }
    }

    /** Paginated mode: to the next or previous row of pages, the page's top-left in view, with a short glide. */
    private fun flipRow(delta: Int) {
        val rows = PageSpots.rows(spots)
        val target = (focusRow + delta).coerceIn(0, rows.size - 1)
        val real = viewport.mapping ?: return
        val shown = stretched(real)
        viewport.setPull(0f, 0f)
        if (target == focusRow) { viewport.setMapping(shown); springBack(); return }
        focusRow = target
        flipAnimating = true
        refreshGeometry() // the whole strip while gliding
        viewport.setMapping(shown)
        val end = PageNav.showing(rows[target].first(), shown.scale, width, height)
        scroller.forceFinished(true)
        scroller.startScroll(shown.tx.toInt(), shown.ty.toInt(), (end.tx - shown.tx).toInt(), (end.ty - shown.ty).toInt(), 260)
        postInvalidateOnAnimation()
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val mode = canvasMode
        if (mode == null) canvas.drawColor(PaperPalette.desk(dark))
        val real = viewport.mapping ?: return
        scene.noteId?.let { onViewportChanged(it, real) }
        pad.viewMayHaveChanged(real.shifted(padShift), width, height) // a moved canvas hands the pad's strokes to dry ink first
        if (mode != null) viewChanged(real)
        else {
        // The page's own title (canvas y 70 to about 165) hides the floating title chip while any of it is on screen.
        val titleShown = real.toScreen(textOffset.first, textOffset.second + MacMetrics.TITLE_Y + 100f).second > 24f * density
        if (titleShown != titleVisible) { titleVisible = titleShown; post { onTitleVisible(titleShown) } }
        if (!lastTy.isNaN() && real.ty != lastTy) onScrolled(lastTy - real.ty)
        lastTy = real.ty
        val under = real.ty < -1f
        if (under != underTop) { underTop = under; post { onContentUnderTop(under) } }
        }
        // A finger pulling past the end of the paper stretches what is drawn; the real mapping stays put.
        val m = stretched(real)
        textInput.position(m.shifted(textOffset), contentDark)
        dry.beginFrame()
        if (mode != null) { drawUnbounded(canvas, m, mode); return }
        viewRect.set(-m.tx / m.scale, -m.ty / m.scale, (width - m.tx) / m.scale, (height - m.ty) / m.scale)
        if (spots.isEmpty()) drawColumn(canvas, m) else drawPages(canvas, m)
        drawLassoAndSelection(canvas, m)
        drawPullHint(canvas, m)
        reportPage(real)
    }

    /** A legacy column: the paper from its top edge down past the bottom of the screen, today's drawing. */
    private fun drawColumn(canvas: Canvas, m: SheetMapping) {
        for (page in viewport.geometry.paper) paper.draw(canvas, m, page, null, dark, width, height, density, edges = false)
        pageRect.set(m.tx, m.ty, m.tx + LegacyPage.WIDTH * m.scale, height.toFloat())
        canvas.withClip(pageRect) {
            translate(m.tx, m.ty)
            scale(m.scale, m.scale)
            save()
            // Until the page's own copy of the words has been rebuilt without them, the field covers them.
            textInput.bounds?.let { if (!text.hides(editingBlock)) clipOutRect(it) }
            text.draw(this, viewRect) // finished bands only; never waits for layout
            if (text.drawDissolves(this)) postInvalidateOnAnimation()
            restore()
            trace(TraceNames.DRY_DRAW) {
                // The visible part of the canvas, to skip strokes wholly off screen.
                dry.previews.forEach { drawStroke(this, it, m.scale) }
                scene.strokeHandoffs.forEach { handoff ->
                    if (dry.previews.none { it.id == handoff.id }) drawStroke(this, handoff, m.scale)
                }
            }
            if (pen.mode == PenMode.INK && !wet.isDrawing && !wetBridge.isAttached) {
                pen.inkChannels()?.let { dry.drawLive(this, it, pen.brush, m.scale) }
            }
        }
    }

    /** Pages: each page's paper and pattern, then the text (which may sit off the page, like margin notes), then each page's ink clipped to it. */
    private fun drawPages(canvas: Canvas, m: SheetMapping) {
        for (spot in spots) {
            val r = spot.rect
            if (m.tx + r.right * m.scale < 0 || m.tx + r.left * m.scale > width || m.ty + r.bottom * m.scale < 0 || m.ty + r.top * m.scale > height) continue
            paper.draw(canvas, m, r, spot.resolved.resolvedStyle, dark, width, height, density)
        }
        canvas.save()
        canvas.translate(m.tx, m.ty)
        canvas.scale(m.scale, m.scale)
        canvas.save()
        canvas.translate(textOffset.first, textOffset.second)
        textView.set(viewRect.left - textOffset.first, viewRect.top - textOffset.second, viewRect.right - textOffset.first, viewRect.bottom - textOffset.second)
        textInput.bounds?.let { if (!text.hides(editingBlock)) canvas.clipOutRect(it) }
        text.draw(canvas, textView)
        if (text.drawDissolves(canvas)) postInvalidateOnAnimation()
        canvas.restore()
        trace(TraceNames.DRY_DRAW) {
            val groups = inkByPage()
            for (spot in spots) {
                if (spot.right < viewRect.left || spot.x > viewRect.right || spot.bottom < viewRect.top || spot.y > viewRect.bottom) continue
                canvas.save()
                canvas.clipRect(spot.x, spot.y, spot.right, spot.bottom)
                groups[spot.sheetId]?.forEach { drawStroke(canvas, it, m.scale) }
                scene.strokeHandoffs.forEach { handoff ->
                    if (ownerPage(handoff.sheetId) == spot.sheetId && dry.previews.none { it.id == handoff.id }) drawStroke(canvas, handoff, m.scale)
                }
                if (pen.mode == PenMode.INK && !wet.isDrawing && !wetBridge.isAttached && inkSheet == spot.sheetId) {
                    pen.inkChannels()?.let {
                        canvas.save()
                        canvas.translate(inkShift.first, inkShift.second)
                        dry.drawLive(canvas, it, pen.brush, m.scale)
                        canvas.restore()
                    }
                }
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /** The page whose ink a sheet's strokes are drawn with: itself, or page 1 for an older sheet inside it. */
    private fun ownerPage(sheetId: String): String? =
        if (spots.any { it.sheetId == sheetId }) sheetId else scene.firstSheet

    private fun inkByPage(): Map<String, List<InkPreview>> {
        if (inkGroupsFor !== dry.previews || inkGroupsSpots !== spots) {
            inkGroups = dry.previews.groupBy { ownerPage(it.sheetId) ?: "" }
            inkGroupsFor = dry.previews; inkGroupsSpots = spots
        }
        return inkGroups
    }

    /** The mapping as drawn: the real one, stretched by a finger pulling past the end (vertical scroll) or flipping (paginated). */
    private fun stretched(m: SheetMapping): SheetMapping {
        val px = viewport.pullX; val py = viewport.pullY
        val flip = viewSettings.flip && spots.isNotEmpty()
        val sx = if (flip) (px * 0.5f).coerceIn(-0.35f * width, 0.35f * width) else 0f
        val sy = if (!flip && py > 0f) FlipPolicy.stretch(py) else 0f
        return if (sx == 0f && sy == 0f) m else SheetMapping(m.scale, m.tx - sx, m.ty - sy)
    }

    /** What the stretch is for, said in the space it opens at the bottom (or the side): pull to add, release to add. */
    private fun drawPullHint(canvas: Canvas, m: SheetMapping) {
        val py = viewport.pullY; val px = viewport.pullX
        val flip = viewSettings.flip && spots.isNotEmpty()
        val atLast = flip && focusRow >= PageSpots.rows(spots).size - 1
        val shown = if (!flip) py > 0f else (atLast && px > 0f)
        if (!shown) return
        val armed = if (!flip) FlipPolicy.armed(py) else px >= FlipPolicy.addPx(width.toFloat())
        hintPaint.color = if (armed) accent else PaperPalette.quiet(dark)
        val text = if (armed) "Release to add a page" else "Pull to add a page"
        if (!flip) canvas.drawText(text, width / 2f, height - 20f * density, hintPaint)
        else canvas.drawText(text, width - 90f * density, height / 2f, hintPaint)
    }

    private fun reportPage(real: SheetMapping) {
        val page = PageNav.currentPage(spots, real, width, height)
        val now = Triple(page?.index ?: -1, spots.size, page?.sheetId)
        if (now != reportedPage) { reportedPage = now; post { onPages(now.first, now.second, now.third) } }
    }

    /** What changed in the view since the last frame: the zoom for the toolbar, and a kept view once the finger rests. */
    private fun viewChanged(m: SheetMapping) {
        val mode = canvasMode ?: return
        val percent = mode.percent(m)
        if (percent != shownPercent) { shownPercent = percent; post { onZoomPercent(percent) } }
        if (m != viewSaved) { removeCallbacks(saveView); postDelayed(saveView, SAVE_VIEW_MS) }
        autoMapping?.let { if (it != m) autoMapping = null }
    }

    /** The unbounded canvas: ruling on the paper, then text and ink culled to the view, then the minimap. No clip anywhere. */
    private fun drawUnbounded(canvas: Canvas, m: SheetMapping, mode: CanvasMode) {
        mode.drawBackground(canvas, PaperPalette.paper(dark))
        canvas.save()
        canvas.translate(m.tx, m.ty)
        canvas.scale(m.scale, m.scale)
        viewRect.set(-m.tx / m.scale, -m.ty / m.scale, (width - m.tx) / m.scale, (height - m.ty) / m.scale)
        canvas.save()
        textInput.bounds?.let { if (!text.hides(editingBlock)) canvas.clipOutRect(it) }
        text.draw(canvas, viewRect)
        if (text.drawDissolves(canvas)) postInvalidateOnAnimation()
        canvas.restore()
        trace(TraceNames.DRY_DRAW) {
            val previews = dry.previews
            // The grid index finds the strokes in view; a stroke moving under the finger is not where the index has it.
            val seen = if (previews === scene.previews && selection.gesture == null)
                mode.visible(UnitRect(viewRect.left.toDouble(), viewRect.top.toDouble(), viewRect.right.toDouble(), viewRect.bottom.toDouble())) else null
            if (seen != null) for (k in 0 until seen.size) previews.getOrNull(seen[k])?.let { drawStroke(canvas, it, m.scale) }
            else previews.forEach { drawStroke(canvas, it, m.scale) }
            scene.strokeHandoffs.forEach { handoff ->
                if (previews.none { it.id == handoff.id }) drawStroke(canvas, handoff, m.scale)
            }
        }
        if (pen.mode == PenMode.INK && !wet.isDrawing && !wetBridge.isAttached) {
            pen.inkChannels()?.let { dry.drawLive(canvas, it, pen.brush, m.scale) }
        }
        canvas.restore()
        drawLassoAndSelection(canvas, m)
        mode.drawMinimap(canvas, width, height, m, dark)
    }

    private fun drawStroke(canvas: Canvas, preview: InkPreview, scale: Float) {
        val id = preview.id
        // Erased (staged, handed off or under the eraser now), or still on the wet layer.
        if (scene.isHidden(id) || id in pen.erased || wetBridge.isFinishing(id) || pad.isFinishing(id)) return
        var affine = scene.canvasTransformOf(id, preview.sheetId)
        selection.gesture?.let { gesture -> if (id in selection.ids) affine = compose(gesture, affine) }
        if (!dry.isVisible(preview, affine, viewRect)) return
        dry.draw(canvas, preview, affine, scale)
    }

    private fun drawLassoAndSelection(canvas: Canvas, m: SheetMapping) {
        if (pen.mode == PenMode.LASSO && pen.lassoPoints.size > 1) {
            canvas.drawPath(screenPath(pen.lassoPoints, pen.frozen, close = false), dottedPaint)
        }
        val (outline, mapping) = selection.outline(m) ?: return
        val closed = screenPath(outline, mapping, close = true)
        canvas.drawPath(closed, outlineFillPaint)
        canvas.drawPath(closed, dottedPaint)
        val bounds = Bounds.of(outline) ?: return
        bounds.corners().forEach { (x, y) ->
            val (sx, sy) = mapping.toScreen(x, y)
            handleFillPaint.color = if (dark) 0xff1e1e1e.toInt() else 0xffffffff.toInt()
            canvas.drawCircle(sx, sy, 3.2f * density, handleFillPaint)
            canvas.drawCircle(sx, sy, 3.2f * density, handleStrokePaint)
        }
    }

    private fun screenPath(points: List<Pt>, mapping: SheetMapping, close: Boolean): Path = outlinePath.apply {
        rewind()
        points.forEachIndexed { i, (x, y) ->
            val (sx, sy) = mapping.toScreen(x, y)
            if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
        }
        if (close) close()
    }

    // ---- Input ----

    // Every touch and hover is ours; the wet-ink overlay only draws.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // Finger selection/caret/IME gestures belong to the inline editor; a stylus still draws.
        if (textInput.active && event.pointerCount == 1 && !isPenTool(event.getToolType(0))) return false
        return true
    }

    override fun onInterceptHoverEvent(event: MotionEvent) = true

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (reading || !isPenTool(event.getToolType(0))) return super.onHoverEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) setTemporaryEraser(false)
        else updateTemporaryEraser(event)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            updateTemporaryEraser(event)
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) setTemporaryEraser(false)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (reading || !isPenTool(event.getToolType(pointerIndex))) return super.onResolvePointerIcon(event, pointerIndex)
        val scale = viewport.mapping?.scale ?: return super.onResolvePointerIcon(event, pointerIndex)
        val units = when {
            eraser || temporaryEraser -> ERASER_HOVER_UNITS
            lasso -> 0f
            else -> brushWidth(brush)
        }
        val selecting = lasso && !eraser && !temporaryEraser
        val colour = if (selecting) accent else PaperPalette.quiet(dark)
        return hoverIcon.icon(units * scale, colour, dashed = selecting)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = trace(TraceNames.TOUCH_INPUT) { handleTouch(event) }

    private fun handleTouch(event: MotionEvent): Boolean {
        updateTemporaryEraser(event)
        if (scene.closing) {
            cancelGesture()
            return true
        }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            // What was open when the finger landed decides whether its tap only dismisses; landing folds it.
            chromeAtDown = chromeOpen; selectionAtDown = selection.ids.isNotEmpty(); fitTookTap = false
            removeCallbacks(startTypingNow); pendingWish = null // a second touch within the double-tap window cancels typing
            onTouchStart()
            scroller.forceFinished(true)
        }
        if (pen.requireLift) {
            // A rejected gesture: ignore everything until all pointers lift.
            taps.cancel()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pen.requireLift = false
                cancelGesture()
                palmBlocked = false
            }
            return true
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        if (action == MotionEvent.ACTION_DOWN) {
            panZoom.start()
            fingerMode = FingerMode.NONE
            palmBlocked = !reading && isRestingPalm(isPenTool(event.getToolType(0)), event.eventTime, lastPenUp, palmMs)
        }
        if (isCancelled(event)) {
            cancelGesture()
            palmBlocked = action != MotionEvent.ACTION_CANCEL
            return true
        }
        if (palmBlocked) taps.cancel() else trackTaps(event)

        val down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        val up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
        val actionIndex = event.actionIndex
        if (down && !reading && isPenTool(event.getToolType(actionIndex))) {
            if (activePointer != -1) {
                // A second pen: drop both rather than guess.
                cancelGesture()
                pen.refuse()
                return true
            }
            // Pen contact wins: abandon any finger gesture (its temporary selection move is reverted).
            removeCallbacks(longPress)
            if (fingerMode == FingerMode.SELECTION) selection.cancelGesture()
            fingerMode = FingerMode.NONE
            tapCandidate = false
            activePointer = event.getPointerId(actionIndex)
            lastWriteY = event.getY(actionIndex)
            penContact(true)
            palmBlocked = true
            panZoom.reanchor()
            requestUnbufferedDispatch(event)
            penDown(event, actionIndex)
        } else if (activePointer != -1) {
            val index = event.findPointerIndex(activePointer)
            if (index < 0) {
                cancelGesture()
                return true
            }
            val penLifted = up && event.getPointerId(actionIndex) == activePointer
            if (action == MotionEvent.ACTION_MOVE || penLifted) {
                for (h in 0 until event.historySize) sample(event, index, h)
                sample(event, index, CURRENT)
                if (action == MotionEvent.ACTION_MOVE) wet.add(event)
                if (mirroring) mirror?.flush() // one frame per input batch, after the local ink has drawn
                if (pen.mode == PenMode.ERASE && action == MotionEvent.ACTION_MOVE) commitErase(event.eventTime, force = false)
            }
            if (penLifted) {
                lastWriteY = event.getY(index)
                penUp(event, index)
                penContact(false)
                lastPenUp = event.eventTime
            }
        } else if (!palmBlocked && (reading || (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER })) {
            pad.forceHandover() // a pan, zoom or selection move is coming: the pad's strokes go to dry ink first
            finger(event)
        }
        if (down || up) panZoom.reanchor()
        if (action == MotionEvent.ACTION_UP) {
            palmBlocked = false
            performClick()
        }
        // The wet overlay draws a moving ink stroke, so the dry canvas has nothing new to show.
        // Everything else (finger pan/zoom, eraser feedback, lasso, selection, down/up) redraws it.
        val inkMoving = action == MotionEvent.ACTION_MOVE && activePointer != -1 && pen.mode == PenMode.INK && wet.isDrawing
        if (!inkMoving) invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun penDown(event: MotionEvent, index: Int) {
        val x = event.getX(index)
        val y = event.getY(index)
        val mode = penModeFor(
            eraserSelected = eraser,
            lassoSelected = lasso,
            eraserEnd = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER,
            sideButtonHeld = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0,
            sideButtonErases = sideButtonErases,
        )
        pen.begin(prefs.drawn(brush)) // the stroke is stored with this look and feel; later settings never restyle it
        if (mode == PenMode.LASSO && selection.beginGesture(x, y, viewport.mapping)) {
            pen.startSelectionGesture()
            return
        }
        if (!isOnCanvas(x, y)) {
            pen.ignore()
            return
        }
        if (!lasso) selection.clear()
        val device = event.device
        // Ink is recorded in the local space of the page under the pen: the frozen mapping is shifted to that page, so the
        // wet layers, the mirror and the samples all work in sheet units, and the paper clip is that page's rect.
        val page = if (mode == PenMode.INK) pageAt(x, y) else null
        inkSheet = page?.sheetId ?: scene.firstSheet
        inkShift = page?.let { it.x to it.y } ?: (0f to 0f)
        pen.start(
            mode,
            if (mode == PenMode.INK) viewport.mapping!!.shifted(inkShift) else viewport.mapping!!,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
        )
        if (mode == PenMode.ERASE) {
            eraseGesture = java.util.UUID.randomUUID().toString()
            lastEraseCommit = event.eventTime
        }
        sample(event, index, CURRENT)
        if (mode == PenMode.INK && !pen.rejected) {
            val inkBrush = InkBrushes.brush(pen.brush, contentDark)
            val solid = pen.brush.family != BrushFamily.HIGHLIGHTER && (inkBrush.colorIntArgb ushr 24) == 0xff
            padShift = inkShift
            pad.viewMayHaveChanged(pen.frozen, width, height)
            wet = if (chooseWetPath(prefs.frontBufferInk, FrontInkPad.available != false, rotation == 0f, solid, pad.canStart(pen.frozen, width, height)) == WetPath.PAD) pad else wetBridge
            val (penX, penY) = viewport.mapping!!.toSheet(x, y)
            val clip = viewport.geometry.inkClip(penX, penY)?.let { CanvasRect(it.left - inkShift.first, it.top - inkShift.second, it.right - inkShift.first, it.bottom - inkShift.second) }
            wet.start(event, event.getPointerId(index), pen.frozen, width, height, clip, inkBrush, contentDark, prefs)
            startMirror(event, index)
        }
    }

    /** After the local wet ink has started: opens the mirrored stroke and sends the pen-down sample. */
    private fun startMirror(event: MotionEvent, index: Int) {
        val target = mirror ?: return
        val note = scene.noteId ?: return
        val sheet = scene.firstSheet ?: return
        if (inkSheet != sheet) return // the Mac does not know later pages yet: ink on them is not mirrored
        val device = event.device
        val id = target.begin(
            note, sheet, pen.brush, pen.frozen.scale, pen.frozen.tx, pen.frozen.ty,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
        )
        mirroredStrokeId = id
        mirroring = id != null
        if (!mirroring || pen.rejected) return
        mirrorSample(event, index, CURRENT)
        target.flush()
    }

    private fun mirrorSample(event: MotionEvent, index: Int, history: Int) {
        val historical = history != CURRENT
        mirror?.sample(
            if (historical) event.getHistoricalX(index, history) else event.getX(index),
            if (historical) event.getHistoricalY(index, history) else event.getY(index),
            if (historical) event.getHistoricalPressure(index, history) else event.getPressure(index),
            axisValue(event, MotionEvent.AXIS_TILT, index, history),
            axisValue(event, MotionEvent.AXIS_ORIENTATION, index, history),
            if (historical) event.getHistoricalEventTime(history) else event.eventTime,
        )
    }

    private fun cancelWet() {
        wetBridge.cancel()
        pad.cancel()
    }

    private fun clearWet() {
        wetBridge.clear()
        pad.clear()
    }

    private fun cancelMirror() {
        if (mirroring) mirror?.cancel()
        mirroring = false
    }

    private fun penUp(event: MotionEvent, index: Int) {
        if (!pen.rejected) {
            when (pen.mode) {
                PenMode.INK -> {
                    val preview = pen.inkChannels()?.let { channels ->
                        inkSheet?.let { sheet -> onStroke(sheet, channels, pen.brush)?.also(scene::handOffStroke) }
                    }
                    wet.finish(event, preview)
                }
                PenMode.ERASE -> commitErase(event.eventTime, force = true)
                PenMode.LASSO -> selection.selectLasso(pen.lassoPoints.toList())
                PenMode.SELECTION -> selection.commitGesture()
                PenMode.NONE, PenMode.IGNORED -> Unit
            }
        }
        if (mirroring) {
            if (pen.mode == PenMode.INK && !pen.rejected) mirror?.end() else mirror?.cancel()
            mirroring = false
        }
        mirroredStrokeId = null
        eraseGesture = null
        cancelWet()
        pen.finish()
        activePointer = -1
        selection.cancelGesture()
    }

    /**
     * Deletes what the eraser has swept so far, without waiting for the pen to lift: at most every
     * [ERASE_COMMIT_MS] while it moves, and once more at pen-up. Each commit is one Marks command in the
     * gesture's group. A refused commit keeps its strokes hidden and is retried on the next one.
     */
    private fun commitErase(now: Long, force: Boolean) {
        val ids = pen.uncommitted
        if (ids.isEmpty() || (!force && now - lastEraseCommit < ERASE_COMMIT_MS)) return
        val batch = ids.take(MAX_ERASE_BATCH).toSet()
        lastEraseCommit = now
        if (onMarks(MarkEdit.Delete(batch, eraseGesture))) {
            scene.handOffErase(batch)
            pen.committed(batch)
            if (force && pen.uncommitted.isNotEmpty()) commitErase(now, force = true)
        }
    }

    /** Feeds one pen sample, historical ([history] ≥ 0) or [CURRENT], oldest first. */
    private fun sample(event: MotionEvent, index: Int, history: Int) {
        if (pen.rejected) return
        val historical = history != CURRENT
        val x = if (historical) event.getHistoricalX(index, history) else event.getX(index)
        val y = if (historical) event.getHistoricalY(index, history) else event.getY(index)
        val rejection = when (pen.mode) {
            PenMode.ERASE -> pen.addErasePoint(x, y)
            PenMode.INK -> {
                pen.addInkSample(
                    x,
                    y,
                    pressure = if (historical) event.getHistoricalPressure(index, history) else event.getPressure(index),
                    time = if (historical) event.getHistoricalEventTime(history) else event.eventTime,
                    tilt = axisValue(event, MotionEvent.AXIS_TILT, index, history),
                    orientation = axisValue(event, MotionEvent.AXIS_ORIENTATION, index, history),
                ).also { if (it == null && mirroring) mirrorSample(event, index, history) }
            }
            PenMode.LASSO -> {
                pen.addLassoPoint(x, y)
                null
            }
            PenMode.SELECTION -> {
                selection.updateGesture(x, y)
                null
            }
            PenMode.NONE, PenMode.IGNORED -> null
        }
        if (rejection != null) {
            cancelMirror()
            cancelWet()
            selection.cancelGesture()
            onError(rejection)
        }
    }

    /** A named function, not a local closure, so the pen path allocates nothing per sample. */
    private fun axisValue(event: MotionEvent, axis: Int, index: Int, history: Int) =
        if (history != CURRENT) event.getHistoricalAxisValue(axis, index, history) else event.getAxisValue(axis, index)

    private fun finger(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pullAnimator?.cancel(); viewport.setPull(0f, 0f); pullArmed = false
                if (canvasMode?.hitsMinimap(event.x, event.y, width, height) == true) {
                    // The minimap: a touch (and a drag) centres the view on that spot, and is nothing else.
                    minimapDrag = true; tapCandidate = false; fingerMode = FingerMode.NONE
                    jumpFromMinimap(event.x, event.y)
                    return
                }
                tapStart = Triple(event.x, event.y, event.eventTime)
                tapCandidate = true
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                fingerMode = if (!reading && selection.beginGesture(event.x, event.y, viewport.mapping)) {
                    FingerMode.SELECTION
                } else {
                    if (!reading) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    FingerMode.PAN
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (minimapDrag) return
                removeCallbacks(longPress)
                tapCandidate = false
                // A second finger turns a selection drag back into navigation.
                if (fingerMode == FingerMode.SELECTION) selection.cancelGesture()
                fingerMode = FingerMode.PAN
                panZoom.secondPointerDown()
            }
            MotionEvent.ACTION_MOVE -> {
                if (minimapDrag) { jumpFromMinimap(event.x, event.y); return }
                velocity?.addMovement(event)
                val start = tapStart
                if (start != null && hypot(event.x - start.first, event.y - start.second) > slop) {
                    removeCallbacks(longPress)
                    tapCandidate = false
                }
                when (fingerMode) {
                    FingerMode.SELECTION -> selection.updateGesture(event.x, event.y)
                    FingerMode.PAN -> { pan(event); lastMoveTime = event.eventTime; checkPullHaptic() }
                    FingerMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                if (minimapDrag) { minimapDrag = false; return }
                removeCallbacks(longPress)
                if (fingerMode == FingerMode.SELECTION) {
                    selection.commitGesture()
                } else if (fingerMode == FingerMode.PAN) {
                    if (panZoom.pinched) {
                        val (fx, fy) = panZoom.focus ?: (width / 2f to height / 2f)
                        viewport.setPull(0f, 0f)
                        if (canvasMode == null) viewport.snapAfterPinch(fx, fy)
                    } else if (!tapCandidate) {
                        velocity?.let { v ->
                            v.addMovement(event)
                            v.computeCurrentVelocity(1000)
                            if (!liftPull(v.xVelocity, event.eventTime)) fling(v.xVelocity, v.yVelocity)
                        }
                    }
                }
                val start = tapStart
                if (tapCandidate && !reading && start != null && event.eventTime - start.third < ViewConfiguration.getTapTimeout() * 3L) {
                    viewport.mapping?.let { mapping ->
                        val (cx, cy) = mapping.toSheet(event.x, event.y)
                        val x = cx - textOffset.first; val y = cy - textOffset.second
                        val textTools = typing || (!lasso && !eraser)
                        val onStroke = strokeAt(event.x, event.y) != null
                        val action = TapRouter.route(y, TapContext(
                            palm = false, // fingers that land in the palm window never reach here
                            selection = selectionAtDown, chrome = chromeAtDown,
                            task = if (textTools) text.task(x, y) else null,
                            content = if (textTools && !onStroke) text.hit(x, y) else null,
                            onStroke = onStroke, typingAllowed = textTools, lines = text.lines()))
                        when (action) {
                            TapAction.Ignored -> Unit
                            is TapAction.Task -> onTask(action.block, action.line)
                            is TapAction.Content -> { lastTextTap = event.x to event.y; lastWriteY = event.y; onContent(action.target) }
                            is TapAction.Type -> if (canvasMode != null) {
                                lastTextTap = event.x to event.y; lastWriteY = event.y
                                newFreeText(x, y)
                            } else {
                                pendingFreeTap = freePlaceAt(event.x, event.y)
                                lastTextTap = event.x to event.y
                                val wait = typingWaitMs(doubleTapFit, typing, ViewConfiguration.getDoubleTapTimeout().toLong())
                                if (fitTookTap) Unit
                                else if (wait == 0L) { pendingWish = action.wish; startTypingPending() }
                                else { pendingWish = action.wish; postDelayed(startTypingNow, wait) }
                            }
                            TapAction.Dismiss, TapAction.Selection -> selection.tap(event.x, event.y, mapping)
                        }
                    }
                }
                fingerMode = FingerMode.NONE
                tapCandidate = false
                velocity?.recycle()
                velocity = null
            }
        }
    }

    /** Centres the view on the canvas point a touch on the minimap means. */
    private fun jumpFromMinimap(x: Float, y: Float) {
        val mode = canvasMode ?: return
        val m = viewport.mapping ?: return
        val (cx, cy) = mode.minimapTarget(x, y, m, width, height)
        scroller.forceFinished(true)
        viewport.setMapping(mode.jump(m, width, height, cx, cy))
        invalidate()
    }

    /** A new free text block whose editor opens at ([x], [y]) in canvas units. */
    private fun newFreeText(x: Float, y: Float) {
        if (textInput.active) return
        val scale = viewport.mapping?.scale ?: return
        // As wide as 80% of the screen at this zoom, within what a text card is comfortable at.
        val width = (this.width * 0.8f / scale).coerceIn(FREE_MIN_WIDTH, FREE_MAX_WIDTH)
        val sheet = textSnapshot?.sheets?.firstOrNull() ?: return
        // The block is made with its first words, in the one sheet of a canvas, under the ID chosen here.
        freePlace = FreePlace(sheet.id, x, y - NoteTextSceneBuilder.FREE_TEXT_DROP, width, dev.syncr.core.newId())
        caretWish = null
        onContent(ContentTarget("", false))
    }

    private fun pan(event: MotionEvent) {
        val xs = FloatArray(event.pointerCount) { event.getX(it) }
        val ys = FloatArray(event.pointerCount) { event.getY(it) }
        panZoom.move(xs, ys, withinTapSlop = tapCandidate, viewport)
    }

    private fun fling(vx: Float, vy: Float) {
        val bounds = viewport.flingBounds() ?: return
        scroller.fling(bounds.startX, bounds.startY, vx.toInt(), vy.toInt(), bounds.minX, bounds.maxX, bounds.minY, bounds.maxY)
        postInvalidateOnAnimation()
    }

    /** Feeds the tap classifier only; never changes stroke capture or the frozen mapping. */
    private fun trackTaps(event: MotionEvent) {
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> taps.down(
                event.getPointerId(i),
                event.getX(i),
                event.getY(i),
                event.eventTime,
                finger = event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER && activePointer == -1,
                first = event.actionMasked == MotionEvent.ACTION_DOWN,
            )
            MotionEvent.ACTION_MOVE -> {
                if (activePointer != -1) taps.penContact()
                for (p in 0 until event.pointerCount) taps.move(event.getPointerId(p), event.getX(p), event.getY(p), event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> taps.up(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            MotionEvent.ACTION_UP -> {
                val tap = taps.up(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime) ?: return
                if (activePointer != -1) return
                if (tap.command == TapCommand.FIT) {
                    if (!doubleTapFit) return
                    textMapping()?.toSheet(tap.x, tap.y)?.let { (x, y) -> if (text.hit(x, y) != null) return }
                    if (strokeAt(tap.x, tap.y) != null) return // design §8.4: double-tap acts on empty paper only
                    if (viewport.mapping == null) return
                    if (!viewport.zoomInFromFit(tap.x, tap.y)) fit()
                    fitTookTap = true; removeCallbacks(startTypingNow); pendingWish = null
                }
                if (!reading) onTap(tap.command)
            }
        }
    }

    /** The tap's wait is over (or there was none): open the field with the caret where the wish says. */
    private fun startTypingPending() {
        val wish = pendingWish ?: return
        pendingWish = null
        if (textInput.active) return
        freePlace = pendingFreeTap; pendingFreeTap = null
        caretWish = if (freePlace != null) null else wish
        onContent(ContentTarget("", false))
    }

    private fun onLongPress() {
        val start = tapStart ?: return
        if (fingerMode != FingerMode.PAN || !tapCandidate || activePointer != -1) return
        tapCandidate = false
        val id = strokeAt(start.first, start.second) ?: return
        val m = viewport.mapping ?: return
        if (!selection.selectStroke(id, m)) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Keep holding and drag: the long-pressed stroke moves.
        if (selection.beginGesture(start.first, start.second, m)) fingerMode = FingerMode.SELECTION
        invalidate()
    }

    /** Cancel, outside, or an up the system flags as cancelled (e.g. an accidental palm touch). */
    // FLAG_CANCELED is API 33; the constant is inlined and older platforms never set the bit.
    @SuppressLint("InlinedApi")
    private fun isCancelled(event: MotionEvent): Boolean {
        val action = event.actionMasked
        return action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE ||
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
    }

    private fun isOnCanvas(x: Float, y: Float) = scene.firstSheet != null && viewport.contains(x, y)

    /** The page under a screen point, or null (a legacy column, a canvas, or the desk). */
    private fun pageAt(x: Float, y: Float): PageSpot? {
        if (spots.isEmpty()) return null
        val (cx, cy) = viewport.mapping?.toSheet(x, y) ?: return null
        return spots.firstOrNull { it.contains(cx, cy) }
    }

    /** Topmost visible stroke near a screen point. */
    private fun strokeAt(x: Float, y: Float): String? {
        if (!isOnCanvas(x, y)) return null
        val m = viewport.mapping ?: return null
        val (cx, cy) = m.toSheet(x, y)
        return scene.strokeAt(cx, cy, radius = 20f * density / m.scale)
    }

    private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        /** A4 portrait in note units: what a page added under a legacy column is. */
        val A4: Pair<Float, Float> by lazy {
            try { dev.syncr.core.pageSizes().first { it.name == "A4" }.let { it.width to it.height } } catch (_: Throwable) { 1600f to 2262.9f }
        }
        const val PALM_MS = 300L
        /** A moved canvas view is kept on the device this long after it stops changing. */
        private const val SAVE_VIEW_MS = 600L
        /** A new free text block's width in canvas units, at the narrowest and the widest. */
        private const val FREE_MIN_WIDTH = 400f
        private const val FREE_MAX_WIDTH = 1200f
        private const val LEGIBLE_SP = 15f
        private const val GUTTER_DP = 16f

        /** [sample]'s history index for the event's current position. */
        private const val CURRENT = -1

        /** How often a moving eraser commits what it has swept. */
        private const val ERASE_COMMIT_MS = 40L

        /** Strokes per eraser command; the core's batch limit is 1,024. */
        private const val MAX_ERASE_BATCH = 1024

        /** Hover ring for the whole-stroke eraser, in canvas units. */
        private const val ERASER_HOVER_UNITS = 24f
    }
}
