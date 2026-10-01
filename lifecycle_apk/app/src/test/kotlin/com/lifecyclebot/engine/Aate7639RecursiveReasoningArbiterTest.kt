
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7639RecursiveReasoningArbiterTest {
    @Test fun arbiterReweightsExistingReasonersOnly() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperReasoningArbiter7639.kt").readText()
        assertTrue(s.contains("worldWeight"))
        assertTrue(s.contains("criticWeight"))
        assertTrue(s.contains("memoryWeight"))
        assertTrue(s.contains("treeWeight"))
        assertTrue(!s.contains("executeBuy"))
        assertTrue(!s.contains("FinalDecisionGate.evaluate"))
    }

    @Test fun plannerConsumesArbiterWeights() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligencePlanner7633.kt").readText()
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(p.contains("arbiter: SuperReasoningArbiter7639.Decision? = null"))
        assertTrue(p.contains("arbiter?.criticWeight"))
        assertTrue(p.contains("arbiter?.treeWeight"))
        assertTrue(o.contains("SuperReasoningArbiter7639.arbitrate("))
        assertTrue(o.contains("contributions += arbiter7639.contributionTag()"))
    }
}
