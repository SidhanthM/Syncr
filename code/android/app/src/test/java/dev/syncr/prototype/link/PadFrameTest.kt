package dev.syncr.prototype.link

import dev.syncr.prototype.tablet.PadRect
import dev.syncr.prototype.tablet.tabletFrameGestureAllowed
import org.junit.Assert.*
import org.junit.Test

/** The pad additions of rework-v1 §V: the pen frame's messages and maths, pictures, and the datagram path's framing. */
class PadFrameTest {
    private val area = PadRect(100.0, 50.0, 2000.0, 1000.0)
    private val frame = CanvasRect(600.0, 200.0, 1000.0, 500.0)
    private val view = CanvasRect(0.0, 0.0, 3200.0, 2000.0)

    @Test fun controlMessagesRoundTrip() {
        val target = Control.Target("N1", "S1", true, 7, frame, view, 5200.5)
        assertEquals(target, Control.parse(target.encode()))
        assertEquals(Control.Target("N1", "S1", true, 7), Control.parse(Control.Target("N1", "S1", true, 7).encode()))
        for (c in listOf(Control.PadMode(true, true, 2.05), Control.Frame(frame), Control.Udp(50123, "00ff10aabbccdd01"))) {
            assertEquals(c, Control.parse(c.encode()))
        }
    }

    @Test fun macStyleTargetWithWholeNumbersParses() {
        val json = """{"t":"target","note":"N1","sheet":"S1","writable":true,"rev":3,"frame":{"x":650.5,"y":0,"w":1300,"h":634.6},"view":{"x":-598.2,"y":0,"w":3200,"h":2283.5},"page":{"w":4800,"h":2283.5}}"""
        val t = Control.parse(byteArrayOf(Wire.KIND_CONTROL.toByte()) + json.toByteArray()) as Control.Target
        assertEquals(CanvasRect(650.5, 0.0, 1300.0, 634.6), t.frame)
        assertEquals(-598.2, t.view!!.x, 1e-9)
        assertEquals(2283.5, t.pageHeight!!, 1e-9)
    }

    @Test fun aBrokenAdditionIsIgnoredNotFatal() {
        val json = """{"t":"target","note":"N1","sheet":"S1","writable":true,"rev":3,"frame":{"x":"a","y":0,"w":-1,"h":0},"view":7}"""
        val t = Control.parse(byteArrayOf(Wire.KIND_CONTROL.toByte()) + json.toByteArray()) as Control.Target
        assertNull(t.frame); assertNull(t.view); assertNull(t.pageHeight)
    }

    @Test fun badFrameAndUdpAreRefused() {
        for (json in listOf("""{"t":"frame","x":0,"y":0,"w":0,"h":10}""", """{"t":"frame","x":0,"y":0,"w":1e9,"h":10}""",
            """{"t":"udp","port":0,"key":"00ff10aabbccdd01"}""", """{"t":"udp","port":5000,"key":"XYZ"}""")) {
            try { Control.parse(byteArrayOf(Wire.KIND_CONTROL.toByte()) + json.toByteArray()); fail(json) } catch (_: LinkProtocolException) { }
        }
    }

    @Test fun penMapsThroughTheFrameAndClampsToIt() {
        assertEquals(Pair(600f, 200f), FrameMapping.toCanvas(area, frame, 100.0, 50.0))
        assertEquals(Pair(1100f, 450f), FrameMapping.toCanvas(area, frame, 1100.0, 550.0))
        assertEquals(Pair(1600f, 700f), FrameMapping.toCanvas(area, frame, 9000.0, 9000.0))
        assertEquals(Pair(600f, 200f), FrameMapping.toCanvas(area, frame, -5.0, -5.0))
        assertEquals(1100f, FrameMapping.toPadX(area, frame, 1100.0), 1e-3f)
        assertEquals(550f, FrameMapping.toPadY(area, frame, 450.0), 1e-3f)
    }

