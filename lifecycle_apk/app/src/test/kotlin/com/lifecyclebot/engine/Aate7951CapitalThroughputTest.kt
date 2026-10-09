package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalThroughput7951
import com.lifecyclebot.engine.truth.CapitalThroughput7951.Demand7951
import com.lifecyclebot.engine.truth.CapitalThroughput7951.RotationOffer7951
import com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255
import com.lifecyclebot.v3.sizing.SmartSizerV3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7951 — CapitalThroughput: demand-driven rotation, slots under scarcity, reserve and minimum audit. */
class Aate7951CapitalThroughputTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    /** 5.0.7949 live diag: SOL $110.32, equity 0.2219, liquid 0.0400, four open live positions. */
    private val solUsd7949 = 110.32

    // ── 3. reserve and minimum ─────────────────────────────────────────────

    @Test fun fixed_costs_come_from_the_codes_own_tip_and_fee_values() {
        assertEquals(0.0002, CapitalThroughput7951.SENDER_TIP_FLOOR_SOL_7951, 1e-12)
        assertEquals(2 * CapitalThroughput7951.SENDER_TIP_FLOOR_SOL_7951, CapitalThroughput7951.URGENT_TIP_SOL_7951, 1e-12)
        assertEquals(0.00022, CapitalThroughput7951.buyLegSol7951(), 1e-12)
        assertEquals(0.00042, CapitalThroughput7951.sellLegSol7951(), 1e-12)
        assertEquals(0.00064, CapitalThroughput7951.fixedRoundTripSol7951(), 1e-12)
        assertEquals(0.0256, CapitalThroughput7951.costFloorOrderSol7951(), 1e-9)
        // The Executor's tip floor and PumpFunDirectApi's Sender minimum are the numbers used here.
        assertTrue(src("engine/Executor.kt").contains("val floored = maxOf(dynamic, c.jitoTipLamports, 200_000L)"))
        assertTrue(src("network/PumpFunDirectApi.kt").contains("val SENDER_MIN_TIP_SOL = 0.0002"))
    }

    @Test fun reserve_covers_one_buy_the_worst_sell_and_a_retry_per_position() {
        val ceiling = LiveSpendReserveAuthority7255.RESERVE_CEILING_SOL_7951
        val flat = CapitalThroughput7951.reserveSol7951(0, ceiling)
        val expectedFlat = CapitalThroughput7951.WALLET_RENT_EXEMPT_SOL_7951 +
            CapitalThroughput7951.buyLegSol7951() + CapitalThroughput7951.ATA_RENT_SOL_7951 +
            CapitalThroughput7951.DRAIN_TIP_SOL_7951 + CapitalThroughput7951.CU_AND_BASE_FEE_SOL_7951 + CapitalThroughput7951.ATA_RENT_SOL_7951
        assertEquals(expectedFlat, flat, 1e-12)
        assertEquals(0.00601, flat, 1e-9)
        assertTrue(flat >= CapitalThroughput7951.RESERVE_FLOOR_SOL_7951)
        // 5.0.7949: four open live positions.
        val four = CapitalThroughput7951.reserveSol7951(4, ceiling)
        assertEquals(0.00601 + 4 * CapitalThroughput7951.PER_OPEN_SELL_ALLOWANCE_SOL_7951, four, 1e-9)
        assertEquals(0.00721, four, 1e-9)
        // Never above the old fixed reserve, never below the floor.
        assertEquals(0.012, CapitalThroughput7951.reserveSol7951(40, ceiling), 1e-12)
        assertEquals(CapitalThroughput7951.RESERVE_FLOOR_SOL_7951, CapitalThroughput7951.reserveSol7951(0, 0.001), 1e-12)
        // The worst sell alone (drain tip + temp wSOL account) always fits with the wallet rent-exempt.
        assertTrue(four - CapitalThroughput7951.WALLET_RENT_EXEMPT_SOL_7951 >
            CapitalThroughput7951.DRAIN_TIP_SOL_7951 + CapitalThroughput7951.CU_AND_BASE_FEE_SOL_7951 + CapitalThroughput7951.ATA_RENT_SOL_7951)
        // The live authority stays inside the band whatever it reads.
        assertTrue(LiveSpendReserveAuthority7255.RESERVE_SOL in CapitalThroughput7951.RESERVE_FLOOR_SOL_7951..ceiling)
    }

    @Test fun three_dollar_minimum_keeps_fixed_cost_under_two_and_a_half_percent() {
        assertEquals(3.0, SmartSizerV3.LIVE_ROUTABLE_MIN_USD_7127, 1e-12)
        val pf = SmartSizerV3.routableCapacityPreflight7224(0.0, solUsd7949)
        assertEquals(3.0 / solUsd7949, pf.routableMinSol, 1e-9)       // 0.02719
        assertTrue(CapitalThroughput7951.fixedCostSharePct7951(pf.routableMinSol) <= 100.0 * CapitalThroughput7951.MAX_FIXED_COST_SHARE_7951)
        // At a high SOL price $3 would be dust; the price-independent cost floor holds the line.
        val rich = SmartSizerV3.routableCapacityPreflight7224(0.0, 300.0)
        assertEquals(CapitalThroughput7951.costFloorOrderSol7951(), rich.routableMinSol, 1e-12)
        assertTrue(rich.routableMinSol >= SmartSizerV3.LIVE_FLOOR_ABSOLUTE_MIN_SOL_7127)
        // Unknown price: the cost floor, not the 0.05 ceiling.
        assertEquals(CapitalThroughput7951.costFloorOrderSol7951(), SmartSizerV3.routableCapacityPreflight7224(0.0, 0.0).routableMinSol, 1e-12)
    }

    @Test fun the_7949_wallet_can_route_one_order_now() {
        val reserve = CapitalThroughput7951.reserveSol7951(4, LiveSpendReserveAuthority7255.RESERVE_CEILING_SOL_7951)
        val pf = SmartSizerV3.routableCapacityPreflight7224(0.0400 - reserve, solUsd7949)
        assertEquals(1, pf.capacity)
        assertFalse(pf.wouldRefuse)
        // Slots at equity 0.2219: seven routable orders (was four at $5 / 0.012).
        assertEquals(7, CapitalThroughput7951.routableCapacity7951(0.2219, reserve, pf.routableMinSol))
        assertEquals(1, CapitalThroughput7951.routableCapacity7951(0.0400, reserve, pf.routableMinSol))
        assertEquals(4, CapitalThroughput7951.routableCapacity7951(0.2219, 0.012, 5.0 / solUsd7949))
        assertEquals(0, CapitalThroughput7951.routableCapacity7951(0.0400, 0.012, 5.0 / solUsd7949))
    }

    @Test fun sizer_and_preflight_share_one_routable_minimum() {
        val s = src("v3/sizing/SmartSizerV3.kt")
        assertTrue(s.contains("private fun routableMinSol7951(rawSol: Double): Double {"))
        assertTrue(s.contains("val routableMin = routableMinSol7951(rawSol)"))
        assertTrue(s.contains("routableMinSol7951(routableRawSol7127) // V5.0.7951"))
        assertTrue(s.contains("routableMinSol7951(Double.NaN) // V5.0.7951"))
        assertFalse(s.contains("routableRawSol7127.coerceIn(LIVE_FLOOR_ABSOLUTE_MIN_SOL_7127, LIVE_FLOOR_CEILING_SOL_7127)"))
    }

    // ── 1. demand and rotation choice ──────────────────────────────────────

    @Test fun demand_is_fresh_for_two_minutes_and_filters_by_lane() {
        val now = 50_000_000L
        val w = CapitalThroughput7951.DEMAND_WINDOW_MS_7951
        val entries = listOf(
            Demand7951("MOONSHOT", "m1", "SIZE_CAPITAL_REFUSED", now - 10_000L),
            Demand7951("CASHGEN", "m2", "CHART_BUY_CAPITAL_REFUSED", now - 30_000L),
            Demand7951("CRYPTO_ALT", "", "CRYPTO_WALLET_BELOW_ROUTABLE", now - w - 1L),
        )
        assertEquals(2, CapitalThroughput7951.freshDemand7951(entries, now).size)
        assertEquals(listOf("MOONSHOT"), CapitalThroughput7951.freshDemand7951(entries, now, fastOnly = true).map { it.lane })
        assertEquals(listOf("CASHGEN"), CapitalThroughput7951.freshDemand7951(entries, now, excludeLane = "MOONSHOT").map { it.lane })
        assertTrue(CapitalThroughput7951.freshDemand7951(entries, now, excludeLane = "MOONSHOT", fastOnly = true).isEmpty())
        assertTrue("SLOW" , "CASHGEN" in CapitalThroughput7951.SLOW_LANES_7951 && "CRYPTO_ALT" in CapitalThroughput7951.FAST_LANES_7951)
    }

    @Test fun live_demand_registry_wakes_rotation() {
        val t = 7_000_000_000L
        assertFalse(CapitalThroughput7951.demandWaiting7951(t, fastOnly = true))
        CapitalThroughput7951.noteCapitalDemand7951("CRYPTO_ALT", "", "CRYPTO_WALLET_BELOW_ROUTABLE", t)
        assertTrue(CapitalThroughput7951.demandWaiting7951(t + 60_000L, fastOnly = true))
        assertFalse(CapitalThroughput7951.demandWaiting7951(t + 60_000L, excludeLane = "CRYPTO_ALT"))
        assertFalse(CapitalThroughput7951.demandWaiting7951(t + CapitalThroughput7951.DEMAND_WINDOW_MS_7951 + 1L))
    }

    @Test fun deadness_is_red_pnl_times_minutes_without_a_high() {
        assertEquals(8.0 * 30.0, CapitalThroughput7951.deadness7951(-6.0, 30L * 60_000L), 1e-9)
        assertEquals(1.5 * 60.0, CapitalThroughput7951.deadness7951(0.5, 60L * 60_000L), 1e-9)
        assertEquals(0.0, CapitalThroughput7951.deadness7951(-6.0, -1L), 1e-12)
        assertTrue(CapitalThroughput7951.deadness7951(-6.0, 30L * 60_000L) > CapitalThroughput7951.deadness7951(0.5, 60L * 60_000L))
    }

    @Test fun deadest_offer_wins_and_a_chart_exit_wins_first() {
        val now = 1_000_000L
        val a = RotationOffer7951("A", false, 90.0, now - 1_000L)
        val b = RotationOffer7951("B", false, 240.0, now - 2_000L)
        val c = RotationOffer7951("C", true, 5.0, now - 3_000L)
        val stale = RotationOffer7951("S", false, 9_999.0, now - CapitalThroughput7951.OFFER_FRESH_MS_7951 - 1L)
        assertEquals("B", CapitalThroughput7951.deadestKey7951(listOf(a, b, stale), now))
        assertEquals("C", CapitalThroughput7951.deadestKey7951(listOf(a, b, c, stale), now))
        assertNull(CapitalThroughput7951.deadestKey7951(listOf(stale), now))
    }

    @Test fun offers_settle_before_one_position_is_picked() {
        val t0 = 9_000_000_000L
        val s = CapitalThroughput7951.OFFER_SETTLE_MS_7951
        fun offer(k: String, d: Double, at: Long) = CapitalThroughput7951.offerRotation7951(RotationOffer7951(k, false, d, at), at)
        assertFalse(offer("A", 90.0, t0))                // first offer: others get a tick
        assertFalse(offer("B", 240.0, t0 + 3_000L))       // still settling
        assertFalse(offer("A", 90.0, t0 + s + 1_000L))    // B is deader
        assertTrue(offer("B", 240.0, t0 + s + 2_000L))    // the deadest goes
        CapitalThroughput7951.withdrawRotationOffer7951("B")
        assertTrue(offer("A", 90.0, t0 + s + 3_000L))
        CapitalThroughput7951.withdrawRotationOffer7951("A")
        // Long after, a lone fresh offer settles again before it fires.
        assertFalse(offer("Z", 50.0, t0 + 200_000L))
        CapitalThroughput7951.withdrawRotationOffer7951("Z")
    }

    @Test fun executor_rotation_fires_on_demand_and_picks_the_deadest() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("rotationBlocker7948(input) { capitalDemandWaiting7951() }"))
        assertFalse(ex.contains("CapitalDrawdown7948.anyLiveLaneProven7948()"))
        assertTrue(ex.contains("if (!deadestRotationOffer7951(ts.mint, offerKey7951, verdict.pnlPct, input.msSinceNewHigh, now)) return false"))
        assertTrue(ex.contains("ChartReader7950.exitSignal(com.lifecyclebot.engine.chart.ChartReader7950.read(mint, nowMs)) != null"))
        // The 7948 guards are unchanged: green, banked, runner lock, hold, highs, stale mark, cooldown.
        val cd = src("engine/truth/CapitalDrawdown7948.kt")
        assertTrue(cd.contains("i.peakGainPct >= ROTATE_PEAK_EXEMPT_PCT_7948 -> \"RUNNER_LOCK_ARMED\""))
        assertTrue(cd.contains("!i.pnlPct.isFinite() || i.pnlPct > ROTATE_MAX_PNL_PCT_7948 -> \"GREEN\""))
        val r = src("engine/truth/OrderSizeResolver6441.kt")
        assertTrue(r.contains("if (!paperMode) noteCapitalDemand7951(laneName, mint)"))
        assertTrue(r.contains("ChartReader7950.saysBuy(mint)"))
        val alt = src("perps/CryptoAltTrader.kt")
        assertTrue(alt.contains("if (floor <= 0.0) noteCryptoCapitalDemand7951()"))
    }

    // ── 2. slots under scarcity ────────────────────────────────────────────

    @Test fun a_slow_lane_holds_one_slot_while_a_fast_lane_waits() {
        // 5.0.7949: equity capacity 7, liquid capacity 1 -> scarce.
        assertTrue(CapitalThroughput7951.scarce7951(7, 1))
        assertTrue(CapitalThroughput7951.scarce7951(CapitalThroughput7951.SCARCE_EQUITY_CAPACITY_7951, 3))
        assertFalse(CapitalThroughput7951.scarce7951(8, 2))
        fun r(lane: String, open: Int, util: Double, fast: Boolean, other: Boolean, eq: Int = 7, liq: Int = 1) =
            CapitalThroughput7951.scarceSlotRefusal7951(lane, open, util, eq, liq, fast, other)
        assertEquals("SLOW_LANE_ONE_SLOT_WHILE_FAST_DEMAND_7951", r("CASHGEN", 2, 1.63, fast = true, other = true))
        assertEquals("SLOW_LANE_ONE_SLOT_WHILE_FAST_DEMAND_7951", r("BLUE_CHIP", 1, 0.83, fast = true, other = true))
        assertNull(r("QUALITY", 0, 0.0, fast = true, other = true))       // first slot is always available
        assertNull(r("MOONSHOT", 1, 0.5, fast = true, other = true))      // fast lanes are not capped to one
        assertEquals("LANE_OVER_ALLOCATION_SCARCE_7951", r("CASHGEN", 2, 1.63, fast = false, other = true))
        assertEquals("LANE_OVER_ALLOCATION_SCARCE_7951", r("MOONSHOT", 2, 1.2, fast = true, other = true))
        assertNull(r("CASHGEN", 2, 1.63, fast = false, other = false))    // alone with the wallet: never refused
        assertNull(r("CASHGEN", 2, 1.63, fast = true, other = true, eq = 12, liq = 4)) // not scarce
    }

    @Test fun throughput_authority_applies_the_scarce_slot_rule_before_the_fairness_bypass() {
        val s = src("engine/truth/ExitThroughputAuthority6727.kt")
        val i = s.indexOf("scarceSlotRefusal7951(m, lane, laneHeadroom6732)?.let { return Verdict(false, it, openCount, cash, equity, cashRatio) }")
        assertTrue(i > 0)
        assertTrue(i < s.indexOf("if (laneHeadroom6732?.hasHeadroom == true) {"))
        assertTrue(s.contains("CapitalThroughput7951.demandWaiting7951(now, excludeLane = nl, fastOnly = true)"))
    }

    @Test fun last_slot_is_only_held_for_a_proven_lane_that_exists() {
        val s = src("engine/truth/LiveSlotPriority7304.kt")
        assertTrue(s.contains("val defer = shouldDefer(freeSlots, proven, lastSlotSinceMs, nowMs) && anyProvenLane7951()"))
    }

    // ── 4. crypto ──────────────────────────────────────────────────────────

    @Test fun exposure_caps_are_shares_of_the_wallet_not_of_liquid_sol() {
        // 5.0.7949: liquid 0.0400, nothing recorded this session, canonical live equity 0.2219.
        val basis = WalletPositionLock.exposureBasis7951(0.0400, 0.0, 0.2219)
        assertEquals(0.2219, basis, 1e-12)
        // A $3 crypto order fits the 40% CryptoAlt share of the wallet (0.0888), not of liquid (0.016).
        assertTrue(0.0272 <= basis * 0.40)
        assertFalse(0.0272 <= 0.0400 * 0.40)
        assertEquals(0.22, WalletPositionLock.exposureBasis7951(0.04, 0.18, 0.0), 1e-12)
        assertEquals(0.04, WalletPositionLock.exposureBasis7951(0.04, Double.NaN, Double.NaN), 1e-12)
        assertTrue(src("engine/WalletPositionLock.kt").contains("val basis7951 = exposureBasis7951(walletSol, totalDeployed, liveEquity7951(walletSol))"))
    }
}
