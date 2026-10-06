package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7807 — LiveRiskPolicy7807 (B5-B10). */
class Aate7807LiveRiskPolicyTest {

    private fun inputs(
        lane: String = "QUALITY",
        equity: Double = 1.0,
        upstream: Double = 0.18,
        execMin: Double = 0.025,
        stop: Double? = 10.0,
        first: Double? = 30.0,
        target: Double? = 40.0,
        liq: Double = 200_000.0,
        n: Int = 0,
        mean: Double = 0.0,
        net: Double = 0.0,
        wr: Double = 0.0,
        dd: Double = 0.0,
        daily: Double = 0.0,
        gov: Double = 1.0,
        open: Int = 0,
    ) = LiveRiskPolicy7807.Inputs(
        lane = lane, equitySol = equity, upstreamSol = upstream, execMinSol = execMin,
        planStopPct = stop, planFirstTargetPct = first, planTargetPct = target,
        solUsd = 200.0, liquidityUsd = liq, liveCloses = n, liveMeanNetPct = mean,
        liveTotalNetSol = net, liveWrPct = wr, drawdownFrac = dd, laneDailyLossSol = daily,
        governorLossMult = gov, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = open,
    )

    @Test
    fun drawdown_shrinks_linearly_to_035_and_never_zero() {
        assertEquals(1.0, LiveRiskPolicy7807.drawdownMultiplier(0.0), 1e-9)
        assertEquals(0.675, LiveRiskPolicy7807.drawdownMultiplier(0.15), 1e-9)
        assertEquals(0.35, LiveRiskPolicy7807.drawdownMultiplier(0.30), 1e-9)
        assertEquals(0.35, LiveRiskPolicy7807.drawdownMultiplier(0.90), 1e-9)
    }

