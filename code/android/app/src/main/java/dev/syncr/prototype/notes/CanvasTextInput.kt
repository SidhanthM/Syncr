package dev.syncr.prototype.notes

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/** The actual IME/selection surface lives on the paper, not in a dialog. */
internal class CanvasTextInput(private val host: FrameLayout) {
    var changed: (String) -> Unit = {}
    var selectionChanged: (Int, Int) -> Unit = { _, _ -> }
    var bounds: RectF? = null
        private set
    private var field: EditText? = null
    private var tap: Pair<Float, Float>? = null
    val active get() = field != null

    fun begin(text: String, rect: RectF, tapPosition: Pair<Float, Float>?) {
        if (active) return
        bounds = rect; tap = tapPosition
        val edit = object : EditText(host.context) {
            override fun onSelectionChanged(start: Int, end: Int) {
                super.onSelectionChanged(start, end)
                selectionChanged(start.coerceAtLeast(0), end.coerceAtLeast(0))
            }
        }
        field = edit
        edit.setBackgroundColor(Color.TRANSPARENT)
        edit.setPadding(0, 0, 0, 0)
        edit.gravity = Gravity.TOP or Gravity.START
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.setHorizontallyScrolling(false)
        edit.includeFontPadding = false
        edit.contentDescription = "Note text"
        edit.setText(text)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        host.addView(edit, FrameLayout.LayoutParams(1, 1))
        edit.requestFocus()
        host.invalidate()
    }

    fun position(mapping: SheetMapping, dark: Boolean) {
        val edit = field ?: return
        val rect = bounds ?: return
        val (x, y) = mapping.toScreen(rect.left, rect.top)
        val width = (rect.width() * mapping.scale).toInt().coerceAtLeast(1)
        val top = y.coerceAtLeast(0f)
        val height = (host.height - top - 64f * host.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        edit.translationX = x; edit.translationY = top
        val params = edit.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width; params.height = height; edit.layoutParams = params
        }
        val size = MacMetrics.BODY * mapping.scale
        if (edit.textSize != size) {
            edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            edit.setLineSpacing(MacMetrics.LINE_SPACING * mapping.scale, 1f)
        }
        edit.setTextColor(if (dark) Color.rgb(224, 221, 216) else Color.rgb(48, 46, 43))
        // Transparent so ink and the paper remain visible while typing.
        tap?.let { point ->
            tap = null
            edit.post {
                if (field !== edit) return@post
                edit.scrollTo(0, (-y).toInt().coerceAtLeast(0))
                edit.setSelection(edit.getOffsetForPosition(point.first - x, point.second - top).coerceIn(0, edit.length()))
                (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun setEnabled(enabled: Boolean) { field?.isEnabled = enabled }

    fun end() {
        field?.let {
            (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            host.removeView(it)
        }
        field = null; bounds = null; tap = null
        host.invalidate()
    }
}
