package dev.syncr.prototype.link

import dev.syncr.core.PreviewBody
import dev.syncr.core.PreviewFrame
import dev.syncr.core.StrokeCommit
import java.io.File
import java.nio.file.Files

class FakeClock(var now: Long = 1_000) : Clock {
    override fun nowMs() = now
}

class MemoryStore(private val id: String = "0123456789abcdef0123456789abcdef", var current: Pairing? = null) : PairingStore {
    var forgets = 0
    override fun phoneId() = id
    override fun pairing() = current
    override fun save(pairing: Pairing) { current = pairing }
    override fun forget() { current = null; forgets++ }
}

/** Posted tasks wait for [runPosted]; background work runs at once, so a test controls only the return to the link thread. */
class FakeLoop : LinkLoop {
    private val posted = ArrayDeque<Runnable>()
    override fun post(task: Runnable) { posted += task }
    override fun background(task: Runnable) = task.run()
    fun runPosted() { while (posted.isNotEmpty()) posted.removeFirst().run() }
}

/** A stand-in for the core's send queue: previews pop before durable chunks, like the real one. */
class FakeOut : PreviewOut {
    val frames = ArrayList<PreviewFrame>()
    val commits = ArrayList<StrokeCommit>()
    private val previews = ArrayDeque<ByteArray>()
    private val durable = ArrayDeque<ByteArray>()
    var refuseCommits = 0

    override fun push(frame: PreviewFrame) {
        frames += frame
        val body = when (frame.body) {
            is PreviewBody.Begin -> 1
            is PreviewBody.Samples -> 2
            is PreviewBody.End -> 3
            PreviewBody.Cancel -> 4
            is PreviewBody.Hover -> 5
        }
        previews += byteArrayOf(Wire.KIND_PREVIEW.toByte(), 1, body.toByte())
    }

    override fun commit(commit: StrokeCommit) {
        if (refuseCommits > 0) { refuseCommits--; throw dev.syncr.core.CoreException.ResourceLimit("backlog", true) }
        commits += commit
        durable += byteArrayOf(Wire.KIND_STROKE.toByte(), 1, 1)
    }

    override fun pop(): ByteArray? = previews.removeFirstOrNull() ?: durable.removeFirstOrNull()
    override fun queuedPreviewBytes() = previews.size.toLong() * 3
    override fun clearPreview() = previews.clear()
}

val MAC_ID = "fedcba9876543210fedcba9876543210"

class NotesFixture : NoteSource {
    val notes = ArrayList<NoteMeta>()
    val contents = HashMap<String, ByteArray>()
    var exportSeq = HashMap<String, Long>()
    override fun list() = notes.toList()
    override fun exportCopy(id: String, dest: File): Long? {
        val bytes = contents[id] ?: return null
        dest.writeBytes(bytes)
        return exportSeq[id] ?: notes.first { it.id == id }.seq
    }
}

/** A session wired to fakes, driven by hand. */
class SessionHarness(
    val store: MemoryStore = MemoryStore(),
    endpoint: Endpoint = Endpoint("127.0.0.1", 1, "Test Mac", MAC_ID),
) : SessionEvents {
    val clock = FakeClock()
    val out = FakeOut()
    val feed = PenFeed(clock)
    val pump = FeedPump(feed, out, clock, { ByteArray(4) })
    val scheduler = SendScheduler(out, pump)
    val loop = FakeLoop()
    val source = NotesFixture()
    val temp: File = Files.createTempDirectory("syncr-link-test-").toFile()
    val sync = SyncSender(source, scheduler, loop, clock, temp)
    val latency = LatencyLog { }
    val statuses = ArrayList<SessionStatus>()
    val targets = ArrayList<StreamTarget>()
    var closed: Pair<String, Boolean>? = null
    val session = LinkSession("Test phone", store, endpoint, scheduler, sync, pump, feed, clock, latency, source, this)

    override fun status(status: SessionStatus) { statuses += status }
    override fun target(target: StreamTarget) { targets += target }
    override fun close(reason: String, fatal: Boolean) { closed = reason to fatal }

    /** Everything queued to send, control messages parsed; other kinds are returned raw. */
    fun drain(): List<Any> {
        val list = ArrayList<Any>()
        while (true) {
            val m = scheduler.next() ?: break
            list += if (Wire.kind(m) == Wire.KIND_CONTROL) Control.parse(m)!! else m
        }
        return list
    }

    fun controls() = drain().filterIsInstance<Control>()

    fun mac(c: Control) = session.onControl(c)

    fun welcome(epoch: Long = 7) = mac(Control.Welcome(0, "Test Mac", MAC_ID, epoch))

    fun cleanup() { temp.deleteRecursively() }
}
