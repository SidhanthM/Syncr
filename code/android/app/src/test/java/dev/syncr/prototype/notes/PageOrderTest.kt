package dev.syncr.prototype.notes

import org.junit.Assert.*
import org.junit.Test

class PageOrderTest {
    @Test fun reorderingMovesThePickedPagesAfterTheDropPageKeepingTheirOrder() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PageOrder.reorder(order, setOf("a"), "c"))
        assertEquals(listOf("c", "a", "b", "d"), PageOrder.reorder(order, setOf("c"), null))
        assertEquals(listOf("a", "b", "d", "c"), PageOrder.reorder(order, setOf("c"), "d"))
        assertEquals(listOf("b", "d", "a", "c"), PageOrder.reorder(order, setOf("c", "a"), "d"))
        assertEquals(order, PageOrder.reorder(order, setOf("b"), "a")) // dropped where it already is
        assertEquals(order, PageOrder.reorder(order, setOf("a", "b"), null))
    }
}