    @Test
    fun no_growth_before_ten_positive_live_closes_and_shallow_shrink_under_ten() {
        // N=4, WR 0%, negative: may shrink, but not below 0.7 (B9).
        val young = LiveRiskPolicy7807.expectancyMultiplier(4, -60.0, -0.02)
        assertTrue(young < 1.0 && young >= 0.7)
        // Positive but young: no growth above 1.0.
        assertTrue(LiveRiskPolicy7807.expectancyMultiplier(9, 80.0, 0.5) <= 1.0)
        // Ten clean positive closes: growth allowed.
        assertTrue(LiveRiskPolicy7807.expectancyMultiplier(12, 30.0, 0.2) > 1.0)
        // Mature losing lane: deep shrink allowed, never below the floor.
        val bleeder = LiveRiskPolicy7807.expectancyMultiplier(40, -60.0, -1.0)
        assertTrue(bleeder < 0.7 && bleeder >= LiveRiskPolicy7807.LEARNED_FLOOR_7807)
        assertEquals(1.0, LiveRiskPolicy7807.expectancyMultiplier(0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun daily_loss_cap_shrinks_lane_never_pauses() {
        assertEquals(1.0, LiveRiskPolicy7807.dailyLossMultiplier(0.01, 0.04), 1e-9)
        assertEquals(0.5, LiveRiskPolicy7807.dailyLossMultiplier(0.04, 0.04), 1e-9)
        assertEquals(0.3, LiveRiskPolicy7807.dailyLossMultiplier(0.09, 0.04), 1e-9)
    }

    @Test
    fun lane_table_covers_every_lane_and_respects_slot_rules() {
        val lanes = listOf("QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "CORE", "MOONSHOT", "PROJECT_SNIPER",
            "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC")
        for (l in lanes) {
            val b = LiveRiskPolicy7807.LaneRiskBudget7807[l]
            assertTrue("missing $l", b != null)
            assertTrue(b!!.maxConcurrentLive in 1..6)
        }
        assertTrue(LiveRiskPolicy7807.LaneRiskBudget7807.values.sumOf { it.maxConcurrentLive } > 20)
        assertEquals(20, LiveRiskPolicy7807.TOTAL_LIVE_SLOTS_7807)
        assertEquals("PROJECT_SNIPER", LiveRiskPolicy7807.budgetFor("PRESALE_SNIPE").lane)
        assertEquals("BLUECHIP", LiveRiskPolicy7807.budgetFor("BLUE_CHIP").lane)
    }

    @Test
    fun admission_reduces_with_named_reasons_and_high_ev_runner_floor() {
        val (m, reasons) = LiveRiskPolicy7807.admissionMultiplier(
            LiveRiskPolicy7807.Admission(liveCloses = 0, liveWrPct = 0.0, partialProviderEvidence = true, oracleUnproven = true, highEvRunner = false),
        )
        assertTrue(m < 1.0 && m >= 0.5)
        assertTrue("NEW_SPECIALIST_NO_LIVE_SAMPLE_7807" in reasons)
        assertTrue("PARTIAL_PROVIDER_EVIDENCE_7807" in reasons)
        val (mr, _) = LiveRiskPolicy7807.admissionMultiplier(
            LiveRiskPolicy7807.Admission(liveCloses = 6, liveWrPct = 0.0, partialProviderEvidence = true, oracleUnproven = true, highEvRunner = true),
        )
        assertTrue(mr >= 0.70)
    }

    @Test
    fun cost_versus_move() {
        assertEquals(LiveRiskPolicy7807.CostVerdict.PASS, LiveRiskPolicy7807.costVerdict(8.0, 4.0))
        assertEquals(LiveRiskPolicy7807.CostVerdict.REDUCE, LiveRiskPolicy7807.costVerdict(11.0, 4.0))
        assertEquals(LiveRiskPolicy7807.CostVerdict.OK, LiveRiskPolicy7807.costVerdict(30.0, 4.0))
    }

    @Test
    fun size_is_risk_budget_over_stop_plus_cost() {
        assertEquals(0.1, LiveRiskPolicy7807.sizeFromInvalidation(1.0, 0.02, 1.0, 15.0, 5.0), 1e-9)
        assertTrue(LiveRiskPolicy7807.executableMinRiskOk(0.025, 15.0, 5.0, 0.15))
        assertFalse(LiveRiskPolicy7807.executableMinRiskOk(0.04, 25.0, 6.0, 0.15))
    }

    @Test
    fun decide_passes_per_candidate_and_never_grows_past_upstream() {
        assertEquals("LANE_SLOT_CAP_7807", LiveRiskPolicy7807.decide(inputs(open = 3)).reason)
        assertEquals("COST_CONSUMES_MOVE_7807", LiveRiskPolicy7807.decide(inputs(first = 3.0)).reason)
        val tooWide = LiveRiskPolicy7807.decide(inputs(lane = "MOONSHOT", equity = 0.15, upstream = 0.027, execMin = 0.04, stop = 30.0, first = 60.0, target = 120.0))
        assertFalse(tooWide.open)
        assertEquals("SIZE_BELOW_MIN_RISK_TOO_WIDE_7807", tooWide.reason)
        val atMin = LiveRiskPolicy7807.decide(inputs(equity = 0.15, upstream = 0.027, execMin = 0.025, stop = 8.0, first = 30.0))
        assertTrue(atMin.open)
        assertEquals(0.025, atMin.sizeSol, 1e-9)
        val safeRouteFloor = LiveRiskPolicy7807.decide(inputs(
            lane = "MOONSHOT", equity = 0.2113, upstream = 0.02274,
            execMin = 0.0414, stop = 8.0, first = 30.0, target = 40.0, liq = 100_000.0,
        ))
        assertTrue(safeRouteFloor.reason, safeRouteFloor.open)
        assertEquals("OPEN_RISK_SAFE_MIN_PROMOTED_7840", safeRouteFloor.reason)
        assertEquals(0.0414, safeRouteFloor.sizeSol, 1e-9)
        assertTrue("RISK_SAFE_EXECUTABLE_MIN_PROMOTED_7840" in safeRouteFloor.labels)
        val big = LiveRiskPolicy7807.decide(inputs(equity = 10.0, upstream = 0.05, n = 40, mean = 40.0, net = 2.0, wr = 60.0))
        assertTrue(big.open && big.sizeSol <= 0.05 + 1e-12)
        val dd = LiveRiskPolicy7807.decide(inputs(equity = 1.0, upstream = 1.0, dd = 0.30))
        val noDd = LiveRiskPolicy7807.decide(inputs(equity = 1.0, upstream = 1.0, dd = 0.0))
        assertTrue(dd.sizeSol < noDd.sizeSol && dd.sizeSol > 0.0)
        val moonNoPlan = LiveRiskPolicy7807.decide(inputs(lane = "MOONSHOT", upstream = 1.0, stop = null, first = null, target = null))
        assertTrue("MOONSHOT_NO_PLAN_SIZE_7807" in moonNoPlan.labels)
    }

    @Test
    fun held_reclaim_needs_three_fresh_marks_over_sixty_seconds_above_the_stop() {
        val stopAt = 1_000_000L
        val now = stopAt + 300_000L
        val held = listOf(now - 70_000L to 1.2, now - 40_000L to 1.3, now - 5_000L to 1.25)
        assertTrue(MintReEntryCooldown.heldReclaim7807(held, 1.0, stopAt, now))
        val brokeBack = listOf(now - 70_000L to 1.2, now - 40_000L to 0.9, now - 5_000L to 1.25)
        assertFalse(MintReEntryCooldown.heldReclaim7807(brokeBack, 1.0, stopAt, now))
        val tooShort = listOf(now - 20_000L to 1.2, now - 10_000L to 1.3, now - 5_000L to 1.25)
        assertFalse(MintReEntryCooldown.heldReclaim7807(tooShort, 1.0, stopAt, now))
        val stale = listOf(now - 200_000L to 1.2, now - 150_000L to 1.3, now - 100_000L to 1.25)
        assertFalse(MintReEntryCooldown.heldReclaim7807(stale, 1.0, stopAt, now))
        assertEquals(120_000L, MintReEntryCooldown.REENTRY_MIN_MS_7807)
    }

    @Test
    fun executor_wiring_contract() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("if (liveRiskPolicyPreTicketRefused7807(ts, layerTag, sol, walletSol)) return false"))
        assertTrue(ex.contains("sol = liveRiskPolicyFinalSize7807("))
        assertTrue(ex.contains("BUY_TERMINAL_LIVE_RISK_POLICY_7807"))
        assertTrue(ex.contains("PAPER_EV_BUCKET_SIZE_DOWN_NOT_REFUSED_7807"))
        assertFalse(ex.contains("LOSER_BUCKET_COOLDOWN_APPLIED_6405"))
        assertTrue(ex.contains("REENTRY_AFTER_STOP_7807"))
        assertTrue(ex.contains("LiveRiskPolicy7807.isHighEvRunner(ts.mint, gateLaneLive6451)"))
        // The final shape runs after the last-mile floor and before the executable floors.
        val lastMile = ex.indexOf("LiveSizingProfile.lastMileEntryFloor(")
        val finalShape = ex.indexOf("sol = liveRiskPolicyFinalSize7807(")
        val execFloor = ex.indexOf("LIVE_FINAL_EXECUTABLE_FLOOR_RESTORED_6687")
        assertTrue(lastMile in 0 until finalShape && finalShape < execFloor)
        val eea = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt").readText()
        assertTrue(eea.contains("cooling -> coolingMult7807"))
    }

    @Test
    fun sealedSpecialistLaneOwnsLastMileRisk7811() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("fun activeCanonicalIntentForMint7811"))
        assertTrue(gate.contains("LaneExecutionCoordinator.currentElection6600(mint)?.primaryLane"))

        val risk = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(risk.contains("activeCanonicalIntentForMint7811(\"LIVE\", mint)"))
        assertTrue(risk.contains("val effectiveLane7811 = sealedLane7811.ifBlank { canonicalLane(lane) }"))
        assertTrue(risk.contains("laneOpenLive = laneOpenLive(effectiveLane7811)"))
        assertTrue(risk.contains("LIVE_RISK_LANE_CONVERGED_TO_SEALED_INTENT_7811"))
    }

}
