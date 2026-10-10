package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.CapitalDrawdown7948.Peak7948
import com.lifecyclebot.engine.truth.CapitalDrawdown7948.RotationInput7948
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.v3.sizing.SmartSizerV3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7948 — CapitalDrawdown: routable capacity truth, capital rotation, one drawdown basis. */
class Aate7948CapitalDrawdownTest {

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // 5.0.7947: one routable order (then $5) at this price is 0.04535 SOL (V5.0.7951: priced off the current USD floor).
    private val solUsd7947 = SmartSizerV3.LIVE_ROUTABLE_MIN_USD_7127 / 0.04535

    @Test fun capacity_zero_uses_single_position_rule_not_a_25pct_band() {
        val pf = SmartSizerV3.routableCapacityPreflight7224(0.0418, solUsd7947)
        assertEquals(0, pf.capacity)
        assertEquals(0.04535, pf.routableMinSol, 1e-6)
        assertEquals(1.0, pf.shareGuard, 1e-12)
        assertEquals(0.0418, pf.safeShareCapSol, 1e-12)   // was 0.01045 (25%)
        assertTrue(pf.wouldRefuse)
        assertEquals(0.04535, pf.minViableTradeableSol, 1e-6)
        // The real shortfall: one order + reserve - wallet.
        assertEquals(0.00355, CapitalDrawdown7948.shortfallSol7948(0.0538, 0.012, pf.routableMinSol), 1e-6)
        val reason = CapitalDrawdown7948.routableRefusalReason7948(pf.capacity, pf.tradeableSol, pf.routableMinSol)
        assertTrue(reason, reason.contains("below one routable order"))
        assertFalse(reason.contains("share guard"))
    }

    @Test fun adding_the_stated_shortfall_makes_the_wallet_routable() {
        val wallet = 0.0538 + 0.00355 + 1e-6
        val pf = SmartSizerV3.routableCapacityPreflight7224(wallet - 0.012, solUsd7947)
        assertEquals(1, pf.capacity)
        assertFalse(pf.wouldRefuse)
        assertEquals(0.0, CapitalDrawdown7948.shortfallSol7948(wallet, 0.012, pf.routableMinSol), 1e-12)
    }

    @Test fun sizer_and_preflight_share_the_capacity_zero_rule() {
        val s = src("v3/sizing/SmartSizerV3.kt")
        assertTrue(s.contains("} else SINGLE_ROUTABLE_POSITION_SHARE_7399 // V5.0.7948 capacity 0"))
        assertTrue(s.contains("V5.0.7948 — capacity 0 is \"tradeable below one routable order\""))
        assertTrue(s.contains("routableCapacity7218 >= 1 && shareGuard7218 > LIVE_FLOOR_MAX_WALLET_SHARE_7127"))
        val p = src("engine/truth/LivePreflight7222.kt")
        assertTrue(p.contains("CapitalDrawdown7948.routableRefusalReason7948(pf.capacity, pf.tradeableSol, pf.routableMinSol)"))
    }

    @Test fun cash_moved_into_a_position_is_not_drawdown() {
        val t0 = 1_000_000L
        val start = CapitalDrawdown7948.markedEquitySol7948(0.30, 0.0, 0.0)
        val peak = CapitalDrawdown7948.rollPeak7948(Peak7948(0.0, 0L), start, t0)
        // Buy 0.25: liquid falls to 0.05, the position is marked at cost.
        val afterBuy = CapitalDrawdown7948.markedEquitySol7948(0.05, 0.25, 0.0)
        assertEquals(0.0, CapitalDrawdown7948.drawdownPct7948(peak, afterBuy, t0 + 60_000L), 1e-9)
        // Liquid-only basis would have read 83%.
        assertEquals(83.3, CapitalDrawdown7948.drawdownPct7948(peak, 0.05, t0 + 60_000L), 0.1)
        // A real mark loss is drawdown.
        val marked = CapitalDrawdown7948.markedEquitySol7948(0.05, 0.25, -0.03)
        assertEquals(10.0, CapitalDrawdown7948.drawdownPct7948(peak, marked, t0 + 60_000L), 1e-6)
        // The open leg never goes negative.
        assertEquals(0.05, CapitalDrawdown7948.markedEquitySol7948(0.05, 0.25, -0.40), 1e-12)
        assertEquals(0.05, CapitalDrawdown7948.markedEquitySol7948(0.05, Double.NaN, Double.NaN), 1e-12)
    }

