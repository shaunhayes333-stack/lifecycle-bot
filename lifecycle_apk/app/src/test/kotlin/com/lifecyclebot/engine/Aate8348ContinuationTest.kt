package com.lifecyclebot.engine

import com.lifecyclebot.engine.ContinuationRead8348.Source8348
import com.lifecyclebot.engine.truth.TradeTape8100
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
 * V5.0.8348 — an unobserved continuation is not a flat 0.0 %. 5.0.8341: EXPRESS NATIVE_NO_CONTINUATION 1,567 (SSP +402 %
 * "0.3% < 1.0%" at 7 s of age). Unobserved -> DEFER (the hydration re-evaluation answers it); observed below the bar ->
 * refuse; a coin inside its launch hour with tape prints reads the tape's change over its own age, not a 1 h field.
 */
class Aate8348ContinuationTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private fun op(lane: String, reason: String, eligible: Boolean = false) =
        SpecialistBrainBridge7542.Opinion(lane, eligible, 10, 10, 0.0, reason, "NONE", "e", "x")

    @Before fun fresh() { ContinuationRead8348.resetForTests8348() }
    @After fun back() { ContinuationRead8348.resetForTests8348() }

    // ── the read: provenance, in order ──

    @Test fun anUnobservedContinuationIsFlaggedNotReadAsZero() {
        val c = ContinuationRead8348.resolve8348(0.0, 0L, 0.0, 0.0, 0, null, null, 0.12)
        assertEquals(Source8348.UNOBSERVED, c.source)
        assertFalse(c.observed)
        assertEquals(0.0, c.pct, 1e-12)
        // one history point is not a move; an unstamped 0/0 change pair is the default
        assertEquals(Source8348.UNOBSERVED, ContinuationRead8348.resolve8348(0.0, 0L, 0.0, 0.0, 1, null, null, 30.0).source)
    }

    @Test fun anObservedSmallChangeIsAReading() {
        val h = ContinuationRead8348.resolve8348(0.3, 0L, 0.0, 0.0, 0, null, null, 90.0)
        assertEquals(Source8348.FROM_1H, h.source); assertEquals(0.3, h.pct, 1e-12); assertTrue(h.observed)
        // a STAMPED 0.0 is a real flat read (LanePlaybook7907.chgObs8084)
        val flat = ContinuationRead8348.resolve8348(0.0, 1L, 0.0, 0.0, 0, null, null, 90.0)
        assertEquals(Source8348.FROM_1H, flat.source); assertTrue(flat.observed)
        val hist = ContinuationRead8348.resolve8348(0.0, 0L, 0.0, 0.4, 2, null, null, 0.2)
        assertEquals(Source8348.FROM_HISTORY, hist.source); assertEquals(0.4, hist.pct, 1e-12)
        val launch = ContinuationRead8348.resolve8348(0.0, 0L, 0.0, 0.0, 1, null, 12.0, 0.2)
        assertEquals(Source8348.FROM_LAUNCH, launch.source); assertEquals(12.0, launch.pct, 1e-12)
        val m5 = ContinuationRead8348.resolve8348(0.0, 0L, 2.5, 0.0, 0, null, null, 0.2)
        assertEquals(Source8348.FROM_5M, m5.source); assertEquals(2.5, m5.pct, 1e-12)
    }

    @Test fun aYoungCoinWithTapePrintsReadsTheTapeOverItsOwnAgeNotA1hField() {
        // 7 s old, tape says +40 % since launch, the provider 1 h field says 0.0 (it cannot exist yet)
        val young = ContinuationRead8348.resolve8348(0.0, 0L, 0.0, 0.3, 2, 40.0, 5.0, 7.0 / 60.0)
        assertEquals(Source8348.FROM_TAPE, young.source); assertEquals(40.0, young.pct, 1e-12)
        // even a non-zero 1 h field yields to the tape inside the launch hour
        assertEquals(Source8348.FROM_TAPE, ContinuationRead8348.resolve8348(9.0, 1L, 0.0, 0.0, 0, 40.0, null, 30.0).source)
        // past the launch hour the tape's "over age" read is not used; the 1 h change is the continuation
        val old = ContinuationRead8348.resolve8348(9.0, 1L, 0.0, 0.0, 0, 40.0, null, 61.0)
        assertEquals(Source8348.FROM_1H, old.source); assertEquals(9.0, old.pct, 1e-12)
        // an unknown age is not young
        assertFalse(ContinuationRead8348.youngCoin8348(null)); assertFalse(ContinuationRead8348.youngCoin8348(Double.NaN))
        assertTrue(ContinuationRead8348.youngCoin8348(0.0)); assertFalse(ContinuationRead8348.youngCoin8348(60.0))
    }

    @Test fun theTapeChangeOverAgeComesFromTheLaunchPriceBase() {
        val t = System.currentTimeMillis()
        val mint = "ContTapeMint8348aaaaaaaaaaaaaaaaaaaaaaaapump"
        com.lifecyclebot.network.PumpCurveKeys7269.rememberCreate7280(mint, 1.0e-7, t - 20_000L)
        TradeTape8100.note8100(mint, 1.2e-7, 0.5, true, t - 12_000L)
        TradeTape8100.note8100(mint, 1.5e-7, 0.4, true, t - 4_000L)
        val chg = ContinuationRead8348.tapeChangeOverAge8348(mint, t)
        assertNotNull(chg)
        assertEquals(50.0, chg!!, 1e-6)
        // no prints at all: nothing is stated (never a 0.0)
        assertNull(ContinuationRead8348.tapeChangeOverAge8348("ContNoTapeMint8348bbbbbbbbbbbbbbbbbbbbbbpump", t))
    }

    // ── the verdict: unobserved defers, observed refuses ──

    @Test fun unobservedMomentumDefersObservedSmallRefuses() {
        val d = ContinuationRead8348.deferMomentum8348("NO_CONTINUATION: 0.0% < 1.0% (learning=0%)", observed = false)
        assertNotNull(d)
        assertTrue(d!!.startsWith(LaneInputTruth8236.MOMENTUM_DEFER))
        assertNull(ContinuationRead8348.deferMomentum8348("NO_CONTINUATION: 0.3% < 1.0% (learning=0%)", observed = true))
        // other lanes' momentum mandates, same rule
        assertNotNull(ContinuationRead8348.deferMomentum8348("REJECTED_MOMENTUM: 0.0%", observed = false))
        assertNotNull(ContinuationRead8348.deferMomentum8348("mom+0.0 too weak", observed = false))
        assertNotNull(ContinuationRead8348.deferMomentum8348("NO_MOMENTUM: 0.0%", observed = false))
        // never safety / liquidity / an existing deferral
        assertNull(ContinuationRead8348.deferMomentum8348("LIQ_TOO_LOW: $400 no momentum", observed = false))
        assertNull(ContinuationRead8348.deferMomentum8348("RUGCHECK no_momentum", observed = false))
        assertNull(ContinuationRead8348.deferMomentum8348(LaneInputTruth8236.MOMENTUM_DEFER + ":NO_CONTINUATION", observed = false))
        assertNull(ContinuationRead8348.deferMomentum8348("WEAK_BUYS: 50% < 55%", observed = false))
    }

    @Test fun theDeskReviewDefersEveryLanesUnobservedMomentumRefusalAndCounts() {
        val out = linkedMapOf(
            "EXPRESS" to op("EXPRESS", "NO_CONTINUATION: 0.0% < 1.0% (learning=0%)"),
            "SHITCOIN" to op("SHITCOIN", "mom+0.0 below bar"),
            "CASHGEN" to op("CASHGEN", "REJECTED_MOMENTUM"),
            "MOONSHOT" to op("MOONSHOT", "rugcheck_0_below_min_15"),
            "QUALITY" to op("QUALITY", "QUALITY_NATIVE_ALLOW", eligible = true),
        )
        ContinuationRead8348.reviewMomentum8348(out, expressObserved = false, momObserved = false)
        assertTrue(out["EXPRESS"]!!.reason.startsWith(LaneInputTruth8236.MOMENTUM_DEFER))
        assertFalse(out["EXPRESS"]!!.gradeable)
        assertTrue(out["SHITCOIN"]!!.reason.startsWith(LaneInputTruth8236.MOMENTUM_DEFER))
        assertTrue(out["CASHGEN"]!!.reason.startsWith(LaneInputTruth8236.MOMENTUM_DEFER))
        assertEquals("rugcheck_0_below_min_15", out["MOONSHOT"]!!.reason)
        assertTrue(out["QUALITY"]!!.eligible)
        assertEquals(3L, ContinuationRead8348.counts8348()["unobservedDeferred"])
        assertEquals(0L, ContinuationRead8348.counts8348()["refusedObserved"])

        // observed: the refusal stands and is counted as a judgement; EXPRESS is read against the continuation, the rest against the shared momentum
        val out2 = linkedMapOf(
            "EXPRESS" to op("EXPRESS", "NO_CONTINUATION: 0.3% < 1.0% (learning=0%)"),
            "SHITCOIN" to op("SHITCOIN", "mom+0.0 below bar"),
        )
        ContinuationRead8348.reviewMomentum8348(out2, expressObserved = true, momObserved = false)
        assertEquals("NO_CONTINUATION: 0.3% < 1.0% (learning=0%)", out2["EXPRESS"]!!.reason)
        assertTrue(out2["EXPRESS"]!!.gradeable)
        assertTrue(out2["SHITCOIN"]!!.reason.startsWith(LaneInputTruth8236.MOMENTUM_DEFER))
        assertEquals(1L, ContinuationRead8348.counts8348()["refusedObserved"])
        assertTrue(ContinuationRead8348.statusLine8348().contains("continuation8348[fromTape=0 from1h=0"))
        assertTrue(ContinuationRead8348.statusLine8348().contains("unobservedDeferred=4 refusedObserved=1]"))
    }

    @Test fun theSharedMomentumInputIsObservedByStampHistoryNonZeroChangeOrTape() {
        val un = ContinuationRead8348.Continuation8348(0.0, Source8348.UNOBSERVED)
        assertFalse(ContinuationRead8348.momentumObserved8348(0.0, 0L, 1, un))
        assertTrue(ContinuationRead8348.momentumObserved8348(0.0, 1L, 1, un))
        assertTrue(ContinuationRead8348.momentumObserved8348(0.7, 0L, 1, un))
        assertTrue(ContinuationRead8348.momentumObserved8348(0.0, 0L, 2, un))
        assertTrue(ContinuationRead8348.momentumObserved8348(0.0, 0L, 0, ContinuationRead8348.Continuation8348(3.0, Source8348.FROM_TAPE)))
        assertFalse(ContinuationRead8348.momentumObserved8348(0.0, 0L, 0, ContinuationRead8348.Continuation8348(3.0, Source8348.FROM_LAUNCH)))
    }

    // ── the deferral reaches the hydration re-evaluation ──

    @Test fun theMomentumDeferralIsADataDeferralTheHydrationReevaluationAnswers() {
        assertTrue(ContinuationRead8348.dataDefer8348(LaneInputTruth8236.MOMENTUM_DEFER + ":NO_CONTINUATION: 0.0% < 1.0%"))
        assertTrue(ContinuationRead8348.dataDefer8348("LANE_LIQ_UNHYDRATED_DEFER_8140"))
        assertFalse(ContinuationRead8348.dataDefer8348("NO_CONTINUATION: 0.3% < 1.0%"))
        assertFalse(ContinuationRead8348.dataDefer8348(null))
        assertTrue(HandoffReceiverTasks8153.hydrationAnswers8345("DEFERRED:DEFERRED_NATIVE:" + LaneInputTruth8236.MOMENTUM_DEFER + ":NO_CONTINUATION: 0.0% < 1.0%"))
        assertEquals(HandoffReceiverTasks8153.Block8153.DATA,
            HandoffRefusal8190.classify8190(LaneInputTruth8236.MOMENTUM_DEFER + ":NO_CONTINUATION: 0.0% < 1.0%", false, false))
    }

    // ── the hook sites ──

    @Test fun theDeskReadsOneContinuationAndReviewsMomentumBeforeTheGrabLane() {
        val b = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("val cont8348 = ContinuationRead8348.read8348(ts, histMomentumPct7551, prices.size, ageMin,"))
        assertTrue(b.contains("if (prices.size < 2) ExpressMomentum8190.sinceLaunchPct8190(ts, ageMin) else null, now)"))
        assertTrue(b.contains("val expressMomentum7555 = cont8348.pct"))
        assertTrue(b.contains("?: (if (prices.size < 2 && cont8348.source == ContinuationRead8348.Source8348.FROM_TAPE) cont8348.pct else histMomentumPct7551)"))
        val review = "ContinuationRead8348.reviewMomentum8348(out, cont8348.observed, ContinuationRead8348.momentumObserved8348(ts.lastPriceChange5m, ts.priceChangeObservedAtMs8084, prices.size, cont8348))"
        assertTrue(b.contains(review))
        val i8236 = b.indexOf("LaneInputTruth8236.deferFor8236(lane, o.reason, inputs8236)")
        val i8348 = b.indexOf(review)
        val i8343 = b.indexOf("RunnerGrabLane8343.reviewNative8343(ts, out, now)")
        assertTrue(i8236 in 0 until i8348 && i8348 < i8343)
        // the native EXPRESS rule itself is unchanged: an observed read below the bar still refuses by name
        assertTrue(src("v3/scoring/ShitCoinExpress.kt").contains("return noRide(\"NO_CONTINUATION: \${effectiveMomentum.fmt(1)}% < \${fluidMinMomentum.fmt(1)}%"))
        // the deferral is remembered for hydration and answered by it
        assertTrue(src("engine/RunnerGrabLane8343.kt").contains("if (!o.eligible && o.authoritative && ContinuationRead8348.dataDefer8348(o.reason)) {"))
        assertTrue(src("engine/HandoffReceiverTasks8153.kt").contains("return ContinuationRead8348.dataDefer8348(b) || b.contains(\"LIQ\") || b.contains(\"DATA_INCOMPLETE\") ||"))
        assertTrue(src("engine/RunnerGrabLane8343.kt").contains("ContinuationRead8348.statusLine8348()"))
        // the read is from the tape's own launch-price base, no fabricated value
        assertTrue(src("engine/ContinuationRead8348.kt").contains("TradeTape8100.read8100(mint, nowMs)?.chg?.second?.takeIf { it.isFinite() }"))
    }
}
