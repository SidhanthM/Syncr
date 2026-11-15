package dev.syncr.prototype.notes

import dev.syncr.core.AnchorTarget
import dev.syncr.core.CompanionBody
import dev.syncr.core.CompanionItemView
import dev.syncr.core.CompanionSource
import dev.syncr.core.InkColour
import dev.syncr.core.MarginAnchor
import dev.syncr.core.MarginInput
import dev.syncr.core.MarginItem
import dev.syncr.core.MarginItemKind
import dev.syncr.core.MarginKind
import dev.syncr.core.MarginLine
import dev.syncr.core.MarginPolicy
import dev.syncr.core.MarginState
import dev.syncr.core.MessageWho
import dev.syncr.core.NoteSnapshot
import dev.syncr.core.PictureDisplayList
import dev.syncr.core.PictureItem
import dev.syncr.core.TextRole
import dev.syncr.core.layoutMargins
import kotlin.math.max
import kotlin.math.min

internal data class ItemBox(val x: Float, val y: Float, val w: Float, val h: Float)

internal data class TaskHit(val blockId: String, val line: Int, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px >= x - 8 && px <= x + w + 8 && py >= y - 8 && py <= y + h + 8
}

/** What the canvas draws under the ink for a note's text and companion content, in canvas units. */
data class ContentTarget(val id: String, val response: Boolean)
internal data class ContentHit(val target: ContentTarget, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height
}
internal class NoteTextScene(val prims: List<Prim>, val pictures: Map<String, PictureDisplayList>, val bottom: Float,
    val hits: List<ContentHit> = emptyList(),
    /** Room the document gives its lines beyond the words, by logical line: after answers, and for picture embeds. */
    val gaps: Map<Int, Float> = emptyMap(), val embeds: Map<Int, Float> = emptyMap(), val documentText: String = "",
    /** The rendered task boxes, to flip with a tap (T18). */
    val tasks: List<TaskHit> = emptyList(),
    /** Where each companion item (answer, margin note, picture) is, by ID, to dissolve it in when it arrives. */
    val items: Map<String, ItemBox> = emptyMap(),
    /** The document's visual lines, for a tap on empty paper to find the caret's place. */
    val lines: List<TextLine> = emptyList()) {
    companion object { val EMPTY = NoteTextScene(emptyList(), emptyMap(), 0f) }
}

/** What the title band shows: the title as read (the default stamp humanised) and the quiet line under it. */
internal data class TitleDisplay(val text: String, val meta: String)

/**
 * Builds [NoteTextScene] from a snapshot with the Mac's default geometry (page at x 0, text column x 100, y 260, width
 * block.width - 200; margin cards placed by the core's shared margin layout; see `NoteDetailView.showCompanion`). Pure and read-only: a snapshot
 * in, drawing instructions out. [pictureOf] decodes a picture's SVG into the core's display list (null if it cannot).
 */
internal class NoteTextSceneBuilder(private val shaper: Shaper, private val pictureOf: (String) -> PictureDisplayList?) {
    private val typeset = Typeset(shaper)
    private val rich = RichText(shaper)

    /**
     * The page's title band, drawn only; nothing below it moves (the text column starts at [MacMetrics.TEXT_Y] as on the Mac).
     * A display-size title in ink with tight tracking (cut to one line with an ellipsis), a quiet letter-spaced meta line
     * under it, and a short salmon rule as the only accent.
     */
    private val TITLE_BAND_TOP = 56f

    private fun titleBand(title: String, meta: String, width: Float): List<Prim> {
        val out = ArrayList<Prim>()
        val x = MacMetrics.TEXT_X
        if (title.isNotEmpty()) {
            val size = MacMetrics.PHONE_TITLE_SIZE
            val attr = Attr(size = size, weight = 600, kern = -0.03f)
            val first = shaper.shape(title, listOf(Run(0, title.length, attr)), width).first()
            val shown = if (first.end < title.length) title.substring(0, first.end).trimEnd() + "…" else title
            out += Prim.Text(shown, x, TITLE_BAND_TOP + MacMetrics.ascent(size), attr)
        }
        if (meta.isNotEmpty()) {
            val size = MacMetrics.PHONE_META_SIZE
            out += Prim.Text(meta.uppercase(), x + 2f, 172f, Attr(size = size, weight = 500, tok = Tok.MUTED, kern = 0.1f))
        }
        if (title.isNotEmpty()) out += Prim.Fill(x + 2f, 190f, 72f, 6f, 3f, Tok.ACCENT)
        return out
    }

    /** [hide] is the ID of a text block being edited in the field above the page: its own words are left out, the rest is built as usual. */
    fun build(note: NoteSnapshot, hide: String? = null, title: TitleDisplay? = null): NoteTextScene {
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
            prims += titleBand(title?.text ?: note.title, title?.meta.orEmpty(), columnWidth)
        }
        if (document?.id != hide) prims += layout.prims
        bottom = max(bottom, layout.bottom)

        val taskHits = ArrayList<TaskHit>()
        if (document != null) layout.tasks.forEach { taskHits += TaskHit(document.id, it.logical, it.x, it.y, it.w, it.h) }
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

