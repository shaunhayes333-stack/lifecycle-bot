package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitThroughputAuthority6727
import com.lifecyclebot.engine.truth.PerformanceDoctrine6727
import com.lifecyclebot.engine.truth.ProviderInferenceHealth6727
import com.lifecyclebot.engine.truth.SourceCohortAdvisory6727
import com.lifecyclebot.engine.truth.CrossAssetUniverseDiagnostic6727
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6727 — full runtime-diagnostic authority coverage.
 *
 * Locks in the six new authorities that address the operator's 10-item
 * root-cause map from the 6726 dump. None of these change trading
 * heuristics; they establish canonical single-source-of-truth surfaces
 * downstream consumers key on.
 */
class Aate6727RuntimeAuthoritiesTest {

    @Before
    fun reset() {
        SourceCohortAdvisory6727.resetForTest6727()
    }

    // Item #1 & #3 covered in ExecutableOpenGate wire — smoke here only.
    @Test
    fun `exit throughput authority evaluates without throwing on empty state`() {
        val v = ExitThroughputAuthority6727.evaluate("paper")
        assertNotNull(v)
        assertTrue("fresh state must fail open", v.allow)
    }

    // Item #4 — PerformanceDoctrine
    @Test
    fun `performance doctrine reads without throwing on empty state`() {
        val v = PerformanceDoctrine6727.evaluate()
        assertNotNull(v)
        assertEquals(50.0, v.targetWinRatePct, 0.001)
    }

    @Test
    fun `performance doctrine diagnostic line contains target and measured`() {
        val line = PerformanceDoctrine6727.diagnosticLine()
        assertTrue(line.contains("target=50%"))
        assertTrue(line.contains("measured="))
    }

    // Item #5 — SourceCohortAdvisory
    @Test
    fun `source cohort advisory fails open with insufficient samples`() {
        val a = SourceCohortAdvisory6727.advisory("PUMP_PORTAL")
        assertEquals(1.0, a.throttleMultiplier, 0.001)
        assertFalse(SourceCohortAdvisory6727.shouldThrottle("PUMP_PORTAL"))
    }

    @Test
    fun `source cohort advisory throttles chronic losers past sample floor`() {
        // 15 losses at 0% WR — must throttle below 1.0
        for (i in 1..15) SourceCohortAdvisory6727.recordLoss("PUMP_PORTAL")
        val a = SourceCohortAdvisory6727.advisory("PUMP_PORTAL")
        assertTrue("chronic loser must throttle below 1.0, got ${a.throttleMultiplier}",
            a.throttleMultiplier < 1.0)
        assertTrue("chronic loser must throttle above the floor (0.30), got ${a.throttleMultiplier}",
            a.throttleMultiplier >= 0.30)
        assertTrue(SourceCohortAdvisory6727.shouldThrottle("PUMP_PORTAL"))
    }

    @Test
    fun `source cohort advisory does not throttle winners`() {
        for (i in 1..12) SourceCohortAdvisory6727.recordWin("COINGECKO_TRENDING")
        for (i in 1..3) SourceCohortAdvisory6727.recordLoss("COINGECKO_TRENDING")
        val a = SourceCohortAdvisory6727.advisory("COINGECKO_TRENDING")
        assertEquals("winner must NOT throttle (mult = 1.0)", 1.0, a.throttleMultiplier, 0.001)
    }

    // Item #8 — ProviderInferenceHealth
    @Test
    fun `provider inference health fails open when unknown`() {
        assertTrue(ProviderInferenceHealth6727.isHealthy("NEVER_SEEN"))
    }

    @Test
    fun `provider inference health fails closed on chronic failures`() {
        for (i in 1..10) ProviderInferenceHealth6727.recordFailure("GROQ")
        val h = ProviderInferenceHealth6727.health("GROQ")
        assertFalse("provider with 10 failures 0 successes must NOT be healthy", h.isHealthy)
        assertTrue("failure count captured", h.failures == 10L)
    }

    @Test
    fun `provider inference health recovers on successes`() {
        for (i in 1..10) ProviderInferenceHealth6727.recordSuccess("DEXSCREENER")
        val h = ProviderInferenceHealth6727.health("DEXSCREENER")
        assertTrue("provider with 10 successes must be healthy", h.isHealthy)
    }

    // Item #9 — CrossAssetUniverseDiagnostic
    @Test
    fun `cross asset universe records producer liveness and funnel drops`() {
        CrossAssetUniverseDiagnostic6727.recordStart("CRYPTO_ALT")
        for (i in 1..100) CrossAssetUniverseDiagnostic6727.recordCandidate("CRYPTO_ALT")
        for (i in 1..40) CrossAssetUniverseDiagnostic6727.recordAdvance("CRYPTO_ALT", "cryptobrain")
        for (i in 1..20) CrossAssetUniverseDiagnostic6727.recordAdvance("CRYPTO_ALT", "v3fdg")
        for (i in 1..30) CrossAssetUniverseDiagnostic6727.recordTerminal("CRYPTO_ALT", "STALE_SHARED")
        val snap = CrossAssetUniverseDiagnostic6727.snapshot()
        val cryptoAlt = snap.firstOrNull { it.track == "CRYPTO_ALT" }
        assertNotNull(cryptoAlt)
        assertTrue(cryptoAlt!!.started)
        assertEquals(100L, cryptoAlt.candidatesEmitted)
        assertEquals(40L, cryptoAlt.advancedToCryptoBrain)
        assertEquals(20L, cryptoAlt.advancedToV3Fdg)
        assertEquals(30L, cryptoAlt.terminalStaleShared)
    }
}
