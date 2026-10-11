package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveTruth8064
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8064 — labels credit only what live can bank, and the live book has the last word. */
class Aate8064HonestLabelsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun noSpikeCredit() {
        // foff: +276% peak, back to 0 at the read -> the label is 0, not the +50% the ladder promised.
        assertEquals(0.0, ForwardReturnLabeler7731.spikeCredit8064(276.0, 0.0), 1e-9)
        assertEquals(-12.0, ForwardReturnLabeler7731.spikeCredit8064(40.0, -12.0), 1e-9)
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("{ pk, rest -> spikeCredit8064(pk, rest) }"))
    }

    @Test fun freshBooks() {
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("CELLS_KEY_7946 = \"cells_8064\""))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("\"LANE_PLAYBOOK_8064\""))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("\"CORTEX_7885_V8064\""))
        assertTrue(src("engine/truth/SpecialistMiner7972.kt").contains("\"specialists8064.bin\""))
        assertTrue(src("engine/truth/HiveEdge8000.kt").contains("\"hive_edge_8064\""))
    }

    @Test fun liveBookLosingHoldsTheLane() {
        assertTrue(LiveTruth8064.provenLosing8064(listOf(-12.0, -5.0, -20.0, 4.0, -7.0, -13.0, -6.0, -9.0)))
        assertFalse(LiveTruth8064.provenLosing8064(listOf(-12.0, -5.0, -20.0)))                       // too few
        assertFalse(LiveTruth8064.provenLosing8064(listOf(-12.0, 80.0, -20.0, 4.0, -7.0, 60.0, -6.0, -9.0)))  // runners pay
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("LiveTruth8064.refusal8064(l8059, nowMs)"))
        assertFalse(g.contains("TailHunter7996.ticket7996(ts, lane, nowMs) } catch (_: Throwable) { false }) ||"))
    }
}
