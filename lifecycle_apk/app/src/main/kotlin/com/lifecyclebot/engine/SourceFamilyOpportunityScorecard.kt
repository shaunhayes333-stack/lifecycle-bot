package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** V5.0.4287 — diagnostic source-family opportunity scorecard. No source block authority. */
object SourceFamilyOpportunityScorecard {
    data class Stat(var discovered: Int = 0, var admitted: Int = 0, var opened: Int = 0, var closed: Int = 0, var wins: Int = 0, var pnlSol: Double = 0.0, var costSol: Double = 0.0, var holdMin: Double = 0.0, var rugOverlay: Int = 0)
    private val stats = ConcurrentHashMap<String, Stat>() // legacy pooled/reporting
    // V5.0.7403 — decision-facing source truth is mode-specific. Historical
    // pooled rows remain for reports, but cannot authorize LIVE entries.
    private val liveStats7403 = ConcurrentHashMap<String, Stat>()
    private val paperStats7403 = ConcurrentHashMap<String, Stat>()
    private const val MAX_FAMILIES = 48

    /**
     * V5.0.6915 — READ ACCESSOR. This object has tracked per-source closed
     * count, wins and realised PnL since V5.0.4287 and exposed it only through
     * `snapshot()`, a display string. Six months of source-family expectancy
     * was therefore unreadable by any decision path — the header calls itself
     * "diagnostic ... No source block authority", which was accurate and is
     * exactly the problem the operator named: the brains contribute nothing
     * but trade size.
     *
     * PredictiveEntryOracle6915 reads this as one bounded input to entry
     * expectancy. Still not a block authority on its own; it is evidence.
     */
    data class Expectancy6915(
        val source: String,
        val closed: Int,
        val wins: Int,
        val winRatePct: Double,
        val pnlSol: Double,
        val costSol: Double,
        /** Realised PnL as a percentage of deployed cost. */
        val meanPnlPct: Double,
    )

    fun expectancyFor6915(source: String, liveMode7403: Boolean? = null): Expectancy6915? {
        val key = source.trim().uppercase()
        if (key.isEmpty()) return null
        val store = when (liveMode7403) {
            true -> liveStats7403
            false -> paperStats7403
            null -> stats
        }
        // Intake sources arrive as scanner names (often a comma-separated
        // union), while outcomes are stored under canonical source families.
        // Resolve every explicit family and combine only those rows. The old
        // substring fallback never matched DEX_TRENDING -> DEX or
        // PUMP_PORTAL_WS -> PUMP_FAMILY, so the predictive stack read 0 source
        // cohorts even when the ledger contained them.
        val families = key.split(',', '+', '|').map { it.trim() }
            .filter { it.isNotEmpty() }.map(::family).distinct()
        val selected = families.mapNotNull { store[it] }
        if (selected.isEmpty()) return null
        val closed = selected.sumOf { it.closed }
        if (closed <= 0) return null
        val wins = selected.sumOf { it.wins }
        val pnlSol = selected.sumOf { it.pnlSol }
        val costSol = selected.sumOf { it.costSol }
        val meanPct = if (costSol > 0.0) (pnlSol / costSol) * 100.0 else 0.0
        return Expectancy6915(
            source = key,
            closed = closed,
            wins = wins,
            winRatePct = wins * 100.0 / closed,
            pnlSol = pnlSol,
            costSol = costSol,
            meanPnlPct = if (meanPct.isFinite()) meanPct.coerceIn(-100.0, 5000.0) else 0.0,
        )
    }

    fun recordDiscovered(source: String, hasRugOverlay: Boolean = false) = update(source) { discovered++; if (hasRugOverlay) this.rugOverlay++ }
    fun recordAdmitted(source: String, hasRugOverlay: Boolean = false) = update(source) { admitted++; if (hasRugOverlay) this.rugOverlay++ }
    fun recordOpened(source: String) = update(source) { opened++ }
    fun recordClosed(source: String, trade: Trade) {
        val pnl7403 = (trade.netPnlSol.takeIf { it.isFinite() && it != 0.0 } ?: trade.pnlSol)
            .takeIf { it.isFinite() } ?: 0.0
        // V5.0.7403 — cost means deployed basis, NOT fees. Using feeSol as
        // denominator inflated a normal return into hundreds/thousands of %
        // and poisoned source-family edge in the oracle/admission stack.
        val basis7403 = when {
            trade.entryCostSol.isFinite() && trade.entryCostSol > 0.0 -> trade.entryCostSol
            trade.soldCostBasisSol.isFinite() && trade.soldCostBasisSol > 0.0 -> trade.soldCostBasisSol
            trade.preCostSol.isFinite() && trade.preCostSol > 0.0 -> trade.preCostSol
            trade.sol.isFinite() && trade.sol > 0.0 -> trade.sol
            else -> 0.0
        }
        fun apply7403(target: ConcurrentHashMap<String, Stat>) {
            updateInto7403(target, source) {
                closed++
                if (pnl7403 > 0.0) wins++
                pnlSol += pnl7403
                costSol += basis7403
                holdMin += ((trade.ts - trade.entryTsMs).coerceAtLeast(0L).toDouble() / 60_000.0)
                    .coerceAtMost(24.0 * 60.0)
            }
        }
        apply7403(stats)
        if (trade.mode.equals("live", true)) apply7403(liveStats7403)
        else if (trade.mode.equals("paper", true)) apply7403(paperStats7403)
    }

