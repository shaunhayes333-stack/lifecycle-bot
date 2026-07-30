package com.lifecyclebot.engine.truth

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6390 — EARLY-ENTRY DETECTION + PEAK CAPTURE.
 *
 * Every substrate module from EarlyEntryAndPeakCapture6390 is exercised.
 * CI blocks the ship if any invariant fails.
 */
class Bundle6390EarlyEntryAndPeakCaptureTest {

    @Before fun setUp() {
        PeakAdaptiveTrail6390.clearForTest()
        WinnerLadderExit6390.clearForTest()
    }
    @After fun tearDown() { setUp() }

    private fun goodSignals() = EarlyEntryScout6390.Signals(
        mintAgeMinutes = 8, liquidityUsd = 15_000.0, marketCapUsd = 46_500.0,
        distinctBuyerWalletsLast60s = 25, netBuyVolumeUsdLast60s = 8_000.0,
        netSellVolumeUsdLast60s = 1_500.0, topHolderConcentrationPct = 30.0,
        topHolderConcentrationDeltaPct = -3.5, smartMoneyBuysLast60s = 3,
        bondingCurveBuyTxPerMinRising = true, hasRealPoolAddress = true,
        mintAuthorityRevoked = true, freezeAuthorityRevoked = true,
    )

    /* -------------------- PART A EARLY ENTRY ------------------------------ */

    @Test fun cheems_like_setup_is_high_conviction_early() {
        val v = EarlyEntryScout6390.evaluate(goodSignals())
        assertEquals(EarlyEntryScout6390.Tier.HIGH_CONVICTION_EARLY, v.tier)
        assertTrue(v.score >= 70)
        assertTrue(v.reasons.any { it.contains("SMART_MONEY_CLUSTER") })
        assertTrue(v.reasons.any { it.contains("MICROCAP") })
    }

    @Test fun mint_authority_alive_rejects_regardless_of_score() {
        val s = goodSignals().copy(mintAuthorityRevoked = false)
        val v = EarlyEntryScout6390.evaluate(s)
        assertEquals(EarlyEntryScout6390.Tier.NOT_QUALIFIED, v.tier)
        assertTrue(v.reasons.contains("MINT_AUTHORITY_LIVE"))
    }

    @Test fun freeze_authority_alive_rejects_regardless_of_score() {
        val s = goodSignals().copy(freezeAuthorityRevoked = false)
        val v = EarlyEntryScout6390.evaluate(s)
        assertEquals(EarlyEntryScout6390.Tier.NOT_QUALIFIED, v.tier)
    }

    @Test fun lp_below_3k_rejects() {
        val s = goodSignals().copy(liquidityUsd = 2_500.0)
        val v = EarlyEntryScout6390.evaluate(s)
        assertEquals(EarlyEntryScout6390.Tier.NOT_QUALIFIED, v.tier)
    }

    @Test fun mature_mint_scores_lower_than_fresh() {
        val fresh = EarlyEntryScout6390.evaluate(goodSignals())
        val mature = EarlyEntryScout6390.evaluate(goodSignals().copy(
            mintAgeMinutes = 300, marketCapUsd = 2_000_000.0))
        assertTrue(fresh.score > mature.score)
    }

    @Test fun size_multiplier_scales_with_tier() {
        assertEquals(1.50, EarlyEntryScout6390.sizeMultiplier(
            EarlyEntryScout6390.Tier.HIGH_CONVICTION_EARLY), 1e-9)
        assertEquals(1.10, EarlyEntryScout6390.sizeMultiplier(
            EarlyEntryScout6390.Tier.EARLY_INTEREST), 1e-9)
        assertEquals(1.00, EarlyEntryScout6390.sizeMultiplier(
            EarlyEntryScout6390.Tier.NOT_QUALIFIED), 1e-9)
    }

    /* -------------------- PART B PEAK ADAPTIVE TRAIL --------------------- */

    @Test fun trail_ratchets_tighter_as_gain_grows() {
        assertTrue(PeakAdaptiveTrail6390.trailPctForPeakGain(10.0) < Double.POSITIVE_INFINITY)
        assertTrue(PeakAdaptiveTrail6390.trailPctForPeakGain(50.0) < PeakAdaptiveTrail6390.trailPctForPeakGain(20.0))
        assertTrue(PeakAdaptiveTrail6390.trailPctForPeakGain(1_000.0) < PeakAdaptiveTrail6390.trailPctForPeakGain(200.0))
    }

