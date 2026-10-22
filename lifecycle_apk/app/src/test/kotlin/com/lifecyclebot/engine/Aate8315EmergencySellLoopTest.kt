package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import com.lifecyclebot.engine.sell.NoSignatureLease8315
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144
import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144
import com.lifecyclebot.engine.sell.SellRouteSpine8144
import com.lifecyclebot.engine.sell.SellRouteVenueProbe8144
import com.lifecyclebot.engine.sell.StopRoute8189
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8315 — P0: the emergency sell loop. 5.0.8314 live, Au (Raydium venue) made ~334 emergency attempts: the 7807
 * escalation skipped the Jupiter ladder, the 8189 stop budget skipped Raydium, PumpPortal was hard-blocked, every
 * attempt ended ROUTE_FAILED_NO_SIGNATURE and re-fired ~400 ms later.
 */
class Aate8315EmergencySellLoopTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun reset() {
        StopRoute8189.resetForTest8189()
        SellRouteSpine8144.resetForTest8144()
        SellRouteVenueProbe8144.resetForTest8144()
        CloseLease.emergencyHandoff8087 = null
    }

    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    // ── Fix 1: a confirmed Raydium venue is never budget-skipped ──

    @Test fun aRaydiumVenuePastTheStopBudgetIsNotSkippedANonRaydiumVenueIs() {
        val r = StopRoute8189
        // trigger at 1 s, nothing broadcast, now 20 s: past the 10 s budget
        assertNull("Raydium venue past budget: its own route answers", r.raydiumSkipWhy8315(Venue8144.RAYDIUM, false, true, 1_000L, 0L, 20_000L))
        assertEquals("STOP_BUDGET", r.raydiumSkipWhy8315(Venue8144.PUMPSWAP, false, true, 1_000L, 0L, 20_000L))
        assertEquals("STOP_BUDGET", r.raydiumSkipWhy8315(Venue8144.UNKNOWN, false, true, 1_000L, 0L, 20_000L))
        assertEquals("STOP_BUDGET", r.raydiumSkipWhy8315(Venue8144.AGGREGATOR_ONLY, false, true, 1_000L, 0L, 20_000L))
        assertNull("inside the budget", r.raydiumSkipWhy8315(Venue8144.PUMPSWAP, false, true, 1_000L, 0L, 11_000L))
        assertNull("already broadcast", r.raydiumSkipWhy8315(Venue8144.PUMPSWAP, false, true, 1_000L, 2_000L, 60_000L))
        assertNull("not a stop", r.raydiumSkipWhy8315(Venue8144.PUMPSWAP, false, false, 1_000L, 0L, 60_000L))
        assertNull("no open spine", r.raydiumSkipWhy8315(Venue8144.PUMPSWAP, false, true, null, 0L, 60_000L))
        // the other two reasons are unchanged, and a remembered failure wins over the venue
        assertEquals("PUMP_CURVE", r.raydiumSkipWhy8315(Venue8144.PUMP_CURVE, false, false, null, 0L, 20_000L))
        assertEquals("FAILED_RECENTLY", r.raydiumSkipWhy8315(Venue8144.RAYDIUM, true, true, 1_000L, 0L, 20_000L))
        assertEquals("FAILED_RECENTLY", r.raydiumSkipWhy8315(Venue8144.PUMP_CURVE, true, false, null, 0L, 20_000L))
    }

    // ── Fix 2: the decision is asked without counting a skip; the ladder guard reads it ──

    @Test fun raydiumSkipWhyAnswersWithoutCountingAndTheLadderGuardKeepsJupiterWhenRaydiumIsOff() {
        val r = StopRoute8189
        val mint = "au6mYfg1_8315"
        val reason = "CATASTROPHIC_HARD_BACKSTOP_-25"
        val t0 = 5_000_000L
        assertNotNull(SellRouteSpine8144.trigger8144(mint, reason, "p1", t0))
        val now = t0 + 20_000L  // past the 10 s stop budget, nothing broadcast

        // confirmed Raydium venue (curve absent, Raydium pool known)
        SellRouteVenueProbe8144.putForTest8144(mint, SellRouteVenueProbe8144.Probe8144(Venue8144.RAYDIUM, false, null, true, t0))
        assertEquals(Venue8144.RAYDIUM, SellRouteVenueProbe8144.cached8144(mint, now))
        assertNull(r.raydiumSkipWhy8189(mint, reason, now))
        assertFalse(r.skipRaydium8189(mint, reason, now))
        assertFalse("Raydium answers: the escalation may still skip the ladder", r.raydiumDirectSkipped8315(mint, reason, now))
        assertTrue(r.statusLine8189().contains("raydiumSkipped=0"))

        // the same stop on a PumpSwap venue is budget-skipped; asking why counts nothing, skipping counts once
        SellRouteVenueProbe8144.putForTest8144(mint, SellRouteVenueProbe8144.Probe8144(Venue8144.PUMPSWAP, true, true, null, t0))
        assertEquals("STOP_BUDGET", r.raydiumSkipWhy8189(mint, reason, now))
        assertTrue(r.statusLine8189().contains("raydiumSkipped=0"))
        assertFalse("not a Raydium venue: PumpPortal is the direct route there", r.raydiumDirectSkipped8315(mint, reason, now))
        assertTrue(r.skipRaydium8189(mint, reason, now))
        assertTrue(r.statusLine8189().contains("raydiumSkipped=1"))

        // a Raydium venue whose build failed inside the TTL: skipped, and the ladder guard keeps Jupiter
        SellRouteVenueProbe8144.putForTest8144(mint, SellRouteVenueProbe8144.Probe8144(Venue8144.RAYDIUM, false, null, true, t0))
        r.noteVenueFailure8189(mint, SellRouteEndpoint8144.Route8144.RAYDIUM, now)
        assertEquals("FAILED_RECENTLY", r.raydiumSkipWhy8189(mint, reason, now + 1_000L))
        assertTrue(r.raydiumDirectSkipped8315(mint, reason, now + 1_000L))
        assertFalse("the failure aged out", r.raydiumDirectSkipped8315(mint, reason, now + 90_000L))
        assertTrue(r.statusLine8189().contains("raydiumSkipped=1"))
    }

    @Test fun theLadderGuardAndTheLeaseSettlementAreWiredIntoTheExecutor() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("if (!directRouteViable8144(ts.mint) || com.lifecyclebot.engine.sell.StopRoute8189.raydiumDirectSkipped8315(ts.mint, reason)) {"))
        assertTrue("the guard runs before the escalation can skip the ladder",
            ex.indexOf("StopRoute8189.raydiumDirectSkipped8315(ts.mint, reason)") < ex.indexOf("if (emergencyRouteEscalated7807(ts, reason)) return true"))
        assertTrue(ex.contains("val kept8315 = com.lifecyclebot.engine.sell.NoSignatureLease8315.settle8315(ts.mint, canonicalReason, r.name)"))
        assertFalse("NO_SIGNATURE no longer releases unconditionally",
            ex.contains("ROUTE_FAILED_NO_SIGNATURE -> {\n                        com.lifecyclebot.engine.sell.CloseLease.release(ts.mint, r.name)"))
        val stop = src("engine/sell/StopRoute8189.kt")
        assertTrue(stop.contains("val why = raydiumSkipWhy8189(mint, reason, nowMs) ?: return false"))
        assertTrue(stop.contains("venue != SellRouteEndpoint8144.Venue8144.RAYDIUM &&"))
    }

    // ── Fix 3: an emergency NO_SIGNATURE records a retry and keeps its lease (no 400 ms re-fire) ──

    @Test fun anEmergencyNoSignatureRecordsARetryAndKeepsItsLeaseANormalExitStillReleases() {
        val m = "noSig8315"
        CloseLease.release(m, "TEST_RESET")
        val reason = "CATASTROPHIC_HARD_BACKSTOP_-25"
        assertTrue(NoSignatureLease8315.keepsLease8315(reason, "ROUTE_FAILED_NO_SIGNATURE"))
        assertTrue(NoSignatureLease8315.keepsLease8315("HARD_STOP_LOSS", "ROUTE_FAILED_NO_SIGNATURE"))
        assertFalse("not an emergency", NoSignatureLease8315.keepsLease8315("TAKE_PROFIT", "ROUTE_FAILED_NO_SIGNATURE"))
        assertFalse("not a no-signature result", NoSignatureLease8315.keepsLease8315(reason, "FAILED_FATAL"))
        assertFalse(NoSignatureLease8315.keepsLease8315(reason, "CONFIRMED"))

        assertNotNull(CloseLease.acquire(m, "NS", reason))
        assertTrue(CloseLease.sellInFlight7948(m))
        assertTrue(NoSignatureLease8315.settle8315(m, reason, "ROUTE_FAILED_NO_SIGNATURE"))
        assertTrue("lease kept", CloseLease.isLeased(m))
        assertFalse("out of flight: never a blocking lease", CloseLease.sellInFlight7948(m))
        assertEquals("the retry was recorded", 2, CloseLease.attemptCount(m))
        assertTrue("the 2 s floor stands", CloseLease.inBackoff(m))
        assertTrue(CloseLease.msUntilEligible(m) in 1L..2_000L)
        assertNull("a same-tick re-fire is refused: no 400 ms loop", CloseLease.acquire(m, "NS", reason))
        CloseLease.release(m, "TEST_DONE")
        assertFalse(CloseLease.isLeased(m))

        // a non-emergency NO_SIGNATURE releases exactly as before
        assertNotNull(CloseLease.acquire(m, "NS", "TAKE_PROFIT"))
        assertFalse(NoSignatureLease8315.settle8315(m, "TAKE_PROFIT", "ROUTE_FAILED_NO_SIGNATURE"))
        assertFalse(CloseLease.isLeased(m))
        // and every other terminal result releases an emergency lease
        assertNotNull(CloseLease.acquire(m, "NS", reason))
        assertFalse(NoSignatureLease8315.settle8315(m, reason, "FAILED_FATAL"))
        assertFalse(CloseLease.isLeased(m))
    }
}
