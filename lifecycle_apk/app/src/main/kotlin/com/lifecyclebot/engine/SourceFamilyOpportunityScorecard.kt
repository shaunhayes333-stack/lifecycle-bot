package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** V5.0.4287 — diagnostic source-family opportunity scorecard. No source block authority. */
object SourceFamilyOpportunityScorecard {
    data class Stat(var discovered: Int = 0, var admitted: Int = 0, var opened: Int = 0, var closed: Int = 0, var wins: Int = 0, var pnlSol: Double = 0.0, var costSol: Double = 0.0, var holdMin: Double = 0.0, var rugOverlay: Int = 0)
    private val stats = ConcurrentHashMap<String, Stat>()
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

    fun expectancyFor6915(source: String): Expectancy6915? {
        val key = source.trim().uppercase()
        if (key.isEmpty()) return null
        // Exact family first; otherwise the best substring match, because
        // intake sources arrive as comma-joined composites
        // ("PUMP_FUN_NEW,SCANNER_DIRECT,REGISTRY_DUPLICATE_HYDRATE") while the
        // scorecard is keyed on the family.
        val s = stats[key]
            ?: stats.entries.firstOrNull { (k, _) -> k.isNotEmpty() && key.contains(k) }?.value
            ?: return null
        if (s.closed <= 0) return null
        val meanPct = if (s.costSol > 0.0) (s.pnlSol / s.costSol) * 100.0 else 0.0
        return Expectancy6915(
            source = key,
            closed = s.closed,
            wins = s.wins,
            winRatePct = s.wins * 100.0 / s.closed,
            pnlSol = s.pnlSol,
            costSol = s.costSol,
            meanPnlPct = if (meanPct.isFinite()) meanPct.coerceIn(-100.0, 5000.0) else 0.0,
        )
    }

    fun recordDiscovered(source: String, hasRugOverlay: Boolean = false) = update(source) { discovered++; if (hasRugOverlay) this.rugOverlay++ }
    fun recordAdmitted(source: String, hasRugOverlay: Boolean = false) = update(source) { admitted++; if (hasRugOverlay) this.rugOverlay++ }
    fun recordOpened(source: String) = update(source) { opened++ }
    fun recordClosed(source: String, trade: Trade) = update(source) {
        closed++
        if ((trade.netPnlSol.takeIf { it != 0.0 } ?: trade.pnlSol) > 0.0) wins++
        pnlSol += (trade.netPnlSol.takeIf { it.isFinite() && it != 0.0 } ?: trade.pnlSol).takeIf { it.isFinite() } ?: 0.0
        costSol += trade.feeSol.takeIf { it.isFinite() } ?: 0.0
        holdMin += ((trade.ts - trade.entryTsMs).coerceAtLeast(0L).toDouble() / 60_000.0).coerceAtMost(24.0 * 60.0)
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

    fun exportState(): String = JSONArray().also { a ->
        stats.entries.take(MAX_FAMILIES).forEach { (k, s) -> synchronized(s) { a.put(JSONObject().put("k", k).put("d", s.discovered).put("a", s.admitted).put("o", s.opened).put("c", s.closed).put("w", s.wins).put("p", s.pnlSol).put("cost", s.costSol).put("h", s.holdMin).put("r", s.rugOverlay)) } }
    }.toString()
    fun importState(raw: String?) {
        if (raw.isNullOrBlank()) return
        try { val a = JSONArray(raw); stats.clear(); for (i in 0 until a.length().coerceAtMost(MAX_FAMILIES)) { val o = a.optJSONObject(i) ?: continue; val k = o.optString("k"); if (k.isNotBlank()) stats[k] = Stat(o.optInt("d"), o.optInt("a"), o.optInt("o"), o.optInt("c"), o.optInt("w"), o.optDouble("p"), o.optDouble("cost"), o.optDouble("h"), o.optInt("r")) } } catch (_: Throwable) {}
    }
    fun reset() { stats.clear() }

    private fun update(source: String, block: Stat.() -> Unit) {
        val key = family(source)
        val s = stats.getOrPut(key) { Stat() }
        synchronized(s) { s.block() }
        if (stats.size > MAX_FAMILIES) stats.keys.take(stats.size - MAX_FAMILIES).forEach { stats.remove(it) }
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
