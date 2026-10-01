package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.OracleEdgeProof7263
import com.lifecyclebot.engine.truth.PredictiveEntryOracle6915
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class OracleEdgeProofModeIsolation7693Test {
    @After fun resetProof() = OracleEdgeProof7263.resetForTest()

    private fun forecast(verdict: PredictiveEntryOracle6915.Verdict, pWin: Double) =
        PredictiveEntryOracle6915.Forecast(
            verdict = verdict,
            expectancyPct = if (verdict == PredictiveEntryOracle6915.Verdict.ADMIT) 10.0 else -5.0,
            pWin = pWin,
            confidence = 0.9,
            contributions = emptyList(),
            reason = "mode-isolation-test",
        )

    private fun grade(mode: String, mint: String, verdict: PredictiveEntryOracle6915.Verdict, pWin: Double, ret: Double) {
        OracleEdgeProof7263.stamp(mint, forecast(verdict, pWin), mode)
        OracleEdgeProof7263.onEvent(
            CanonicalTradeFinalizedBus6450.Event(
                positionId = "$mode-$mint",
                mint = mint,
                outcome = if (ret > 0.0) CanonicalTradeFinalizedBus6450.Outcome.WIN else CanonicalTradeFinalizedBus6450.Outcome.LOSS,
                netRealizedPnlSol = ret,
                grossRealizedPnlSol = ret,
                returnFraction = ret,
                netReturnPct = ret * 100.0,
                feesSol = 0.0,
                entryLane = "TEST",
                entryStrategyPid = "TEST",
                entryTactic = "TEST",
                exitReason = "TEST",
                holdingTimeMs = 1L,
                dataQuality = "TEST",
                priceIntegrity = "TEST",
                mode = mode,
                settledAtMs = System.currentTimeMillis() + 1L,
            ),
        )
    }

    private fun prove(mode: String) {
        repeat(20) { grade(mode, "$mode-admit-$it", PredictiveEntryOracle6915.Verdict.ADMIT, 0.9, 0.10) }
        repeat(10) { grade(mode, "$mode-refuse-$it", PredictiveEntryOracle6915.Verdict.REFUSE, 0.4, -0.05) }
    }

    @Test fun paperProofCannotPromoteLiveOracle() {
        prove("PAPER")
        assertEquals(OracleEdgeProof7263.Tier.PROVEN, OracleEdgeProof7263.tier("PAPER"))
        assertEquals(OracleEdgeProof7263.Tier.ADVISORY, OracleEdgeProof7263.tier("LIVE"))
        prove("LIVE")
        assertEquals(OracleEdgeProof7263.Tier.PROVEN, OracleEdgeProof7263.tier("LIVE"))
        assertEquals(OracleEdgeProof7263.Tier.PROVEN, OracleEdgeProof7263.tier("PAPER"))
    }

    @Test fun liveProofCannotPromotePaperOracle() {
        prove("LIVE")
        assertEquals(OracleEdgeProof7263.Tier.PROVEN, OracleEdgeProof7263.tier("LIVE"))
        assertEquals(OracleEdgeProof7263.Tier.ADVISORY, OracleEdgeProof7263.tier("PAPER"))
    }
}
