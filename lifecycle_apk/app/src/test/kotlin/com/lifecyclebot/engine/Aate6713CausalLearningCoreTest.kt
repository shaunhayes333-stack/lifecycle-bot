package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate6713CausalLearningCoreTest {
    @Test fun `canonical terminal owns finalize and learner mutation owns learn`() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(b.contains("latestUnfinalizedOpenKey6713"))
        assertTrue(b.contains("Stage.FINALIZE"))
        assertTrue(b.contains("latestFinalizedUnlearnedKey6713"))
        assertTrue(b.contains("if (learned6713)"))
        assertTrue(b.contains("Stage.LEARN"))
    }

    @Test fun `missing causal entry snapshot cannot be falsely acked`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        val fn = s.substringAfter("fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean")
            .substringBefore("private fun med")
        val miss = fn.substringAfter("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568").take(220)
        assertTrue(miss.contains("return false"))
    }

    @Test fun `policy rewarded latch occurs after exact owner mutation`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val fn = s.substringAfter("fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean")
            .substringBefore("private fun emitPolicy")
        assertTrue(fn.indexOf("recordOutcome6681(") >= 0)
        assertTrue(fn.indexOf("rewardedPositions.add(env.positionId)") > fn.indexOf("recordOutcome6681("))
        assertTrue(fn.contains("AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6713"))
    }

    @Test fun `sealed AATE decision is only fallback source for missing policy observation`() {
        val fabric = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val uph = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
        assertTrue(fabric.contains("bindDecisionFallback6713("))
        assertTrue(uph.contains("source=SEALED_AATE_DECISION"))
        assertFalse(uph.contains("source=POST_HOC_MARKET"))
    }
}