    @Test fun peak_is_rolling_24h_not_forever() {
        val day = 24L * 60L * 60_000L
        val peak = Peak7948(0.40, 1_000L)
        assertEquals(86.5, CapitalDrawdown7948.drawdownPct7948(peak, 0.054, 2_000L), 0.1)
        // After the window the old spike no longer counts.
        assertEquals(0.0, CapitalDrawdown7948.drawdownPct7948(peak, 0.054, 1_000L + day + 1L), 1e-12)
        val rolled = CapitalDrawdown7948.rollPeak7948(peak, 0.054, 1_000L + day + 1L)
        assertEquals(0.054, rolled.peakSol, 1e-12)
        // A new high re-anchors; a lower read inside the window keeps the peak.
        assertEquals(0.5, CapitalDrawdown7948.rollPeak7948(peak, 0.5, 5_000L).peakSol, 1e-12)
        assertEquals(peak, CapitalDrawdown7948.rollPeak7948(peak, 0.3, 5_000L))
        // Unusable equity never moves the peak and never reads as drawdown.
        assertEquals(peak, CapitalDrawdown7948.rollPeak7948(peak, Double.NaN, 5_000L))
        assertEquals(0.0, CapitalDrawdown7948.drawdownPct7948(peak, 0.0, 5_000L), 1e-12)
    }

    @Test fun killswitch_measures_marked_equity_on_the_shared_authority() {
        val ks = src("engine/KillSwitch.kt")
        assertFalse(ks.contains("LiveRiskPolicy7807.liveEquitySol(BotService.status.walletSol)"))
        assertEquals(4, Regex(Regex.escape("CapitalDrawdown7948.liveMarkedEquitySol7948(BotService.status.walletSol)")).findAll(ks).count())
        assertFalse(ks.contains("((peakBalance - currentBalance) / peakBalance) * 100"))
        assertTrue(ks.contains("putInt(\"environment_schema\", 7981)"))
        assertTrue(ks.contains("SIZE_DOWN_NOT_HALT_7864"))
        val lrp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertFalse(lrp.contains("private fun observeEquity("))
        assertTrue(lrp.contains("CapitalDrawdown7948.observePct7948("))
        val pa = src("engine/PerformanceAnalytics.kt")
        assertTrue(pa.contains("sb.append(drawdownLines7948(stats))"))
    }

    @Test fun drawdown_size_down_never_sizes_a_live_order_below_the_routable_minimum() {
        fun at(dd: Double) = LiveRiskPolicy7807.decide(LiveRiskPolicy7807.Inputs(
            lane = "MOONSHOT", equitySol = 0.2113, upstreamSol = 0.02274, execMinSol = 0.0414,
            planStopPct = 8.0, planFirstTargetPct = 30.0, planTargetPct = 40.0,
            solUsd = 200.0, liquidityUsd = 100_000.0, liveCloses = 0, liveMeanNetPct = 0.0,
            liveTotalNetSol = 0.0, liveWrPct = 0.0, drawdownFrac = dd, laneDailyLossSol = 0.0,
            governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
        ))
        for (dd in listOf(0.0, 0.25, 0.86, 1.0)) {
            val d = at(dd)
            assertTrue("dd=$dd ${d.reason}", d.open)
            assertEquals(0.0414, d.sizeSol, 1e-9)
        }
    }

