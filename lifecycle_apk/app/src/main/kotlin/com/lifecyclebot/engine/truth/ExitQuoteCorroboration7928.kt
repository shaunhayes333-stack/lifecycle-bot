package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7928 — a live stop sells on what the position is worth, not on a feed print.
 *
 * The 9 Oct live export carried NINJACAT (bad mark/basis) and 7thx (a phantom
 * -82%): a stop fired on a price the route never offered. Before a live stop-side
 * sell at a deep loss, the executable sell quote for the held amount is read; if
 * it values the position well above what the trigger saw, the sell is refused for
 * a short window and the next tick re-decides on a fresh read. A failed quote
 * never blocks a sell (a rug with no route must still be able to exit).
 */
object ExitQuoteCorroboration7928 {
    /** Only deep stop-side triggers are checked: shallow stops are not phantom-shaped. */
    const val CHECK_BELOW_PCT = -8.0
    private const val VERDICT_TTL_MS = 10_000L
    internal const val EMERGENCY_VERDICT_TTL_MS_8521 = 250L

    private data class Verdict(val atMs: Long, val quotePnl: Double, val identity: String, val costSol: Double, val reason: String, val sellCostSol: Double)
    private val recent = ConcurrentHashMap<String, Verdict>()

    /** Pure: is this exit one the quote check applies to? */
    fun applies(reason: String, triggerPnlPct: Double, live: Boolean, stopThresholdPct: Double? = null): Boolean {
        if (!live) return false
        val r = reason.uppercase()
        if (r.contains("MANUAL") || r.contains("OPERATOR") || r.contains("USER") || r.contains("SHUTDOWN") || r.contains("RUG")) return false
        // A queued price stop can outlive its trigger. Re-reading a recovered mark above -8% must not
        // bypass the very check which cancels that obsolete stop. The bound threshold, not the new
        // display PnL, decides whether a known price stop needs revalidation.
        if (stopThresholdPct != null && stopThresholdPct.isFinite() && stopThresholdPct < 0.0 &&
            com.lifecyclebot.engine.StopMarkConfirm8418.priceLossStop8489(reason)) return true
        if (!triggerPnlPct.isFinite() || triggerPnlPct > CHECK_BELOW_PCT) return false
        return ExitRegret7752.isStopFamily7925(r)
    }

    /** Pure: does the executable value contradict the trigger? */
    fun contradicts(triggerPnlPct: Double, quotePnlPct: Double): Boolean {
        if (!triggerPnlPct.isFinite() || !quotePnlPct.isFinite()) return false
        return quotePnlPct - triggerPnlPct >= maxOf(8.0, 0.5 * kotlin.math.abs(triggerPnlPct))
    }

    /**
     * A route which breaches the actual stop still exits when a bad mark overstates the loss.
     * V5.0.8529 — a quote refutes a bound stop only when the executable loss is under half the stop's depth
     * ([REFUTE_DEPTH_SHARE_8529], the StopMarkConfirm8418.venueRefutes8489 rule): a phantom mark or a recovered queued stop.
     * superseded: `return quotePnlPct > stopThresholdPct` — a quote a hair above the line cancelled the stop (8527 live:
     * SIGNAL_INVALIDATED_QUOTE_8521 x12 on emergencies; stops landed at -21 / -25 / -30 % against -15 % lines). A quote at
     * the line is the same falling price, not a bad print.
     */
    fun quoteRefutes8521(triggerPnlPct: Double, quotePnlPct: Double, stopThresholdPct: Double?): Boolean {
        if (stopThresholdPct != null && stopThresholdPct.isFinite() && stopThresholdPct < 0.0 &&
            quotePnlPct.isFinite()) return quotePnlPct > stopThresholdPct * REFUTE_DEPTH_SHARE_8529
        return contradicts(triggerPnlPct, quotePnlPct)
    }

    internal const val REFUTE_DEPTH_SHARE_8529 = 0.5

    /** Pure: the position's pnl at an executable quote (gross of the sell's own fee). */
    fun quotePnlPct(quotedOutSol: Double, costSol: Double): Double =
        if (quotedOutSol.isFinite() && quotedOutSol >= 0.0 && costSol.isFinite() && costSol > 0.0)
            (quotedOutSol - costSol) / costSol * 100.0 else Double.NaN

    /**
     * True when the sell may proceed. [quoteOutSol] returns the route's SOL out for
     * the held amount, or null/throws when no quote is available.
     */
    fun allowSell(mint: String, symbol: String, reason: String, triggerPnlPct: Double, costSol: Double,
                  nowMs: Long = System.currentTimeMillis(), positionIdentity8521: String = "",
                  stopThresholdPct8521: Double? = null, sellCostSol8521: Double = 0.0,
                  sellsUnquoted8706: () -> Boolean = { true }, quoteOutSol: () -> Double?): Boolean {
        // 8519: eight quote refutations and forty pre-route retries; each cached veto was a FAILED_RETRYABLE result.
        // A pre-dump quote must not veto the next emergency; a partial or re-entry must not inherit its old value.
        if (!sellCostSol8521.isFinite() || sellCostSol8521 < 0.0) return true // unknown net value cannot refute a stop
        val ttl = if (com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason)) EMERGENCY_VERDICT_TTL_MS_8521 else VERDICT_TTL_MS
        recent[mint]?.let { v ->
            if (nowMs - v.atMs in 0L until ttl && v.identity == positionIdentity8521 && v.costSol == costSol &&
                v.reason == reason && v.sellCostSol == sellCostSol8521)
                return !quoteRefutes8521(triggerPnlPct, v.quotePnl, stopThresholdPct8521)
        }
        // V5.0.8706 — no quote is not a confirmation: [sellsUnquoted8706] (StopMarkConfirm8418.sellsWithoutRouteQuote8706) decides.
        val out = try { quoteOutSol() } catch (_: Throwable) { null } ?: return (try { sellsUnquoted8706() } catch (_: Throwable) { true })
        val qPnl = quotePnlPct((out - sellCostSol8521).coerceAtLeast(0.0), costSol)
        if (!qPnl.isFinite()) return true
        val bad = quoteRefutes8521(triggerPnlPct, qPnl, stopThresholdPct8521)
        if (recent.size > 2_000) recent.clear()
        recent[mint] = Verdict(nowMs, qPnl, positionIdentity8521, costSol, reason, sellCostSol8521)
        try {
            PipelineHealthCollector.labelInc(if (bad) "EXIT_TRIGGER_CONTRADICTED_BY_QUOTE_7928" else "EXIT_TRIGGER_QUOTE_CONFIRMED_7928")
            if (bad) ForensicLogger.lifecycle(
                "EXIT_TRIGGER_CONTRADICTED_BY_QUOTE_7928",
                "mint=${mint.take(10)} symbol=$symbol reason=$reason triggerPnl=${"%.1f".format(triggerPnlPct)}% " +
                    "quotePnl=${"%.1f".format(qPnl)}% costSol=${"%.5f".format(costSol)} outSol=${"%.5f".format(out)} action=hold_and_redecide",
            )
        } catch (_: Throwable) {}
        return !bad
    }

    internal fun resetForTest8521() = recent.clear()
}
