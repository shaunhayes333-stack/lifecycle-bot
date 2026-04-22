package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.136 — LLM Paper Trade Scoreboard.
 *
 * Tracks every paper position opened by the sentient chat layer (via
 * CryptoAltTrader.llmOpenPaperBuy) and rolls up wins / losses / net PnL
 * when those positions close. Lets the user see whether the LLM's chat
 * instincts beat the rest of the 41-layer bot.
 *
 * Opened positions are marked by a "LLM chat:" prefix in AltPosition.reasons
 * (set in llmOpenPaperBuy). On close, CryptoAltTrader.closePosition checks
 * for that marker and calls recordClose() here.
 *
 * Persisted to SharedPreferences so the scoreboard survives app restarts.
 */
object LlmTradeScore {

    private const val TAG = "LlmTradeScore"
    private const val PREFS_NAME = "llm_trade_score"
    private const val K_OPENS = "opens"
    private const val K_WINS = "wins"
    private const val K_LOSSES = "losses"
    private const val K_PNL_MILLIS = "pnl_millisol"   // net PnL in SOL × 1000
    private const val K_BEST_PCT = "best_pct_x10"
    private const val K_WORST_PCT = "worst_pct_x10"
    private const val K_LAST_SYMBOL = "last_symbol"
    private const val K_LAST_PCT_X10 = "last_pct_x10"

    private var prefs: SharedPreferences? = null

    private val opens = AtomicInteger(0)
    private val wins = AtomicInteger(0)
    private val losses = AtomicInteger(0)
    private val netPnlMilliSol = AtomicLong(0)   // PnL × 1000
    private val bestPctX10 = AtomicLong(Long.MIN_VALUE)
    private val worstPctX10 = AtomicLong(Long.MAX_VALUE)

    @Volatile private var lastSymbol: String = ""
    @Volatile private var lastPctX10: Long = 0

    fun init(context: Context) {
        val p = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        opens.set(p.getInt(K_OPENS, 0))
        wins.set(p.getInt(K_WINS, 0))
        losses.set(p.getInt(K_LOSSES, 0))
        netPnlMilliSol.set(p.getLong(K_PNL_MILLIS, 0L))
        bestPctX10.set(p.getLong(K_BEST_PCT, Long.MIN_VALUE))
        worstPctX10.set(p.getLong(K_WORST_PCT, Long.MAX_VALUE))
        lastSymbol = p.getString(K_LAST_SYMBOL, "") ?: ""
        lastPctX10 = p.getLong(K_LAST_PCT_X10, 0L)
    }

    /** Called when the sentient chat layer opens a paper position. */
    fun recordOpen() {
        opens.incrementAndGet()
        save()
    }

    /**
     * Called when a chat-originated paper position closes.
     * @param pnlSol  Realised SOL PnL (positive = win).
     * @param pnlPct  Realised percentage PnL (already leverage-adjusted).
     * @param symbol  Ticker for last-trade display.
     */
    fun recordClose(pnlSol: Double, pnlPct: Double, symbol: String) {
        if (pnlSol >= 0.0) wins.incrementAndGet() else losses.incrementAndGet()
        netPnlMilliSol.addAndGet((pnlSol * 1000.0).toLong())
        val pctX10 = (pnlPct * 10.0).toLong()
        bestPctX10.updateAndGet { old -> if (pctX10 > old) pctX10 else old }
        worstPctX10.updateAndGet { old -> if (pctX10 < old) pctX10 else old }
        lastSymbol = symbol
        lastPctX10 = pctX10
        save()
    }

    fun reset() {
        opens.set(0); wins.set(0); losses.set(0)
        netPnlMilliSol.set(0)
        bestPctX10.set(Long.MIN_VALUE); worstPctX10.set(Long.MAX_VALUE)
        lastSymbol = ""; lastPctX10 = 0
        save()
    }

    data class Snapshot(
        val opens: Int,
        val wins: Int,
        val losses: Int,
        val openNow: Int,       // opens - (wins + losses)
        val winRatePct: Int,    // 0..100
        val netPnlSol: Double,
        val bestPct: Double,    // 0.0 if no trade yet
        val worstPct: Double,
        val lastSymbol: String,
        val lastPct: Double,
    )

    fun snapshot(): Snapshot {
        val o = opens.get(); val w = wins.get(); val l = losses.get()
        val closed = w + l
        val wr = if (closed > 0) (w * 100 / closed) else 0
        val best = bestPctX10.get().let { if (it == Long.MIN_VALUE) 0.0 else it / 10.0 }
        val worst = worstPctX10.get().let { if (it == Long.MAX_VALUE) 0.0 else it / 10.0 }
        return Snapshot(
            opens       = o,
            wins        = w,
            losses      = l,
            openNow     = (o - closed).coerceAtLeast(0),
            winRatePct  = wr,
            netPnlSol   = netPnlMilliSol.get() / 1000.0,
            bestPct     = best,
            worstPct    = worst,
            lastSymbol  = lastSymbol,
            lastPct     = lastPctX10 / 10.0,
        )
    }

    /** Single-line summary for the chat scoreboard UI row. */
    fun summaryLine(): String {
        val s = snapshot()
        if (s.opens == 0) {
            return "🧠 LLM DESK · no chat trades yet · tell me to buy something"
        }
        val pnlEmoji = if (s.netPnlSol >= 0) "🟢" else "🔴"
        val pnlStr = "%+.3f◎".format(s.netPnlSol)
        val wrStr = if (s.wins + s.losses > 0) "${s.winRatePct}%" else "—"
        return "🧠 LLM DESK · ${s.opens} trades · ${s.wins}W/${s.losses}L · WR $wrStr · $pnlEmoji $pnlStr"
    }

    /** Multi-line detail used in the expandable scoreboard card. */
    fun detailBlock(): String {
        val s = snapshot()
        if (s.opens == 0) return "no chat trades yet"
        val sb = StringBuilder()
        sb.append("Opens: ${s.opens}   Wins: ${s.wins}   Losses: ${s.losses}   Open: ${s.openNow}\n")
        sb.append("Win rate: ${s.winRatePct}%   Net: %+.4f◎".format(s.netPnlSol)).append('\n')
        if (s.bestPct != 0.0 || s.worstPct != 0.0) {
            sb.append("Best: %+.1f%%   Worst: %+.1f%%".format(s.bestPct, s.worstPct)).append('\n')
        }
        if (s.lastSymbol.isNotBlank()) {
            sb.append("Last: ${s.lastSymbol} %+.1f%%".format(s.lastPct))
        }
        return sb.toString().trimEnd()
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putInt(K_OPENS, opens.get())
            .putInt(K_WINS, wins.get())
            .putInt(K_LOSSES, losses.get())
            .putLong(K_PNL_MILLIS, netPnlMilliSol.get())
            .putLong(K_BEST_PCT, bestPctX10.get())
            .putLong(K_WORST_PCT, worstPctX10.get())
            .putString(K_LAST_SYMBOL, lastSymbol)
            .putLong(K_LAST_PCT_X10, lastPctX10)
            .apply()
    }
}
