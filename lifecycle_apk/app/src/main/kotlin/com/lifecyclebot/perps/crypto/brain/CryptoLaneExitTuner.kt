package com.lifecyclebot.perps.crypto.brain

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1442 — Isolated LaneExitTuner analogue for the CRYPTO universe.
 *
 * Mirrors [com.lifecyclebot.engine.learning.LaneExitTuner] — a per-lane
 * (tier) outcome window that learns whether the lane is profitable enough
 * to keep its widened-stop preference. Returns a [Verdict] that the
 * CryptoAltTrader's exit path consults to decide whether to widen the
 * stop or run the default.
 */
object CryptoLaneExitTuner {

    enum class Verdict {
        WIDEN_STOPS,        // lane is profitable + low PF → run wider stops
        DEFAULT,            // neutral lane → use static SL
        TIGHTEN_STOPS,      // lane is bleeding → tighten stops
    }

    private data class Sample(val pnlPct: Double, val pnlSol: Double, val win: Boolean)

    private data class LaneStats(
        val window: ArrayDeque<Sample> = ArrayDeque(),
    ) {
        private val MAX = 100
        fun add(s: Sample) {
            window.addLast(s)
            while (window.size > MAX) window.removeFirst()
        }
        fun n(): Int = window.size
        fun winRate(): Double {
            val w = window.count { it.win }
            return if (n() == 0) 0.0 else w.toDouble() / n().toDouble()
        }
        fun netSol(): Double = window.sumOf { it.pnlSol }
        fun profitFactor(): Double {
            val wins = window.filter { it.pnlPct > 0 }.sumOf { it.pnlPct }
            val losses = -window.filter { it.pnlPct < 0 }.sumOf { it.pnlPct }
            return if (losses == 0.0) Double.POSITIVE_INFINITY else wins / losses
        }
    }

    private val lanes = ConcurrentHashMap<String, LaneStats>()

    fun recordClose(tier: String, pnlPct: Double, pnlSol: Double) {
        val s = lanes.getOrPut(tier.uppercase()) { LaneStats() }
        s.add(Sample(pnlPct = pnlPct, pnlSol = pnlSol, win = pnlPct > 0))
    }

    fun verdict(tier: String): Verdict {
        val s = lanes[tier.uppercase()] ?: return Verdict.DEFAULT
        if (s.n() < 25) return Verdict.DEFAULT
        val pf = s.profitFactor()
        val net = s.netSol()
        return when {
            net <= 0.0 || pf < 1.0 -> Verdict.TIGHTEN_STOPS
            net > 0.0 && pf >= 1.5 -> Verdict.WIDEN_STOPS
            else -> Verdict.DEFAULT
        }
    }

    fun summary(): String {
        if (lanes.isEmpty()) return "Crypto LaneExitTuner: no samples"
        val sb = StringBuilder("Crypto LaneExitTuner per-tier:\n")
        for ((k, v) in lanes.entries.sortedBy { it.key }) {
            val pf = v.profitFactor()
            val pfStr = if (pf.isInfinite()) "∞" else "%.2f".format(pf)
            sb.append("  $k  n=${v.n()}  WR=${"%.0f".format(v.winRate() * 100)}%  PF=$pfStr  netSol=${"%.4f".format(v.netSol())}  → ${verdict(k)}\n")
        }
        return sb.toString()
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    private const val K_BLOB = "let.json"

    fun loadFrom(state: CryptoBrainState) {
        lanes.clear()
        val blob = state.getString(K_BLOB, "")
        if (blob.isBlank()) return
        try {
            val obj = org.json.JSONObject(blob)
            for (k in obj.keys()) {
                val arr = obj.getJSONArray(k)
                val s = LaneStats()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    s.add(Sample(
                        pnlPct = o.optDouble("p", 0.0),
                        pnlSol = o.optDouble("s", 0.0),
                        win    = o.optBoolean("w", false),
                    ))
                }
                lanes[k] = s
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        val obj = org.json.JSONObject()
        for ((k, v) in lanes) {
            val arr = org.json.JSONArray()
            for (s in v.window) {
                val o = org.json.JSONObject()
                o.put("p", s.pnlPct); o.put("s", s.pnlSol); o.put("w", s.win)
                arr.put(o)
            }
            obj.put(k, arr)
        }
        ed.putString(K_BLOB, obj.toString())
    }
}
