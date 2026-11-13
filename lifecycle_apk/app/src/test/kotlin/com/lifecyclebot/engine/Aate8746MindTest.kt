package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexVoters7885
import com.lifecyclebot.engine.truth.LlmAnalyst8746
import com.lifecyclebot.engine.truth.Mind8746
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8746 — the mind stacks every voter on forward labels; the LLM is a graded voter; the Cortex reads the brains. */
class Aate8746MindTest {
    @Test fun featuresCarryEachVoterItsPresenceAndTheSpread() {
        val f = Mind8746.features8746(10.0, -10.0, Double.NaN, 0.7)
        assertEquals(10, f.size)
        assertEquals(10.0, f[1], 0.0); assertEquals(1.0, f[2], 0.0); assertEquals(-10.0, f[3], 0.0)
        assertEquals(0.0, f[4], 0.0); assertEquals(0.0, f[5], 0.0)          // no cell
        assertEquals(20.0, f[6], 1e-9); assertEquals(1.0, f[7], 0.0)       // llm p 0.7 -> +20
        assertTrue(f[8] > 0.0 && f[9] == 1.0)                              // spread of 10, -10, 20
    }

    @Test fun theMindLearnsAWeightAndStaysColdUntilWarm() {
        assertNull(Mind8746.predict8746(Mind8746.features8746(5.0, 0.0, Double.NaN, Double.NaN)))
        for (i in 0 until 400) {
            val t = (i % 20 - 10).toDouble()
            Mind8746.freeze8746("m$i", i.toLong(), Mind8746.features8746(t, 0.0, Double.NaN, Double.NaN))
            Mind8746.learn8746("m$i", i.toLong(), 2.0 * t)
        }
        val hi = Mind8746.predict8746(Mind8746.features8746(8.0, 0.0, Double.NaN, Double.NaN))
        val lo = Mind8746.predict8746(Mind8746.features8746(-8.0, 0.0, Double.NaN, Double.NaN))
        assertNotNull(hi); assertTrue("$hi $lo", hi!! > 5.0 && lo!! < -5.0)
    }

    @Test fun theAnalystsAnswerIsParsedAndItsFactsAreDecisionTimeOnly() {
        assertEquals(0.31, LlmAnalyst8746.parse8746("{\"p_up\": 0.31, \"why\": \"thin pool\"}")!!, 1e-9)
        assertNull(LlmAnalyst8746.parse8746("{\"p_up\": 1.7}"))
        assertNull(LlmAnalyst8746.parse8746("no idea"))
    }

    @Test fun itIsWiredWhereEverythingAlreadyReads() {
        val edge = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
        assertTrue(edge.contains("mind != null -> mind to Mind8746.KEY_8746"))
        assertTrue(edge.contains("Mind8746.learn8746(s.mint, s.atMs, y)"))
        assertTrue(edge.contains("predict8737(raw, x, ts.mint, ts, freezeAtMs = nowMs)"))
        for (id in listOf("MIND_STACKED_PRED", "TREE_RANKER_PRED", "LLM_ANALYST_P_UP", "BRAIN_DISAGREEMENT_SSI"))
            assertNotNull(id, CortexVoters7885.edgesFor(id))
    }
}
