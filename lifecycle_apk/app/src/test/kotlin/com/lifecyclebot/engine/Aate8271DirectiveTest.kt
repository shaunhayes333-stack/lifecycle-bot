package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import com.lifecyclebot.engine.sell.ProfitEscalation8189
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8271 — directive R2 (book versions), R5 (freeze UNKNOWN), R6 (hard risk before grace), R7 (exit cost once), R13. */
class Aate8271DirectiveTest {
    @Test fun exitCostsAreChargedOnce_theDirectiveFixture() {
        // all-in round trip = 2 % variable + fixed share of 0.000205 on 0.030 SOL per leg (×2 legs)
        val fixedSharePct = 2.0 * 0.000205 / 0.030 * 100.0
        val rt = 2.0 + fixedSharePct
        val variableLeg = NetEdge8030.exitVariableFromRoundTrip8271(rt, fixedSharePct)
        assertEquals(1.0, variableLeg, 1e-9)
        val netCorrect = 0.030 * (1.0 - variableLeg / 100.0) - 0.0289 - 0.000205
        assertEquals(0.000595, netCorrect, 1e-9)
        // the pre-8271 path (half the all-in figure PLUS the fixed fee) double-charged the fixed lamports
        val netDouble = 0.030 * (1.0 - (rt / 2.0) / 100.0) - 0.0289 - 0.000205
        assertEquals(0.000390, netDouble, 1e-9)
        assertEquals(ProfitEscalation8189.Verdict8189.ESCALATE_FULL, ProfitEscalation8189.verdict8189(true, 0.030, 0.0289, variableLeg, 0.000205, false))
        assertTrue(Double.NaN.let { NetEdge8030.exitVariableFromRoundTrip8271(it, 0.0).isNaN() })
        assertEquals(0.0, NetEdge8030.exitVariableFromRoundTrip8271(1.0, 5.0), 1e-12)
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("NetEdge8030.exitVariablePct8271(pos.tradingMode, full, ts.lastLiquidityUsd, ts.mint)"))
        assertFalse(ex.contains("NetEdge8030.costPct8030(pos.tradingMode, full, ts.lastLiquidityUsd) / 2.0"))
    }

    @Test fun aFreshRefusalSupersedesOlderReadinessAndAStaleOneDoesNot() {
        val b = SpecialistCandidateBooks7803
        assertTrue(b.refusalSupersedes8271(100L, 101L))     // generation 100 READY, generation 101 REFUSE
        assertTrue(b.refusalSupersedes8271(100L, 100L))
        assertFalse(b.refusalSupersedes8271(101L, 99L))     // generation 99 late refusal cannot erase 101 approval
        assertTrue(b.refusalSupersedes8271(0L, 5L)); assertTrue(b.refusalSupersedes8271(5L, 0L))
        b.resetForTests()
        b.markReady("MOONSHOT", "MINT8271", "M", 100L, 80, 70.0, "ready")
        b.markLost("MOONSHOT", "MINT8271", 101L, "FRESH_REFUSAL")
        assertEquals(SpecialistCandidateBooks7803.State.LOST, b.entry("MOONSHOT", "MINT8271")?.state)
        assertTrue(b.readyFor("MINT8271", 101L).isEmpty())
        b.markReady("MOONSHOT", "MINT8271", "M", 101L, 80, 70.0, "ready")
        b.markLost("MOONSHOT", "MINT8271", 99L, "STALE_ASYNC_REFUSAL")
        assertEquals(SpecialistCandidateBooks7803.State.READY, b.entry("MOONSHOT", "MINT8271")?.state)
        b.resetForTests()
    }

    @Test fun unknownFreezeProofIsPendingHydrationNotAPass_andHardRiskPrecedesGrace() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(g.contains("ts.safety.freezeAuthorityDisabled == null) {"))
        assertTrue(g.contains("blockReason = \"SAFETY_FREEZE_PROOF_PENDING_DEFER_8271\""))
        assertTrue(PipelineHealthCollector.isNonTerminalBuyDeferral7868("SAFETY_FREEZE_PROOF_PENDING_DEFER_8271"))
        assertTrue(g.contains("blockReason = \"HARD_BLOCK_FREEZE_AUTHORITY\""))   // verified retained authority still hard
        val r = File("src/main/kotlin/com/lifecyclebot/engine/RunnerGrab7967.kt").readText()
        assertTrue(r.contains("if (nowMs - pos.entryTime < 60_000L && !peakGiveback7980(peak, g) && g > HARD_STOP_PCT) return"))
    }

    @Test fun sizingCeilingAndFloorBehave() = synchronized(PaperAccountLedger6430) {
        // R13: the resolver never enlarges a risk-approved size, and never seals below the executable minimum.
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(1.0)
        val big = OrderSizeResolver6441.resolve(requestedSol = 0.30, laneName = "SHITCOIN", walletSol = 1.0, paperMode = true, laneRiskCapSol = 0.30, laneMinExecutableSol = 0.05)
        assertTrue(big.executable)
        assertTrue("ceiling: ${big.finalSizeSol}", big.finalSizeSol <= 0.30 + 1e-9)
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(0.0501)
        val tiny = OrderSizeResolver6441.resolve(requestedSol = 0.05, laneName = "SHITCOIN", walletSol = 0.0501, paperMode = true, laneRiskCapSol = 0.05, laneMinExecutableSol = 0.05)
        assertFalse(tiny.executable)
        assertEquals(0.0, tiny.finalSizeSol, 1e-9)
    }
}
