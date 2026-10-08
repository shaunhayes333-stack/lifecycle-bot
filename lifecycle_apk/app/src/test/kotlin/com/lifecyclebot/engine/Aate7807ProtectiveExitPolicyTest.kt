package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority
import com.lifecyclebot.engine.sell.SellSafetyPolicy
import com.lifecyclebot.engine.truth.ExitTelemetryStamper6732
import com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450
import com.lifecyclebot.engine.truth.StopLatencyClasses6464
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7807 — ProtectiveExitPolicy: operator-approved B1-B4 dispatch / bypass / distress routing. */
class Aate7807ProtectiveExitPolicyTest {

    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    // ── B2 / B3 — one hierarchy, one class function ─────────────────────────
    @Test fun every_emergency_reason_family_maps_into_the_bypass_class() {
        val capital = listOf("RAPID_CATASTROPHE_STOP", "CATASTROPHIC_HARD_BACKSTOP_-25", "PROTECTIVE_EXIT_CATASTROPHE_6450_RISKCLOCK",
            "DEEP_CATASTROPHE_NET", "ZOMBIE_CATASTROPHE_PENDING_RETRY", "TICK_CATASTROPHIC_CONFIRMED_-60PCT", "catastrophic_gap_guard_40")
        capital.forEach { assertEquals(it, Priority.CAPITAL_PRESERVATION, ProtectiveExitClass7807.of(it)) }
        val structural = listOf("RUG_DRAIN", "learned_rug_pattern", "STEALTH_MINT_RUG_CATASTROPHIC".replace("CATASTROPHIC", "X"),
            "HONEYPOT_DETECTED", "liquidity_collapse", "liquidity_drain", "reflex_liq_drain", "LIQUIDITY_REMOVED",
            "dev_dump", "DEV_SELL", "FREEZE_AUTHORITY_ACTIVE", "MINT_OR_FREEZE_AUTHORITY_LIVE",
            "STALE_PRICE_RUG_ESCAPE", "STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP", "THIN_LIQ_EARLY_RUG_BACKSTOP_10")
        structural.forEach { assertEquals(it, Priority.STRUCTURAL_EMERGENCY, ProtectiveExitClass7807.of(it)) }
        val hardSl = listOf("STOP", "STRICT_SL_-8", "stop_loss", "CASHGEN_STOP_LOSS", "HARD_STOP", "RAPID_HARD_FLOOR_STOP",
            "LANE_HARD_15PCT_SL_SHITCOIN", "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK", "STRUCTURE_STOP_7739_PULLBACK_RECLAIM_-9PCT",
            "MODE_EXIT_STOP_7744_LIQ", "fluid_stop_loss")
        hardSl.forEach { assertEquals(it, Priority.HARD_SL, ProtectiveExitClass7807.of(it)) }
        (capital + structural + hardSl).forEach { assertTrue(it, ProtectiveExitClass7807.isEmergency(it)) }
    }

    @Test fun trailing_profit_and_housekeeping_exits_are_not_emergencies() {
        listOf("trailing_stop", "TRAIL_STOP_PEAK", "STRUCTURE_TRAIL_STOP_7739_X_12PCT", "PROFIT_LOCK_STOP", "breakeven_ratchet",
            "PROTECTIVE_EXIT_TAKE_PROFIT_6450_RISKCLOCK").forEach {
            assertEquals(it, Priority.PROFIT_PROTECTION, ProtectiveExitClass7807.of(it))
            assertFalse(it, ProtectiveExitClass7807.isEmergency(it))
        }
        listOf("emergency_exit", "EMERGENCY_AUTO", "STALE_FLAT_CULL_7353", "DEAD_MONEY_CULL_7388", "UNDERWATER_TIME_STOP_7739_-3PCT",
            "MINT_AUTHORITY_DISABLED", "").forEach { assertFalse(it, ProtectiveExitClass7807.isEmergency(it)) }
    }

