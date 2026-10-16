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
        // V5.0.7432/7544 — history is separated from a current publish failure by
        // durable terminal evidence, not by comparing timestamps to the bus window:
        // only a repairable durable full SELL is BUS_PUBLISH_FAILED.
        assertTrue(src.contains("earliestCanonicalAtMs7433()"))
        assertTrue(src.contains("src.contains(\"LEGACY_REPLAY\") || src.contains(\"REPLAY_CARRY\") -> Reason.LEGACY_REPLAY"))
        assertTrue(src.contains("repairable7544 -> Reason.BUS_PUBLISH_FAILED"))
        assertTrue(src.contains("terminal7544 != null -> Reason.DURABLE_TERMINAL_UNREPAIRABLE"))
        assertTrue(src.contains("p.positionId.isNotBlank() -> Reason.HISTORICAL_NO_DURABLE_FINALITY"))
    }
}
