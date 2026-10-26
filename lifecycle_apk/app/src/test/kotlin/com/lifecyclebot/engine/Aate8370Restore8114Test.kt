package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.FdgGate8365
import com.lifecyclebot.engine.truth.Kelly8365
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.SmallStake8370
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8370 — three 8114 behaviours the owner chose to restore (379ad2fa0, the profitable 8114 build):
 *  1. "Small stakes trade": a Kelly / risk stake under the route minimum is promoted to the minimum inside every hard cap.
 *  2. "Runner trail 35% from peak": a held runner's floor is max(owner 8337 3a rule, 65 % of the peak price).
 *  3. "No fired-setup rule": the 8364 rule is telemetry; an M: band niche needs no named setup.
 */
class Aate8370Restore8114Test {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    // ── 1. small stakes trade ──

    @Test fun smallStakeRefusalsAreLabelsNotRefusals() {
        // The 8365 / 8329 classification still names a stake far under the minimum ...
        val kelly = EntryEconomics8329.kellyRefusal8365(0.002, 0.025, EntryEconomics8329.PROMOTION_UNSUPPORTED_8329 + "_QUALITY")
        assertEquals(Kelly8365.KELLY_TOO_SMALL_8365, kelly)
        // ... but it is a label only (counted under SMALL_STAKE_PROMOTED_8370_<gate>); decide promotes (test below).
        val k0 = SmallStake8370.count8370(Kelly8365.KELLY_TOO_SMALL_8365)
        val u0 = SmallStake8370.count8370(EntryEconomics8329.PROMOTION_UNSUPPORTED_8329)
        SmallStake8370.telemetry8370(kelly)
        SmallStake8370.telemetry8370(EntryEconomics8329.PROMOTION_UNSUPPORTED_8329 + "_QUALITY")
        SmallStake8370.telemetry8370(null)
        assertTrue(SmallStake8370.count8370(Kelly8365.KELLY_TOO_SMALL_8365) >= k0 + 1L)
        assertTrue(SmallStake8370.count8370(EntryEconomics8329.PROMOTION_UNSUPPORTED_8329) >= u0 + 1L)
        assertEquals(EntryEconomics8329.PROMOTION_UNSUPPORTED_8329, SmallStake8370.key8370(EntryEconomics8329.PROMOTION_UNSUPPORTED_8329 + "_MOONSHOT"))
        assertEquals(Kelly8365.KELLY_TOO_SMALL_8365, SmallStake8370.key8370(Kelly8365.KELLY_TOO_SMALL_8365))
        assertTrue(SmallStake8370.statusLine8370().contains("smallStake8370[labelOnly "))
        assertEquals("SMALL_STAKE_PROMOTED_8370_", SmallStake8370.LABEL_PREFIX_8370)
    }

    @Test fun the8114PromotionRuleWithItsCaps() {
        // 8114 (LiveRiskPolicy7807 at 379ad2fa0): the minimum replaces a smaller stake when it fits max(lane cap, small-account
        // share), the depth cap, and its loss at the stop fits the hard per-trade cap.
        assertEquals(0.025, SmallStake8370.promoted8370(0.001, 0.025, 0.02, 0.05, 1.0, 0.0025, 0.0136), 1e-12)
        assertEquals("over the lane cap and the small-account share", 0.0,
            SmallStake8370.promoted8370(0.001, 0.025, 0.02, 0.015, 1.0, 0.0025, 0.0136), 0.0)
        assertEquals("over the depth cap", 0.0, SmallStake8370.promoted8370(0.001, 0.025, 0.05, 0.05, 0.02, 0.0025, 0.0136), 0.0)
        assertEquals("loss at the stop over the hard cap", 0.0, SmallStake8370.promoted8370(0.001, 0.025, 0.05, 0.05, 1.0, 0.02, 0.0136), 0.0)
        // above the minimum the (Kelly) stake is used unchanged
        assertEquals(0.04, SmallStake8370.promoted8370(0.04, 0.025, 0.05, 0.05, 1.0, 0.004, 0.0136), 1e-12)
    }

