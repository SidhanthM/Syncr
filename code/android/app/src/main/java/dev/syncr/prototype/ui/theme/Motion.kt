package dev.syncr.prototype.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntSize

/**
 * One motion vocabulary. Everything moves on critically damped springs (no overshoot) or short fades;
 * with reduced motion ([Syncr.reducedMotion]) the same changes become quick crossfades.
 */
object Motion {
    /** Fades for things appearing in place (banners, options). */
    const val FADE_MS = 160
    // The companion's stipple (editor-v2 §8), the Mac's `Motion` values. Only what the companion makes uses it; the owner's own
    // menus, toolbar and typing use [MENU_MS] or nothing.
    /** A companion card, picture or answer dissolving in from its top edge down. */
    const val REVEAL_MS = 420
    /** The same dissolve run backwards when something is dismissed, replaced or undone: two thirds of the time. */
    const val LEAVE_MS = REVEAL_MS * 2 / 3
    /** One loop of the waiting band across placeholder lines. */
    const val DRIFT_MS = 1500
    /** A dissolve does not start on the page while the pen is down or was active this recently. */
    const val PEN_QUIET_MS = 150L
    /** Images a dissolve steps through. */
    const val STIPPLE_FRAMES = 14
    /** A stipple cell, in dp, on every screen and at every zoom. */
    const val STIPPLE_CELL_DP = 3f

    /** The sticky title band dissolving in or out. */
    const val TITLE_MS = 200

    /** The slash menu and other plain panels of the owner's own actions: a fade, never the stipple. */
    const val MENU_MS = 120
    /** Reduced-motion crossfade. */
    const val REDUCED_MS = 120

    /** Shape changes: the bubble's morph and the options tray. Settles in about 300 ms. */
    fun <T> shape(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
    /** Size changes that should feel quicker than the morph (collapsing a tray). */
    fun <T> quick(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    /** The dragged bubble settling after a fling: a little give, so it reads as physical. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)

    /** [shape] and [quick] with animations off: the value just changes. */
    fun <T> shape(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else shape()
    fun <T> quick(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else quick()

    /** The press fill arriving under a finger (quick) and leaving it (a fade). */
    fun <T> press(down: Boolean, reduced: Boolean = false): FiniteAnimationSpec<T> = tween(if (reduced) REDUCED_MS else if (down) MENU_MS else FADE_MS)

    /** A plain fade, [FADE_MS] (or [REDUCED_MS] with animations off), for things appearing or leaving in place. */
    fun <T> fade(reduced: Boolean = false): FiniteAnimationSpec<T> = tween(if (reduced) REDUCED_MS else FADE_MS)
    /** The owner's own panels (menus, popovers, the slash menu, the editor toolbar): a fade of [MENU_MS]. */
    fun <T> menu(reduced: Boolean = false): FiniteAnimationSpec<T> = tween(if (reduced) REDUCED_MS else MENU_MS)
    fun menuIn(reduced: Boolean = false): EnterTransition = fadeIn(menu(reduced))
    fun menuOut(reduced: Boolean = false): ExitTransition = fadeOut(menu(reduced))
    fun fadeInOnly(reduced: Boolean = false): EnterTransition = fadeIn(fade(reduced))
    fun fadeOutOnly(reduced: Boolean = false): ExitTransition = fadeOut(fade(reduced))
    /** A dialog: the menu fade and a slight scale up from 0.96. With animations off, the fade alone. */
    fun dialogIn(reduced: Boolean = false): EnterTransition =
        if (reduced) menuIn(true) else fadeIn(menu()) + scaleIn(shape(), initialScale = 0.96f)
    /** A page of a sheet (Settings) arriving from the right, and the page below leaving to the left. */
    fun pageIn(reduced: Boolean, back: Boolean): EnterTransition =
        if (reduced) fadeIn(menu(true)) else slideInHorizontally(shape()) { if (back) -it / 4 else it } + fadeIn(menu())
    fun pageOut(reduced: Boolean, back: Boolean): ExitTransition =
        if (reduced) fadeOut(menu(true)) else slideOutHorizontally(shape()) { if (back) it else -it / 4 } + fadeOut(menu())
    /** The companion's dissolve, run on a straight line over [ms]. */
    fun <T> linear(ms: Int): FiniteAnimationSpec<T> = tween(ms, easing = LinearEasing)
    /** The waiting band's loop. */
    fun <T> drift(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing), RepeatMode.Restart)
    /** The saving ring's pulse: [halfMs] each way. */
    fun <T> pulse(halfMs: Int): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(halfMs, easing = FastOutSlowInEasing), RepeatMode.Reverse)

    /** A pen head drawing itself on, once: [PEN_DRAW_MS], ease-out. */
    const val PEN_DRAW_MS = 260
    fun <T> penDraw(): FiniteAnimationSpec<T> = tween(PEN_DRAW_MS, easing = LinearOutSlowInEasing)
    /** The ink picker's stipple dissolve in and out (a straight line through the dissolve's own steps). */
    const val PICKER_IN_MS = 240
    const val PICKER_OUT_MS = 180
    fun <T> pickerIn(): FiniteAnimationSpec<T> = linear(PICKER_IN_MS)
    fun <T> pickerOut(): FiniteAnimationSpec<T> = linear(PICKER_OUT_MS)

    /** Something growing into a column (a banner); reduced motion just fades. */
    fun appear(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(menu(true))
        else expandVertically(shape<IntSize>()) + fadeIn(tween(FADE_MS, delayMillis = 40))

    fun disappear(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(REDUCED_MS))
        else shrinkVertically(quick<IntSize>()) + fadeOut(tween(FADE_MS / 2))

    /** "Remove animations" sets the animator duration scale to 0; honour it as reduced motion. */
    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