    private fun starved(
        wallet: Double = 0.0538,
        value: Double = 0.03,
        pnl: Double = -6.0,
        peak: Double = 4.0,
        ageMin: Long = 120L,
        holdMin: Int = 60,
        sinceHighMin: Long = 30L,
        markAgeMs: Long = 10_000L,
        banked: Boolean = false,
        sinceRotationMs: Long = Long.MAX_VALUE,
    ) = RotationInput7948(
        walletSol = wallet, reserveSol = 0.012, routableMinSol = 0.04535,
        positionValueSol = value, pnlPct = pnl, peakGainPct = peak,
        ageMs = ageMin * 60_000L, laneMaxHoldMinutes = holdMin,
        msSinceNewHigh = if (sinceHighMin < 0L) -1L else sinceHighMin * 60_000L,
        markAgeMs = markAgeMs, banked = banked, msSinceLastRotation = sinceRotationMs,
    )

    @Test fun dead_money_rotates_for_a_proven_setup_when_capital_starved() {
        assertNull(CapitalDrawdown7948.rotationBlocker7948(starved()) { true })
        // A position that touched +3% and faded (exempt from both culls) still rotates.
        assertNull(CapitalDrawdown7948.rotationBlocker7948(starved(peak = 12.0, pnl = 0.5)) { true })
        assertEquals("NO_PROVEN_SETUP", CapitalDrawdown7948.rotationBlocker7948(starved()) { false })
    }

    @Test fun rotation_is_conservative() {
        var asked = false
        val probe: () -> Boolean = { asked = true; true }
        assertEquals("NOT_CAPITAL_STARVED", CapitalDrawdown7948.rotationBlocker7948(starved(wallet = 0.06), probe))
        assertEquals("GREEN", CapitalDrawdown7948.rotationBlocker7948(starved(pnl = 1.5), probe))
        assertEquals("RUNNER_LOCK_ARMED", CapitalDrawdown7948.rotationBlocker7948(starved(peak = 25.0, pnl = -2.0), probe))
        assertEquals("BANKED", CapitalDrawdown7948.rotationBlocker7948(starved(banked = true), probe))
        assertEquals("WOULD_NOT_FUND_ONE_ORDER", CapitalDrawdown7948.rotationBlocker7948(starved(value = 0.002), probe))
        assertEquals("WITHIN_USEFUL_HOLD", CapitalDrawdown7948.rotationBlocker7948(starved(ageMin = 50L), probe))
        assertEquals("STILL_MAKING_HIGHS", CapitalDrawdown7948.rotationBlocker7948(starved(sinceHighMin = 5L), probe))
        assertEquals("STILL_MAKING_HIGHS", CapitalDrawdown7948.rotationBlocker7948(starved(sinceHighMin = -1L), probe))
        assertEquals("STALE_MARK", CapitalDrawdown7948.rotationBlocker7948(starved(markAgeMs = 300_000L), probe))
        assertEquals("STALE_MARK", CapitalDrawdown7948.rotationBlocker7948(starved(markAgeMs = -1L), probe))
        assertEquals("ROTATION_COOLDOWN", CapitalDrawdown7948.rotationBlocker7948(starved(sinceRotationMs = 60_000L), probe))
        assertFalse("evidence is read only when every cheaper condition holds", asked)
    }

    @Test fun useful_hold_is_the_lane_hold_bounded_45_to_90_minutes() {
        assertEquals(45L * 60_000L, CapitalDrawdown7948.usefulHoldMs7948(12))
        assertEquals(60L * 60_000L, CapitalDrawdown7948.usefulHoldMs7948(60))
        assertEquals(90L * 60_000L, CapitalDrawdown7948.usefulHoldMs7948(240))
        assertEquals(90L * 60_000L, CapitalDrawdown7948.usefulHoldMs7948(LiveRiskPolicy7807.budgetFor("BLUECHIP").maxHoldMinutes))
    }

    @Test fun executor_calls_rotation_without_growing_runManageOnly() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("if (capitalRotation7948(ts, currentPrice, posAgeMs, wallet, walletSol) || checkProfitLock(ts, wallet, walletSol)) return"))
        assertTrue(ex.contains("requestSell(ts = ts, reason = \"CAPITAL_ROTATION_7948\", wallet = wallet, walletSol = walletSol)"))
    }
}
