package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellRouteEndpoint8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Route8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.ExitQuoteCorroboration7928
import com.lifecyclebot.engine.truth.StrategyMove8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8529 — the 8527 live session (16 closes, 3 W / 13 L, PF 0.36, -0.031 SOL):
 *  1. entries bought the top: the expected move was the hour's move already made (STONKS +70 % in the hour);
 *  2. HonEQ5 stopped out at -1.9 % (10 s), bought back 14.5 min later by another lane, -25 %;
 *  3. stops cancelled by a quote one point above the line, landing at -21 / -25 / -30 % against -15 %;
 *  4. Raydium / PumpPortal sells landing 0/3 and 1/3 still tried first;
 */
class Aate8529EntryExitTruthTest {

    // ── 1. the expected move is what is left of the hour ──

    @Test fun a_coin_at_the_top_of_its_hour_has_no_move_left() {
        // STONKS-shaped: +70 % from the hour low, at the high. The read said "the lane mandate" (30 %).
        assertEquals(0.0, StrategyMove8153.remaining8529(30.0, 70.0, 0.0)!!, 1e-9)
        // part-way up: what is left of the move, or the room back to the high, whichever is larger
        assertEquals(20.0, StrategyMove8153.remaining8529(30.0, 10.0, 5.0)!!, 1e-9)
        assertEquals(12.0, StrategyMove8153.remaining8529(30.0, 25.0, 12.0)!!, 1e-9)
        // at the low: the whole move is ahead
        assertEquals(30.0, StrategyMove8153.remaining8529(30.0, 0.0, 40.0)!!, 1e-9)
        // never above the read it starts from; unknown position = no change
        assertEquals(30.0, StrategyMove8153.remaining8529(30.0, 0.0, 400.0)!!, 1e-9)
        assertNull(StrategyMove8153.remaining8529(30.0, null, 5.0))
        assertNull(StrategyMove8153.remaining8529(30.0, 5.0, Double.NaN))
    }

    @Test fun hour_position_is_read_from_the_low_high_and_current_price() {
        val (run, room) = StrategyMove8153.hourPosition8529(1.0, 1.7, 1.7)!!
        assertEquals(70.0, run, 1e-9); assertEquals(0.0, room, 1e-9)
        val (run2, room2) = StrategyMove8153.hourPosition8529(1.0, 2.0, 1.25)!!
        assertEquals(25.0, run2, 1e-9); assertEquals(60.0, room2, 1e-9)
        assertNull(StrategyMove8153.hourPosition8529(0.0, 2.0, 1.0))
        assertNull(StrategyMove8153.hourPosition8529(1.0, 0.5, 1.0))
    }