        // Margin notes beside their passage, then pictures with no embed line: measured here, placed by the core in one
        // call (the same rules and vectors as the Mac; the phone's policy keeps the right lane only).
        fun noteAnchor(item: CompanionItemView): MarginAnchor {
            val anchor = (item.body as? CompanionBody.MarginNote)?.anchorId?.let(byId::get)?.body as? CompanionBody.Anchor ?: return MarginAnchor.None
            return when (val t = anchor.target) {
                is AnchorTarget.Text -> MarginAnchor.Text(utf16.at(t.start).toUInt())
                is AnchorTarget.Canvas -> MarginAnchor.Canvas(t.y.toFloat())
            }
        }
        val tone = tone(note)
        val marginItems = ArrayList<MarginItem>()
        val marginCards = HashMap<String, RichResult>()
        val marginPictures = HashMap<String, PictureDisplayList>()
        val pictureRatio = MARGIN_CARD_WIDTH / PICTURE_WIDTH
        for (item in items) {
            val body = item.body as? CompanionBody.MarginNote ?: continue
            if (body.state == MarginState.DISMISSED) continue
            // An ask's root note is only the thread's header ("Question", read by "You"); its words are the thread.
            if (body.kind == MarginKind.QUESTION && body.reader == ASK_READER) continue
            val card = rich.margin(body.text, body.sources, 0f, 0f, tone)
            marginCards[item.id] = card
            marginItems += MarginItem(item.id, MarginItemKind.NOTE, noteAnchor(item), card.height)
        }
        for (item in items) {
            val body = item.body as? CompanionBody.Picture ?: continue
            val picture = decoded[item.id] ?: continue
            if (item.id in placed) continue
            val end = ((byId[body.anchorId]?.body as? CompanionBody.Anchor)?.target as? AnchorTarget.Text)?.end
            val at = if (end != null) MarginAnchor.Text(utf16.at(if (end > 0u) end - 1u else 0u).toUInt()) else MarginAnchor.None
            marginPictures[item.id] = picture
            marginItems += MarginItem(item.id, MarginItemKind.PICTURE, at, PictureGeometry.fittedHeight(picture) * pictureRatio)
        }
        val lanes = layoutMargins(MarginInput(MarginPolicy.PHONE, MacMetrics.TEXT_X, pageWidth - 100f, MARGIN_CARD_WIDTH,
            MacMetrics.MARGIN_GAP, MacMetrics.MARGIN_LANE_TOP, MacMetrics.MARGIN_SPACING, MacMetrics.TEXT_Y,
            layout.vlines.map { MarginLine(it.start.toUInt(), it.end.toUInt(), it.top) }, marginItems))
        for (spot in lanes.placements) {
            val card = marginCards[spot.id]
            if (card != null) {
                prims += card.moved(spot.x, spot.y)
                hits += ContentHit(ContentTarget(spot.id, true), spot.x, spot.y, spot.width, spot.height)
                continue
            }
            val picture = marginPictures[spot.id] ?: continue
            prims += pictureCard(spot.id, picture, 0f, 0f, MARGIN_CARD_WIDTH, pictureRatio).map { it.shifted(spot.x, spot.y) }
            pictures[spot.id] = picture
        }
        bottom = max(bottom, lanes.bottom)

        // Free text blocks: the editor sits at the block's origin, its text 45 units lower.
        for (block in note.textBlocks) {
            if (block.role != TextRole.FREE || block.tombstone) continue
            val free = typeset.document(block.text, block.originX + block.transform.tx.toFloat(), block.originY + block.transform.ty.toFloat() + 45f, block.width)
            if (block.id != hide) prims += free.prims
            free.tasks.forEach { taskHits += TaskHit(block.id, it.logical, it.x, it.y, it.w, it.h) }
            hits += ContentHit(ContentTarget(block.id, false), block.originX + block.transform.tx.toFloat(),
                block.originY + block.transform.ty.toFloat() + 45f, block.width,
                max(64f, free.bottom - block.originY - block.transform.ty.toFloat() - 45f))
            bottom = max(bottom, free.bottom)
        }
        val embeds = HashMap<Int, Float>()
        for ((id, height) in heights) {
            val index = logical.indexOfFirst { it.text.trim() == "![[syncr-drawing:$id]]" }
            if (index >= 0) embeds[index] = height
        }
        val boxes = HashMap<String, ItemBox>()
        for (h in hits) if (h.target.response) boxes[h.target.id] = ItemBox(h.x, h.y, h.width, h.height)
        for (p in prims) if (p is Prim.Picture) boxes[p.key] = ItemBox(p.x, p.y, p.w, p.h)
        val lines = layout.vlines.mapIndexed { i, v ->
            val next = layout.vlines.getOrNull(i + 1)
            // A line the column wraps ends before the space it broke at; the last line of the text claims only its own height.
            val end = if (next != null && next.logical == v.logical) text.substring(v.start, v.end).trimEnd(' ', '\t').length + v.start else v.end
            TextLine(v.start, end, v.top, next?.top ?: v.used)
        }
        return NoteTextScene(prims, pictures, bottom + 200f, hits, gaps, embeds, text, taskHits, boxes, lines)
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
        /** Margin cards are this wide (`MarginCard.width` on the Mac). */
        const val MARGIN_CARD_WIDTH = 1000f
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
