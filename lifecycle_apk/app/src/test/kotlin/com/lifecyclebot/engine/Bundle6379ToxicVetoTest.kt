package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6489 — catastrophic lane evidence must shape, never globally veto. */
class Bundle6379ToxicVetoTest {

    @Test
    fun live_probability_engine_exposes_recoverable_toxic_shape_signal() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue(txt.contains("fun isLaneLearnedToxic6379(lane: String): Boolean"))
        assertTrue(txt.contains("fun toxicShapeReason6489(lane: String): String?"))
        assertTrue(txt.contains("learned_toxic_shape"))
    }

    @Test
    fun toxic_shape_thresholds_remain_evidence_based() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue(txt.contains("TOXIC_SHAPE_MIN_SAMPLES  = 15"))
        assertTrue(txt.contains("TOXIC_SHAPE_MAX_WR_PCT   = 10.0"))
        assertTrue(txt.contains("TOXIC_SHAPE_MAX_MEAN_PCT = -25.0"))
    }

    @Test
    fun executable_open_gate_emits_full_lane_soft_shape_without_hard_return() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(txt.contains("LiveProbabilityEngine.toxicShapeReason6489(canonicalSelectedLane)"))
        assertTrue(txt.contains("LEARNED_TOXIC_LANE_SOFT_SHAPED_6489|lane=$" + "fullLane6489"))
        assertTrue(txt.contains("action=allow_existing_bounded_shapers"))
        assertFalse(txt.contains("LEARNED_TOXIC_LANE_HARD_VETO_6379"))
        assertFalse(txt.contains("EXEC_OPEN_BLOCKED_LEARNED_TOXIC_LANE_6379"))
    }

    @Test
    fun toxic_shape_reads_raw_journal_not_sanitized() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue(
            txt.contains("fun isLaneLearnedToxic6379(lane: String): Boolean") &&
                txt.contains("rawLaneReality(lane)?.let")
        )
    }
}
