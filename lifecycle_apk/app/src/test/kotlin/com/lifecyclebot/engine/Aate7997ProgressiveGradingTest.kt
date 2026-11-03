package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import com.lifecyclebot.engine.truth.TailHunter7996
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7997ProgressiveGradingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun revisionReplacesNeverDoubleCounts() {
        val s = CortexLedger7885.Stat()
        s.add(-9.0, false); s.add(4.0, false)
        s.revise(-9.0, 250.0, false, true)                 // the -9% launch went on to run
        assertEquals(2.0, s.n, 1e-12)
        assertEquals(254.0, s.sum, 1e-9)
        assertEquals(1.0, s.runners, 1e-12)
        assertEquals(2.0, s.wins, 1e-12)
        val m = SpecialistMiner7972.Stat()
        m.add(-9.0, -9.0); m.revise(-9.0, 120.0, -9.0, 400.0)
        assertEquals(1, m.n); assertEquals(120.0, m.sum, 1e-9); assertEquals(1, m.runners); assertEquals(1, m.wins)
    }

    @Test fun checkpointsKeepPathLearningWithoutRetargetingTheEntryBooks() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("val stop7997 = if (o.done60) checkpoint7997(o, net, gross, observedAge8521, nowMs) else false"))
        val checkpoint = f.substringAfter("private fun checkpoint7997(").substringBefore("internal fun revisionStageGiveUp8418(")
        assertTrue(checkpoint.contains("captured7945(o, net0, gross0)"))
        assertTrue(checkpoint.contains("worthWatchingNow8040(o, gross0)"))
        assertFalse(checkpoint.contains("o.bookedNet7997 ="))
        assertFalse(checkpoint.contains(".reviseLabel7997("))
        val target = f.substringAfter("private fun bookEntryTarget8521(").substringBefore("private fun teachForwardLearners8521(")
        assertTrue(target.contains("o.bookedNet7997.isFinite()"))
        assertTrue(target.contains("teachForwardLearners8521(o, net, gross, nowMs)"))
    }

    @Test fun cryptoRidesTheSwingLadder() {
        val l = TailHunter7996.Ladder7996(100.0, crypto = true)
        for (p in listOf(105.0, 115.0, 140.0, 150.0, 119.0)) l.onPrice(p)
        assertTrue(l.done)   // out 20% below the 1.5x peak
        // 0.5x1.15 + 0.175x1.40 + 0.325x1.19 = 1.20675 -> +20.7% - 3% cost
        assertEquals(17.675, l.resultPct(), 1e-6)
    }
}
