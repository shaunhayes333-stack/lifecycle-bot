package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.MinPromotion8188
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PeakLedger8188
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8188 — the drawdown peak is a recorded event; a route-minimum promotion is re-evaluated at the promoted size. */
class Aate8188RiskTruthTest {

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @After fun tearDown() { PeakLedger8188.resetForTests8188() }

    // ── 1. high-water mark ──

    @Test fun anUnexplainedResidualThatNeverReachedTheWalletCannotRaiseAPastPeak() {
        val t0 = 1_000_000L
        val peak1528 = CapitalDrawdown7948.Peak7948(0.4488, t0)
        // 5.0.8186: the 15:28 peak became 0.7385 at the same time — a +0.2897 "external inflow" rebased it.
        assertEquals(0.7385, CapitalDrawdown7948.rebasedPeak7981(peak1528, 0.2897, 0.29, t0 + 1).peakSol, 1e-9)
        assertEquals(t0, CapitalDrawdown7948.rebasedPeak7981(peak1528, 0.2897, 0.29, t0 + 1).atMs)
        // The residual had no wallet leg (principal re-admitted / unrealised deeper than principal): not a deposit.
        assertEquals(0.0, PeakLedger8188.liquidCorroboratedFlow8188(0.2897, 0.0, 0.0), 0.0)
        assertFalse(CapitalDrawdown7948.isExternal7981(PeakLedger8188.liquidCorroboratedFlow8188(0.2897, 0.0, 0.0), 0.29))
        // Principal moved in the same window (a buy, a sell, a tracker flicker): a trade or an artefact, never a flow.
        assertEquals(0.0, PeakLedger8188.liquidCorroboratedFlow8188(0.10, 0.10, 0.0623), 0.0)
        // Opposite signs never corroborate.
        assertEquals(0.0, PeakLedger8188.liquidCorroboratedFlow8188(0.10, -0.10, 0.0), 0.0)
        // A real owner deposit / withdrawal moves liquid SOL with principal still: it rebases, at most by the wallet leg.
        assertEquals(0.10, PeakLedger8188.liquidCorroboratedFlow8188(0.10, 0.10, 0.0), 1e-12)
        assertEquals(0.024, PeakLedger8188.liquidCorroboratedFlow8188(0.30, 0.024, 0.0005), 1e-12)
        assertEquals(-0.05, PeakLedger8188.liquidCorroboratedFlow8188(-0.08, -0.05, 0.0), 1e-12)
        assertEquals(PeakLedger8188.REBASE_PARTIAL_8188, PeakLedger8188.rebaseVerdict8188(0.30, 0.024))
        assertEquals(PeakLedger8188.REBASE_APPLIED_8188, PeakLedger8188.rebaseVerdict8188(0.10, 0.10))
        assertEquals(PeakLedger8188.REBASE_REFUSED_8188, PeakLedger8188.rebaseVerdict8188(0.2897, 0.0))
        // The runtime path refuses it and records the refusal next to the peak (which does not move).
        val flow = PeakLedger8188.corroborate8188(0.2897, 0.0, 0.0, 0.4488, t0 + 2) { CapitalDrawdown7948.isExternal7981(it, 0.29) }
        assertEquals(0.0, flow, 0.0)
        assertEquals(PeakLedger8188.REBASE_REFUSED_8188, PeakLedger8188.rebases8188().last().verdict)
        assertEquals(0.4488, PeakLedger8188.rebases8188().last().toSol, 0.0)
    }

