package dev.syncr.prototype.notes

import android.content.Context
import androidx.core.content.edit
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.Oklch
import dev.syncr.core.PenFeel
import dev.syncr.core.PenLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A drawing tool that remembers its own ink and size: the three pens and the highlighter. */
enum class InkTool {
    FOUNTAIN, BALLPOINT, FINELINER, HIGHLIGHTER;

    /** The pen look of a pen tool; null for the highlighter. */
    val look: PenLook? get() = when (this) {
        FOUNTAIN -> PenLook.FOUNTAIN
        BALLPOINT -> PenLook.BALLPOINT
        FINELINER -> PenLook.FINELINER
        HIGHLIGHTER -> null
    }

    companion object {
        fun of(look: PenLook): InkTool = when (look) {
            PenLook.FOUNTAIN -> FOUNTAIN
            PenLook.BALLPOINT -> BALLPOINT
            PenLook.FINELINER -> FINELINER
        }

        /** The tool a stored brush was drawn with. */
        fun of(brush: Brush): InkTool =
            if (brush.family == BrushFamily.HIGHLIGHTER) HIGHLIGHTER else of(brush.look ?: PenLook.FOUNTAIN)
    }
}

/** An ink: one of the five named colours, or an OKLCH colour exactly as the writer chose it. */
sealed interface Ink {
    data class Named(val colour: InkColour) : Ink
    data class Custom(val oklch: Oklch) : Ink
}

/**
 * Local ink preferences.
 *
 * A stroke stores the look, ink and feel it was drawn with ([drawn]), so nothing here ever restyles a
 * stroke that exists: these values apply to the *next* stroke only. [pressure] and [smoothing] are
 * 0..1, where 0.5 is the tuned default.
 *
 * Each tool ([InkTool]) remembers its own ink and size: [inkFor], [sizeFor]; change them with
 * [withInk] and [withSize]. [recentInks] lists the last custom inks used, newest first, at most
 * [MAX_RECENT_INKS].
 */
data class InkPreferences(
    val penLook: PenLook = PenLook.FOUNTAIN,
    val pressure: Float = 0.5f,
    val smoothing: Float = 0.5f,
    val taper: Boolean = true,
    /** Draw a short predicted tail ahead of the pen on the wet layer. Never stored, saved or exported. */
    val predictPenMotion: Boolean = true,
    /** Draw the stroke under the pen straight into the front buffer (experimental; the Ink overlay stays the fallback). */
    val frontBufferInk: Boolean = false,
    val toolInks: Map<InkTool, Ink> = emptyMap(),
    val toolSizes: Map<InkTool, BrushSize> = emptyMap(),
    val recentInks: List<Oklch> = emptyList(),
) {
    /** The ink this tool last used (a named ink until the writer picks otherwise). */
    fun inkFor(tool: InkTool): Ink = toolInks[tool] ?: Ink.Named(defaultColour(tool))

    fun sizeFor(tool: InkTool): BrushSize = toolSizes[tool] ?: BrushSize.MEDIUM

    /** Remembers [ink] for [tool]; a custom ink also moves to the front of [recentInks]. */
    fun withInk(tool: InkTool, ink: Ink): InkPreferences = copy(
        toolInks = toolInks + (tool to ink),
        recentInks = if (ink is Ink.Custom) recentWith(ink.oklch) else recentInks,
    )

    fun withSize(tool: InkTool, size: BrushSize): InkPreferences = copy(toolSizes = toolSizes + (tool to size))

    /** The pen the writer is writing with now, or the highlighter when [highlighter] is set. */
    fun currentTool(highlighter: Boolean = false): InkTool = if (highlighter) InkTool.HIGHLIGHTER else InkTool.of(penLook)

    /** The device feel to store with the next stroke. */
    fun feel(): PenFeel = PenFeel(
        pressure = Math.round(pressure.coerceIn(0f, 1f) * 100f).toUByte(),
        smoothing = Math.round(smoothing.coerceIn(0f, 1f) * 100f).toUByte(),
        taper = taper,
    )

    /** The brush for a stroke written with [tool] right now: its remembered ink and size, the tool's look and the feel. */
    fun brushFor(tool: InkTool): Brush = tool.toBrush().copy(look = tool.look?.takeIf { it != PenLook.FOUNTAIN }, feel = feel())

    /**
     * What a stroke is stored with: [brush]'s family, size and ink, plus this preferences' pen look (Pen
     * only) and feel. The ink is never touched, so the tool bar owns colour.
     */
    fun drawn(brush: Brush): Brush = brush.copy(
        look = if (brush.family == BrushFamily.PEN) penLook.takeIf { it != PenLook.FOUNTAIN } else null,
        feel = feel(),
    )

    private fun InkTool.toBrush(): Brush {
        val family = if (this == InkTool.HIGHLIGHTER) BrushFamily.HIGHLIGHTER else BrushFamily.PEN
        return when (val ink = inkFor(this)) {
            is Ink.Named -> Brush(family, sizeFor(this), ink.colour)
            is Ink.Custom -> Brush(family, sizeFor(this), InkColour.INK, customInk = ink.oklch)
        }
    }

    private fun recentWith(ink: Oklch): List<Oklch> = (listOf(ink) + recentInks.filter { it != ink }).take(MAX_RECENT_INKS)

    companion object {
        const val MAX_RECENT_INKS = 8

        private fun defaultColour(tool: InkTool) = if (tool == InkTool.HIGHLIGHTER) InkColour.ORANGE else InkColour.INK
    }
}

