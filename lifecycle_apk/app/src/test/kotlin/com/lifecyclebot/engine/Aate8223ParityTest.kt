package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperExitLatency8223
import com.lifecyclebot.engine.truth.PaperUniverseParity8223
import com.lifecyclebot.engine.truth.PeakLedger8188
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8223 — paper trades the live universe and pays the live exit latency; a sell's settlement never rebases the peak. */
class Aate8223ParityTest {

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── 1. peak ledger: settlement lag is not a deposit ──

    @Test fun aSellsProceedsLandingAfterItsJournalRowAreNotADeposit() {
        // residual +0.08 corroborated by liquid +0.08; the bot sold 0.081 SOL of tokens inside the lag window.
        assertTrue(PeakLedger8188.settlementExplains8223(0.08, 0.081))
        // a buy's spend landing before its row: outflow explained by the buy leg
        assertTrue(PeakLedger8188.settlementExplains8223(-0.05, -0.05))
        // an owner deposit larger than anything the bot sold is still a deposit
        assertFalse(PeakLedger8188.settlementExplains8223(0.50, 0.08))
        // opposite signs never explain each other; nothing traded explains nothing
        assertFalse(PeakLedger8188.settlementExplains8223(0.08, -0.08))
        assertFalse(PeakLedger8188.settlementExplains8223(0.08, 0.0))
        assertFalse(PeakLedger8188.settlementExplains8223(Double.NaN, 0.08))
    }

    @Test fun theDrawdownConfirmBranchAsksTheSettlementQuestionBeforeRebasing() {
        val s = src("engine/truth/CapitalDrawdown7948.kt")
        assertTrue(s.contains("PeakLedger8188.settlementExplains8223(flow, netLegs8223)"))
        assertTrue(s.contains("PeakLedger8188.refuseSettlement8223("))
        assertTrue(s.indexOf("settlementExplains8223") < s.indexOf("peak7948 = rebasedPeak7981(peak7948, flow, equitySol, nowMs)"))
    }

    // ── 2. paper universe = live universe ──

    private fun wall(liq: Double = 5_000.0, hard: Boolean = false, rug: Int = 50, mint: Boolean? = true, freeze: Boolean? = true,
                     top: Double = 10.0, black: Boolean = false, quar: Boolean = false) =
        PaperUniverseParity8223.wall8223(PaperUniverseParity8223.Wall8223(liq, hard, rug, mint, freeze, top, black, quar))

    @Test fun paperRefusesExactlyTheWallsLiveWouldHit() {
        assertNull(wall())
        assertEquals("LIQUIDITY_BELOW_EXIT_SAFE_FLOOR", wall(liq = 192.0))
        assertEquals("SAFETY_HARD_BLOCK", wall(hard = true))
        assertEquals("RUGCHECK_CONFIRMED_RUG", wall(rug = 0))
        assertEquals("MINT_AUTHORITY_ACTIVE", wall(mint = false))
        assertEquals("FREEZE_AUTHORITY_ACTIVE", wall(freeze = false))
        assertEquals("TOP_HOLDER_CONCENTRATION", wall(top = 35.0))
        assertEquals("EXACT_MINT_BLACKLISTED", wall(black = true))
        assertEquals("EXACT_MINT_QUARANTINED", wall(quar = true))
    }

    @Test fun pendingProofsStayPaperSampled() {
        // live DEFERS unknown authorities / holders / liquidity; paper keeps learning there
        assertNull(wall(mint = null, freeze = null, top = -1.0))
        assertNull(wall(liq = 0.0))
        assertNull(wall(rug = -1))
    }

    @Test fun thePaperBuyDoorConsultsTheWallBeforeAnyMarkOrSizeWork() {
        val s = src("engine/Executor.kt")
        val wallAt = s.indexOf("PaperUniverseParity8223.refusal8223(ts")
        assertTrue(wallAt > 0)
        assertTrue(s.contains("markPaperBuyNotOpened(\"LIVE_WALL_8223_\$liveWall8223\")"))
        assertTrue(wallAt < s.indexOf("val now6616 = System.currentTimeMillis()"))
    }

    // ── 3. paper exit pays the live latency ──

    @Test fun theLatencyIsThePriorUntilThreeLiveExitsMeasuredIt() {
        assertEquals(16_000L, PaperExitLatency8223.latencyFrom8223(0, 0, 16_000L))
        assertEquals(16_000L, PaperExitLatency8223.latencyFrom8223(2, 40_000L, 16_000L))
        assertEquals(12_000L, PaperExitLatency8223.latencyFrom8223(3, 36_000L, 16_000L))
        assertEquals(500L, PaperExitLatency8223.latencyFrom8223(5, 100L, 16_000L))
    }

