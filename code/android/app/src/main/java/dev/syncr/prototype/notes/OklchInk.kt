package dev.syncr.prototype.notes

import dev.syncr.core.Oklch
import dev.syncr.core.oklchGamutMap
import dev.syncr.core.oklchMaxChroma
import dev.syncr.core.oklchToSrgb
import dev.syncr.core.oklchUsesThemeInk
import dev.syncr.prototype.ui.theme.NotePalette

/**
 * Helpers over the core's OKLCH conversion ([dev.syncr.core.Oklch]: l 0..1, c 0..0.37, h 0..360). The
 * core is the only converter; nothing here does colour maths.
 */
object OklchInk {
    const val MAX_CHROMA = 0.37f

    /** The largest chroma sRGB can show at this lightness and hue: the edge of the picker's field. */
    fun maxChroma(l: Float, h: Float): Float = oklchMaxChroma(l, h)

    /** The nearest drawable ink: chroma reduced at fixed lightness and hue until it fits sRGB. */
    fun snapToGamut(ink: Oklch): Oklch = oklchGamutMap(ink)

    /** Whether [ink] is already drawable without reducing chroma (within rounding). */
    fun inGamut(ink: Oklch): Boolean = oklchGamutMap(ink).c >= ink.c - GAMUT_TOLERANCE

    private const val GAMUT_TOLERANCE = 1e-4f
}

/** The swatch colour for this ink, gamut-mapped by the core, exactly as chosen (no theme rule): for the picker and the heads. */
fun Oklch.toArgb(): Int = oklchToSrgb(this).toInt()

/** The colour a stroke in this ink draws with in the given theme: as chosen, except near-black and near-white greys, which take the theme's ink. */
fun Oklch.strokeArgb(dark: Boolean): Int = if (oklchUsesThemeInk(this)) NotePalette.ink(dark) else toArgb()
