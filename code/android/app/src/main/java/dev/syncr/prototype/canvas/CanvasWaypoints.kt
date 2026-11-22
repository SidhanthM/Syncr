package dev.syncr.prototype.canvas

import dev.syncr.core.Waypoint

/** The waypoint list rules, as the core applies them (K3): at most 100, names 1..48 characters, same name replaces in place. */
internal object CanvasWaypoints {
    const val MAX = 100
    const val MAX_NAME = 48

    /** The next free "View n" name, so Save needs no typing. */
    fun nextName(items: List<Waypoint>): String {
        var n = items.size + 1
        while (items.any { it.name.equals("View $n", ignoreCase = true) }) n++
        return "View $n"
    }

    /** The list after saving [view]; null when the name is empty or the list is full of other names. */
    fun saved(items: List<Waypoint>, view: Waypoint): List<Waypoint>? {
        val name = view.name.trim().take(MAX_NAME)
        if (name.isEmpty() || !view.zoom.isFinite() || view.zoom <= 0f) return null
        val clean = view.copy(name = name)
        val at = items.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        return when {
            at >= 0 -> items.toMutableList().also { it[at] = clean }
            items.size >= MAX -> null
            else -> items + clean
        }
    }

    fun removed(items: List<Waypoint>, name: String): List<Waypoint> = items.filterNot { it.name == name }
}
