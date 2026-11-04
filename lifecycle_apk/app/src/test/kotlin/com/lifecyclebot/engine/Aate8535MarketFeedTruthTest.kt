package com.lifecyclebot.engine

import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketData
import com.lifecyclebot.perps.strategy.CommoditiesStrategy
import com.lifecyclebot.perps.strategy.CryptoAltStrategy
import com.lifecyclebot.perps.strategy.CryptoAltStrategy.BtcRegime
import com.lifecyclebot.perps.strategy.CryptoAltStrategy.Mode
import com.lifecyclebot.perps.strategy.CryptoAltStrategy.VolRegime
import com.lifecyclebot.perps.strategy.MetalsStrategy
import com.lifecyclebot.perps.strategy.PerpsSetupGradeBook8535
import com.lifecyclebot.perps.strategy.PerpsStrategy
import com.lifecyclebot.perps.strategy.SpecialistPlanExit8535
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8535 — audit C12 (displacement scored as volatility, BTC.D hard-coded 0.0, EXTREME sign inversion), C13 (perps
 * strategy fed placeholders, never bound to the fill), C15 (metals / commodities specialist inputs and exit contracts).
 */
class Aate8535MarketFeedTruthTest {

    // ── C12 ──

    @Test fun the_same_24h_change_with_different_realised_volatility_lands_in_different_regimes() {
        val low = CryptoAltStrategy.decide("X", 4.0, Mode.SPOT_LONG_ONLY, volatility24h = 2.0)!!
        assertEquals(VolRegime.LOW, low.volRegime)
        assertEquals(PerpsDirection.LONG, low.direction)
        val unknown = CryptoAltStrategy.decide("X", 4.0, Mode.SPOT_LONG_ONLY, volatility24h = null)!!
        assertEquals(VolRegime.UNKNOWN, unknown.volRegime)
        assertEquals("an unknown range earns no low-vol trend bonus", low.conviction - 8, unknown.conviction)
        assertEquals(5.0, unknown.tpPct, 0.0)
        assertTrue(unknown.reasons.any { it.contains("vol unobserved") })
        assertNull("EXTREME with nothing verified is a stand-down, not a flipped trade", CryptoAltStrategy.decide("X", 4.0, Mode.SPOT_LONG_ONLY, volatility24h = 16.0))
        assertEquals(VolRegime.EXTREME, CryptoAltStrategy.classifyVolRegime(16.0))
        assertEquals(VolRegime.UNKNOWN, CryptoAltStrategy.classifyVolRegime(Double.NaN))
    }

    @Test fun btc_dominance_is_a_submodel_that_is_off_when_unobserved_and_live_when_read() {
        val off = CryptoAltStrategy.decide("X", 4.0, Mode.SPOT_LONG_ONLY, volatility24h = 5.0, btcDominanceChange7d = null)!!
        assertEquals(BtcRegime.UNKNOWN, off.btcRegime)
        assertTrue(off.reasons.any { it.contains("BTC.D unobserved") })
        val alt = CryptoAltStrategy.decide("X", 4.0, Mode.SPOT_LONG_ONLY, volatility24h = 5.0, btcDominanceChange7d = -2.0)!!
        assertEquals(BtcRegime.ALT_SEASON, alt.btcRegime)
        assertEquals(off.conviction + 15, alt.conviction)
        val btc = CryptoAltStrategy.decide("X", 4.0, Mode.SPOT_LONG_ONLY, volatility24h = 5.0, btcDominanceChange7d = 2.0)!!
        assertEquals(BtcRegime.BTC_SEASON, btc.btcRegime)
        assertEquals(BtcRegime.UNKNOWN, CryptoAltStrategy.classifyBtcRegime(null))
    }