    @Test fun the_decision_path_charges_the_spent_move_but_not_a_proven_strategy_or_a_launch() {
        // dense 36 % range, mandate 45 %: the hour read is 36 %; the price has run 30 % of it and sits 5 % under the high.
        val spent = StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 6.0, false,
            runupFromHourLowPct8529 = 30.0, roomToHourHighPct8529 = 5.0)
        assertEquals("RANGE", spent.basis)
        assertEquals(6.0, spent.expectedGrossPct, 1e-9)
        // the same coin without a position read keeps the old number (no invented data)
        assertEquals(36.0, StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 6.0, false).expectedGrossPct, 1e-9)
        // a setup with its own supported record is judged on that record
        val strat = StrategyMove8153.expected8153(50.0, 4.0, true, null, 40, 12.0, 3.0, 6.0, false,
            runupFromHourLowPct8529 = 70.0, roomToHourHighPct8529 = 0.0)
        assertEquals("STRATEGY", strat.basis); assertEquals(18.0, strat.expectedGrossPct, 1e-9)
        // a coin inside its launch window has no prior hour to have spent — UNLESS it has already documented a run.
        // superseded: `assertEquals(36.0, launch.expectedGrossPct)` for a +36 % in-window run — V5.0.8535 §P0-04: a documented
        // in-window run from the low (>= LAUNCH_SPENT_MIN_PCT_8535) is consumed move; only the undocumented first move is exempt.
        val launch = StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 6.0, true,
            inLaunchWindow8418 = true, runupFromHourLowPct8529 = 36.0, roomToHourHighPct8529 = 0.0)
        assertEquals(0.0, launch.expectedGrossPct, 1e-9)
        val firstMove = StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 6.0, true,
            inLaunchWindow8418 = true, runupFromHourLowPct8529 = 20.0, roomToHourHighPct8529 = 0.0)
        assertEquals(36.0, firstMove.expectedGrossPct, 1e-9)
    }

    // ── 2. a stop-out is a real loss whatever the % ──

    @Test fun a_losing_protective_stop_close_is_a_real_loss() {
        assertTrue(RebuyLockout8019.stoppedLoss8529(-1.9, "RAPID_CATASTROPHE_STOP"))
        assertTrue(RebuyLockout8019.stoppedLoss8529(-25.0, "LANE_HARD_15PCT_SL_SHITCOIN"))
        assertTrue(RebuyLockout8019.stoppedLoss8529(-6.0, "RAPID_SUB_TRADER_HARD_FLOOR"))
        assertTrue(RebuyLockout8019.stoppedLoss8529(-8.0, "PROTECTIVE_EXIT_STOP_LOSS_6450_RISKCLOCK"))
        assertTrue(RebuyLockout8019.stoppedLoss8529(-21.0, "SWEEP_FLUID_FLOOR_-15"))
        // not stop-outs: a win, an operator / shutdown close, a profit lock, a take
        assertFalse(RebuyLockout8019.stoppedLoss8529(11.0, "TICK_PROFIT_LOCK_peak11_now2"))
        assertFalse(RebuyLockout8019.stoppedLoss8529(-2.5, "MANUAL"))
        assertFalse(RebuyLockout8019.stoppedLoss8529(-1.7, "bot_shutdown"))
        assertFalse(RebuyLockout8019.stoppedLoss8529(-3.0, "fresh_launch_quick_take_7737_50pct"))
        assertFalse(RebuyLockout8019.stoppedLoss8529(Double.NaN, "RAPID_CATASTROPHE_STOP"))
        assertFalse(RebuyLockout8019.stoppedLoss8529(-3.0, ""))
    }

    @Test fun honeq5_is_not_bought_back_fifteen_minutes_after_its_stop_out() {
        val now = 1_900_000_000_000L
        val mint = "HONEQ5_8529_TEST"
        RebuyLockout8019.onClose8019(mint, now - 14 * 60_000L - 30_000L, -1.9, "RAPID_CATASTROPHE_STOP")
        assertEquals("BIG_LOSER_8130", RebuyLockout8019.refusal8019(mint, now))
        // the old call shape (no reason) keeps the old rule: a -1.9 % close only takes the 10-minute lockout
        val other = "SCRATCH_8529_TEST"
        RebuyLockout8019.onClose8019(other, now - 14 * 60_000L, -1.9)
        assertNull(RebuyLockout8019.refusal8019(other, now))
    }

    // ── 3. a quote at the stop line does not cancel the stop ──

    @Test fun only_a_quote_better_than_half_the_stop_depth_cancels_a_bound_stop() {
        assertFalse("-14 % quote on a -15 % line sells", ExitQuoteCorroboration7928.quoteRefutes8521(-25.0, -14.0, -15.0))
        assertFalse(ExitQuoteCorroboration7928.quoteRefutes8521(-30.0, -9.0, -15.0))
        assertTrue("a phantom mark (quote -3 %) is refuted", ExitQuoteCorroboration7928.quoteRefutes8521(-82.0, -3.4, -15.0))
        assertTrue("a recovered queued stop is refuted", ExitQuoteCorroboration7928.quoteRefutes8521(-3.0, -4.5, -20.0))
        assertFalse(ExitQuoteCorroboration7928.quoteRefutes8521(-82.0, -16.0, -15.0))
        // no bound line: the phantom-gap rule is unchanged
        assertTrue(ExitQuoteCorroboration7928.quoteRefutes8521(-40.0, -2.0, null))
        assertFalse(ExitQuoteCorroboration7928.quoteRefutes8521(-40.0, -38.0, null))
    }

    @Test fun the_sell_path_sells_a_stop_whose_quote_sits_at_the_line() {
        ExitQuoteCorroboration7928.resetForTest8521()
        // 0.0335 SOL basis, quote 0.0288 SOL out = -14 % on a -15 % line: was refused (hold), now sells
        assertTrue(ExitQuoteCorroboration7928.allowSell("line8529", "X", "LANE_HARD_15PCT_SL_SHITCOIN", -25.0, 0.0335,
            nowMs = 1_000L, positionIdentity8521 = "p1|1|1", stopThresholdPct8521 = -15.0) { 0.0288 })
    }

    // ── 4. routes that land go first ──

    @Test fun a_route_failing_on_its_own_record_moves_behind_the_routes_that_land() {
        val d = SellRouteEndpoint8144.demoted8529(mapOf(
            Route8144.RAYDIUM to (0 to 3), Route8144.PUMPPORTAL to (1 to 3), Route8144.JUPITER to (39 to 43)))
        assertEquals(setOf(Route8144.RAYDIUM, Route8144.PUMPPORTAL), d)
        assertEquals(listOf(Route8144.JUPITER, Route8144.RAYDIUM, Route8144.PUMPPORTAL),
            SellRouteEndpoint8144.reorder8529(SellRouteEndpoint8144.routeOrder8144(Venue8144.RAYDIUM), d))
        // V5.0.8693 — a graduated pool already sells Jupiter first (PumpPortal only on the curve)
        assertEquals(listOf(Route8144.JUPITER, Route8144.RAYDIUM, Route8144.PUMPPORTAL),
            SellRouteEndpoint8144.reorder8529(SellRouteEndpoint8144.routeOrder8144(Venue8144.PUMPSWAP), d))
        // too few resolved sells is not a record; every route failing keeps the venue order
        assertTrue(SellRouteEndpoint8144.demoted8529(mapOf(Route8144.RAYDIUM to (0 to 2))).isEmpty())
        val all = setOf(Route8144.RAYDIUM, Route8144.PUMPPORTAL, Route8144.JUPITER)
        assertEquals(SellRouteEndpoint8144.routeOrder8144(Venue8144.RAYDIUM),
            SellRouteEndpoint8144.reorder8529(SellRouteEndpoint8144.routeOrder8144(Venue8144.RAYDIUM), all))
        assertEquals(Route8144.JUPITER, SellRouteEndpoint8144.routeOf8529("JUPITER_ULTRA"))
        assertEquals(Route8144.PUMPPORTAL, SellRouteEndpoint8144.routeOf8529("PUMPPORTAL"))
        assertNull(SellRouteEndpoint8144.routeOf8529("UNKNOWN"))
    }

    // ── 5. a runner is a real trade: the proof reads its real SOL ──

    @Test fun a_forty_thousand_percent_runner_counts_at_its_real_size() {
        val r = List(65) { -10.0 } + List(7) { 40_000.0 }
        val sol = r.map { it * 0.03 / 100.0 }
        val p = EvidenceHold8336.proof8336(r, sol)
        assertEquals((7 * 12.0) / (65 * 0.003), p.pf, 1e-6)
    }
}
