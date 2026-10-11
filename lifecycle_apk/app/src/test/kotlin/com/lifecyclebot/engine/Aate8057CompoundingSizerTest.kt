package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8057 — proven edge is sized as a quarter-Kelly share of CURRENT equity: it compounds with the wallet. */
class Aate8057CompoundingSizerTest {
    @Test fun quarterKelly() {
        // ACCELERATING_TAPE-like record: n61, mean +23%, sd 60% -> mean-SE ~15.3% -> f = 0.25 * 0.153 / 0.36 ~ 0.106.
        val f = LiveRiskPolicy7807.kellyFrac8057(23.0, 3600.0, 61.0)
        assertEquals(0.106, f, 0.01)
        // No proven edge (mean - SE <= 0): no compounding size.
        assertEquals(0.0, LiveRiskPolicy7807.kellyFrac8057(2.0, 3600.0, 61.0), 1e-9)
        // Never more than 30% of equity on one coin.
        assertTrue(LiveRiskPolicy7807.kellyFrac8057(80.0, 400.0, 200.0) <= 0.30 + 1e-9)
    }

    @Test fun sizeGrowsWithTheWallet() {
        val small = LiveRiskPolicy7807.edgeKellySizeSol8057(0.33, 0.106, 0.33 * 0.20, 10.0, 15.0, 3.5)
        val big = LiveRiskPolicy7807.edgeKellySizeSol8057(3.3, 0.106, 3.3 * 0.20, 10.0, 15.0, 3.5)
        assertEquals(10.0, big / small, 0.5)   // 10x the wallet -> 10x the bet
        // The hard loss at the stop still binds (8% of a small wallet over a 18.5% stop+cost).
        assertTrue(LiveRiskPolicy7807.edgeKellySizeSol8057(0.33, 0.30, 1.0, 10.0, 15.0, 3.5) <= 0.33 * 0.08 / 0.185 + 1e-9)
    }

    @Test fun wired() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(p.contains("PROVEN_EDGE_COMPOUNDING_SIZE_8057"))
        val g = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(g.contains("LiveRiskPolicy7807.edgeKellyFracFor8057(ts, lane)"))
    }
}