    @Test fun hierarchy_orders_exits_over_holds() {
        assertTrue(ProtectiveExitClass7807.exitOverridesHold(Priority.HARD_SL, Priority.STRATEGY_HOLD))
        assertTrue(ProtectiveExitClass7807.exitOverridesHold(Priority.CAPITAL_PRESERVATION, Priority.MIN_HOLD))
        assertFalse(ProtectiveExitClass7807.exitOverridesHold(Priority.MIN_HOLD, Priority.STRATEGY_HOLD))
        assertTrue(Priority.values().map { it.rank } == Priority.values().map { it.rank }.sorted())
    }

    @Test fun moonshot_hard_stops_and_kill_switch_always_override_discretionary_holds() {
        assertTrue(ProtectiveExitClass7807.bypassesHolds("STRICT_SL_-8", "STANDARD"))
        assertTrue(ProtectiveExitClass7807.bypassesHolds("STRICT_SL_-8", "MOONSHOT"))
        assertFalse(ProtectiveExitClass7807.bypassesHolds("trailing_stop", "MOONSHOT"))
        assertTrue(ProtectiveExitClass7807.bypassesHolds("dev_dump", "MOONSHOT"))
        assertTrue(ProtectiveExitClass7807.bypassesHolds("RAPID_CATASTROPHE_STOP", "MOONSHOT"))
        assertTrue(ProtectiveExitClass7807.bypassesHolds("KILL_SWITCH", "MOONSHOT"))
    }

    @Test fun emergency_reason_wins_on_a_softer_lease_and_in_the_pending_queue() {
        assertEquals("STRICT_SL_-8", ProtectiveExitClass7807.effectiveReason("TAKE_PROFIT_25", "STRICT_SL_-8"))
        assertEquals("RUG_DRAIN", ProtectiveExitClass7807.effectiveReason("RUG_DRAIN", "STRICT_SL_-8"))
        assertEquals("TAKE_PROFIT_25", ProtectiveExitClass7807.effectiveReason("TAKE_PROFIT_25", "TRAIL_STOP"))
    }

    // ── B1 — retry cadence ─────────────────────────────────────────────────
    @Test fun emergency_retry_cadence_is_2_3_5_8_then_10s_forever_and_normal_caps_at_15s() {
        assertEquals(listOf(2_000L, 3_000L, 5_000L, 8_000L, 10_000L, 10_000L, 10_000L),
            (1..7).map { ProtectiveExitClass7807.retryDelayMs("STRICT_SL_-8", it, 60_000L) })
        assertEquals(15_000L, ProtectiveExitClass7807.retryDelayMs("TAKE_PROFIT_25", 1, 60_000L))
        assertEquals(10_000L, ProtectiveExitClass7807.retryDelayMs("TAKE_PROFIT_25", 1, 10_000L))
        assertEquals(10_000L, ProtectiveExitClass7807.emergencyRetryDelayMs(500))
    }

    @Test fun risk_clock_redispatch_uses_the_emergency_ladder_for_stops() {
        val k = ProtectiveExitScheduler6450.TriggerKind.STOP_LOSS
        assertEquals(2_000L, ProtectiveExitScheduler6450.redispatchIntervalMs7807(k, 1))
        assertEquals(10_000L, ProtectiveExitScheduler6450.redispatchIntervalMs7807(ProtectiveExitScheduler6450.TriggerKind.CATASTROPHE, 9))
        assertEquals(15_000L, ProtectiveExitScheduler6450.redispatchIntervalMs7807(ProtectiveExitScheduler6450.TriggerKind.TAKE_PROFIT, 1))
    }

