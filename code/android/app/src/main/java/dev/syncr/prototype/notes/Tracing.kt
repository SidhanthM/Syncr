package dev.syncr.prototype.notes

import android.os.Trace

/**
 * A Perfetto/systrace slice around [block]. The platform call is a cheap no-op unless a trace is
 * being recorded for this (debuggable or profileable) app, so it costs nothing in normal use.
 * Section names are fixed strings: no allocation on the input path.
 */
internal inline fun <T> trace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

/** Slice names, so the README's "what to look for" list and the code agree. */
internal object TraceNames {
    const val TOUCH_INPUT = "syncr.input.touch"
    const val WET_START = "syncr.wet.start"
    const val WET_ADD = "syncr.wet.add"
    const val WET_FINISH = "syncr.wet.finish"
    const val WET_HANDOFF = "syncr.wet.handoff"
    const val FRONT_PRESENT = "syncr.front.present"
    const val FRONT_FLUSH = "syncr.front.flush"
    const val FRONT_HANDOFF = "syncr.front.handoff"
    const val DRY_DRAW = "syncr.dry.draw"
}
