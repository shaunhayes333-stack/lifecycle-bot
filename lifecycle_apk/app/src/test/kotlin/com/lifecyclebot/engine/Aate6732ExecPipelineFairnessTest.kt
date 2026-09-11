package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitTelemetryStamper6732
import com.lifecyclebot.engine.truth.ExitThroughputAuthority6727
import com.lifecyclebot.engine.truth.LaneCapitalFairness6732
import com.lifecyclebot.engine.truth.StopLatencyClasses6464
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6732 — regression suite for:
 *   1. LaneCapitalFairness6732 — non-meme lanes fail open, blank lane
 *      preserves legacy behaviour, meme lanes gate on target utilization.
 *   2. ExitThroughputAuthority6727 lane bypass — a lane with headroom
 *      is never blocked by portfolio-wide gates.
 *   3. CanonicalPriceMarkRegistry6522 observation fallback — invalid
 *      liquidity no longer kills the observation slot when everything
 *      else is provable.
 *   4. ExitTelemetryStamper6732 — terminal sells now stamp the
 *      canonical StopLatencyClasses buckets and exit-gate counters.
 */
class Aate6732ExecPipelineFairnessTest {

    @Before
    fun reset() {
        StopLatencyClasses6464.resetForTest()
        ExitTelemetryStamper6732.resetForTest()
        CanonicalPriceMarkRegistry6522.resetForTest()
    }

    // ─── 1. LaneCapitalFairness6732 ─────────────────────────────────

    @Test
    fun `non-meme lane always has headroom (fail open)`() {
        // "STOCK", "PERPS", empty, unknown — all must fail open. Cross-asset
        // parity is preserved (we never hard-block cross-asset admissions).
        assertTrue(LaneCapitalFairness6732.hasHeadroom("PAPER", "STOCK"))
        assertTrue(LaneCapitalFairness6732.hasHeadroom("PAPER", "PERPS"))
        assertTrue(LaneCapitalFairness6732.hasHeadroom("PAPER", ""))
        assertTrue(LaneCapitalFairness6732.hasHeadroom("PAPER", "UNKNOWN_LANE"))
    }

    @Test
    fun `meme lane returns a headroom struct with lane name normalized`() {
        val h = LaneCapitalFairness6732.headroomFor("PAPER", "blue-chip")
        assertEquals("BLUECHIP", h.lane)
    }

    // ─── 2. ExitThroughputAuthority6727 lane bypass ─────────────────

    @Test
    fun `blank lane preserves legacy portfolio-wide evaluation`() {
        // Calling with blank lane must not throw and must return a Verdict
        // whose reason is one of the well-known strings — no new lane-
        // fairness bypass surfaces here.
        val v = ExitThroughputAuthority6727.evaluate("PAPER", "")
        assertNotNull(v)
        assertFalse(v.reason == "LANE_HEADROOM_FAIRNESS_6732")
    }

    @Test
    fun `lane with headroom returns lane-fairness bypass verdict`() {
        // Non-meme lane always has headroom → bypass reason surfaces.
        val v = ExitThroughputAuthority6727.evaluate("PAPER", "STOCK")
        assertTrue("STOCK is non-meme; must bypass with fairness reason", v.allow)
        assertEquals("LANE_HEADROOM_FAIRNESS_6732", v.reason)
    }

    // ─── 3. Mark observation fallback ───────────────────────────────

    @Test
    fun `invalid liquidity falls through to observation slot instead of hard-reject`() {
        val mint = "A".repeat(32)
        val r = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = mint,
            observedBaseMint = mint,
            pairOrPool = "MINT_ROUTE:$mint",
            quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 1.2345,
            liquidityUsd = 0.0,           // ← formerly killed both slots
            evidenceTimestampMs = System.currentTimeMillis(),
        )
        assertTrue(
            "V5.0.6732 must publish observation when liquidity is invalid, got reason=${r.reason}",
            r.promoted,
        )
        assertEquals("OBSERVATION_ADMITTED_6628", r.reason)
        // Executable slot must NOT be published without liquidity.
        assertNull(
            "executable slot must stay empty when liquidity is invalid",
            CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE),
        )
        // Observation slot must be populated.
        assertNotNull(
            "observation slot must be populated by the 6732 fallback",
            CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING),
        )
    }

    @Test
    fun `valid liquidity continues to publish executable slot`() {
        val mint = "B".repeat(32)
        val r = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = mint,
            observedBaseMint = mint,
            pairOrPool = "MINT_ROUTE:$mint",
            quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 1.2345,
            liquidityUsd = 25_000.0,
            evidenceTimestampMs = System.currentTimeMillis(),
        )
        assertTrue(r.promoted)
        assertNotNull(
            "healthy liquidity must still publish executable slot",
            CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE),
        )
    }

    // ─── 4. Exit telemetry stamper ──────────────────────────────────

    @Test
    fun `note exit intent then completed records latency in stop-latency bucket`() {
        ExitTelemetryStamper6732.noteExitIntent("pos-1", StopLatencyClasses6464.Class.HARD_STOP)
        ExitTelemetryStamper6732.noteExitCompleted("pos-1", "HARD_STOP_CONFIRMED")
        val snap = StopLatencyClasses6464.snapshot()
        val (count, _, _) = snap.getValue(StopLatencyClasses6464.Class.HARD_STOP)
        assertEquals("hard-stop bucket must record exactly one sample", 1L, count)
    }

    @Test
    fun `terminal without intent records unknown latency not zero`() {
        // A terminal reason identifies its class, not when its decision began.
        // Missing timestamps must never inflate the zero-millisecond population.
        ExitTelemetryStamper6732.noteExitCompleted("pos-2", "CATASTROPHIC_STOP_LOSS_OVERRUN_-47pct_FROM_RAPID")
        val snap = StopLatencyClasses6464.snapshot()
        val (count, _, _) = snap.getValue(StopLatencyClasses6464.Class.CATASTROPHIC_EXIT)
        assertEquals("unknown latency must not appear as a measured zero", 0L, count)
    }

    @Test
    fun `trailing reason routes to trailing-stop bucket`() {
        ExitTelemetryStamper6732.noteExitIntent("pos-3", StopLatencyClasses6464.Class.TRAILING_STOP)
        ExitTelemetryStamper6732.noteExitCompleted("pos-3", "PROFIT_LOCK_TRAIL_TIGHTENED")
        val snap = StopLatencyClasses6464.snapshot()
        val (count, _, _) = snap.getValue(StopLatencyClasses6464.Class.TRAILING_STOP)
        assertEquals("profit-lock/trail reason must land in trailing bucket", 1L, count)
    }
}
