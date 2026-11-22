package dev.syncr.prototype.notes

import dev.syncr.core.*
import dev.syncr.prototype.ui.TypingThrottle
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Remaining-v1 L.3: what continuous typing costs the disk, on the real core (host build). A person types 10 keys a second for
 * [SECONDS]; the text is stored on the phone's shipped throttle ([TypingThrottle]) through the open note, as the app does
 * (`TextCommands.save`, `submit`, then a snapshot). Reported per second: durable commits, bytes the process wrote to disk
 * (macOS `proc_pid_rusage` of this JVM, so everything it wrote), and how the note file and its write-ahead log grew. fsyncs
 * are not countable without root on macOS: the core opens notes in WAL mode with `synchronous=FULL`, one fsync of the log
 * per commit plus the checkpoints'. Run with SYNCR_DISK_RATE=1 (about two minutes); prints one `DISK-RATE` line per note size.
 */
class TypingDiskRateHostTest {
    @Test fun typingForAMinuteAtTheShippedThrottle() {
        assumeTrue(System.getenv("SYNCR_DISK_RATE") == "1")
        val seconds = System.getenv("SYNCR_DISK_RATE_SECONDS")?.toInt() ?: SECONDS
        for (strokes in (System.getenv("SYNCR_DISK_RATE_STROKES") ?: "0,2000").split(',').map { it.trim().toInt() }) run(strokes, seconds)
    }

    private fun run(strokes: Int, seconds: Int) {
        val root = Files.createTempDirectory("syncr-disk-rate-").toFile()
        val core = GeneratedCoreAdapter().open(root.absolutePath)
        val create = CreateNoteRequest(newId(), newId(), newId(), "Typing", null, NoteKind.NOTE)
        try {
            core.create(create)
            val note = core.openNote(create.noteId)
            try {
                fill(note, strokes)
                note.submit(requireNotNull(TextCommands.save(note.snapshot(), TextCommands.draft(note.snapshot()), "Typing.", ::newId)))
                val files = { root.walk().filter { it.isFile && it.name.startsWith(create.noteId) }.associate { it.name to it.length() } }
                val before = files(); val wrote0 = bytesWritten()
                var text = TextCommands.draft(note.snapshot()).text
                var dirtySince = 0L; var lastCommit = 0L; var due = Long.MAX_VALUE; var commits = 0
                val start = System.nanoTime() / 1_000_000; var nextKey = start
                val end = start + seconds * 1000L
                while (true) {
                    val now = System.nanoTime() / 1_000_000
                    if (now >= end && dirtySince == 0L) break
                    if (now >= nextKey && now < end) {
                        text += "p"; if (dirtySince == 0L) dirtySince = now
                        due = now + TypingThrottle.delayMs(now, dirtySince, lastCommit)
                        nextKey += 100
                    }
                    if (dirtySince != 0L && now >= due) {
                        val snap = note.snapshot()
                        note.submit(requireNotNull(TextCommands.save(snap, TextCommands.draft(snap), text, ::newId)))
                        note.snapshot()
                        commits++; dirtySince = 0L; lastCommit = now; due = Long.MAX_VALUE
                    }
                    Thread.sleep(1)
                }
                val wrote = bytesWritten() - wrote0
                val after = files()
                val grew = after.entries.joinToString(" ") { (name, size) -> "${name.removePrefix(create.noteId).ifEmpty { ".syncr" }}=${(size - (before[name] ?: 0)) / 1024}KiB" }
                val elapsed = (System.nanoTime() / 1_000_000 - start) / 1000.0
                println("DISK-RATE strokes=$strokes seconds=%.1f commits=$commits commits/s=%.2f process-bytes-written/s=%.0f KiB growth: $grew text=${text.length} chars".format(
                    java.util.Locale.ROOT, elapsed, commits / elapsed, wrote / elapsed / 1024))
            } finally { note.closeDurably() }
        } finally { core.release(); root.deleteRecursively() }
    }

    private fun fill(note: NoteAccess, count: Int) {
        val sheet = note.snapshot().sheets.first()
        val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
        for (start in 0 until count step 500) {
            val marks = (start until minOf(count, start + 500)).map { k ->
                val x = (k * 977 % 4000).toFloat(); val y = (k * 613 % 6000).toFloat()
                MarkMutation.AddStroke(newId(), sheet.id, encodeSamples(SampleChannels(List(60) { x + it * 3f }, List(60) { y + (it % 7) * 2f },
                    List(60) { 0.5f }, List(60) { (it * 4).toUInt() }, null, null)), brush)
            }
            note.submit(Command(newId(), listOf(sheet.creationOperationId), CommandBody.Marks(marks)))
        }
    }

    /** Bytes this process has written to disk so far (macOS `proc_pid_rusage`, RUSAGE_INFO_V4 `ri_diskio_byteswritten`). */
    private fun bytesWritten(): Long {
        val script = "import ctypes,sys\nb=ctypes.create_string_buffer(512)\n" +
            "ctypes.CDLL('/usr/lib/libproc.dylib').proc_pid_rusage(int(sys.argv[1]),4,b)\n" +
            "print(int.from_bytes(b.raw[152:160],'little'))"
        // The JVM's own process ID: the shell's parent (the test classpath is Android's, which has no ProcessHandle).
        val pid = ProcessBuilder("sh", "-c", "echo \$PPID").start().inputStream.bufferedReader().readText().trim()
        val p = ProcessBuilder("python3", "-c", script, pid).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().trim().toLongOrNull() ?: -1
    }

    private companion object { const val SECONDS = 60 }
}
