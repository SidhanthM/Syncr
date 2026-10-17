package dev.syncr.prototype.tablet

/** Tablet mode links (design §8.2). Only Pointer exists until the Mac app and Wi-Fi sync ship. */
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

    /** POINTER · BLUETOOTH · CONNECTED TO <NAME> | NOT CONNECTED [· ACTUAL SIZE [· UNCALIBRATED] | · WHOLE SCREEN] */
    fun caption(host: String?, mode: MappingMode, calibrated: Boolean): String {
        val link = if (host != null) "CONNECTED TO ${host.uppercase()}" else "NOT CONNECTED"
        val mapping = when (mode) {
            MappingMode.ACTUAL_SIZE -> if (calibrated) "ACTUAL SIZE" else "ACTUAL SIZE · UNCALIBRATED"
            MappingMode.WHOLE_SCREEN -> "WHOLE SCREEN"
        }
        return "POINTER · BLUETOOTH · $link · $mapping"
    }

    /** Needs attention (a [problem] the user must fix) beats connected, which beats not connected. */
    fun state(host: String?, problem: String?): LinkState = when {
        problem != null -> LinkState.ATTENTION
        host != null -> LinkState.CONNECTED
        else -> LinkState.NOT_CONNECTED
    }

    /** The pad's one-line caption, as in the mock: "<Mac> · Pointer · Bluetooth", or the problem in place of the Mac. */
    fun padCaption(host: String?, problem: String?): String = "${problem ?: host ?: "Not connected"} · Pointer · Bluetooth"
}
