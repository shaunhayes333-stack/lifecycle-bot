package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.StrategyMove8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — the economics test compares the right expected move to the real cost (5.0.8416: FDG allow 0 / block 9,286;
 * EXECUTION_ECONOMICS 2,680, SCALP_COIN_TOO_FLAT 1,365, DATA_INCOMPLETE_DEFER 6,682 MOONSHOT that never completed).
 */
class Aate8418EconomicsTruthTest {

    private fun move(gross: Double, basis: String, defer: Boolean = false) = StrategyMove8153.Move8153(gross, basis, defer)

    // ── sparse range is pre-history ──

    @Test fun aSparseRangeNeverCapsTheMoveAtAnyAge() {
        // An older coin seen for two candles "moved 2%": pre-8418 RANGE_SPARSE capped the move at 2% (refused vs 4.5% x1.5).
        val old = StrategyMove8153.expected8153(50.0, 2.0, false, null, 0, Double.NaN, Double.NaN, 4.5, false)
        assertTrue(old.deferForHistory)
        assertTrue(StrategyMove8153.needsDefer8153(old, 4.5))
        // A dense range is a measurement and still caps (the owner's 8035 rule stands).
        val dense = StrategyMove8153.expected8153(50.0, 2.0, true, null, 0, Double.NaN, Double.NaN, 4.5, false)
        assertEquals("RANGE", dense.basis)
        assertFalse(dense.deferForHistory)
        assertEquals(2.0, dense.expectedGrossPct, 1e-9)
        assertFalse(NetEdge8030.entryClears8030(dense.expectedGrossPct, 4.5))
        // A measured hour change decides beside a sparse range.
        val h1 = StrategyMove8153.expected8153(50.0, 2.0, false, 12.0, 0, Double.NaN, Double.NaN, 4.5, false)
        assertEquals(12.0, h1.expectedGrossPct, 1e-9)
        assertFalse(h1.deferForHistory)
    }

    @Test fun theScalpMoveReadsOnlyADenseRangeOrTheHour() {
        assertNull("two candles at intake are not the hour", NetEdge8030.realizedMove8418(1.0, false, null))
        assertEquals(1.0, NetEdge8030.realizedMove8418(1.0, true, null)!!, 1e-9)
        assertEquals(8.0, NetEdge8030.realizedMove8418(1.0, false, 8.0)!!, 1e-9)
        assertEquals(9.0, NetEdge8030.realizedMove8418(9.0, true, 8.0)!!, 1e-9)
        assertNull("a falling / unknown hour with no dense range is unobserved", NetEdge8030.realizedMove8418(null, false, -3.0))
        // A known flat coin is still too flat (3x rule unchanged).
        assertTrue(NetEdge8030.scalpMoveTooSmall8054(NetEdge8030.realizedMove8418(1.0, true, null), 4.5))
    }

