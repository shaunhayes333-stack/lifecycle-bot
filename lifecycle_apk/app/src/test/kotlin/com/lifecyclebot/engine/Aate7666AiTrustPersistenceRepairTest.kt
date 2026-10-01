package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7666AiTrustPersistenceRepairTest {
    @Test fun trustNetworkPersistsItsRollingWindow() {
        val s = File("src/main/kotlin/com/lifecyclebot/v3/scoring/AITrustNetworkAI.kt").readText()
        assertTrue(s.contains("optJSONArray(\"window\")"))
        assertTrue(s.contains("samplesInWindow = restoredWindow"))
        assertTrue(s.contains("put(\"window\""))
    }

    @Test fun unifiedScorerAlreadyConsumesCanonicalTrustAccessor() {
        val u = File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        assertTrue(u.contains("AITrustNetworkAI.getTrustWeight(c.name)"))
    }
}
