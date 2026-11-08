package dev.syncr.prototype.notes

import dev.syncr.core.AnchorTarget
import dev.syncr.core.CompanionBody
import dev.syncr.core.CompanionItemView
import dev.syncr.core.CompanionSource
import dev.syncr.core.InkColour
import dev.syncr.core.MarginKind
import dev.syncr.core.MarginState
import dev.syncr.core.MessageWho
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.PictureDisplayList
import dev.syncr.core.PictureItem
import dev.syncr.core.TextRole
import kotlin.math.max
import kotlin.math.min

/** What the canvas draws under the ink for a note's text and companion content, in canvas units. */
data class ContentTarget(val id: String, val response: Boolean)
internal data class ContentHit(val target: ContentTarget, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height
}
internal class NoteTextScene(val prims: List<Prim>, val pictures: Map<String, PictureDisplayList>, val bottom: Float,
    val hits: List<ContentHit> = emptyList()) {
    companion object { val EMPTY = NoteTextScene(emptyList(), emptyMap(), 0f) }
}

/**
 * Builds [NoteTextScene] from a snapshot with the Mac's default geometry (page at x 0, text column x 100, y 260, width
 * block.width - 200; margin cards at block.width + 40; see `NoteDetailView.showCompanion`). Pure and read-only: a snapshot
 * in, drawing instructions out. [pictureOf] decodes a picture's SVG into the core's display list (null if it cannot).
 */
internal class NoteTextSceneBuilder(private val shaper: Shaper, private val pictureOf: (String) -> PictureDisplayList?) {
    private val typeset = Typeset(shaper)
    private val rich = RichText(shaper)

