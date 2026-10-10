package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HiveEdge8000
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8000HiveBrainTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun onlyTheChangeIsUploaded() {
        val local = doubleArrayOf(30.0, 120.0, 900.0, 12.0, 3.0, 0.0)
        val sent = doubleArrayOf(25.0, 100.0, 800.0, 10.0, 2.0, 0.0)
        assertArrayEquals(doubleArrayOf(5.0, 20.0, 100.0, 2.0, 1.0, 0.0), HiveEdge8000.delta8000(local, sent), 1e-9)
        assertNull(HiveEdge8000.delta8000(sent, sent))                         // nothing changed
        assertNull(HiveEdge8000.delta8000(doubleArrayOf(3.0, 1.0, 1.0, 1.0, 0.0, 0.0), sent))   // reset: re-based, never negative
    }

    @Test fun ownEvidenceIsNotCountedTwice() {
        val total = doubleArrayOf(100.0, 400.0, 9000.0, 40.0, 9.0, 3200.0)
        val mine = doubleArrayOf(30.0, 120.0, 900.0, 12.0, 3.0, 0.0)
        val others = HiveEdge8000.othersOf8000(total, mine)
        assertEquals(70.0, others[0], 1e-9); assertEquals(280.0, others[1], 1e-9); assertEquals(6.0, others[4], 1e-9)
    }

    @Test fun everyBookReadsTheHive() {
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("HiveEdge8000.net8000(\"FRL|\$key\")"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("HiveEdge8000.net8000(\"PB|\$lane|\$setup\")"))
        assertTrue(src("engine/truth/SpecialistMiner7972.kt").contains("fun hivePromote8000(): Int"))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("HiveEdge8000.noteRunner8000(o.mint, o.symbol, gross, o.atMs)"))
    }
}
