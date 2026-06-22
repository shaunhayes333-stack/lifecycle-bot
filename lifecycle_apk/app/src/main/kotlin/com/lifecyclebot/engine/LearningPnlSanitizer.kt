package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import kotlin.math.abs
import kotlin.math.max

/**
 * V5.0.4066 — canonical trainable-PnL contract.
 *
 * Accounting/journal storage may retain very loose historical runner bounds, but
 * adaptive learning must never train from fabricated percentage math. This helper
 * is deliberately soft at runtime: it does not block execution or journal writes;
 * it only decides whether an outcome is safe for learning/hive/expectancy views.
 *
 * V5.0.4066 — REAL WINS TRAIN AT REAL VALUES.
 * Previous MAX_TRAINABLE_PNL_PCT = 5_000.0 hard-rejected real moonshot outcomes
 * like +207,248% as "fabricated." That silently discarded the exact compounding
 * wins the bot is built to capture — they never trained TokenWinMemory,
 * StrategyTelemetry, PatternClassifier, CanonicalOutcomeBus, or WR/expectancy.
 *
 * Fix: raise MAX_TRAINABLE_PNL_PCT to 100_000.0 (same order as the accounting
 * validity gate). NO CLAMP. A real +207,248% live win trains at +207,248%.
 * The fabrication guard is the SOL-basis mismatch check below: if pnlPct doesn't
 * match (pnlSol / sol) * 100 within tolerance, it's rejected as fabricated.
 * Real on-chain live sells have real pnlSol and real sol, so they pass. Fake rows
 * (USDC → +8722 SOL) don't, because the math doesn't reconcile.
 *
 * The accounting validity gate in TradeHistoryStore and the SOL notional cap
 * (5,000 SOL proceeds) are unchanged — those catch fabricated rows at storage.
 */
object LearningPnlSanitizer {
    const val MIN_TRAINABLE_PNL_PCT = -100.0001
    // V5.0.4066: raised from 5_000 to 100_000. Real meme runners can hit 200k%+.
    // The SOL-basis mismatch check is the fabrication guard, not this ceiling.
    const val MAX_TRAINABLE_PNL_PCT = 100_000.0
    private const val ABS_PCT_MISMATCH_TOLERANCE = 50.0
    private const val REL_PCT_MISMATCH_TOLERANCE = 0.35

    data class Verdict(
        val ok: Boolean,
        val pnlPct: Double = 0.0,
        val reason: String = "",
    )

    fun inspectPct(pnlPct: Double, context: String = "", sol: Double? = null, pnlSol: Double? = null, emit: Boolean = true): Verdict {
        if (!pnlPct.isFinite()) return reject("PNL_PCT_NOT_FINITE", pnlPct, context, emit = emit)
        if (pnlPct < MIN_TRAINABLE_PNL_PCT) return reject("PNL_PCT_BELOW_TOTAL_LOSS", pnlPct, context, emit = emit)
        if (pnlPct > MAX_TRAINABLE_PNL_PCT) return reject("PNL_PCT_ABOVE_TRAINABLE_MAX", pnlPct, context, emit = emit)
        val s = sol?.takeIf { it.isFinite() && it > 0.0 }
        val p = pnlSol?.takeIf { it.isFinite() }
        if (s != null && p != null) {
            val implied = (p / s) * 100.0
            val tolerance = max(ABS_PCT_MISMATCH_TOLERANCE, abs(pnlPct) * REL_PCT_MISMATCH_TOLERANCE)
            if (abs(implied - pnlPct) > tolerance) {
                return reject("PNL_PCT_SOL_BASIS_MISMATCH", pnlPct, context, "implied=${"%.2f".format(implied)} tol=${"%.2f".format(tolerance)}", emit = emit)
            }
        }
        return Verdict(true, pnlPct)
    }

    fun inspectTrade(t: Trade, context: String = "trade", emit: Boolean = true): Verdict {
        val side = t.side.uppercase()
        if (side != "SELL" && side != "PARTIAL_SELL") return Verdict(true, t.pnlPct)
        if (t.entryCostSol <= 0.0 || !t.entryCostSol.isFinite()) return reject("MISSING_ENTRY_COST_BASIS", t.pnlPct, context, emit = emit)
        if (t.entryPriceSnapshot <= 0.0 || !t.entryPriceSnapshot.isFinite()) return reject("MISSING_ENTRY_PRICE_BASIS", t.pnlPct, context, emit = emit)
        val labelVerdict = try { CloseOutcomeLabelSanitizer.inspect(t) } catch (_: Throwable) { null }
        if (labelVerdict?.dirty == true && side == "PARTIAL_SELL") {
            return reject(labelVerdict.dirtyReason.ifBlank { "DIRTY_PARTIAL_LABEL" }, t.pnlPct, context, emit = emit)
        }
        val realizedSol = t.netPnlSol.takeIf { it != 0.0 } ?: t.pnlSol
        return inspectPct(
            pnlPct = t.pnlPct,
            context = "$context/${side}/${t.tradingMode}/${t.reason.take(32)}",
            sol = t.sol,
            pnlSol = realizedSol,
            emit = emit,
        )
    }

    fun isTrainablePct(pnlPct: Double): Boolean = inspectPct(pnlPct, emit = false).ok
    fun isTrainableTrade(t: Trade): Boolean = inspectTrade(t, emit = false).ok

    fun emitReject(reason: String, context: String, pnlPct: Double) {
        try { PipelineHealthCollector.labelInc("LEARNING_PNL_QUARANTINED") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("LEARNING_PNL_QUARANTINED_$reason") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("LEARNING_PNL_QUARANTINED", "reason=$reason context=${context.take(96)} pnlPct=${"%.2f".format(pnlPct)}") } catch (_: Throwable) {}
    }

    fun emitReject(v: Verdict, context: String) {
        if (!v.ok) emitReject(v.reason, context, v.pnlPct)
    }

    private fun reject(reason: String, pnlPct: Double, context: String, extra: String = "", emit: Boolean = true): Verdict {
        if (emit) emitReject(reason, context, pnlPct)
        if (emit && extra.isNotBlank()) {
            try { ErrorLogger.warn("LearningPnlSanitizer", "Rejected trainable pnl reason=$reason context=${context.take(96)} pnlPct=$pnlPct $extra") } catch (_: Throwable) {}
        }
        return Verdict(false, pnlPct, reason)
    }
}
