package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalInstanceIdentity6472
import com.lifecyclebot.engine.truth.LaneAdaptiveDamping6472
import com.lifecyclebot.engine.truth.LaneAdmissionGate6473
import com.lifecyclebot.engine.truth.RootCauseClassifier6471
import com.lifecyclebot.engine.truth.TelemetryIntegrityHold6472
import com.lifecyclebot.engine.truth.UnifiedReconcilerHealth6470
import com.lifecyclebot.engine.truth.WatchlistHardCapInvariant6473
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6473 — HARD CI ASSERTIONS for the deferred 6472 wires.
 *
 * §Damping-Wire  LaneAdmissionGate6473 skips on cadence throttle, shrinks size otherwise.
 * §Identity-Wire Every canonical status line ends with the instanceId/runId/epoch stamp.
 * §P1.1-Watchlist WatchlistHardCapInvariant6473 fires on overrun, silent on clean size.
 */
class DeferredWiresAcceptanceTest6473 {

    // ─── Lane admission gate ──────────────────────────────────────────
    @Test
    fun `admission gate allows probe at neutral EV with full size`() {
        LaneAdaptiveDamping6472.resetForTest()
        LaneAdmissionGate6473.resetForTest()
        LaneAdaptiveDamping6472.recordLaneEvPct("MEME", 0.0)
        val d = LaneAdmissionGate6473.admissionDecision("MEME", requestedSizeSol = 0.1)
        assertTrue(d is LaneAdmissionGate6473.Decision.Allow)
        val allow = d as LaneAdmissionGate6473.Decision.Allow
        assertEquals(0.1, allow.effectiveSizeSol, 1e-9)
        assertEquals(0, allow.level)
    }

    @Test
    fun `admission gate shrinks size for SHITCOIN at -57 EV`() {
        LaneAdaptiveDamping6472.resetForTest()
        LaneAdmissionGate6473.resetForTest()
        LaneAdaptiveDamping6472.recordLaneEvPct("SHITCOIN", -57.0)
        val d = LaneAdmissionGate6473.admissionDecision("SHITCOIN", requestedSizeSol = 0.10)
        assertTrue(d is LaneAdmissionGate6473.Decision.Allow)
        val allow = d as LaneAdmissionGate6473.Decision.Allow
        // Damping level 3 → size × 0.25 → 0.025
        assertEquals(0.025, allow.effectiveSizeSol, 1e-9)
        assertEquals(3, allow.level)
        assertEquals(15, allow.effectiveScoreFloor)
    }

    @Test
    fun `admission gate throttles second admission inside cadence window`() {
        LaneAdaptiveDamping6472.resetForTest()
        LaneAdmissionGate6473.resetForTest()
        LaneAdaptiveDamping6472.recordLaneEvPct("BLEED", -95.0)
        // First entry allowed at damped size.
        val first = LaneAdmissionGate6473.admissionDecision("BLEED", requestedSizeSol = 0.10)
        assertTrue(first is LaneAdmissionGate6473.Decision.Allow)
        // Second entry in the same instant must be throttled by the 300s cadence.
        val second = LaneAdmissionGate6473.admissionDecision("BLEED", requestedSizeSol = 0.10)
        assertTrue("second is throttled", second is LaneAdmissionGate6473.Decision.Skip)
        val skip = second as LaneAdmissionGate6473.Decision.Skip
        assertTrue(skip.reason.startsWith("cadence_throttle_lvl_"))
    }

    // ─── Identity stamp wire ──────────────────────────────────────────
    @Test
    fun `status lines carry the canonical identity stamp`() {
        val rootStamp = RootCauseClassifier6471.statusLine()
        val reconcStamp = UnifiedReconcilerHealth6470.statusLine()
        val telemetryStamp = TelemetryIntegrityHold6472.statusLine()
        val gateStamp = LaneAdmissionGate6473.statusLine()
        val watchlistStamp = WatchlistHardCapInvariant6473.statusLine()
        val instId = CanonicalInstanceIdentity6472.instanceId().take(8)
        listOf(rootStamp, reconcStamp, telemetryStamp, gateStamp, watchlistStamp).forEach {
            assertTrue("status line must include instanceId stamp: $it", it.contains("instanceId=$instId"))
            assertTrue("status line must include runId: $it", it.contains("runId="))
            assertTrue("status line must include epoch: $it", it.contains("epoch="))
        }
    }

    // ─── Watchlist hard-cap invariant ─────────────────────────────────
    @Test
    fun `watchlist hardcap invariant silent when under cap`() {
        WatchlistHardCapInvariant6473.resetForTest()
        assertTrue(WatchlistHardCapInvariant6473.assertSize(200, 250, "hot_watchlist"))
        val s = WatchlistHardCapInvariant6473.statusLine()
        assertTrue(s.contains("overruns=0"))
    }

    @Test
    fun `watchlist hardcap invariant fires on overrun with magnitude`() {
        WatchlistHardCapInvariant6473.resetForTest()
        assertFalse(WatchlistHardCapInvariant6473.assertSize(320, 250, "hot_watchlist"))
        val s = WatchlistHardCapInvariant6473.statusLine()
        assertTrue(s.contains("overruns=1"))
        assertTrue(s.contains("lastOverrun=70"))
    }

    @Test
    fun `watchlist hardcap invariant is safe when cap is zero or negative`() {
        WatchlistHardCapInvariant6473.resetForTest()
        // cap<=0 means "no cap configured yet" — must not crash and must not
        // record a false overrun.
        assertTrue(WatchlistHardCapInvariant6473.assertSize(1000, 0, "unconfigured"))
        assertTrue(WatchlistHardCapInvariant6473.assertSize(1000, -1, "invalid"))
    }
}
