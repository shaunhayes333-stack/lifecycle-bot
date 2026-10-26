package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8347 — lost-wiring audit against the profitable 5.0.8114 build. Fourteen public functions had external callers at
 * 8114 and none at 8346. Each one was traced to the commit that dropped its caller; every one was either superseded by a
 * newer component doing the same job, or retired for a stated reason. Nothing is rewired (duplicating a behaviour a newer
 * component already provides is forbidden); these pins hold the EQUIVALENT in place so the audit does not silently regress:
 *
 *  - LiveSlotPriority7304.deferLive + LiveRiskPolicy7807.isHighEvRunner (its exemption): retired 8198 ("retire duplicate
 *    post-seal slot vote"); the high-EV runner sizing clause lives inside LiveRiskPolicy7807.decide (admission floor 0.70)
 *    and EntryEconomics8329 promotes an early runner to the route minimum (EARLY_RUNNER).
 *  - RunnerGrab7967.firmingNow8018 / SpikeCapture7943.resetTiers8018: retired 8153 (one promotion rule); RunnerLive8153
 *    reads firming7967 directly and the tier maps live in RunnerRungBook8153.
 *  - SellSlippageProfile.forTier: 8163 — the profit-lock quote reads SellSafetyPolicy.maxSlippageBps(reason).
 *  - LanePlaybook7907.matchedSetups8083: 8174 — graded setups are the decision-time snapshot only (no recompute).
 *  - LaneScoreAdmission7308.confirm7772 -> confirmExecution7772 (8161).
 *  - HunterMandates8031.expressFits8031 -> HunterMandates8293.expressFits8293 (8293, the native terms exactly).
 *  - HostWalletTokenTracker.reapZeroBalanceGhosts: still runs inside the wallet-truth pass; the UI call went at 8163.
 *  - CortexLedger7885.predictionSnapshot8076 -> fuseWithSnapshot8324 (8324, one prediction per voter).
 *  - TradeCostMeasurement8090.measure -> CostSides8169.sides8169 (8169); every live close reaches CostLedger7962 via the
 *    finalized bus bridge.
 *  - ToolkitSignalSheet.terminalizeUnexecutedTicket7948: still the one terminal (Executor.unexecutedTicket7948 via
 *    TicketRetry8327); RuntimeDoctor.currentFaults -> faultsForReport8214 (8215); PoolCreationTime7385.ageSecs ->
 *    LaneAgeLiquidityTruth8140.chainAgeSecs8140 (8140).
 */
class Aate8347RewireTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. high-EV runner: sizing clause and route-minimum promotion are live without isHighEvRunner ──

    @Test fun highEvRunnerKeepsTheHigherAdmissionFloor() {
        // liveCloses 0 (x0.85), partial provider evidence (x0.85), oracle unproven (x0.90) = 0.65025.
        val plain = LiveRiskPolicy7807Access.admission(highEvRunner = false)
        val runner = LiveRiskPolicy7807Access.admission(highEvRunner = true)
        assertEquals(0.85 * 0.90 * 0.85, plain.first, 1e-9)
        assertFalse(plain.second.contains("HIGH_EV_RUNNER_ADMIT_REDUCED_7807"))
        assertEquals("a >= 3R runner is floored at 0.70 of the sealed size", 0.70, runner.first, 1e-9)
        assertTrue(runner.second.contains("HIGH_EV_RUNNER_ADMIT_REDUCED_7807"))
    }

    @Test fun earlyRunnerIsTheRouteMinimumPromotionReason() {
        val ee = com.lifecyclebot.engine.truth.EntryEconomics8329
        assertEquals("EARLY_RUNNER", ee.promotionReason8329(0.001, 0.029, 0.0, Double.NaN, earlyRunner = true))
        assertNull("no runner, no record, stake under a quarter of the minimum: no promotion",
            ee.promotionReason8329(0.001, 0.029, 0.0, Double.NaN, earlyRunner = false))
    }

    @Test fun lastSlotVoteStaysRetired8198() {
        val exec = src("engine/Executor.kt")
        assertFalse("8198 retired the duplicate post-seal slot vote", exec.contains("LiveSlotPriority7304.deferLive("))
        assertFalse(exec.contains("LiveRiskPolicy7807.isHighEvRunner("))
        assertFalse(File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveSlotPriority7304.kt").exists())
        val lrp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(lrp.contains("val highEvRunner = isRunnerLane(i.lane) && planR(i.planStopPct, i.planTargetPct) >= HIGH_EV_RUNNER_MIN_R_7807"))
        assertTrue(lrp.contains("val floor = if (a.highEvRunner) 0.70 else 0.50"))
    }

    // ── 2. runner firming: RunnerLive8153 reads the structure directly; the grab lane uses grab7967 / formingNative8260 ──

    @Test fun firmingIsReadByThePromotionRuleAndTheGrabLane() {
        val live = src("engine/RunnerLive8153.kt")
        assertTrue(live.contains("RunnerGrab7967.firming7967(r15) || RunnerGrab7967.firming7967(r60)"))
        val play = src("engine/RunnerPlay8018.kt")
        assertFalse("8153: one promotion rule, no 8018 firming pre-check", play.contains("firmingNow8018("))
        assertTrue(play.contains("RunnerLive8153.promotion8153(ts, gross, down, nowMs)"))
        val grab = src("engine/RunnerGrabLane8343.kt")
        assertTrue(grab.contains("RunnerGrab7967.formingNative8260"))
        assertFalse(RunnerGrab7967.runStanding8019(null))
    }

    // ── 3. tier sale slippage: SellSafetyPolicy owns the cap at the profit-lock quote ──

    @Test fun profitLockQuoteReadsSellSafetyPolicy() {
        val exec = src("engine/Executor.kt")
        assertFalse(exec.contains("SellSlippageProfile.forTier("))
        assertTrue(exec.contains("com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)"))
        assertTrue(exec.contains("ProfitExitTruth8163.acceptQuote(ts, reason, activeQuote7228"))
    }

    // ── 4. graded setups are the decision-time snapshot (8174); the playbook still matches internally ──

    @Test fun firedSetupsComeFromTheDecisionSnapshotOnly() {
        val cortex = src("engine/cortex/Cortex7885.kt")
        assertTrue(cortex.contains("val fired8094 = snap8084?.takeIf { it.lane == a.lane }?.matched.orEmpty()"))
        assertFalse("8174: no recompute behind the capture queue", cortex.contains("LanePlaybook7907.matchedSetups8083("))
        val playbook = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(playbook.contains("fun selectedSetup8094("))
        assertTrue(playbook.contains("fun admittedSetup8153("))
        assertTrue(playbook.contains("fun rankedSetup8153("))
        assertTrue(playbook.contains("matchedSetups8083(ts, lane, nowMs), mode, version, selectionRevision8198())"))
    }

    // ── 5. admission confirmation: confirmExecution7772 (8161) at the FDG allow ──

    @Test fun admissionIsConfirmedByExecutionVersion() {
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(fdg.contains("LaneScoreAdmission7308.confirmExecution7772(ts.mint, canonicalPrimaryLane6658, candidateVersion7623)"))
        assertFalse(fdg.contains("LaneScoreAdmission7308.confirm7772(ts.mint)"))
    }

    // ── 6. EXPRESS fit: the native terms (8293) ──

    @Test fun expressFitIsTheNativeContinuationWindow() {
        val hm = com.lifecyclebot.engine.market.HunterMandates8293
        assertTrue(hm.expressFits8293(15.0, 5.0))
        assertTrue("unknown 5m move is not a refusal", hm.expressFits8293(15.0, Double.NaN))
        assertFalse("below the native 1% continuation", hm.expressFits8293(0.5, 5.0))
        assertFalse("CHASE_EXTENDED above 30%", hm.expressFits8293(31.0, 5.0))
        assertFalse("5m chase line at 20%", hm.expressFits8293(15.0, 25.0))
        val hunter = src("engine/market/LaneHunter7297.kt")
        assertTrue(hunter.contains("HunterMandates8293.expressFits8293(r.priceChangeH1Pct, r.priceChange5mPct)"))
        assertFalse(hunter.contains("expressFits8031("))
    }

    // ── 7. ghost reaping runs inside the wallet-truth pass ──

    @Test fun zeroBalanceGhostsAreReapedAfterWalletTruth() {
        val hwtt = src("engine/HostWalletTokenTracker.kt")
        assertTrue(hwtt.contains("try { reapZeroBalanceGhosts() } catch (_: Throwable) {}"))
        assertTrue(src("engine/ReconcileRunner8144.kt").contains("ghostClosed8264.addAndGet(closed.size)"))
    }

    // ── 8. Cortex prediction: one snapshot per voter (8324) ──

    @Test fun cortexFusesWithOneSnapshotPerVoter() {
        val cortex = src("engine/cortex/Cortex7885.kt")
        assertTrue(cortex.contains("ledger.fuseWithSnapshot8324(lane, baseVotes8325 + cross, regime)"))
        assertFalse(cortex.contains("ledger.predictionSnapshot8076("))
        assertTrue("the ledger's fused path still builds the same snapshot (identical outputs, one pass per voter)",
            src("engine/cortex/CortexLedger7885.kt").contains("fun fuseWithSnapshot8324(lane: String, votes: List<Vote>, regime: String = \"\"): Pair<Fused, PredictionSnapshot8076>"))
    }

    // ── 9. every live close reaches CostLedger7962; sides are CostSides8169 ──

    @Test fun liveClosesFeedTheCostLedger() {
        assertTrue(src("engine/truth/FinalizedBusConsumerBridge6465.kt").contains("\"CostLedger7962\" -> CostLedger7962.onLiveFinalized(env)"))
        assertTrue(src("engine/truth/EntryStrategySnapshot6450.kt").contains("CostLedger7962.onEntry7962(snap.positionId, snap.mint, snap.entryTimestampMs"))
        val ledger = src("engine/truth/CostLedger7962.kt")
        assertTrue(ledger.contains("CanonicalFinalizedTradeBus6464.registerConsumer(\"CostLedger7962\")"))
        assertTrue(ledger.contains("CostSides8169.sides8169(spent, buy.entryFeesSol * share, soldRaw, buy.tokenDecimals, em.markUsd, em.solUsd, legs)"))
        assertFalse(ledger.contains("TradeCostMeasurement8090.measure("))
    }

    // ── 10. named ticket terminal, rung book, fresh faults, chain age ──

    @Test fun remainingReplacementsAreWired() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("ToolkitSignalSheet.terminalizeUnexecutedTicket7948(normalizeExecutionLane(layerTag), attemptId, reason8327)"))
        assertTrue(src("engine/TicketRetry8327.kt").contains("ToolkitSignalSheet.terminalizeUnexecutedTicket7948(lane, attemptId, reason)"))
        val spike = src("engine/SpikeCapture7943.kt")
        assertFalse(spike.contains("fun resetTiers8018("))
        assertTrue(spike.contains("RunnerLive8153.confirmTier8153(ts, tier, reason)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("RuntimeDoctor.faultsForReport8214(loopNow8214)"))
        assertFalse(src("engine/PipelineHealthCollector.kt").contains("RuntimeDoctor.currentFaults()"))
        assertTrue(src("engine/BotService.kt").contains("val poolAgeSecs7389 = LaneAgeLiquidityTruth8140.chainAgeSecs8140(ts)"))
    }
}

/** Builds the 7807 admission input with every non-essential-evidence reduction on, so the runner floor is what differs. */
private object LiveRiskPolicy7807Access {
    fun admission(highEvRunner: Boolean): Pair<Double, List<String>> =
        com.lifecyclebot.engine.truth.LiveRiskPolicy7807.admissionMultiplier(
            com.lifecyclebot.engine.truth.LiveRiskPolicy7807.Admission(
                liveCloses = 0, liveWrPct = Double.NaN, partialProviderEvidence = true, oracleUnproven = true,
                highEvRunner = highEvRunner,
            ),
        )
}
