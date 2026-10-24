package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.PartialNetEconomics8094
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144.Stage8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8363 — exits keep winners (owner-forwarded audit 8360, items 3 and 4): profit locks are protective (not refused
 * by the 8163 requote test), green holds carry a floor, slippage is out of the advisor's hands, stops broadcast without
 * waiting on a balance proof, and the scalp stop stands outside entry noise.
 */
class Aate8363ExitsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    // ── 1. profit locks are protective ──

    @Test fun giveBackAndLockReasonsPassThe8163TestTakeProfitsAreStillChecked() {
        for (r in listOf("PROFIT_LOCK_8030", "TICK_PROFIT_LOCK_12PCT", "PEAK_GIVEBACK_20", "GIVE_BACK_FROM_PEAK", "PEAK_LOCK_45",
            "RAPID_DRAWDOWN_FROM_PEAK_SETTLE_BYPASS_6080", "TRAILING_STOP", "SCALP_PROFIT_LOCK_8351",
            "RUNNER_GREEN_FLOOR_8363_120PK_55PCT")) {
            assertTrue(r, ProfitLock8363.profitLock8363(r) || ExitTruthArbiter8144.protective8144(r))
            assertFalse("$r protects a shrinking gain: never refused by the requote test", ProfitExitTruth8163.profitTaking(r))
        }
        for (r in listOf("PARTIAL_TAKE_PROFIT", "SPIKE_CAPTURE_7943_T1_42PCT", "wallet_growth_harvest_4.3x",
            "RUNNER_MILESTONE_8136_T2_70PCT", "PEAK_CAPTURE", "protective_peak_partial_2113pct", "SCALP_TAKE_PROFIT_8351_T1",
            com.lifecyclebot.engine.sell.ProfitEscalation8189.reason8189("SPIKE_CAPTURE_7943_T1_45PCT"))) {
            assertFalse(r, ProfitLock8363.profitLock8363(r))
            assertTrue("$r sells into strength: still checked on its executable quote", ProfitExitTruth8163.profitTaking(r))
        }
        // The recovered-hold grace still lets a green lock out (RecoveredHoldGuard's own classification is unchanged).
        assertTrue(RecoveredHoldGuard.isProfitTakingExit7931("PROFIT_LOCK_8030"))
        // One classification for every door: the partial-economics door never holds a lock slice.
        assertFalse(PartialNetEconomics8094.discretionary8094("PROFIT_LOCK_PARTIAL", 0.3))
        assertTrue(PartialNetEconomics8094.discretionary8094("PARTIAL_TAKE_PROFIT", 0.25))
    }

    @Test fun everyDoorConsultsTheSameClassificationFirst() {
        val pt = src("engine/ProfitExitTruth8163.kt")
        val ptBody = pt.substringAfter("fun profitTaking(reason: String): Boolean {").substringBefore("val r = reason.uppercase()")
        assertTrue(ptBody.contains("if (try { ProfitLock8363.profitLock8363(reason) } catch (_: Throwable) { false }) return false"))
        val ne = src("engine/truth/NetEdge8030.kt")
        val neBody = ne.substringAfter("fun exitRefusal8030(").substringBefore("val structural = structural8030(reason)")
        assertTrue(neBody.contains("if (try { com.lifecyclebot.engine.ProfitLock8363.profitLock8363(reason) } catch (_: Throwable) { false }) return false"))
        val pe = src("engine/sell/PartialNetEconomics8094.kt").substringAfter("fun discretionary8094(").substringBefore("fun sliceNetSol8094(")
        assertTrue(pe.contains("com.lifecyclebot.engine.ProfitLock8363.profitLock8363(reason)"))
        // A refusal is labelled and handed to the spine as a refusal.
        assertTrue(pt.contains("PipelineHealthCollector.labelInc(\"PROFIT_EXIT_REFUSED_8163_\$why\")"))
        assertTrue(pt.contains("SellRouteSpine8144.noteRefused8363(ts.mint, \"PROFIT_EXIT_REFUSED_8163_\$why\")"))
    }

    @Test fun anRefusalIsNotCountedAsASignFailure() {
        SellRouteSpine8144.resetForTest8144()
        val a = "MintRefused8363aaaaaaaaaaaaaaaaaaaaaaaaaaa"
        SellRouteSpine8144.trigger8144(a, "PARTIAL_TAKE_PROFIT", "p", 1_000L)
        SellRouteSpine8144.mark8144(a, Stage8144.CLOSE_LEASE, 1_100L)
        SellRouteSpine8144.onPhase8144(a, "SELL_TX_BUILT", 1_200L)
        SellRouteSpine8144.noteRefused8363(a, "PROFIT_EXIT_REFUSED_8163_REQUOTE_BELOW_SLICE_COST", 1_300L)
        SellRouteSpine8144.onResult8144(a, "FAILED_RETRYABLE", 1_400L)
        assertNull("no stage failure for a policy refusal", SellRouteSpine8144.timer8144(a)!!.failedStage)
        assertTrue(SellRouteSpine8144.statusLine8144().contains("PROFIT_EXIT_REFUSED_8163_REQUOTE_BELOW_SLICE_COST=1"))
        // Without the refusal the same result is the SIGN-stage failure the 8360 report counted.
        val b = "MintSignFail8363bbbbbbbbbbbbbbbbbbbbbbbbbbb"
        SellRouteSpine8144.trigger8144(b, "PARTIAL_TAKE_PROFIT", "p", 1_000L)
        SellRouteSpine8144.mark8144(b, Stage8144.CLOSE_LEASE, 1_100L)
        SellRouteSpine8144.onPhase8144(b, "SELL_TX_BUILT", 1_200L)
        SellRouteSpine8144.onResult8144(b, "FAILED_RETRYABLE", 1_400L)
        assertEquals(Stage8144.SIGN, SellRouteSpine8144.timer8144(b)!!.failedStage)
        // A refusal older than the window is not borrowed by a later real failure.
        assertEquals(10_000L, SellRouteSpine8144.REFUSAL_WINDOW_MS_8363)
        SellRouteSpine8144.resetForTest8144()
    }

    // ── 2. floors on green holds ──

    @Test fun floorsArmOncePeakClearsBreakEvenAndKeepHalfThePeak() {
        assertTrue("not armed under net break-even + margin", GreenFloor8363.floor8363(5.0, 3.0).isNaN())
        assertEquals(7.5, GreenFloor8363.floor8363(15.0, 3.0), 1e-9)          // max(BE 3.5, 50 % of 15)
        assertEquals(90.0, GreenFloor8363.floor8363(180.0, 3.0), 1e-9)        // +180 % peak keeps +90 %
        assertEquals(0.5, GreenFloor8363.KEEP_FRACTION_8363, 0.0)
        assertTrue(GreenFloor8363.floor8363(Double.NaN, 3.0).isNaN())
        assertTrue("the +180 % peak at -13 % has long fired", GreenFloor8363.breached8363(-13.0, 180.0, 3.0))
        assertFalse(GreenFloor8363.breached8363(100.0, 180.0, 3.0))
        assertFalse("no floor before the peak clears break-even", GreenFloor8363.breached8363(-5.0, 4.0, 3.0))
    }

    @Test fun theTailIsReleasedUnderTheArmedFloorInsteadOfMinusForty() {
        val now = 10_000_000L
        val tail = TailBag8034.Tail(1L, now - 1_000L, 0)
        assertEquals(TailBag8034.Decision.Hold, TailBag8034.decidePure8034("MOONSHOT", "WEAK_MOMENTUM", -30.0, tail, now))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "WEAK_MOMENTUM", -30.0, tail, now, false, 7.5))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "WEAK_MOMENTUM", 50.0, tail, now, false, 90.0))
        assertEquals(TailBag8034.Decision.Hold, TailBag8034.decidePure8034("MOONSHOT", "WEAK_MOMENTUM", 120.0, tail, now, false, 90.0))
    }

    @Test fun floorHooksAreWiredAndProtective() {
        assertTrue(ExitTruthArbiter8144.protective8144("RUNNER_GREEN_FLOOR_8363_180PK_80PCT"))
        assertTrue(ExitTruthArbiter8144.protective8144("LIFECYCLE_GREEN_FLOOR_8363_300PK_140PCT"))
        val rg = src("engine/RunnerGrab7967.kt")
        assertTrue(rg.contains("GreenFloor8363.breachedFor8363(ts, g) -> \"RUNNER_GREEN_FLOOR_8363_"))
        assertTrue(rg.contains("if (GreenFloor8363.breachedFor8363(ts, g)) { GreenFloor8363.noteFired8363(\"RUNNER_DEFER_RELEASED\"); return null }"))
        val lh = src("engine/LifecycleHold8065.kt")
        assertTrue(lh.contains("GreenFloor8363.breachedFor8363(ts, g) -> \"LIFECYCLE_GREEN_FLOOR_8363_"))
        assertTrue(lh.contains("if (GreenFloor8363.breachedFor8363(ts, g)) { GreenFloor8363.noteFired8363(\"LIFECYCLE_RELEASED\"); return release8189(ts, reason) }"))
        val tb = src("engine/TailBag8034.kt")
        assertTrue(tb.contains("decidePure8034(lane, reason, pnlOf(ts), tail, nowMs, capitalRecovered, floor8363)"))
        // The rug / catastrophic exits still run first and are never held for a floor.
        val lhMark = lh.substringAfter("val why: Pair<String, Double>? = when {")
        assertTrue(lhMark.indexOf("g <= -FLOOR_PCT ->") < lhMark.indexOf("GreenFloor8363.breachedFor8363(ts, g)"))
        val rgExit = rg.substringAfter("val why = when {")
        assertTrue(rgExit.indexOf("g <= HARD_STOP_PCT ->") < rgExit.indexOf("GreenFloor8363.breachedFor8363(ts, g)"))
    }

    // ── 3. slippage out of the advisor's hands ──

    @Test fun slippageIsAdvisoryOnly() {
        assertTrue(AdvisorKeyAuthority8353.isAdvisoryOnly8353("slippageBps"))
        assertFalse(LlmParameterTuner.isAllowedKey("slippageBps"))
        assertFalse(LlmParameterTuner.allowedKeys().contains("slippageBps"))
        assertEquals("ADVISOR_KEY_ADVISORY_ONLY_8353_slippageBps", AdvisorKeyAuthority8353.advisoryOnlyLabel8353("slippageBps"))
        val restore = AdvisorKeyAuthority8353.decideRestore8353("slippageBps", 80.0, 100.0, 80.0, alreadyDone = false)
        assertEquals(AdvisorKeyAuthority8353.RestoreKind8353.RESTORE, restore.kind)
        assertEquals(100.0, restore.to, 0.0)
        val auto = src("engine/truth/AutoPipelineAdvisor6462.kt")
        assertFalse("no forced agreement for a slippage decrease", auto.contains("\"perPositionSizePct\", \"slippageBps\", \"trailingStopBasePct\""))
        assertTrue(auto.contains("c.key in setOf(\"perPositionSizePct\", \"trailingStopBasePct\"))"))
        val ka = src("engine/AdvisorKeyAuthority8353.kt")
        assertTrue(ka.contains("KEY_SLIPPAGE_8363 -> cfg.copy(slippageBps = Math.round(v).toInt())"))
        assertTrue(ka.contains("val current = currentValue8363(cfg, key)"))
        assertFalse(src("engine/LlmParameterTuner.kt").contains("\"slippageBps\" to ParamSpec"))
    }

    // ── 4. stops broadcast ──

    @Test fun stopsAreNeverParkedBehindABalanceProof() {
        assertTrue(StopBroadcast8363.stopClass8363("HARD_STOP"))
        assertTrue(StopBroadcast8363.stopClass8363("STOP_LOSS"))
        assertTrue(StopBroadcast8363.stopClass8363("CATASTROPHIC_HARD_BACKSTOP_-25"))
        assertFalse(StopBroadcast8363.stopClass8363("PROFIT_LOCK_8030"))
        assertFalse("an absent holding stays with the poller", StopBroadcast8363.waitAllowsStop8363("STOP_ACCOUNT_ABSENT_8288:HARD_STOP"))
        assertFalse(StopBroadcast8363.waitAllowsStop8363("FROZEN_BY_ISSUER_8122"))
        assertTrue(StopBroadcast8363.waitAllowsStop8363("BALANCE_UNKNOWN_WALLET_TOKEN_READ_INDETERMINATE"))
        val exec = src("engine/Executor.kt")
        val core = exec.substringAfter("private fun requestSellCore7948(")
        assertTrue(core.indexOf("if (isLivePositionEarly) StopBroadcast8363.releaseWait8363(ts, requestReason)") in 0 until
            core.indexOf("if (isLivePositionEarly && com.lifecyclebot.engine.sell.BalanceProofWaitState.isWaiting(ts.mint)) {"))
        assertTrue(exec.contains("val stop8363 = StopBroadcast8363.rescue8363(ts, reason, recovered, walletReadIndeterminate)"))
        assertTrue(exec.contains("if (stop8363 != null) { tokenData = stop8363; mapEmpty = false; recovered = true }"))
        assertTrue(src("engine/Exits8363.kt").contains("STOP_BROADCAST_WITHOUT_PROOF_WAIT_8363"))
        // The indeterminate-read forensic still exists for every other exit.
        assertTrue(src("engine/Exits8363.kt").contains("SELL_RPC_EMPTY_RESCUE_BLOCKED_INDETERMINATE"))
    }

    // ── 5. scalp stop out of entry noise ──

    @Test fun scalpStopFloorAndWarmup() {
        assertEquals(11.1, ScalpStop8363.stopPct8363(15.0, 4.1, 2.9), 1e-9)   // 2 x 4.1 + 2.9 beats 0.6 x 15
        assertEquals(9.0, ScalpStop8363.stopPct8363(15.0, Double.NaN, 0.0), 1e-9)
        assertEquals(ScalpStop8363.MIN_STOP_PCT_8363, ScalpStop8363.stopPct8363(5.0, 1.0, 0.0), 1e-9)
        assertEquals(0.6, ScalpStop8363.LANE_STOP_SHARE_8363, 0.0)
        assertEquals(2.0, ScalpStop8363.NOISE_MULT_8363, 0.0)
        assertEquals(60_000L, ScalpStop8363.WARMUP_MS_8363)
        // No scalp stop inside the first minute, however deep (catastrophic / rug are other doors).
        assertNull(ScalpStop8363.decide8363(-30.0, 0.0, 30_000L, 0, 15.0, 6.0, 4.1, 2.9))
        // Past the minute: -10 % is inside the 11.1 % stop, -12 % is not.
        assertNull(ScalpStop8363.decide8363(-10.0, 0.0, 61_000L, 0, 15.0, 6.0, 4.1, 2.9))
        assertEquals("SCALP_HARD_STOP_8351", ScalpStop8363.decide8363(-12.0, 0.0, 61_000L, 0, 15.0, 6.0, 4.1, 2.9)?.second)
        // The profit ladder is unchanged, warmup or not.
        assertEquals(0.5 to "SCALP_TAKE_PROFIT_8351_T1", ScalpStop8363.decide8363(16.0, 16.0, 5_000L, 0, 15.0, 6.0, 4.1, 2.9))
        val se = src("engine/truth/ScalpExit8351.kt")
        assertTrue(se.contains("com.lifecyclebot.engine.ScalpStop8363.decide8363(g, maxOf(peak, g), nowMs - pos.entryTime, st.tier, st.stopPct, st.costPct,"))
        assertTrue(se.contains("// superseded by V5.0.8363"))
        assertTrue(src("engine/Exits8363.kt").contains("ExitDoctrine8169.noisePct8169(candles.takeLast(12)"))
        assertTrue(src("engine/Exits8363.kt").contains("EntryChase7961.lanePenaltyPct7961(lane)"))
    }
}