    @Test fun aPeakIsRecordedOnceWithItsComponentsAndRestoredUnchanged() {
        val t0 = 5_000_000L
        val ev = PeakLedger8188.PeakEvent8188(0.4488, t0, 0.2500, 0.1988, -0.0050, 0.0, PeakLedger8188.ORIGIN_NEW_HIGH_8188,
            "hero6503:CANONICAL ageMs=900 rev=12 liveOpen=4 lossIncluded")
        val enc = PeakLedger8188.encodeEvent8188(ev)
        assertEquals(ev, PeakLedger8188.decodeEvent8188(enc))
        assertNull(PeakLedger8188.decodeEvent8188("garbage"))
        val rb = listOf(PeakLedger8188.Rebase8188(t0 + 60_000L, 0.10, 0.10, 0.10, 0.4488, 0.5488, PeakLedger8188.REBASE_APPLIED_8188))
        assertEquals(rb, PeakLedger8188.decodeRebases8188(PeakLedger8188.encodeRebases8188(rb)))
        // Restore, then the seed of the SAME peak keeps the recorded event: a later read cannot revalue it.
        PeakLedger8188.restore8188(enc, PeakLedger8188.encodeRebases8188(rb))
        PeakLedger8188.noteSeed8188(0.7385, t0, nowMs = t0 + 10_000_000L)
        val kept = PeakLedger8188.currentEvent8188()
        assertNotNull(kept)
        assertEquals(0.4488, kept!!.recordedSol, 0.0)
        assertEquals(PeakLedger8188.ORIGIN_NEW_HIGH_8188, kept.origin)
        assertEquals(1, PeakLedger8188.rebases8188().size)
        val line = PeakLedger8188.line8188(CapitalDrawdown7948.Peak7948(0.5488, t0), t0 + 120_000L)
        assertTrue(line, line.contains("peakEvent8188=[recorded=0.4488"))
        assertTrue(line, line.contains("origin=NEW_HIGH liquid=0.2500 principal=0.1988"))
        assertTrue(line, line.contains("rebases8188=[applied=1 net=+0.1000"))
        // A peak saved before 8188 is named as such — kept, never reset.
        PeakLedger8188.noteSeed8188(0.7625, t0 - 1_000L, nowMs = t0 + 10_000_000L)
        assertEquals(PeakLedger8188.ORIGIN_RESTORED_NO_COMPONENTS_8188, PeakLedger8188.currentEvent8188()!!.origin)
        assertEquals(0.7625, PeakLedger8188.currentEvent8188()!!.recordedSol, 0.0)
    }

    @Test fun onlyVerifiedLiveComponentsSetAPeak() {
        assertTrue(PeakLedger8188.verifiedCandidate8188(0.30, 0.0, paperRuntime = true).isNaN())
        assertEquals(0.25, PeakLedger8188.verifiedCandidate8188(0.30, 0.05, paperRuntime = false), 1e-12)
        assertEquals(0.30, PeakLedger8188.verifiedCandidate8188(0.30, Double.NaN, paperRuntime = false), 1e-12)
        assertTrue(PeakLedger8188.unverifiedPrincipalSol8188() >= 0.0)
        assertEquals(0.001, PeakLedger8188.PRINCIPAL_STILL_SOL_8188, 0.0)
        assertTrue(setOf(PeakLedger8188.ORIGIN_SEEDED_8188, PeakLedger8188.ORIGIN_REANCHOR_8188, PeakLedger8188.ORIGIN_NEW_HIGH_8188).size == 3)
        val t = 10L * 24L * 3_600_000L
        assertNull(PeakLedger8188.moveOrigin8188(0.40, t, 0.40, t, t + 5))
        assertEquals(PeakLedger8188.ORIGIN_NEW_HIGH_8188, PeakLedger8188.moveOrigin8188(0.40, t, 0.41, t + 5, t + 5))
        assertEquals(PeakLedger8188.ORIGIN_WINDOW_ROLL_8188, PeakLedger8188.moveOrigin8188(0.40, t, 0.30, t + 25L * 3_600_000L, t + 25L * 3_600_000L))
        val cd = src("engine/truth/CapitalDrawdown7948.kt")
        assertTrue(cd.contains("PeakLedger8188.corroborate8188(residual8188, liq8188 - p.liq, prin8188 - p.prin, peak7948.peakSol, nowMs)"))
        assertTrue(cd.contains("PeakLedger8188.verifiedPeakCandidate8188(peakCandidate7990(gainFreeEquity8130(equitySol, upnl8130), prevPeakObs7990))"))
        assertTrue(cd.contains("PeakLedger8188.noteMove8188(beforeRoll8188, peak7948, nowMs)"))
        assertTrue(cd.contains("PeakLedger8188.line8188(peak7948, nowMs)"))
        val ks = src("engine/KillSwitch.kt")
        assertTrue(ks.contains("PeakLedger8188.restore8188(prefs.getString("))
        assertTrue(ks.contains("PeakLedger8188.persist8188 = { persistPeak8188(context.applicationContext) }"))
        assertTrue(ks.contains("peakAt7948 = System.currentTimeMillis()  // V5.0.8188 — a new peak value is a new peak event"))
    }

