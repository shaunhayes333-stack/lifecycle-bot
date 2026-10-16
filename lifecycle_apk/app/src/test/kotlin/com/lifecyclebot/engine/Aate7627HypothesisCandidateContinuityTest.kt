package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7627HypothesisCandidateContinuityTest {
    @Test fun `hypothesis decision stamp accepts caller candidate generation`() {
        val h = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        val fn = h.substringAfter("fun getSizeBias(").substringBefore("fun peekSizeBias(")
        assertTrue(fn.contains("candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("val cv7428 = candidateVersion"))
        assertTrue(!fn.contains("val cv7428 = LaneExecutionCoordinator.candidateVersionFor(mint)"))
    }

    @Test fun `fdg passes its pinned generation into hypothesis stamp`() {
        val f = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        // The argument list now contains nested calls (e.g. `.toInt()`), so bound the
        // slice at the call's closing line rather than the first ')'.
        val i = f.substringAfter("StrategyHypothesisEngine.getSizeBias(")
            .substringBefore("\n                        )\n")
        assertTrue(i.contains("candidateVersion7623"))
    }
}