/** The ink a stored brush was drawn with. */
val Brush.ink: Ink get() = customInk?.let { Ink.Custom(it) } ?: Ink.Named(colour)

/** Persistence text for inks, kept pure so it is testable off-device. */
internal object InkCodec {
    fun encode(ink: Ink): String = when (ink) {
        is Ink.Named -> "named:${ink.colour.name}"
        is Ink.Custom -> "oklch:${encode(ink.oklch)}"
    }

    fun encode(c: Oklch): String = "${c.l},${c.c},${c.h}"

    fun decodeInk(text: String?): Ink? {
        val (kind, value) = text?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "named" -> InkColour.entries.firstOrNull { it.name == value }?.let { Ink.Named(it) }
            "oklch" -> decodeOklch(value)?.let { Ink.Custom(it) }
            else -> null
        }
    }

    fun decodeOklch(text: String): Oklch? {
        val parts = text.split(',').map { it.toFloatOrNull() ?: return null }
        if (parts.size != 3) return null
        val (l, c, h) = parts
        val valid = parts.all { it.isFinite() } && l in 0f..1f && c in 0f..0.37f && h in 0f..360f
        return if (valid) Oklch(l, c, h) else null
    }

    fun encodeRecents(list: List<Oklch>): String = list.joinToString(";") { encode(it) }

    fun decodeRecents(text: String?): List<Oklch> =
        text.orEmpty().split(';').filter { it.isNotBlank() }.mapNotNull { decodeOklch(it) }.distinct().take(InkPreferences.MAX_RECENT_INKS)
}

/** Process-wide store over the shared "syncr-settings" preferences, observable by views and Compose. */
object InkPreferencesStore {
    private const val FILE = "syncr-settings"
    private var flow: MutableStateFlow<InkPreferences>? = null

    fun state(context: Context): StateFlow<InkPreferences> = synchronized(this) {
        flow ?: MutableStateFlow(read(context)).also { flow = it }
    }.asStateFlow()

    fun update(context: Context, change: (InkPreferences) -> InkPreferences) {
        val next = change(state(context).value).let {
            it.copy(
                pressure = it.pressure.coerceIn(0f, 1f), smoothing = it.smoothing.coerceIn(0f, 1f),
                recentInks = it.recentInks.take(InkPreferences.MAX_RECENT_INKS),
            )
        }
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString("ink-pen-look", next.penLook.name)
            putFloat("ink-pressure", next.pressure)
            putFloat("ink-smoothing", next.smoothing)
            putBoolean("ink-taper", next.taper)
            putBoolean("ink-predict", next.predictPenMotion)
            putBoolean("ink-front-buffer", next.frontBufferInk)
            InkTool.entries.forEach { tool ->
                next.toolInks[tool]?.let { putString("ink-tool-${tool.name}-ink", InkCodec.encode(it)) }
                next.toolSizes[tool]?.let { putString("ink-tool-${tool.name}-size", it.name) }
            }
            putString("ink-recent", InkCodec.encodeRecents(next.recentInks))
        }
        synchronized(this) { flow!!.value = next }
    }

    private fun read(context: Context): InkPreferences {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val defaults = InkPreferences()
        return InkPreferences(
            penLook = PenLook.entries.firstOrNull { it.name == prefs.getString("ink-pen-look", null) } ?: defaults.penLook,
            pressure = prefs.getFloat("ink-pressure", defaults.pressure),
            smoothing = prefs.getFloat("ink-smoothing", defaults.smoothing),
            taper = prefs.getBoolean("ink-taper", defaults.taper),
            predictPenMotion = prefs.getBoolean("ink-predict", defaults.predictPenMotion),
            frontBufferInk = prefs.getBoolean("ink-front-buffer", defaults.frontBufferInk),
            toolInks = InkTool.entries.mapNotNull { tool ->
                InkCodec.decodeInk(prefs.getString("ink-tool-${tool.name}-ink", null))?.let { tool to it }
            }.toMap(),
            toolSizes = InkTool.entries.mapNotNull { tool ->
                BrushSize.entries.firstOrNull { it.name == prefs.getString("ink-tool-${tool.name}-size", null) }?.let { tool to it }
            }.toMap(),
            recentInks = InkCodec.decodeRecents(prefs.getString("ink-recent", null)),
        )
    }
}
