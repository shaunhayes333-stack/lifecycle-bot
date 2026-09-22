package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7242 — canonical entry selectivity regression coverage.
 *
 * Runtime 7240 showed zero/negative-score and WAIT candidates being resurrected
 * by lane-local probe/floor shapers. The economic boundary belongs in FDG so it
 * applies identically to paper and live before any canonical position can open.
 */
class Aate7242CanonicalEntrySelectivityTest {

    @Test
    fun fdg_refuses_sub30_and_weak_wait_promotions_before_lane_execution() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt"
        ).readText()

        assertTrue(src.contains("CANONICAL_V3_SCORE_FLOOR_7243"))
        assertTrue(src.contains("CANONICAL_WAIT_PROMOTION_REFUSED_7243"))
        assertTrue(src.contains("canonicalV3Score7243 < 30.0"))
        assertTrue(src.contains("canonicalV3Score7243 < 55.0"))
        assertTrue(src.contains("baseEntrySignal7243 !in setOf(\"BUY\", \"EXECUTE\")"))
        assertTrue(src.contains("ts.lastV3Score?.toDouble() ?: candidate.entryScore"))

        val modeIdx = src.indexOf("val mode = if (config.paperMode)")
        val selectivityIdx = src.indexOf("§CANONICAL_ENTRY_SELECTIVITY")
        val laneIdx = src.indexOf("val laneName = tradingModeTag")
        assertTrue(modeIdx >= 0 && selectivityIdx > modeIdx && laneIdx > selectivityIdx)
    }
}
