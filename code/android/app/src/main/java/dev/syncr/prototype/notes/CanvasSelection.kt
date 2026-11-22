package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.InkColour
import kotlin.math.hypot

/**
 * The selection and its move/resize gesture, in canvas space (so one selection can span legacy
 * sheets). The gesture is only a visual transform until it is committed as one atomic Transform
 * command. Pure, so it is unit tested; [onMarks] stages commands and [onCount] reports the size.
 */
internal class CanvasSelection(
    private val scene: CanvasScene,
    private val density: Float,
    private val slop: Float,
    private val onMarks: (MarkEdit) -> Boolean,
    private val onCount: (Int) -> Unit,
) {
    private class Selected(val ids: Set<String>, val outline: List<Pt>)

    private var selected: Selected? = null

    /** The uncommitted canvas-space gesture, or null when none is in progress. */
    var gesture: Affine? = null
        private set

    // Gesture state, captured when it begins.
    private var mapping = SheetMapping(1f, 0f, 0f)
    private var startScreen: Pt = 0f to 0f
    private var start: Pt = 0f to 0f
    private var resizing = false
    private var anchor: Pt = 0f to 0f
    private var handleStart: Pt = 0f to 0f
    private var moved = false

    val ids: Set<String> get() = selected?.ids.orEmpty()

    fun clear() {
        val had = selected != null
        selected = null
        gesture = null
        if (had) onCount(0)
    }

    /** Clears the selection when any of its strokes was deleted or is gone. */
    fun dropIfStale() {
        if (selected?.ids?.any { !scene.isLive(it) } == true) clear()
    }

    /** Selects what the lasso [polygon] encloses, or clears. */
    fun selectLasso(polygon: List<Pt>) {
        val ids = scene.strokesInLasso(polygon)
        if (ids.isEmpty()) {
            clear()
            return
        }
        selected = Selected(ids, polygon)
        onCount(ids.size)
    }

    /** Long-press: selects one stroke inside a padded, rounded outline. */
    fun selectStroke(id: String, mapping: SheetMapping): Boolean {
        val stroke = scene.stroke(id) ?: return false
        val samples = scene.samples(id) ?: return false
        val bounds = Bounds.of(strokePoints(samples, scene.canvasTransformOf(id, stroke.sheetId))) ?: return false
        val pad = 10f * density / mapping.scale + brushWidth(stroke.brush) / 2
        selected = Selected(setOf(id), roundedOutline(bounds, pad, 8f * density / mapping.scale))
        onCount(1)
        return true
    }

    /** Starts a move (inside the outline's bounds) or a uniform resize (on a corner handle). */
    fun beginGesture(x: Float, y: Float, mapping: SheetMapping?): Boolean {
        val selection = selected ?: return false
        val m = mapping ?: return false
        val (cx, cy) = m.toSheet(x, y)
        val bounds = Bounds.of(selection.outline) ?: return false
        val handle = handleAt(bounds, cx, cy, 24f * density / m.scale)
        if (handle == null && !bounds.contains(cx, cy)) return false
        this.mapping = m
        start = cx to cy
        startScreen = x to y
        resizing = handle != null
        moved = false
        if (handle != null) {
            val corners = bounds.corners()
            handleStart = corners[handle]
            anchor = corners[(handle + 2) % 4]
        }
        gesture = IDENTITY
        return true
    }

    /** Follows the pointer once it leaves touch slop. */
    fun updateGesture(x: Float, y: Float) {
        if (gesture == null) return
        if (!moved && hypot(x - startScreen.first, y - startScreen.second) < slop) return
        val (cx, cy) = mapping.toSheet(x, y)
        val dx = cx - start.first
        val dy = cy - start.second
        moved = true
        gesture = if (resizing) {
            val handleNow = (handleStart.first + dx) to (handleStart.second + dy)
            val scale = handleScale(anchor, handleStart, handleNow).toDouble()
            scaleAbout(scale, anchor.first.toDouble(), anchor.second.toDouble())
        } else {
            translation(dx.toDouble(), dy.toDouble())
        }
    }

    /** Ends the gesture with one atomic Transform command, or nothing if it didn't move. */
    fun commitGesture() {
        val committed = gesture ?: return
        gesture = null
        val selection = selected ?: return
        if (!moved || isIdentity(committed)) return
        if (onMarks(MarkEdit.Transform(selection.ids, committed, scene.placement))) {
            scene.handOffTransform(selection.ids, committed)
            selected = Selected(selection.ids, transformPolygon(selection.outline, committed))
        }
    }

    /** Abandons the gesture; the strokes snap back. */
    fun cancelGesture() {
        gesture = null
    }

    fun restyle(colour: InkColour) {
        val selection = selected ?: return
        onMarks(MarkEdit.Restyle(selection.ids, colour))
    }

    fun delete() {
        val selection = selected ?: return
        if (onMarks(MarkEdit.Delete(selection.ids))) {
            scene.handOffErase(selection.ids)
            clear()
        }
    }

    /** A tap on empty paper, outside the selection's bounds, clears it. */
    fun tap(x: Float, y: Float, mapping: SheetMapping) {
        val selection = selected ?: return
        val (cx, cy) = mapping.toSheet(x, y)
        if (Bounds.of(selection.outline)?.contains(cx, cy) != true) clear()
    }

    /** The outline to draw, moved by any gesture in progress, and the mapping to draw it with. */
    fun outline(current: SheetMapping): Pair<List<Pt>, SheetMapping>? {
        val selection = selected ?: return null
        val inProgress = gesture ?: return selection.outline to current
        return transformPolygon(selection.outline, inProgress) to mapping
    }
}