    fun snapshot(): String = stats.entries.sortedByDescending { it.value.closed + it.value.opened }.take(12).joinToString(" | ") { (k, s) ->
        val pf = if (s.closed <= 0) 0.0 else s.pnlSol / s.closed.toDouble()
        val wr = if (s.closed <= 0) 0.0 else 100.0 * s.wins / s.closed.toDouble()
        "$k d=${s.discovered} a=${s.admitted} o=${s.opened} c=${s.closed} wr=${wr.fmtLocal(1)} avgPnl=${pf.fmtLocal(5)} cost=${s.costSol.fmtLocal(5)} rug=${s.rugOverlay}"
    }

    fun maybeReport() {
        try {
            val text = snapshot()
            if (text.isNotBlank()) ForensicLogger.lifecycle("SOURCE_FAMILY_OPPORTUNITY_SCORECARD_4287", text.take(900))
        } catch (_: Throwable) {}
    }

    private fun exportStats7403(source: ConcurrentHashMap<String, Stat>): JSONArray = JSONArray().also { a ->
        source.entries.take(MAX_FAMILIES).forEach { (k, s) ->
            synchronized(s) {
                a.put(JSONObject().put("k", k).put("d", s.discovered).put("a", s.admitted)
                    .put("o", s.opened).put("c", s.closed).put("w", s.wins).put("p", s.pnlSol)
                    .put("cost", s.costSol).put("h", s.holdMin).put("r", s.rugOverlay))
            }
        }
    }

    /** Persist the three views separately: pooled rows are reporting-only. */
    fun exportState(): String = JSONObject()
        .put("schema", 2)
        .put("legacy", exportStats7403(stats))
        .put("live", exportStats7403(liveStats7403))
        .put("paper", exportStats7403(paperStats7403))
        .toString()

    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            stats.clear(); liveStats7403.clear(); paperStats7403.clear()
            fun restore(target: ConcurrentHashMap<String, Stat>, rows: JSONArray?) {
                val a = rows ?: return
                for (i in 0 until a.length().coerceAtMost(MAX_FAMILIES)) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("k")
                    if (k.isNotBlank()) target[k] = Stat(
                        o.optInt("d"), o.optInt("a"), o.optInt("o"), o.optInt("c"), o.optInt("w"),
                        o.optDouble("p"), o.optDouble("cost"), o.optDouble("h"), o.optInt("r"),
                    )
                }
            }
            val root = raw.trimStart().firstOrNull()
            if (root == '{') {
                val o = JSONObject(raw)
                restore(stats, o.optJSONArray("legacy"))
                restore(liveStats7403, o.optJSONArray("live"))
                restore(paperStats7403, o.optJSONArray("paper"))
            } else {
                // V1 was a pooled JSON array. Keep it reporting-only: its mode
                // and execution-cost provenance cannot be reconstructed.
                restore(stats, JSONArray(raw))
            }
        } catch (_: Throwable) {}
    }
    fun reset() { stats.clear(); liveStats7403.clear(); paperStats7403.clear() }

    private fun update(source: String, block: Stat.() -> Unit) =
        updateInto7403(stats, source, block)

    private fun updateInto7403(target: ConcurrentHashMap<String, Stat>, source: String, block: Stat.() -> Unit) {
        val key = family(source)
        val s = target.getOrPut(key) { Stat() }
        synchronized(s) { s.block() }
        if (target.size > MAX_FAMILIES) target.keys.take(target.size - MAX_FAMILIES).forEach { target.remove(it) }
    }
    private fun family(source: String): String = when {
        source.contains("pump", true) -> "PUMP_FAMILY"
        source.contains("birdeye", true) -> "BIRDEYE"
        source.contains("dex", true) -> "DEX"
        source.contains("jupiter", true) -> "JUPITER"
        source.isBlank() -> "UNKNOWN"
        else -> source.uppercase().take(32)
    }
}

private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "%.${decimals}f", this) } catch (_: Throwable) { this.toString() }
