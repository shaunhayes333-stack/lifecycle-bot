package com.lifecyclebot.engine.truth

/**
 * V5.0.7287 §THE ORACLE READS THE WHOLE BOOK.
 *
 * Operator: "the oracle is meant to effect trading and ingest all trade
 * history as well. it shouldn't be blind."
 *
 * It was reading session-scoped learners. PredictiveEntryOracle6915's LANE
 * and GLOBAL levels came from LiveProbabilityEngine.laneSnapshots, which on
 * the 5.0.7285 device reported n=44 CRYPTO_ALT, n=22 PROJECT_SNIPER and so
 * on beside 233 lifetime closes in the journal. Every restart the oracle
 * started over while the journal already knew the answer.
 *
 * This reads the terminal journal — every cleaned terminal SELL the bot has
 * ever booked, paper and live, via TradeHistoryStore — and reduces it to per
 * lane and whole-book expectancy (net of fees, per position) and win rate.
 * The oracle blends these as its LANE and GLOBAL levels whenever the journal
 * holds more closes than the session learner, so its evidence is the full
 * history and never less than what the session has seen.
 *
 * Recomputed at most once a minute; the journal read is bounded at 5,000
 * terminal rows. Recorded values only; nothing is inferred or re-costed.
 * Rows booked before 7287 carry the old paper fee model, which overstated a
 * round trip by several points, so the early history reads pessimistic; new
 * closes replace it as they arrive.
 */
object OracleTradeHistory7287 {
    data class Stat(val n: Int, val meanNetPct: Double, val winRate: Double)

    private const val REFRESH_MS = 60_000L
    private const val MAX_ROWS = 5_000

    @Volatile private var byLane: Map<String, Stat> = emptyMap()
    @Volatile private var global: Stat = Stat(0, 0.0, 0.0)
    // V5.0.7307 — terminal LIVE closes per lane, so a paper-seeded caution
    // can hand authority to live once live has a view of its own.
    @Volatile private var liveClosesByLane: Map<String, Int> = emptyMap()
    @Volatile private var computedAtMs = 0L

    private fun netPct(t: com.lifecyclebot.data.Trade): Double? {
        val cost = t.entryCostSol
        val v = if (cost.isFinite() && cost > 0.0 && t.netPnlSol.isFinite()) t.netPnlSol / cost * 100.0 else t.pnlPct
        return if (v.isFinite()) v.coerceIn(-100.0, 100_000.0) else null
    }

    // V5.0.7346 — read the volatile stamp before taking the monitor. Every
    // lane()/book() call (per pool lane, per lane, per token) used to enter the
    // lock even when the data was fresh; the locked path re-checks as before.
    private fun refreshIfDue(nowMs: Long) {
        if (nowMs - computedAtMs < REFRESH_MS && computedAtMs > 0L) return
        refreshIfDueLocked7346(nowMs)
    }

    @Synchronized
    private fun refreshIfDueLocked7346(nowMs: Long) {
        if (nowMs - computedAtMs < REFRESH_MS && computedAtMs > 0L) return
        computedAtMs = nowMs
        val rows = try {
            com.lifecyclebot.engine.TradeHistoryStore.getRecentCleanStrategyTerminalTrades(MAX_ROWS)
        } catch (_: Throwable) { return }
        val acc = HashMap<String, DoubleArray>() // [n, sum, wins]
        val all = DoubleArray(3)
        val live7307 = HashMap<String, Int>()
        for (t in rows) {
            if (!t.side.equals("SELL", ignoreCase = true)) continue
            val r = netPct(t) ?: continue
            val lane = t.tradingMode.trim().uppercase().ifBlank { "UNKNOWN" }
            if (t.mode.equals("live", ignoreCase = true)) live7307[lane] = (live7307[lane] ?: 0) + 1
            val a = acc.getOrPut(lane) { DoubleArray(3) }
            a[0] += 1.0; a[1] += r; if (r > 0.0) a[2] += 1.0
            all[0] += 1.0; all[1] += r; if (r > 0.0) all[2] += 1.0
        }
        liveClosesByLane = live7307
        byLane = acc.mapValues { (_, a) -> Stat(a[0].toInt(), a[1] / a[0], a[2] / a[0]) }
        global = if (all[0] > 0.0) Stat(all[0].toInt(), all[1] / all[0], all[2] / all[0]) else Stat(0, 0.0, 0.0)
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ORACLE_HISTORY_REFRESHED_7287")
        } catch (_: Throwable) {}
    }

    fun lane(lane: String, nowMs: Long = System.currentTimeMillis()): Stat? {
        refreshIfDue(nowMs)
        return byLane[lane.trim().uppercase()]?.takeIf { it.n > 0 }
    }

    /** V5.0.7307 — terminal LIVE closes this lane has booked (journal, all sessions). */
    fun liveCloses(lane: String, nowMs: Long = System.currentTimeMillis()): Int {
        refreshIfDue(nowMs)
        return liveClosesByLane[lane.trim().uppercase()] ?: 0
    }

    fun book(nowMs: Long = System.currentTimeMillis()): Stat? {
        refreshIfDue(nowMs)
        return global.takeIf { it.n > 0 }
    }

    fun statusLine(): String {
        val g = global
        return "historyCloses=${g.n} bookE=${"%+.1f".format(g.meanNetPct)}% bookWR=${"%.0f".format(g.winRate * 100.0)}% lanes=${byLane.size}"
    }
}
