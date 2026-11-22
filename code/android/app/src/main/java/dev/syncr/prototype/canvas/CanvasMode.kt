package dev.syncr.prototype.canvas

import android.content.Context
import android.graphics.Canvas
import dev.syncr.core.Waypoint
import dev.syncr.prototype.notes.CanvasScene
import dev.syncr.prototype.notes.ContentHit
import dev.syncr.prototype.notes.InkPreview
import dev.syncr.prototype.notes.SheetMapping
import dev.syncr.prototype.ui.theme.DustyRose
import androidx.compose.ui.graphics.toArgb

/**
 * Everything the note view needs to be an infinite canvas, kept out of the view itself: the ruling and paper from the
 * note's stored background, the content bounds, the spatial index that culls strokes, the minimap, the fits and jumps, and
 * the per-device last view. The view asks and draws; this holds the state.
 */
internal class CanvasMode(context: Context) {
    val store = CanvasViewStore(context)
    private val density = context.resources.displayMetrics.density
    private val accent = DustyRose.toArgb()

    var minimapOn = store.minimap
        set(v) { field = v; store.minimap = v }
    private val minimap = CanvasMinimap()

    private val index = CanvasIndex()
    private var rects = DoubleArray(0)
    private var count = 0
    private var inkCount = 0
    private var builtRevision = -1L
    private var builtText = -1
    private var builtList: List<InkPreview>? = null
    private var version = 0L
    private var bounds: UnitRect? = null
    private val query = IntArrayList()

    /** Rebuilds the item rectangles and the index when the scene or the text changed. */
    fun sync(scene: CanvasScene, text: List<ContentHit>, textVersion: Int) {
        if (scene.revision == builtRevision && textVersion == builtText && scene.previews === builtList) return
        builtRevision = scene.revision; builtText = textVersion; builtList = scene.previews
        val n = scene.previews.size
        var r = scene.inkRects(rects)
        if (r.size < (n + text.size) * 4) r = r.copyOf((n + text.size) * 4)
        for ((i, box) in text.withIndex()) {
            val at = 4 * (n + i)
            r[at] = box.x.toDouble(); r[at + 1] = box.y.toDouble(); r[at + 2] = (box.x + box.width).toDouble(); r[at + 3] = (box.y + box.height).toDouble()
        }
        rects = r; inkCount = n; count = n + text.size; version++
        index.rebuild(rects, inkCount)
        var u: UnitRect? = null
        for (i in 0 until count) {
            if (rects[4 * i].isNaN()) continue
            val b = UnitRect(rects[4 * i], rects[4 * i + 1], rects[4 * i + 2], rects[4 * i + 3])
            u = u?.union(b) ?: b
        }
        bounds = u
    }

    /** The union of all ink and text, or null for an empty canvas. */
    fun contentBounds(): UnitRect? = bounds

    /**
     * Indices into the scene's previews that touch [view], ascending (drawing order), or null when the view takes in so much
     * of the content that the caller's own per-stroke bounds test is cheaper than the grid (measured: the grid wins for a view
     * a fraction of the content, and loses when the view covers most of it).
     */
    fun visible(view: UnitRect): IntArrayList? {
        val all = bounds ?: return null
        if (view.width * view.height > all.width * all.height * WIDE_VIEW) return null
        index.query(view.left, view.top, view.right, view.bottom, query)
        return query
    }

    // ---- views ----

    private fun viewport(m: SheetMapping, w: Int, h: Int) = InfiniteViewport().also { it.from(m, w, h) }

    fun fitAll(m: SheetMapping, w: Int, h: Int): SheetMapping = viewport(m, w, h).also { it.fitAll(bounds) }.mapping()
    fun step(m: SheetMapping, w: Int, h: Int, inward: Boolean): SheetMapping = viewport(m, w, h).also { it.step(inward) }.mapping()
    fun jump(m: SheetMapping, w: Int, h: Int, cx: Double, cy: Double): SheetMapping = viewport(m, w, h).also { it.centerOn(cx, cy) }.mapping()
    fun waypoint(m: SheetMapping, w: Int, h: Int, name: String): Waypoint = viewport(m, w, h).toWaypoint(name)
    fun goTo(m: SheetMapping, w: Int, h: Int, wp: Waypoint): SheetMapping = viewport(m, w, h).also { it.apply(wp) }.mapping()

    /** The zoom as the xnotes percentage the toolbar shows. */
    fun percent(m: SheetMapping): Int = Math.round(m.scale * XN * 100f)

    /** The view to open with: the last one kept on this device for [noteId], else null (the caller fits all). */
    fun savedView(noteId: String, w: Int, h: Int): SheetMapping? {
        val v = store.view(noteId) ?: return null
        return InfiniteViewport().also { it.resize(w, h); it.set(v.zoom, 0.0, 0.0); it.centerOn(v.centerX, v.centerY) }.mapping()
    }

    fun saveView(noteId: String, m: SheetMapping, w: Int, h: Int) {
        val v = viewport(m, w, h)
        store.saveView(noteId, SavedView(v.zoom, v.centerX, v.centerY))
    }

    // ---- drawing ----

    /** A canvas is plain paper in the theme's colour: no ruling (owner decision), whatever the core stores. */
    fun drawBackground(canvas: Canvas, paper: Int) = canvas.drawColor(paper)

    /** The panel sits above the tool bubble's resting corner, as xnotes' panel clears its floating bar. */
    private val lift = MINIMAP_LIFT_DP * density

    fun minimapPanel(w: Int, h: Int) = minimap.panel(w, h, 0f, lift, density)

    fun hitsMinimap(x: Float, y: Float, w: Int, h: Int): Boolean {
        if (!minimapOn) return false
        val p = minimapPanel(w, h)
        return x >= p.left && x <= p.right && y >= p.top && y <= p.bottom
    }

    /** Where in the canvas a touch at ([x], [y]) on the minimap points. */
    fun minimapTarget(x: Float, y: Float, m: SheetMapping, w: Int, h: Int): Pair<Double, Double> {
        val vp = viewport(m, w, h)
        val panel = minimapPanel(w, h)
        return minimap.toCanvas(x, y, minimap.extent(bounds, vp.visible()), panel, density)
    }

    fun drawMinimap(canvas: Canvas, w: Int, h: Int, m: SheetMapping, dark: Boolean) {
        if (!minimapOn) return
        val vp = viewport(m, w, h)
        val visible = vp.visible()
        val panel = minimapPanel(w, h)
        val extent = minimap.extent(bounds, visible)
        val back = if (dark) 0xE61E1E1E.toInt() else 0xE6FFFFFF.toInt()
        val line = if (dark) 0x40FFFFFF else 0x33000000
        val item = if (dark) 0xFFB9B9B9.toInt() else 0xFF555555.toInt()
        minimap.draw(canvas, panel, extent, rects, count, version * 31 + item, visible, density, back, line, item, accent)
    }

    companion object {
        /** How far the minimap clears the bottom edge, in dp (a 64 dp bubble rests in that corner); xnotes has the same inset for its bar. */
        /** A view over this share of the content's area is culled by the per-stroke bounds instead of the grid. */
        const val WIDE_VIEW = 0.15
        const val MINIMAP_LIFT_DP = 72f
    }
}
