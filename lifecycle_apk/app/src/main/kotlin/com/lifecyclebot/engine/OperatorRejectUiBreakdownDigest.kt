package com.lifecyclebot.engine

/**
 * V5.0.4436 — compact UI/operator reject-pressure breakdown.
 * Uses the shared taxonomy ledger only; no UI thread work, no execution authority.
 */
object OperatorRejectUiBreakdownDigest {
    fun status(): String {
        val ledger = try { RejectTaxonomyLedger.status().take(760) } catch (_: Throwable) { "RejectTaxonomyLedger unavailable" }
        return "OPERATOR_REJECT_UI_BREAKDOWN_DIGEST_4436 panels=[category_pressure lane_pressure hard_vs_trainable unknown_review] ledger=[$ledger] sources=[SCANNER FDG TRADE_AUTH EXECUTOR JOURNAL CANONICAL] ui_safe=true report_only=true no_main_thread_render=true no_execution_authority=true"
    }
}
