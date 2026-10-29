package dev.syncr.prototype.link

import dev.syncr.core.CoordinateSpace
import dev.syncr.core.PreviewEvent
import dev.syncr.core.PreviewReceiver
import dev.syncr.core.StrokeCommit
import dev.syncr.core.StrokeReassembler
import dev.syncr.core.decodeSamples
import dev.syncr.core.newId
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's link against a Kotlin fake Mac over loopback, with the REAL core on both sides of the byte stream: the probe
 * (real `export_copy`, real `PreviewSendQueue`) is the phone; the fake Mac decodes frames with the core's own
 * `PreviewReceiver` and `StrokeReassembler`. Runs only with `-PsyncrNativeHost=true`. Takes about ten seconds: the probe plays
 * its strokes in real time.
 */
class LoopbackHostTest {
    @Test fun probeAgainstFakeMacPairsSyncsMirrorsAndStreams() {
        val noteId = newId()
        val sheetId = newId()
        FakeMac(code = "4242").use { mac ->
            val events = CopyOnWriteArrayList<PreviewEvent>()
            val commits = CopyOnWriteArrayList<StrokeCommit>()
            var receiver: PreviewReceiver? = null
            val reassembler = StrokeReassembler()
            mac.acceptPreviewFrame = { frame ->
                val r = receiver ?: PreviewReceiver(mac.epoch.toUInt()).also { receiver = it }
                events += r.accept(frame, System.nanoTime().toULong() / 1_000_000uL)
            }
            mac.acceptStrokeChunk = { chunk -> reassembler.accept(chunk, System.nanoTime().toULong() / 1_000_000uL)?.let { commits += it } }
            mac.hook = { c -> if (c is Control.Pad && c.on) mac.send(Control.Target(noteId, sheetId, true, 5)) }

            val lines = CopyOnWriteArrayList<String>()
            val library = File("../../core/fixtures/canvas-v2").canonicalFile
            val ok = Probe(Probe.Options(mac.endpoint, "4242", library, timeoutMs = 30_000)) { lines += it }.run()
            lines.forEach(::println)
            assertTrue(lines.joinToString("\n"), ok)

            // Pairing and sync.
            assertTrue(lines.any { it.startsWith("PROBE welcome") })
            val delivered = lines.filter { it.startsWith("PROBE sync have") }
            assertEquals(2, delivered.size)
            assertEquals(2, mac.files.size)
            val fixture = File(library, library.listFiles()!!.first { it.extension == "syncr" }.name).readBytes()
            for (bytes in mac.files.values) assertTrue(bytes.size > 100)
            assertEquals(fixture.take(16), mac.files.values.first().take(16)) // same container format as the library's own note files

            // Mirror: Canvas space, the fixture note, 240 samples, preview only.
            val started = events.filterIsInstance<PreviewEvent.StrokeStarted>()
            val mirror = started.first { it.space == CoordinateSpace.CANVAS }
            assertTrue(mac.files.keys.contains(mirror.noteId))
            val mirrored = events.filterIsInstance<PreviewEvent.SamplesAppended>().filter { it.strokeId == mirror.strokeId }
            assertEquals(240, mirrored.sumOf { it.channels.x.size })
            assertEquals(mirrored.map { it.firstIndex }.sorted(), mirrored.map { it.firstIndex }) // in order
            assertTrue(events.any { it is PreviewEvent.StrokeEnded && it.strokeId == mirror.strokeId && it.totalSamples == 240u })

            // Stream: view-relative, the Mac's target, live preview and a durable commit with the same stroke ID.
            val stream = started.first { it.space == CoordinateSpace.VIEW_RELATIVE }
            assertEquals(noteId, stream.noteId)
            assertEquals(sheetId, stream.sheetId)
            assertEquals(5u, stream.viewportRevision)
            assertTrue(events.any { it is PreviewEvent.StrokeEnded && it.strokeId == stream.strokeId && it.totalSamples == 240u })
            assertTrue(mac.await { commits.isNotEmpty() })
            val commit = commits.single()
            assertEquals(stream.strokeId, commit.strokeId)
            assertEquals(CoordinateSpace.VIEW_RELATIVE, commit.space)
            val whole = decodeSamples(commit.samples)
            assertEquals(240, whole.x.size)
            assertTrue(whole.x.all { it in 0f..4800f } && whole.y.all { it in 0f..3000f })
            assertTrue(events.none { it is PreviewEvent.Dropped })

            // The latency line names what it leaves out.
            assertNotNull(lines.firstOrNull { it.startsWith("PROBE latency") && it.contains("excludes digitiser, display scan-out") })
            assertTrue(lines.contains("PROBE done ok"))
        }
    }
}
