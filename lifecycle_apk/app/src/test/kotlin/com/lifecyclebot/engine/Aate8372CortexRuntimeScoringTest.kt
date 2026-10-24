package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.junit.Assert.*
import org.junit.Test

/** Actual runtime score readers: Android unit suite only, not a replacement runtime in the JVM harness. */
class Aate8372CortexRuntimeScoringTest {
    private fun fused(edge: Double) = CortexLedger7885.Fused(
        edge, 0.0, 0.0, 100.0, 1.0, 1.0, 0.0, emptyList(), laneMeanAll8325 = 0.0,
    )

    @Test fun flatCalibrationDoesNotEraseDifferentTokenPredictionsIntoOneLaneVerdict() {
        val fit = CortexCalibration7901.fitNet8372((0 until 100).map { it.toDouble() }, List(100) { -3.0 })
        assertTrue(fit.flat)
        assertFalse(fit.calibrated)
        val yes = Cortex7885.calibratedRead8372(false, fused(15.0), 1.0, fit)
        val no = Cortex7885.calibratedRead8372(false, fused(-15.0), 1.0, fit)
        assertEquals(15.0, yes, 0.0)
        assertEquals(-15.0, no, 0.0)
        assertEquals(CortexScoreboard7885.Bucket.STRONG, CortexScoreboard7885.bucketOf(yes, 0.0, false))
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(no, 0.0, false))
    }

    @Test fun actualRuntimeForecastUsesNetExpectancyNotTheNegativeWinsorizedLogDiagnostic() {
        val scores = List(100) { 0.0 } + List(100) { 10.0 }
        val outcomes = List(100) { -20.0 } + List(100) { if (it % 10 == 0) 1_000.0 else -19.0 }
        val netFit = CortexCalibration7901.fitNet8372(scores, outcomes)
        val logFit = CortexCalibration7901.fitIso8366(scores, outcomes)
        assertTrue(netFit.calibrated)
        assertTrue(logFit.calibrated)
        val actual = Cortex7885.calibratedRead8372(false, fused(10.0), 1.0, netFit)
        val oldDiagnostic = Cortex7885.calibratedRead8366(false, fused(10.0), 1.0, logFit)
        assertEquals(82.9, actual, 1e-9)
        assertTrue("The old clipped log target rejects this positive arithmetic cohort", oldDiagnostic < 0.0)
        assertEquals(CortexScoreboard7885.Bucket.STRONG, CortexScoreboard7885.bucketOf(actual, 0.0, false))
    }
}
