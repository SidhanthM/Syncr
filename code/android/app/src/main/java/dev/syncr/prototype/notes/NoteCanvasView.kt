package dev.syncr.prototype.notes

import android.content.Context
import android.graphics.Bitmap
import android.view.PointerIcon
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.syncr.core.*
import dev.syncr.prototype.ui.theme.DustyRose
import dev.syncr.prototype.ui.theme.PaperPalette
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import androidx.compose.ui.graphics.toArgb

/**
 * One continuous canvas. AndroidX Ink strokes are disposable caches; canonical samples and sheet
 * origins come from Rust. Fingers navigate; the pen draws, erases or lassos. New ink freezes the
 * canvas mapping and always targets the first sheet. Existing ink keeps its sheet-local samples.
 *
 * Wet ink (design §4.1) is drawn by Ink's [InProgressStrokesView] overlaid on this view, on the
 * front-buffer path, from the same pen MotionEvents and with the same brush as dry ink, so a stroke
 * keeps its shape when the pen lifts. That Ink stroke is only a rendering cache: the canonical samples
 * still come from [MeasuredGesture] and go out through [onStroke].
 */
class NoteCanvasView(context: Context) : FrameLayout(context) {
    var onStroke: (sheetId: String, SampleChannels, Brush) -> InkPreview? = { _, _, _ -> null }
    var onMarks: (MarkEdit) -> Boolean = { false }
    var onError: (String) -> Unit = {}
    /** Finger-tap commands (undo/redo). FIT is handled here and also reported. */
    var onTap: (TapCommand) -> Unit = {}
    /** Any new touch on the paper, e.g. to collapse floating tool chrome. */
    var onTouchStart: () -> Unit = {}
    /** Transient viewport retained by the ViewModel across Activity recreation, never note data. */
    var restoredViewport: Pair<String, SheetMapping>? = null
    var onViewportChanged: (String, SheetMapping) -> Unit = { _, _ -> }
    /** Number of selected strokes; 0 when nothing is selected. */
    var onSelection: (Int) -> Unit = {}
    var brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
    var eraser = false
    var lasso = false

    private val density = context.resources.displayMetrics.density
    private var scene = NotesState()
    private var noteId: String? = null
    private var dark = false
    private var layout = CanvasLayout()
    private var sheets = emptyMap<String, SheetView>()
    private var firstSheet: String? = null
    private var strokeById = emptyMap<String, StrokeView>()
    private var pendingTransforms = emptyMap<String, Affine>()
    private var pendingRestyles = emptyMap<String, Brush>()
    private var view: SheetMapping? = null

    // Pen gesture
    private enum class PenMode { NONE, IGNORED, INK, ERASE, LASSO, SELECTION }
    private var penMode = PenMode.NONE
    private var frozen = SheetMapping(1f, 0f, 0f)
    private var activePointer = -1
    private var lastPenUp = Long.MIN_VALUE / 2
    private var palmBlocked = false
    // Settings → Pen & gestures (ui/AppPreferences.kt), pushed in by NoteScreen.
    var sideButtonErases = true
    var doubleTapFit = true
    var palmMs = PALM_MS
    private var gesture: MeasuredGesture? = null
    private var gestureBrush = brush
    private var eraseLast: Pair<Float, Float>? = null
    private val erased = linkedSetOf<String>()
    private val lassoPoints = mutableListOf<Pt>()
    private var gestureRejected = false
    private var requireLift = false

    // Finger gesture
    private enum class FingerMode { NONE, PAN, SELECTION }
    private var fingerMode = FingerMode.NONE
    private var navFocus: Pair<Float, Float>? = null
    private var navSpan = 0f
    private var pinched = false
    private var tapStart: Triple<Float, Float, Long>? = null
    private var tapCandidate = false
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPress = Runnable { onLongPress() }

