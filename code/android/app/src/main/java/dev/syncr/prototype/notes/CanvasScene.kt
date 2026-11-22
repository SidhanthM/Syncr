package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.Brush
import dev.syncr.core.SampleChannels
import dev.syncr.core.SheetView
import dev.syncr.core.StrokeView
import java.util.IdentityHashMap
import kotlin.math.hypot

/**
 * What the canvas shows, in canvas units: the core snapshot, the repository's staged edits, and
 * local handoffs. A handoff is an edit this view has just given the repository; it is drawn from
 * here until the state passed to [load] represents it, so no frame shows the old ink in between.
 * Pure (no Android types), so it is unit tested directly.
 */
internal class CanvasScene {
    private var state = NotesState()
    private var sheets = emptyMap<String, SheetView>()
    private var strokes = emptyMap<String, StrokeView>()
    private var pendingTransforms = emptyMap<String, Affine>()
    private var pendingHidden = emptySet<String>()

    /** Saved strokes (with staged restyles) followed by staged new strokes, in drawing order. */
    var previews = emptyList<InkPreview>()
        private set

    /** The sheet that receives all new ink. */
    var firstSheet: String? = null
        private set

    /**
     * Where each sheet sits on the paper, in canvas units: its page's place, or page 1's for a sheet inside it. Empty for a
     * legacy column and a canvas (every sheet at its own origin). Set by the view from the page layout.
     */
    var placement: Map<String, Placement> = emptyMap()

    /** Sheets of deleted pages: their ink is neither drawn nor selectable. */
    private var deadSheets = emptySet<String>()

    private val handedOffStrokes = mutableListOf<InkPreview>()
    private var handedOffErases = emptySet<String>()
    private var handedOffTransforms = emptyMap<String, Affine>()

    val noteId: String? get() = state.snapshot?.noteId
    val closing: Boolean get() = state.closing
    val unsaved: Boolean get() = state.unsaved

    /** New strokes handed to the repository and not yet in [previews]. */
    val strokeHandoffs: List<InkPreview> get() = handedOffStrokes

    fun load(next: NotesState) {
        state = next
        val snapshot = next.snapshot
        sheets = snapshot?.sheets.orEmpty().associateBy { it.id }
        firstSheet = snapshot?.sheets?.firstOrNull()?.id
        deadSheets = snapshot?.pages.orEmpty().filter { it.tombstone }.map { it.sheetId }.toSet()
        strokes = snapshot?.strokes.orEmpty().associateBy { it.id }
        pendingTransforms = next.pending.flatMap { it.transforms.entries }.associate { it.key to it.value }
        val pendingRestyles = next.pending.flatMap { it.restyles.entries }.associate { it.key to it.value }
        pendingHidden = next.pending.flatMap { it.hidden }.toSet()
        previews = buildList {
            snapshot?.strokes?.forEach { stroke ->
                if (stroke.tombstone || stroke.sheetId in deadSheets) return@forEach
                val samples = next.samples[stroke.id] ?: return@forEach
                add(InkPreview(stroke.id, stroke.sheetId, samples, pendingRestyles[stroke.id] ?: stroke.brush))
            }
            next.pending.forEach { edit ->
                val preview = edit.preview ?: return@forEach
                if (none { it.id == preview.id }) add(preview)
            }
        }
        // Keep eraser bounds only for ink that is still shown.
        val live = IdentityHashMap<SampleChannels, Bounds?>()
        for (preview in previews) if (hitBounds.containsKey(preview.channels)) live[preview.channels] = hitBounds[preview.channels]
        for (preview in handedOffStrokes) if (hitBounds.containsKey(preview.channels)) live[preview.channels] = hitBounds[preview.channels]
        hitBounds = live
        // Drop each handoff once the new state represents it.
        handedOffStrokes.removeAll { handoff -> previews.any { it.id == handoff.id } }
        val tombstoned = snapshot?.strokes.orEmpty().filter { it.tombstone }.map { it.id }
        handedOffErases = handedOffErases - (pendingHidden + tombstoned)
        handedOffTransforms = handedOffTransforms.filter { (id, affine) -> storedTransform(id) != affine }
    }

    fun clearHandoffs() {
        handedOffStrokes.clear()
        handedOffErases = emptySet()
        handedOffTransforms = emptyMap()
    }

    fun handOffStroke(preview: InkPreview) {
        handedOffStrokes += preview
    }

    fun handOffErase(ids: Set<String>) {
        handedOffErases = handedOffErases + ids
    }

    /** Records a committed canvas-space [gesture] as each stroke's resulting sheet-local transform. */
    fun handOffTransform(ids: Set<String>, gesture: Affine) {
        handedOffTransforms = handedOffTransforms + ids.associateWith { id ->
            val sheet = sheets.getValue(strokes.getValue(id).sheetId)
            compose(localGesture(sheet, gesture, offsetOf(sheet.id)), transformOf(id))
        }
    }

    fun hasStrokeHandoff(id: String): Boolean = handedOffStrokes.any { it.id == id }

    fun stroke(id: String): StrokeView? = strokes[id]

    fun samples(id: String): SampleChannels? = state.samples[id]

    /** A saved stroke that has not been deleted. */
    fun isLive(id: String): Boolean = strokes[id]?.tombstone == false