    @Test fun emergency_on_a_soft_lease_does_not_wait_out_the_soft_backoff() {
        val mint = "Mint7807Lease${System.nanoTime()}"
        assertNotNull(CloseLease.acquire(mint, "T", "TAKE_PROFIT_25"))
        CloseLease.scheduleBackoff(mint, "503")   // normal backoff (capped 15s)
        CloseLease.recordRetry(mint, "RETRYABLE")
        assertTrue(CloseLease.msUntilEligible(mint) <= 15_000L)
        assertNull("a soft exit still waits", CloseLease.acquire(mint, "T", "TAKE_PROFIT_25"))
        val lease = CloseLease.acquire(mint, "T", "STRICT_SL_-8")
        assertNotNull("escalated emergency re-enters immediately when prior soft attempt is no longer in flight", lease)
        assertEquals("STRICT_SL_-8", ProtectiveExitClass7807.effectiveReason(lease!!.emergencyReason7807 ?: lease.originalExitReason, "STRICT_SL_-8"))
        CloseLease.release(mint, "TEST_DONE")
    }

    // ── B4 — slippage + route escalation ───────────────────────────────────
    @Test fun emergency_slippage_ladder_escalates_per_attempt_to_a_50pct_cap() {
        assertEquals(listOf(500, 2_500, 3_500, 5_000), SellSafetyPolicy.ladder("STRICT_SL_-8", 1))
        assertEquals(listOf(2_500, 3_500, 5_000), SellSafetyPolicy.ladder("liquidity_collapse", 2))
        assertEquals(listOf(5_000), SellSafetyPolicy.ladder("RUG_DRAIN", 9))
        assertEquals(listOf(1_500, 2_500, 3_500, 5_000), ProtectiveExitClass7807.slippageLadderBps(1, null))
        assertEquals(5_000, SellSafetyPolicy.maxSlippageBps("dev_dump"))
        assertEquals(1_500, SellSafetyPolicy.initialSlippageBps("dev_dump"))
        assertEquals(500, SellSafetyPolicy.initialSlippageBps("STRICT_SL_-8"))
        // Non-emergency and manual ladders unchanged.
        assertEquals(listOf(200, 350, 500), SellSafetyPolicy.ladder("profit_lock_12.0x", 3))
        assertEquals(9999, SellSafetyPolicy.maxSlippageBps("MANUAL_EMERGENCY_RUG_DRAIN"))
        assertTrue(SellSafetyPolicy.ladder("STRICT_SL_-8", 1).all { it <= ProtectiveExitClass7807.EMERGENCY_SLIP_CAP_BPS_7807 })
    }

    @Test fun failed_emergency_attempts_are_remembered_across_a_released_lease() {
        val mint = "Mint7807Attempt${System.nanoTime()}"
        assertEquals(1, ProtectiveExitClass7807.attemptFor(mint, 1))
        ProtectiveExitClass7807.noteFailedAttempt(mint)
        ProtectiveExitClass7807.noteFailedAttempt(mint)
        assertEquals(3, ProtectiveExitClass7807.attemptFor(mint, 1))
        assertEquals(5, ProtectiveExitClass7807.attemptFor(mint, 5))
        assertEquals(listOf(3_500, 5_000), SellSafetyPolicy.ladder("STRICT_SL_-8", ProtectiveExitClass7807.attemptFor(mint, 1)))
        assertEquals(1, ProtectiveExitClass7807.attemptFor(mint, 1, System.currentTimeMillis() + 11L * 60_000L))
        ProtectiveExitClass7807.clearAttempts(mint)
        assertEquals(1, ProtectiveExitClass7807.attemptFor(mint, 1))
    }

    @Test fun route_escalates_only_for_emergencies_after_a_failed_attempt() {
        assertFalse(ProtectiveExitClass7807.shouldEscalateRoute("STRICT_SL_-8", 1))
        assertTrue(ProtectiveExitClass7807.shouldEscalateRoute("STRICT_SL_-8", 2))
        assertFalse(ProtectiveExitClass7807.shouldEscalateRoute("TAKE_PROFIT_25", 5))
        assertTrue(ExecutionHealthGuard.isEmergencyReason("dev_dump"))
        assertTrue(ExecutionHealthGuard.isEmergencyReason("FREEZE_AUTHORITY_ACTIVE"))
    }

