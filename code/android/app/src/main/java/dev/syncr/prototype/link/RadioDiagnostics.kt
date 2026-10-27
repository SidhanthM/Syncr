package dev.syncr.prototype.link

/** No SSID, BSSID, address or location is needed to explain a latency sample. */
fun wifiBand(frequencyMhz: Int?): String = when (frequencyMhz) {
    in 2400..2500 -> "2.4GHz"
    in 4900..5900 -> "5GHz"
    in 5925..7125 -> "6GHz"
    in 58320..70200 -> "60GHz"
    else -> "unavailable"
}

fun radioLine(held: Boolean, mode: String, speedMbps: Int?, frequencyMhz: Int?, rssiDbm: Int?, saver: Boolean?): String =
    "wifi-lock-held=$held requested=$mode low-latency-effect=unobservable " +
        "wifi-speed=${speedMbps?.let { "${it}Mbps" } ?: "unavailable"} " +
        "wifi-frequency=${frequencyMhz?.let { "${it}MHz" } ?: "unavailable"} band=${wifiBand(frequencyMhz)} " +
        "rssi=${rssiDbm?.let { "${it}dBm" } ?: "unavailable"} battery-saver=${saver ?: "unavailable"} " +
        "location-fields=not-read(no-location-permission)"
