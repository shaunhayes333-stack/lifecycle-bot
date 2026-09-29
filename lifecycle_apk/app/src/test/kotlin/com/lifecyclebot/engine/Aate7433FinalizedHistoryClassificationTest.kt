package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7433FinalizedHistoryClassificationTest {
    @Test fun finalizedBusExposesHistoryLowerBound() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        assertTrue(src.contains("fun earliestCanonicalAtMs7433()"))
        assertTrue(src.contains("it.atMs"))
        assertTrue(src.contains("minOrNull()"))
    }

    @Test fun reconcilerSeparatesPreBusHistoryFromCurrentPublishFailure() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        assertTrue(src.contains("earliestCanonicalAtMs7433()"))
        assertTrue(src.contains("predatesSurvivingBus7433"))
        assertTrue(src.contains("p.lastMutationMs < earliestBusAt7433"))
        assertTrue(src.contains("predatesSurvivingBus7433 -> Reason.LEGACY_REPLAY"))
        assertTrue(src.contains("p.positionId.isNotBlank() -> Reason.BUS_PUBLISH_FAILED"))
    }
}
