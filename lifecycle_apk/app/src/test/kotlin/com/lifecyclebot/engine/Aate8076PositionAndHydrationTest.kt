package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexEntryReadBook8076
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import org.junit.Assert.*
import org.junit.Test

class Aate8076PositionAndHydrationTest {
    @Test fun laterMintAssessmentAndAnotherModeCannotRewriteAnOpenPosition() {
        val reads = CortexEntryReadBook8076<String>()
        assertTrue(reads.bind("p1", "mint", "LIVE", "entry-strong"))
        assertFalse(reads.bind("p1", "mint", "LIVE", "later-refuse"))
        assertTrue(reads.bind("p1", "mint", "PAPER", "paper-neutral"))
        assertTrue(reads.bind("p2", "mint", "LIVE", "reentry-neutral"))
        assertNull(reads.take("p1", "wrong-mint", "LIVE"))
        assertEquals("entry-strong", reads.take("p1", "mint", "LIVE"))
        assertNull(reads.take("p1", "mint", "LIVE"))
        val restored = CortexEntryReadBook8076<String>()
        reads.snapshot().forEach { restored.bind(it.positionId, it.mint, it.mode, it.read) }
        assertEquals("paper-neutral", restored.take("p1", "mint", "PAPER"))
        assertEquals("reentry-neutral", restored.take("p2", "mint", "LIVE"))
    }

    @Test fun repeatedRegistrationDoesNotReadWholeDurableStoreAgain() {
        val hydration = CanonicalFinalizedTradeBus6464.ConsumerHydration8076()
        var reads = 0
        hydration.once("Cortex", false) { reads++ }
        assertEquals(0, reads)
        repeat(1000) { hydration.once("Cortex", true) { reads++ } }
        assertEquals(1, reads)
        hydration.once("Governor", true) { reads++ }
        assertEquals(2, reads)
        hydration.clear()
        hydration.once("Cortex", true) { reads++ }
        assertEquals(3, reads)
    }

    @Test fun failedHydrationCanRetryRatherThanLatchingAnEmptyHistory() {
        val hydration = CanonicalFinalizedTradeBus6464.ConsumerHydration8076()
        try { hydration.once("Cortex", true) { throw IllegalStateException("store unavailable") } } catch (_: IllegalStateException) {}
        var loaded = false
        hydration.once("Cortex", true) { loaded = true }
        assertTrue(loaded)
    }
}
