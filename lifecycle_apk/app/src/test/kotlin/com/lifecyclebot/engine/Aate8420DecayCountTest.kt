package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DecayCount8420
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8420 — windowed win / runner counts fade with their records; runner magnitude is not capped at +300%. */
class Aate8420DecayCountTest {
    @Test fun aWinCountFallsAfterARunOfLosses() {
        val s = SpecialistMiner7972.Stat()
        repeat(150) { s.add(10.0, 10.0) }
        repeat(150) { s.add(-10.0, -10.0) }
        assertEquals(300, s.n); assertEquals(150, s.wins)
        repeat(3_000) { s.add(-10.0, -10.0) }
        assertEquals(300, s.n)
        assertTrue("150 wins must fade after 3,000 losses, was ${s.wins}", s.wins <= 1)
        assertTrue(s.mean() < -9.0)
    }

    @Test fun fadeConservesTheExpectedCount() {
        var c = 100; var carry = 0.0; val k = 300.0 / 301.0
        var expected = 100.0
        repeat(500) { DecayCount8420.fade(c, carry, k).let { (w, r) -> c = w; carry = r }; expected *= k }
        assertEquals(expected, c + carry, 1e-6)
        assertEquals(5 to 0.0, DecayCount8420.fade(5, 0.0, 1.0))   // no fade, unchanged
    }

    @Test fun runnerMagnitudeSurvives() {
        val a = SpecialistMiner7972.Stat(); a.add(500.0, 500.0)
        val b = SpecialistMiner7972.Stat(); b.add(600.0, 600.0)
        assertEquals(500.0, a.mean(), 1e-9); assertEquals(600.0, b.mean(), 1e-9)
    }
}

/** V5.0.8420 — the runner trail floor binds after the patience multiplier; near-target tightening never cuts a runner under it. */
class Aate8420RunnerTrailTest {
    @Test fun runnerFloorIsAppliedAfterPatience() {
        // 35-point floor: a 0.65 patience on a 20-point table must still give 35, never 35 * 0.65 = 22.75
        assertEquals(35.0, LaneHolds8418.trailFor8418("MOONSHOT", "MOONSHOT", 20.0 * 0.65), 1e-9)
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HoldingLogicLayer.kt").readText()
        // superseded by V5.0.8423 — the hold layer no longer reads the 35-POINT table for a runner lane at all: RunnerTrail8419
        // (35 % under the peak PRICE, ledger #34) owns the runner trail; the table x patience stays for the other desks.
        assertTrue(src.contains("RunnerTrail8419.holdLayerTrailPoints8419(mode, learningLane8384, params.trailingStopPct, ssiExitPatience6091, position.peakGainPct)"))
        assertTrue(!src.contains("LaneHolds8418.trailFor8418(mode, learningLane8384, params.trailingStopPct) * ssiExitPatience6091"))
        assertTrue(src.contains("val tighterStop = currentPnlPct - (trailingStopPct6091 * tighten8420)"))
    }
}
