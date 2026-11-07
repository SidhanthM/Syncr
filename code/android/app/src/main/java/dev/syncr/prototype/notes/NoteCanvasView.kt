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
    private val textInput = CanvasTextInput(this)
    private var lastTextTap: Pair<Float, Float>? = null
    private var textSnapshot: dev.syncr.core.NoteSnapshot? = null

    fun beginTextEditing(draft: TextDraft, value: String, changed: (String) -> Unit, selection: (Int, Int) -> Unit) {
        textInput.changed = changed
        textInput.selectionChanged = selection
        val block = textSnapshot?.textBlocks?.firstOrNull { it.id == draft.blockId }
        val document = block == null || block.role == dev.syncr.core.TextRole.DOCUMENT
        val rect = draft.blockId?.let(text::bounds) ?: if (document) {
            RectF(100f, 260f, (block?.width ?: 1600f) - 100f, 324f)
        } else {
            RectF(block!!.originX + block.transform.tx.toFloat(), block.originY + block.transform.ty.toFloat(),
                block.originX + block.transform.tx.toFloat() + block.width, block.originY + block.transform.ty.toFloat() + 64f)
        }
        textInput.begin(value, rect, lastTextTap ?: (viewport.mapping?.toScreen(rect.left, rect.top) ?: (0f to 0f)))
        lastTextTap = null
    }

    fun endTextEditing() = textInput.end()
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

    // Settings → Pen & gestures (ui/AppPreferences.kt), pushed in by NoteScreen.
    var sideButtonErases = true
    var doubleTapFit = true
    var palmMs = PALM_MS

    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val accent = DustyRose.toArgb()
    private var dark = false

    private val scene = CanvasScene()
    private val viewport = CanvasViewport()

    /** The note's text and the companion's comments under the ink, built and recorded off the main thread. */
    private var textBottom = 0f
    private val text = TextLayer(invalidate = ::invalidate, onExtent = { bottom ->
        textBottom = bottom
        viewport.setLayout(CanvasLayout(maxOf(scene.lowestInk(), textBottom)))
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
    private val dry = DryInkLayer(
        InkPreferencesStore.state(context).value,
        scope = { scope },
        isHandedOff = scene::hasStrokeHandoff,
        invalidate = ::invalidate,
    )
    private val wet = WetInkBridge(context, onFinished = dry::adoptWetStroke, invalidate = ::invalidate)
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
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private val edgePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    init {
        setWillNotDraw(false)
        addView(wet.view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentDescription = "Note canvas. Stylus draws; one finger scrolls, pinch zooms. " +
            "Two-finger tap undoes, three-finger tap redoes, double-tap empty paper to fit the page, long-press selects a stroke."
        isFocusable = true
    }

    // ---- API ----

    fun update(state: NotesState, night: Boolean) {
        val noteId = state.snapshot?.noteId
        if (noteId != scene.noteId) startNote(noteId)
        dark = night
        textSnapshot = state.snapshot
        scene.load(state)
        selection.dropIfStale()
        viewport.setLayout(CanvasLayout(maxOf(scene.lowestInk(), textBottom)))
        text.show(state.snapshot, night)
        dry.show(scene.previews, night)
        invalidate()
    }

    /** Back to the phone column. Ignored while the pen is down. */
    fun fit() {
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
        if (pen.mode == PenMode.ERASE && !pen.rejected) commitErase(android.os.SystemClock.uptimeMillis(), force = true)
        eraseGesture = null
        wet.cancel()
        pen.cancel()
        cancelMirror()
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
        wet.clear()
        scene.clearHandoffs()
        dry.clearHandoffInk()
        deselect()
        viewport.reset(restoredViewport?.takeIf { it.first == noteId }?.second)
    }

    // ---- Lifecycle ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelGesture()
        viewport.resize(w, h, oldw)
        textInput.bounds?.let { rect ->
            viewport.mapping?.let { m ->
                val (_, top) = m.toScreen(rect.left, rect.top)
                if (top > h / 3f) viewport.navigate(0f, h / 3f - top, 1f, 0f, 0f)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = mainScope()
            dry.forgetPreviews()
        }
        scope.launch { InkPreferencesStore.state(context).collect(dry::setPreferences) }
        post { wet.eagerInit() } // children attach after this callback
    }

    override fun onDetachedFromWindow() {
        text.clear()
        cancelGesture()
        wet.clear()
        dry.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            viewport.scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(PaperPalette.desk(dark))
        val m = viewport.mapping ?: return
        scene.noteId?.let { onViewportChanged(it, m) }
        textInput.position(m, dark)
        dry.beginFrame()
        // The paper: the canvas's width, from its top edge down past the bottom of the screen.
        pageRect.set(m.tx, m.ty, m.tx + CanvasLayout.WIDTH * m.scale, height.toFloat())
        pagePaint.color = PaperPalette.paper(dark)
        canvas.drawRect(pageRect, pagePaint)
        edgePaint.color = PaperPalette.hairline(dark)
        canvas.drawLine(pageRect.left, pageRect.top, pageRect.left, pageRect.bottom, edgePaint)
        canvas.drawLine(pageRect.right, pageRect.top, pageRect.right, pageRect.bottom, edgePaint)
        canvas.withClip(pageRect) {
            translate(m.tx, m.ty)
            scale(m.scale, m.scale)
            viewRect.set(-m.tx / m.scale, -m.ty / m.scale, (width - m.tx) / m.scale, (height - m.ty) / m.scale)
            save()
            textInput.bounds?.let { clipOutRect(it) }
            text.draw(this, viewRect) // finished bands only; never waits for layout
            restore()
            trace(TraceNames.DRY_DRAW) {
                // The visible part of the canvas, to skip strokes wholly off screen.
                dry.previews.forEach { drawStroke(this, it, m.scale) }
                scene.strokeHandoffs.forEach { handoff ->
                    if (dry.previews.none { it.id == handoff.id }) drawStroke(this, handoff, m.scale)
                }
            }
            if (pen.mode == PenMode.INK && !wet.isDrawing && !wet.isAttached) {
                pen.inkChannels()?.let { dry.drawLive(this, it, pen.brush, m.scale) }
            }
        }
        drawLassoAndSelection(canvas, m)
    }

    private fun drawStroke(canvas: Canvas, preview: InkPreview, scale: Float) {
        val id = preview.id
        // Erased (staged, handed off or under the eraser now), or still on the wet layer.
        if (scene.isHidden(id) || id in pen.erased || wet.isFinishing(id)) return
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

    override fun onHoverEvent(event: MotionEvent): Boolean = isPenTool(event.getToolType(0)) || super.onHoverEvent(event)

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (!isPenTool(event.getToolType(pointerIndex))) return super.onResolvePointerIcon(event, pointerIndex)
        val scale = viewport.mapping?.scale ?: return super.onResolvePointerIcon(event, pointerIndex)
        val units = when {
            eraser -> ERASER_HOVER_UNITS
            lasso -> 0f
            else -> brushWidth(brush)
        }
        val colour = if (lasso) accent else PaperPalette.quiet(dark)
        return hoverIcon.icon(units * scale, colour, dashed = lasso)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = trace(TraceNames.TOUCH_INPUT) { handleTouch(event) }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (scene.closing) {
            cancelGesture()
            return true
        }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
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
            palmBlocked = isRestingPalm(isPenTool(event.getToolType(0)), event.eventTime, lastPenUp, palmMs)
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
        if (down && isPenTool(event.getToolType(actionIndex))) {
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
                penUp(event, index)
                lastPenUp = event.eventTime
            }
        } else if (!palmBlocked && (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }) {
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
        pen.begin(brush)
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
        pen.start(
            mode,
            viewport.mapping!!,
            hasTilt = device?.getMotionRange(MotionEvent.AXIS_TILT) != null,
            hasOrientation = device?.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
        )
        if (mode == PenMode.ERASE) {
            eraseGesture = java.util.UUID.randomUUID().toString()
            lastEraseCommit = event.eventTime
        }
        sample(event, index, CURRENT)
        if (mode == PenMode.INK && !pen.rejected) {
            val inkBrush = InkBrushes.brush(pen.brush, dark, dry.prefs)
            wet.start(event, event.getPointerId(index), pen.frozen, width, height, inkBrush, dark, dry.prefs)
            startMirror(event, index)
        }
    }

    /** After the local wet ink has started: opens the mirrored stroke and sends the pen-down sample. */
    private fun startMirror(event: MotionEvent, index: Int) {
        val target = mirror ?: return
        val note = scene.noteId ?: return
        val sheet = scene.firstSheet ?: return
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

    private fun cancelMirror() {
        if (mirroring) mirror?.cancel()
        mirroring = false
    }

    private fun penUp(event: MotionEvent, index: Int) {
        if (!pen.rejected) {
            when (pen.mode) {
                PenMode.INK -> {
                    val preview = pen.inkChannels()?.let { channels ->
                        scene.firstSheet?.let { sheet -> onStroke(sheet, channels, pen.brush)?.also(scene::handOffStroke) }
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
        wet.cancel()
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
            wet.cancel()
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
                tapStart = Triple(event.x, event.y, event.eventTime)
                tapCandidate = true
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                fingerMode = if (selection.beginGesture(event.x, event.y, viewport.mapping)) {
                    FingerMode.SELECTION
                } else {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    FingerMode.PAN
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                tapCandidate = false
                // A second finger turns a selection drag back into navigation.
                if (fingerMode == FingerMode.SELECTION) selection.cancelGesture()
                fingerMode = FingerMode.PAN
                panZoom.secondPointerDown()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val start = tapStart
                if (start != null && hypot(event.x - start.first, event.y - start.second) > slop) {
                    removeCallbacks(longPress)
                    tapCandidate = false
                }
                when (fingerMode) {
                    FingerMode.SELECTION -> selection.updateGesture(event.x, event.y)
                    FingerMode.PAN -> pan(event)
                    FingerMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (fingerMode == FingerMode.SELECTION) {
                    selection.commitGesture()
                } else if (fingerMode == FingerMode.PAN) {
                    if (panZoom.pinched) {
                        val (fx, fy) = panZoom.focus ?: (width / 2f to height / 2f)
                        viewport.snapAfterPinch(fx, fy)
                    } else if (!tapCandidate) {
                        velocity?.let { v ->
                            v.addMovement(event)
                            v.computeCurrentVelocity(1000)
                            fling(v.xVelocity, v.yVelocity)
                        }
                    }
                }
                val start = tapStart
                if (tapCandidate && start != null && event.eventTime - start.third < ViewConfiguration.getTapTimeout() * 3L) {
                    viewport.mapping?.let { mapping ->
                        val (x, y) = mapping.toSheet(event.x, event.y)
                        val target = if (!lasso && !eraser && strokeAt(event.x, event.y) == null) text.hit(x, y) else null
                        if (target != null) {
                            lastTextTap = event.x to event.y
                            onContent(target)
                        } else selection.tap(event.x, event.y, mapping)
                    }
                }
                fingerMode = FingerMode.NONE
                tapCandidate = false
                velocity?.recycle()
                velocity = null
            }
        }
    }

    private fun pan(event: MotionEvent) {
        val xs = FloatArray(event.pointerCount) { event.getX(it) }
        val ys = FloatArray(event.pointerCount) { event.getY(it) }
        panZoom.move(xs, ys, withinTapSlop = tapCandidate, viewport)
    }

    private fun fling(vx: Float, vy: Float) {
        val bounds = viewport.flingBounds() ?: return
        scroller.fling(bounds.startX, bounds.startY, vx.toInt(), vy.toInt(), bounds.minX, 0, bounds.minY, 0)
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
                    viewport.mapping?.toSheet(tap.x, tap.y)?.let { (x, y) -> if (text.hit(x, y) != null) return }
                    if (strokeAt(tap.x, tap.y) != null) return // design §8.4: double-tap acts on empty paper only
                    if (viewport.mapping == null) return
                    if (!viewport.zoomInFromFit(tap.x, tap.y)) fit()
                }
                onTap(tap.command)
            }
        }
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

    /** Topmost visible stroke near a screen point. */
    private fun strokeAt(x: Float, y: Float): String? {
        if (!isOnCanvas(x, y)) return null
        val m = viewport.mapping ?: return null
        val (cx, cy) = m.toSheet(x, y)
        return scene.strokeAt(cx, cy, radius = 20f * density / m.scale)
    }

    private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val PALM_MS = 300L

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
