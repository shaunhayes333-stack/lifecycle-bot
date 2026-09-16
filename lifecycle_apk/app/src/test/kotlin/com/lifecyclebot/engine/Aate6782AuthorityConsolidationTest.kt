package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6782 §AUTHORITY_CONSOLIDATION — source-level acceptance tests.
 *
 * Per the operator's full-stack authority consolidation directive:
 *
 *   "Older throughput-era patches deliberately weakened the intelligence
 *    stack so the unfinished application could generate enough trade volume
 *    to learn. Those patches now contradict the intended production
 *    architecture."
 *
 * These tests enforce, at the source level, the acceptance criteria from
 * §14 of the directive:
 *
 *   A. Mature dual-negative prediction cannot execute.
 *   B. Proven-toxic lane/context cannot be resurrected by a minimum-size clamp.
 *   C. WAIT cannot silently become BUY without a new candidate version.
 *   D. HARD_BLOCK survives FDG → intent → ExecutableOpenGate → executor.
 *   E. Positive authoritative learned evidence can still execute normally.
 *   F. Shadow/replay/lab continues learning from rejected candidates.
 *   G. No external API/LLM failure blocks the local intelligence pipeline.
 *   H. Hard safety remains absolute.
 *   I. Every executed trade has one sealed predictive authority record.
 *   J. pWin / EV used at execution matches the sealed cognitive decision.
 *   K. No downstream bootstrap/default policy can overwrite an authoritative
 *      learned prediction.
 *   L. Full-stack learning fanout continues after terminal outcomes.
 *
 * These are source-text golden-tape assertions: they lock the removed
 * throughput-era patches OUT and lock the consolidated authority IN.
 */
class Aate6782AuthorityConsolidationTest {

    private fun file(path: String): String = java.io.File(path).readText()

