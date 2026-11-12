package dev.syncr.prototype.tablet

/** The caption dot and bubble ring (design §1, ring states). */
enum class LinkState { CONNECTED, NOT_CONNECTED, ATTENTION }

/** Connection state for the pad's corner caption: Tablet mode writes into the Mac's note over Wi-Fi. */
object LinkStatus {
    /** The pad's one-line caption: "<Mac> · Stream · Wi-Fi", or the problem in place of the Mac. */
    fun padCaption(mac: String?, problem: String?): String = "${problem ?: mac ?: "Not connected"} · Stream · Wi-Fi"

    /** The pad's spoken description. */
    fun caption(mac: String?, problem: String?): String =
        "Tablet mode pad. Stream over Wi-Fi. ${problem ?: if (mac != null) "Writing into the note open on $mac" else "Not connected"}. Whole note view."

    /** Connected and writable is connected; a reason it cannot write is attention; otherwise not connected. */
    fun state(connected: Boolean, problem: String?): LinkState = when {
        !connected -> LinkState.NOT_CONNECTED
        problem != null -> LinkState.ATTENTION
        else -> LinkState.CONNECTED
    }
}