    private fun quality(equity: Double, upstream: Double, tradeable: Double = Double.NaN) = LiveRiskPolicy7807.Inputs(
        lane = "QUALITY", equitySol = equity, upstreamSol = upstream, execMinSol = 0.025,
        planStopPct = 8.0, planFirstTargetPct = 30.0, planTargetPct = 40.0,
        solUsd = 200.0, liquidityUsd = 200_000.0, liveCloses = 0, liveMeanNetPct = 0.0,
        liveTotalNetSol = 0.0, liveWrPct = 0.0, drawdownFrac = 0.0, laneDailyLossSol = 0.0,
        governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
        setupExpectedGrossPct8153 = 20.0, tradeableSol8153 = tradeable,
    )

    @Test fun aStakeFarUnderTheMinimumIsPromotedAndTradedInsideTheCaps() {
        // 0.002 SOL requested (8 % of the 0.025 minimum, no named reason): 8365 refused KELLY_TOO_SMALL_FOR_ROUTE_8365.
        // V5.0.8392 — the loss budget is the account's own safe share (8236), read from the decision's tradeable cash: an
        // unknown wallet is never guessed (the removed 8114 mode waived the budget outright).
        val d = LiveRiskPolicy7807.decide(quality(0.17, 0.002, tradeable = 0.17))
        assertTrue(d.reason, d.open)
        assertEquals("OPEN_RISK_SAFE_MIN_PROMOTED_7840", d.reason)
        assertEquals(0.025, d.sizeSol, 1e-12)
        // Every hard cap still binds at the promoted size: tradeable cash after the reserve ...
        val cash = LiveRiskPolicy7807.decide(quality(0.17, 0.002, tradeable = 0.01))
        assertFalse(cash.reason, cash.open)
        assertEquals("ROUTE_MIN_EXCEEDS_TRADEABLE_8153", cash.reason)
        // ... and the position cap / hard loss on a wallet too small to carry the minimum.
        val tiny = LiveRiskPolicy7807.decide(quality(0.02, 0.002))   // the minimum is over 100 % of this wallet
        assertFalse(tiny.reason, tiny.open)
        assertNotEquals(Kelly8365.KELLY_TOO_SMALL_8365, tiny.reason)
    }

    // ── 2. runner trail 35 % from the peak price, the owner's 3a rule beneath it ──

    @Test fun runnerFloorIsThe8114TrailOverThe8337Rule() {
        assertEquals(0.65, GreenFloor8363.TRAIL_KEEP_PRICE_FRACTION_8370, 0.0)
        // the owner's 8337 3a rule: from a +20 % peak, at least 40 % of the peak
        assertEquals(20.0, GreenFloor8363.RULE_3A_PEAK_PCT_8370, 0.0)
        assertEquals(0.4, GreenFloor8363.RULE_3A_KEEP_FRACTION_8370, 0.0)
        assertEquals(40.0, GreenFloor8363.rule8337Floor8370(100.0, 3.0), 1e-9)
        assertEquals(3.5, GreenFloor8363.rule8337Floor8370(15.0, 3.0), 1e-9)
        assertEquals(30.0, GreenFloor8363.trail8370(100.0), 1e-9)          // 0.65 x 2.0 = 1.3
        assertEquals(160.0, GreenFloor8363.trail8370(300.0), 1e-9)         // 0.65 x 4.0 = 2.6
        // peak +100 %: max(3a 40 % of 100, trail +30) = +40 %
        assertEquals(40.0, GreenFloor8363.floor8363(100.0, 3.0), 1e-9)
        // peak +300 %: max(3a +120, trail +160) = +160 %
        assertEquals(160.0, GreenFloor8363.floor8363(300.0, 3.0), 1e-9)
        // the 3a rule binds when the cost model reads high: +22 % peak, 50 % round trip -> max(min(50.5, 11), 8.8) = +11
        assertEquals(11.0, GreenFloor8363.floor8363(22.0, 50.0), 1e-9)
        assertTrue(GreenFloor8363.floor8363(22.0, 50.0) >= 22.0 * 0.4 - 1e-9)
        // under +20 % the 8169 break-even floor; not armed before the peak clears it
        assertEquals(3.5, GreenFloor8363.floor8363(15.0, 3.0), 1e-9)
        assertTrue(GreenFloor8363.floor8363(5.0, 3.0).isNaN())
        assertTrue(GreenFloor8363.floor8363(Double.NaN, 3.0).isNaN())
        // breaches, and the same line as the 8114 peak capture (RunnerGrab7967.peakGiveback7980) above +200 %
        assertTrue(GreenFloor8363.breached8363(159.0, 300.0, 3.0))
        assertFalse(GreenFloor8363.breached8363(161.0, 300.0, 3.0))
        assertTrue(RunnerGrab7967.peakGiveback7980(300.0, 159.0))
        assertFalse(RunnerGrab7967.peakGiveback7980(300.0, 161.0))
        assertTrue(GreenFloor8363.breached8363(39.0, 100.0, 3.0))
        assertFalse(GreenFloor8363.breached8363(45.0, 100.0, 3.0))
        assertTrue(GreenFloor8363.statusLine8363().contains("rule8370=max(8337_3a,trail35%underPeakPrice)"))
    }

