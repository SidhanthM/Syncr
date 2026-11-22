package dev.syncr.prototype.notes

/** The page order a drag would make, to tell whether a drop changes anything. */
object PageOrder {
    /** [order] with the [picked] pages (keeping their order) moved to follow [after] (null: first). */
    fun reorder(order: List<String>, picked: Set<String>, after: String?): List<String> {
        val rest = order.filter { it !in picked }
        val at = if (after == null) 0 else rest.indexOf(after) + 1
        return rest.take(at) + order.filter { it in picked } + rest.drop(at)
    }
}