    @Test fun trail_does_not_fire_below_10pct_gain() {
        assertFalse(PeakAdaptiveTrail6390.shouldExitOnTrail(5.0, 3.0))
    }

    @Test fun trail_fires_when_giveback_exceeds_threshold() {
        // At peak 500%, trail = 10% → exit when current gain drops below 490%.
        assertFalse(PeakAdaptiveTrail6390.shouldExitOnTrail(500.0, 495.0))
        assertTrue(PeakAdaptiveTrail6390.shouldExitOnTrail(500.0, 489.0))
    }

    @Test fun peak_state_ratchets_up_only() {
        assertEquals(50.0, PeakAdaptiveTrail6390.recordTick("posA", 50.0), 1e-9)
        assertEquals(80.0, PeakAdaptiveTrail6390.recordTick("posA", 80.0), 1e-9)
        assertEquals(80.0, PeakAdaptiveTrail6390.recordTick("posA", 60.0), 1e-9)  // peak preserved
    }

    /* -------------------- PART B PEAK SLIP EXIT --------------------------- */

    @Test fun peak_slip_ignored_below_30pct_gain() {
        assertEquals(PeakSlipExit6390.Action.HOLD,
            PeakSlipExit6390.evaluate(peakGainPct = 20.0, currentGainPct = -5.0))
    }

    @Test fun peak_slip_cuts_half_at_25pct_giveback() {
        assertEquals(PeakSlipExit6390.Action.CUT_HALF,
            PeakSlipExit6390.evaluate(peakGainPct = 100.0, currentGainPct = 74.0))
    }

    @Test fun peak_slip_cuts_full_at_40pct_giveback() {
        assertEquals(PeakSlipExit6390.Action.CUT_FULL,
            PeakSlipExit6390.evaluate(peakGainPct = 200.0, currentGainPct = 159.0))
    }

    /* -------------------- PART B WINNER LADDER --------------------------- */

    @Test fun ladder_fires_each_rung_once_then_null() {
        val pos = "posA"
        val r1 = WinnerLadderExit6390.nextRung(pos, currentGainPct = 250.0)
        assertNotNull(r1); assertEquals(WinnerLadderExit6390.Rung.RUNG_3X, r1)
        // Same rung must not re-fire even if we tick again in the same window.
        val r1Again = WinnerLadderExit6390.nextRung(pos, currentGainPct = 250.0)
        assertNull(r1Again)
        // Reaching 5x fires the second rung.
        val r2 = WinnerLadderExit6390.nextRung(pos, currentGainPct = 500.0)
        assertEquals(WinnerLadderExit6390.Rung.RUNG_5X, r2)
        // At 10x the third rung fires.
        val r3 = WinnerLadderExit6390.nextRung(pos, currentGainPct = 950.0)
        assertEquals(WinnerLadderExit6390.Rung.RUNG_10X, r3)
    }

    @Test fun ladder_sell_fractions_are_25_25_25() {
        assertEquals(0.25, WinnerLadderExit6390.Rung.RUNG_3X.sellFraction, 1e-9)
        assertEquals(0.25, WinnerLadderExit6390.Rung.RUNG_5X.sellFraction, 1e-9)
        assertEquals(0.25, WinnerLadderExit6390.Rung.RUNG_10X.sellFraction, 1e-9)
    }

    /* -------------------- PART B VOL EXHAUSTION -------------------------- */

    @Test fun volume_collapse_while_price_holds_is_distribution() {
        // Buy volume collapsed 70% (8000 -> 2400) but price only slipped 2%.
        assertTrue(VolumeExhaustionDetector6390.isDistributionRisk(
            peakBuyVolumeUsd = 8_000.0, currentBuyVolumeUsd = 2_400.0,
            peakPriceUsd = 1.00, currentPriceUsd = 0.98))
    }

