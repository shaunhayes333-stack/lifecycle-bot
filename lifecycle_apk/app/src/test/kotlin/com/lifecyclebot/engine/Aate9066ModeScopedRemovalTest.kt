package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test

class Aate9066ModeScopedRemovalTest {
    private data class Position(val paper: Boolean, val generation: Int)

    @Test fun oppositeAccountCannotBeRemovedInEitherDirection() {
        for (paper in listOf(true, false)) {
            val record = Position(paper, 1)
            val book = ConcurrentHashMap<String, Position>().apply { put("mint", record) }
            assertFalse(ModeScopedRemoval9066.remove(book, "mint", !paper) { it.paper })
            assertSame(record, book["mint"])
        }
    }

    @Test fun matchingAccountCanBeRemovedExactlyOnce() {
        for (paper in listOf(true, false)) {
            val book = ConcurrentHashMap<String, Position>().apply { put("mint", Position(paper, 1)) }
            assertTrue(ModeScopedRemoval9066.remove(book, "mint", paper) { it.paper })
            assertFalse(ModeScopedRemoval9066.remove(book, "mint", paper) { it.paper })
            assertTrue(book.isEmpty())
        }
    }

    @Test fun replacementDuringInspectionCannotBeErased() {
        for (replacementMode in listOf(true, false)) for (generation in listOf(1, 2)) {
            val replacement = Position(replacementMode, generation)
            val book = ConcurrentHashMap<String, Position>().apply { put("mint", Position(false, 1)) }
            assertFalse(ModeScopedRemoval9066.remove(book, "mint", false) { observed ->
                book["mint"] = replacement
                observed.paper
            })
            assertSame(replacement, book["mint"])
        }
    }
}
