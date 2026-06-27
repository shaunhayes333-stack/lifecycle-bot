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
        "$k d=${s.discovered} a=${s.admitted} o=${s.opened} c=${s.closed} wr=${wr.fmt(1)} avgPnl=${pf.fmt(5)} cost=${s.costSol.fmt(5)} rug=${s.rugOverlay}"
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
