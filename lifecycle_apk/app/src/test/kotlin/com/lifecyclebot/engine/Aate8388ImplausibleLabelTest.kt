package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import org.junit.Assert.*
import org.junit.Test

class Aate8388ImplausibleLabelTest {
    private fun rec(id: String, lane: String = "SHITCOIN") =
        CortexGradeRecord8144('D', id, "$id|$lane", 1_000_000L, lane, "LAUNCH", "R", "T", "C", "SHITCOIN|LAUNCH", 2, 5.0, true, true, false)
            .also { it.scoreVersion8372 = 1 }

    @Test fun implausibilityIsPureAndNeverRefusesAWitnessedValue() {
        val b = CortexGradeBook8144()
        assertTrue(b.implausible8388(3.348e10, 1000.0, witnessed = false))
        assertTrue(b.implausible8388(10_001.0, 1000.0, witnessed = false))
        assertFalse(b.implausible8388(10_000.0, 1000.0, witnessed = false))   // at the multiple: kept (no win caps)
        assertFalse(b.implausible8388(3.348e10, 1000.0, witnessed = true))
        assertFalse(b.implausible8388(-99.9, 1000.0, witnessed = false))      // losses are never refused by magnitude
        assertFalse(b.implausible8388(Double.NaN, 1000.0, witnessed = false))
    }

    @Test fun aLiveLabelWithAnImplausibleUnwitnessedValueIsObservedOnly() {
        val b = CortexGradeBook8144()
        val r = rec("m1|SHITCOIN|1000000"); assertTrue(b.addDecision(r))
        assertTrue(b.label(r.id, netPct = 3.3e10, grossPct = 3.3e10, executableValue = 3.3e10, executableAllowed = true, availableAtMs = 1_300_000L))
        assertFalse(r.executablePct.isFinite())
        assertEquals(3.3e10, r.observedNetPct, 0.0)
        assertEquals(1L, b.implausibleRefused8388)
        val stats = b.bucketStats8372("SHITCOIN")
        assertEquals(0.0, stats.sumOf { it.n }, 0.0)
        assertEquals(0, b.rankRows8324("SHITCOIN").size)
        // a plausible later label still becomes the first executable target
        assertTrue(b.label(r.id, 42.0, 60.0, 42.0, true, 1_400_000L))
        assertEquals(42.0, r.executablePct, 0.0)
    }

    @Test fun restoreSanitizesHistoricalCorruptedRowsWithoutDroppingThem() {
        val src = CortexGradeBook8144()
        val bad = rec("bad|SHITCOIN|1000000"); src.addDecision(bad)
        src.label(bad.id, 50.0, 60.0, 50.0, true, 1_300_000L)
        bad.executablePct = 3.348e10   // the 8385 corruption, written before 8386/8388 existed
        val good = rec("good|SHITCOIN|1000001"); src.addDecision(good)
        src.label(good.id, 25.0, 30.0, 25.0, true, 1_300_001L)
        val restored = CortexGradeBook8144(); restored.decode(src.encode())
        val stats = restored.bucketStats8372("SHITCOIN")
        assertEquals(1.0, stats.sumOf { it.n }, 0.0)
        assertTrue(stats.all { it.n == 0.0 || it.sum / it.n < 1000.0 })
        assertEquals(1L, restored.implausibleRefused8388)
        assertEquals(1, restored.rankRows8324("SHITCOIN").size)          // the corrupted row is out of every graded cohort
        assertNotNull(restored.decision(bad.id))                                 // but it stays in the book as an observation
        assertFalse(restored.decision(bad.id)!!.executablePct.isFinite())
    }
}
