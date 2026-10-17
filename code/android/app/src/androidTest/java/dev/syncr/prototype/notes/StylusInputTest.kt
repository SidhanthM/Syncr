package dev.syncr.prototype.notes

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusInputTest {
    private data class Pointer(val id: Int, val tool: Int, val x: Float, val y: Float)
    // y = 400 lies on the first page (below the top padding) at any test density.
    private fun finger(id: Int = 7, x: Float = 100f, y: Float = 400f) = Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y)
    private fun pen(id: Int = 42, x: Float = 100f, y: Float = 400f) = Pointer(id, MotionEvent.TOOL_TYPE_STYLUS, x, y)
    private fun event(action: Int, time: Long, vararg pointers: Pointer): MotionEvent = MotionEvent.obtain(
        100L, time, action, pointers.size,
        pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.tool } }.toTypedArray(),
        pointers.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = .7f; size = .1f } }.toTypedArray(),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    private fun send(view: NoteCanvasView, action: Int, time: Long, vararg pointers: Pointer) {
        event(action, time, *pointers).useEvent { view.onTouchEvent(it) }
    }
    private fun MotionEvent.useEvent(block: (MotionEvent) -> Unit) { try { block(this) } finally { recycle() } }
    private fun paper(): NoteCanvasView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NoteCanvasView(context).apply {
            layout(0, 0, 1600, 1000)
            update(NotesState(snapshot = NoteSnapshot("note", "Test", null, PageFormat.SHEET16X10, 1600f, 1000f,
                EntityHead("create", null), EntityHead("create", null), listOf(SheetView("sheet", "create")), emptyList(), 1u,
                EventCursor("stream", 1u), null, null, emptyList(), emptyList())), false)
        }
    }
    private fun pageX(view: NoteCanvasView, screenX: Float) = view.pageMapping(0)!!.toSheet(screenX, 400f).first
    @Test fun fingersNeverInkAndStableStylusIdWinsOverPalmNavigation() = onMain {
        val view = paper(); val completed = mutableListOf<SampleChannels>(); val sheets = mutableListOf<String>()
        view.onStroke = { sheet, samples, _ -> sheets += sheet; completed += samples; null }
        send(view, MotionEvent.ACTION_DOWN, 100, finger())
        send(view, MotionEvent.ACTION_MOVE, 110, finger(x = 120f))
        send(view, MotionEvent.ACTION_UP, 120, finger(x = 120f))
        assertTrue(completed.isEmpty())
        send(view, MotionEvent.ACTION_DOWN, 200, finger())
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 210, finger(), pen())
        send(view, MotionEvent.ACTION_MOVE, 220, finger(x = 900f), pen(x = 120f, y = 130f))
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 230, finger(x = 950f), pen(x = 140f, y = 150f))
        assertEquals(1, completed.size); assertEquals(listOf("sheet"), sheets)
        assertEquals(listOf(100f, 120f, 140f).map { pageX(view, it) }, completed.single().x)
        assertEquals(listOf(0u, 10u, 20u), completed.single().elapsedMs)
        // Remaining palm must not jump viewport after pen-up.
        send(view, MotionEvent.ACTION_MOVE, 240, finger(x = 1200f))
        send(view, MotionEvent.ACTION_UP, 250, finger(x = 1200f))
        send(view, MotionEvent.ACTION_DOWN, 260, pen())
        send(view, MotionEvent.ACTION_UP, 270, pen())
        assertEquals(pageX(view, 100f), completed.last().x.first(), .001f)
        // Palm rule v1: a finger landing within 300 ms of pen-up neither pans nor taps.
        val before = view.pageMapping(0)
        send(view, MotionEvent.ACTION_DOWN, 400, finger(y = 600f))
        send(view, MotionEvent.ACTION_MOVE, 410, finger(y = 100f))
        send(view, MotionEvent.ACTION_UP, 420, finger(y = 100f))
        assertEquals(before, view.pageMapping(0))
        // Pen-down in the gap above the first page never inks.
        send(view, MotionEvent.ACTION_DOWN, 1000, pen(y = 1f)); send(view, MotionEvent.ACTION_UP, 1010, pen(y = 1f))
        assertEquals(2, completed.size)
    }
    @Test fun historicalSamplesAreOldestFirstAndCancelBackgroundOrLostContactNeverSave() = onMain {
        val view = paper(); val completed = mutableListOf<SampleChannels>()
        view.onStroke = { _, samples, _ -> completed += samples; null }
        send(view, MotionEvent.ACTION_DOWN, 100, pen(x = 110f))
        event(MotionEvent.ACTION_MOVE, 110, pen(x = 120f)).useEvent { move ->
            move.addBatch(120, arrayOf(MotionEvent.PointerCoords().apply { x = 130f; y = 400f; pressure = .8f }), 0)
            view.onTouchEvent(move)
        }
        send(view, MotionEvent.ACTION_UP, 130, pen(x = 140f))
        assertEquals(listOf(110f, 120f, 130f, 140f).map { pageX(view, it) }, completed.single().x)
        assertEquals(listOf(0u, 10u, 20u, 30u), completed.single().elapsedMs)
        send(view, MotionEvent.ACTION_DOWN, 200, pen()); send(view, MotionEvent.ACTION_CANCEL, 210, pen())
        send(view, MotionEvent.ACTION_DOWN, 300, pen()); view.cancelGesture() // lifecycle pause
        send(view, MotionEvent.ACTION_UP, 310, pen())
        send(view, MotionEvent.ACTION_DOWN, 400, pen()); send(view, MotionEvent.ACTION_MOVE, 410, finger())
        send(view, MotionEvent.ACTION_UP, 420, finger())
        assertEquals(1, completed.size)
    }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
