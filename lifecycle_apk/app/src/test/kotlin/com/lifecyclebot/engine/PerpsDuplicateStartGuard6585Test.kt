package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6585 §P0-10 — PERPS producer STARTED stamp is now transition-only.
 *
 * Operator forensic (6580):
 *   PERPS started=2 scanTick=1 marketDataOk=0 rawSignal=0 candidateCreated=0
 *
 * Root cause: `markProducerStage6569(PERPS, "STARTED")` fired at the top
 * of `start(context)` BEFORE the isRunning() early-return. A duplicate
 * start() therefore double-stamped, creating the illusion of two
 * producers while scan/marketData reported a single-producer footprint.
 *
 * Fix: STARTED stamp moved after the running-transition point (line 121)
 * so it fires exactly once per stopped→started transition.
 * PERPS_DUPLICATE_START_SKIPPED_6584 counter now visible for operator
 * to see how often duplicate start() attempts occurred.
 */
class PerpsDuplicateStartGuard6585Test {

    private val perpsSrc = File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()

    @Test
    fun started_stamp_is_after_transition_not_before() {
        // The STARTED stamp must NOT appear before the isRunning early-return.
        val startFn = perpsSrc.substringAfter("fun start(context: android.content.Context) {")
            .substringBefore("fun stop()")
        val startedIdx = startFn.indexOf("markProducerStage6569(com.lifecyclebot.engine.truth.AssetClass.PERPS, \"STARTED\")")
        val isRunningEarlyReturnIdx = startFn.indexOf("Already running and loops are alive — no restart needed")
        assertTrue("STARTED stamp must appear AFTER the running-transition early return", startedIdx > isRunningEarlyReturnIdx)
    }

    @Test
    fun duplicate_start_emits_skipped_counter() {
        assertTrue(
            "Duplicate start() when loops are already alive must emit " +
                "PERPS_DUPLICATE_START_SKIPPED_6584 for operator visibility",
            perpsSrc.contains("PERPS_DUPLICATE_START_SKIPPED_6584")
        )
    }
}
