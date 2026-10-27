package dev.syncr.prototype.link

/**
 * When the phone's link may touch the network. Plain functions so the rule is tested on the host: the link is off until the
 * user turns it on in Settings, and with it off no socket is opened, no discovery runs and no Wi-Fi lock is taken.
 */
object LinkPolicy {
    const val ENABLED_BY_DEFAULT = false

    /** The link client and discovery exist only while the link is switched on and the app is in front. */
    fun shouldRun(enabled: Boolean, startedActivities: Int) = enabled && startedActivities > 0

    /** The low-latency Wi-Fi lock is held only while a session is connected and the app is in front. */
    fun wantsWifiLock(startedActivities: Int, connected: Boolean) = startedActivities > 0 && connected
}
