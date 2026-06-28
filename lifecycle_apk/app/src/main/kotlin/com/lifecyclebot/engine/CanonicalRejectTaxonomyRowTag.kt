package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/** V5.0.4435 — report-only canonical outcome row tag for reject taxonomy drift. */
object CanonicalRejectTaxonomyRowTag {
    fun inspectLegacyTrade(trade: Trade, stage: String) {
        val reason = trade.reason
        val r = reason.uppercase()
        if (!(r.contains("REJECT") || r.contains("BLOCK") || r.contains("DEFER") || r.contains("PENDING") || r.contains("NO_QUOTE") || r.contains("LOW_LIQ") || r.contains("UNPROFITABLE"))) return
        val taxonomy = try { RejectTaxonomy.classify(reason, null) } catch (_: Throwable) { return }
        ChokeReliefBus.launch("CANONICAL_REJECT_ROW_TAG_4435", trade.mint) {
            try { RejectTaxonomyLedger.record(taxonomy, "CANONICAL_${trade.mode.uppercase()}", reason) } catch (_: Throwable) {}
            try {
                PipelineHealthCollector.labelInc("CANONICAL_REJECT_ROW_TAG_4435_${taxonomy.category.name}")
                ForensicLogger.lifecycle("CANONICAL_REJECT_ROW_TAG_4435", "stage=$stage mint=${trade.mint.take(10)} side=${trade.side} mode=${trade.mode} reason=${reason.take(96)} taxonomy=${taxonomy.category.name} report_only=true")
            } catch (_: Throwable) {}
        }
    }

    fun status(): String = "CANONICAL_REJECT_ROW_TAG_4435 watches=[REJECT BLOCK DEFER PENDING NO_QUOTE LOW_LIQ UNPROFITABLE] ledger=RejectTaxonomyLedger report_only=true no_outcome_mutation=true no_learning_block=true"
}
