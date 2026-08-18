package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdvisorDecisionHistory6463
import com.lifecyclebot.engine.truth.AdvisorRegressionMonitor6463
import com.lifecyclebot.engine.truth.PartialSellCorrectness6463
import com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
import com.lifecyclebot.engine.truth.PerpsSandbox6463
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6463 §P1 — HARD CI ASSERTIONS.
 *
 * Locks:
 *   - Advisor decision timeline records + prints last 20.
 *   - Regression monitor pending count tracks registerApply.
 *   - Perps sandbox refuses live mode + enforces margin-call
 *     liquidation at leverage-scaled thresholds.
 */
class AdvisorAndPerpsAcceptanceTest6463 {

    // ─── ADVISOR DECISION TIMELINE ──────────────────────────────────────

    @Test
    fun `AdvisorDecisionHistory keeps last 20 decisions and prints them`() {
        AdvisorDecisionHistory6463.resetForTest()
        for (i in 1..25) {
            AdvisorDecisionHistory6463.record(
                AdvisorDecisionHistory6463.Decision(
                    atMs = System.currentTimeMillis(), key = "stopLossPct",
                    delta = -1.0 * i, severity = "med", source = "rules",
                    action = AdvisorDecisionHistory6463.Action.AUTO_APPLIED,
                    brainAgreement = 0.7, votes = listOf(
                        AdvisorDecisionHistory6463.BrainVote("MetaCog", true, 1.0),
                    ),
                    reason = "test decision $i",
                    oldValue = 10.0, newValue = 10.0 - i,
                )
            )
        }
        assertEquals(20, AdvisorDecisionHistory6463.recent().size)
        val dump = AdvisorDecisionHistory6463.formatForPipelineDump()
        assertTrue("dump must include AdvisorTimeline6463 header", dump.contains("AdvisorTimeline6463"))
        assertTrue("dump must include AUTO_APPLIED action", dump.contains("AUTO_APPLIED"))
        assertTrue("dump must include brain vote breakdown", dump.contains("MetaCog"))
    }

    // ─── ADVISOR REGRESSION MONITOR ─────────────────────────────────────

    @Test
    fun `registerApply increments pending count`() {
        AdvisorRegressionMonitor6463.resetForTest()
        assertEquals(0, AdvisorRegressionMonitor6463.pendingCount())
        AdvisorRegressionMonitor6463.registerApply(
            id = "test_${System.nanoTime()}", key = "stopLossPct",
            deltaApplied = -2.0, reason = "unit test",
        )
        assertEquals(1, AdvisorRegressionMonitor6463.pendingCount())
        val status = AdvisorRegressionMonitor6463.statusLine()
        assertTrue("status includes pending=1: $status", status.contains("pending=1"))
    }

    // ─── PERPS SANDBOX ──────────────────────────────────────────────────

    @Test
    fun `Perps sandbox refuses live mode enable`() {
        PerpsSandbox6463.resetForTest()
        PerpsSandbox6463.setEnabled(desired = true, paperMode = false)
        assertEquals(false, PerpsSandbox6463.isEnabled())
    }

    @Test
    fun `Perps sandbox openLeveragedPaper refuses when live`() {
        PerpsSandbox6463.resetForTest()
        PerpsSandbox6463.setEnabled(desired = true, paperMode = true)
        val res = PerpsSandbox6463.openLeveragedPaper(
            positionId = "PID_TEST_${System.nanoTime()}",
            mint = "MINT_TEST", leverageX = 3.0,
            entryPx = 0.0001, paperMode = false,
        )
        assertEquals(PerpsSandbox6463.OpenResult.REFUSED_LIVE_MODE, res)
    }

    @Test
    fun `Perps sandbox opens 5x paper position and reports leverageOf`() {
        PerpsSandbox6463.resetForTest()
        PerpsSandbox6463.setEnabled(desired = true, paperMode = true)
        val pid = "PID_TEST_${System.nanoTime()}"
        val res = PerpsSandbox6463.openLeveragedPaper(
            positionId = pid, mint = "MINT", leverageX = 5.0,
            entryPx = 0.0001, paperMode = true,
        )
        assertEquals(PerpsSandbox6463.OpenResult.OPENED, res)
        assertEquals(5.0, PerpsSandbox6463.leverageOf(pid), 1e-9)
    }

    @Test
    fun `Perps sandbox 5x liquidates on 20 percent underlying drop`() {
        PerpsSandbox6463.resetForTest()
        PerpsSandbox6463.setEnabled(desired = true, paperMode = true)
        val pid = "PID_TEST_${System.nanoTime()}"
        PerpsSandbox6463.openLeveragedPaper(pid, "MINT", 5.0, 0.0001, paperMode = true)
        // 20% underlying drop × 5x leverage = 100% effective → liquidation.
        val verdict = PerpsSandbox6463.evaluateRiskExit(pid, underlyingDropPct = 20.0)
        assertEquals(PerpsSandbox6463.RiskExitVerdict.EXIT_LIQUIDATION, verdict)
    }

    @Test
    fun `Perps sandbox 2x on 10 percent underlying drop returns NO_ACTION`() {
        PerpsSandbox6463.resetForTest()
        PerpsSandbox6463.setEnabled(desired = true, paperMode = true)
        val pid = "PID_TEST_${System.nanoTime()}"
        PerpsSandbox6463.openLeveragedPaper(pid, "MINT", 2.0, 0.0001, paperMode = true)
        // 10% × 2 = 20% effective, below 60% warning band → no action.
        val verdict = PerpsSandbox6463.evaluateRiskExit(pid, underlyingDropPct = 10.0)
        assertEquals(PerpsSandbox6463.RiskExitVerdict.NO_ACTION, verdict)
    }

    @Test
    fun `Perps sandbox refuses invalid leverage bounds`() {
        PerpsSandbox6463.resetForTest()
        PerpsSandbox6463.setEnabled(desired = true, paperMode = true)
        val tooHigh = PerpsSandbox6463.openLeveragedPaper(
            "PID_TEST_HIGH_${System.nanoTime()}", "MINT", 25.0, 0.0001, paperMode = true,
        )
        assertEquals(PerpsSandbox6463.OpenResult.REFUSED_LEVERAGE_BOUNDS, tooHigh)
        val negative = PerpsSandbox6463.openLeveragedPaper(
            "PID_TEST_NEG_${System.nanoTime()}", "MINT", -1.0, 0.0001, paperMode = true,
        )
        assertEquals(PerpsSandbox6463.OpenResult.REFUSED_LEVERAGE_BOUNDS, negative)
    }

    // ─── PARTIAL-SELL CORRECTNESS ───────────────────────────────────────

    @Test
    fun `PartialSellCorrectness6463 statusLine is publishable`() {
        PartialSellCorrectness6463.resetForTest()
        val s = PartialSellCorrectness6463.statusLine()
        assertTrue("status must include validations=0: $s", s.contains("validations=0"))
    }
}
