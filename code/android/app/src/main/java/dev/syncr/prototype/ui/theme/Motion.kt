package dev.syncr.prototype.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
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
    /** Reduced-motion crossfade. */
    const val REDUCED_MS = 120

    /** Shape changes: the bubble's morph and the options tray. Settles in about 300 ms. */
    fun <T> shape(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
    /** Size changes that should feel quicker than the morph (collapsing a tray). */
    fun <T> quick(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    /** The dragged bubble settling after a fling: a little give, so it reads as physical. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)

    /** Something growing into a column (a banner); reduced motion just fades. */
    fun appear(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(REDUCED_MS))
        else expandVertically(shape<IntSize>()) + fadeIn(tween(FADE_MS, delayMillis = 40))

    fun disappear(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(REDUCED_MS))
        else shrinkVertically(quick<IntSize>()) + fadeOut(tween(FADE_MS / 2))

    /** "Remove animations" sets the animator duration scale to 0; honour it as reduced motion. */
    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
