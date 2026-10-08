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

    private data class Verdict(val atMs: Long, val contradicted: Boolean, val quotePnl: Double)
    private val recent = ConcurrentHashMap<String, Verdict>()

    /** Pure: is this exit one the quote check applies to? */
    fun applies(reason: String, triggerPnlPct: Double, live: Boolean): Boolean {
        if (!live || !triggerPnlPct.isFinite() || triggerPnlPct > CHECK_BELOW_PCT) return false
        val r = reason.uppercase()
        if (r.contains("MANUAL") || r.contains("OPERATOR") || r.contains("USER") || r.contains("SHUTDOWN") || r.contains("RUG")) return false
        return ExitRegret7752.isStopFamily7925(r)
    }

    /** Pure: does the executable value contradict the trigger? */
    fun contradicts(triggerPnlPct: Double, quotePnlPct: Double): Boolean {
        if (!triggerPnlPct.isFinite() || !quotePnlPct.isFinite()) return false
        return quotePnlPct - triggerPnlPct >= maxOf(8.0, 0.5 * kotlin.math.abs(triggerPnlPct))
    }

    /** Pure: the position's pnl at an executable quote (gross of the sell's own fee). */
    fun quotePnlPct(quotedOutSol: Double, costSol: Double): Double =
        if (quotedOutSol.isFinite() && quotedOutSol >= 0.0 && costSol.isFinite() && costSol > 0.0)
            (quotedOutSol - costSol) / costSol * 100.0 else Double.NaN

    /**
     * True when the sell may proceed. [quoteOutSol] returns the route's SOL out for
     * the held amount, or null/throws when no quote is available.
     */
    fun allowSell(mint: String, symbol: String, reason: String, triggerPnlPct: Double, costSol: Double,
                  nowMs: Long = System.currentTimeMillis(), quoteOutSol: () -> Double?): Boolean {
        recent[mint]?.let { v ->
            if (nowMs - v.atMs < VERDICT_TTL_MS) return !v.contradicted || !contradicts(triggerPnlPct, v.quotePnl)
        }
        val out = try { quoteOutSol() } catch (_: Throwable) { null } ?: return true
        val qPnl = quotePnlPct(out, costSol)
        if (!qPnl.isFinite()) return true
        val bad = contradicts(triggerPnlPct, qPnl)
        if (recent.size > 2_000) recent.clear()
        recent[mint] = Verdict(nowMs, bad, qPnl)
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
}
