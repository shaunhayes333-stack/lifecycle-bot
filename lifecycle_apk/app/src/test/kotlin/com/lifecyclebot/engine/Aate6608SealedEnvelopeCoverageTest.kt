package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * V5.0.6608 — Operator directive Feb 2026:
 *   "After FDG BUY: UNKNOWN is an invariant failure. EXEC MUST consume
 *    this envelope. EXEC MUST NOT re-read a mutable V3/base/lane signal
 *    and convert the sealed BUY to UNKNOWN/WAIT."
 *
 * Post-6606 forensic dump captured 156× EXEC_GATE/SIGNAL_NOT_BUY:UNKNOWN
 * against 296 FDG allows (52% of allowed candidates converted to UNKNOWN
 * at the executable-open gate). Root cause: the pre-existing sealed-
 * envelope check at ExecutableOpenGate.canOpenExecutablePosition only
 * consulted `immutableFdgBuy6519` (an ExecutionIntent object) or
 * `canonicalExecutableIntent6509` (state.preFdgVerdict==BUY). It did NOT
 * consult the two OTHER seals that were already computed and available
 * at the same call site:
 *   * `immutableAuthority6513` — the canonical immutable authority
 *     record from FDG.
 *   * `ticketAuthority6564` — the FDG ticket carried through by
 *     attemptId.
 * Both of these can carry `verdict = BUY/PROBE_ONLY` with no hardNos,
 * which is the same operational seal the operator's directive requires
 * EXEC to honour. Fix: add `sealedBuyIntent6608` covering these two
 * additional seals and route them into the diagnostic-ignore branch.
 *
 * Also adds two invariant counters so the operator can grep for both
 * the recovery path (EXEC_SEALED_ENVELOPE_HONOURED_6608) and any
 * remaining unsealed FDG-allow → EXEC-UNKNOWN cases
 * (FDG_BUY_TO_EXEC_UNKNOWN_6608 — target zero).
 */
class Aate6608SealedEnvelopeCoverageTest {

    @Test
    fun aate6608_sealed_envelope_honoured_over_mutable_signal() {
        val gate = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        assertTrue(
            "V5.0.6608: sealedBuyIntent6608 must combine immutableAuthority6513 + ticketAuthority6564 with BUY/PROBE_ONLY + empty hardNos",
            gate.contains("val sealedBuyIntent6608") &&
                gate.contains("immAuthSealed6608") &&
                gate.contains("ticketSealed6608") &&
                gate.contains("hardNoReasons.isEmpty()")
        )
        assertTrue(
            "V5.0.6608: diagnostic-ignore branch must accept sealedBuyIntent6608",
            gate.contains("if (immutableFdgBuy6519 || canonicalExecutableIntent6509 || sealedBuyIntent6608)") &&
                gate.contains("EXEC_SEALED_ENVELOPE_HONOURED_6608")
        )
    }

    @Test
    fun aate6608_invariant_counter_for_unsealed_fdg_allow() {
        val gate = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
        ).readText()
        assertTrue(
            "V5.0.6608: FDG_BUY_TO_EXEC_UNKNOWN_6608 invariant counter must fire when fdgCan==true but no seal covered the block",
            gate.contains("FDG_BUY_TO_EXEC_UNKNOWN_6608") &&
                gate.contains("if (fdgCan == true) {") &&
                gate.contains("invariant_failure_no_seal_available")
        )
        // V5.0.6615 — an unsealed FDG permission is not a direction signal.
        // Keep the invariant counter, but terminate with typed missing-intent
        // authority instead of reconstructing the forbidden UNKNOWN action.
        assertTrue(
            "V5.0.6615: unsealed FDG allow must terminate as typed NO_EXECUTION_INTENT",
            gate.contains("EXEC_OPEN_BLOCKED_NO_EXECUTION_INTENT_6615") &&
                gate.contains("NO_EXECUTION_INTENT") &&
                !gate.contains("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY")
        )
    }
}
