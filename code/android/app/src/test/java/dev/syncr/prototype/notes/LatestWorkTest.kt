package dev.syncr.prototype.notes

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWorkTest {
    /** An executor that holds what it is given until the test runs it, one task at a time. */
    private class Hand : java.util.concurrent.Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test fun aBurstOfRequestsBeforeTheJobStartsRunsOnlyTheNewest() {
        val hand = Hand(); val seen = ArrayList<Int>()
        val work = LatestWork<Int>(hand) { seen += it }
        for (i in 1..50) work.submit(i)
        assertEquals("one job is on its way, not fifty", 1, hand.queue.size)
        hand.runAll()
        assertEquals(listOf(50), seen)
    }

    @Test fun whileOneRunsOnlyOneNewerRequestWaitsAndEachReplacesIt() {
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        lateinit var work: LatestWork<Int>
        var sawNewer = false
        work = LatestWork(pool) { n ->
            seen += n
            if (n == 1) {
                started.countDown(); release.await(5, TimeUnit.SECONDS)
                sawNewer = work.newerWaiting // the job asks before its expensive stages
            } else done.countDown()
        }
        work.submit(1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        for (i in 2..40) work.submit(i)
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue("the running job saw the newer request", sawNewer)
        assertEquals("the first ran, the thirty-nine in between never did, the last did", listOf(1, 40), seen.toList())
    }

    @Test fun aRequestAfterTheQueueDrainedStartsAFreshJob() {
        val hand = Hand(); val seen = ArrayList<Int>()
        val work = LatestWork<Int>(hand) { seen += it }
        work.submit(1); hand.runAll()
        work.submit(2); assertEquals(1, hand.queue.size); hand.runAll()
        assertEquals(listOf(1, 2), seen)
    }
}
