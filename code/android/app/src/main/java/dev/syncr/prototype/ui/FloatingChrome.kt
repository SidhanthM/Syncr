package dev.syncr.prototype.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.syncr.prototype.ui.theme.Motion
import dev.syncr.prototype.ui.theme.Syncr

/**
 * The note's draggable tool bubble. Its centre is stored as fractions of the safe viewport, so it survives
 * rotation and app restarts; until it is moved it sits in the bottom corner on the writing hand's side.
 * The bubble and its bar are one shape ([MorphingBubble]).
 */
@Composable
fun FloatingChrome(open: Boolean, optionsOpen: Boolean, description: String, stateDescription: String,
    onOpen: () -> Unit,
    ring: @Composable () -> Unit,
    glyph: @Composable () -> Unit,
    bar: @Composable BubbleBarScope.(onDrag: (Offset) -> Unit, onRelease: (Offset) -> Unit) -> Unit,
    options: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs by remember(context) { AppPreferencesStore.state(context) }.collectAsState()
    var position by remember { mutableStateOf(Offset(prefs.bubbleX, prefs.bubbleY)) }
    // A reset from Settings (or another window) wins over the local copy.
    LaunchedEffect(prefs.bubbleX, prefs.bubbleY) { position = Offset(prefs.bubbleX, prefs.bubbleY) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val reducedMotion = Syncr.reducedMotion
    val density = LocalDensity.current
    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val radius = with(density) { 32.dp.toPx() } // half the bubble's touch target
    val margin = with(density) { 8.dp.toPx() }
    val leftHanded = prefs.leftHanded
    fun clamp(p: Offset) = Offset(p.x.coerceIn(radius + margin, width - radius - margin),
        p.y.coerceIn(radius + margin, height - radius - margin))
    fun centerOf(p: Offset) = clamp(Offset(
        if (p.x.isNaN()) (if (leftHanded) radius + margin else width - radius - margin) else p.x * width,
        if (p.y.isNaN()) height - radius - margin else p.y * height))
    fun persist(p: Offset) {
        if (p.x.isNaN() || p.y.isNaN()) return
        AppPreferencesStore.update(context) { it.copy(bubbleX = p.x, bubbleY = p.y) }
    }
    fun move(delta: Offset) {
        settle?.cancel()
        val next = clamp(centerOf(position) + delta)
        position = Offset(next.x / width, next.y / height)
    }
    fun release(velocity: Offset) {
        settle?.cancel()
        val start = centerOf(position)
        if (reducedMotion) { persist(position); return }
        val target = clamp(start + velocity * 0.12f)
        settle = scope.launch {
            Animatable(start, Offset.VectorConverter).animateTo(target, Motion.settle(), initialVelocity = velocity) {
                val p = clamp(value)
                position = Offset(p.x / width, p.y / height)
            }
            persist(position)
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().onSizeChanged { viewport = it }) {
        if (width < radius * 2 + margin * 2 || height < radius * 2 + margin * 2) return@Box
        MorphingBubble(open, centerOf(position), description, stateDescription, onOpen, ring, glyph,
            bar = { this.bar(::move, ::release) }, optionsOpen = optionsOpen, options = options,
            onDrag = ::move, onRelease = ::release)
    }
}
