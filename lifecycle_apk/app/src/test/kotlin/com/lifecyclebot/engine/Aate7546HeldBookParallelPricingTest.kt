package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7546HeldBookParallelPricingTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/HeldHotMarkAuthority7419.kt").readText()

    @Test fun locked_venue_and_fanout_launch_together() {
        val s = src()
        assertTrue(s.contains("lockedFuture7546"))
        assertTrue(s.contains("fanFuture7546"))
        assertTrue(s.contains("LockedVenueMarks7392.resolve(heldBare7546, dex)"))
        assertTrue(s.contains("ParallelMarkFanout7088.resolve7088(heldBare7546)"))
        assertTrue(s.contains("HELD_HOT_BOOK_PARALLEL_PASS_7546"))
    }

    @Test fun whole_book_has_one_deadline_not_additive_provider_deadlines() {
        val s = src()
        assertTrue(s.contains("val passStart7546"))
        assertTrue(s.contains("fun remaining7546()"))
        val block = s.substringAfter("val passStart7546").substringBefore("for (p in stale)")
        assertFalse(block.contains("val unresolved7510"))
        assertFalse(block.contains("boundedBatch7510"))
    }

    @Test fun locked_venue_still_has_merge_precedence() {
        val s = src()
        val loop = s.substringAfter("for (p in stale)").substringBefore("fun summary")
        val lockedAt = loop.indexOf("val locked = locked7510[bare]")
        val fanAt = loop.indexOf("val fan = fan7510[bare]")
        assertTrue(lockedAt >= 0 && fanAt > lockedAt)
    }
}
