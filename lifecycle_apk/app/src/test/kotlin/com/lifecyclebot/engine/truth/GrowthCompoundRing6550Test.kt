package com.lifecyclebot.engine.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6550c — GROWTH COMPOUND RING acceptance tests.
 *
 * Operator directive: "Turn the freshly-flowing paper buys into a
 * live-shadow $50 → $1M compounding scoreboard operators can watch grow."
 *
 * These tests lock the ring semantics:
 *   * a $50 start is the anchor, milestones cross as equity climbs
 *   * peak equity is monotonic; drawdowns are measured against it
 *   * every milestone timestamps its first crossing exactly once
 *   * bad SOL/USD holds prior equity (doesn't drive false milestones)
 */
class GrowthCompoundRing6550Test {

    @Before
    fun setUp() { GrowthCompoundRing6550.resetForTest() }

    @Test
    fun start_equity_below_100_crosses_no_milestone() {
        val snap = GrowthCompoundRing6550.observe(equitySol = 0.33, solPriceUsd = 150.0)
        assertEquals(49.5, snap.currentEquityUsd, 1e-6)
        assertFalse(snap.milestones.any { it.crossed })
        assertEquals(100.0, snap.nextMilestoneUsd)
    }

    @Test
    fun crossing_1k_and_10k_records_timestamps() {
        val a = GrowthCompoundRing6550.observe(equitySol = 7.0, solPriceUsd = 150.0)  // $1050
        assertTrue(a.milestones.first { it.amountUsd == 100.0 }.crossed)
        assertTrue(a.milestones.first { it.amountUsd == 1_000.0 }.crossed)
        assertFalse(a.milestones.first { it.amountUsd == 10_000.0 }.crossed)
        assertNotNull(a.milestones.first { it.amountUsd == 1_000.0 }.crossedAtMs)

        Thread.sleep(3)
        val b = GrowthCompoundRing6550.observe(equitySol = 70.0, solPriceUsd = 150.0) // $10.5k
        assertTrue(b.milestones.first { it.amountUsd == 10_000.0 }.crossed)
        // Prior milestone timestamp preserved across new observations.
        assertEquals(
            a.milestones.first { it.amountUsd == 1_000.0 }.crossedAtMs,
            b.milestones.first { it.amountUsd == 1_000.0 }.crossedAtMs,
        )
    }

    @Test
    fun peak_and_drawdown_track_correctly() {
        val a = GrowthCompoundRing6550.observe(equitySol = 100.0, solPriceUsd = 150.0) // $15k
        assertEquals(15_000.0, a.peakEquityUsd, 1e-6)
        assertEquals(0, a.drawdownFromPeakBps)

        val b = GrowthCompoundRing6550.observe(equitySol = 80.0, solPriceUsd = 150.0)  // $12k, -20%
        assertEquals(15_000.0, b.peakEquityUsd, 1e-6)
        assertEquals(2_000, b.drawdownFromPeakBps)

        val c = GrowthCompoundRing6550.observe(equitySol = 120.0, solPriceUsd = 150.0) // $18k new peak
        assertEquals(18_000.0, c.peakEquityUsd, 1e-6)
        assertEquals(0, c.drawdownFromPeakBps)
    }

    @Test
    fun bad_sol_price_holds_prior_usd_equity() {
        val a = GrowthCompoundRing6550.observe(equitySol = 4.0, solPriceUsd = 150.0)  // $600
        val heldUsd = a.currentEquityUsd

        val b = GrowthCompoundRing6550.observe(equitySol = 4.0, solPriceUsd = 0.0)    // bad feed
        assertEquals("bad feed must not zero the ring", heldUsd, b.currentEquityUsd, 1e-6)

        val c = GrowthCompoundRing6550.observe(equitySol = 4.0, solPriceUsd = -1.0)   // bad feed
        assertEquals(heldUsd, c.currentEquityUsd, 1e-6)

        val d = GrowthCompoundRing6550.observe(equitySol = 4.0, solPriceUsd = Double.NaN)
        assertEquals(heldUsd, d.currentEquityUsd, 1e-6)
    }

    @Test
    fun target_reached_reports_no_next_milestone() {
        val s = GrowthCompoundRing6550.observe(equitySol = 8_000.0, solPriceUsd = 150.0) // $1.2M
        assertTrue(s.milestones.all { it.crossed })
        assertNull(s.nextMilestoneUsd)
        assertEquals(10_000, s.progressBps)
    }

    @Test
    fun progress_bps_is_bounded() {
        val a = GrowthCompoundRing6550.observe(equitySol = 0.0, solPriceUsd = 150.0) // $0
        assertEquals(0, a.progressBps)

        val b = GrowthCompoundRing6550.observe(equitySol = 10_000.0, solPriceUsd = 150.0) // $1.5M > $1M
        assertEquals(10_000, b.progressBps)
    }

    @Test
    fun statusLine_is_non_blank_after_first_observation() {
        assertTrue(GrowthCompoundRing6550.statusLine().startsWith("no_ring_yet"))
        GrowthCompoundRing6550.observe(equitySol = 1.0, solPriceUsd = 150.0)
        val line = GrowthCompoundRing6550.statusLine()
        assertTrue("statusLine must contain equityUsd", line.contains("equityUsd="))
        assertTrue("statusLine must contain mult", line.contains("mult="))
        assertTrue("statusLine must contain milestones", line.contains("milestones="))
    }
}
