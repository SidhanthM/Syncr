package dev.syncr.prototype.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Selection
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import dev.syncr.prototype.editor.EditHistory
import dev.syncr.prototype.editor.EditorExtras
import dev.syncr.prototype.editor.EditorStyler
import dev.syncr.prototype.editor.TextSel
import dev.syncr.prototype.editor.Typing
import kotlin.math.abs

/**
 * The actual IME/selection surface lives on the paper, not in a dialog. The field is laid out in canvas units, in the
 * reading layer's column, fonts and line boxes (see [EditorStyler]), and drawn at the page's zoom by a view transform, so
 * its lines break exactly where the rendered note's do at any zoom. Typing rules run on every change ([Typing.reconcile]).
 */
internal class CanvasTextInput(private val host: FrameLayout) {
    /** The text, its selection and whether undo and redo have anything, after every change or move. */
    var onState: (TextSel, Boolean, Boolean) -> Unit = { _, _, _ -> }
    /** A finger drag on the text pans the page; the second is a fling's velocity. */
    var onPan: (Float) -> Unit = {}
    var onFling: (Float) -> Unit = {}
    /** The canvas units the field needs below the page's top, so the page can scroll that far. */
    var onExtent: (Float) -> Unit = {}
    /** Pixels at the bottom of the view covered by the toolbar and menu. */
    var bottomInset = 0
        set(value) { if (field != value) { field = value; edit?.post { ensureCaretVisible() } } }

    var bounds: RectF? = null
        private set
    private var edit: NoteEdit? = null
    private var tap: Pair<Float, Float>? = null
    private var wantedCaret: Int? = null
    private var history: EditHistory? = null
    private var styler: EditorStyler? = null
    private var dark = false
    private var before: TextSel? = null
    private var internal = false
    private var scale = 1f
    private var screenY = 0f
    private var extent = 0f
    val active get() = edit != null

    fun current(): TextSel = edit?.let { TextSel(it.text.toString(), selStart(it), selEnd(it)) } ?: TextSel("", 0)
    private fun selStart(e: EditText) = Selection.getSelectionStart(e.text).coerceAtLeast(0)
    private fun selEnd(e: EditText) = Selection.getSelectionEnd(e.text).coerceAtLeast(0)

