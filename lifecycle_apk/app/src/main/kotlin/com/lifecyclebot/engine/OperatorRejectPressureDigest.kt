package com.lifecyclebot.engine

/**
 * V5.0.4426 — operator-facing reject pressure digest.
 *
 * Consumes the normalized reject taxonomy/ledger and turns it into a compact
 * source-contract map for FDG/blocker audits. Report-only: it cannot reject,
 * size, admit, suppress, or train anything by itself.
 */
object OperatorRejectPressureDigest {
    fun status(): String {
        val taxonomy = try { RejectTaxonomy.status() } catch (_: Throwable) { "REJECT_TAXONOMY unavailable" }
        val ledger = try { RejectTaxonomyLedger.status() } catch (_: Throwable) { "REJECT_TAXONOMY_LEDGER unavailable" }
        return "OPERATOR_REJECT_PRESSURE_DIGEST_4426 taxonomy=[$taxonomy] ledger=[$ledger] doctrine=[pending_is_penalty low_liq_is_size_reduction unprofitable_is_cost_reject hard_safety_preserved] audit_next=[FDG_BLOCKERS EXECUTOR_SKIP_REASONS SCANNER_HARD_REJECTS LEARNING_LABELS] report_only=true no_execution_authority=true no_gate_change=true"
    }
}