    // ── 3. no fired-setup rule ──

    @Test fun namedSetupMetadataDoesNotCreateASecondEntryAuthority() {
        val p=com.lifecyclebot.engine.cortex.LanePlaybook7907
        assertFalse(p.namedSetup8392(null));assertFalse(p.namedSetup8392("NO_TRIGGER"))
        assertFalse(p.namedSetup8392("matches_unrankable"))
        assertTrue(p.namedSetup8392("IGNITION_5M"))
        assertFalse(src("engine/truth/LiveEdgeGate7877.kt").contains("liveFiredSetupRefusal8364"))
    }

    // ── wiring ──

    @Test fun wired() {
        val rp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(rp.contains("SmallStake8370.telemetry8370(EntryEconomics8329.kellyRefusal8365(size, executableFloor8269, EntryEconomics8329.promotionReasonRefusal8329(i.mint8329, i.lane, size, executableFloor8269)))\n"))
        assertFalse("no KELLY_TOO_SMALL return", rp.contains("kellyRefusal8365(size, executableFloor8269, EntryEconomics8329.promotionReasonRefusal8329(i.mint8329, i.lane, size, executableFloor8269))?.let"))
        assertTrue("hard caps at the promoted size", rp.contains("executableMinRiskOk(executableFloor8269, stop, minCost7840, i.equitySol)") &&
            rp.contains("SizingNetEdge8153.promotionRefusal8153("))
        assertTrue("Kelly above the minimum unchanged", rp.contains("if (kellySol > size) { size = kellySol; labels += \"PROVEN_EDGE_COMPOUNDING_SIZE_8057\" }"))
        assertTrue(src("engine/truth/MinPromotion8188.kt").contains("if (r == null) SmallStake8370.telemetry8370(EntryEconomics8329.promotionReasonRefusal8329(mint, lane, requestedSol, promotedSol))"))
        val ex = src("engine/Exits8363.kt")
        assertTrue(ex.contains("fun floor8363(peakPct: Double, costPct: Double): Double = floor8370(peakPct, costPct)"))
        assertTrue(src("engine/RunnerGrab7967.kt").contains("(1.0 + grossPct / 100.0) <= 0.65 * (1.0 + peakPct / 100.0)"))
        assertTrue(src("engine/RunnerGrab7967.kt").contains("GreenFloor8363.breachedFor8363(ts, g) -> \"RUNNER_GREEN_FLOOR_8363_"))
        assertTrue(src("engine/LifecycleHold8065.kt").contains("GreenFloor8363.breachedFor8363(ts, g) -> \"LIFECYCLE_GREEN_FLOOR_8363_"))
        assertTrue(src("engine/TailBag8034.kt").contains("GreenFloor8363.floorFor8363(ts)"))
        val canonical = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(canonical.contains("canonicalRefusal8392"))
        assertFalse(canonical.contains("liveFiredSetupRefusal8364"))
        assertFalse("NO_TRIGGER is not demoted as a 8364 duplicate",
            Regex("^\\s*return BY_FIRED_SETUP_8365\\b", RegexOption.MULTILINE).containsMatchIn(src("engine/truth/FdgGate8365.kt")))
        // the hard runner stop and the lifecycle floor stay
        assertTrue(src("engine/RunnerGrab7967.kt").contains("private const val HARD_STOP_PCT = -35.0"))
        assertTrue(src("engine/LifecycleHold8065.kt").contains("const val FLOOR_PCT = 40.0"))
    }
}