    @Test fun onlyAnAdverseMoveOverTheLatencyIsCharged() {
        // fell 10% over the last 16 s; the live fill lands 16 s after the trigger: another -10%
        assertEquals(-10.0, PaperExitLatency8223.adverseDriftPct8223(0.90, 1.00, 16_000L, 16_000L), 1e-9)
        // same velocity, half the latency: half the drift
        assertEquals(-5.0, PaperExitLatency8223.adverseDriftPct8223(0.90, 1.00, 16_000L, 8_000L), 1e-9)
        // a rising coin does not fill paper better than its trigger
        assertEquals(0.0, PaperExitLatency8223.adverseDriftPct8223(1.10, 1.00, 16_000L, 16_000L), 0.0)
        // a dust-pool collapse is bounded (impact and the -100% floor are charged elsewhere)
        assertEquals(-60.0, PaperExitLatency8223.adverseDriftPct8223(0.10, 1.00, 16_000L, 16_000L), 1e-9)
        // a one-tick reference is noise, not velocity
        assertEquals(0.0, PaperExitLatency8223.adverseDriftPct8223(0.90, 1.00, 1_000L, 16_000L), 0.0)
    }

    @Test fun thePaperSellChargesImpactOnTheExecutableMarkAndTheSpineFeedsTheMeasurement() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("PaperExitLatency8223.paperFill8223(ts.mint, price, reason)"))
        assertTrue(e.contains("var effectivePrice = executablePrice8223 * slippageMultiplier"))
        val spine = src("engine/sell/SellRouteSpine8144.kt")
        assertTrue(spine.contains("PaperExitLatency8223.noteLive8223(t.emergency, t.triggerToBroadcastMs())"))
    }

    // ── 4. one canonical prior per candidate generation ──

    @Test fun theFdgSnapshotPriorIsCarriedToEveryLaterAssessmentOfTheSameGeneration() {
        val s = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(s.contains("snapshotPriors8223[priorKey8223] = it"))
        assertTrue(s.contains("ADMISSION_PRIOR_CARRIED_8223_"))
        assertTrue(s.contains("built.copy(genericScore = prior8223.score, genericFloor = prior8223.floor)"))
    }

    // ── 5. a persistent receiver data gap re-fetches and re-arms ──

    @Test fun aPersistentDataGapReFetchesInsteadOfStandingAsADeadDeferral() {
        val s = src("engine/HandoffThroughput8190.kt")
        assertTrue(s.contains("else if (v == DEFER_DATA_GAP_PERSISTENT_8221)"))
        assertTrue(s.contains("inputDeferredAt8190[key] = nowMs"))
    }

    // ── 6. an entry mark that contradicts the fill no longer discards the measured sell side ──

    @Test fun aContradictedEntryMarkStillLearnsTheSellSide() {
        val raw = java.math.BigInteger.valueOf(1_000_000L)
        // spent 1.0 SOL for 1.0 token (6 dp) when the market (3 s before the fill) read $200 at SOL=$100: "bought" at
        // 2x the market → buy side +50%, beyond any slippage bound → the entry mark is not our price.
        val sale = com.lifecyclebot.engine.truth.TradeCostMeasurement8090.Sale(raw, 1.90, 200.0, 100.0)
        val sides = com.lifecyclebot.engine.truth.CostSides8169.sides8169(1.0, 0.001, raw, 6, 400.0, 100.0, listOf(sale))
        assertEquals(com.lifecyclebot.engine.truth.CostSides8169.Verdict8169.SELL_SIDE_ONLY, sides.verdict)
        // the sell leg's market value was 2.0 SOL and the wallet received 1.90: a 10% sell side over the 1.0 spent
        assertEquals(10.0, sides.sellPct, 1e-6)
        assertTrue(sides.costPct.isNaN())
        // both sides contradicted: still unlearnable
        val badSale = com.lifecyclebot.engine.truth.TradeCostMeasurement8090.Sale(raw, 2.50, 200.0, 100.0)
        assertEquals(com.lifecyclebot.engine.truth.CostSides8169.Verdict8169.MARK_INCONSISTENT,
            com.lifecyclebot.engine.truth.CostSides8169.sides8169(1.0, 0.001, raw, 6, 400.0, 100.0, listOf(badSale)).verdict)
        val ledger = src("engine/truth/CostLedger7962.kt")
        assertTrue(ledger.contains("noteSellOnly8223(lane(e.lane), sides.sellPct)"))
        assertTrue(ledger.contains("+ sellOnlyOf8223(l)"))
    }

    // ── 7. one economic basis: promotion is judged on the admission's own gross and cost model ──

    @Test fun aRouteMinimumPromotionIsJudgedOnTheAdmissionsOwnBasis() {
        val p = com.lifecyclebot.engine.truth.AdmissionEdge8180.Proof8198("LIVE", "m", "MOONSHOT", 7L, "b", "LAUNCH_EARLY", 0L,
            expectedNetPct = 12.0, costPct = 4.5, costSizeSol = 0.03)
        assertEquals(16.5, p.expectedGrossPct, 1e-9)
        assertTrue(com.lifecyclebot.engine.truth.AdmissionEdge8180.Proof8198("LIVE", "m", "MOONSHOT", 7L, "b", null, 0L).expectedGrossPct.isNaN())
        val s = src("engine/truth/MinPromotion8188.kt")
        assertTrue(s.contains("fun cost(s: Double): Double = NetEdge8030.costPct8030(l, s, liq, mint, mcap)"))
        assertFalse(s.contains("LiveRiskPolicy7807.roundTripCostPct(s, solUsd, liq)"))
        assertTrue(s.contains("AdmissionEdge8180.proof8198(\"LIVE\", mint, l, version)"))
        // V5.0.8232 — the sizer nets the admission's own gross at the size and costs it with the same component model.
        val risk = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(risk.contains("val gross8153 = proofGross8232 ?: ts8153?.let { setupExpectedGrossPct8153(it, effectiveLane7811, rec8153) } ?: Double.NaN"))
        assertTrue(risk.contains("return if (real.isFinite() && real > 0.0) real else model"))
    }

    // ── 8. the shadow book observes what live wanted and could not take ──

    @Test fun theShadowBookOpensOnAnAdmittedCandidateTheFdgBlockedInLiveOnly() {
        val f = com.lifecyclebot.engine.FdgOutcomeContinuity8197
        assertEquals("admitted_blocked:NET_EV_NONPOSITIVE_AT_SIZE_8153", f.shadowOpenReason8233(true, true, "NET_EV_NONPOSITIVE_AT_SIZE_8153"))
        assertNull(f.shadowOpenReason8233(false, true, "X"))   // paper mode has its own book
        assertNull(f.shadowOpenReason8233(true, false, "X"))   // never admitted: not what live wanted
        assertNull(f.shadowOpenReason8233(true, true, null))   // an allow is the live path's business
        val s = src("engine/FdgOutcomeContinuity8197.kt")
        assertTrue(s.contains("shadowObserveAdmittedBlock8233(ts, lane, version, result, proposedSizeSol, laneScore)"))
        assertTrue(s.contains("executor.shadowObserve8233(ts, sol, laneScore, lane, reason)"))
    }

    // ── 9. the route-minimum loss budget is the account's doctrine, not a ticket that never routed ──

    @Test fun aLaneAllocationUnderTheRoutableMinimumIsBudgetedByTheDoctrineNotByItself() {
        val mp = com.lifecyclebot.engine.truth.MinPromotion8188
        // QUALITY asked 0.0199 on a 0.32 SOL wallet; the routable minimum is 0.0285; stop 10%; cost ~6%; gross 15%.
        assertEquals(mp.REFUSED_LOSS_BUDGET_8188, mp.refusal8188(0.0199, 0.0285, 10.0, 6.3, 6.25, 15.0, 0.05))
        // the same promotion inside the doctrine's safe share cap (0.078) is within budget and clears
        assertNull(mp.refusal8188(0.0199, 0.0285, 10.0, 6.3, 6.25, 15.0, 0.05, budgetSol = 0.078))
        // a doctrine below the routable minimum still refuses; a negative edge still refuses
        assertEquals(mp.REFUSED_LOSS_BUDGET_8188, mp.refusal8188(0.0199, 0.0285, 10.0, 6.3, 6.25, 15.0, 0.05, budgetSol = 0.021))
        assertEquals(mp.REFUSED_NEGATIVE_EDGE_8188, mp.refusal8188(0.0199, 0.0285, 10.0, 6.3, 6.25, 5.0, 0.05, budgetSol = 0.078))
        val src = src("engine/truth/MinPromotion8188.kt")
        assertTrue(src.contains("routableCapacityPreflight7224(tradeable, solUsd).safeShareCapSol"))
    }
}
