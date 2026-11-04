package com.lifecyclebot.perps.strategy

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8535 §C13 — grades each executed PerpsStrategy setup (e.g. PERPS_FUNDING_SHORT) on its settled closes.
 *
 * The replay learner and the hive cache grade market × direction; the setup that decided the entry was never recorded,
 * so a funding-contrarian short and a momentum short on the same market were one cohort. The position carries its
 * setup name from the signal to the close; this book grades it and hands back a bounded size shape (same bounds as the
 * 7540 replay-pattern shape: 0.75–1.15, neutral until five settled closes).
 */
object PerpsSetupGradeBook8535 {

    internal data class Cell(var n: Int = 0, var wins: Int = 0, var pnlSum: Double = 0.0) {
        fun winRate(): Double = if (n == 0) 0.0 else wins * 100.0 / n
        fun meanPnl(): Double = if (n == 0) 0.0 else pnlSum / n
    }

    private const val MIN_SAMPLES = 5
    private val cells = ConcurrentHashMap<String, Cell>()

    fun record(setup: String, pnlPct: Double) {
        if (setup.isBlank() || !pnlPct.isFinite()) return
        val c = cells.getOrPut(setup) { Cell() }
        synchronized(c) {
            c.n++
            if (pnlPct >= 1.0) c.wins++   // the perps book's own win floor (V5.9.225)
            c.pnlSum += pnlPct
        }
    }

    /** 1.0 until [MIN_SAMPLES] closes; then 0.75..1.15 by win-rate edge, only when win rate and mean PnL agree. */
    fun sizeMultiplier(setup: String): Double {
        val c = cells[setup] ?: return 1.0
        if (c.n < MIN_SAMPLES) return 1.0
        val positive = c.winRate() > 50.0 && c.meanPnl() > 0.0
        val negative = c.winRate() < 50.0 && c.meanPnl() < 0.0
        if (!positive && !negative) return 1.0
        val wrEdge = ((c.winRate() - 50.0) / 50.0).coerceIn(-1.0, 1.0)
        return (1.0 + wrEdge * 0.25).coerceIn(0.75, 1.15)
    }

    internal fun snapshot(setup: String): Cell? = cells[setup]?.let { Cell(it.n, it.wins, it.pnlSum) }

    fun summary(): String {
        if (cells.isEmpty()) return "Perps setups: no settled closes yet"
        return cells.entries.sortedByDescending { it.value.n }.take(8).joinToString("\n") { (k, v) ->
            "  $k  n=${v.n} wr=${"%.0f".format(v.winRate())}% mean=${"%+.2f".format(v.meanPnl())}% size×${"%.2f".format(sizeMultiplier(k))}"
        }
    }

    fun serialize(): String {
        val obj = org.json.JSONObject()
        for ((k, v) in cells) obj.put(k, org.json.JSONObject().put("n", v.n).put("w", v.wins).put("p", v.pnlSum))
        return obj.toString()
    }

    fun load(blob: String) {
        cells.clear()
        if (blob.isBlank()) return
        try {
            val obj = org.json.JSONObject(blob)
            for (k in obj.keys()) {
                val o = obj.getJSONObject(k)
                cells[k] = Cell(o.optInt("n", 0), o.optInt("w", 0), o.optDouble("p", 0.0))
            }
        } catch (_: Throwable) { /* fail-open */ }
    }
}
