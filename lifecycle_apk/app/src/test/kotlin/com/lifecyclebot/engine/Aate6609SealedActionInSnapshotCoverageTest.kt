package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6609 — SEALED_ACTION_IN_SNAPSHOT (operator directive Feb 2026,
 * P0: eliminate SIGNAL_NOT_BUY:UNKNOWN completely).
 *
 * Operator's V5.0.6608 forensic dump smoking gun:
 *   EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY              = 118
 *   EXEC_STATE_RESTORED_FROM_FROZEN_SNAPSHOT_6499 = 118
 *
 * Exactly 1:1. The frozen-snapshot restore path was preserving the
 * ticket but losing the FDG-authorized executable BUY signal to
 * UNKNOWN.
 *
 * Root cause: ExecutionSnapshotAuthority6496.Snapshot only carried
 * primaryLane / safety / occupancy / size. No fdgVerdict, no
 * executionAction. When the restore fired, downstream signal
 * derivation fell through to `state?.signal ?: "UNKNOWN"`.
 *
 * Repair (per operator's §3 SEAL and §5 UNKNOWN IS PRE-FDG ONLY):
 *   1. Extend Snapshot with fdgVerdict + executionAction.
 *   2. FDG_ALLOW record path passes BUY/PROBE_ONLY → BUY/PROBE_BUY.
 *   3. New sealedSnapshot6609(mint) read for the executor.
 *   4. Executor signal derivation consults sealed snapshot as a 4th
 *      authority (after immutableAuthority / ticket / state) — so a
 *      frozen-snapshot restore never lets UNKNOWN survive past
 *      FDG_ALLOW.
 *   5. Two invariant counters:
 *        POST_FDG_UNKNOWN_SIGNAL_6609       — target zero
 *        FDG_ALLOW_WITHOUT_SEALED_EXEC_ACTION_6609 — target zero
 *      One recovery counter:
 *        EXEC_RESTORED_ACTION_REPAIRED_6609 — the exact
 *          EXEC_RESTORED_ACTION_REPAIRED_6609 the operator specced.
 */
class Aate6609SealedActionInSnapshotCoverageTest {

    @Test
    fun aate6609_snapshot_carries_sealed_fdg_action() {
        val snapSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSnapshotAuthority6496.kt"
        ).readText()
        assertTrue(
            "V5.0.6609: Snapshot must carry fdgVerdict + executionAction",
            snapSrc.contains("val fdgVerdict: String") &&
                snapSrc.contains("val executionAction: String")
        )
        assertTrue(
            "V5.0.6609: record() must accept fdgVerdict + executionAction and normalize to uppercase",
            snapSrc.contains("fdgVerdict: String = \"\"") &&
                snapSrc.contains("executionAction: String = \"\"") &&
                snapSrc.contains("fdgVerdict = fdgVerdict.uppercase()") &&
                snapSrc.contains("executionAction = executionAction.uppercase()")
        )
        assertTrue(
            "V5.0.6609: sealedSnapshot6609(mint) must be exposed for the executor read path (TTL-bounded)",
            snapSrc.contains("fun sealedSnapshot6609(mint: String): Snapshot?") &&
                snapSrc.contains("age in 0..SNAPSHOT_TTL_MS")
        )
    }

    @Test
    fun aate6609_fdg_allow_records_sealed_action() {
        val gate = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        assertTrue(
            "V5.0.6609: FDG_ALLOW record() must map finalVerdict → BUY / PROBE_BUY",
            gate.contains("fdgVerdict = finalVerdict") &&
                gate.contains("\"BUY\" -> \"BUY\"") &&
                gate.contains("\"PROBE_ONLY\" -> \"PROBE_BUY\"")
        )
    }

    @Test
    fun aate6609_executor_signal_derivation_consults_sealed_snapshot() {
        val gate = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        assertTrue(
            "V5.0.6609: signal derivation must consult sealedSnapshot6609 as 4th authority",
            gate.contains("fromSealed6609") &&
                gate.contains("ExecutionSnapshotAuthority6496") &&
                gate.contains("sealedSnapshot6609(mint)")
        )
        // The PROBE_BUY mapping must resolve to executable BUY per operator
        // directive (\"PROBE_ONLY + allowed=true -> PROBE_BUY -> executable
        // ticket -> executor\").
        assertTrue(
            "V5.0.6609: PROBE_BUY must resolve to executable BUY at the signal derivation",
            gate.contains("\"PROBE_BUY\" -> \"BUY\"")
        )
    }

    @Test
    fun aate6609_invariant_and_recovery_counters_present() {
        val gate = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        // Invariant counters (target zero) that the operator required in
        // §8 REQUIRED POST-FIX INVARIANTS.
        assertTrue(
            "V5.0.6609: POST_FDG_UNKNOWN_SIGNAL_6609 invariant must be wired at signal derivation",
            gate.contains("POST_FDG_UNKNOWN_SIGNAL_6609")
        )
        assertTrue(
            "V5.0.6609: FDG_ALLOW_WITHOUT_SEALED_EXEC_ACTION_6609 invariant must be wired at signal derivation",
            gate.contains("FDG_ALLOW_WITHOUT_SEALED_EXEC_ACTION_6609")
        )
        // Recovery counter — the exact EXEC_RESTORED_ACTION_REPAIRED_6609
        // the operator specced in §3.
        assertTrue(
            "V5.0.6609: EXEC_RESTORED_ACTION_REPAIRED_6609 recovery counter must be wired",
            gate.contains("EXEC_RESTORED_ACTION_REPAIRED_6609")
        )
    }
}
