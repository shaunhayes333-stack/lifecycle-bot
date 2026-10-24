package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryEconomics8329
import com.lifecyclebot.engine.truth.Kelly8365
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8365 — log-optimal Kelly over the empirical returns, fractional and sample-shrunk; one shrink; promotion keeps edges. */
class Aate8365KellyTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun runnerBook(): List<Double> = List(95) { -10.0 } + List(5) { 500.0 }

    @Test fun runnerBookFullKellyIsAboutThirtyOnePercent() {
        // 95 trades at -10 %, 5 at +500 %: d/df [0.95 ln(1 - 0.1 f) + 0.05 ln(1 + 5 f)] = 0 at f = 0.31.
        val r = runnerBook().map { it / 100.0 }.toDoubleArray()
        val fStar = Kelly8365.fullKelly8365(r)
        assertEquals(0.31, fStar, 0.005)
        assertTrue(Kelly8365.growth8365(fStar, r, null) > Kelly8365.growth8365(0.5, r, null))
        val tp = Kelly8365.twoPoint8365(0.155, 1.2355, 0.05)!!
        assertEquals(5.0, tp[0], 0.01)
        assertEquals(-0.10, tp[1], 0.01)
        assertEquals(0.5, Kelly8365.sampleShrink8365(30.0), 1e-12)
        // Quarter Kelly before the sample shrink ~ 0.0775; with n/(n+30) at n=100 ~ 0.0596.
        assertEquals(0.0775, Kelly8365.KELLY_FRACTION_8365 * fStar, 0.002)
        val s = Kelly8365.shareFromReturnsPct8365(runnerBook())
        assertEquals("live", s.src)
        assertEquals(100, s.n)
        assertEquals(100.0 / 130.0, s.nShrink, 1e-12)
        assertEquals(0.25 * 0.31 * 100.0 / 130.0, s.frac, 0.002)
        // The old mean-variance form gave this book ~0.009 of equity; the new share is ~6x that.
        assertTrue(s.frac > 0.05)
    }

    @Test fun theSameBookReadFromItsMomentsAndRunnerRateMatches() {
        val xs = runnerBook()
        val mean = xs.sum() / xs.size
        val variance = xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)
        val s = Kelly8365.shareFromMoments8365(mean, variance, xs.size.toDouble(), upsideP = 0.05)
        assertEquals(0.30, s.fStar, 0.01)
        assertTrue(s.frac > 0.05 && s.frac <= 0.30)
    }

    @Test fun aLosingRecordSizesZero() {
        val losing = List(10) { -10.0 } + List(10) { 5.0 }
        assertEquals(0.0, Kelly8365.fullKelly8365(losing.map { it / 100.0 }.toDoubleArray()), 0.0)
        assertEquals(0.0, Kelly8365.shareFromReturnsPct8365(losing).frac, 0.0)
        assertEquals(0.0, Kelly8365.shareFromMoments8365(-2.0, 400.0, 60.0).frac, 0.0)
        assertEquals(0.0, LiveRiskPolicy7807.kellyFrac8057(2.0, 3600.0, 61.0), 0.0)   // mean - SE <= 0: no size
        // Every loss clipped at -99 %: the search never leaves the domain.
        assertEquals(0.0, Kelly8365.fullKelly8365(doubleArrayOf(-5.0, -1.0, 0.01)), 0.0)
    }

    @Test fun aThinRecordIsShrunkAndMustClearOneStandardError() {
        val thin = List(3) { 40.0 } + listOf(-10.0)
        val thick = List(60) { 40.0 } + List(20) { -10.0 }
        val sThin = Kelly8365.shareFromReturnsPct8365(thin)
        val sThick = Kelly8365.shareFromReturnsPct8365(thick)
        assertEquals(4.0 / 34.0, sThin.nShrink, 1e-12)
        assertTrue("${sThin.frac} vs ${sThick.frac}", sThin.frac < sThick.frac)
        assertTrue(sThick.frac <= Kelly8365.MAX_EQUITY_FRAC_8365 + 1e-12)
        // mean - SE <= 0 on two wild closes: nothing.
        assertEquals(0.0, Kelly8365.shareFromReturnsPct8365(listOf(300.0, -90.0)).frac, 0.0)
        assertEquals(0.0, Kelly8365.sizedFrac8365(0.5, 1.0, 10.0, 1.0), 0.0)
        assertEquals(0.25, Kelly8365.sizedFrac8365(1.0, 1e9, 10.0, 1.0), 1e-6)   // f* <= 1 (no leverage): quarter <= 0.25
        assertEquals(0.30, Kelly8365.sizedFrac8365(2.0, 1e9, 10.0, 1.0), 1e-9)   // and never above 30 % of equity
    }

    @Test fun oneShrinkIsTheMinimumNotTheProduct() {
        assertEquals(0.35, Kelly8365.oneShrink8365(0.85, 0.90, 0.35, 0.85), 1e-12)
        assertEquals(1.0, Kelly8365.oneShrink8365(1.2, Double.NaN), 1e-12)
        assertEquals(0.40 * 1.10, Kelly8365.shrinkThenBoost8365(0.40, 0.50, 1.10, 0.60), 1e-12)
        // A size already multiplied by every factor is lifted back to min x boosts.
        val f = doubleArrayOf(0.85, 0.70, 1.20, 0.80)
        val product = f.fold(1.0) { a, b -> a * b }
        assertEquals(0.70 * 1.20, product * Kelly8365.undoStackedShrink8365(*f), 1e-12)
        assertEquals(1.0, Kelly8365.undoStackedShrink8365(1.0, 1.3), 1e-12)
        // The live admission: three soft unknowns read 0.85, not 0.85 x 0.90 x 0.85.
        val (m, reasons) = LiveRiskPolicy7807.admissionOne8365(LiveRiskPolicy7807.Admission(
            liveCloses = 0, liveWrPct = Double.NaN, partialProviderEvidence = true, oracleUnproven = true, highEvRunner = false))
        assertEquals(0.85, m, 1e-12)
        assertTrue("PARTIAL_PROVIDER_EVIDENCE_7807" in reasons && "ORACLE_ADVISORY_UNPROVEN_7807" in reasons)
    }

    @Test fun promotionStopsFlatteningEdges() {
        // Below 25 % of the minimum with no named reason: refused by name.
        assertEquals("KELLY_TOO_SMALL_FOR_ROUTE_8365",
            EntryEconomics8329.kellyRefusal8365(0.005, 0.0293, EntryEconomics8329.PROMOTION_UNSUPPORTED_8329 + "_QUALITY"))
        // A named 8329 reason (record positive / early runner / exploration slot): promoted.
        assertNull(EntryEconomics8329.kellyRefusal8365(0.005, 0.0293, null))
        // At or above 25 % of the minimum: promoted.
        assertNull(EntryEconomics8329.kellyRefusal8365(0.0080, 0.0293, EntryEconomics8329.PROMOTION_UNSUPPORTED_8329 + "_QUALITY"))
        assertTrue(Kelly8365.stakeNearRoute8365(0.25 * 0.0293, 0.0293))
        val rp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(rp.contains("EntryEconomics8329.kellyRefusal8365(size, executableFloor8269, EntryEconomics8329.promotionReasonRefusal8329(i.mint8329, i.lane, size, executableFloor8269))"))
        // Above the minimum the Kelly size is used (inside the caps): the compounding lift is unchanged.
        assertTrue(rp.contains("if (kellySol > size) { size = kellySol; labels += \"PROVEN_EDGE_COMPOUNDING_SIZE_8057\" }"))
    }

    @Test fun wired() {
        val rp = src("engine/truth/LiveRiskPolicy7807.kt")
        assertTrue(rp.contains("Kelly8365.shareFromMoments8365(meanPct, variancePct2, n).frac"))
        assertTrue(rp.contains("val share8365 = Kelly8365.setupShare8365(ts, l, st)"))
        assertTrue(rp.contains("Kelly8365.oneShrink8365(shrink, admMult, ddMult, moonMult, costMult) * growth"))
        assertTrue(rp.contains("val dampMult8094 = Kelly8365.oneShrink8365(ddMult, dailyMult, gov)"))
        assertTrue(rp.contains("Kelly8365.statusLine8365()"))
        assertTrue(src("v3/sizing/SmartSizerV3.kt").contains("Kelly8365.shrinkThenBoost8365(confMult, probeMult, liqMult, ddMult, learningMult, v3ConfigMult)"))
        assertTrue(src("engine/SmartSizer.kt").contains("if (!isPaperMode) size *= com.lifecyclebot.engine.truth.Kelly8365.undoStackedShrink8365("))
        val k = src("engine/truth/Kelly8365.kt")
        assertTrue(k.contains("if (live.size >= LIVE_MIN_N_8365) shareFromReturnsPct8365(live, \"live\")"))
        assertTrue(Kelly8365.statusLine8365().startsWith("size8365["))
    }
}
