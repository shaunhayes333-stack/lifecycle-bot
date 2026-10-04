package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7789LiveLastMileAuthorityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun liveWalletAuthorityWinsOverStaleCache() {
        val b = src("engine/truth/TraderSizingBridge6444.kt")
        assertTrue(b.contains("if (status7226.isFinite() && status7226 > 0.0) status7226 else cached7226"))
        for (p in listOf("v3/scoring/QualityTraderAI.kt","v3/scoring/BlueChipTraderAI.kt","v3/scoring/MoonshotTraderAI.kt","v3/scoring/ShitCoinTraderAI.kt"))
            assertTrue(p, src(p).contains("if (status.isFinite() && status > 0.0) status else cached"))
    }

    @Test fun executablePromotionUsesStrictFreshness() {
        val s = src("engine/truth/CanonicalPriceMark6522.kt")
        val f = s.substringAfter("fun promoteObservationToExecutable6613(").substringBefore("/** V5.0.6616")
        assertTrue(f.contains("MARK_FRESHNESS_WINDOW_MS_6739"))
        assertFalse(f.contains("age in -5_000L..300_000L"))
        assertFalse(f.contains("age !in -5_000L..300_000L"))
    }

    @Test fun executorPreservesSealedLastMileAuthority() {
        val s = src("engine/Executor.kt")
        assertTrue(s.contains("ENTRY_SNAPSHOT_RECOVERED_FROM_OBSERVED_LIQUIDITY_7789"))
        assertTrue(s.contains("LaneEntryContract6342.assessEntry(ts, contractLane7789)"))
        assertTrue(s.contains("CHOKEPOINT_7742_ADVISORY_AFTER_SEALED_FDG_7789"))
        assertTrue(s.contains("LIVE_PRELEASE_CANONICAL_TERMINAL_7789"))
    }
}
