package com.lifecyclebot.engine

/** V5.0.4431 — compact coverage map for the reject-taxonomy audit chain. */
object RejectTaxonomyCoverageDigest {
    fun status(): String {
        val taxonomy = try { RejectTaxonomy.status() } catch (_: Throwable) { "RejectTaxonomy unavailable" }
        val ledger = try { RejectTaxonomyLedger.status().take(520) } catch (_: Throwable) { "RejectTaxonomyLedger unavailable" }
        return "REJECT_TAXONOMY_COVERAGE_DIGEST_4431 coverage=[SCANNER_HARD_REJECT FDG_FINAL_DECISION TRADE_AUTHORIZER EXECUTOR_PREATTEMPT TRADE_HISTORY_PRE_LEARNING] taxonomy=[$taxonomy] ledger=[$ledger] remaining=[CANONICAL_OUTCOME_BUS_ROW_TAG EXECUTOR_DEFERRED_BUY_QUOTES UI_REJECT_PRESSURE_BREAKDOWN] report_only=true no_execution_authority=true no_learning_mutation=true"
    }
}
