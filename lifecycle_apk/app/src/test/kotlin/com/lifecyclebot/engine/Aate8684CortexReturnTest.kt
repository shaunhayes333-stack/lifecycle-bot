package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.CanonicalIntegrityGuards6449
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8684 — the Cortex returns in its bounded form, and the open items from the 5.0.8680 review are closed:
 * capital-conservation noise, backfilled buys losing their decision score, and top-holder blocks that only fired after a
 * ticket had been spent.
 */
class Aate8684CortexReturnTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun productionRunsTheBoundedGraphAndAdmittedDecisionsAreSampled() {
        assertEquals("sampled", Cortex7885.graphMode8684(null))
        assertEquals("sampled", Cortex7885.graphMode8684("  "))
        assertEquals("on", Cortex7885.graphMode8684("on"))
        assertFalse(Cortex7885.voterGraphRetired8623(Cortex7885.graphMode8684(null)))
        assertTrue(Cortex7885.voterGraphBounded8644(Cortex7885.graphMode8684(null)))
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("(reserveSample8644() || (admitted && reserveAdmitted8684()))"))
        assertTrue(c.contains("CortexSampleBudget8871(GRAPH_SAMPLE_GAP_MS_8644)"))
        assertTrue(c.contains("CortexSampleBudget8871(ADMITTED_SAMPLE_GAP_MS_8684)"))
        assertTrue(c.contains("if (pending.size >= pendingCap8684()) { inc(\"PENDING_FULL\"); return }"))
        assertTrue("persisted pending stays capped", c.contains("private const val PENDING_PERSIST_MAX = 128"))
    }

    @Test fun conservationToleranceScalesWithTheBook() {
        assertEquals(1e-4, CanonicalIntegrityGuards6449.toleranceFor8684(1e-4, 1.0, 1.0), 1e-12)
        assertEquals(175e-5, CanonicalIntegrityGuards6449.toleranceFor8684(1e-4, 175.0, 174.9997), 1e-12)
        assertTrue("8680's 0.000275 SOL drift on 175 SOL is not a violation", 0.000275 < CanonicalIntegrityGuards6449.toleranceFor8684(1e-4, 175.0, 175.0))
        assertTrue("a real 0.01 SOL leak still is", 0.01 > CanonicalIntegrityGuards6449.toleranceFor8684(1e-4, 175.0, 175.0))
        assertEquals(1e-4, CanonicalIntegrityGuards6449.toleranceFor8684(1e-4, Double.NaN, Double.NaN), 1e-12)
    }

    @Test fun backfilledBuysKeepTheirScoreAndHolderBlocksRefuseBeforeATicket() {
        assertTrue(src("engine/truth/AccountingIntegrity7948.kt").contains("score = score8684,"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("PreTradeHardGate.holderBlock8684(ts, nowMs)"))
        val gate = src("engine/PreTradeHardGate.kt")
        assertTrue("the buy door keeps its own check", gate.contains("return block(ts, \"TOP_HOLDER_CONCENTRATION\""))
        // superseded by V5.0.9020: one read of the launch facts, judged net of the owner's named traders (holderBlockFor9020)
        assertTrue(gate.contains("holderBlockFor9020(holderProof8960(ts, ts.safety, nowMs).adversePct, ts.mint)"))
    }
}
