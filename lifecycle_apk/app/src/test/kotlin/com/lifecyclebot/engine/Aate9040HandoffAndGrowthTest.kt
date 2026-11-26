package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
import com.lifecyclebot.engine.truth.EconomicScorecard9037
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9040 — the 9039 report (27 Nov 02:51): FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715 after FDG approval, and a money line
 * that printed tradingGrowth=+92.31 % on a day whose closes realised -0.0260 SOL.
 */
class Aate9040HandoffAndGrowthTest {
    @Test fun aDecisionHandedToTheOwnerLaneGoesOnWhenThatLaneHadNoNewClose() {
        CausalFeedbackAuthority6715.resetForTest6715()
        val mode = "PAPER"; val score = 40
        val sfx = System.nanoTime().toString()
        // decided on MOONSHOT, executed by the SHITCOIN owner; no close in SHITCOIN since the stamp
        assertTrue(CausalFeedbackAuthority6715.stampDecision("h1$sfx", "mint-h1$sfx", mode, "MOONSHOT", score))
        val handed = CausalFeedbackAuthority6715.admit("h1$sfx", "mint-h1$sfx", mode, "SHITCOIN", score)
        assertTrue(handed.reason, handed.allowed)
        // a close lands in SHITCOIN after the stamp: the decision did not know it, so the handoff is refused as before
        assertTrue(CausalFeedbackAuthority6715.stampDecision("h2$sfx", "mint-h2$sfx", mode, "MOONSHOT", score))
        Thread.sleep(5L)
        val env = CanonicalFinalizedTradeBus6464.Envelope(tradeId = "t9040$sfx", atMs = System.currentTimeMillis(), realizedPnlSol = -0.01,
            realizedReturnPct = -20.0, mint = "mint-x$sfx", lane = "SHITCOIN", positionId = "p9040$sfx", mode = mode, entryScore = score,
            scoreBand = CausalFeedbackAuthority6715.scoreBand(score), learningEligible = true)
        assertTrue(CausalFeedbackAuthority6715.onTerminal(env))
        val refused = CausalFeedbackAuthority6715.admit("h2$sfx", "mint-h2$sfx", mode, "SHITCOIN", score)
        assertFalse(refused.allowed)
        assertEquals("FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715", refused.reason)
        // a change of mint is never handed over
        assertTrue(CausalFeedbackAuthority6715.stampDecision("h3$sfx", "mint-h3$sfx", mode, "MOONSHOT", score))
        assertFalse(CausalFeedbackAuthority6715.admit("h3$sfx", "mint-other$sfx", mode, "MOONSHOT", score).allowed)
        assertTrue(CausalFeedbackAuthority6715.handoffClean9040(100L, 100L))
        assertFalse(CausalFeedbackAuthority6715.handoffClean9040(101L, 100L))
        CausalFeedbackAuthority6715.resetForTest6715()
    }

    @Test fun growthIsCalledTradingOnlyWhenThePartsAddUp() {
        // the 9039 day: 0.1951 -> 0.3752, realised -0.0260, nothing else recorded: +0.2061 SOL unexplained
        val day = EconomicScorecard9037.Split9040(0.3752 - 0.1951, -0.0260, 0.0, 0.0)
        assertEquals(0.2061, day.unreconciledSol, 1e-9)
        assertFalse(EconomicScorecard9037.reconciled9040(day, 0.1951))
        val line = EconomicScorecard9037.splitLine9040(day, 0.1951)
        assertTrue(line, line.contains("UNRECONCILED +0.2061") && line.contains("NOT reconciled") && line.contains("tradingGrowth=-13.33%"))
        // a day made of a close and an open position's rise is reconciled
        val ok = EconomicScorecard9037.Split9040(0.12, 0.10, 0.02, 0.0)
        assertTrue(EconomicScorecard9037.reconciled9040(ok, 1.0))
        assertTrue(EconomicScorecard9037.splitLine9040(ok, 1.0).contains("tradingGrowth=+12.00% (reconciled)"))
        assertTrue("a deposit is a flow, not growth", EconomicScorecard9037.reconciled9040(EconomicScorecard9037.Split9040(0.5, 0.0, 0.0, 0.5), 1.0))
        assertEquals(EconomicScorecard9037.Open9040("2026-11-27", 0.2, 0.01, 5L), EconomicScorecard9037.decodeOpen9040("2026-11-27,0.2,0.01,5"))
        assertNull(EconomicScorecard9037.decodeOpen9040("2026-11-27,0.2"))
        assertTrue(EconomicScorecard9037.RECONCILE_TOL_SOL_9040 > 0.0)
        EconomicScorecard9037.noteDayOpen9040("2026-11-27", 0.2, 1_000L)
        val policy = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(policy.contains("EconomicScorecard9037.noteDayOpen9040(day, equitySol, nowMs)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §225 — V5.0.9040: the owner takes the decision it was handed"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9040*'"))
    }
}
