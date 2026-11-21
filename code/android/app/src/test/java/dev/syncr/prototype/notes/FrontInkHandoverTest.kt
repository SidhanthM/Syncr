package dev.syncr.prototype.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Front-buffer pad (contract F1 damage, F2 handover and joining, F3 path choice, F4 stats): the pure parts. */
class FrontInkHandoverTest {
    private val view = PadView(SheetMapping(1f, 10f, 20f), 1000, 2000)
    private val panned = PadView(SheetMapping(1f, 10f, -300f), 1000, 2000)

    @Test fun directInkIsOffByDefault() = assertFalse(InkPreferences().frontBufferInk)

    // ---- path choice ----
    @Test fun padOnlyWhenEverythingAllowsIt() {
        assertEquals(WetPath.PAD, chooseWetPath(true, true, true, true, true))
    }
    @Test fun settingOffIsTheBridge() = assertEquals(WetPath.BRIDGE, chooseWetPath(false, true, true, true, true))
    @Test fun failedProbeIsTheBridge() = assertEquals(WetPath.BRIDGE, chooseWetPath(true, false, true, true, true))
    @Test fun rotatedViewIsTheBridge() = assertEquals(WetPath.BRIDGE, chooseWetPath(true, true, false, true, true))
    @Test fun highlighterOrTranslucentIsTheBridge() = assertEquals(WetPath.BRIDGE, chooseWetPath(true, true, true, false, true))
    @Test fun padLettingGoIsTheBridge() = assertEquals(WetPath.BRIDGE, chooseWetPath(true, true, true, true, false))

    // ---- damage ----
    private val clip = PxRect(0, 0, 1000, 2000)
    @Test fun damageIsBothTailsGrownByHalfWidthAndFringe() {
        val prev = PxBox(100f, 100f, 110f, 110f)
        val cur = PxBox(120f, 105f, 130f, 112f)
        val d = FrontInkDamage.rect(prev, cur, 3f, clip)!!
        assertEquals(PxRect(95, 95, 135, 117), d) // 100-5, 100-5, 130+5, 112+5
    }
    @Test fun aRetractedTailStillRepaintsWhereItWas() {
        val d = FrontInkDamage.rect(PxBox(200f, 200f, 260f, 210f), PxBox(200f, 200f, 220f, 210f), 0f, clip)!!
        assertTrue(d.right >= 262)
    }
    @Test fun damageIsCutToTheClip() {
        val d = FrontInkDamage.rect(null, PxBox(-50f, 10f, 20f, 3000f), 4f, PxRect(5, 0, 900, 1500))!!
        assertEquals(PxRect(5, 4, 26, 1500), d)
    }
    @Test fun noGeometryOrOffClipIsNoDamage() {
        assertNull(FrontInkDamage.rect(null, null, 3f, clip))
        assertNull(FrontInkDamage.rect(null, PxBox(2000f, 0f, 2100f, 10f), 2f, clip))
    }

    // ---- handover and joining ----
    private fun held(h: FrontInkHandover, vararg ids: String) {
        for (id in ids) {
            assertNotNull(h.penDown(view))
            h.penUp(id)
        }
    }

