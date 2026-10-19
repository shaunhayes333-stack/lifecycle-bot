package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryLearningExclusion8239
import org.junit.Assert.*
import org.junit.Test

class Aate8239LearningExclusionTest {
    private fun reason(consumer: String = "MemeCausalLearning6568", ready: Boolean = true,
                       failed: Boolean = false, age: Long = 180_000L) =
        EntryLearningExclusion8239.reason8239(consumer, "exact-position", "mint", "LIVE", age, ready, failed, null)

    @Test fun aMissingRestoredEntryIsNamedWithoutInventingFeatures() {
        assertEquals("ENTRY_FEATURES_UNOBSERVED_8239", reason())
    }

    @Test fun aTemporaryLoadFailureOrRecentEntryCanRetry() {
        assertNull(reason(ready = false))
        assertNull(reason(failed = true))
        assertNull(reason(age = 119_999))
        assertNull(reason(age = -1))
    }

    @Test fun theSameCloseStillReachesFinancialConsumers() {
        listOf("Dashboard", "KillSwitch7835", "RewardPurity", "CausalFeedback6715", "CostLedger7962", "EVEstimator")
            .forEach { assertNull(it, reason(consumer = it)) }
    }
}