    fun build(note: NoteSnapshot): NoteTextScene {
        val prims = ArrayList<Prim>()
        val hits = ArrayList<ContentHit>()
        val pictures = HashMap<String, PictureDisplayList>()
        var bottom = 0f
        val document = note.textBlocks.firstOrNull { it.role == TextRole.DOCUMENT && !it.tombstone }
        val items = note.companionItems.filter { !it.tombstone }
        if (document == null && items.isEmpty() && note.textBlocks.none { !it.tombstone }) return NoteTextScene.EMPTY

        val pageWidth = document?.width ?: MacMetrics.DEFAULT_PAGE_WIDTH
        val columnWidth = max(200f, pageWidth - 200f)
        val text = document?.text.orEmpty()
        val byId = items.associateBy { it.id }
        val utf16 = Utf16Offsets(text)
        val inlineRatio = min(1f, (pageWidth - 200f) / 1100f)

        // Pictures first: an embed line in the text holds the room, else the picture waits in the margin.
        val decoded = HashMap<String, PictureDisplayList>()
        for (item in items) (item.body as? CompanionBody.Picture)?.let { body ->
            pictureOf(body.svg)?.let { decoded[item.id] = it }
        }
        val heights = decoded.mapValues { (_, p) -> PictureGeometry.fittedHeight(p) * inlineRatio }

        // Answers sit under the passage they answer: their height becomes space after that paragraph.
        val logical = TextMarkdown.lines(text)
        fun paragraphOf(end: UInt): Int {
            val at = utf16.at(if (end > 0u) end - 1u else 0u)
            return logical.indexOfLast { it.start <= at }.coerceAtLeast(0)
        }
        val threads = threadsByPassage(items, byId)
        val answerCards = HashMap<Int, MutableList<Pair<String, RichResult>>>()
        val gaps = HashMap<Int, Float>()
        for (thread in threads) {
            if (thread.end == null) continue
            val index = paragraphOf(thread.end)
            val card = rich.thread(thread.messages, 0f, 0f, columnWidth)
            answerCards.getOrPut(index) { ArrayList() } += thread.root to card
            gaps[index] = (gaps[index] ?: 0f) + card.height + 40f
        }

        val layout = typeset.document(text, MacMetrics.TEXT_X, MacMetrics.TEXT_Y, columnWidth, heights, gaps)
        if (document != null) {
            val title = note.title
            if (title.isNotEmpty()) prims += Prim.Text(title, MacMetrics.TEXT_X, MacMetrics.TITLE_Y + 9.5f + MacMetrics.ascent(MacMetrics.TITLE_SIZE),
                Attr(size = MacMetrics.TITLE_SIZE, weight = 500, kern = -1.5f / MacMetrics.TITLE_SIZE))
        }
        prims += layout.prims
        bottom = max(bottom, layout.bottom)

        if (document != null) for (line in layout.vlines) {
            hits += ContentHit(ContentTarget(document.id, false), MacMetrics.TEXT_X, line.top, columnWidth, 64f)
        }

        // Answers: under the asked passage, inside the column.
        for ((index, cards) in answerCards) {
            var top = layout.logicalBottoms.getOrNull(index)?.plus(16f) ?: continue
            for ((root, card) in cards) {
                hits += ContentHit(ContentTarget(root, true), MacMetrics.TEXT_X, top, columnWidth, card.height)
                prims += card.moved(MacMetrics.TEXT_X, top)
                top += card.height + 40f
            }
        }

        // Inline pictures at their embed line.
        val placed = HashSet<String>()
        for ((id, picture) in decoded) {
            val embed = "![[syncr-drawing:$id]]"
            val index = logical.indexOfFirst { it.text.trim() == embed }
            if (index < 0) continue
            val y = layout.vlines.firstOrNull { it.logical == index }?.top ?: continue
            val width = PICTURE_WIDTH * inlineRatio
            prims += pictureCard(id, picture, 170f, y, width, width / PICTURE_WIDTH)
            pictures[id] = picture
            placed += id
            bottom = max(bottom, y + heights.getValue(id))
        }

        // Margin notes beside their passage, then pictures with no embed line.
        fun passageTop(item: CompanionItemView): Float {
            val anchor = (item.body as? CompanionBody.MarginNote)?.anchorId?.let(byId::get)?.body as? CompanionBody.Anchor ?: return MacMetrics.TEXT_Y
            return when (val t = anchor.target) {
                is AnchorTarget.Text -> layout.yOf(utf16.at(t.start)) ?: MacMetrics.TEXT_Y
                is AnchorTarget.Canvas -> t.y.toFloat()
            }
        }
        val marginX = pageWidth + MacMetrics.MARGIN_GAP
        var marginBottom = 240f
        val margins = items.filter { it.body is CompanionBody.MarginNote }.sortedWith(compareBy({ passageTop(it) }, { it.id }))
        val tone = tone(note)
        for (item in margins) {
            val body = item.body as CompanionBody.MarginNote
            if (body.state == MarginState.DISMISSED) continue
            // An ask's root note is only the thread's header ("Question", read by "You"); its words are the thread.
            if (body.kind == MarginKind.QUESTION && body.reader == ASK_READER) continue
            val top = max(passageTop(item), marginBottom + 24f)
            val card = rich.margin(body.text, body.sources, marginX, top, tone)
            prims += card.prims
            hits += ContentHit(ContentTarget(item.id, true), marginX, top, 1000f, card.height)
            marginBottom = top + card.height
            bottom = max(bottom, marginBottom)
        }
        for (item in items) {
            val body = item.body as? CompanionBody.Picture ?: continue
            val picture = decoded[item.id] ?: continue
            if (item.id in placed) continue
            var top = marginBottom + 24f
            (byId[body.anchorId]?.body as? CompanionBody.Anchor)?.let { a ->
                (a.target as? AnchorTarget.Text)?.let { top = max(top, layout.yOf(utf16.at(if (it.end > 0u) it.end - 1u else 0u)) ?: top) }
            }
            val ratio = 1000f / PICTURE_WIDTH
            prims += pictureCard(item.id, picture, marginX, top, 1000f, ratio)
            pictures[item.id] = picture
            marginBottom = top + PictureGeometry.fittedHeight(picture) * ratio
            bottom = max(bottom, marginBottom)
        }

        // Free text blocks: the editor sits at the block's origin, its text 45 units lower.
        for (block in note.textBlocks) {
            if (block.role != TextRole.FREE || block.tombstone) continue
            val free = typeset.document(block.text, block.originX + block.transform.tx.toFloat(), block.originY + block.transform.ty.toFloat() + 45f, block.width)
            prims += free.prims
            hits += ContentHit(ContentTarget(block.id, false), block.originX + block.transform.tx.toFloat(),
                block.originY + block.transform.ty.toFloat() + 45f, block.width,
                max(64f, free.bottom - block.originY - block.transform.ty.toFloat() - 45f))
            bottom = max(bottom, free.bottom)
        }
        return NoteTextScene(prims, pictures, bottom + 200f, hits)
    }

