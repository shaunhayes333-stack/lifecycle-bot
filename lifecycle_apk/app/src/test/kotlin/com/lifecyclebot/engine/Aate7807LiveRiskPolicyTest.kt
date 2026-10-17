package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
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
            assertTrue(b!!.maxConcurrentLive in 1..10)   // V5.0.8098 — owner: 10 live positions per lane
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
        assertEquals("LANE_SLOT_CAP_7807", LiveRiskPolicy7807.decide(inputs(open = LiveRiskPolicy7807.LANE_SLOTS_8098)).reason)
        assertEquals("COST_CONSUMES_MOVE_7807", LiveRiskPolicy7807.decide(inputs(first = 3.0)).reason)
        val tooWide = LiveRiskPolicy7807.decide(inputs(lane = "MOONSHOT", equity = 0.15, upstream = 0.027, execMin = 0.04, stop = 30.0, first = 60.0, target = 120.0))
        assertFalse(tooWide.open)
        assertEquals("RISK_SIZE_BELOW_EXECUTABLE_MINIMUM_7835", tooWide.reason)
        val atMin = LiveRiskPolicy7807.decide(inputs(equity = 0.15, upstream = 0.027, execMin = 0.025, stop = 8.0, first = 30.0))
        // superseded by V5.0.8188 — the risk size here is ~0.02150 SOL; promoted to 0.025 its loss at the stop (0.00291 SOL)
        // exceeds the original budget (0.00256 SOL) by 13.7% > 10%, so the minimum is refused, not sent (MinPromotion8188).
        assertFalse(atMin.open)
        assertEquals(com.lifecyclebot.engine.truth.MinPromotion8188.REFUSED_LOSS_BUDGET_8188, atMin.reason)
        val safeRouteFloor = LiveRiskPolicy7807.decide(inputs(
            lane = "MOONSHOT", equity = 0.2113, upstream = 0.02274,
            execMin = 0.0414, stop = 8.0, first = 30.0, target = 40.0, liq = 100_000.0,
        ))
        // superseded by V5.0.8188 — risk-safe and affordable is not enough: 0.02274 -> 0.0414 SOL (+82%) loses 0.0046 SOL at
        // the stop against a 0.0027 SOL budget, so it is refused by name (MinPromotion8188).
        assertFalse(safeRouteFloor.open)
        assertEquals(com.lifecyclebot.engine.truth.MinPromotion8188.REFUSED_LOSS_BUDGET_8188, safeRouteFloor.reason)
        assertEquals(0.0, safeRouteFloor.sizeSol, 0.0)
        assertFalse("RISK_SAFE_EXECUTABLE_MIN_PROMOTED_7840" in safeRouteFloor.labels)
        val big = LiveRiskPolicy7807.decide(inputs(equity = 10.0, upstream = 0.05, n = 40, mean = 40.0, net = 2.0, wr = 60.0))
        assertTrue(big.open && big.sizeSol <= 0.05 + 1e-12)
        val dd = LiveRiskPolicy7807.decide(inputs(equity = 1.0, upstream = 1.0, dd = 0.30))
        val noDd = LiveRiskPolicy7807.decide(inputs(equity = 1.0, upstream = 1.0, dd = 0.0))
        assertTrue(dd.sizeSol < noDd.sizeSol && dd.sizeSol > 0.0)
        val moonNoPlan = LiveRiskPolicy7807.decide(inputs(lane = "MOONSHOT", upstream = 1.0, stop = null, first = null, target = null))
        assertTrue("MOONSHOT_NO_PLAN_SIZE_7807" in moonNoPlan.labels)
    }


    @Test
    fun route_floor_can_bridge_18pct_cap_without_raising_global_cap() {
        val cap = LiveRiskPolicy7807.routeAwareSpendCap7842(
            walletSol = 0.2113,
            solUsd = 120.85,
            maxLiveBuySol = 2.0,
            // V5.0.7951 — the routable minimum is now ~0.0256 SOL ($3 / cost floor),
            // below an 18% cap of this wallet; a 10% share keeps the bridge case.
            walletSharePct = 0.10,
        )
        assertEquals(0.2113 * 0.10, cap.configuredWalletCapSol, 1e-9)
        assertTrue(cap.routableMinSol > cap.configuredWalletCapSol)
        assertTrue(cap.routeFloorLifted)
        // V5.0.8079 — the bridged floor carries the 3% FDG seal headroom (never above tradeable).
        assertEquals(minOf(cap.routableMinSol * 1.03, cap.tradeableSol), cap.maxSpendableSol, 1e-9)
        assertTrue(cap.maxSpendableSol < 0.2113 * 0.25)
    }

    @Test
    fun runtime_7840_sub_routable_case_resolves_to_executable_minimum() {
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.02284,
            laneName = "CRYPTO",
            walletSol = 0.2113,
            paperMode = false,
            laneRiskCapSol = 0.16906,
            laneMinExecutableSol = 0.04137,
            mint = "",
        )
        // superseded by V5.0.8188 — 0.02284 -> 0.04137 SOL (+81%) breaks the original loss budget at the stop: refused by name.
        assertFalse(r.trace(), r.executable)
        assertEquals(com.lifecyclebot.engine.truth.MinPromotion8188.REFUSED_LOSS_BUDGET_8188, r.reason)
        assertEquals(0.0, r.finalSizeSol, 0.0)
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
    fun sealedSpecialistLaneOwnsLastMileRisk7811() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("fun activeCanonicalIntentForMint7811"))
        assertTrue(gate.contains("LaneExecutionCoordinator.currentElection6600(mint)?.primaryLane"))

        val risk = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        // The risk policy now resolves on the caller's canonical decision lane and
        // never substitutes another candidate's active intent (runtimeInputs).
        assertFalse(risk.contains("activeCanonicalIntentForMint7811(\"LIVE\", mint)"))
        assertTrue(risk.contains("val effectiveLane7811 = canonicalLane(lane)"))
        assertTrue(risk.contains("laneOpenLive = laneOpenLive(effectiveLane7811)"))
    }


    // ── V5.0.8094 — approved candidate-specific compounding target vs an ordinary request ──

    private fun proven(lane: String, dd: Double = 0.0, ceiling: Double = 0.2281 * 0.25, kelly: Double = 0.20, liq: Double = 200_000.0) =
        inputs(lane = lane, equity = 0.2281, upstream = 0.02892, execMin = 0.02892, dd = dd, liq = liq)
            .copy(edgeKellyFrac8057 = kelly, compoundingCeilingSol8094 = ceiling)

    @Test
    fun a8094_verified_edge_exceeds_the_minimum_when_every_constraint_allows() {
        for (lane in listOf("SHITCOIN", "QUALITY")) {
            val d = LiveRiskPolicy7807.decide(proven(lane))
            assertTrue("$lane ${d.reason} ${d.sizeSol}", d.open && d.sizeSol > 0.03)
            assertTrue(d.sizeSol <= 0.2281 * LiveRiskPolicy7807.budgetFor(lane).maxPositionEquityFrac + 1e-9)   // lane cap kept
            assertTrue(d.sizeSol <= 0.2281 * 0.25 + 1e-9)                                                   // wallet ceiling kept
            assertTrue(d.sizing8094 != null)
        }
    }

    @Test
    fun a8094_an_ordinary_request_is_still_clamped_to_its_proposal() {
        // Same proven record, no approved ceiling: the policy never exceeds the upstream proposal (old safety property).
        val d = LiveRiskPolicy7807.decide(proven("SHITCOIN", ceiling = 0.0))
        assertTrue(d.sizeSol <= 0.02892 + 1e-9)
        // Unproven: no blanket increase, even with a ceiling offered.
        val u = LiveRiskPolicy7807.decide(proven("SHITCOIN", kelly = 0.0))
        assertTrue(u.sizeSol <= 0.02892 + 1e-9)
        assertTrue(u.sizing8094 == null)
    }

    @Test
    fun a8094_drawdown_does_not_shrink_a_proven_size_again_but_every_hard_cap_still_binds() {
        // 5.0.8095 (owner: drawdown stopped the balance growing): a proven size is a quarter-Kelly share of CURRENT
        // equity, which already fell with the drawdown, so it is not cut again. Lane cap and wallet ceiling still bind.
        val d = LiveRiskPolicy7807.decide(proven("SHITCOIN", dd = 0.259))
        val flat = LiveRiskPolicy7807.decide(proven("SHITCOIN"))
        assertTrue("${d.reason} ${d.sizeSol}", d.open && d.sizeSol > 0.03)
        assertEquals(flat.sizeSol, d.sizeSol, 1e-9)
        assertTrue(d.labels.contains("DRAWDOWN_SHRINK_WAIVED_PROVEN_EDGE_8094"))
        assertTrue(d.sizing8094?.bindingCap != "DRAWDOWN")
        assertTrue(d.sizeSol <= 0.2281 * LiveRiskPolicy7807.budgetFor("SHITCOIN").maxPositionEquityFrac + 1e-9)
        assertTrue(d.sizeSol <= 0.2281 * 0.25 + 1e-9)
        // An unproven request under the same drawdown is still shrunk (doctrine curve unchanged).
        assertTrue(LiveRiskPolicy7807.decide(proven("SHITCOIN", dd = 0.259, kelly = 0.0, ceiling = 0.0)).labels.contains("WALLET_DRAWDOWN_SHRINK_7807"))
    }

    @Test
    fun a8094_thin_depth_caps_a_proven_size() {
        // $400 pool: 3% one-way impact on half of it at $200/SOL = 0.03 SOL, below the 0.0456 Kelly size.
        val thin = LiveRiskPolicy7807.decide(proven("QUALITY", liq = 400.0))
        assertTrue("${thin.reason} ${thin.sizeSol}", !thin.open || thin.sizeSol <= 400.0 * 0.5 * 0.03 / 200.0 + 1e-9 || thin.sizeSol <= 0.02892 + 1e-9)
    }
}