    // Selection outlines and gestures are in canvas space, including across legacy sheets.
    private class Selection(val ids: Set<String>, val outline: List<Pt>)
    private var selection: Selection? = null
    private var selGesture: Affine? = null
    private var selResize = false
    private var selFrozen = SheetMapping(1f, 0f, 0f)
    private var selStart: Pt = 0f to 0f
    private var selAnchor: Pt = 0f to 0f
    private var selHandleStart: Pt = 0f to 0f
    private var selMoved = false
    private var selStartScreen: Pt = 0f to 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 1.8f * density
        pathEffect = DashPathEffect(floatArrayOf(0.01f, 5f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = DustyRose.toArgb()
    private val renderer by lazy { CanvasStrokeRenderer.create() }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cacheJob: Job? = null
    private var prefsJob: Job? = null
    private var prefs = InkPreferencesStore.state(context).value
    /** A dry-ink rendering cache entry, valid for one preview under one theme and one set of preferences. */
    private class CachedInk(val preview: InkPreview, val dark: Boolean, val prefs: InkPreferences, val stroke: Stroke)
    private var cache = emptyMap<String, CachedInk>()
    /** Ink handed over by the wet layer, or built in-frame, until the next cache build includes it. */
    private val extraInk = mutableMapOf<String, CachedInk>()
    private var syncBudgetEnd = 0L
    private var previews = emptyList<InkPreview>()
    // Bridge the input callback → Compose state handoff without a deliberate blank frame.
    private val handoffs = mutableListOf<InkPreview>()
    private var eraseHandoff = emptySet<String>()
    private var transformHandoff = emptyMap<String, Affine>()
    private var restyleHandoff = emptyMap<String, Brush>()
    private val taps = ViewConfiguration.get(context).let {
        FingerTapClassifier(it.scaledTouchSlop.toFloat(), it.scaledDoubleTapSlop.toFloat(),
            doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    // Wet ink: Ink's live view on the front-buffer path. Finished strokes stay there until handed off.
    private class Live(val id: InProgressStrokeId, val dark: Boolean, val prefs: InkPreferences, var preview: InkPreview? = null)
    private val wet = InProgressStrokesView(context)
    private var wetStroke: Live? = null
    private val wetFinished = mutableMapOf<InProgressStrokeId, Live>()
    private val wetListener = object : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
    }

    init {
        setWillNotDraw(false)
        addView(wet, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(wetListener)
        contentDescription = "Note canvas. Stylus draws; one finger scrolls, pinch zooms. Two-finger tap undoes, three-finger tap redoes, " +
            "double-tap fits a phone column, long-press selects a stroke."
        isFocusable = true
    }

    fun update(state: NotesState, night: Boolean) {
        val snapshot = state.snapshot
        val newNote = snapshot?.noteId != noteId
        if (newNote) {
            cancelGesture(); clearWet(); handoffs.clear(); eraseHandoff = emptySet(); transformHandoff = emptyMap(); restyleHandoff = emptyMap()
            extraInk.clear(); clearSelection(); noteId = snapshot?.noteId
            view = restoredViewport?.takeIf { it.first == noteId }?.second?.copy(tx = 0f)
        }
        val themeChanged = dark != night
        dark = night; scene = state
        sheets = snapshot?.sheets.orEmpty().associateBy { it.id }
        firstSheet = snapshot?.sheets?.firstOrNull()?.id
        strokeById = snapshot?.strokes.orEmpty().associateBy { it.id }
        pendingTransforms = state.pending.flatMap { it.transforms.entries }.associate { it.key to it.value }
        pendingRestyles = state.pending.flatMap { it.restyles.entries }.associate { it.key to it.value }
        val wanted = buildList {
            snapshot?.strokes?.filter { !it.tombstone }?.forEach { stroke ->
                state.samples[stroke.id]?.let { add(InkPreview(stroke.id, stroke.sheetId, it, pendingRestyles[stroke.id] ?: stroke.brush)) }
            }
            state.pending.mapNotNull { it.preview }.forEach { preview -> if (none { it.id == preview.id }) add(preview) }
        }
        handoffs.removeAll { pending -> wanted.any { it.id == pending.id } }
        val representedDeletes = state.pending.flatMap { it.hidden }.toSet() + snapshot?.strokes.orEmpty().filter { it.tombstone }.map { it.id }
        eraseHandoff = eraseHandoff - representedDeletes
        transformHandoff = transformHandoff.filter { (id, affine) -> transformOf(id, includeHandoff = false) != affine }
        restyleHandoff = restyleHandoff.filter { (id, b) -> (pendingRestyles[id] ?: strokeById[id]?.brush) != b }
        selection?.let { s -> if (s.ids.any { strokeById[it]?.tombstone != false }) clearSelection() }
        val hidden = state.pending.flatMap { it.hidden }.toSet() + eraseHandoff
        layout = CanvasLayout(maxOf(0f, (wanted + handoffs).filterNot { it.id in hidden }.maxOfOrNull { preview ->
            val affine = canvasTransformOf(preview.id, preview.sheetId)
            val radiusY = brushWidth(preview.brush) / 2 * hypot(affine.b, affine.d).toFloat()
            preview.channels.x.indices.maxOfOrNull { i ->
                transformedPoint(preview.channels.x[i], preview.channels.y[i], affine).second + radiusY
            } ?: 0f
        } ?: 0f))
        if (width > 0 && height > 0) view = view?.let(::clamp) ?: Viewport.defaultView(layout, width, height)
        if (wanted != previews || themeChanged) {
            previews = wanted
            rebuildCache()
        }
        invalidate()
    }

    /** Rebuilds dry ink off the UI thread. The previous strokes keep drawing until the new set is ready. */
    private fun rebuildCache() {
        val wanted = previews; val night = dark; val style = prefs
        val reusable = cache.values + extraInk.values
        cacheJob?.cancel()
        cacheJob = scope.launch {
            val built = withContext(Dispatchers.Default) {
                val old = reusable.filter { it.dark == night && it.prefs == style }.associateBy { it.preview }
                wanted.associate { preview ->
                    ensureActive()
                    preview.id to (old[preview] ?: makeInk(preview, night, style))
                }
            }
            cache = built
            extraInk.keys.retainAll { id -> id !in built && handoffs.any { it.id == id } }
            invalidate()
        }
    }

    private fun transformOf(id: String, includeHandoff: Boolean = true): Affine =
        (if (includeHandoff) transformHandoff[id] else null) ?: pendingTransforms[id] ?: strokeById[id]?.transform ?: IDENTITY

    private fun canvasTransformOf(id: String, sheetId: String): Affine =
        sheets[sheetId]?.let { canvasTransform(it, transformOf(id)) } ?: transformOf(id)

    private fun makeInk(preview: InkPreview, night: Boolean, style: InkPreferences) =
        CachedInk(preview, night, style, InkBrushes.stroke(preview.channels, InkBrushes.brush(preview.brush, night, style)))

    /** Dry ink for a preview: the cache, then wet-layer handoffs; else built now within a small per-frame budget. */
    private fun inkFor(preview: InkPreview): Stroke? {
        cache[preview.id]?.takeIf { it.preview == preview }?.let { return it.stroke }
        extraInk[preview.id]?.takeIf { it.preview == preview }?.let { return it.stroke }
        if (System.nanoTime() > syncBudgetEnd) return null
        return makeInk(preview, dark, prefs).also { extraInk[preview.id] = it }.stroke
    }

    private fun applyPreferences(next: InkPreferences) {
        if (next == prefs) return
        prefs = next; rebuildCache(); invalidate()
    }

    // ---- Wet ink ----

    private fun wetAvailable() = wet.isAttachedToWindow

    private fun startWet(event: MotionEvent, index: Int) {
        if (!wetAvailable()) return
        val pw = CanvasLayout.WIDTH * frozen.scale
        // Only the canvas's horizontal edges and top clip ink; there is no bottom page edge.
        wet.maskPath = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            op(Path().apply { addRect(frozen.tx, frozen.ty, frozen.tx + pw, height.toFloat(), Path.Direction.CW) }, Path.Op.DIFFERENCE)
        }
        // New stroke units are canvas units, exactly as MeasuredGesture stores them.
        val toCanvas = Matrix().apply { setTranslate(-frozen.tx, -frozen.ty); postScale(1f / frozen.scale, 1f / frozen.scale) }
        val id = wet.startStroke(event, index, InkBrushes.brush(gestureBrush, dark, prefs), toCanvas, Matrix())
        wetStroke = Live(id, dark, prefs)
    }

    private fun cancelWet() {
        // Once the overlay has left the window there is nothing on screen to cancel.
        wetStroke?.let { if (wetAvailable()) wet.cancelStroke(it.id, null) }
        wetStroke = null
    }

    /** Pen-up: the wet stroke stays on screen until Ink hands it over; dry ink skips it meanwhile. */
    private fun finishWet(event: MotionEvent, index: Int, preview: InkPreview?) {
        val live = wetStroke ?: return
        wetStroke = null
        if (preview == null) { wet.cancelStroke(live.id, event); return }
        live.preview = preview
        wetFinished[live.id] = live
        wet.finishStroke(event, index, live.id)
    }

    /** The finished Ink stroke becomes this preview's dry ink in the same frame it leaves the wet layer. */
    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        strokes.forEach { (id, stroke) ->
            val live = wetFinished.remove(id) ?: return@forEach
            val preview = live.preview ?: return@forEach
            if (cache[preview.id]?.preview != preview && (previews.any { it.id == preview.id } || handoffs.any { it.id == preview.id }))
                extraInk[preview.id] = CachedInk(preview, live.dark, live.prefs, stroke)
        }
        invalidate()
        wet.removeFinishedStrokes(strokes.keys)
    }

    private fun clearWet() {
        cancelWet(); wetFinished.clear()
        if (wetAvailable()) { wet.cancelUnfinishedStrokes(); wet.removeFinishedStrokes(wet.getFinishedStrokes().keys) }
    }

    // ---- Viewport ----

    private fun clamp(m: SheetMapping) = Viewport.clamp(m, layout, width, height)
    private fun fitScale() = Viewport.fitScale(width, height)
    fun fit() {
        if (activePointer != -1 || width == 0) return
        scroller.forceFinished(true)
        view = Viewport.fitColumn(layout, view, width, height); invalidate()
    }
    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val m = view ?: return
        view = clamp(m.navigate(0f, 0f, target / m.scale, fx, fy)); invalidate()
    }
    private fun navigate(dx: Float, dy: Float, factor: Float, fx: Float, fy: Float) {
        val m = view ?: return
        val next = (m.scale * factor).coerceIn(Viewport.scaleRange(fitScale()))
        view = clamp(m.navigate(dx, dy, next / m.scale, fx, fy))
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            view?.let { view = clamp(it.copy(tx = scroller.currX.toFloat(), ty = scroller.currY.toFloat())) }
            postInvalidateOnAnimation()
        }
    }
    private fun fling(vx: Float, vy: Float) {
        val m = view ?: return
        val minX = minOf(0f, width - CanvasLayout.WIDTH * m.scale)
        val minY = -layout.lowestInk * m.scale
        scroller.fling(m.tx.toInt(), m.ty.toInt(), vx.toInt(), vy.toInt(), minX.toInt(), 0, minY.toInt(), 0)
        postInvalidateOnAnimation()
    }
    private fun onCanvas(x: Float, y: Float): Boolean {
        val m = view ?: return false
        val (dx, dy) = m.toSheet(x, y)
        return firstSheet != null && layout.contains(dx, dy)
    }
    /** Test hook: canvas units to view pixels. */
    internal fun canvasMapping(): SheetMapping? = view

