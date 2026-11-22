package dev.syncr.prototype.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.NoteKind
import dev.syncr.core.SampleChannels
import dev.syncr.core.newId
import dev.syncr.prototype.notes.InkPreview
import kotlin.math.cos
import kotlin.math.sin

/**
 * Emulator-only broadcast hooks for the canvas (the same gate as the other debug hooks, see [debugHooksAllowed]):
 * `DEBUG_NEW_CANVAS` creates and opens a canvas, and `DEBUG_FILL_CANVAS --ei strokes N` writes N small strokes over a wide
 * area into the open canvas through the normal save path, to measure drawing with thousands of strokes.
 */
@Composable
internal fun CanvasDebugHooks(vm: NotesViewModel) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        if (!debugHooksAllowed(context)) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    NEW -> vm.run { vm.repository.create(intent.getStringExtra("name") ?: "Canvas", null, NoteKind.CANVAS) }
                    FILL -> vm.run { fill(vm, intent.getIntExtra("strokes", 5000), intent.getIntExtra("seed", 1)) }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(NEW); addAction(FILL) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

private const val NEW = "dev.syncr.prototype.DEBUG_NEW_CANVAS"
private const val FILL = "dev.syncr.prototype.DEBUG_FILL_CANVAS"

private suspend fun fill(vm: NotesViewModel, count: Int, seed: Int) {
    val repo = vm.repository
    val sheet = repo.state.value.snapshot?.sheets?.firstOrNull()?.id ?: return
    val rnd = java.util.Random(seed.toLong())
    val brush = Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)
    var written = 0
    while (written < count) {
        val batch = minOf(40, count - written)
        repeat(batch) {
            // A short curve at a random place in a 60,000 x 40,000 unit area, 24 samples like a quick pen stroke.
            val x0 = rnd.nextFloat() * 60_000f - 30_000f
            val y0 = rnd.nextFloat() * 40_000f - 20_000f
            val r = 40f + rnd.nextFloat() * 160f
            val a = rnd.nextFloat() * 6.28f
            val n = 24
            val samples = SampleChannels(List(n) { x0 + r * cos(a + it * 0.12f) }, List(n) { y0 + r * sin(a + it * 0.12f) },
                List(n) { 0.5f }, List(n) { it.toUInt() * 8u }, null, null)
            repo.stageStroke(InkPreview(newId(), sheet, samples, brush))
        }
        repo.retry()
        written += batch
    }
}
