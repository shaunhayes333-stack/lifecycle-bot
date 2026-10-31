package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8080 — trade-first: each strategy graded on its own exits; bounded cold start; data is not a buy failure. */
class Aate8080TradeFirstTest {
    // [scalpNet, scalpGross, longNet, longGross] for one observed path: a scalp banked +12%, the long hold +80%.
    private val fam = doubleArrayOf(12.0, 14.0, 80.0, 82.0)

    @Test fun oneTokenTwoStrategiesTwoOutcomes() {
        // Same coin, same path, QUALITY (a lifecycle lane): a breakout setup holds long, a pullback exits as a scalp.
        // (V5.0.8106 — MOONSHOT rides every live buy on the runner ladder since 8104, so all its setups hold long.)
        assertTrue(LanePlaybook7907.setupHoldsLong8080("QUALITY", "BASE_BREAKOUT"))
        // V5.0.8406 — QUALITY's +20% trend-pullback mandate is hour-graded (LaneGradeHorizon8406): every setup book of the
        // lane takes the hour read. A range-reversion lane (TREASURY, 12%) keeps the 5-minute freeze for a short setup.
        assertTrue(LanePlaybook7907.setupHoldsLong8080("QUALITY", "HIGHER_LOW_PULLBACK"))
        assertFalse(LanePlaybook7907.setupHoldsLong8080("TREASURY", "HIGHER_LOW_PULLBACK"))
        assertEquals(80.0, LanePlaybook7907.familyLabel8080("QUALITY", "BASE_BREAKOUT", 40.0, 41.0, fam).first, 1e-9)
        assertEquals(80.0, LanePlaybook7907.familyLabel8080("QUALITY", "HIGHER_LOW_PULLBACK", 40.0, 41.0, fam).first, 1e-9)   // 8406: hour-graded lane, hold family
        assertEquals(12.0, LanePlaybook7907.familyLabel8080("TREASURY", "HIGHER_LOW_PULLBACK", 40.0, 41.0, fam).first, 1e-9)
        assertTrue(LanePlaybook7907.setupHoldsLong8080("MOONSHOT", "HIGHER_LOW_PULLBACK"))
    }

    @Test fun scalpLaneNeverCreditedTheLongHold() {
        // CASHGEN keeps its scalp ladder: even a breakout setup exits on the scalp family there.
        assertFalse(LanePlaybook7907.setupHoldsLong8080("CASHGEN", "BASE_BREAKOUT"))
        assertEquals(12.0, LanePlaybook7907.familyLabel8080("CASHGEN", "BASE_BREAKOUT", 40.0, 41.0, fam).first, 1e-9)
        // V5.0.8106 — owner (8104): every live SHITCOIN buy rides the runner ladder from entry, so its setups are graded
        // on the hold family, whatever the setup.
        assertTrue(LanePlaybook7907.setupHoldsLong8080("SHITCOIN", "BASE_BREAKOUT"))
        assertTrue(LanePlaybook7907.setupHoldsLong8080("EXPRESS", "HIGHER_LOW_PULLBACK"))
        assertEquals(80.0, LanePlaybook7907.familyLabel8080("SHITCOIN", "BASE_BREAKOUT", 40.0, 41.0, fam).first, 1e-9)
        // The runner-lane remainder banks half a +40..+199% held peak (its give-back exit), else the mark.
        val f = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        assertEquals(60.0, f.runnerRest8106(true, 120.0, -5.0), 1e-9)
        assertEquals(-5.0, f.runnerRest8106(false, 120.0, -5.0), 1e-9)
        assertEquals(90.0, f.runnerRest8106(true, 120.0, 90.0), 1e-9)
    }

    @Test fun legacyLabelWithoutFamiliesKeepsItsValue() {
        assertEquals(40.0, LanePlaybook7907.familyLabel8080("MOONSHOT", "BASE_BREAKOUT", 40.0, 41.0, null).first, 1e-9)
    }

    @Test fun coldStartNeedsSamplesAndMarginNotJustHope() {
        // missing / thin evidence is never edge
        assertFalse(LanePlaybook7907.coldStartPositive8080(0.0, Double.NaN, Double.NaN))
        assertFalse(LanePlaybook7907.coldStartPositive8080(11.0, 30.0, 25.0))
        // 12 labels, mean +8%, sd 10% -> SE 2.89, mean - 1.5 SE = +3.7% > 0 -> clears
        assertTrue(LanePlaybook7907.coldStartPositive8080(12.0, 8.0, 100.0))
        // 12 labels, mean +3%, sd 10% -> 3 - 4.33 < 0 -> refused (zero-edge: no forced buy)
        assertFalse(LanePlaybook7907.coldStartPositive8080(12.0, 3.0, 100.0))
        // negative mean never clears
        assertFalse(LanePlaybook7907.coldStartPositive8080(50.0, -1.0, 1.0))
    }

    @Test fun coldStartBudgetCountsDistinctCoinsPerWindow() {
        val now = 10L * 3_600_000L
        val three = mapOf("A" to now - 1_000L, "B" to now - 2_000L, "C" to now - 3_000L)
        assertFalse(LiveEdgeGate7877.coldBudgetOpen8080(three, "D", now))
        assertTrue(LiveEdgeGate7877.coldBudgetOpen8080(three, "A", now))   // re-evaluated coin keeps its slot
        val aged = three + ("A" to now - LiveEdgeGate7877.COLD_WINDOW_MS_8080)
        assertTrue(LiveEdgeGate7877.coldBudgetOpen8080(aged, "D", now))   // an expired slot frees up
    }