    // ── Telemetry — trigger -> broadcast ────────────────────────────────────
    @Test fun trigger_to_broadcast_is_recorded_in_stop_latency_telemetry() {
        StopLatencyClasses6464.resetForTest()
        ExitTelemetryStamper6732.resetForTest()
        val mint = "Mint7807Tel${System.nanoTime()}"
        ExitTelemetryStamper6732.noteTrigger7807(mint, "RAPID_CATASTROPHE_STOP", System.currentTimeMillis() - 4_000L)
        ExitTelemetryStamper6732.noteTrigger7807(mint, "RAPID_CATASTROPHE_STOP")  // later stamp loses
        ExitTelemetryStamper6732.noteBroadcast7807(mint)
        ExitTelemetryStamper6732.noteBroadcast7807(mint)  // second broadcast: no second sample
        val snap = StopLatencyClasses6464.broadcastSnapshot7807()[StopLatencyClasses6464.Class.CATASTROPHIC_EXIT]!!
        assertEquals(1L, snap.first)
        assertTrue("earliest trigger wins: ${snap.second}", snap.second >= 4_000L)
        assertTrue(StopLatencyClasses6464.statusLine().contains("slaBreaches=1"))
    }

    // ── Wiring (source contracts) ───────────────────────────────────────────
    @Test fun executor_and_bot_service_are_wired_to_the_one_class() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("val bypass7807 = protectiveEmergencyAdmit7807(ts, reason)"))
        assertTrue(ex.contains("if (isLivePositionEarly && !bypass7807 && liveProfitDustExitShouldDefer(ts, reason))"))
        assertTrue(ex.contains("if (isLivePositionEarly && !bypass7807) {\n            val holdDelay = liveHoldDelayIfNeeded(ts, requestReason)"))
        assertTrue(ex.contains("(bypass7807 && fundedQuarantine7807(ts.mint))"))
        assertTrue(ex.contains("ProtectiveExitClass7807.bypassesHolds(requestReason, ts.position.tradingMode)"))
        assertTrue(ex.contains("val exitSignals = if (strictSlBypassesAdvisory7807(pos.tradingMode)) null"))
        assertTrue(ex.contains("val extendHold6725 = !strictSlBypassesAdvisory7807(pos.tradingMode) && try {"))
        assertTrue(ex.contains("emergencyRouteEscalated7807(ts, reason)"))
        assertTrue(ex.contains("val slippageLevels = emergencySlipLadder7807(ts, reason)"))
        assertTrue(ex.contains("\"EMERGENCY_EXIT_DISPATCHED_7807\""))
        assertTrue(ex.contains("\"EMERGENCY_EXIT_ROUTE_ESCALATED_7807\""))
        assertTrue(ex.contains("\"EMERGENCY_EXIT_SLIP_ESCALATED_7807\""))
        assertTrue(src("engine/sell/CloseLease.kt").contains("\"EMERGENCY_EXIT_RETRY_7807\""))
        assertTrue(src("engine/truth/ProtectiveExitScheduler6450.kt").contains("\"EMERGENCY_EXIT_RETRY_7807\""))
        assertTrue(src("engine/LiveTradeLogStore.kt").contains("ExitTelemetryStamper6732.noteBroadcast7807(mint)"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("ExitTelemetryStamper6732.noteTrigger7807("))
        assertTrue(bot.contains("ProtectiveExitClass7807.retryDelayMs(exit.reason, 1, 10_000L)"))
        assertTrue(bot.contains("ProtectiveExitClass7807.capNormalWindowMs(OFF_LOOP_SELL_RETRY_MS_7288)"))
        assertTrue(src("engine/PendingSellQueue.kt").contains("sortedBy { com.lifecyclebot.engine.sell.ProtectiveExitClass7807.of(it.reason).rank }"))
    }

    @Test fun the_class_reads_no_learner_state() {
        val cls = src("engine/sell/ProtectiveExitClass7807.kt")
        listOf("UnifiedExitPolicyHead", "Council7740", "FluidLearningAI", "ScoreExpectancyTracker", "AdvancedExitManager", "Oracle")
            .forEach { assertFalse(it, cls.substringAfter("object ProtectiveExitClass7807").contains(it)) }
    }
}
