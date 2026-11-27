package com.lifecyclebot.engine.truth

import org.json.JSONArray
import org.json.JSONObject

/** Frozen inputs to the existing forward play and edge-doctrine transformations. No live exit policy is changed. */
data class ForwardLabelRecipe9043(
    val decidedAtMs: Long,
    val decision: Play, val scalp: Play, val long: Play,
    val runner: Boolean, val meme: Boolean,
    val sellLatencyMs: Long, val entryLatencyMs: Long, val windowFloorPct: Double,
    val edgeStopPct: Double, val doctrineKeep: Double,
) {
    data class Play(val stopPct: Double, val tiers: List<Pair<Double, Double>>) {
        fun json9043(): JSONObject = JSONObject().put("stop", stopPct.toString())
            .put("tiers", JSONArray(tiers.map { JSONArray(listOf(it.first, it.second)) }))
    }

    fun play9043(longFamily: Boolean?): Play = when (longFamily) { null -> decision; false -> scalp; true -> long }

    fun valid9043(): Boolean = decidedAtMs > 0L && sellLatencyMs > 0L && entryLatencyMs >= 0L &&
        windowFloorPct.isFinite() && windowFloorPct > 0.0 && edgeStopPct in 3.0..40.0 &&
        doctrineKeep.isFinite() && doctrineKeep in 0.0..1.0 && listOf(decision, scalp, long).all { play ->
            (play.stopPct.isNaN() || play.stopPct.isFinite() && play.stopPct >= 0.0) && play.tiers.size in 1..32 &&
                play.tiers.all { (pct, fraction) -> pct.isFinite() && pct > 0.0 && fraction.isFinite() && fraction in 0.0..1.0 }
        }

    fun json9043(): JSONObject = JSONObject().put("recipe", RECIPE).put("decidedAtMs", decidedAtMs)
        .put("decision", decision.json9043()).put("scalp", scalp.json9043()).put("long", long.json9043())
        .put("runner", runner).put("meme", meme).put("sellLatencyMs", sellLatencyMs).put("entryLatencyMs", entryLatencyMs)
        .put("windowFloorPct", windowFloorPct).put("edgeStopPct", edgeStopPct).put("doctrineKeep", doctrineKeep)

    /** Versioned copy of the existing 8910/8784 paper-doctrine arithmetic, with its decision-time stop and keep. */
    fun edgeNet9043(grossPct: Double, minPct: Double, heldPeakPct: Double, costPct: Double, netPct: Double): Double {
        if (!meme || !netPct.isFinite() || !grossPct.isFinite() || !minPct.isFinite() || !heldPeakPct.isFinite() || !costPct.isFinite()) return netPct
        if (minPct <= -edgeStopPct) return -edgeStopPct - costPct
        if (heldPeakPct >= 10.0) return maxOf(grossPct, (line9043(1.0 + heldPeakPct / 100.0) - 1.0) * 100.0) - costPct
        return grossPct - costPct
    }

    private fun line9043(peak: Double): Double {
        fun halfLine(p: Double): Double = if (p < 2.0) maxOf(1.05, 1.0 + 0.5 * (p - 1.0)) else maxOf(1.5, p * doctrineKeep)
        val half = halfLine(peak)
        val withStop = peak * (1.0 - edgeStopPct.coerceAtMost(95.0) / 100.0)
        if (peak >= 2.0) return half
        val base = maxOf(1.05, minOf(half, withStop))
        if (peak <= 1.8) return base
        val t = (peak - 1.8) / (2.0 - 1.8)
        return minOf(half, base + (halfLine(2.0) - base) * t).coerceAtLeast(base)
    }

    companion object {
        const val RECIPE = "FORWARD_8366_EDGE_8910_V1"
        const val LEGACY = "LEGACY_DYNAMIC_OR_EXTERNAL"

        /** No current policy is consulted during restore. Unknown recipes are not treated as legacy. */
        fun decode9043(row: JSONObject?): ForwardLabelRecipe9043? {
            return try {
            if (row == null || row.optString("recipe") != RECIPE) null else {
                fun play(key: String): Play {
                    val p = row.getJSONObject(key)
                    val stop = p.getString("stop").toDouble()
                    require(stop.isNaN() || stop.isFinite() && stop >= 0.0)
                    val a = p.getJSONArray("tiers")
                    require(a.length() in 1..32)
                    val tiers = (0 until a.length()).map { i ->
                        val r = a.getJSONArray(i); require(r.length() == 2)
                        val pct = r.getDouble(0); val fraction = r.getDouble(1)
                        require(pct.isFinite() && pct > 0.0 && fraction.isFinite() && fraction in 0.0..1.0)
                        pct to fraction
                    }
                    return Play(stop, tiers)
                }
                val value = ForwardLabelRecipe9043(row.getLong("decidedAtMs"), play("decision"), play("scalp"), play("long"),
                    row.getBoolean("runner"), row.getBoolean("meme"), row.getLong("sellLatencyMs"), row.getLong("entryLatencyMs"),
                    row.getDouble("windowFloorPct"), row.getDouble("edgeStopPct"), row.getDouble("doctrineKeep"))
                require(value.valid9043())
                value
            }
            } catch (_: Exception) { null }
        }
    }
}