    // ---- Lifecycle ----

    fun cancelGesture() {
        cancelWet(); gesture?.cancel(); gesture = null; activePointer = -1; erased.clear(); eraseLast = null; lassoPoints.clear()
        penMode = PenMode.NONE; selGesture = null; fingerMode = FingerMode.NONE; removeCallbacks(longPress)
        navFocus = null; palmBlocked = true; taps.cancel(); scroller.forceFinished(true); tapCandidate = false
        velocity?.recycle(); velocity = null; invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelGesture(); view = Viewport.resized(layout, view?.takeIf { oldw > 0 }, w, h)
    }
    override fun onDetachedFromWindow() { cancelGesture(); clearWet(); cacheJob?.cancel(); scope.cancel(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) { scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); previews = emptyList() }
        prefsJob = scope.launch { InkPreferencesStore.state(context).collect(::applyPreferences) }
        post { if (wetAvailable()) wet.eagerInit() } // children attach after this callback
    }
    // Every touch and hover is ours; the wet-ink overlay only draws.
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onInterceptHoverEvent(event: MotionEvent) = true

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(PaperPalette.desk(dark))
        val m = view ?: return
        noteId?.let { onViewportChanged(it, m) }
        syncBudgetEnd = System.nanoTime() + SYNC_BUDGET_NS
        val wetIds = wetFinished.values.mapNotNullTo(HashSet()) { it.preview?.id }
        val hidden = scene.pending.flatMap { it.hidden }.toSet() + erased + eraseHandoff
        val rect = RectF(m.tx, m.ty, m.tx + CanvasLayout.WIDTH * m.scale, height.toFloat())
        pagePaint.color = PaperPalette.paper(dark)
        canvas.drawRect(rect, pagePaint)
        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(m.tx, m.ty); canvas.scale(m.scale, m.scale)
        val visible = previews + handoffs.filter { p -> previews.none { it.id == p.id } }
        val selected = selection?.ids.orEmpty()
        visible.forEach { preview ->
            if (preview.id in hidden || preview.id in wetIds) return@forEach
            var affine = canvasTransformOf(preview.id, preview.sheetId)
            if (preview.id in selected) selGesture?.let { affine = compose(it, affine) }
            drawStroke(canvas, preview, affine, m.scale)
        }
        if (penMode == PenMode.INK && wetStroke == null && !wetAvailable()) gesture?.channels()?.let {
            renderer.draw(canvas, InkBrushes.stroke(it, InkBrushes.brush(gestureBrush, dark, prefs)), Matrix().apply { setScale(m.scale, m.scale) })
        }
        canvas.restoreToCount(save)
        drawLassoAndSelection(canvas, m)
    }
    private fun drawStroke(canvas: Canvas, preview: InkPreview, affine: Affine, scale: Float) {
        val local = Matrix().apply { setValues(floatArrayOf(affine.a.toFloat(), affine.c.toFloat(), affine.tx.toFloat(),
            affine.b.toFloat(), affine.d.toFloat(), affine.ty.toFloat(), 0f, 0f, 1f)) }
        val save = canvas.save(); canvas.concat(local)
        val stroke = inkFor(preview)
        if (stroke != null) renderer.draw(canvas, stroke, Matrix(local).apply { postScale(scale, scale) })
        else drawMeasured(canvas, preview.channels, preview.brush)
        canvas.restoreToCount(save)
    }
    /** Cold-load placeholder only, while a large note's dry ink is still being built. */
    private fun drawMeasured(canvas: Canvas, samples: SampleChannels, brush: Brush) {
        val path = Path()
        samples.x.indices.forEach { if (it == 0) path.moveTo(samples.x[it], samples.y[it]) else path.lineTo(samples.x[it], samples.y[it]) }
        paint.color = inkColour(brush, dark); paint.strokeWidth = brushWidth(brush)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        if (samples.x.size == 1) canvas.drawPoint(samples.x[0], samples.y[0], paint) else canvas.drawPath(path, paint)
    }
    private fun drawLassoAndSelection(canvas: Canvas, m: SheetMapping) {
        dottedPaint.color = accent
        if (penMode == PenMode.LASSO && lassoPoints.size > 1) canvas.drawPath(screenPath(lassoPoints, frozen, close = false), dottedPaint)
        val s = selection ?: return
        val pm = if (selGesture != null) selFrozen else m
        val outline = selGesture?.let { transformPolygon(s.outline, it) } ?: s.outline
        canvas.drawPath(screenPath(outline, pm, close = true), dottedPaint)
        val bounds = Bounds.of(outline) ?: return
        handlePaint.color = accent
        bounds.corners().forEach { (x, y) ->
            val (sx, sy) = pm.toScreen(x, y)
            canvas.drawCircle(sx, sy, 6f * density, handlePaint)
        }
    }
    private fun screenPath(points: List<Pt>, mapping: SheetMapping, close: Boolean) = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val (sx, sy) = mapping.toScreen(x, y)
            if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
        }
        if (close) close()
    }

    // ---- Input ----

    // Pen hover is drawn by the system pointer, composited by the OS, never redrawn by this view. We only swap its
    // picture for a brush-sized ring (Android 14+ honours this for styluses; if One UI ignores it, its own dot stays).
    override fun onHoverEvent(event: MotionEvent): Boolean = isPen(event.getToolType(0)) || super.onHoverEvent(event)

    private var hoverIcon: Pair<HoverIconKey, PointerIcon>? = null
    private data class HoverIconKey(val diameterPx: Int, val colour: Int, val dashed: Boolean)

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (!isPen(event.getToolType(pointerIndex))) return super.onResolvePointerIcon(event, pointerIndex)
        val scale = view?.scale ?: return super.onResolvePointerIcon(event, pointerIndex)
        val units = when { eraser -> 24f; lasso -> 0f; else -> brushWidth(brush) }
        val key = HoverIconKey((units * scale).coerceIn(6f * density, 96f * density).roundToInt(),
            if (lasso) accent else PaperPalette.quiet(dark), dashed = lasso)
        hoverIcon?.let { (cached, icon) -> if (cached == key) return icon }
        val stroke = 1.5f * density
        val size = key.diameterPx + (2 * stroke).roundToInt() + 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, key.diameterPx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = key.colour
            if (key.dashed) pathEffect = DashPathEffect(floatArrayOf(2 * density, 2 * density), 0f)
        })
        return PointerIcon.create(bitmap, size / 2f, size / 2f).also { hoverIcon = key to it }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (scene.closing) { cancelGesture(); return true }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { onTouchStart(); scroller.forceFinished(true) }
        if (requireLift) {
            taps.cancel()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                requireLift = false; cancelGesture(); palmBlocked = false
            }
            return true
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        val actionIndex = event.actionIndex
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            navFocus = null; pinched = false; fingerMode = FingerMode.NONE
            // Palm rule v1: a finger landing within palmMs of pen-up is a resting palm.
            palmBlocked = !isPen(event.getToolType(0)) && event.eventTime - lastPenUp < palmMs
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
            (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
            cancelGesture(); palmBlocked = event.actionMasked != MotionEvent.ACTION_CANCEL; return true
        }
        if (!palmBlocked) trackTaps(event) else taps.cancel()
        val down = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        val up = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP
        if (down && isPen(event.getToolType(actionIndex))) {
            if (activePointer != -1) { cancelGesture(); gestureRejected = true; return true }
            // Pen contact wins: abandon any finger gesture (its temporary selection move is reverted).
            removeCallbacks(longPress); if (fingerMode == FingerMode.SELECTION) selGesture = null
            fingerMode = FingerMode.NONE; tapCandidate = false
            activePointer = event.getPointerId(actionIndex); palmBlocked = true; navFocus = null
            requestUnbufferedDispatch(event)
            penDown(event, actionIndex)
        } else if (activePointer != -1) {
            val index = event.findPointerIndex(activePointer)
            if (index < 0) { cancelGesture(); return true }
            if (event.actionMasked == MotionEvent.ACTION_MOVE || (up && event.getPointerId(actionIndex) == activePointer)) {
                for (h in 0 until event.historySize) sample(event, index, true, h)
                sample(event, index, false)
                if (event.actionMasked == MotionEvent.ACTION_MOVE) wetStroke?.let { wet.addToStroke(event, index, it.id) }
            }
            if (up && event.getPointerId(actionIndex) == activePointer) {
                penUp(event, index); lastPenUp = event.eventTime
            }
        } else if (!palmBlocked && (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }) {
            finger(event)
        }
        if (down || up) navFocus = null
        if (event.actionMasked == MotionEvent.ACTION_UP) { palmBlocked = false; navFocus = null; performClick() }
        invalidate(); return true
    }

    private fun penDown(event: MotionEvent, index: Int) {
        val x = event.getX(index); val y = event.getY(index)
        val tool = event.getToolType(index)
        // S Pen side button held at pen-down, or the eraser end, erases whole strokes.
        val erase = eraser || tool == MotionEvent.TOOL_TYPE_ERASER || (sideButtonErases && (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        gestureRejected = false; erased.clear(); eraseLast = null; lassoPoints.clear(); gestureBrush = brush
        if (lasso && !erase && beginSelectionGesture(x, y)) { penMode = PenMode.SELECTION; return }
        if (!onCanvas(x, y)) { penMode = PenMode.IGNORED; return }
        if (!lasso) clearSelection()
        frozen = view!!
        penMode = when { erase -> PenMode.ERASE; lasso -> PenMode.LASSO; else -> PenMode.INK }
        if (penMode == PenMode.ERASE && scene.unsaved) { gestureRejected = true; onError("Resolve unsaved ink before erasing.") }
        val device = event.device
        gesture = if (penMode == PenMode.INK) MeasuredGesture(frozen,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null) else null
        sample(event, index, false)
        if (penMode == PenMode.INK && !gestureRejected) startWet(event, index)
    }

    private fun penUp(event: MotionEvent, index: Int) {
        if (!gestureRejected) when (penMode) {
            PenMode.INK -> {
                val preview = gesture?.channels()?.let { channels ->
                    firstSheet?.let { sheet -> onStroke(sheet, channels, gestureBrush)?.also { handoffs += it } }
                }
                finishWet(event, index, preview)
            }
            PenMode.ERASE -> if (erased.isNotEmpty() && onMarks(MarkEdit.Delete(erased.toSet()))) eraseHandoff = eraseHandoff + erased
            PenMode.LASSO -> finishLasso()
            PenMode.SELECTION -> commitSelectionGesture()
            else -> Unit
        }
        cancelWet(); gesture = null; activePointer = -1; erased.clear(); eraseLast = null; lassoPoints.clear(); penMode = PenMode.NONE
        selGesture = null
    }

    private fun sample(event: MotionEvent, index: Int, historical: Boolean, h: Int = 0) {
        if (gestureRejected) return
        val x = if (historical) event.getHistoricalX(index, h) else event.getX(index)
        val y = if (historical) event.getHistoricalY(index, h) else event.getY(index)
        when (penMode) {
            PenMode.ERASE -> {
                val point = frozen.toSheet(x, y)
                val previous = eraseLast ?: point
                scene.snapshot?.strokes?.filter { !it.tombstone && it.id !in erased }?.forEach { stroke ->
                    scene.samples[stroke.id]?.let { samples ->
                        if (hitsSweep(previous, point, 12f + brushWidth(stroke.brush) / 2, samples, canvasTransformOf(stroke.id, stroke.sheetId))) erased += stroke.id
                    }
                }
                eraseLast = point
                if (erased.size > 1024) reject("Eraser limit reached. Entire gesture cancelled; lift the pen.")
            }
            PenMode.INK -> {
                val p = if (historical) event.getHistoricalPressure(index, h) else event.getPressure(index)
                val time = if (historical) event.getHistoricalEventTime(h) else event.eventTime
                fun axis(axis: Int) = if (historical) event.getHistoricalAxisValue(axis, index, h) else event.getAxisValue(axis, index)
                if (gesture?.add(x, y, p, time, axis(MotionEvent.AXIS_TILT), axis(MotionEvent.AXIS_ORIENTATION)) == false)
                    reject("Sample limit or invalid input. Entire stroke cancelled; lift the pen.")
            }
            PenMode.LASSO -> {
                val point = frozen.toSheet(x, y)
                val last = lassoPoints.lastOrNull()
                if (last == null || hypot(point.first - last.first, point.second - last.second) * frozen.scale >= density) lassoPoints += point
            }
            PenMode.SELECTION -> updateSelectionGesture(x, y)
            else -> Unit
        }
    }
    private fun reject(message: String) {
        gestureRejected = true; requireLift = true; cancelWet(); gesture?.cancel(); erased.clear(); lassoPoints.clear(); selGesture = null; onError(message)
    }

    private fun finger(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x; val y = event.y
                tapStart = Triple(x, y, event.eventTime); tapCandidate = true
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                fingerMode = if (beginSelectionGesture(x, y)) FingerMode.SELECTION else {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); FingerMode.PAN
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress); tapCandidate = false
                if (fingerMode == FingerMode.SELECTION) selGesture = null // second finger: back to navigation
                fingerMode = FingerMode.PAN; pinched = true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val start = tapStart
                if (start != null && hypot(event.x - start.first, event.y - start.second) > slop) { removeCallbacks(longPress); tapCandidate = false }
                if (fingerMode == FingerMode.SELECTION) updateSelectionGesture(event.x, event.y)
                else if (fingerMode == FingerMode.PAN) pan(event)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (fingerMode == FingerMode.SELECTION) commitSelectionGesture()
                else if (fingerMode == FingerMode.PAN) {
                    if (pinched) snapAfterPinch(navFocus ?: (width / 2f to height / 2f))
                    else if (!tapCandidate) velocity?.let { v ->
                        v.addMovement(event); v.computeCurrentVelocity(1000); fling(v.xVelocity, v.yVelocity)
                    }
                }
                val start = tapStart
                if (tapCandidate && start != null && event.eventTime - start.third < ViewConfiguration.getTapTimeout() * 3L)
                    singleTap(event.x, event.y)
                fingerMode = FingerMode.NONE; tapCandidate = false; velocity?.recycle(); velocity = null
            }
        }
    }
    /** Pinch end: snap to fit-width or 100% when close to either. */
    private fun snapAfterPinch(focus: Pair<Float, Float>) {
        val m = view ?: return
        val target = Viewport.snapScale(m.scale, fitScale())
        if (target != m.scale) zoomTo(target, focus.first, focus.second)
    }
    private fun pan(event: MotionEvent) {
        val x = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
        val y = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
        val span = if (event.pointerCount >= 2) hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)) else 0f
        if (event.pointerCount == 1 && tapCandidate) { navFocus = x to y; navSpan = span; return } // within slop: not a pan yet
        navFocus?.let { previous ->
            val factor = if (span > 0 && navSpan > 0) span / navSpan else 1f
            navigate(x - previous.first, y - previous.second, factor, previous.first, previous.second)
        }
        navFocus = x to y; navSpan = span
    }
    private fun singleTap(x: Float, y: Float) {
        val s = selection ?: return
        val (px, py) = (view ?: return).toSheet(x, y)
        if (Bounds.of(s.outline)?.contains(px, py) != true) clearSelection() // tap on empty paper clears
    }

    /** Feeds the tap classifier only; never changes stroke capture or the frozen mapping. */
    private fun trackTaps(event: MotionEvent) {
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> taps.down(event.getPointerId(i), event.getX(i), event.getY(i),
                event.eventTime, finger = event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER && activePointer == -1,
                first = event.actionMasked == MotionEvent.ACTION_DOWN)
            MotionEvent.ACTION_MOVE -> {
                if (activePointer != -1) taps.penContact()
                for (p in 0 until event.pointerCount) taps.move(event.getPointerId(p), event.getX(p), event.getY(p), event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> taps.up(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            MotionEvent.ACTION_UP -> taps.up(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)?.let { tap ->
                if (activePointer != -1) return
                if (tap.command == TapCommand.FIT) {
                    if (!doubleTapFit) return
                    if (strokeAt(tap.x, tap.y) != null) return // design §8.4: double-tap acts on empty paper only
                    val m = view ?: return
                    val fit = fitScale()
                    if (Viewport.isFit(m.scale, fit)) zoomTo(maxOf(1f, fit * 2f), tap.x, tap.y) else fit()
                }
                onTap(tap.command)
            }
        }
    }

    /** Topmost visible stroke near a screen point, in canvas space. */
    private fun strokeAt(x: Float, y: Float): String? {
        if (!onCanvas(x, y)) return null
        val pm = view ?: return null
        val (sx, sy) = pm.toSheet(x, y)
        val radius = 20f * density / pm.scale
        val hidden = scene.pending.flatMap { it.hidden }.toSet() + eraseHandoff
        return scene.snapshot?.strokes.orEmpty().lastOrNull { stroke ->
            !stroke.tombstone && stroke.id !in hidden &&
                scene.samples[stroke.id]?.let { hitsStroke(sx, sy, radius, it, canvasTransformOf(stroke.id, stroke.sheetId)) } == true
        }?.id
    }

    // ---- Selection ----

    private fun clearSelection() {
        val had = selection != null
        selection = null; selGesture = null
        if (had) onSelection(0)
        invalidate()
    }
    fun deselect() = clearSelection()
    fun restyleSelection(colour: InkColour) {
        val s = selection ?: return
        if (onMarks(MarkEdit.Restyle(s.ids, colour))) restyleHandoff = restyleHandoff + s.ids.mapNotNull { id ->
            (pendingRestyles[id] ?: strokeById[id]?.brush)?.let { id to it.copy(colour = colour) }
        }
        invalidate()
    }
    fun deleteSelection() {
        val s = selection ?: return
        if (onMarks(MarkEdit.Delete(s.ids))) { eraseHandoff = eraseHandoff + s.ids; clearSelection() }
    }
    private fun onLongPress() {
        val start = tapStart ?: return
        if (fingerMode != FingerMode.PAN || !tapCandidate || activePointer != -1) return
        tapCandidate = false
        val id = strokeAt(start.first, start.second) ?: return
        val samples = scene.samples[id] ?: return
        val pm = view ?: return
        val bounds = Bounds.of(strokePoints(samples, canvasTransformOf(id, strokeById.getValue(id).sheetId))) ?: return
        val pad = 10f * density / pm.scale + brushWidth(strokeById[id]?.brush ?: brush) / 2
        selection = Selection(setOf(id), roundedOutline(bounds, pad, 8f * density / pm.scale))
        onSelection(1)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Keep holding and drag: the long-pressed stroke moves.
        if (beginSelectionGesture(start.first, start.second)) fingerMode = FingerMode.SELECTION
        invalidate()
    }
    private fun finishLasso() {
        val polygon = lassoPoints.toList()
        val hidden = scene.pending.flatMap { it.hidden }.toSet() + eraseHandoff
        val ids = if (polygon.size < 3) emptySet() else scene.snapshot?.strokes.orEmpty().filter { stroke ->
            !stroke.tombstone && stroke.id !in hidden &&
                scene.samples[stroke.id]?.let { lassoSelects(it, canvasTransformOf(stroke.id, stroke.sheetId), polygon) } == true
        }.map { it.id }.toSet()
        if (ids.isEmpty()) { clearSelection(); return }
        selection = Selection(ids, polygon)
        onSelection(ids.size)
    }
    /** Starts a move (inside the outline's bounds) or a uniform resize (on a corner handle). */
    private fun beginSelectionGesture(x: Float, y: Float): Boolean {
        val s = selection ?: return false
        val m = view ?: return false
        val pm = m
        val (px, py) = pm.toSheet(x, y)
        val bounds = Bounds.of(s.outline) ?: return false
        val handle = handleAt(bounds, px, py, 24f * density / pm.scale)
        if (handle == null && !bounds.contains(px, py)) return false
        selFrozen = pm; selStart = px to py; selStartScreen = x to y; selResize = handle != null; selMoved = false
        if (handle != null) { val corners = bounds.corners(); selHandleStart = corners[handle]; selAnchor = corners[(handle + 2) % 4] }
        selGesture = IDENTITY
        return true
    }
    private fun updateSelectionGesture(x: Float, y: Float) {
        if (selGesture == null) return
        if (!selMoved && hypot(x - selStartScreen.first, y - selStartScreen.second) < slop) return
        val (px, py) = selFrozen.toSheet(x, y)
        val dx = px - selStart.first; val dy = py - selStart.second
        selMoved = true
        selGesture = if (selResize) {
            val now = (selHandleStart.first + dx) to (selHandleStart.second + dy)
            scaleAbout(handleScale(selAnchor, selHandleStart, now).toDouble(), selAnchor.first.toDouble(), selAnchor.second.toDouble())
        } else translation(dx.toDouble(), dy.toDouble())
    }
    /** Temporary visual state ends here: one atomic Transform command, or nothing. */
    private fun commitSelectionGesture() {
        val g = selGesture ?: return
        selGesture = null
        val s = selection ?: return
        if (!selMoved || isIdentity(g)) return
        if (onMarks(MarkEdit.Transform(s.ids, g))) {
            transformHandoff = transformHandoff + s.ids.associateWith { id ->
                compose(localGesture(sheets.getValue(strokeById.getValue(id).sheetId), g), transformOf(id))
            }
            selection = Selection(s.ids, transformPolygon(s.outline, g))
        }
        invalidate()
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    companion object {
        const val PALM_MS = 300L
        /** In-frame dry-ink builds per frame (cold load); the rest come from the background cache build. */
        private const val SYNC_BUDGET_NS = 4_000_000L
    }
}

fun brushWidth(brush: Brush): Float = (when (brush.size) { BrushSize.SMALL -> 2f; BrushSize.MEDIUM -> 4f; BrushSize.LARGE -> 8f }) *
    if (brush.family == BrushFamily.HIGHLIGHTER) 5f else 1f
fun inkColour(brush: Brush, dark: Boolean = false): Int {
    val rgb = when (brush.colour) { InkColour.INK -> if (dark) 0xffeee7ed else 0xff29252b; InkColour.RED -> 0xffbe3544; InkColour.GREEN -> 0xff278052
        InkColour.BLUE -> 0xff356dc1; InkColour.ORANGE -> 0xffcf711c }.toInt()
    return if (brush.family == BrushFamily.HIGHLIGHTER) (rgb and 0x00ffffff) or 0x66000000 else rgb
}
