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
        // Same coin, same path, MOONSHOT (a lifecycle lane): a breakout setup holds long, a pullback exits as a scalp.
        assertTrue(LanePlaybook7907.setupHoldsLong8080("MOONSHOT", "BASE_BREAKOUT"))
        assertFalse(LanePlaybook7907.setupHoldsLong8080("MOONSHOT", "HIGHER_LOW_PULLBACK"))
        assertEquals(80.0, LanePlaybook7907.familyLabel8080("MOONSHOT", "BASE_BREAKOUT", 40.0, 41.0, fam).first, 1e-9)
        assertEquals(12.0, LanePlaybook7907.familyLabel8080("MOONSHOT", "HIGHER_LOW_PULLBACK", 40.0, 41.0, fam).first, 1e-9)
    }

    @Test fun scalpLaneNeverCreditedTheLongHold() {
        // SHITCOIN is not a lifecycle lane: even a breakout setup exits on the scalp family there.
        assertFalse(LanePlaybook7907.setupHoldsLong8080("SHITCOIN", "BASE_BREAKOUT"))
        assertEquals(12.0, LanePlaybook7907.familyLabel8080("SHITCOIN", "BASE_BREAKOUT", 40.0, 41.0, fam).first, 1e-9)
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
        val door = g.indexOf("!coldStartAdmits8080(ts, l8059, nowMs)) {")
        assertTrue(door > 0)
        assertTrue(door < g.indexOf("return \"LIVE_PROVEN_SETUP_ONLY_8059\""))
        // the proven-losing exact setup is still refused after any admission door
        assertTrue(g.contains("LanePlaybook7907.provenLosingSetupRefusal8079(ts, l8079, nowMs)"))
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
}