    @Test fun extreme_vol_trades_only_a_verified_reclaim_and_never_a_trending_panic() {
        val panic = CryptoAltStrategy.decide("X", -18.0, Mode.PERPS_BIDIRECTIONAL, volatility24h = 20.0, rsi = 20.0, reversalConfirmed = false)
        assertNull("still falling: no entry", panic)
        assertNull("no observation of a reversal: no entry", CryptoAltStrategy.decide("X", -18.0, Mode.PERPS_BIDIRECTIONAL, volatility24h = 20.0, rsi = 20.0, reversalConfirmed = null))
        assertNull("oversold without the MACD cross: no entry", CryptoAltStrategy.decide("X", -18.0, Mode.PERPS_BIDIRECTIONAL, volatility24h = 20.0, rsi = 20.0))
        assertNull("too little displacement left to revert into the 3 % target", CryptoAltStrategy.decide("X", -4.0, Mode.PERPS_BIDIRECTIONAL, volatility24h = 20.0, rsi = 20.0, reversalConfirmed = true))
        val reclaim = CryptoAltStrategy.decide("X", -18.0, Mode.SPOT_LONG_ONLY, volatility24h = 20.0, rsi = 22.0, reversalConfirmed = true)!!
        assertEquals(PerpsDirection.LONG, reclaim.direction)
        assertEquals(VolRegime.EXTREME, reclaim.volRegime)
        assertEquals(3.0, reclaim.tpPct, 0.0); assertEquals(2.0, reclaim.slPct, 0.0)
        assertTrue(reclaim.reasons.any { it.contains("verified reversal") })
        // a verified blow-off roll-over is a SHORT: impossible on spot, taken on perps
        assertNull(CryptoAltStrategy.decide("X", 18.0, Mode.SPOT_LONG_ONLY, volatility24h = 20.0, rsi = 80.0, reversalConfirmed = true))
        assertEquals(PerpsDirection.SHORT, CryptoAltStrategy.decide("X", 18.0, Mode.PERPS_BIDIRECTIONAL, volatility24h = 20.0, rsi = 80.0, reversalConfirmed = true)!!.direction)
        // unknown volatility on a dump never becomes a safe spot long
        assertNull(CryptoAltStrategy.decide("X", -18.0, Mode.SPOT_LONG_ONLY, volatility24h = null))
        assertTrue(CryptoAltStrategy.EXTREME_MIN_DISPLACEMENT_PCT >= 6.0)
    }

    // ── C13 ──

    @Test fun perps_strategy_returns_a_setup_only_on_an_observed_native_feed_and_names_it() {
        assertNull("displacement alone is not a perps edge", PerpsStrategy.decide("SOL", 9.0))
        val funding = PerpsStrategy.decide("SOL", 0.0, fundingRate8h = 0.0012)!!
        assertEquals(PerpsDirection.SHORT, funding.direction)
        assertEquals("PERPS_FUNDING_SHORT", funding.setup)
        assertTrue(funding.feeds.contains("funding=✓") && funding.feeds.contains("OI=✗") && funding.feeds.contains("vol=✗"))
        val fundingOnRally = PerpsStrategy.decide("SOL", 3.0, fundingRate8h = 0.0012)!!
        assertEquals("the funding edge decided against the 24h drift", "PERPS_FUNDING_SHORT", fundingOnRally.setup)
        val rsiOnly = PerpsStrategy.decide("SOL", 0.0, rsi = 20.0)!!
        assertEquals("PERPS_RSI_LONG", rsiOnly.setup)
    }

    @Test fun an_observed_oi_change_changes_the_result_and_unknown_volatility_is_not_calm() {
        assertNull(PerpsStrategy.decide("SOL", 6.0, openInterestChange24h = null))
        val oiRally = PerpsStrategy.decide("SOL", 6.0, openInterestChange24h = 20.0)!!
        assertTrue(oiRally.reasons.any { it.contains("longs crowded") })
        assertEquals(65, oiRally.conviction)
        val oiDump = PerpsStrategy.decide("SOL", -6.0, openInterestChange24h = 20.0)!!
        assertTrue(oiDump.reasons.any { it.contains("shorts crowded") })
        assertNotEquals(oiRally.direction, oiDump.direction)
        val unknownVol = PerpsStrategy.decide("SOL", 0.0, fundingRate8h = -0.0012, volatility24h = null)!!
        val calm = PerpsStrategy.decide("SOL", 0.0, fundingRate8h = -0.0012, volatility24h = 3.0)!!
        val wild = PerpsStrategy.decide("SOL", 0.0, fundingRate8h = -0.0012, volatility24h = 14.0)!!
        assertTrue("unknown range is damped below a calm one", unknownVol.leverage < calm.leverage)
        assertTrue("and a wild one is damped further", wild.leverage < unknownVol.leverage)
        assertNotEquals(unknownVol.tpPct, wild.tpPct, 0.0)
    }

