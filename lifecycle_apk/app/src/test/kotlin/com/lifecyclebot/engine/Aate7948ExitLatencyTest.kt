package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.sell.ExitDispatchLatency7948
import com.lifecyclebot.engine.sell.ExitHotPath7809
import com.lifecyclebot.engine.truth.ExitStageTiming7876
import com.lifecyclebot.engine.truth.ExitTelemetryStamper6732
import com.lifecyclebot.engine.truth.StopLatencyClasses6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7948 — ExitLatency: honest trigger stamps, supervisor sell hand-off, sign-delay skip, hot-unit coalescing. */
class Aate7948ExitLatencyTest {

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private fun trailingQueueMaxMs(): Long? {
        val m = Regex("TRAILING_STOP\\[queue=avg\\d+/max(\\d+)ms").find(ExitStageTiming7876.statusLine()) ?: return null
        return m.groupValues[1].toLong()
    }

    @Test fun supervisor_threads_hand_their_sells_off_and_nothing_else_does() {
        assertTrue(ExitDispatchLatency7948.runsOffCallerThread("AATE-Entry-6647"))
        assertTrue(ExitDispatchLatency7948.runsOffCallerThread(ExitDispatchLatency7948.SUPERVISOR_THREAD_PREFIX_7948))
        assertFalse("the hand-off pool itself never hops again", ExitDispatchLatency7948.runsOffCallerThread("AATE-SupervisorSell-7948"))
        assertFalse(ExitDispatchLatency7948.runsOffCallerThread("AATE-ExitUnit-7809"))
        assertFalse(ExitDispatchLatency7948.runsOffCallerThread(null))
        assertTrue("wait stays inside the 15 s supervisor budget", ExitDispatchLatency7948.SUPERVISOR_SELL_WAIT_MS_7948 < 15_000L)
        // the prefix is the supervisor executor's real thread name
        assertTrue(src("engine/BotService.kt").contains("Thread(r, \"${ExitDispatchLatency7948.SUPERVISOR_THREAD_PREFIX_7948}\")"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("ExitDispatchLatency7948.runsOffCallerThread(Thread.currentThread().name)"))
        assertTrue(ex.contains("return requestSellCore7948(ts, reason, wallet, walletSol)"))
    }

    @Test fun protective_and_trailing_exits_skip_the_sign_delay_ordinary_exits_keep_it() {
        assertTrue(ExitDispatchLatency7948.skipsSignDelay("RAPID_TRAILING_STOP"))
        assertTrue(ExitDispatchLatency7948.skipsSignDelay("TICK_PROFIT_LOCK_peak40_now30"))
        assertTrue(ExitDispatchLatency7948.skipsSignDelay("RAPID_CATASTROPHE_STOP"))
        assertTrue(ExitDispatchLatency7948.skipsSignDelay("STRICT_SL_-8"))
        assertTrue(ExitDispatchLatency7948.skipsSignDelay("RUG_SAFETY_NET"))
        assertFalse(ExitDispatchLatency7948.skipsSignDelay("MAX_HOLD_TIME_EXIT"))
        assertFalse(ExitDispatchLatency7948.skipsSignDelay(""))
        val ex = src("engine/Executor.kt")
        assertEquals(2, Regex("signDelayFor7948\\(reason\\)").findAll(ex).count())
    }

    @Test fun a_deferred_trigger_does_not_time_its_hold_as_queue() {
        val m = "Mint7948Defer${System.nanoTime()}"
        val t0 = 5_000_000L
        assertTrue(ExitStageTiming7876.onTrigger(m, StopLatencyClasses6464.Class.TRAILING_STOP, t0, t0))
        ExitStageTiming7876.withdrawUndispatched7948(m)   // e.g. DEFER_STYLE_MIN_HOLD / HOLD_MOONBAG
        // the real dispatch 200 s later starts its own clock
        assertTrue(ExitStageTiming7876.onTrigger(m, StopLatencyClasses6464.Class.TRAILING_STOP, t0 + 200_000L, t0 + 200_000L))
        ExitStageTiming7876.onPhase(m, "SELL_START", t0 + 201_000L)
        val max = trailingQueueMaxMs()
        assertTrue("queue max=$max", max != null && max < 10_000L)
    }

    @Test fun a_restamp_while_the_sell_awaits_its_outcome_is_ignored() {
        val m = "Mint7948Await${System.nanoTime()}"
        val t0 = 7_000_000L
        assertTrue(ExitStageTiming7876.onTrigger(m, StopLatencyClasses6464.Class.TRAILING_STOP, t0, t0))
        ExitStageTiming7876.onPhase(m, "SELL_START", t0 + 500L)
        ExitStageTiming7876.withdrawUndispatched7948(m)    // dispatched: kept
        ExitStageTiming7876.onPhase(m, "SELL_BROADCAST", t0 + 1_500L)
        ExitStageTiming7876.closeUnbroadcast7948(m)        // awaiting outcome: kept
        assertFalse(ExitStageTiming7876.onTrigger(m, StopLatencyClasses6464.Class.TRAILING_STOP, t0 + 30_000L, t0 + 30_000L))
        // past the outcome window a new condition stamps again
        assertTrue(ExitStageTiming7876.onTrigger(m, StopLatencyClasses6464.Class.TRAILING_STOP, t0 + 92_000L, t0 + 92_000L))
    }

    @Test fun stamper_drops_restamps_during_confirmation_and_clears_on_close() {
        StopLatencyClasses6464.resetForTest()
        ExitTelemetryStamper6732.resetForTest()
        val m = "Mint7948Stamp${System.nanoTime()}"
        val now = System.currentTimeMillis()
        ExitTelemetryStamper6732.noteTrigger7807(m, "RAPID_TRAILING_STOP", now - 1_000L)
        ExitStageTiming7876.onPhase(m, "SELL_START", now)
        ExitStageTiming7876.onPhase(m, "SELL_BROADCAST", now)
        ExitTelemetryStamper6732.noteBroadcast7807(m)
        // the hot loop re-asks while verifySell runs: no new trigger, no second sample
        ExitTelemetryStamper6732.noteTrigger7807(m, "RAPID_TRAILING_STOP")
        ExitTelemetryStamper6732.noteBroadcast7807(m)
        assertEquals(1L, StopLatencyClasses6464.broadcastSnapshot7807()[StopLatencyClasses6464.Class.TRAILING_STOP]!!.first)
        // a non-emergency deferral withdraws both the trigger and the stage track
        val d = "Mint7948Withdraw${System.nanoTime()}"
        ExitTelemetryStamper6732.noteTrigger7807(d, "RAPID_TRAILING_STOP", now - 120_000L)
        ExitTelemetryStamper6732.withdrawDeferred7809(d, "")
        ExitTelemetryStamper6732.noteBroadcast7807(d)
        assertEquals(1L, StopLatencyClasses6464.broadcastSnapshot7807()[StopLatencyClasses6464.Class.TRAILING_STOP]!!.first)
    }

    @Test fun a_selling_hot_unit_is_coalesced_not_replaced() {
        ExitHotPath7809.resetForTest()
        val k = "Mint7948Unit"
        assertTrue(ExitHotPath7809.tryBegin(k, 1_000L))
        val late = 1_000L + ExitHotPath7809.UNIT_STUCK_MS_7809 + 1L
        assertFalse("in-flight close: the unit is selling", ExitHotPath7809.tryBegin(k, late, true))
        assertTrue("no close in flight: a wedged unit is still replaced", ExitHotPath7809.tryBegin(k, late, false))
        ExitHotPath7809.resetForTest()
    }

    @Test fun close_lease_reports_an_attempt_in_flight() {
        val m = "Mint7948Lease${System.nanoTime()}"
        assertFalse(CloseLease.sellInFlight7948(m))
        val lease = CloseLease.acquire(m, "SYM", "RAPID_TRAILING_STOP")
        assertTrue(lease != null)
        assertTrue(CloseLease.sellInFlight7948(m))
        CloseLease.recordRetry(m, "RETRYABLE")
        assertFalse("between attempts the lease is not selling", CloseLease.sellInFlight7948(m))
        CloseLease.release(m, "TEST")
        assertFalse(CloseLease.sellInFlight7948(m))
    }

    @Test fun sweeps_dispatch_per_mint_instead_of_selling_inline() {
        val bot = src("engine/BotService.kt")
        val sweep = bot.substringAfter("private fun sweepUniversalExits(").substringBefore("private suspend fun cleanupWatchlist()")
        assertTrue(sweep.contains("requestSellOffLoop7288(ts, reason6882, wallet, effectiveBalance)"))
        assertTrue(sweep.contains("dispatchHotExitUnit7809(ts, wallet, effectiveBalance)"))
        assertFalse(sweep.contains("executor.runManageOnly(ts, wallet, effectiveBalance)"))
        assertTrue(bot.contains("ExitHotPath7809.tryBegin(key7809, startedAt7809, selling7948)"))
    }
}