    private fun pictureCard(id: String, picture: PictureDisplayList, x: Float, y: Float, width: Float, ratio: Float): List<Prim> {
        val canvasHeight = (PictureGeometry.fittedHeight(picture) - 200f) * ratio
        val out = ArrayList<Prim>()
        out += Prim.Picture(id, x, y, width, canvasHeight)
        val caption = picture.steps.lastOrNull()?.caption.orEmpty()
        if (caption.isNotEmpty()) out += rich.caption(caption, x, y + canvasHeight + 18f * ratio, width, 38f * ratio, "${picture.steps.size} steps")
        return out
    }

    private fun tone(note: NoteSnapshot): Tok = when (note.companionSettings?.settings?.colour) {
        InkColour.RED -> Tok.RED
        InkColour.GREEN -> Tok.GREEN
        InkColour.BLUE -> Tok.BLUE
        InkColour.ORANGE -> Tok.ORANGE
        else -> Tok.MUTED
    }

    private class Thread(val root: String, val end: UInt?, val messages: List<Pair<MessageWho, String>>)

    /** Thread messages grouped by what they hang from: the margin note's anchor, or an anchor itself. */
    private fun threadsByPassage(items: List<CompanionItemView>, byId: Map<String, CompanionItemView>): List<Thread> {
        val groups = items.mapNotNull { it.body as? CompanionBody.ThreadMessage }.groupBy { it.marginId }
        return groups.map { (root, messages) ->
            val rootBody = byId[root]?.body
            val anchor = when (rootBody) {
                is CompanionBody.Anchor -> rootBody
                is CompanionBody.MarginNote -> byId[rootBody.anchorId]?.body as? CompanionBody.Anchor
                else -> null
            }
            val end = (anchor?.target as? AnchorTarget.Text)?.takeIf { !it.detached }?.end
            Thread(root, end, messages.sortedBy { it.order }.map { it.who to it.text })
        }.sortedBy { it.end ?: UInt.MAX_VALUE }
    }

    companion object {
        const val PICTURE_WIDTH = 1100f
        const val ASK_READER = "You" // CompanionStore.askReader on the Mac
    }
}

/** UTF-8 byte offsets (what anchors store) to UTF-16 offsets (what lines use). */
internal class Utf16Offsets(private val text: String) {
    private val map: IntArray by lazy {
        val out = IntArray(text.toByteArray().size + 1)
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val units = Character.charCount(cp)
            val size = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            for (b in 0 until size) out[bytes + b] = i
            bytes += size
            i += units
        }
        out[bytes] = text.length
        out
    }

    fun at(byte: UInt): Int = if (byte.toInt() in 0 until map.size) map[byte.toInt()] else 0
}

/** The Mac's picture card sizing (`PictureCard.fittedHeight`, `PictureRenderer.bounds`) from the core's display list. */
internal object PictureGeometry {
    /**
     * Fit rectangle: points widened by stroke width, text boxes and the core's own conservative item bounds, 8 units of
     * slack. The Mac also unions glyph boxes it measures; those lie inside the core's character-count bounds, which this
     * uses instead of measuring.
     */
    fun bounds(p: PictureDisplayList): FloatArray {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        fun add(x0: Float, y0: Float, x1: Float, y1: Float) { minX = min(minX, x0); minY = min(minY, y0); maxX = max(maxX, x1); maxY = max(maxY, y1) }
        for (item in p.always + p.steps.flatMap { it.items }) when (item) {
            is PictureItem.Polyline -> {
                for (pt in item.points) add(pt.x - item.strokeWidth, pt.y - item.strokeWidth, pt.x + item.strokeWidth, pt.y + item.strokeWidth)
                add(item.bounds.minX, item.bounds.minY, item.bounds.maxX, item.bounds.maxY)
            }
            is PictureItem.Text -> add(item.bounds.minX, item.bounds.minY, item.bounds.maxX, item.bounds.maxY)
        }
        if (minX > maxX) return floatArrayOf(0f, 0f, p.width, p.height)
        return floatArrayOf(minX - 8, minY - 8, maxX + 8, maxY + 8)
    }

    fun fittedHeight(p: PictureDisplayList): Float {
        val b = bounds(p)
        return NoteTextSceneBuilder.PICTURE_WIDTH * (b[3] - b[1]) / max(1f, b[2] - b[0]) + 200f
    }
}