    @Test fun volume_collapse_with_price_collapse_is_not_flagged_as_distribution() {
        // Price collapsed too — this is just a bleed, not a top-distribution.
        assertFalse(VolumeExhaustionDetector6390.isDistributionRisk(
            peakBuyVolumeUsd = 8_000.0, currentBuyVolumeUsd = 2_400.0,
            peakPriceUsd = 1.00, currentPriceUsd = 0.50))
    }

    /* -------------------- PART B WHALE DISTRIBUTION ---------------------- */

    @Test fun single_whale_dumping_20pct_of_bag_alarms() {
        assertTrue(WhaleDistributionAlarm6390.isAlarm(topHolderNetSellPctOfBag = 21.0))
        assertFalse(WhaleDistributionAlarm6390.isAlarm(topHolderNetSellPctOfBag = 10.0))
    }

    @Test fun three_whales_selling_10pct_alarms() {
        assertTrue(WhaleDistributionAlarm6390.aggregateAlarm(listOf(10.5, 11.0, 12.0, 5.0)))
    }

    /* -------------------- PART B PEAK CAPTURE AUTHORITY ------------------ */

    @Test fun authority_prioritises_full_cut_when_giveback_severe() {
        val d = PeakCaptureAuthority6390.decide(PeakCaptureAuthority6390.Inputs(
            positionId = "posX",
            peakGainPct = 500.0, currentGainPct = 400.0,   // 100pp giveback
            peakBuyVolumeUsd = 5_000.0, currentBuyVolumeUsd = 5_000.0,
            peakPriceUsd = 1.0, currentPriceUsd = 1.0,
            topHolderNetSellsPctOfBag = emptyList(),
        ))
        assertEquals(PeakCaptureAuthority6390.Verdict.FULL_CUT, d.verdict)
        assertEquals(1.0, d.sellFraction, 1e-9)
    }

    @Test fun authority_fires_ladder_partial_on_scheduled_rung() {
        val d = PeakCaptureAuthority6390.decide(PeakCaptureAuthority6390.Inputs(
            positionId = "posY",
            peakGainPct = 250.0, currentGainPct = 250.0,   // still at peak
            peakBuyVolumeUsd = 5_000.0, currentBuyVolumeUsd = 5_000.0,
            peakPriceUsd = 1.0, currentPriceUsd = 1.0,
            topHolderNetSellsPctOfBag = emptyList(),
        ))
        assertEquals(PeakCaptureAuthority6390.Verdict.LADDER_PARTIAL, d.verdict)
        assertEquals(0.25, d.sellFraction, 1e-9)
    }

    @Test fun authority_holds_when_nothing_triggers() {
        val d = PeakCaptureAuthority6390.decide(PeakCaptureAuthority6390.Inputs(
            positionId = "posZ",
            peakGainPct = 20.0, currentGainPct = 18.0,
            peakBuyVolumeUsd = 5_000.0, currentBuyVolumeUsd = 4_800.0,
            peakPriceUsd = 1.0, currentPriceUsd = 0.98,
            topHolderNetSellsPctOfBag = emptyList(),
        ))
        assertEquals(PeakCaptureAuthority6390.Verdict.HOLD, d.verdict)
        assertEquals(0.0, d.sellFraction, 1e-9)
    }

    @Test fun cheems_26x_would_capture_at_least_ten_x_via_ladder_plus_trail() {
        val pos = "cheems"
        // Simulate the runner: it climbs from +50% to +2600% then gives back.
        val gains = listOf(50.0, 150.0, 250.0, 500.0, 900.0, 1800.0, 2600.0, 2400.0, 2200.0, 2100.0)
        var laddered = 0.0
        for (g in gains) {
            PeakAdaptiveTrail6390.recordTick(pos, g)
            val r = WinnerLadderExit6390.nextRung(pos, g)
            if (r != null) laddered += r.sellFraction
        }
        // All three rungs must have banked 25/25/25 by 10x.
        assertEquals(0.75, laddered, 1e-9)
        // And on the give-back the trail must have cut the remaining 25%.
        val trail = PeakAdaptiveTrail6390.shouldExitOnTrail(peakGainPct = 2600.0, currentGainPct = 2100.0)
        assertTrue("500pp giveback at 10x-tier trail=8% MUST fire", trail)
    }
}