    /** Deleted by a staged edit or a handoff, but not yet tombstoned by the core. */
    fun isHidden(id: String): Boolean = id in pendingHidden || id in handedOffErases

    /** The stroke's sheet-local transform: handoff, then staged edit, then saved. */
    fun transformOf(id: String): Affine = handedOffTransforms[id] ?: storedTransform(id)

    /** The stroke's transform into canvas space: its sheet's fixed origin after its local transform. */
    fun canvasTransformOf(id: String, sheetId: String): Affine {
        val local = transformOf(id)
        return sheets[sheetId]?.let { canvasTransform(it, local, offsetOf(sheetId)) } ?: local
    }

    fun offsetOf(sheetId: String): Placement = placement[sheetId] ?: (0f to 0f)

    /** Sample bounds per stroke for the eraser's cull, by samples identity; pruned on [load]. */
    private var hitBounds = IdentityHashMap<SampleChannels, Bounds?>()
    private val sweepRect = CullRect()

    /** Sample bounds per stroke (by samples identity), so [lowestInk] never rescans samples it has seen. */
    private var sampleBounds = IdentityHashMap<SampleChannels, Bounds?>()

    /**
     * The lowest visible ink edge, including brush radius; 0 for an empty canvas. Each stroke's
     * sample bounds are computed once, so this is O(strokes) per call. The edge comes from the
     * transformed bounds corners, which is exact for the move and scale transforms strokes get.
     */
    fun lowestInk(): Float {
        var lowest = 0f
        val used = IdentityHashMap<SampleChannels, Bounds?>()
        fun visit(preview: InkPreview) {
            val channels = preview.channels
            val bounds = if (sampleBounds.containsKey(channels)) sampleBounds[channels] else StrokeBounds.of(channels, 0f)
            used[channels] = bounds
            if (bounds == null || isHidden(preview.id)) return
            val affine = canvasTransformOf(preview.id, preview.sheetId)
            val radius = brushWidth(preview.brush) / 2 * hypot(affine.b, affine.d).toFloat()
            for ((x, y) in bounds.corners()) lowest = maxOf(lowest, transformedPoint(x, y, affine).second + radius)
        }
        previews.forEach(::visit)
        handedOffStrokes.forEach(::visit)
        sampleBounds = used // forgets strokes that are gone
        return lowest
    }

    /** The topmost visible stroke within [radius] of a canvas point. */
    fun strokeAt(x: Float, y: Float, radius: Float): String? = state.snapshot?.strokes.orEmpty().lastOrNull { stroke ->
        isSelectable(stroke) && hits(stroke) { samples, affine -> hitsStroke(x, y, radius, samples, affine) }
    }?.id

    /** Visible strokes the lasso [polygon] selects; none for fewer than three points. */
    fun strokesInLasso(polygon: List<Pt>): Set<String> {
        if (polygon.size < 3) return emptySet()
        return state.snapshot?.strokes.orEmpty().filter { stroke ->
            isSelectable(stroke) && hits(stroke) { samples, affine -> lassoSelects(samples, affine, polygon) }
        }.map { it.id }.toSet()
    }

    /**
     * Visible strokes, other than [exclude], that the eraser's sweep from [from] to [to] touches: saved ink
     * in snapshot order, then strokes still saving, then strokes just handed off. Ink an earlier edit already
     * erased is skipped, so no stroke is ever deleted twice.
     */
    fun strokesSwept(from: Pt, to: Pt, exclude: Set<String>): List<String> {
        val hit = ArrayList<String>()
        fun test(ink: InkPreview) {
            if (ink.id in exclude || isHidden(ink.id) || ink.id in hit) return
            val reach = ERASER_RADIUS + brushWidth(ink.brush) / 2
            // Cheap first: most strokes lie nowhere near the sweep, so only their placed bounds are compared.
            val bounds = hitBounds.getOrPut(ink.channels) { StrokeBounds.of(ink.channels, 0f) } ?: return
            val affine = canvasTransformOf(ink.id, ink.sheetId)
            sweepRect.set(minOf(from.first, to.first) - reach, minOf(from.second, to.second) - reach,
                maxOf(from.first, to.first) + reach, maxOf(from.second, to.second) + reach)
            if (!StrokeBounds.intersects(bounds, affine, sweepRect)) return
            if (hitsSweep(from, to, reach, ink.channels, affine)) hit += ink.id
        }
        previews.forEach(::test)
        handedOffStrokes.forEach(::test)
        return hit
    }

    private fun storedTransform(id: String): Affine = pendingTransforms[id] ?: strokes[id]?.transform ?: IDENTITY

    private fun isSelectable(stroke: StrokeView) = !stroke.tombstone && stroke.sheetId !in deadSheets && !isHidden(stroke.id)

    private inline fun hits(stroke: StrokeView, test: (SampleChannels, Affine) -> Boolean): Boolean {
        val samples = state.samples[stroke.id] ?: return false
        return test(samples, canvasTransformOf(stroke.id, stroke.sheetId))
    }

    private companion object {
        /** Eraser reach beyond the stroke's own half-width, in canvas units. */
        const val ERASER_RADIUS = 12f
    }
}
