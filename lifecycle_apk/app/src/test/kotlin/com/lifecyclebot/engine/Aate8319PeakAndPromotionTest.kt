package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.MinPromotion8188
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8319 (owner's exact F1–F4): an inflow never raises the drawdown peak (only withdrawals rebase); the peak inflated by
 * pre-8319 inflow rebases is re-anchored once (live only, prefs flag); the upstream sizers defer edge-tier promotion refusals
 * to the last mile while no admission proof exists; an admitted candidate is promoted at the last mile without the
 * drawdown / exploration-slot refusals while the loss budget still binds.
 */
class Aate8319PeakAndPromotionTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun anInflowIsNotRebasedAndCountedWhileAWithdrawalStillRebases() {
        // 8316 shape: +mark residual of a quarantined / emergency sell on 0.3217 equity
        assertFalse(CapitalDrawdown7948.flowRebasesPeak8319(0.0450, 0.3217))
        assertFalse(CapitalDrawdown7948.flowRebasesPeak8319(0.1671, 0.3217))
        // a true withdrawal still rebases (peak moves down with it)
        assertTrue(CapitalDrawdown7948.flowRebasesPeak8319(-0.1000, 0.3217))
        val p = CapitalDrawdown7948.rebasedPeak7981(CapitalDrawdown7948.Peak7948(0.4000, 1_000L), -0.1000, 0.2900, 2_000L)
        assertEquals(0.3000, p.peakSol, 1e-9)
        assertEquals(1_000L, p.atMs)
        // too small to be external: never rebases either way
        assertFalse(CapitalDrawdown7948.flowRebasesPeak8319(-0.001, 0.3217))
        // a real deposit needs no rebase: equity above the peak sets a new high by itself
        val high = CapitalDrawdown7948.rollPeak7948(CapitalDrawdown7948.Peak7948(0.3217, 1_000L), 0.5217, 2_000L)
        assertEquals(0.5217, high.peakSol, 1e-9)

        val s = src("engine/truth/CapitalDrawdown7948.kt")
        val inflow = s.indexOf("} else if (isExternal7981(flow, equitySol) && flow > 0.0) {")
        val outflow = s.indexOf("} else if (isExternal7981(flow, equitySol)) {")
        val settlement = s.indexOf("PeakLedger8188.settlementExplains8223(flow, netLegs8223)")
        assertTrue(settlement in 1 until inflow)
        assertTrue(inflow in 1 until outflow)
        val branch = s.substring(inflow, outflow)
        assertTrue(branch.contains("DRAWDOWN_INFLOW_NOT_REBASED_8319"))
        assertTrue(branch.contains("inflowNotRebased8319.incrementAndGet()"))
        assertFalse(branch.contains("rebasedPeak7981"))
        assertTrue(s.contains("inflowNotRebased8319=\${inflowNotRebased8319.get()}"))
    }

    @Test fun thePeakIsReanchoredOnceLiveOnlyBehindAPrefsFlag() {
        assertTrue(PeakReanchorOnce8319.due8319(alreadyDone = false, live = true))
        assertFalse(PeakReanchorOnce8319.due8319(alreadyDone = true, live = true))    // idempotent once the flag is set
        assertFalse(PeakReanchorOnce8319.due8319(alreadyDone = false, live = false))  // never in paper
        assertEquals("aate_migrations_8319", PeakReanchorOnce8319.PREFS_8319)
        assertEquals("peak_reanchor_done", PeakReanchorOnce8319.FLAG_8319)
        val h = src("engine/PeakReanchorOnce8319.kt")
        // the flag is written only after a successful re-anchor
        val call = h.indexOf("KillSwitch.reanchorExplicitProofRun8093(context)")
        val flag = h.indexOf("p8319.edit().putBoolean(FLAG_8319, true).apply()")
        assertTrue(call in 1 until flag)
        assertTrue(h.contains("PEAK_REANCHORED_ONCE_8319"))
        assertTrue(src("engine/BotService.kt").contains("PeakReanchorOnce8319.maybeRun8319(applicationContext, loopCount)"))
        val k = src("engine/KillSwitch.kt").substringAfter("fun reanchorExplicitProofRun8093(").substringBefore("fun reset(")
        assertTrue(k.contains("if (RuntimeModeAuthority.isPaper()) return false"))
        assertTrue(k.contains("if (!equity.isFinite() || equity <= 0.0) return false"))
    }

    @Test fun upstreamEdgeRefusalsDeferOnlyWithoutAnAdmissionProof() {
        val neg = MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188
        // lane record negative, no admission proof: deferred to the last mile
        assertNull(MinPromotion8188.deferredEdge8319(neg, admissionProven = false))
        assertNull(MinPromotion8188.deferredEdge8319(MinPromotion8188.REFUSED_UNMEASURED_EDGE_8188, admissionProven = false))
        assertNull(MinPromotion8188.deferredEdge8319(MinPromotion8188.REFUSED_DRAWDOWN_8188, admissionProven = false))
        // the admission's own gross proven negative: still refuses
        assertEquals(neg, MinPromotion8188.deferredEdge8319(neg, admissionProven = true))
        // the loss budget (route minimum stays real) and an unevaluated read still refuse without a proof
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, MinPromotion8188.deferredEdge8319(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, false))
        assertEquals(MinPromotion8188.REFUSED_UNEVALUATED_8188, MinPromotion8188.deferredEdge8319(MinPromotion8188.REFUSED_UNEVALUATED_8188, false))
        assertNull(MinPromotion8188.deferredEdge8319(null, false))
        assertEquals("ROUTE_MIN_PROMOTION_EDGE_DEFERRED_TO_LAST_MILE_8319", MinPromotion8188.EDGE_DEFERRED_8319)
        val m = src("engine/truth/MinPromotion8188.kt")
        assertTrue(m.contains("deferredEdge8319(r8188, admissionGross8224 != null)"))
        assertTrue(m.contains("private val EDGE_TIER_REFUSALS_8319 = setOf(REFUSED_NEGATIVE_EDGE_8188, REFUSED_UNMEASURED_EDGE_8188, REFUSED_DRAWDOWN_8188)"))
    }

    @Test fun anAdmittedCandidateIsPromotedAtTheLastMileButTheLossBudgetStillRefuses() {
        // admitted args: drawdown 0.0, slot free — a 60.8% drawdown with a busy slot no longer refuses
        assertEquals(MinPromotion8188.REFUSED_DRAWDOWN_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 6.0, 0.608, explorationSlotFree = false))
        assertNull(MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 6.0, 0.0, explorationSlotFree = true))
        // the loss budget still binds for an admitted candidate
        assertEquals(MinPromotion8188.REFUSED_LOSS_BUDGET_8188, MinPromotion8188.refusal8188(0.02424, 0.02862, 10.0, 3.69, 3.43, 6.0, 0.0, explorationSlotFree = true))
        // a negative edge on the admission's own gross at the promoted size still refuses
        assertEquals(MinPromotion8188.REFUSED_NEGATIVE_EDGE_8188, MinPromotion8188.refusal8188(0.0270, 0.02862, 10.0, 3.67, 3.43, 3.40, 0.0, explorationSlotFree = true))
        val l = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(l.contains("val admissionProven8319: Boolean = false,"))
        assertTrue(l.contains("admissionProven8319 = proofGross8232 != null,"))
        assertTrue(l.contains("minCost7840, i.setupExpectedGrossPct8153, if (i.admissionProven8319) 0.0 else i.drawdownFrac,"))
        assertTrue(l.contains("explorationSlotFree = i.admissionProven8319 || (try { LaneScoreAdmission7308.runnerSlotFreeNow(i.lane) } catch (_: Throwable) { false }))"))
        assertTrue(l.contains("labels += \"RISK_SAFE_EXECUTABLE_MIN_PROMOTED_7840\"\n                if (i.admissionProven8319) labels += \"ADMITTED_MIN_PROMOTED_8319\""))
        assertTrue(l.contains("MinPromotion8188.doctrineBudgetSol8236(size, tradeable8153, i.solUsd),"))
    }
}
