package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** 8216 — a pending receiver whose mint left the rotating pool still rides the next cycle when its TokenState exists. */
class Aate8216HandoffPoolTest {
    @Test fun targetOutsidePoolJoinsWhenTokenStateHeldElseRehydrates() {
        val targets = listOf("AAA" to 10L, "BBB" to 5L, "CCC" to 7L)
        val asked = mutableListOf<String>()
        val pool = HandoffPriority8190.poolWithLiveTargets8216(targets, listOf("AAA", "ZZZ"), 0L,
            tokenFor = { it == "BBB" }, rehydrate = { asked += it })
        assertTrue("BBB has TokenState: joins", "BBB" in pool)
        assertFalse("CCC has none: not invented", "CCC" in pool)
        assertEquals(listOf("CCC"), asked)
        assertEquals(listOf("BBB", "AAA"), HandoffPriority8190.select8190(targets, pool))
    }

    @Test fun windowUsesExtendedPool() {
        val s = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HandoffThroughput8190.kt").readText()
        assertTrue(s.contains("val pool = poolWithLiveTargets8216(targets, all, nowMs)"))
        assertTrue(s.contains("select8190(targets, pool).also { accountDropped8198(targets, pool, it, nowMs) }"))
    }
}