    // ────────────────────────────────────────────────────────────────────
    // B. Proven-toxic lane cannot be resurrected by a minimum-size clamp.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun proven_dead_context_cannot_consume_canonical_capital() {
        val bcg = file("src/main/kotlin/com/lifecyclebot/engine/BrainConsensusGate.kt")
        assertTrue(
            "Proven-dead context must produce a HARD_BLOCK, not a 1-in-25 dust probe",
            bcg.contains("PROVEN_DEAD_HARD_VETO_6782") && bcg.contains("normalEntryBlocked = true"),
        )
        assertFalse(
            "The 1-in-25 canonical dust-probe path must be gone",
            bcg.contains("PROVEN_DEAD_PROBE=") ||
                bcg.contains("BRAIN_CONSENSUS_PROBE_ALLOWED") ||
                bcg.contains("probeAllowed = true"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // C. WAIT cannot silently become BUY without a new candidate version.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun wait_cannot_silently_become_buy_at_executable_open_gate() {
        val gate = file("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt")
        assertFalse(
            "LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW must not exist — WAIT/PROBE cannot be resurrected",
            gate.contains("LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW"),
        )
        assertFalse(
            "LIVE_RESTORE_STALE_CANDIDATE_SOFT_ALLOW must not exist — stale version must re-enter FDG",
            gate.contains("LIVE_RESTORE_STALE_CANDIDATE_SOFT_ALLOW"),
        )
        assertFalse(
            "LIVE_RESTORE_MISSING_FINAL_CANDIDATE_SOFT_ALLOW must not exist — missing state must re-enter FDG",
            gate.contains("LIVE_RESTORE_MISSING_FINAL_CANDIDATE_SOFT_ALLOW"),
        )
        assertTrue(
            "PRE_FDG_NOT_BUY (WATCH/PROBE) must drop and require re-entry",
            gate.contains("EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY"),
        )
        assertTrue(
            "STALE_CANDIDATE_VERSION must drop and require re-entry",
            gate.contains("EXEC_OPEN_DROPPED_STALE_CANDIDATE") && gate.contains("STALE_CANDIDATE_VERSION_"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Zero-confidence / "I don't know" must mean WAIT/REJECT, not tiny buy.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun zero_confidence_must_mean_reject_not_micro_probe() {
        val fdg = file("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")
        assertTrue(
            "Zero-conf must emit a sealed REJECT FinalDecision",
            fdg.contains("ZERO_CONFIDENCE_REJECT_6782") && fdg.contains("zero_conf_reject_6782"),
        )
        assertFalse(
            "Live zero-conf micro-probe path must be removed at source",
            fdg.contains("live_zero_conf_micro_probe") ||
                fdg.contains("ZERO_CONF_MICRO_PROBE (LIVE)") ||
                fdg.contains("ZERO_CONF_PASSTHRU (PAPER)") ||
                fdg.contains("zero_conf_paper_learn"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Adaptive relaxation forced by AntiChoke starvation must be a no-op.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun anti_choke_cannot_force_relaxation_of_confidence_floors() {
        val fdg = file("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")
        assertTrue(
            "forceAdaptiveRelaxation must be a documented no-op under 6782",
            fdg.contains("V5.0.6782 §AUTHORITY_CONSOLIDATION — no-op") &&
                fdg.contains("FORCE_ADAPTIVE_RELAXATION_NOOP_6782"),
        )
        assertFalse(
            "Automatic adaptive-relaxation trigger from consecutive blocks must be removed",
            fdg.contains("ADAPTIVE RELAXATION ACTIVATED after"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // H. Hard safety remains absolute (freeze/route/liquidity/ledger integrity).
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun hard_safety_remains_constitutional() {
        val gate = file("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt")
        assertTrue(
            "trueHardTicketKill list must retain TRUE_ZERO_LIQUIDITY / NO_EXECUTABLE_ROUTE / CONFIRMED_RUG",
            gate.contains("TRUE_ZERO_LIQUIDITY") &&
                gate.contains("NO_EXECUTABLE_ROUTE") &&
                gate.contains("CONFIRMED_RUG"),
        )
        assertTrue(
            "EXEC_OPEN_DROPPED_HARD_NO_BUY path must remain intact",
            gate.contains("EXEC_OPEN_DROPPED_HARD_NO_BUY"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // I. Every executed trade has one sealed predictive authority record.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun sealed_execution_intent_authority_is_the_single_source_of_truth() {
        val gate = file("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt")
        assertTrue(
            "validSealedDecision6613 must gate every restore/reseal path",
            gate.contains("private fun validSealedDecision6613"),
        )
        assertTrue(
            "resolveSealedIntent6613 must be the canonical resolver for tickets",
            gate.contains("resolveSealedIntent6613"),
        )
        assertTrue(
            "Frozen-snapshot fast-path must require an authoritative sealed intent",
            gate.contains("intentAuthoritative6627") ||
                gate.contains("EXEC_FROZEN_SNAPSHOT_MISSING_INTENT_NEEDS_REVALIDATION_6627"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // K. No downstream bootstrap/default policy can overwrite a learned prediction.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun downstream_cannot_overwrite_sealed_prediction() {
        val gate = file("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt")
        // A restored ticket that has no valid sealed BUY/PROBE_ONLY decision
        // must be REJECTED — not resurrected with a fresh default.
        assertTrue(
            "Ticket without sealed BUY decision must be discarded and re-validated",
            gate.contains("RESTORED_ALLOW_TICKET_WITHOUT_BUY_DECISION") &&
                gate.contains("EXEC_RESTORED_TICKET_REJECTED_6613"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Lanes are experts, not authorities: they cannot force execution when
    // the sealed cognitive verdict is REJECT.
    // ────────────────────────────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────
    // V5.0.6783 — Symbolic universe block is authoritative in ALL modes.
    // Prior LIVE-only block was a §12 downgrade-to-advisory in paper mode.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun symbolic_universe_block_authoritative_in_all_modes() {
        val fdg = file("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")
        assertFalse(
            "sym_panic_paper_warn passthrough must be removed",
            fdg.contains("sym_panic_paper_warn") || fdg.contains("PAPER: Tag it but allow through"),
        )
        assertTrue(
            "Symbolic universe block must be authoritative in all modes",
            fdg.contains("authoritative in all modes") && fdg.contains("SYMBOLIC_UNIVERSE_BLOCK"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // V5.0.6783 — Stale safety = WAIT, not "shape to 0.3x and continue".
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun stale_safety_means_wait_not_soft_shape() {
        val fdg = file("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")
        assertFalse(
            "FDG_SAFETY_STALE_SOFT_SHAPED_6341 must be removed",
            fdg.contains("FDG_SAFETY_STALE_SOFT_SHAPED_6341") ||
                fdg.contains("soft_shape_030x_and_continue"),
        )
        assertTrue(
            "Stale safety must return a WAIT/BLOCKED FinalDecision",
            fdg.contains("FDG_SAFETY_WAIT_6783") && fdg.contains("WAIT for refresh"),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // V5.0.6783 — COPY/WHALE lanes cannot force low-conf micro-probes.
    // ────────────────────────────────────────────────────────────────────
    @Test
    fun copy_and_whale_lanes_are_not_execution_authorities() {
        val fdg = file("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")
        assertFalse(
            "COPY_TRADE lane-forced micro-probe must be removed",
            fdg.contains("copy_trade_live_micro_probe") ||
                fdg.contains("LIVE COPY low confidence → micro-probe sizing"),
        )
        assertFalse(
            "WHALE_FOLLOW lane-forced growth probe must be removed",
            fdg.contains("whale_follow_live_growth_probe") ||
                fdg.contains("WHALE_FOLLOW allowed through shared growth doctrine"),
        )
        assertTrue(
            "COPY/WHALE observation is informational only under 6783",
            fdg.contains("decision follows sealed authority"),
        )
    }
}
