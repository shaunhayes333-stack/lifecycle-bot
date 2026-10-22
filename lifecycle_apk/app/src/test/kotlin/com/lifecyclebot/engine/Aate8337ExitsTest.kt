package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.truth.FieldManual7715
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8337 — the owner's exit block: profit protection the cost model cannot switch off (FIX 3), the runner hard stop's
 * 60 s grace with confirmation (FIX 4), the exit door order and receipts (FIX 9), the scalp tier before the plan is
 * confirmed (FIX 10a) and RAPID_FLUID_STOP back on the normal loss path (FIX 10b).
 */
class Aate8337ExitsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aTwentyPercentPeakKeepsFortyPercentWhateverTheCostModelReads() {
        // A fresh curve read cost 30% (impact on its real SOL): break-even 30.5 is above the whole peak.
        assertEquals(10.0, ExitDoctrine8169.runnerProtectFloor8169(20.0, 30.0), 1e-9)
        assertEquals(10.0, FieldManual7715.runnerAwareStop7935(Double.NaN, 20.0, 30.0, runnerDeferred = true) { -15.0 }, 1e-9)
        // A +30% peak with a 30% cost keeps 15 (half the peak caps break-even; 40% of the peak is 12).
        assertEquals(15.0, ExitDoctrine8169.runnerProtectFloor8169(30.0, 30.0), 1e-9)
        assertEquals(15.0, FieldManual7715.runnerAwareStop7935(Double.NaN, 30.0, 30.0, runnerDeferred = true) { -15.0 }, 1e-9)
        // Under +20% nothing changes: the pre-trail loss stop stands.
        assertTrue(ExitDoctrine8169.runnerProtectFloor8169(19.0, 30.0).isNaN())
        assertEquals(-15.0, FieldManual7715.runnerAwareStop7935(Double.NaN, 19.0, 30.0, runnerDeferred = true) { -15.0 }, 1e-9)
        // The 7935 / 8169 break-even and half-peak rules still come first (their pins stand).
        assertEquals(3.5, ExitDoctrine8169.runnerProtectFloor8169(21.0, 3.0), 1e-9)
        assertEquals(22.5, ExitDoctrine8169.runnerProtectFloor8169(45.0, 3.0), 1e-9)
        assertEquals(3.5, FieldManual7715.runnerAwareStop7935(Double.NaN, 21.0, 3.0, runnerDeferred = true) { -15.0 }, 1e-9)
    }

    @Test fun theGiveBackLockArmsAtTwentyFive() {
        assertEquals(25.0, RunnerExitProfile7277.MIN_PEAK_FOR_GIVEBACK_LOCK_PCT, 1e-9)
        assertTrue(RunnerExitProfile7277.deferGiveBackLock("MOONSHOT", 24.0))
        assertFalse(RunnerExitProfile7277.deferGiveBackLock("MOONSHOT", 26.0))
    }

    @Test fun protectiveReasonsPassTheCostModelAndAreNeverProfitTaking() {
        for (r in listOf("RUG_PULL_DETECTED_-60PCT", "CATASTROPHIC_-40PCT", "RUNNER_HARD_STOP_7967_-36PCT",
            "RUNNER_STRUCTURE_BREAK_7967_1M_45PCT", "RUNNER_PEAK_CAPTURE_7980_250PK_120PCT", "RUNNER_FROM_ENTRY_GIVEBACK_8104_80PK_30PCT")) {
            assertTrue(r, ExitTruthArbiter8144.protective8144(r))
            assertFalse(r, ProfitExitTruth8163.profitTaking(r))
        }
        // The ordinary profit takes are still profit-taking.
        assertTrue(ProfitExitTruth8163.profitTaking("SPIKE_CAPTURE_7943_T1_42PCT"))
        assertTrue(ProfitExitTruth8163.profitTaking("wallet_growth_harvest_4.3x"))
        val ne = src("engine/truth/NetEdge8030.kt")
        val body = ne.substringAfter("fun exitRefusal8030(").substringBefore("val structural = structural8030(reason)")
        assertTrue(body.contains("if (try { com.lifecyclebot.engine.ExitTruthArbiter8144.protective8144(reason) } catch (_: Throwable) { false }) return false"))
        val pt = src("engine/ProfitExitTruth8163.kt")
        val ptBody = pt.substringAfter("fun profitTaking(reason: String): Boolean {").substringBefore("val r = reason.uppercase()")
        assertTrue(ptBody.contains("if (try { ExitTruthArbiter8144.protective8144(reason) } catch (_: Throwable) { false }) return false"))
    }

    @Test fun theRunnerHardStopKeepsItsMinuteUnlessConfirmedOrPastEighty() {
        // Inside the first minute an unconfirmed -36% waits ...
        assertTrue(RunnerGrab7967.hardStopWaits8337(-36.0, 10_000L, confirmed = false))
        // ... a confirmed one sells ...
        assertFalse(RunnerGrab7967.hardStopWaits8337(-36.0, 10_000L, confirmed = true))
        // ... a catastrophe past -80% sells unconfirmed ...
        assertFalse(RunnerGrab7967.hardStopWaits8337(-85.0, 10_000L, confirmed = false))
        // ... and after the minute the hard stop sells as before.
        assertFalse(RunnerGrab7967.hardStopWaits8337(-36.0, 60_000L, confirmed = false))
        // A read above the hard stop is not this door's business.
        assertFalse(RunnerGrab7967.hardStopWaits8337(-20.0, 10_000L, confirmed = false))
        assertFalse(RunnerGrab7967.hardStopWaits8337(Double.NaN, 10_000L, confirmed = false))
        val grab = src("engine/RunnerGrab7967.kt")
        assertTrue(grab.contains("if (hardStopWaits8337(g, nowMs - pos.entryTime, hardStopConfirmed8337(ts, nowMs))) {"))
        assertTrue(grab.contains("PipelineHealthCollector.labelInc(\"RUNNER_HARD_STOP_UNCONFIRMED_8337\")"))
        assertTrue(grab.contains("com.lifecyclebot.engine.truth.HeldExitQuote8189.fresh8189(ts.mint, nowMs)"))
        // The grace is placed before the hard-stop sell and after the 8271 first-minute soft-exit grace.
        val i8271 = grab.indexOf("if (nowMs - pos.entryTime < 60_000L && !peakGiveback7980(peak, g) && g > HARD_STOP_PCT) return")
        val i8337 = grab.indexOf("if (hardStopWaits8337(g, nowMs - pos.entryTime, hardStopConfirmed8337(ts, nowMs))) {")
        val iSell = grab.indexOf("g <= HARD_STOP_PCT -> \"RUNNER_HARD_STOP_7967_\${g.toInt()}PCT\"")
        assertTrue(i8271 in 0 until i8337 && i8337 < iSell)
    }

    @Test fun aFullSizePartialGoesThroughRequestSell() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("val fullResult6566 = requestSell(ts, reason, activeWallet, walletBalance)"))
        assertFalse(e.contains("doSell(ts, \"[PARTIAL→FULL] \$reason\", activeWallet, walletBalance)"))
    }

    @Test fun theExitDoorsRunBeforeTheBudgetAndReceiptsSayWhatHappened() {
        val e = src("engine/Executor.kt")
        val body = e.substringAfter("fun requestPartialSell(").substringBefore("rehydratePositionFromSubTraders(ts)")
        val iNet = body.indexOf("NetEdge8030.exitRefusal8030(ts, reason, getActualPrice(ts), partial = true)")
        val iUnecon = body.indexOf("if (partialUneconomic8094(ts, requested8337, reason)) {")
        val iBudget = body.indexOf("val pct = RunnerExitLive8136.budget8136(ts, sellPercentage.coerceIn(0.0, 1.0), reason)")
        val iTail = body.indexOf("TailBag8034.partialHeld8034(ts, reason)")
        val iLife = body.indexOf("LifecycleHold8065.partialHeld8065(ts, reason)")
        // 9a — net-profit and uneconomic doors before the budget (which takes the arbiter ticket); tail / lifecycle after it.
        assertTrue(iNet in 0 until iUnecon && iUnecon < iBudget && iBudget < iTail && iTail < iLife)
        // 9b — the escalated full exit is reported as applied (not terminal) under its own authority result.
        assertTrue(body.contains("if (profitEscalated8189(ts, reason, wallet, walletBalance)) { receipt6566?.invoke(PartialSellReceipt6566(applied = true, terminal = false, authorityResult = \"ESCALATED_FULL_8189\")); return }"))
        assertFalse(body.contains("if (profitEscalated8189(ts, reason, wallet, walletBalance)) { receipt6566?.invoke(PartialSellReceipt6566(false)); return }"))
        // 9c — a spike-capture rung refused at a door is counted by the door.
        for (door in listOf("NET_PROFIT", "UNECONOMIC", "TAILBAG", "LIFECYCLE_HOLD")) assertTrue(door, body.contains("spikeRefused8337(reason, \"$door\")"))
        assertTrue(e.contains("if (reason.contains(\"SPIKE_CAPTURE_7943\")) try { PipelineHealthCollector.labelInc(\"SPIKE_REFUSED_8337_\$door\") } catch (_: Throwable) {}"))
        val r = Executor.PartialSellReceipt6566(applied = true, terminal = false, authorityResult = "ESCALATED_FULL_8189")
        assertTrue(r.applied)
        assertFalse(r.terminal)
        assertEquals("ESCALATED_FULL_8189", r.authorityResult)
    }

    @Test fun aRunnerFromEntrySellsTheScalpTierUntilItsPlanIsConfirmed() {
        // Pure rule: scalp tiers until the plan is sealed AND the continuation is confirmed.
        assertTrue(RunnerGrab7967.scalpTiersUntilConfirmed8337(runnerFromEntry = true, planSealed = false, continuationConfirmed = false))
        assertTrue(RunnerGrab7967.scalpTiersUntilConfirmed8337(runnerFromEntry = true, planSealed = true, continuationConfirmed = false))
        assertTrue(RunnerGrab7967.scalpTiersUntilConfirmed8337(runnerFromEntry = true, planSealed = false, continuationConfirmed = true))
        assertFalse(RunnerGrab7967.scalpTiersUntilConfirmed8337(runnerFromEntry = true, planSealed = true, continuationConfirmed = true))
        assertFalse(RunnerGrab7967.scalpTiersUntilConfirmed8337(runnerFromEntry = false, planSealed = false, continuationConfirmed = false))
        // The scalp tier is SpikeCapture's own (+40% sells 60%), never a learned ladder.
        assertEquals(40.0, SpikeCapture7943.TIERS.first().first, 1e-9)
        assertEquals(0.60, SpikeCapture7943.TIERS.first().second, 1e-9)
        assertEquals(100.0, RunnerGrab7967.TIERS_7967.first().first, 1e-9)
        // A live runner-from-entry position whose continuation is not yet confirmed reads the scalp tiers.
        val ts = com.lifecyclebot.data.TokenState(mint = "fromEntry8337", position = com.lifecyclebot.data.Position(qtyToken = 1_000_000.0,
            entryPrice = 1.0, entryTime = System.currentTimeMillis() - 60_000L, tradingMode = "MOONSHOT", isPaperPosition = false,
            peakGainPct = 0.0, entryPolicySnapshot = "style=BREAKOUT_RUNNER;entryTactic=BREAKOUT"))
        assertTrue(TradeExitContract8077.runnerFromEntry8104(ts))
        assertFalse(RunnerLive8153.continuationConfirmed8153(ts))
        assertEquals(SpikeCapture7943.TIERS, RunnerGrab7967.tiersFor7967(ts))
        // A QUICK_FLIP scalp keeps no runner ladder at all.
        val quick = com.lifecyclebot.data.TokenState(mint = "quick8337", position = com.lifecyclebot.data.Position(qtyToken = 1_000_000.0,
            entryPrice = 1.0, entryTime = System.currentTimeMillis() - 60_000L, tradingMode = "MOONSHOT", isPaperPosition = false,
            peakGainPct = 0.0, entryPolicySnapshot = "style=QUICK_FLIP;entryTactic=BREAKOUT"))
        assertNull(RunnerGrab7967.tiersFor7967(quick))
        val grab = src("engine/RunnerGrab7967.kt")
        assertTrue(grab.contains("if (holding7967(ts)) (if (scalpTierHolds8337(ts)) SpikeCapture7943.TIERS else TIERS_7967)"))
        // The scalp tiers book on the SCALP ladder (ladder identity, 8331).
        assertTrue(src("engine/SpikeCapture7943.kt").contains("val ladder8153 = RunnerLive8153.ladderFor8153(runnerTiers8153 != null && runnerTiers8153 !== TIERS)"))
    }

    @Test fun rapidFluidStopIsBackOnTheNormalLossPath() {
        assertEquals(ProtectiveExitClass7807.Priority.NONE, ProtectiveExitClass7807.of("RAPID_FLUID_STOP"))
        assertFalse(ProtectiveExitClass7807.isEmergency("RAPID_FLUID_STOP"))
        // The profit side and the real hard stops are untouched.
        assertEquals(ProtectiveExitClass7807.Priority.PROFIT_PROTECTION, ProtectiveExitClass7807.of("RAPID_TRAILING_STOP"))
        assertEquals(ProtectiveExitClass7807.Priority.HARD_SL, ProtectiveExitClass7807.of("RUNNER_HARD_STOP_7967_-36PCT"))
        assertEquals(ProtectiveExitClass7807.Priority.HARD_SL, ProtectiveExitClass7807.of("FLUID_FLOOR_-12PCT"))
        assertEquals(ProtectiveExitClass7807.Priority.CAPITAL_PRESERVATION, ProtectiveExitClass7807.of("CATASTROPHIC_-60PCT"))
        assertFalse(src("engine/sell/ProtectiveExitClass7807.kt").contains("r.contains(\"FLUID_STOP\")"))
    }
}
