package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket
import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.truth.ShadowBookExit7948
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7948 — learning loops that never closed, and a Cortex lane whose "strong" read was inverted. */
class Aate7948LearningLoopsTest {

    /** A stat of [n] outcomes centred on [mean] with spread [sd]. */
    private fun stat(n: Int, mean: Double, sd: Double): CortexLedger7885.Stat {
        val s = CortexLedger7885.Stat()
        repeat(n) { i -> s.add(mean + if (i % 2 == 0) sd else -sd, false) }
        return s
    }

    @After fun tearDown() { StrategyHypothesisEngine.reset() }

    // ── 1. Cortex inversion ──

    @Test fun theReportedShitcoinCohortIsInverted() {
        // 5.0.7947: STRONG n=39 at -8.1%, NEUTRAL n=14 at +5.5%.
        assertTrue(CortexScoreboard7885.invertedProven7948(stat(39, -8.1, 20.0), stat(14, 5.5, 20.0)))
    }

    @Test fun inversionNeedsEnoughReadsAndAGapBeyondNoise() {
        val minS = CortexScoreboard7885.INVERSION_MIN_STRONG_7948
        val minN = CortexScoreboard7885.INVERSION_MIN_NEUTRAL_7948
        assertFalse(CortexScoreboard7885.invertedProven7948(stat(minS - 1, -8.0, 5.0), stat(30, 5.0, 5.0)))
        assertFalse(CortexScoreboard7885.invertedProven7948(stat(40, -8.0, 5.0), stat(minN - 1, 5.0, 5.0)))
        // gap inside the proof margin
        assertFalse(CortexScoreboard7885.invertedProven7948(stat(40, 1.0, 2.0), stat(40, 2.5, 2.0)))
        // gap swamped by variance
        assertFalse(CortexScoreboard7885.invertedProven7948(stat(20, -3.0, 80.0), stat(10, 3.0, 80.0)))
        // strong above neutral is never inverted
        assertFalse(CortexScoreboard7885.invertedProven7948(stat(40, 9.0, 5.0), stat(40, 2.0, 5.0)))
    }

    @Test fun anInvertedLaneLosesItsStrongSidePowersUntilItReproves() {
        val board = CortexScoreboard7885()
        // STRONG is proven on its own (+5%, tight) but NEUTRAL earns +15%: strong is not strong.
        repeat(60) { i -> board.record("SHITCOIN", Bucket.STRONG, false, 5.0 + if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        repeat(30) { i -> board.record("SHITCOIN", Bucket.NEUTRAL, false, 15.0 + if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        assertTrue(CortexScoreboard7885.overruleProven(board.books["SHITCOIN"]!!.byBucket[Bucket.STRONG.ordinal]))
        assertTrue(board.inverted7948("SHITCOIN"))
        assertFalse(board.overruleAuthority("SHITCOIN"))
        // The STRONG book keeps learning: once STRONG beats NEUTRAL again the power returns.
        repeat(400) { i -> board.record("SHITCOIN", Bucket.STRONG, false, 25.0 + if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        assertFalse(board.inverted7948("SHITCOIN"))
        // V5.0.8025 — the evidence returns in full; authority is evidence x maturity, never full.
        assertTrue(board.evidenceFor8025("SHITCOIN") > 0.99)
        assertFalse(board.overruleAuthority("SHITCOIN"))
    }

    @Test fun realisedClosesAlsoRevealAnInversion() {
        val board = CortexScoreboard7885()
        repeat(25) { i -> board.recordRealized("LIVE", "MOONSHOT", Bucket.STRONG, -12.0 + if (i % 2 == 0) 2.0 else -2.0) }
        repeat(12) { i -> board.recordRealized("LIVE", "MOONSHOT", Bucket.NEUTRAL, 4.0 + if (i % 2 == 0) 2.0 else -2.0) }
        assertTrue(board.inverted7948("MOONSHOT"))
        assertFalse(board.inverted7948("QUALITY"))
    }

    // ── 2. Shadow book closes what it opens ──

    @Test fun shadowBookExitRules() {
        val t = ShadowBookExit7948.TIMEOUT_MIN_7948
        assertEquals("stop_loss", ShadowBookExit7948.exitReason7948(-12.0, 1L, 10.0))
        assertEquals("take_profit", ShadowBookExit7948.exitReason7948(ShadowBookExit7948.TAKE_PROFIT_PCT_7948, 1L, 10.0))
        assertNull(ShadowBookExit7948.exitReason7948(3.0, t - 1, 10.0))
        // No price now: the timeout still closes it (on its last observed mark).
        assertEquals("timeout_${t}min", ShadowBookExit7948.exitReason7948(null, t, 10.0))
        assertNull(ShadowBookExit7948.exitReason7948(null, t - 1, 10.0))
        assertTrue(t < 24L)   // a ~24-minute session closes what it opened
    }

    // ── 2. Hypothesis arms keep their quality counters across a restart ──

    @Test fun hypothesisArmQualityCountersSurviveExportImport() {
        val ctx = "LIVE|SHITCOIN|S40|CHOP"
        val state = org.json.JSONObject()
            .put("environmentSchema", 7835)
            .put("active", org.json.JSONObject().put(ctx, org.json.JSONObject()
                .put("csN", 9L).put("csM", 31.0).put("csM2", 900.0)
                .put("vsN", 0L).put("vsM", 0.0).put("vsM2", 0.0)
                .put("vSizeBias", 0.95).put("vStopMult", 1.25)
                .put("cw", 5L).put("cl", 4L).put("cr", 1L)))
        StrategyHypothesisEngine.importState(state.toString())
        val arm = org.json.JSONObject(StrategyHypothesisEngine.exportState()).getJSONObject("active").getJSONObject(ctx)
        assertEquals(5L, arm.getLong("cw"))
        assertEquals(4L, arm.getLong("cl"))
        assertEquals(1L, arm.getLong("cr"))
        assertEquals(0.95, arm.getDouble("vSizeBias"), 1e-9)
        assertEquals(1.25, arm.getDouble("vStopMult"), 1e-9)
    }

    // ── 3. Tactic rotation on pooled lane evidence ──

    @Test fun pooledLaneEvidenceRotatesABleedingTactic() {
        // The reported regime: WR ~21%, mean -4.4%, pooled over the lane's n=1 cohorts.
        assertTrue(TacticSwitcher.pooledRotationDue7948(wins = 2, losses = 8, trades = 10, meanPnlPct = -4.37))
        // Too few pooled closes, a non-negative mean, or a winning book: no rotation.
        assertFalse(TacticSwitcher.pooledRotationDue7948(wins = 1, losses = 6, trades = 7, meanPnlPct = -6.0))
        assertFalse(TacticSwitcher.pooledRotationDue7948(wins = 2, losses = 8, trades = 10, meanPnlPct = -1.0))
        assertFalse(TacticSwitcher.pooledRotationDue7948(wins = 6, losses = 6, trades = 12, meanPnlPct = -3.0))
        assertFalse(TacticSwitcher.pooledRotationDue7948(wins = 2, losses = 8, trades = 10, meanPnlPct = Double.NaN))
    }
}