    @Test fun coldStartSitsInsideTheProvenOnlyDoorAndBeforeEveryLaterGate() {
        val g = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        // superseded (8480, owner directive "no evidence is not proven bad"): the proven-only door and its cold-start exemption are removed together
        assertFalse(g.contains("coldStartAdmits8080("))
        assertFalse(g.contains("return \"LIVE_PROVEN_SETUP_ONLY_8059\""))
        // the proven-losing exact setup is still refused after any admission door
        assertTrue(g.contains("LanePlaybook7907.provenLosingNamedSetupRefusal(l8079, boundSetup)"))
    }

    @Test fun tokenMapDataExhaustionIsNamedNotABuyFailure() {
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val i = ex.indexOf("val exhausted8080 = prior.size >= 3")
        assertTrue(i > 0)
        val body = ex.substring(i, ex.indexOf("return true", i))
        assertFalse(body.contains("emitLiveBuyFail"))
        assertTrue(body.contains("if (exhausted8080) buyTerminalFail(reason8080) else buyTerminalRecorded = true"))
    }

    @Test fun provenExactSetupIsNotCutForTheLanesMixedRecord() {
        val f = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val i = f.indexOf("if (pauseState != null && laneMixExemptProven8080(ts, laneName, pauseState.lane, checks)) {")
        assertTrue(i > 0)
        val cut = f.indexOf("finalSize = (finalSize * 0.35).coerceAtLeast(0.01)", i)
        assertTrue(f.indexOf("} else if (pauseState != null) {", i) in i..cut)   // the cut only runs when not proven
        assertTrue(f.contains("toxic.meanPnl <= -8.0 && !toxicExemptProven8080(ts, laneName)) {"))
        // ART verifier: evaluate() is at its size limit (5.0.8080 smoke VerifyError) - logic lives in helpers
        val ev = f.substring(f.indexOf("    fun evaluate(\n        ts: TokenState,"))
        assertFalse(ev.substring(0, ev.indexOf("\n    fun ", 10).let { if (it < 0) ev.length else it }).contains("val exactProven8080"))
    }

    @Test fun aSetupDeclaredForAnotherDeskIsNotSealedOnThisLane() {
        // CASHFLOW_SCALP is declared for CASHGEN only: not MOONSHOT's strategy, still CASHGEN's.
        assertFalse(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("CASHGEN"), "MOONSHOT"))
        assertTrue(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("CASHGEN"), "CASHGEN"))
        // shared multi-desk styles still run on each declared desk; blank/CORE are unconstrained
        assertTrue(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("MOONSHOT", "QUALITY"), "QUALITY"))
        assertTrue(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("CASHGEN"), ""))
        assertTrue(AgenticStyleRouter.toolkitStyleFitsLane8080(setOf("CASHGEN"), "CORE"))
    }

    @Test fun everySealSiteUsesTheSameLaneEligibleSetup() {
        val r = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue(r.contains("val toolkitStyle = toolkitStyle0?.takeIf { toolkitStyleFitsLane8080(it.lanes, laneHint) }"))
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("val strategySheet7427 = strategyDecision7427?.toolkit?.takeIf { sheetFit8080 }"))
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("AgenticStyleRouter.sealedSetup8080(ts, style7430.toolkit, learningOwnerLane7534)"))
        assertFalse(fdg.contains("style7430.toolkit.setup.name,"))
        val og = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(og.contains("AgenticStyleRouter.sealedSetup8080(t, exactStyle7431?.toolkit, lane)"))
    }

    @Test fun rewardChainReachesExactStrategyPerformanceFromTheSealedSnapshot() {
        val bus = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        assertTrue(bus.contains("entrySetup = entrySnap6567?.entrySetup ?: \"\""))
        val br = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(br.contains("ExactStrategyPerformance7429.record(env)"))
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("entrySetup = policyField6568(ts.position.entryPolicySnapshot, \"setup7427\")"))
    }

    @Test fun paperApprovesWhatLiveApprovesAtTheEconomicStep() {
        val f = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        // constant-level edits only: evaluate() is at the ART verifier edge (589 registers)
        assertTrue(f.contains("val wouldPassLiveQuality = candidate.setupQuality in listOf(\"A+\", \"A\", \"B\", \"C\", \"D\")"))
        val c = f.substringAfter("§PAPER_ACCEPTS_WHAT_LIVE_ACCEPTS").substringBefore("tags.add(\"class:")
        assertTrue(c.contains("confidenceProbe -> {"))
        assertFalse(c.contains("isAnyProbe -> {"))
        assertTrue(f.contains("TradeMode.PAPER -> approvalClass == ApprovalClass.PAPER_BENCHMARK"))
        assertFalse(f.contains("paperApprovalClass8080"))
    }

    @Test fun laneLogicNeverEmitsAProbeBuyTheGateMustRefuse() {
        val b = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertFalse(b.contains("blockReason = \"PROBE_ONLY\","))
        val i = b.indexOf("§NO_DOOMED_DUST_PROBE_BUY")
        assertTrue(i > 0)
        val blk = b.substring(i, b.indexOf("aiConfidence = laneBase.aiConfidence.coerceAtLeast(entryScoreTightenedFloor4591)", i))
        assertTrue(blk.contains("if (!cortexSignal8019 && !runTicket8025) {"))
        assertTrue(blk.contains("shouldTrade = false, blockReason = \"DUST_PROBE_SHADOW_ONLY_8080\""))
        assertTrue(blk.contains("blockReason = \"\","))
        assertFalse(blk.contains("resolveProbeSizeMult"))   // promoted signal is not dust-shrunk
    }
}
