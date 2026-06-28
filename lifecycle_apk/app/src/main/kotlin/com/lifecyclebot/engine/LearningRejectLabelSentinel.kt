package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.0.4430 — report-only sentinel for reject/defer/block labels leaking into
 * journaled trade rows before canonical learning fanout.
 */
object LearningRejectLabelSentinel {
    private fun suspicious(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("REJECT") || r.contains("BLOCK") || r.contains("DEFER") ||
            r.contains("PENDING") || r.contains("LOW_LIQ") || r.contains("UNPROFITABLE") ||
            r.contains("COST_REJECT") || r.contains("NO_QUOTE")
    }

    fun inspect(trade: Trade, stage: String) {
        val reason = trade.reason
        if (!suspicious(reason)) return
        val classification = try { RejectTaxonomy.classify(reason, null) } catch (_: Throwable) { return }
        ChokeReliefBus.launch("LEARNING_REJECT_LABEL_SENTINEL_4430", trade.mint) {
            try { RejectTaxonomyLedger.record(classification, "JOURNAL_${trade.mode.uppercase()}", reason) } catch (_: Throwable) {}
            try {
                PipelineHealthCollector.labelInc("LEARNING_REJECT_LABEL_SENTINEL_4430_${classification.category.name}")
                ForensicLogger.lifecycle(
                    "LEARNING_REJECT_LABEL_SENTINEL_4430",
                    "stage=$stage mint=${trade.mint.take(10)} mode=${trade.mode} side=${trade.side} reason=${reason.take(96)} taxonomy=${classification.category.name} report_only=true"
                )
            } catch (_: Throwable) {}
        }
    }

    fun status(): String = "LEARNING_REJECT_LABEL_SENTINEL_4430 watches=[REJECT BLOCK DEFER PENDING LOW_LIQ UNPROFITABLE COST_REJECT NO_QUOTE] ledger=RejectTaxonomyLedger report_only=true no_quarantine_change=true no_learning_block=true"
}