    @Test fun aFinishedStrokeIsFinishingUntilTheDryLayerMayDrawIt() {
        val h = FrontInkHandover()
        assertEquals(PadPen.FRESH, h.penDown(view))
        h.penUp("a")
        assertTrue(h.isFinishing("a"))
        assertTrue(h.strokeReady("a"))
        assertTrue(h.idleElapsed(h.generation))
        assertFalse(h.isFinishing("a")) // released: the dry layer draws it, the pad still shows it
        assertEquals(listOf("a"), h.releasingIds())
        assertTrue(h.cleared(h.generation))
        assertFalse(h.isFinishing("a"))
        assertEquals(FrontInkHandover.Phase.IDLE, h.phase)
    }
    @Test fun aPenDownWhileWaitingJoinsAndNothingIsReleased() {
        val h = FrontInkHandover()
        held(h, "a")
        h.strokeReady("a")
        val armed = h.generation
        assertEquals(PadPen.JOINED, h.penDown(view))
        assertFalse(h.idleElapsed(armed)) // the idle wait armed before the join is stale
        h.penUp("b")
        assertTrue(h.isFinishing("a") && h.isFinishing("b"))
    }
    @Test fun idleReleasesEveryJoinedStrokeWithOneClear() {
        val h = FrontInkHandover()
        held(h, "a", "b", "c")
        assertFalse(h.strokeReady("a"))
        assertFalse(h.strokeReady("b"))
        assertTrue(h.strokeReady("c"))
        assertTrue(h.idleElapsed(h.generation))
        assertEquals(listOf("a", "b", "c"), h.releasingIds())
        val gen = h.generation
        assertTrue(h.cleared(gen))
        assertFalse(h.cleared(gen)) // one clear
        assertEquals(0, h.waitingCount)
    }
    @Test fun notIdleWhileADryStrokeIsStillBuilding() {
        val h = FrontInkHandover()
        held(h, "a", "b")
        h.strokeReady("a")
        assertFalse(h.idleElapsed(h.generation))
        assertTrue(h.strokeReady("b"))
        assertTrue(h.idleElapsed(h.generation))
    }
    @Test fun noIdleHandoverWhileThePenIsDown() {
        val h = FrontInkHandover()
        held(h, "a")
        h.strokeReady("a")
        val armed = h.generation
        h.penDown(view)
        assertFalse(h.idleElapsed(armed))
        assertEquals(FrontInkHandover.Phase.DOWN, h.phase)
    }
    @Test fun aPanForcesTheHandoverAndTheNextStrokeTakesTheOldPath() {
        val h = FrontInkHandover()
        held(h, "a")
        assertFalse(h.canStart(panned)) // a different view cannot join
        assertTrue(h.viewChanged(panned))
        assertFalse(h.isFinishing("a"))
        assertFalse(h.canTake)
        assertEquals(PadPen.BUSY, h.penDown(view))
        assertTrue(h.cleared(h.generation))
        assertTrue(h.canTake)
        assertEquals(PadPen.FRESH, h.penDown(panned)) // the pad is back, on the new view
    }
    @Test fun forceReleasesWithoutWaitingForDryInk() {
        val h = FrontInkHandover()
        held(h, "a")
        assertTrue(h.force())
        assertEquals(listOf("a"), h.releasingIds())
        assertFalse(h.force())
    }
    @Test fun aStaleCommitCannotClearAfterAbandon() {
        val h = FrontInkHandover()
        held(h, "a")
        h.force()
        val stale = h.generation
        h.abandon()
        assertFalse(h.cleared(stale))
        assertEquals(PadPen.FRESH, h.penDown(view))
    }
    @Test fun aCancelledStrokeLeavesWaitingOnesWaiting() {
        val h = FrontInkHandover()
        held(h, "a")
        h.penDown(view)
        h.penCancelled()
        assertEquals(FrontInkHandover.Phase.WAITING, h.phase)
        assertTrue(h.isFinishing("a"))
        val fresh = FrontInkHandover()
        fresh.penDown(view)
        fresh.penCancelled()
        assertEquals(FrontInkHandover.Phase.IDLE, fresh.phase)
    }

    // ---- geometry helpers ----
    @Test fun clipIsTheCanvasColumnWithNoBottomEdge() {
        val clip = FrontInkPad.canvasClip(SheetMapping(2f, 40f, -100f), 1000, 1800)
        assertEquals(40, clip.left)
        assertEquals(0, clip.top)
        assertEquals(1800, clip.bottom)
        assertEquals((40 + CanvasLayout.WIDTH * 2f).toInt().coerceAtMost(1000), clip.right)
    }
}
