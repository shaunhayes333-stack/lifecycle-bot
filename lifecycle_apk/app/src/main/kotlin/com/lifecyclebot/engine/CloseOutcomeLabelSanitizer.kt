package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.0.4024 — close-outcome label sanitizer.
 *
 * Journal/accounting must preserve realized PnL, but close labels must not teach
 * the learning stack the opposite of what happened. A row like
 * MOONSHOT_TAKE_PROFIT at -95% is a LOSS, not a profitable exit. A row like
 * partial_15pct at -93% is a failed/invalid partial, not profit distribution.
 *
 * This object rewrites only the reason label. It never changes PnL, SOL, side,
 * proof state, or mode/lane attribution.
 */
object CloseOutcomeLabelSanitizer {
    const val VERSION = "V5.0.4024_CLOSE_OUTCOME_LABEL_SANITIZER"
    const val PARTIAL_PROFIT_FLOOR_PCT = 8.0

    data class LabelVerdict(
        val dirty: Boolean,
        val canonicalReason: String,
        val dirtyReason: String = "",
    )

    fun canonicalize(t: Trade, emit: Boolean = true): Trade {
        val v = inspect(t)
        if (!v.dirty || v.canonicalReason == t.reason) return t
        if (emit) emitRewrite(t, v)
        return t.copy(reason = v.canonicalReason)
    }

    fun inspect(t: Trade): LabelVerdict {
        val side = t.side.uppercase()
        if (side != "SELL" && side != "PARTIAL_SELL") return LabelVerdict(false, t.reason)
        val reason = t.reason.ifBlank { side }
        val r = reason.uppercase()
        val pnl = t.pnlPct
        if (!pnl.isFinite()) return LabelVerdict(true, "DIRTY_CLOSE_PNL_NOT_FINITE", "PNL_NOT_FINITE")

        val isPartial = side == "PARTIAL_SELL" || r.contains("PARTIAL") || Regex("PARTIAL_\\d+PCT").containsMatchIn(r)
        val looksProfit = r.contains("TAKE_PROFIT") || r.contains("PROFIT_LOCK") ||
            r.contains("CAPITAL_RECOVERY") || r.contains("SWEEP_TAKE_PROFIT") ||
            r == "TP" || r.endsWith("_TP")
        val looksRisk = r.contains("STOP_LOSS") || r.contains("STRICT_SL") || r.contains("HARD_FLOOR") ||
            r.contains("CATASTROPHE") || r.contains("CATASTROPHIC") || r.contains("RUG") ||
            r.contains("CRASH") || r.contains("DRAIN") || r.contains("PANIC")

        if (isPartial && pnl < PARTIAL_PROFIT_FLOOR_PCT) {
            val bucket = when {
                pnl <= -2.0 -> "LOSS"
                pnl >= 0.5 -> "LOW_PROFIT"
                else -> "SCRATCH"
            }
            return LabelVerdict(true, "PARTIAL_BELOW_PROFIT_FLOOR_$bucket", "PARTIAL_BELOW_PROFIT_FLOOR")
        }

        if (looksProfit && pnl <= -2.0) {
            return LabelVerdict(true, "REALIZED_LOSS_AFTER_PROFIT_SIGNAL", "PROFIT_LABEL_NEGATIVE_PNL")
        }
        if (looksProfit && pnl < 0.5) {
            return LabelVerdict(true, "REALIZED_SCRATCH_AFTER_PROFIT_SIGNAL", "PROFIT_LABEL_NOT_WIN")
        }

        if (looksRisk && pnl >= 0.5) {
            return LabelVerdict(true, "REALIZED_WIN_AFTER_RISK_EXIT_SIGNAL", "RISK_LABEL_POSITIVE_PNL")
        }
        if (looksRisk && pnl > -2.0) {
            return LabelVerdict(true, "REALIZED_SCRATCH_AFTER_RISK_EXIT_SIGNAL", "RISK_LABEL_SCRATCH_PNL")
        }

        return LabelVerdict(false, reason)
    }

    fun isDirtyForTraining(t: Trade): Boolean = inspect(t).dirty

    private fun emitRewrite(t: Trade, v: LabelVerdict) {
        try { PipelineHealthCollector.labelInc("TRAINING_ROW_EXCLUDED_REASON_${v.dirtyReason}") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("CLOSE_OUTCOME_LABEL_REWRITTEN") } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "CLOSE_OUTCOME_LABEL_REWRITTEN",
                "version=$VERSION mint=${t.mint.take(10)} side=${t.side} mode=${t.mode} lane=${t.tradingMode} pnl=${"%.2f".format(t.pnlPct)} old=${t.reason.take(64)} new=${v.canonicalReason} dirty=${v.dirtyReason} proof=${t.proofState}",
            )
        } catch (_: Throwable) {}
    }
}