    @Test fun anUnobservedScalpMoveWaitsThenEndsByNameNeverCalledFlat() {
        assertEquals(StrategyMove8153.DEFER_REASON_8153, NetEdge8030.scalpWait8418(null, null) { true })
        assertEquals(NetEdge8030.SCALP_MOVE_UNOBSERVED_8418, NetEdge8030.scalpWait8418(null, null) { false })
        assertNull("a known move goes to the 3x test", NetEdge8030.scalpWait8418(6.0, null) { true })
        // The wait is a data defer at the FDG; its end is a named refusal.
        assertEquals(FdgReceipt8397.Outcome.DEFER_DATA, FdgReceipt8397.outcome(false, StrategyMove8153.DEFER_REASON_8153))
        assertEquals(FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, NetEdge8030.SCALP_MOVE_UNOBSERVED_8418))
        // superseded by V5.0.8418 (buy-path review) — the admission's own supported setup no longer supplies an unobserved move:
        // the wait still ends by name (was assertNull(scalpWait8418(null, own) { false })).
        val own = CandidateAdmission8136.decide8136(Candidate8136(mint = "scalpOwn8418", lane = "CASHGEN", selectedSetup = "RANGE_LOW_BOUNCE",
            expectedGrossPct = 18.0, costPct = 4.0, costSizeSol = 0.08, expectedGrossBySetup = mapOf("RANGE_LOW_BOUNCE" to 18.0),
            setups = listOf(Evidence8136("SETUP", "RANGE_LOW_BOUNCE", 60, 14.0, 2.0)), genericScore = 70.0, genericFloor = 40.0))
        assertTrue(own.compact, own.admitted)
        assertEquals(NetEdge8030.SCALP_MOVE_UNOBSERVED_8418, NetEdge8030.scalpWait8418(null, own) { false })
        assertEquals(StrategyMove8153.DEFER_REASON_8153, NetEdge8030.scalpWait8418(null, own) { true })
    }

    @Test fun aScalpCoinWithNoObservedMoveIsDeferredNotCalledFlat() {
        val ts = com.lifecyclebot.data.TokenState(mint = "ScalpUnseen8418" + System.nanoTime(), symbol = "UNSEEN")
        // Pre-8418: realized == null -> NET_EDGE_SCALP_COIN_TOO_FLAT_8054 on a coin nobody had watched.
        assertEquals(StrategyMove8153.DEFER_REASON_8153, NetEdge8030.laneFitRefusal8054(ts, "CASHGEN"))
    }

    // ── the wrong clock may raise the move, never lower it ──

    @Test fun aFiveMinuteBookCannotLowerAnHourGradedLanesMove() {
        val strat = move(6.0, "STRATEGY")      // +1.5% net on the 5-minute read + 4.5% cost
        val fallback = move(50.0, "MANDATE")   // the lane mandate / the coin's own hour
        var asked = 0
        val spared = StrategyMove8153.wrongClockMove8418(strat, fallback) { asked++; true }
        assertEquals(50.0, spared.expectedGrossPct, 1e-9)
        assertEquals("MANDATE" + StrategyMove8153.WRONG_CLOCK_SUFFIX_8418, spared.basis)
        assertEquals(1, asked)
        assertTrue(NetEdge8030.entryClears8030(spared.expectedGrossPct, 4.5))
        // The right clock (hour book full, or a 5-minute lane): the record decides.
        assertEquals(6.0, StrategyMove8153.wrongClockMove8418(strat, fallback) { false }.expectedGrossPct, 1e-9)
        // A record that already says more is kept without asking; a deferring fallback never replaces it.
        var asked2 = 0
        assertEquals(60.0, StrategyMove8153.wrongClockMove8418(move(60.0, "STRATEGY"), fallback) { asked2++; true }.expectedGrossPct, 1e-9)
        assertEquals(6.0, StrategyMove8153.wrongClockMove8418(strat, move(50.0, "RANGE_SPARSE", true)) { asked2++; true }.expectedGrossPct, 1e-9)
        assertEquals(0, asked2)
        // A non-strategy estimate is never touched.
        assertEquals(3.0, StrategyMove8153.wrongClockMove8418(move(3.0, "RANGE"), fallback) { true }.expectedGrossPct, 1e-9)
        // The 8408 rule this consults: an hour-graded lane short of its hour book may not refuse on 5-minute evidence.
        assertFalse(com.lifecyclebot.engine.cortex.LaneGradeHorizon8406.fiveMinuteMayRefuse8408(true, 3L))
        assertTrue(com.lifecyclebot.engine.cortex.LaneGradeHorizon8406.fiveMinuteMayRefuse8408(false, 0L))
    }

    // ── the admission names why the move is unknown ──

    @Test fun theMoveWaitIsNamedAndJudgedAtTheCandidatesOwnMargin() {
        assertNull(NetEdge8030.moveWait8418(move(50.0, "MANDATE"), 4.5))
        assertEquals(NetEdge8030.MOVE_WAIT_FALLING_8418, NetEdge8030.moveWait8418(move(0.0, "FALLING_HOUR_UNPROVEN", true), 4.5))
        assertEquals(NetEdge8030.MOVE_WAIT_HISTORY_8418, NetEdge8030.moveWait8418(move(5.0, "RANGE_SPARSE", true), 4.5))
        assertNull("a sparse move that clears needs no wait", NetEdge8030.moveWait8418(move(40.0, "RANGE_SPARSE", true), 4.5))
        // Number at the candidate's own margin: 8.0 clears 1.5 x 4.5 but not an adaptive 2.0 x 4.5.
        val sparse8 = move(8.0, "RANGE_SPARSE", true)
        assertEquals(8.0, CandidateAdmission8136.grossOf8418(sparse8, 4.5, 1.5), 1e-9)
        assertTrue(CandidateAdmission8136.grossOf8418(sparse8, 4.5, 2.0).isNaN())
        assertEquals(NetEdge8030.MOVE_WAIT_HISTORY_8418, NetEdge8030.moveWait8418(sparse8, 4.5, 2.0))
        assertTrue(CandidateAdmission8136.grossOf8418(null, 4.5, 1.5).isNaN())
    }

    private fun base(wait: String?, cell: Evidence8136? = null) = Candidate8136(
        mint = "moveWait8418" + (wait ?: "none"), lane = "MOONSHOT", selectedSetup = "IGNITION_5M",
        expectedGrossPct = Double.NaN, costPct = 4.5, costSizeSol = 0.03, moveWait8418 = wait,
        expectedGrossBySetup = mapOf("IGNITION_5M" to Double.NaN), cell = cell, genericScore = 70.0, genericFloor = 40.0,
    )

    @Test fun aFallingHourIsRefusedByNameNotDeferredForever() {
        val d = CandidateAdmission8136.decide8136(base(NetEdge8030.MOVE_WAIT_FALLING_8418))
        assertEquals(d.compact, Terminal8136.REFUSE, d.terminal)
        assertEquals(CandidateAdmission8136.FALLING_HOUR_UNPROVEN_8418, d.reason)
        assertTrue(d.specific)
        assertEquals(FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, "CANDIDATE_ADMISSION_8136_${d.reason}"))
        // A supported net-of-cost edge still translates to a gross move (8171) and is judged on it.
        val proven = CandidateAdmission8136.decide8136(base(NetEdge8030.MOVE_WAIT_FALLING_8418, Evidence8136("CELL", "c", 60, 10.0, 2.0)))
        assertTrue(proven.compact, proven.admitted)
        assertTrue(proven.compact, proven.trace.any { it.startsWith("grossFromProvenNetLower8171") })
    }

    @Test fun aSparseHistoryDefersOnTheFeatureItsFetchAnswers() {
        val d = CandidateAdmission8136.decide8136(base(NetEdge8030.MOVE_WAIT_HISTORY_8418))
        assertEquals(d.compact, Terminal8136.DEFER_DATA, d.terminal)
        val missing = CandidateAdmission8136.missingOf8144(d)
        assertTrue(missing.toString(), StrategyMove8153.DEFER_FEATURE_8153 in missing)
        assertFalse(missing.toString(), "economics" in missing)
        // Still economics: binding (a confirmed run / positive record cannot pass it as an optional gap, RunnerDefer8297).
        assertTrue(CandidateAdmission8136.criticalMissing8170(StrategyMove8153.DEFER_FEATURE_8153))
        assertFalse(com.lifecyclebot.engine.truth.RunnerDefer8297.releases8297(d.terminal, missing, true))
        // The fetch asks for candles and the hour; their arrival answers the wait (resume on arrival).
        assertEquals(listOf("shape:HISTORY_SPARSE", "chg:UNOBSERVED"), DataDeferFetch8180.inputsFor8180(missing))
        assertTrue(FdgReceipt8397.answersFeature(StrategyMove8153.DEFER_FEATURE_8153, setOf("shape")))
        assertTrue(FdgReceipt8397.answersFeature(StrategyMove8153.DEFER_FEATURE_8153, setOf("chg")))
        assertFalse(FdgReceipt8397.answersFeature(StrategyMove8153.DEFER_FEATURE_8153, setOf("liq")))
        // No reason known (cost unknown): the old "economics" wait stands.
        val unknown = CandidateAdmission8136.decide8136(base(null))
        assertTrue(CandidateAdmission8136.missingOf8144(unknown).contains("economics"))
    }

    // ── a spent data wait ends ──

    @Test fun aSpentDataWindowEndsUnderANamedTerminal() {
        assertNull("inside the window the wait continues", CandidateAdmission8136.deferTerminal8418(true, true, listOf("liq:UNKNOWN")))
        assertEquals(CandidateAdmission8136.DATA_NEVER_ARRIVED_8418, CandidateAdmission8136.deferTerminal8418(false, true, listOf("liq:UNKNOWN")))
        assertEquals(CandidateAdmission8136.DATA_WAIT_SPENT_8418, CandidateAdmission8136.deferTerminal8418(false, false, listOf("shape:UNKNOWN")))
        for (r in listOf(CandidateAdmission8136.DATA_NEVER_ARRIVED_8418, CandidateAdmission8136.DATA_WAIT_SPENT_8418)) {
            assertEquals(r, FdgReceipt8397.Outcome.REFUSE, FdgReceipt8397.outcome(false, r))
            assertEquals(r, Terminal8136.REFUSE, CandidateAdmission8136.terminalOf8136("DEFER", r))
        }
        assertTrue(CandidateAdmission8136.statusLine8136().contains("deferTerminal8418{"))
        // The wiring: the exhausted branch returns the named terminal.
        val le = java.io.File(listOf("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt",
            "app/src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt",
            "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").first { java.io.File(it).exists() }).readText()
        assertTrue(le.contains("CandidateAdmission8136.deferTerminal8418(budgetOpen8204, unanswered8204, missing) ?: reason"))
        // Window budget: 40 re-evaluations inside 10 minutes, then spent; the 30-minute reset re-opens it (no permanent verdict).
        val s = CandidateAdmission8136.Defer8136(0L, CandidateAdmission8136.DEFER_MAX_8136 + 1)
        assertFalse(CandidateAdmission8136.deferOpen8136(s, 1_000L))
        assertEquals(1, CandidateAdmission8136.nextDefer8136(s, CandidateAdmission8136.DEFER_RESET_MS_8136).count)
    }

    // ── depth: unknown depth is never costed into an admit ──

    @Test fun unknownDepthDefersAndAKnownCostStillRefusesAShortMove() {
        val unknownDepth = CandidateAdmission8136.decide8136(Candidate8136(mint = "depth8418", lane = "MOONSHOT", selectedSetup = null,
            expectedGrossPct = 50.0, costPct = 4.5, costSizeSol = 0.03, missingInputs = listOf("liquidity:UNKNOWN"),
            genericScore = 70.0, genericFloor = 40.0))
        assertEquals(unknownDepth.compact, Terminal8136.DEFER_DATA, unknownDepth.terminal)
        assertTrue(unknownDepth.trace.any { it.startsWith("criticalUnobserved8170=") })
        // Economics is checked at the quoted size's cost with the owner's 1.5x: 6.0 < 1.5 x 4.5.
        val short = CandidateAdmission8136.decide8136(Candidate8136(mint = "short8418", lane = "MOONSHOT", selectedSetup = null,
            expectedGrossPct = 6.0, costPct = 4.5, costSizeSol = 0.03, genericScore = 70.0, genericFloor = 40.0))
        assertEquals("EXECUTION_ECONOMICS_8136", short.reason)
        assertEquals(0.03, short.costSizeSol, 1e-12)
        val clears = CandidateAdmission8136.decide8136(Candidate8136(mint = "clears8418", lane = "MOONSHOT", selectedSetup = null,
            expectedGrossPct = 6.75, costPct = 4.5, costSizeSol = 0.03, genericScore = 70.0, genericFloor = 40.0))
        assertFalse(clears.compact, clears.reason == "EXECUTION_ECONOMICS_8136")
    }
}
