package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7535PredictiveResetCompletenessTest {
    @Test fun reset_learning_clears_admission_affecting_predictive_authorities() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue(p.contains("OracleEdgeProof7263.resetAllLearning7535()"))
        assertTrue(p.contains("UnifiedPolicyHead.resetAllLearning7535()"))
        assertFalse(p.contains("UnifiedPolicyHead has no reset()"))
    }

    @Test fun oracle_reset_clears_tallies_stamps_and_persistence() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/OracleEdgeProof7263.kt").readText()
        val fn = s.substringAfter("fun resetAllLearning7535()").substringBefore("/** Called by PredictiveEntryOracle6915")
        assertTrue(fn.contains("stamps.clear()"))
        assertTrue(fn.contains("admit.clear7535()"))
        assertTrue(fn.contains("refuse.clear7535()"))
        assertTrue(fn.contains("tier = Tier.ADVISORY"))
        assertTrue(fn.contains("prefs7287?.edit()?.clear()?.commit()"))
    }

    @Test fun unified_policy_reset_clears_model_pending_counters_and_standalone_prefs() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
        val fn = s.substringAfter("fun resetAllLearning7535()").substringBefore("fun exportState()")
        assertTrue(fn.contains("resetModelState6681()"))
        assertTrue(fn.contains("obsCount7389 = 0L"))
        assertTrue(fn.contains("causalBoundCount6681.set(0L)"))
        assertTrue(fn.contains("getSharedPreferences(\"unified_policy_head\""))
        assertTrue(fn.contains("clear()?.commit()"))
    }
}