    @Test fun the_market_row_carries_feed_provenance_and_placeholders_read_as_unobserved() {
        val row = PerpsMarketData(
            market = PerpsMarket.SOL, price = 100.0, indexPrice = 100.0, markPrice = 100.0, fundingRate = 0.0, fundingRateAnnualized = 0.0,
            nextFundingTime = 0L, openInterestLong = 0.0, openInterestShort = 0.0, volume24h = 0.0, high24h = 112.0, low24h = 100.0, priceChange24hPct = 12.0,
        )
        assertNull("synthesised high/low are not a range", row.realisedRangePct8535())
        assertNull("0.0 funding is a placeholder, not flat", row.fundingRate8535())
        assertNull("0/0 open interest is not a 1.0 ratio", row.longShortRatio8535())
        assertEquals(1.0, row.getLongShortRatio(), 0.0)   // the legacy accessor still answers; strategies no longer read it
        val real = row.copy(rangeObserved8535 = true, fundingObserved8535 = true, oiObserved8535 = true, openInterestLong = 3.0, openInterestShort = 1.0, fundingRate = 0.0007)
        assertEquals(12.0, real.realisedRangePct8535()!!, 1e-9)
        assertEquals(0.0007, real.fundingRate8535()!!, 0.0)
        assertEquals(3.0, real.longShortRatio8535()!!, 0.0)
    }

    @Test fun each_executed_setup_is_graded_on_its_own_closes_and_shapes_size_within_bounds() {
        PerpsSetupGradeBook8535.load("")
        assertEquals(1.0, PerpsSetupGradeBook8535.sizeMultiplier("PERPS_FUNDING_SHORT"), 0.0)
        repeat(4) { PerpsSetupGradeBook8535.record("PERPS_FUNDING_SHORT", 3.0) }
        assertEquals("neutral until five closes", 1.0, PerpsSetupGradeBook8535.sizeMultiplier("PERPS_FUNDING_SHORT"), 0.0)
        PerpsSetupGradeBook8535.record("PERPS_FUNDING_SHORT", 3.0)
        assertEquals(1.15, PerpsSetupGradeBook8535.sizeMultiplier("PERPS_FUNDING_SHORT"), 1e-9)
        repeat(6) { PerpsSetupGradeBook8535.record("PERPS_RSI_LONG", -2.0) }
        assertEquals(0.75, PerpsSetupGradeBook8535.sizeMultiplier("PERPS_RSI_LONG"), 1e-9)
        PerpsSetupGradeBook8535.record("", 9.0)   // a legacy signal without a setup grades nothing
        val blob = PerpsSetupGradeBook8535.serialize()
        PerpsSetupGradeBook8535.load("")
        assertEquals(1.0, PerpsSetupGradeBook8535.sizeMultiplier("PERPS_FUNDING_SHORT"), 0.0)
        PerpsSetupGradeBook8535.load(blob)
        assertEquals(5, PerpsSetupGradeBook8535.snapshot("PERPS_FUNDING_SHORT")!!.n)
        assertTrue(PerpsSetupGradeBook8535.summary().contains("PERPS_FUNDING_SHORT"))
    }

    // ── C15 ──

    @Test fun metals_and_commodities_name_their_unobserved_inputs_and_react_when_they_are_read() {
        val gold = MetalsStrategy.decide("XAU", 1.5)!!
        assertTrue(gold.reasons.any { it.contains("DXY unobserved") } && gold.reasons.any { it.contains("VIX unobserved") })
        val goldFear = MetalsStrategy.decide("XAU", 1.5, vixLevel = 35.0)!!
        assertEquals(gold.conviction + 15, goldFear.conviction)
        val goldCalm = MetalsStrategy.decide("XAU", 1.5, vixLevel = 18.0)!!
        assertEquals(gold.conviction, goldCalm.conviction)
        assertNotNull(MetalsStrategy.decide("XAU", 1.5, dxyChangePct = -0.5))
        val wti = CommoditiesStrategy.decide("WTI", 2.0, monthOneIndexed = 2)!!
        assertTrue(wti.reasons.any { it.contains("DXY unobserved") })
        assertEquals(PerpsDirection.LONG, wti.direction)
        assertNull("a USD move against the trend cancels it", CommoditiesStrategy.decide("WTI", 2.0, dxyChangePct = 2.0, monthOneIndexed = 2))
    }

    @Test fun the_specialist_plan_owns_the_exit_inside_the_swap_cost_floor() {
        assertEquals(2.4, SpecialistPlanExit8535.tpPct(2.0, 1.2), 1e-9)
        assertEquals(1.2, SpecialistPlanExit8535.slPct(1.5, 0.8), 1e-9)
        assertTrue("a conviction-tightened gold stop cannot sit inside the round trip", SpecialistPlanExit8535.slPct(0.48, 1.0) >= SpecialistPlanExit8535.SWAP_ROUND_TRIP_PCT)
        assertTrue(SpecialistPlanExit8535.tpPct(0.5, 1.0) > SpecialistPlanExit8535.SWAP_ROUND_TRIP_PCT)
        assertEquals(2.0, SpecialistPlanExit8535.tpPct(2.0, Double.NaN), 1e-9)
    }
}
