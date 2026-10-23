package dev.syncr.prototype.tablet

/** Tablet mode links (design §8.2): Stream writes into the note open on the Mac over Wi-Fi; Pointer is a Bluetooth mouse. */
enum class TabletLink { STREAM, POINTER }

/** The caption dot and bubble ring (design §1, ring states). Pointer never pulses: nothing is pending. */
enum class LinkState { CONNECTED, NOT_CONNECTED, ATTENTION }

/**
 * Connection state for the corner caption, derived from [dev.syncr.prototype.HidController]'s status
 * messages (its public surface; the controller itself is deliberately left unchanged).
 */
object LinkStatus {
    private const val CONNECTED = "Connected to "
    private const val CONNECTED_SUFFIX = ". Draw on the pad."

    /** The connected host's name after [message], given the [previous] one; null when not connected. */
    fun connectedHost(message: String, previous: String?): String? = when {
        message.startsWith(CONNECTED) -> message.removePrefix(CONNECTED).removeSuffix(CONNECTED_SUFFIX)
        message.startsWith("Disconnected") ||
            message.contains("registration lost", ignoreCase = true) ||
            message.contains("service disconnected", ignoreCase = true) ||
            message.startsWith("Enable Bluetooth") -> null
        else -> previous
    }

    /** The pad's spoken description: link, connection and mapping, in sentence case. */
    fun caption(host: String?, mode: MappingMode, calibrated: Boolean): String {
        val link = if (host != null) "Connected to $host" else "Not connected"
        val mapping = when (mode) {
            MappingMode.ACTUAL_SIZE -> if (calibrated) "Actual size" else "Actual size, not checked with a ruler"
            MappingMode.WHOLE_SCREEN -> "Whole screen"
        }
        return "Tablet mode pad. Pointer over Bluetooth. $link. $mapping."
    }

    /** Needs attention (a [problem] the user must fix) beats connected, which beats not connected. */
    fun state(host: String?, problem: String?): LinkState = when {
        problem != null -> LinkState.ATTENTION
        host != null -> LinkState.CONNECTED
        else -> LinkState.NOT_CONNECTED
    }

    /** The pad's one-line caption, as in the mock: "<Mac> · Pointer · Bluetooth", or the problem in place of the Mac. */
    fun padCaption(host: String?, problem: String?): String = "${problem ?: host ?: "Not connected"} · Pointer · Bluetooth"

    /** The pad's caption in Stream: "<Mac> · Stream · Wi-Fi", or the problem in place of the Mac. */
    fun padCaptionStream(mac: String?, problem: String?): String = "${problem ?: mac ?: "Not connected"} · Stream · Wi-Fi"

    /** The pad's spoken description in Stream. */
    fun captionStream(mac: String?, problem: String?): String =
        "Tablet mode pad. Stream over Wi-Fi. ${problem ?: if (mac != null) "Writing into the note open on $mac" else "Not connected"}. Whole note view."

    /** Stream: connected and writable is connected; a reason it cannot write is attention; otherwise not connected. */
    fun streamState(connected: Boolean, problem: String?): LinkState = when {
        !connected -> LinkState.NOT_CONNECTED
        problem != null -> LinkState.ATTENTION
        else -> LinkState.CONNECTED
    }
}
