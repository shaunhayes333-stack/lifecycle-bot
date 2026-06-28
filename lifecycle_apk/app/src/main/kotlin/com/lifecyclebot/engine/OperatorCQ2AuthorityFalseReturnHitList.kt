package com.lifecyclebot.engine

/** V5.0.4413 — CQ2 hard-return sibling hit list, report-only. */
object OperatorCQ2AuthorityFalseReturnHitList {
    data class Hit(
        val file: String,
        val pattern: String,
        val classification: String,
        val risk: String,
        val downstream: String
    )

    private val hits = listOf(
        Hit(
            file = "FinalExecutionPermit.kt",
            pattern = "Runtime pause/lane disabled/finality/pending/telemetry-only branches return false",
            classification = "MIXED_HARD_SAFETY_AND_ROUTE_VISIBILITY_CANDIDATE",
            risk = "true pause/finality blocks are valid; telemetry-only and pending branches need route-visible release proof",
            downstream = "FEP -> LaneExecutionCoordinator -> TradeAuthorizer release -> FDG route verdict -> KPI suppressor counters"
        ),
        Hit(
            file = "TradeAuthorizer.kt",
            pattern = "authoritative-open lock pruning returns false after stale-lock cleanup",
            classification = "LIKELY_BENIGN_STALE_LOCK_CLEANUP",
            risk = "cleanup is desirable, but stale-lock prune needs ledger/reconciler parity visibility",
            downstream = "auth lock -> wallet snapshot -> open slot health -> ledger drift report"
        ),
        Hit(
            file = "LaneExecutionCoordinator.kt",
            pattern = "releasePrimary returns false when election missing or lane is not primary",
            classification = "CANDIDATE_RELEASE_VISIBILITY_GAP",
            risk = "missing-election false may hide permit/lease release mismatches under contention",
            downstream = "lane election -> FEP release -> TradeAuthorizer release -> retry eligibility"
        ),
        Hit(
            file = "BotService.kt",
            pattern = "bridge override and hot-exit helpers return false for score/liquidity/rate-limit/stale-state conditions",
            classification = "MIXED_ADVISORY_AND_SAFETY_TAXONOMY_CANDIDATE",
            risk = "advisory score false must not look like hard safety; liquidity/rate limit need explicit taxonomy",
            downstream = "scanner/source -> bridge override -> FDG bypass eligibility -> executor attempts -> learning labels"
        ),
        Hit(
            file = "ExecutableOpenGate.kt",
            pattern = "no direct return-false sibling in scanned executable-open file",
            classification = "FALSE_POSITIVE_CLEARED",
            risk = "keep in CQ2 family tree because FEP/TradeAuthorizer call through executable-open semantics",
            downstream = "open-gate invariant -> FEP -> executor handoff"
        )
    )

    fun status(): String {
        val candidates = hits.filter { it.classification.contains("CANDIDATE") }.joinToString(",") { it.file }
        val cleared = hits.filter { it.classification.contains("FALSE_POSITIVE") || it.classification.contains("BENIGN") }.joinToString(",") { it.file }
        return "OPERATOR_CQ2_AUTHORITY_FALSE_RETURN_HIT_LIST_4413 total=${hits.size} candidates=[$candidates] cleared=[$cleared] report_only=true no_execution_authority=true no_gate_change=true hard_safety_preserved=true release_visibility_required=true butterflies_named=true"
    }
}