    // ── 2. route-minimum promotion ──

    @Test fun theOwnersExampleIsRefusedAtThePromotedNotional() {
        // 5.0.8186: risk-requested 0.02424 SOL, sent 0.02862 SOL (+18%) at a 60.8% drawdown. Stop 10%; round trip 3.69% at
        // the request, 3.43% at the minimum (the fixed legs weigh less on the larger ticket).
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188, MinPromotion8188.refusal8188(0.02424, 0.02862, 10.0, 3.69, 3.43, 20.0, 0.608))
        // Even with no drawdown: loss at the stop 0.003844 SOL against a 0.003318 SOL budget (+15.8% > 10%).
        assertEquals(0.003318, MinPromotion8188.lossAtStopSol8188(0.02424, 10.0, 3.69), 1e-6)
        assertEquals(0.003844, MinPromotion8188.lossAtStopSol8188(0.02862, 10.0, 3.43), 1e-6)
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, MinPromotion8188.refusal8188(0.02424, 0.02862, 10.0, 3.69, 3.43, 20.0, 0.0))
        // Within 10%: 0.0270 -> 0.02862 loses 0.003844 vs 0.003690 budget (+4.2%) and is promoted when the edge clears.
        assertNull(MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 20.0, 0.10))
        // ...but not when the expected edge after the round trip at the minimum is not positive, or unmeasured.
        assertEquals(MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 3.40, 0.0))
        assertEquals(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, Double.NaN, 0.0))
        // ...and never in the defensive drawdown tier, which is where 7807's drawdown multiplier reaches its floor.
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 20.0, 0.30))
        assertEquals(0.35, LiveRiskPolicy7807.drawdownMultiplier(MinPromotion8188.DEFENSIVE_DRAWDOWN_FRAC_8188), 1e-12)
        assertTrue(LiveRiskPolicy7807.drawdownMultiplier(0.29) > 0.35)
        // A size at or above the minimum is not a promotion.
        assertNull(MinPromotion8188.refusal8188(0.03, 0.02862, 10.0, 3.4, 3.43, Double.NaN, 0.9))
        assertEquals(0.10, MinPromotion8188.LOSS_TOLERANCE_8188, 0.0)
        assertTrue(MinPromotion8188.defensiveDrawdown8188(0.608) && !MinPromotion8188.defensiveDrawdown8188(0.29))
        assertTrue(MinPromotion8188.liveDrawdownFrac8188() in 0.0..1.0)
        assertTrue(MinPromotion8188.REFUSED_UNEVALUATED_8188.startsWith("ROUTE_MIN_PROMOTION_REFUSED_"))
        // No request, no budget.
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, MinPromotion8188.refusal8188(0.0, 0.02862, 10.0, Double.NaN, 3.43, 20.0, 0.0))
    }

    private fun quality(equity: Double, dd: Double, gross: Double) = LiveRiskPolicy7807.Inputs(
        lane = "QUALITY", equitySol = equity, upstreamSol = 0.025, execMinSol = 0.025,
        planStopPct = 8.0, planFirstTargetPct = 30.0, planTargetPct = 40.0,
        solUsd = 200.0, liquidityUsd = 200_000.0, liveCloses = 0, liveMeanNetPct = 0.0,
        liveTotalNetSol = 0.0, liveWrPct = 0.0, drawdownFrac = dd, laneDailyLossSol = 0.0,
        governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
        setupExpectedGrossPct8153 = gross,
    )

    @Test fun theFinalPolicyPromotesOnlyANearMinimumMeasuredEdgeOutsideTheDrawdownTier() {
        // 0.17 SOL equity: the risk size is ~0.02478 SOL; at 0.025 it loses 0.7% more at the stop: promoted.
        val ok = LiveRiskPolicy7807.decide(quality(0.17, 0.0, 20.0))
        assertTrue(ok.reason, ok.open)
        assertEquals("OPEN_RISK_SAFE_MIN_PROMOTED_7840", ok.reason)
        assertEquals(0.025, ok.sizeSol, 1e-12)
        // The same trade with no measured edge, or in a 30%+ drawdown, is refused by name.
        assertEquals(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188, LiveRiskPolicy7807.decide(quality(0.17, 0.0, Double.NaN)).reason)
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188, LiveRiskPolicy7807.decide(quality(0.17, 0.608, 20.0)).reason)
        // A smaller wallet's risk size (~0.0215 at 0.15 SOL) is too far below the minimum.
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, LiveRiskPolicy7807.decide(quality(0.15, 0.0, 20.0)).reason)
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("MinPromotion8188.refusal8188(size, i.execMinSol, stop,"))
    }

    @Test fun everyUpstreamPromotionPathAppliesTheSameRule() {
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.02424, laneName = "CRYPTO", walletSol = 0.30, paperMode = false,
            laneRiskCapSol = 0.30, laneMinExecutableSol = 0.02862, mint = "",
        )
        assertFalse(r.trace(), r.executable)
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, r.reason)
        // Paper promotion is untouched (paper never sizes live).
        val p = OrderSizeResolver6441.resolve(requestedSol = 0.021, laneName = "TEST", walletSol = 1.0, paperMode = true,
            laneRiskCapSol = 1.0, laneMinExecutableSol = 0.05, applyPaperMemeMinimum = false)
        assertFalse(p.reason.startsWith("ROUTE_MIN_PROMOTION_REFUSED"))
        assertTrue(src("engine/truth/OrderSizeResolver6441.kt").contains("promotionRefusal8188 != null -> promotionRefusal8188"))
        assertTrue(src("v3/sizing/SmartSizerV3.kt").contains("MinPromotion8188.runtimeRefusal8188("))
    }

    // ── 3. edge-based size-ups need validated evidence ──

    @Test fun forwardLabelsAloneNeverRaiseTheKellyShare() {
        // SHITCOIN|LAUNCH_LADDER_PROVEN 5.0.8186: forward n578 +103% (label share 0.20) with no independent proof -> 0.
        assertEquals(0.0, LiveRiskPolicy7807.validatedKellyFrac8188(0.20, 0.0, 0.0), 0.0)
        // Capped at the proof's own Kelly stake; labels may still lower it.
        assertEquals(0.05, LiveRiskPolicy7807.validatedKellyFrac8188(0.20, 0.05, 0.0), 1e-12)
        assertEquals(0.06, LiveRiskPolicy7807.validatedKellyFrac8188(0.20, 0.05, 0.06), 1e-12)
        assertEquals(0.02, LiveRiskPolicy7807.validatedKellyFrac8188(0.02, 0.05, 0.0), 1e-12)
        // Realised closes: n2 -47.9% is no proof; a mature positive record is; an outlier close is read at the cap.
        assertEquals(0.0, LiveRiskPolicy7807.realisedProofKellyFrac8188(listOf(-40.0, -55.8), listOf(-0.012, -0.016)), 0.0)
        val rets = List(12) { if (it % 3 == 0) -4.0 else 10.0 }
        val sol = List(12) { 0.01 }
        val proof = LiveRiskPolicy7807.realisedProofKellyFrac8188(rets, sol)
        assertTrue(proof > 0.0 && proof <= 0.30)
        val withOutlier = rets.toMutableList().also { it[1] = 5_000.0 }
        val atCap = rets.toMutableList().also { it[1] = 1_000.0 }
        assertEquals(LiveRiskPolicy7807.realisedProofKellyFrac8188(atCap, sol), LiveRiskPolicy7807.realisedProofKellyFrac8188(withOutlier, sol), 1e-12)
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains(
            "validatedKellyFrac8188(maxOf(setupFrac, cellFrac, kindFrac), laneRealisedProofFrac8188(l, System.currentTimeMillis()), WALK_FORWARD_KELLY_UNAVAILABLE_8188)"))
    }
}
