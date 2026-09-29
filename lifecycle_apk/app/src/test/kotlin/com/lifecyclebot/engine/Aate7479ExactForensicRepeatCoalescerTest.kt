package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7479ExactForensicRepeatCoalescerTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/ForensicLogger.kt").readText()

    @Test fun lifecycle_frequency_truth_is_outside_emit_coalescing() {
        val s = src()
        val fn = s.substringAfter("fun lifecycle(event: String, fields: String)").substringBefore("private fun extractField")
        val emitIdx = fn.indexOf("shouldEmitExactLifecycle7479")
        val collectorIdx = fn.indexOf("PipelineHealthCollector.onLifecycle(event, fields)")
        assertTrue(emitIdx >= 0)
        assertTrue(collectorIdx > emitIdx)
        assertTrue(fn.contains("FORENSIC_REPEAT_SUMMARY_7479"))
    }

    @Test fun exact_repeat_coalescer_is_bounded_and_short_windowed() {
        val s = src()
        assertTrue(s.contains("EXACT_REPEAT_WINDOW_MS_7479 = 5_000L"))
        assertTrue(s.contains("EXACT_REPEAT_MAX_7479 = 4_096"))
        assertTrue(s.contains("FORENSIC_EXACT_REPEAT_COALESCED_7479"))
    }

    @Test fun canonical_bridge_remains_after_collector() {
        val s = src()
        val fn = s.substringAfter("fun lifecycle(event: String, fields: String)").substringBefore("private fun extractField")
        assertTrue(fn.contains("JournalMigrationAdapter6405.map(event)"))
        assertTrue(fn.contains("CanonicalEventStream6405.append("))
    }
}
