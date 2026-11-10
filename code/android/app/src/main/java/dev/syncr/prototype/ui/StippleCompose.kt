package dev.syncr.prototype.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.syncr.prototype.stipple.Stipple
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug builds only: freeze a dissolve for screenshots, and answer asks from a recorded stream instead of the network. */
internal object StippleDebug {
    /** 0..1 stops every dissolve there; null runs normally. */
    var freeze by mutableStateOf<Float?>(null)
    var stream by mutableStateOf(false)
        internal set

    /** `adb shell am broadcast -a dev.syncr.prototype.DEBUG_STIPPLE --ef freeze 0.5 --ez stream true` (`--ef freeze -1` clears). */
    @Composable
    fun Listen() {
        val context = LocalContext.current
        DisposableEffect(Unit) {
            if (!debugHooksAllowed(context)) return@DisposableEffect onDispose {}
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.hasExtra("freeze")) i.getFloatExtra("freeze", -1f).let { v -> freeze = v.takeIf { it >= 0f }?.coerceAtMost(1f); Stipple.freeze = freeze }
                    if (i.hasExtra("stream")) stream = i.getBooleanExtra("stream", false)
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter("dev.syncr.prototype.DEBUG_STIPPLE"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    /** A recorded answer, played as a stream of small pieces. */
    const val RECORDED = "Voltage is a difference in electric potential between two points.\n\n" +
        "It is negative when the first point sits at a lower potential than the second, so a positive charge would move toward it on its own."
}

/**
 * Shows the content only through the stipple at [progress] (0 nothing, 1 everything), from the top edge down: the
 * companion's dissolve. Drawn as one small mask image per step, so a frame costs one draw call, nothing is laid out.
 */
internal fun Modifier.dissolve(progress: () -> Float): Modifier = composed {
    val cellPx = with(LocalDensity.current) { Motion.STIPPLE_CELL_DP.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var masks by remember { mutableStateOf<List<androidx.compose.ui.graphics.ImageBitmap>?>(null) }
    LaunchedEffect(size) {
        if (size.width < 1 || size.height < 1) return@LaunchedEffect
        masks = withContext(Dispatchers.Default) {
            val columns = Stipple.columns(size.width.toFloat(), cellPx); val rows = Stipple.rows(size.height.toFloat(), cellPx)
            (0..Motion.STIPPLE_FRAMES).map { k ->
                val shown = Stipple.mask(columns, rows, k)
                Bitmap.createBitmap(IntArray(columns * rows) { if (shown[it]) -1 else 0 }, columns, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    this.onSizeChanged { size = it }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        val p = StippleDebug.freeze ?: progress()
        val k = Stipple.frame(p)
        val frames = masks
        when {
            p >= 1f -> drawContent()
            frames == null -> Unit // not built yet: nothing shows rather than everything
            k >= Motion.STIPPLE_FRAMES -> drawContent()
            else -> {
                drawContent()
                drawImage(frames[k], dstSize = IntSize(size.width, size.height), filterQuality = FilterQuality.None, blendMode = BlendMode.DstIn)
            }
        }
    }
}

/** A companion item arriving: dissolves in over [Motion.REVEAL_MS] each time [key] changes to a new value, or shows at once with animations off. */
@Composable
internal fun Modifier.arrives(key: Any?): Modifier {
    val reduced = Syncr.reducedMotion
    val progress = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key, reduced) { if (!reduced) progress.animateTo(1f, Motion.linear(Motion.REVEAL_MS)) }
    return dissolve { progress.value }
}

/** The state of a card that arrives and leaves: dissolves in over [Motion.REVEAL_MS], and back out over [Motion.LEAVE_MS]. */
internal class Presence(private val reduced: Boolean) {
    val progress = Animatable(0f)
    suspend fun arrive() { if (reduced) progress.snapTo(1f) else progress.animateTo(1f, Motion.linear(Motion.REVEAL_MS)) }
    suspend fun leave() { if (reduced) progress.snapTo(0f) else progress.animateTo(0f, Motion.linear(Motion.LEAVE_MS)) }
}

/** Waiting: placeholder lines with a denser band of stipple drifting across them. Still with animations off. */
@Composable
internal fun StippleBand(modifier: Modifier = Modifier, lines: Int = 3) {
    val c = Syncr.colors
    val reduced = Syncr.reducedMotion
    val cellPx = with(LocalDensity.current) { Motion.STIPPLE_CELL_DP.dp.toPx() }
    val phase by rememberInfiniteTransition(label = "drift").animateFloat(0f, 1f,
        Motion.drift(), label = "phase")
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tile by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val lineHeight = 10.dp; val gap = 8.dp
    LaunchedEffect(size) {
        if (size.width < 1) return@LaunchedEffect
        tile = withContext(Dispatchers.Default) {
            val columns = Stipple.columns(size.width.toFloat(), cellPx); val rows = Stipple.rows(size.height.toFloat(), cellPx)
            Bitmap.createBitmap(IntArray(columns * rows) { i -> if (Stipple.threshold(i % columns, i / columns) < Stipple.band((i % columns).toDouble() / columns) * 0.9) -1 else 0 },
                columns, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    Box(modifier.fillMaxWidth().height((lineHeight + gap) * lines).onSizeChanged { size = it }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            val h = lineHeight.toPx(); val g = gap.toPx()
            for (i in 0 until lines) {
                val w = this.size.width * (if (i == lines - 1) 0.55f else 1f - 0.06f * (i % 2))
                drawRoundRect(c.dots, Offset(0f, i * (h + g)), Size(w, h), CornerRadius(h / 2))
            }
            val t = tile ?: return@drawWithContent
            val u = StippleDebug.freeze ?: if (reduced) 0.5f else phase
            // The band crosses the lines once per loop: its tile is as wide as the lines and slides from off the left to off the right.
            val x = ((u * 2f - 1f) * this.size.width).toInt()
            drawImage(t, dstOffset = IntOffset(x, 0), dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
                filterQuality = FilterQuality.None, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c.muted, BlendMode.SrcIn), blendMode = BlendMode.SrcAtop)
        })
}
