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
    fun aate6609b_all_configured_desks_receive_pool_bump() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt"
        ).readText()
        assertTrue(
            "V5.0.6609b: every configured meme desk that did not win a hypothesis must still get a POOL bump",
            src.contains("§RESTORE_SPECIALIST_LIVENESS") &&
                src.contains("configuredMemeDesks6599.forEach { deskLane ->") &&
                src.contains("!deskHypotheses.containsKey(deskLane)") &&
                src.contains("recordDeskStage(deskLane, \"POOL\")")
        )
        // QUALIFIED must remain winner-only.
        val poolBumpIdx = src.indexOf("§RESTORE_SPECIALIST_LIVENESS")
        val qualifiedBumpIdxAfter = src.indexOf("recordDeskStage(deskLane, \"QUALIFIED\")", poolBumpIdx)
        assertTrue(
            "V5.0.6609b: QUALIFIED must NOT be bumped for non-winning desks — the fix is POOL-only",
            qualifiedBumpIdxAfter < 0 || qualifiedBumpIdxAfter > poolBumpIdx + 2000
        )
    }

    @Test
    fun aate6609b_task_alive_reports_pool_not_qualified() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt"
        ).readText()
        assertTrue(
            "V5.0.6609b: taskAlive must derive from pool > 0 (desk is running/observing), not qualified > 0 (desk won a hypothesis)",
            src.contains("taskAlive=\${pool > 0} poolAlive=\${pool > 0} discoveryAlive=\${pool > 0}")
        )
    }
}
