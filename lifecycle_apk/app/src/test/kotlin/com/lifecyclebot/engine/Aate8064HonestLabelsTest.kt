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
        // V5.0.8071 — the ladder now credits the level the price HELD for 20 s, never the raw one-print peak.
        // superseded by V5.0.8366 — the held level is the one held for the measured sell latency (o.heldPeak8366) on observations
        // opened by this build; restored ones keep the 8071 20 s level. Still never the raw one-print peak.
        val lab8366 = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab8366.contains("heldCredit8071(minOf(pk, held8366), rest, tiers8071)"))
        assertTrue(lab8366.contains("val held8366 = if (o.honest8366) o.heldPeak8366 else o.heldPeak8071"))
    }

    @Test fun freshBooks() {
        // superseded by V5.0.8350 — grades price on the depth a curve trades against (allInCostPct8350); fresh books cells_8350
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("CELLS_KEY_7946 = \"cells_8350\""))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("\"LANE_PLAYBOOK_8076\""))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("\"CORTEX_7885_V8076\""))
        assertTrue(src("engine/truth/SpecialistMiner7972.kt").contains("\"specialists8076.bin\""))
        assertTrue(src("engine/truth/HiveEdge8000.kt").contains("\"hive_edge_8076\""))
    }

    @Test fun liveBookLosingHoldsTheLane() {
        assertTrue(LiveTruth8064.provenLosing8064(listOf(-12.0, -5.0, -20.0, 4.0, -7.0, -13.0, -6.0, -9.0)))
        assertFalse(LiveTruth8064.provenLosing8064(listOf(-12.0, -5.0, -20.0)))                       // too few
        assertFalse(LiveTruth8064.provenLosing8064(listOf(-12.0, 80.0, -20.0, 4.0, -7.0, 60.0, -6.0, -9.0)))  // runners pay
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("LiveTruth8064.lanePrior8083(l8059, nowMs)"))   // V5.0.8083: prior, not a lane-wide hold
        assertFalse(g.contains("TailHunter7996.ticket7996(ts, lane, nowMs) } catch (_: Throwable) { false }) ||"))
    }
}
