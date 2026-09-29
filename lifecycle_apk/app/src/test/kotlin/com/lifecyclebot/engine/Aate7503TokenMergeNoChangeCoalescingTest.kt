package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7503TokenMergeNoChangeCoalescingTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/TokenMergeQueue.kt").readText()

    @Test fun identical_repeat_exits_before_confidence_recompute() {
        val s = src()
        val fn = s.substringAfter("if (existing != null)").substringBefore("} else {")
        val coalesce = fn.indexOf("MERGE_REPEAT_NO_NEW_EVIDENCE_COALESCED_7503")
        val confidence = fn.indexOf("calculateMergedConfidence(")
        assertTrue(coalesce >= 0)
        assertTrue(confidence > coalesce)
    }

    @Test fun meaningful_evidence_paths_are_preserved() {
        val s = src()
        val fn = s.substringAfter("if (existing != null)").substringBefore("} else {")
        assertTrue(fn.contains("scannerAdded7503"))
        assertTrue(fn.contains("affinityAdded7503"))
        assertTrue(fn.contains("metricsImproved7503"))
        assertTrue(fn.contains("existing.discoveryCount++"))
    }
}
