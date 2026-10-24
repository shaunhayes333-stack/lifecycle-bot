package com.lifecyclebot.engine

import com.lifecyclebot.engine.RunnerExitContract8136.Intent8136
import com.lifecyclebot.engine.RunnerExitContract8136.Mark8136
import com.lifecyclebot.engine.RunnerExitContract8136.Policy8136
import com.lifecyclebot.engine.RunnerExitContract8136.RunnerBasis8136
import com.lifecyclebot.engine.RunnerExitContract8136.TailFilter8136
import com.lifecyclebot.engine.RunnerExitContract8136.Tier8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8136 — realised runner profit capture and exit authority. Price paths are replayed through the legacy lane
 * rules (every live meme buy a runner from entry, decisions on the feed mark, cost / value recovery, a tail on every
 * exit) and through RunnerExitContract8136. Units: the entry cost is 1.0; sells pay 3% (fees + slippage).
 */
class Aate8136RunnerExitAuthorityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun path(vararg px: Double) = px.map { Mark8136(it) }
    private val legacy = Policy8136.LEGACY_LANE_RUNNER
    private val contract = Policy8136.CONTRACT_8136

    private fun report(name: String, old: RunnerExitContract8136.Replay8136, new: RunnerExitContract8136.Replay8136) {
        println("8136 $name: legacy net=${"%+.3f".format(old.net)} captured=${"%.2f".format(old.capturedFraction)} residual=${"%.3f".format(old.residualShare)} " +
            "| contract net=${"%+.3f".format(new.net)} captured=${"%.2f".format(new.capturedFraction)} residual=${"%.3f".format(new.residualShare)} exits=${new.exits}")
    }

    private fun token(mint: String, style: String, peakPct: Double = 0.0, lane: String = "MOONSHOT") =
        com.lifecyclebot.data.TokenState(mint = mint, position = com.lifecyclebot.data.Position(qtyToken = 1_000_000.0,
            entryPrice = 1.0, entryTime = 1L, tradingMode = lane, isPaperPosition = false, peakGainPct = peakPct,
            entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT"))

    // ── 1. eligibility: sealed setup or proven promotion, never the lane name alone ──

    @Test fun runnerEligibilityIsTheSealedSetupOrProvenPromotion() {
        assertFalse(RunnerExitContract8136.sealedRunnerStyle8136("QUICK_FLIP"))
        assertFalse(RunnerExitContract8136.sealedRunnerStyle8136("PUMP_GRADUATION_SNIPE"))
        assertFalse(RunnerExitContract8136.sealedRunnerStyle8136(""))
        assertFalse(RunnerExitContract8136.sealedRunnerStyle8136("UNKNOWN"))
        assertTrue(RunnerExitContract8136.sealedRunnerStyle8136("BREAKOUT_RUNNER"))
        assertTrue(RunnerExitContract8136.sealedRunnerStyle8136("CHART_BREAKOUT"))       // planned hold x1.75
        assertEquals(RunnerBasis8136.NONE, RunnerExitContract8136.runnerBasis8136(true, "QUICK_FLIP", 60.0))
        assertEquals(RunnerBasis8136.PROVEN_PROMOTION, RunnerExitContract8136.runnerBasis8136(true, "QUICK_FLIP", 150.0))
        assertEquals(RunnerBasis8136.SEALED_SETUP, RunnerExitContract8136.runnerBasis8136(true, "BREAKOUT_RUNNER", 0.0))
        assertEquals(RunnerBasis8136.NONE, RunnerExitContract8136.runnerBasis8136(false, "BREAKOUT_RUNNER", 500.0))
        assertFalse(RunnerExitContract8136.provenPromotion8136(250_000.0))                // a broken basis is not a run

        // The lane still says the lane CAN host a runner (owner pin, Aate8094) ...
        assertTrue(TradeExitContract8077.runnerFromEntryLane8104("MOONSHOT", paper = false))
        // ... but a QUICK_FLIP bought in MOONSHOT is not a runner until its verified peak proves +100%.
        val quick = token("quick8136", "QUICK_FLIP")
        assertFalse(TradeExitContract8077.runnerFromEntry8104(quick))
        assertFalse(RunnerGrab7967.holdingRunner8018(quick))
        assertNull(RunnerGrab7967.tiersFor7967(quick))                                    // keeps its scalp ladder
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decide8034(quick, "STRICT_SL_-15"))   // and no moonbag
        assertTrue(TradeExitContract8077.runnerFromEntry8104(token("proven8136", "QUICK_FLIP", peakPct = 150.0)))
        assertTrue(TradeExitContract8077.runnerFromEntry8104(token("sealed8136", "BREAKOUT_RUNNER")))
        assertFalse(TradeExitContract8077.runnerFromEntry8104(token("cashgen8136", "BREAKOUT_RUNNER", lane = "CASHGEN")))
        assertEquals("QUICK_FLIP", TradeExitContract8077.sealedStyle8136(quick))
        // RunnerPlay's +25% promotion needs the verified peak too, not one print.
        assertFalse(RunnerPlay8018.verifiedPeakReached8136(10.0))
        assertTrue(RunnerPlay8018.verifiedPeakReached8136(30.0))
    }

    // ── 2. the order: tier 1 is never suppressed ──

    @Test fun exitIntentsAreRankedAndTierOneIsNeverHeld() {
        assertEquals(Tier8136.EMERGENCY, RunnerExitContract8136.tierOf8136("RAPID_CATASTROPHE_STOP"))
        assertEquals(Tier8136.EMERGENCY, RunnerExitContract8136.tierOf8136("REFLEX_LIQ_DRAIN"))
        assertEquals(Tier8136.EMERGENCY, RunnerExitContract8136.tierOf8136("TOKEN_FROZEN"))
        assertEquals(Tier8136.EMERGENCY, RunnerExitContract8136.tierOf8136("CANNOT_SELL_ROUTE"))
        assertEquals(Tier8136.HARD_RISK, RunnerExitContract8136.tierOf8136("THIN_LIQ_EARLY_RUG_BACKSTOP_-10"))
        assertEquals(Tier8136.HARD_RISK, RunnerExitContract8136.tierOf8136("STRICT_SL_-15"))
        assertEquals(Tier8136.RUNNER_CONTINUATION, RunnerExitContract8136.tierOf8136("RUNNER_STRUCTURE_BREAK_7967_1M_300PCT"))
        assertEquals(Tier8136.CAPITAL_RECOVERY, RunnerExitContract8136.tierOf8136("capital_recovery_2.0x"))
        assertEquals(Tier8136.CAPITAL_RECOVERY, RunnerExitContract8136.tierOf8136("LIFECYCLE_PROFIT_TAKE1_8065_105PCT"))
        assertEquals(Tier8136.PROFIT_LOCK, RunnerExitContract8136.tierOf8136("TICK_PROFIT_LOCK_peak60_now40"))
        assertEquals(Tier8136.PROFIT_LOCK, RunnerExitContract8136.tierOf8136("SPIKE_CAPTURE_7943_T1_45PCT"))
        assertEquals(Tier8136.RUNNER_MILESTONE, RunnerExitContract8136.tierOf8136("LIFECYCLE_PROFIT_TAKE2_8065_420PCT"))
        assertEquals(Tier8136.RUNNER_MILESTONE, RunnerExitContract8136.tierOf8136("TAIL_MILESTONE_8034_1"))
        assertEquals(Tier8136.MOONBAG, RunnerExitContract8136.tierOf8136("TAIL_SET_ASIDE_8034_TIME_EXIT"))
        assertEquals(Tier8136.ORDINARY, RunnerExitContract8136.tierOf8136("FALLBACK_SHITCOIN_TIME_EXIT"))

        // A confirmed continuation holds a soft full exit, never a drained pool.
        val lock = Intent8136("TICK_PROFIT_LOCK_peak300_now210", 1.0)
        val drain = Intent8136("LIQUIDITY_DRAIN_EXIT", 1.0)
        assertNull(RunnerExitContract8136.arbitrate8136(listOf(lock), continuationConfirmed = true).act)
        assertEquals(drain, RunnerExitContract8136.arbitrate8136(listOf(lock, drain), continuationConfirmed = true).act)
        assertEquals(lock, RunnerExitContract8136.arbitrate8136(listOf(lock), continuationConfirmed = false).act)
        // A budgeted partial (the stake-back tranche) is not blocked by the continuation.
        val tranche = Intent8136("CAPITAL_RECOVERY_8136_100PCT", 0.52)
        assertEquals(tranche, RunnerExitContract8136.arbitrate8136(listOf(lock, tranche), continuationConfirmed = true).act)
        // Hard risk outranks the runner contract, which outranks recovery and locks.
        val stop = Intent8136("RUNNER_HARD_STOP_7967_-36PCT", 1.0)
        val brk = Intent8136("RUNNER_STRUCTURE_BREAK_7967_1M_80PCT", 1.0)
        assertEquals(stop, RunnerExitContract8136.arbitrate8136(listOf(tranche, brk, stop, lock), false).act)
        assertEquals(brk, RunnerExitContract8136.arbitrate8136(listOf(tranche, brk, lock), false).act)

        // The hold overlays: a drained / frozen / unsellable exit passes every one of them.
        // (5.0.8135 RunnerGrab7967 deferred these: its integrity list missed LIQ_DRAIN, LP_PULL, CANNOT_SELL, MINT_AUTH.)
        assertFalse(RunnerGrab7967.deferrable7967("REFLEX_LIQ_DRAIN", 120.0))
        assertFalse(RunnerGrab7967.deferrable7967("LP_PULL_DETECTED", 300.0))
        assertFalse(RunnerGrab7967.deferrable7967("CANNOT_SELL_ROUTE", 40.0))
        assertFalse(RunnerGrab7967.deferrable7967("MINT_AUTH_REENABLED", 40.0))
        assertTrue(RunnerGrab7967.deferrable7967("TICK_PROFIT_LOCK_peak6_now1", 40.0))       // owner pins unchanged
        assertTrue(RunnerGrab7967.deferrable7967("THIN_LIQ_EARLY_RUG_BACKSTOP_-10", -10.0))
        assertTrue(LifecycleHold8065.passes8065("REFLEX_LIQ_DRAIN"))
        assertTrue(LifecycleHold8065.passes8065("UNEXECUTABLE_ROUTE_EXIT"))
        assertFalse(LifecycleHold8065.passes8065("RAPID_FLUID_STOP"))
        val tail = TailBag8034.Tail(1L, 1L, 0)
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "UNSELLABLE_TOKEN_EXIT", 150.0, tail, 1000L, true))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("SHITCOIN", "REFLEX_LIQ_DRAIN", 60.0, tail, 1000L, true))
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("RunnerExitLive8136.recoveryTranche8136(ts, targetRecoverySol, expectedNetPositionValueSol)"))
        assertTrue(e.contains("RunnerExitLive8136.budget8136(ts, sellPercentage.coerceIn(0.0, 1.0), reason)"))
        assertTrue(e.contains("RunnerExitLive8136.noteSliceFill8136(reason, result.solReceived, result.realizedPnl.proportionalCostBasisSol)"))
        assertTrue(src("engine/cortex/CortexExit7897.kt").contains("RunnerExitContract8136.emergency8136(reason)"))
    }

    // ── 3. capital recovery: net of costs, on unrecovered capital, only from confirmed proceeds ──

    @Test fun halfAtTwoXDoesNotRecoverCostAfterFees() {
        assertEquals(0.5, RunnerExitContract8136.recoveryFraction8136(1.0, 2.0, 0.0), 1e-12)
        val net = RunnerExitContract8136.recoveryFraction8136(1.0, 2.0, 3.0)
        assertEquals(1.0 / 1.94, net, 1e-12)
        // 50% at exactly 2x after 3% costs returns 0.97 of the stake: not recovered.
        assertFalse(RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, 0.5 * 2.0 * 0.97, 0.0))
        assertTrue(RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, net * 2.0 * 0.97, 0.0))
        // Only confirmed proceeds count; a mark at 2x with nothing sold is not a recovery.
        assertFalse(RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, 0.0, 0.0))
        assertFalse(RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, 1.02, 0.05))
        // An earlier spike rung already brought most of the stake back: the tranche targets what is left.
        assertEquals(0.127, RunnerExitContract8136.unrecoveredSol8136(1.0, 0.873, 0.0), 1e-12)
        assertEquals(0.0, RunnerExitContract8136.unrecoveredSol8136(1.0, 1.2, 0.0), 0.0)
        assertEquals(0.0, RunnerExitContract8136.recoveryFraction8136(0.0, 2.0, 3.0), 0.0)
        assertFalse(TailBag8034.capitalRecovered8090("unfunded8136", "noProof8136"))
    }

    @Test fun firstTrancheNeverConfirmsSoCapitalRecoveredStaysFalse() {
        // The 2x tranche is sent twice and never lands: nothing is booked, nothing is "recovered", no rung is consumed.
        val p = listOf(Mark8136(1.0), Mark8136(2.0, sellFails = true), Mark8136(2.05, sellFails = true), Mark8136(1.9))
        for (policy in listOf(legacy, contract)) {
            val r = RunnerExitContract8136.replay8136(p, policy, "")
            assertFalse(r.capitalRecovered)
            assertEquals(1.0, r.residualShare, 1e-12)
            assertEquals(0.0, r.proceeds, 0.0)
            assertEquals(2, r.unexecutable)
            assertTrue(r.exits.all { it.startsWith("UNFILLED:") })
        }
    }

    // ── 4. one residual share budget ──

    @Test fun overlappingLaddersCannotSellTheResidual() {
        val res = RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136
        assertEquals(0.6, RunnerExitContract8136.budgetedFraction8136(0.6, 1.0, res, "SPIKE_CAPTURE_7943_T1_45PCT"), 1e-12)
        // Spike T1 60% then runner T1 50%, profit lock 50%, harvest 35% (each a fraction of the CURRENT bag):
        // unbudgeted they leave 0.4 x 0.5 x 0.5 x 0.65 = 6.5%; budgeted the residual stays at 20%.
        var remaining = 1.0
        var unbudgeted = 1.0
        for ((frac, reason) in listOf(0.6 to "SPIKE_CAPTURE_7943_T1_45PCT", 0.5 to "SPIKE_CAPTURE_7943_T1_105PCT",
                0.5 to "PROFIT_LOCK_5X_8136", 0.35 to "WALLET_GROWTH_HARVEST_6028")) {
            unbudgeted *= (1.0 - frac)
            remaining *= (1.0 - RunnerExitContract8136.budgetedFraction8136(frac, remaining, res, reason))
        }
        assertEquals(0.065, unbudgeted, 1e-9)
        assertEquals(res, remaining, 1e-9)
        // Full exits, tier 1-3 partials and the moonbag's own milestones are never clamped.
        assertEquals(1.0, RunnerExitContract8136.budgetedFraction8136(1.0, 0.1, res, "TICK_PROFIT_LOCK"), 0.0)
        assertEquals(0.5, RunnerExitContract8136.budgetedFraction8136(0.5, 0.15, res, "LIQUIDITY_DRAIN_EXIT"), 0.0)
        assertEquals(1.0 / 3.0, RunnerExitContract8136.budgetedFraction8136(1.0 / 3.0, 0.05, res, "TAIL_MILESTONE_8034_1"), 0.0)
        assertEquals(0.0, RunnerExitContract8136.budgetedFraction8136(0.7, 0.2, res, "SPIKE_CAPTURE_7943_T2_130PCT"), 0.0)
        assertEquals(0.7, RunnerExitContract8136.budgetedFraction8136(0.7, 0.4, 0.0, "SPIKE_CAPTURE_7943_T2_130PCT"), 0.0)   // not a runner
    }

    // ── 5. the moonbag needs a funded contract ──

    @Test fun aLosingExitLeavesNoUnprotectedTail() {
        val f = TailBag8034.TAIL_FRACTION
        assertFalse(RunnerExitContract8136.tailFunded8136(-15.0, false, 3.0, f))
        assertTrue(RunnerExitContract8136.tailFunded8136(40.0, false, 3.0, f))
        assertTrue(RunnerExitContract8136.tailFunded8136(-15.0, true, 3.0, f))
        assertFalse(RunnerExitContract8136.tailFunded8136(Double.NaN, false, 3.0, f))
        assertEquals(TailFilter8136.REFUSE_UNFUNDED_SET_ASIDE, RunnerExitContract8136.tailFilter8136(true, false, -15.0, false, 3.0, f))
        assertEquals(TailFilter8136.REFUSE_UNFUNDED_SET_ASIDE, RunnerExitContract8136.tailFilter8136(true, false, 10.0, false, 3.0, f))
        assertEquals(TailFilter8136.KEEP, RunnerExitContract8136.tailFilter8136(true, false, 40.0, false, 3.0, f))   // 75% at 1.4x nets 1.02
        assertEquals(TailFilter8136.KEEP, RunnerExitContract8136.tailFilter8136(true, false, -15.0, true, 3.0, f))   // a free ride
        assertEquals(TailFilter8136.RELEASE_UNFUNDED_LOSING_TAIL, RunnerExitContract8136.tailFilter8136(false, true, -5.0, false, 3.0, f))
        assertEquals(TailFilter8136.KEEP, RunnerExitContract8136.tailFilter8136(false, true, -5.0, true, 3.0, f))
        // The legacy pure decision still sets aside (owner pin, Aate8034); the live door applies the contract on top.
        assertEquals(TailBag8034.Decision.SetAside, TailBag8034.decidePure8034("MOONSHOT", "STRICT_SL_-15", -15.0, null, 1000L))
        // superseded by V5.0.8363 — the funding filter still wraps the decision; the retained tail also carries the green floor.
        assertTrue(src("engine/TailBag8034.kt").contains("val d = funded8136(decidePure8034(lane, reason, pnlOf(ts), tail, nowMs, capitalRecovered, floor8363), ts, capitalRecovered)"))
    }

    // ── 6. capture loss on the fill, fresh marks ──

    @Test fun captureLossIsMeasuredOnTheConfirmedFill() {
        assertEquals(130.0, RunnerExitContract8136.decisionGrossOf8136("SPIKE_CAPTURE_7943_T2_130PCT"), 0.0)
        assertEquals(150.0, RunnerExitContract8136.decisionGrossOf8136("RUNNER_PEAK_CAPTURE_7980_400PK_150PCT|MARK_UNTRUSTED_6882"), 0.0)
        assertTrue(RunnerExitContract8136.decisionGrossOf8136("TIME_EXIT").isNaN())
        // Decided at +130%, the route paid 1.1x on the slice's cost: 120 points given up.
        assertEquals(120.0, RunnerExitContract8136.captureLossPp8136(130.0, 1.1, 1.0), 1e-9)
        assertEquals(0.0, RunnerExitContract8136.captureLossPp8136(40.0, 1.5, 1.0), 0.0)
        assertTrue(RunnerExitContract8136.freshForCapture8136(1_000L))
        assertFalse(RunnerExitContract8136.freshForCapture8136(30_000L))
        assertFalse(RunnerExitContract8136.freshForCapture8136(-5L))
        assertTrue(src("engine/LifecycleHold8065.kt").contains("fresh8136 && cumulativeTakeDue8079(g, st.taken) != null"))
    }

    // ── path replays ──

    @Test fun spikeOfFortyThenCollapse() {
        val p = path(1.0, 1.2, 1.4, 1.42, 1.1, 0.9, 0.75)
        val old = RunnerExitContract8136.replay8136(p, legacy, "")
        val new = RunnerExitContract8136.replay8136(p, contract, "")
        report("+40% spike then collapse", old, new)
        assertTrue(old.runner)
        assertFalse(new.runner)
        assertTrue(old.tailKept)                  // legacy rode a quarter down to -25%
        assertFalse(new.tailKept)
        assertTrue(new.net > old.net)
        assertTrue(new.net > 0.2)
    }

    @Test fun hundredPercentThenReversal() {
        val p = path(1.0, 1.3, 1.6, 2.0, 2.05, 1.6, 1.1, 0.8, 0.6)
        val old = RunnerExitContract8136.replay8136(p, legacy, "")
        val new = RunnerExitContract8136.replay8136(p, contract, "")
        report("+100% then reversal", old, new)
        assertTrue(new.capitalRecovered)          // one tranche, confirmed by its own proceeds
        assertEquals(1, new.exits.count { it.startsWith("CAPITAL_RECOVERY_8136") })
        assertFalse(old.capitalRecovered)         // 97% back each time: never confirmed, so it re-sold
        assertTrue(old.exits.count { it.startsWith("capital_recovery") } >= 2)
        assertTrue(new.net > 0.0)
    }

    @Test fun sustainedFiveHundredPercent() {
        val p = path(1.0, 1.5, 2.0, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 6.0)
        val old = RunnerExitContract8136.replay8136(p, legacy, "")
        val new = RunnerExitContract8136.replay8136(p, contract, "")
        val sealed = RunnerExitContract8136.replay8136(p, contract, "BREAKOUT_RUNNER")
        report("sustained +500% (unknown setup)", old, new)
        report("sustained +500% (sealed runner)", old, sealed)
        assertTrue(old.residualShare < 0.05)      // the never-confirming recovery sold 95% on the way up
        assertTrue(new.residualShare >= RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136 - 1e-9)
        assertTrue(new.net > old.net)
        assertTrue(sealed.net > new.net)
        assertTrue(sealed.capturedFraction > old.capturedFraction)
        assertTrue(sealed.capitalRecovered)
    }

    @Test fun thousandPercentWithDeepRecoverablePullback() {
        val p = path(1.0, 2.0, 3.0, 5.0, 8.0, 11.0, 7.5, 6.5, 9.0, 12.0, 15.0)
        val old = RunnerExitContract8136.replay8136(p, legacy, "BREAKOUT_RUNNER")
        val new = RunnerExitContract8136.replay8136(p, contract, "BREAKOUT_RUNNER")
        report("+1000% with deep recoverable pullback", old, new)
        assertTrue(new.exits.any { it.contains("RUNNER_PEAK_CAPTURE_7980") })
        assertTrue(new.exits.any { it.startsWith("RUNNER_MILESTONE_8136_T3") })
        assertTrue(new.tailKept)                  // a funded tail rides the recovery
        assertTrue(new.net > old.net)
    }

    @Test fun runnerWithLiquidityDrainedExitsWhateverTheHold() {
        val p = path(1.0, 2.0, 3.0) + Mark8136(3.0, quotePx = 0.9, liquidityDrained = true)
        for (policy in listOf(legacy, contract)) {
            val r = RunnerExitContract8136.replay8136(p, policy, "BREAKOUT_RUNNER")
            assertEquals("LIQUIDITY_DRAIN_EXIT", r.exits.last())
            assertEquals(0.0, r.residualShare, 1e-12)
        }
    }

    @Test fun scalpFalselyClassifiedRunner() {
        val p = path(1.0, 1.25, 1.45, 1.6, 1.3, 1.1, 0.95, 0.85)
        val old = RunnerExitContract8136.replay8136(p, legacy, "QUICK_FLIP")
        val new = RunnerExitContract8136.replay8136(p, contract, "QUICK_FLIP")
        report("scalp sealed QUICK_FLIP", old, new)
        assertTrue(old.runner)                    // legacy: a MOONSHOT lane buy is a runner
        assertFalse(new.runner)
        assertTrue(new.exits.first().startsWith("SPIKE_CAPTURE_7943_T1"))
        assertTrue(new.net > old.net)
    }

    @Test fun phantomTwoThousandPercentMarkIsNotAPeak() {
        assertEquals(5.0, RunnerExitContract8136.ratchetPeak8136(5.0, 2_000.0, 21.0, null), 0.0)
        assertEquals(2_000.0, RunnerExitContract8136.ratchetPeak8136(5.0, 2_000.0, 21.0, 20.0), 0.0)
        assertEquals(80.0, RunnerExitContract8136.ratchetPeak8136(40.0, 80.0, 1.8, null), 0.0)   // an ordinary step
        val p = listOf(Mark8136(1.0), Mark8136(1.05), Mark8136(21.0, quotePx = 1.06, agreedPx = null), Mark8136(1.04), Mark8136(1.02))
        val old = RunnerExitContract8136.replay8136(p, legacy, "")
        val new = RunnerExitContract8136.replay8136(p, contract, "")
        report("phantom +2000% single mark", old, new)
        assertTrue(old.maxCaptureLossPp > 1_000.0)  // legacy sold "into" a print no route would pay
        assertFalse(new.runner)
        assertTrue(new.exits.isEmpty())
        assertEquals(0.0, new.maxCaptureLossPp, 0.0)
    }

    @Test fun frozenHoldingBooksNoGhostExit() {
        val p = listOf(Mark8136(1.0), Mark8136(1.3), Mark8136(1.3, frozen = true), Mark8136(1.3, frozen = true), Mark8136(1.3, frozen = true))
        val r = RunnerExitContract8136.replay8136(p, contract, "BREAKOUT_RUNNER")
        assertEquals(3, r.unexecutable)
        assertEquals(1.0, r.residualShare, 0.0)
        assertEquals(0.0, r.proceeds, 0.0)
        assertFalse(r.capitalRecovered)
        assertTrue(r.exits.all { it.startsWith("UNEXECUTABLE:FREEZE") })
    }
}