    @Test fun twoFingerDragMovesThePageWithTheFingers() {
        // Fingers move 200 px right and 100 px down: the page follows, so the frame goes left and up by the same in canvas units.
        val moved = FrameMapping.gesture(area, frame, 1000.0, 500.0, 1200.0, 600.0, 1.0, view)
        assertEquals(CanvasRect(500.0, 150.0, 1000.0, 500.0), moved)
        // The canvas point that was under the fingers is still under them.
        assertEquals(FrameMapping.toCanvas(area, frame, 1000.0, 500.0), FrameMapping.toCanvas(area, moved, 1200.0, 600.0))
    }

    @Test fun pinchResizesAroundTheFingersAndStaysInBounds() {
        val closer = FrameMapping.gesture(area, frame, 1100.0, 550.0, 1100.0, 550.0, 2.0, view) // fingers apart: magnify
        assertEquals(500.0, closer.w, 1e-9); assertEquals(250.0, closer.h, 1e-9)
        assertEquals(FrameMapping.toCanvas(area, frame, 1100.0, 550.0), FrameMapping.toCanvas(area, closer, 1100.0, 550.0))
        val wider = FrameMapping.gesture(area, frame, 1100.0, 550.0, 1100.0, 550.0, 0.1, view) // pinched: as large as the view allows
        assertEquals(3200.0, wider.w, 1e-9); assertEquals(1600.0, wider.h, 1e-9)
        assertTrue(wider.x >= 0 && wider.right <= FrameMapping.CANVAS_WIDTH && wider.y >= 0)
        val smallest = FrameMapping.gesture(area, frame, 1100.0, 550.0, 1100.0, 550.0, 100.0, view)
        assertEquals(FrameMapping.MIN_WIDTH, smallest.w, 1e-9)
        // Dragging far right or down never takes the frame off the paper's left or top edge.
        val edge = FrameMapping.gesture(area, frame, 1000.0, 500.0, 90000.0, 90000.0, 1.0, view)
        assertEquals(0.0, edge.x, 1e-9); assertEquals(0.0, edge.y, 1e-9)
        assertEquals(1.0, FrameMapping.gesture(area, frame, 0.0, 0.0, 0.0, 0.0, Double.NaN, null).w / frame.w, 1e-9)
    }

    @Test fun gestureNeedsTwoFingersAFrameAndARestedPen() {
        assertTrue(tabletFrameGestureAllowed(local = true, penDown = false, eventTime = 1000, lastPenUp = 600, fingers = 2))
        assertFalse(tabletFrameGestureAllowed(true, penDown = true, eventTime = 1000, lastPenUp = 0, fingers = 2))
        assertFalse(tabletFrameGestureAllowed(true, false, eventTime = 1000, lastPenUp = 800, fingers = 2))
        assertFalse(tabletFrameGestureAllowed(true, false, 1000, 0, fingers = 1))
        assertFalse(tabletFrameGestureAllowed(true, false, 1000, 0, fingers = 3))
        assertFalse(tabletFrameGestureAllowed(local = false, penDown = false, eventTime = 1000, lastPenUp = 0, fingers = 2))
    }

    @Test fun pictureHeaderIsReadAndBadOnesAreRefused() {
        fun f32(v: Float) = java.nio.ByteBuffer.allocate(4).putFloat(v).array()
        val message = byteArrayOf(Wire.KIND_PICTURE.toByte(), 1, 0, 0, 0, 9, 0, 0, 0, 4) + f32(650.5f) + f32(0f) + f32(1300f) + f32(634.5f) + byteArrayOf(-1, -40, 1)
        val h = Wire.parsePicture(message)!!
        assertEquals(9, h.rev); assertEquals(4, h.saved)
        assertEquals(CanvasRect(650.5, 0.0, 1300.0, 634.5), h.region)
        assertTrue(Wire.isKnownKind(Wire.KIND_PICTURE))
        assertNull(Wire.parsePicture(message.copyOf(Wire.PICTURE_HEADER))) // no image
        assertNull(Wire.parsePicture(message.clone().also { it[1] = 9 })) // unknown kind of picture
        assertNull(Wire.parsePicture(byteArrayOf(Wire.KIND_PICTURE.toByte(), 1, 0, 0, 0, 9, 0, 0, 0, 4) + f32(0f) + f32(0f) + f32(0f) + f32(10f) + byteArrayOf(1)))
        assertNull(Wire.parsePicture(byteArrayOf(Wire.KIND_PICTURE.toByte(), 1, 0, 0, 0, 9, 0, 0, 0, 4) + f32(Float.NaN) + f32(0f) + f32(5f) + f32(10f) + byteArrayOf(1)))
    }

