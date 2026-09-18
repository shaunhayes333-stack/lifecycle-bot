package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7036 §THE_INTELLIGENCE_CIRCUIT_COMPUTES_AND_NOBODY_READS.
 *
 * ci/audit_unread_intelligence.py reports 110 modules holding 280 analysis
 * functions that nothing in the app calls. They are not broken and they are
 * not cheap: FluidLearningAI alone is 2,751 lines and 25 of its 82 query
 * functions have no reader. The engine pays to compute those answers on every
 * cycle and then throws all of them away.
 *
 * WHAT THIS IS AND IS NOT. Batch one of the operator's "wire, ignite, connect"
 * pass, restricted to READ-ONLY outputs: every call below returns a number or
 * a list and none of them can refuse a trade, resize one, or move a
 * threshold. That restriction is the point. The same audit also lists
 * shouldBlockNewBuys, blockLiveBuyReason, shouldBlockFeeBandExit and
 * isInDangerZone — thirteen risk gates whose whole purpose is to STOP trades.
 * Switching those on in the same change as this would mean that if execution
 * fell to zero afterwards, nothing in the snapshot could say which of the
 * thirteen did it. They go in their own batches, one small group at a time,
 * each with its own named counter.
 *
 * WHY SURFACING COUNTS AS WIRING. An analysis function with no reader is
 * indistinguishable from one that is wrong: nobody has ever seen its output,
 * so nobody can tell. Putting these on the snapshot is what makes the next
 * step possible — the operator and I can look at what BehaviorLearning
 * actually thinks a winning pattern is before anything is allowed to trade on
 * it. Reading first, acting second, is the order that was skipped when these
 * were built.
 *
 * EVERY CALL IS INDIVIDUALLY GUARDED. These modules were never exercised from
 * this path; one of them throwing must not cost the operator their health
 * snapshot, which is the only diagnostic surface they have.
 */
object UnreadIntelligenceSurface7036 {

    private fun pct(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%.1f%%", v)

    private fun <T> safe(label: String, block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        try { PipelineHealthCollector.labelInc("UNREAD_INTEL_READ_FAILED_7036_$label") } catch (_: Throwable) {}
        null
    }

    /**
     * One block for the operator snapshot. Deliberately compact: this is a
     * first look at answers nobody has ever seen, not a dashboard.
     */
    fun report(): String {
        val sb = StringBuilder()
        sb.append("===== UNREAD INTELLIGENCE (V5.0.7036 — first readers) =====\n")

        safe("EXIT_PROFITABLE_RATE") {
            com.lifecyclebot.engine.ExitIntelligence.getProfitableRate()
        }?.let { sb.append("  ExitIntelligence.profitableRate:   ").append(pct(it)).append("\n") }

        safe("BRAIN_BLENDED_WR") {
            com.lifecyclebot.engine.BotBrain.getBlendedWinRate()
        }?.let { sb.append("  BotBrain.blendedWinRate:          ").append(pct(it)).append("\n") }

        safe("BEHAVIOUR_GOOD") {
            com.lifecyclebot.engine.BehaviorLearning.getTopGoodPatterns(3)
        }?.let { list ->
            sb.append("  BehaviorLearning top GOOD (n=").append(list.size).append("):\n")
            for (p in list) {
                sb.append("    ").append(p.signature.take(44))
                    .append("  n=").append(p.occurrences)
                    .append(" W/L=").append(p.wins).append("/").append(p.losses)
                    .append(" avgPnl=").append(
                        if (p.occurrences > 0) String.format(java.util.Locale.US, "%+.1f%%", p.totalPnlPct / p.occurrences)
                        else "—"
                    ).append("\n")
            }
        }

        safe("BEHAVIOUR_BAD") {
            com.lifecyclebot.engine.BehaviorLearning.getTopBadPatterns(3)
        }?.let { list ->
            sb.append("  BehaviorLearning top BAD (n=").append(list.size).append("):\n")
            for (p in list) {
                sb.append("    ").append(p.signature.take(44))
                    .append("  n=").append(p.occurrences)
                    .append(" W/L=").append(p.wins).append("/").append(p.losses)
                    .append(" avgPnl=").append(
                        if (p.occurrences > 0) String.format(java.util.Locale.US, "%+.1f%%", p.totalPnlPct / p.occurrences)
                        else "—"
                    ).append("\n")
            }
        }

        safe("MOMENTUM_STRONG") {
            com.lifecyclebot.engine.MomentumPredictorAI.getStrongMomentumTokens()
        }?.let { list ->
            sb.append("  MomentumPredictorAI.strongMomentum: ").append(list.size).append(" tokens")
            if (list.isNotEmpty()) {
                sb.append(" [").append(list.take(5).joinToString(",") { it.symbol.take(10) }).append("]")
            }
            sb.append("\n")
        }

        safe("COLLECTIVE_WIN_PATTERNS") {
            com.lifecyclebot.collective.CollectiveLearning.getHighWinPatterns().size
        }?.let { sb.append("  CollectiveLearning.highWinPatterns: ").append(it).append("\n") }

        safe("COLLECTIVE_LOSS_PATTERNS") {
            com.lifecyclebot.collective.CollectiveLearning.getHighLossPatterns().size
        }?.let { sb.append("  CollectiveLearning.highLossPatterns:").append(it).append("\n") }

        // V5.0.7036 — risk exposure by canonical asset class. CanonicalLedger6387
        // has computed this since 6387 and nothing has ever asked: the count that
        // decides whether the bot is over-exposed was available and unread.
        safe("RISK_EXPOSURE") {
            CanonicalLedger6387.canonicalRiskPositionIds().size to
                CanonicalLedger6387.canonicalRiskPositionMints().size
        }?.let { (ids, mints) ->
            sb.append("  CanonicalLedger6387 risk exposure:  positions=").append(ids)
                .append(" distinctMints=").append(mints).append("\n")
        }

        sb.append("  Read: first readers for outputs the engine has been computing\n")
        sb.append("        and discarding. Advisory only — nothing here gates a trade.\n")
        return sb.toString()
    }
}
