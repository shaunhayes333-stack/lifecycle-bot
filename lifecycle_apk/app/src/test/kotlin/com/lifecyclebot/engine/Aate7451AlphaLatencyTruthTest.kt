package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7451AlphaLatencyTruthTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun latency_authority_separates_discovery_from_internal_pipeline() {
        val s = src("engine/truth/AlphaLatencyTruth7451.kt")
        assertTrue(s.contains("birthToObservationMs"))
        assertTrue(s.contains("intakeToFdgMs"))
        assertTrue(s.contains("intakeToSubmitMs"))
        assertTrue(s.contains("intakeToConfirmedMs"))
        assertTrue(s.contains("BURST_GAP_MS"))
    }

    @Test fun causal_boundaries_stamp_the_same_latency_authority() {
        assertTrue(src("engine/TokenMergeQueue.kt").contains("AlphaLatencyTruth7451.markIntake"))
        assertTrue(src("engine/FinalDecisionGate.kt").contains("AlphaLatencyTruth7451.markFdg"))
        val e = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertTrue(e.contains("AlphaLatencyTruth7451.markSubmit"))
        assertTrue(e.contains("AlphaLatencyTruth7451.markConfirmed"))
    }

    @Test fun v3_no_longer_assumes_healthy_feed_and_100ms_latency() {
        val s = src("v3/V3EngineManager.kt")
        val region = s.substringAfter("val measuredLatency7451").substringBefore("val localOrchestrator")
        assertTrue(region.contains("AlphaLatencyTruth7451.feedHealthy"))
        assertTrue(region.contains("latencyMs = measuredLatency7451"))
        assertFalse(region.contains("feedsHealthy = true"))
        assertFalse(region.contains("latencyMs = 100"))
    }
}