    @Test fun datagramCarriesTheKeyTheFirstNumberAndEachFrameWithItsLength() {
        val d = Wire.datagram(0x0102030405060708L, 258, listOf(byteArrayOf(0x50, 1), byteArrayOf(0x50, 2, 3)))!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 1, 2, 0, 2, 0x50, 1, 0, 3, 0x50, 2, 3), d)
        assertNull(Wire.datagram(1, 0, emptyList()))
        assertNull(Wire.datagram(1, 0, listOf(ByteArray(Wire.MAX_DATAGRAM))))
        assertNotNull(Wire.datagram(1, 0, listOf(ByteArray(Wire.MAX_DATAGRAM - 14) { 0x50 })))
    }

    @Test fun sharedVectorsForPagesAndCanvases() {
        val root = Json.parse(java.io.File("../../shared/link-v1/pad-frame-vectors.json").canonicalFile.readBytes()) as Map<*, *>
        fun nums(v: Any?) = (v as List<*>).map { (it as Number).toDouble() }
        val cases = (root["phone_gesture"] as List<*>).map { it as Map<*, *> }
        assertTrue(cases.size >= 4)
        for (c in cases) {
            val a = nums(c["area"]); val st = nums(c["start"]); val f = nums(c["from"]); val t = nums(c["to"]); val vw = nums(c["view"]); val e = nums(c["expect"])
            val got = FrameMapping.gesture(PadRect(a[0], a[1], a[2], a[3]), CanvasRect(st[0], st[1], st[2], st[3]), f[0], f[1], t[0], t[1],
                (c["ratio"] as Number).toDouble(), CanvasRect(vw[0], vw[1], vw[2], vw[3]), unbounded = c["unbounded"] as Boolean)
            assertEquals(c["name"] as String, CanvasRect(e[0], e[1], e[2], e[3]), got)
        }
    }

    @Test fun readOnThePadMessagesAndViewPictures() {
        val read = Control.PadMode(false, true, 2.0, read = true)
        assertEquals(read, Control.parse(read.encode()))
        assertEquals(false, (Control.parse(Control.PadMode(true, true, 2.0).encode()) as Control.PadMode).read)
        assertEquals(mapOf("t" to "scroll", "dx" to -12.5, "dy" to 400.0, "zoom" to 1.25), Control.Scroll(-12.5, 400.0, 1.25).json())
        // A padmode from an older Mac or phone has no mode: Edit.
        val old = Control.parse(byteArrayOf(Wire.KIND_CONTROL.toByte()) + """{"t":"padmode","local":true,"pics":true,"aspect":2}""".toByteArray()) as Control.PadMode
        assertFalse(old.read)
        fun f32(v: Float) = java.nio.ByteBuffer.allocate(4).putFloat(v).array()
        val view = byteArrayOf(Wire.KIND_PICTURE.toByte(), 3, 0, 0, 0, 9, 0, 0, 0, 0) + f32(0f) + f32(100f) + f32(3200f) + f32(2283f) + byteArrayOf(1, 2)
        val h = Wire.parsePicture(view)!!
        assertEquals(3, h.kind); assertEquals(CanvasRect(0.0, 100.0, 3200.0, 2283.0), h.region)
    }
}
