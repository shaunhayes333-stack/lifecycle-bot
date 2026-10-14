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

    // ── V5.0.8094 ──

    @Test fun provenSizeIsNeverRevertedToTheProposal() {
        val t = com.lifecyclebot.engine.truth.LiveSizeTarget8094
        assertEquals(0.0456, t.fdgRiskSized8094(0.02892, 0.0456, "OPEN_7807", compoundingApproved = true), 1e-12)
        assertEquals(0.02892, t.fdgRiskSized8094(0.02892, 0.0456, "OPEN_7807", compoundingApproved = false), 1e-12)
        assertEquals(0.03, t.fdgRiskSized8094(0.02, 0.03, "OPEN_RISK_SAFE_MIN_PROMOTED_7840", false), 1e-12)
        // Upstream contract: ordinary request clamps to the proposal; an approved target to min(kelly, ceiling).
        assertEquals(0.02892, t.upstreamCeiling8094(0.02892, 0.0456, 0.0), 1e-12)
        assertEquals(0.0456, t.upstreamCeiling8094(0.02892, 0.0456, 0.057), 1e-12)
        assertEquals(0.040, t.upstreamCeiling8094(0.02892, 0.0456, 0.040), 1e-12)
        assertEquals(0.02892, t.upstreamCeiling8094(0.02892, 0.0, 0.057), 1e-12)
    }

    @Test fun oneCandidateSpecificCeilingSharedByFdgAndExecutor() {
        val t = com.lifecyclebot.engine.truth.LiveSizeTarget8094
        assertEquals(0.18, t.walletShareCeiling8094(com.lifecyclebot.engine.truth.LiveSizeTarget8094.ProofTier.UNPROVEN, 0.18), 1e-12)
        assertEquals(t.PROVEN_WALLET_SHARE_8094, t.walletShareCeiling8094(com.lifecyclebot.engine.truth.LiveSizeTarget8094.ProofTier.PROVEN, 0.18), 1e-12)
        assertEquals(0.25, t.PROVEN_WALLET_SHARE_8094, 1e-12)
        assertEquals(com.lifecyclebot.engine.truth.LiveSizeTarget8094.ProofTier.UNPROVEN, t.tierOf8094(0.0))
        t.recordCeiling8094("MINT_8094_A", 0.25, nowMs = 1_000L)
        assertEquals(0.25, t.walletShareFor8094("MINT_8094_A", 0.18, nowMs = 2_000L), 1e-12)
        assertEquals(0.18, t.walletShareFor8094("MINT_8094_A", 0.18, nowMs = 1_000L + 11 * 60_000L), 1e-12)  // stale
        assertEquals(0.18, t.walletShareFor8094("OTHER", 0.18, nowMs = 2_000L), 1e-12)
        val g = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(g.contains("LiveSizeTarget8094.recordCeiling8094(ts.mint, walletShare8094)"))
        assertTrue(g.contains("walletSharePct = walletShare8094"))
        assertTrue(!g.contains("FDG_PROVEN_EDGE_COMPOUNDING_ASK_8057"))                  // superseded ask retired
        assertTrue(g.contains("val edgeKelly8094 = if (config.paperMode) 0.0 else"))      // paper never sized up
        assertTrue(g.contains("alreadyShaped7925 = !config.paperMode ||"))               // no double multipliers
        assertTrue(g.contains("resolution.finalSizeSol > riskSized + 1e-9) return 0.0")) // resolver never enlarges
        val x = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(x.contains("LiveSizeTarget8094.walletShareFor8094(ts.mint, liveCfg.maxWalletRiskPerTradePct)"))
    }

    @Test fun firstBindingCapIsTheSmallestConstraint() {
        val t = com.lifecyclebot.engine.truth.LiveSizeTarget8094
        assertEquals("KELLY", t.firstBindingCap8094(0.03, 1.0, 1.0, 0.05, 1.0, 0.1, 0.06))
        assertEquals("DRAWDOWN", t.firstBindingCap8094(0.05, 0.44, 0.44, 0.05, 1.0, 0.1, 0.06))
        assertEquals("LANE", t.firstBindingCap8094(0.07, 1.0, 1.0, 0.0456, 1.0, 0.1, 0.06))
        assertEquals("WALLET", t.firstBindingCap8094(0.07, 1.0, 1.0, 0.08, 1.0, 0.1, 0.041))
        assertEquals("LIQUIDITY", t.firstBindingCap8094(0.07, 1.0, 1.0, 0.08, 0.02, 0.1, 0.06))
        assertEquals("RISK", t.firstBindingCap8094(0.07, 1.0, 1.0, 0.08, 1.0, 0.03, 0.06))
    }

    @Test fun weakRequestsAreNeverEnlargedByLegacyFloors() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val v3 = bot.substring(bot.indexOf("fun runV3Execution"), bot.indexOf("fun manualBuy"))
        assertTrue(v3.contains("execSol = req.sizeSol"))
        assertTrue(!v3.contains("LiveSizingProfile.lastMileEntryFloor("))               // zero-signal never lifted
        val sizer = File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        assertTrue(sizer.contains("if (isPaperMode) v = LiveSizingProfile.laneCompoundFloor("))  // paper floor only
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(p.contains("val probeSize = maxOf(if (i.upstreamSol > 0.0) i.upstreamSol else 0.0, i.execMinSol)"))
    }
}
