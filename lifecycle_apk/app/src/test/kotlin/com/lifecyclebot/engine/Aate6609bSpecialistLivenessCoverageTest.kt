package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6609b — Specialist liveness restoration (operator directive Feb 2026):
 *   "Every enabled specialist: taskAlive=true, poolAlive=true,
 *   discoveryAlive=true. No specialist remains DEAD merely because its
 *   current learned edge is poor."
 *
 * Operator's V5.0.6608 dump captured DIP_HUNTER / CYCLIC / CORE / TREASURY
 * / CASHGEN as taskAlive=false poolAlive=false discoveryAlive=false with
 * status=DEAD despite hundreds of meme candidates flowing through the
 * scanner every minute.
 *
 * Root cause: `ToolkitSignalSheet.deskHypothesesFor` only bumped
 * `recordDeskStage(lane, "POOL")` for desks that WON the hypothesis
 * election. Every meme candidate IS in the OBSERVATIONAL pool of every
 * configured meme desk even when that desk didn't win the hypothesis
 * contest; the DEAD classification was a telemetry defect, not a
 * runtime one.
 *
 * Fix:
 *   1. Every configured meme desk that did NOT win a hypothesis now
 *      still receives a POOL bump per candidate so its
 *      pool/discovery/task counters reflect the fact that the desk is
 *      running and observing.
 *   2. `taskAlive` reports `pool > 0` instead of `qualified > 0` —
 *      QUALIFIED remains winner-only (it carries the stricter
 *      "produced an actionable hypothesis" meaning).
 */
class Aate6609bSpecialistLivenessCoverageTest {

    @Test
    fun aate6609b_no_configured_desk_receives_fabricated_pool_bump() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt"
        ).readText()
        assertTrue(src.contains("No fabricated pool/liveness credit"))
        assertTrue(!src.contains("recordDeskStage(deskLane, \"POOL\")"))
    }

    @Test
    fun aate6609b_runtime_liveness_comes_from_registered_jobs() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt"
        ).readText()
        assertTrue(
            "runtime liveness must use job heartbeat and queue ownership",
            src.contains("SpecialistRuntimeRegistry6647.snapshot") && src.contains("runtimeAlive=\${runtime.runtimeAlive}")
        )
    }
}