    /** [tapPosition] is a point on the sheet, in canvas units, where the caret should go; [caret] is an exact offset that wins. */
    fun begin(text: String, rect: RectF, tapPosition: Pair<Float, Float>?, extras: EditorExtras, night: Boolean, caret: Int? = null) {
        if (active) return
        bounds = rect; tap = tapPosition; wantedCaret = caret; dark = night; lastExtras = extras
        val field = NoteEdit(host.context)
        edit = field
        val painter = TextPainter(night, emptyMap())
        styler = newStyler(night, rect.width(), extras)
        field.setBackgroundColor(Color.TRANSPARENT)
        field.setPadding(0, 0, 0, 0)
        field.gravity = Gravity.TOP or Gravity.START
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.setHorizontallyScrolling(false)
        field.includeFontPadding = false
        field.setLineSpacing(0f, 1f)
        field.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        field.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        if (Build.VERSION.SDK_INT >= 28) field.isFallbackLineSpacing = false
        // The pen draws on the page while the field is open; the system's stylus handwriting must not take it for text.
        if (Build.VERSION.SDK_INT >= 34) field.isAutoHandwritingEnabled = false
        // Nor may the system treat a pen stroke anywhere over the field as the start of handwriting: its area is shrunk to nothing.
        if (Build.VERSION.SDK_INT >= 34) field.setHandwritingBoundsOffsets(0f, 0f, -1_000_000f, -1_000_000f)
        field.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        field.isVerticalScrollBarEnabled = false
        field.pivotX = 0f; field.pivotY = 0f
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, MacMetrics.BODY)
        field.setTextColor(painter.colour(Tok.INK))
        field.highlightColor = if (night) 0x55d39999 else 0x55c46f6f
        field.contentDescription = "Note text"
        field.setText(text)
        field.setSelection(0)
        history = EditHistory(TextSel(text, 0))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (!internal) before = TextSel(s.toString(), Selection.getSelectionStart(s).coerceAtLeast(0), Selection.getSelectionEnd(s).coerceAtLeast(0))
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: Editable) { if (!internal) changed(e) }
        })
        host.addView(field, FrameLayout.LayoutParams(rect.width().toInt(), FrameLayout.LayoutParams.WRAP_CONTENT))
        restyle()
        field.requestFocus()
        host.invalidate()
    }

    // ---- Changes ----

    private fun changed(e: Editable) {
        val field = edit ?: return
        val now = TextSel(e.toString(), selStart(field), selEnd(field))
        val prior = before; before = null
        val composing = BaseInputConnection.getComposingSpanStart(e) >= 0
        val fixed = prior?.let { Typing.reconcile(it, now.text, composing) }
        if (fixed != null) {
            put(fixed)
            history?.record(fixed, coalesce = false, now = System.currentTimeMillis())
            // The keyboard sets its own caret after the change; set ours again once it has.
            field.post { if (edit === field && current().text == fixed.text && (selStart(field) != fixed.start || selEnd(field) != fixed.end)) { internal = true; Selection.setSelection(field.text, fixed.start, fixed.end); internal = false; restyle(); publish() } }
        } else {
            val plain = prior != null && abs(now.text.length - prior.text.length) <= 1 && now.text.count { it == '\n' } == prior.text.count { it == '\n' }
            history?.record(now, coalesce = plain, now = System.currentTimeMillis())
            restyle()
        }
        publish()
        field.post { ensureCaretVisible() }
    }

    /** Replaces the field's text by [target] with the smallest splice, and sets its selection. */
    private fun put(target: TextSel, restart: Boolean = false) {
        val field = edit ?: return
        val e = field.text
        // A word the keyboard is still composing would be put back over the edit.
        BaseInputConnection.removeComposingSpans(e)
        val old = e.toString()
        var prefix = 0
        while (prefix < minOf(old.length, target.text.length) && old[prefix] == target.text[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(old.length, target.text.length) - prefix && old[old.length - 1 - suffix] == target.text[target.text.length - 1 - suffix]) suffix++
        internal = true
        try {
            if (old != target.text) e.replace(prefix, old.length - suffix, target.text, prefix, target.text.length - suffix)
            Selection.setSelection(e, target.start, target.end)
        } finally { internal = false }
        restyle()
        if (restart) (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
    }

    /** A large code block's colours arrive off the main thread; restyle when they do and the field still shows that text. */
    private fun newStyler(night: Boolean, width: Float, extras: EditorExtras) = EditorStyler(night, width).also { st ->
        st.extras = extras
        st.onCodeReady = { if (styler === st) restyle() }
    }

    private fun restyle() {
        val field = edit ?: return
        val was = internal
        internal = true
        try { styler?.style(field.text, selStart(field), selEnd(field)) } finally { internal = was }
        field.invalidateCodeBlocks()
    }

    private fun publish() {
        val h = history ?: return
        onState(current(), h.canUndo, h.canRedo)
    }

    private fun selection() {
        if (internal) return
        val field = edit ?: return
        history?.moved(current())
        restyle(); publish()
        field.post { ensureCaretVisible() }
    }

    /** Applies a command's result as one undo step. */
    fun apply(next: TextSel, step: Boolean = true) {
        if (!active) return
        put(next, restart = true)
        if (step) history?.record(next, coalesce = false, now = System.currentTimeMillis())
        publish()
        edit?.post { ensureCaretVisible() }
    }

    fun undo() { history?.undo()?.let { put(it, restart = true); publish() } }
    fun redo() { history?.redo()?.let { put(it, restart = true); publish() } }

    /** A new baseline (after Reload): the text as it is on the page now, with a fresh history. */
    fun reset(text: String) {
        if (!active) return
        history = EditHistory(TextSel(text, text.length))
        put(TextSel(text, text.length), restart = true); publish()
    }

    fun setExtras(extras: EditorExtras) { lastExtras = extras; styler?.extras = extras; restyle() }

    fun tab(outdent: Boolean) = apply(Typing.tab(current(), outdent))

    // ---- Position ----

    fun position(mapping: SheetMapping, night: Boolean) {
        val field = edit ?: return
        val rect = bounds ?: return
        if (night != dark) {
            dark = night
            styler?.forget(field.text); styler = newStyler(night, rect.width(), lastExtras)
            field.setTextColor(TextPainter(night, emptyMap()).colour(Tok.INK))
            restyle()
        }
        val (x, y) = mapping.toScreen(rect.left, rect.top)
        scale = mapping.scale; screenY = y
        val params = field.layoutParams
        val contentHeight = field.layout?.height ?: 0
        val wanted = maxOf(contentHeight, ((host.height - y) / mapping.scale).toInt() + 1, 1)
        val width = rect.width().toInt()
        if (params.width != width || params.height != wanted) { params.width = width; params.height = wanted; field.layoutParams = params }
        field.translationX = x; field.translationY = y
        field.scaleX = mapping.scale; field.scaleY = mapping.scale
        field.setCursor(mapping.scale, host.resources.displayMetrics.density, TextPainter(night, emptyMap()).colour(Tok.INK))
        val needed = rect.top + contentHeight
        if (needed != extent) { extent = needed; onExtent(needed) }
        tap?.let { point ->
            if (field.layout == null || placing) return@let
            placing = true
            field.post {
                tap = null; placing = false
                if (edit !== field) return@post
                val offset = (wantedCaret ?: field.getOffsetForPosition(point.first - rect.left, point.second - rect.top)).coerceIn(0, field.length())
                wantedCaret = null
                field.setSelection(offset)
                ensureCaretVisible()
                (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private var lastExtras = EditorExtras()
    private var placing = false

    /** Scrolls the page so the caret's line is on screen, above the keyboard and the toolbar. */
    fun ensureCaretVisible() {
        val field = edit ?: return
        if (tap != null) return // the caret goes where the tap landed first
        val layout = field.layout ?: return
        val line = layout.getLineForOffset(selEnd(field).coerceIn(0, field.length()))
        val density = host.resources.displayMetrics.density
        val top = screenY + layout.getLineTop(line) * scale
        val bottom = screenY + layout.getLineBottom(line) * scale
        val limit = host.height - bottomInset - 16f * density
        val margin = 16f * density
        val dy = when { bottom > limit -> limit - bottom; top < margin -> margin - top; else -> 0f }
        if (abs(dy) >= 1f) onPan(dy)
    }

    fun setEnabled(enabled: Boolean) { edit?.isEnabled = enabled }

    fun end() {
        edit?.let {
            (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            host.removeView(it)
        }
        edit = null; bounds = null; tap = null; wantedCaret = null; history = null; styler = null; before = null
        onExtent(0f)
        host.invalidate()
    }

    private inner class NoteEdit(context: Context) : EditText(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var lastY = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private var codeBlocks: List<IntArray> = emptyList()
        private var cursorKey = 0f

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            if (edit === this) selection()
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed) { tab(event.isShiftPressed); return true }
            return super.onKeyDown(keyCode, event)
        }

        fun invalidateCodeBlocks() {
            val blocks = ArrayList<IntArray>()
            var open = -1
            for (line in TextMarkdown.lines(text.toString())) {
                val code = line.kind is MdKind.Code || line.kind is MdKind.Fence
                if (code && open < 0) open = line.start
                if (!code && open >= 0) { blocks += intArrayOf(open, line.start - 1); open = -1 }
                if (code && line.end >= text.length && open >= 0) { blocks += intArrayOf(open, line.end); open = -1 }
            }
            codeBlocks = blocks
        }

        override fun onDraw(canvas: Canvas) {
            val l = layout
            if (l != null && codeBlocks.isNotEmpty()) {
                fill.color = TextPainter(dark, emptyMap()).colour(Tok.INK); fill.alpha = (0.12f * 255).toInt()
                for (b in codeBlocks) {
                    val a = l.getLineTop(l.getLineForOffset(b[0].coerceIn(0, length())))
                    val z = l.getLineBottom(l.getLineForOffset(b[1].coerceIn(0, length())))
                    canvas.drawRoundRect(0f, a.toFloat(), width.toFloat(), z.toFloat(), 14f, 14f, fill)
                }
            }
            super.onDraw(canvas)
        }

        /** A cursor about 2 dp wide on screen, whatever the zoom. */
        fun setCursor(zoom: Float, density: Float, colour: Int) {
            if (Build.VERSION.SDK_INT < 29 || abs(cursorKey - zoom) < 0.001f) return
            cursorKey = zoom
            textCursorDrawable = GradientDrawable().apply { setColor(colour); setSize(maxOf(2, (2f * density / zoom).toInt()), 0) }
        }

        /** The task line whose rendered box is at ([x], [y]) in the field's own units, or -1. */
        private fun taskBoxAt(x: Float, y: Float): Int {
            val l = layout ?: return -1
            val text = text.toString()
            val line = l.getLineForVertical(y.toInt())
            val start = l.getLineStart(line)
            val index = text.substring(0, start).count { it == '\n' }
            val md = TextMarkdown.lines(text).getOrNull(index) ?: return -1
            if (md.kind !is MdKind.Task || md.start != start || index in dev.syncr.prototype.editor.Reveal.lines(text, selectionStart, selectionEnd)) return -1
            val left = l.getPrimaryHorizontal(start + md.text.takeWhile { it == ' ' || it == '\t' }.length)
            return if (x >= left - 8f && x <= left + 42f) index else -1
        }

        private var boxLine = -1

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // A rendered task box flips on a tap: the caret stays and no keyboard opens (T18).
            if (event.actionMasked == MotionEvent.ACTION_DOWN) boxLine = taskBoxAt(event.x, event.y)
            if (boxLine >= 0) {
                if (event.actionMasked == MotionEvent.ACTION_UP && taskBoxAt(event.x, event.y) == boxLine) {
                    val now = current()
                    Typing.flipTask(now.text, boxLine)?.let { apply(TextSel(it.text, now.start, now.end)) }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) boxLine = -1
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; lastY = event.rawY; dragging = false
                    tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (!dragging && abs(event.rawY - downY) > slop && abs(event.rawY - downY) > abs(event.rawX - downX)) {
                        dragging = true
                        val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel); cancel.recycle()
                    }
                    if (dragging) { onPan(event.rawY - lastY); lastY = event.rawY; return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        tracker?.computeCurrentVelocity(1000)
                        onFling((tracker?.yVelocity ?: 0f) * scale)
                        dragging = false; tracker?.recycle(); tracker = null
                        return true
                    }
                    tracker?.recycle(); tracker = null
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
