package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6357 — LaneBucketPivot whole-lane fallback removed.
 *
 * Operator emergency dump (V5.0.6308, WR -20%):
 *   PIVOT_DEEP_WINNER_6240 sym=MAY lane=STANDARD band=S0-19 score=0
 *   mult=1.35 reason=DEEP_WINNER wins=121 μ=244%
 *
 * Every pump.fun score=0 mint was catching DEEP_WINNER upsize because the
 * whole-lane fallback pooled STANDARD lane's 121 wins @ 244% (mostly S40-60
 * band tokens) into the S0-19 bucket. That drove size×1.35 on garbage
 * entries and produced the WR regression.
 *
 * Fix: require ≥2 winner-DNA rows IN THE SAME BAND. Whole-lane rows remain
 * as CONTEXT for the score>0 case (via the >0 filter inside bandRows), never
 * as a substitute for band-specific evidence.
 */
class LaneBucketPivotBandOnly6357Test {

    @Test
    fun whole_lane_fallback_removed_from_winner_mean_lookup() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LaneBucketPivot.kt").readText()
        // Old three-way fallback (bandRows / laneRows / empty) is gone.
        assertFalse("bandRows>=2 -> bandRows chain must be removed",
            txt.contains("bandRows.size >= 2 -> bandRows\n                laneRows.size >= 2 -> laneRows"))
        // New guard requires band evidence.
        assertTrue("winnerMeanForBucket must gate on bandRows.size < 2",
            txt.contains("if (bandRows.size < 2) 0.0 to 0"))
    }

    @Test
    fun v6357_reason_documented_inside_file() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LaneBucketPivot.kt").readText()
        assertTrue("V5.0.6357 justification must be committed alongside the fix",
            txt.contains("V5.0.6357"))
    }
}
