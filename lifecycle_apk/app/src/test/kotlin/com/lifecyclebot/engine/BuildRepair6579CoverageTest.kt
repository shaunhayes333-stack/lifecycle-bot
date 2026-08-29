package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6579 — highest-ROI subset of the operator's 6578 P0 audit:
 *
 * P0-a  BG split runtime invariant  — PipelineHealthCollector emits
 *       BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579 when intake is fresh while
 *       canonical scan/FDG have been stale for >10 min.
 *
 * P0-b  AntiChokeManager clogged detection — no longer hard-coded false.
 *       Derived from token-map pending / probation hold / supervisor cap.
 *
 * P0-d  ExecutableOpenGate block-reason taxonomy — a positive resolvedSize
 *       is never emitted as the block reason alone; a concrete taxonomy
 *       (BELOW_MIN_NOTIONAL / SIZE_ZERO_UNPRICED_INTAKE) is stamped and
 *       counted separately.
 *
 * P0-e  PAPER opening does NOT require the strict EXECUTABLE_ENTRY_QUOTE
 *       mark — a fresh OBSERVATION_SCORING mark is sufficient. Live still
 *       requires strict. Eliminates the 6578 ROUTE_FAILED_PAPER false blocks.
 */
class BuildRepair6579CoverageTest {

    private val antiChokeSrc = File("src/main/kotlin/com/lifecyclebot/engine/AntiChokeManager.kt").readText()
    private val execGateSrc = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
    private val executorSrc = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
    private val healthSrc = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

    @Test
    fun p0_a_bg_split_runtime_invariant_present() {
        assertTrue(
            "PipelineHealthCollector must emit BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579 " +
                "when INTAKE age <60s while SCAN_CB/FDG >10 min stale",
            healthSrc.contains("BG_SPLIT_RUNTIME_INTAKE_ZOMBIE_6579") &&
                healthSrc.contains("intakeAge < 60_000L") &&
                healthSrc.contains("scanAge > 600_000L")
        )
    }

    @Test
    fun p0_b_anti_choke_clogged_is_derived() {
        assertTrue(
            "AntiChokeManager must NOT hardcode clogged=false anymore",
            !antiChokeSrc.contains("val clogged = false // intake-pool size is not a choke signal")
        )
        assertTrue(
            "AntiChokeManager must derive clogged from real signals",
            antiChokeSrc.contains("TOKEN_MAP_PENDING") &&
                antiChokeSrc.contains("PROBATION_HOLD_ADMIT") &&
                antiChokeSrc.contains("SUPERVISOR_CAP_FIRED")
        )
        assertTrue(
            "AntiChokeManager must emit ANTI_CHOKE_CLOGGED_DETECTED_6579 when clogged fires",
            antiChokeSrc.contains("ANTI_CHOKE_CLOGGED_DETECTED_6579")
        )
    }

    @Test
    fun p0_d_exec_gate_block_reason_taxonomy() {
        assertTrue(
            "ExecutableOpenGate must emit a taxonomy label, not raw positive resolvedSize",
            execGateSrc.contains("BELOW_MIN_NOTIONAL") &&
                execGateSrc.contains("SIZE_ZERO_UNPRICED_INTAKE") &&
                execGateSrc.contains("EXEC_OPEN_BLOCK_TAXONOMY_")
        )
    }

    @Test
    fun p0_e_paper_accepts_observation_mark() {
        assertTrue(
            "Executor.paperBuy must accept OBSERVATION_SCORING mark when strict mark is absent",
            executorSrc.contains("OBSERVATION_SCORING") &&
                executorSrc.contains("EXECUTION_PAPER_OBSERVATION_MARK_OK_6579")
        )
        assertTrue(
            "Executor.paperBuy must still refuse if BOTH marks are absent",
            executorSrc.contains("val paperMarkOk6579 = strictMark6575 != null || observationMark6579 != null") &&
                executorSrc.contains("NO_EXECUTABLE_MARK_6575")
        )
    }
}
