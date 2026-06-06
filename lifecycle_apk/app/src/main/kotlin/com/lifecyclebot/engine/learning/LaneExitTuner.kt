package com.lifecyclebot.engine.learning

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1379 — LANE EXIT TUNER (closed-loop exit-ladder auto-tuning).
 *
 * Closes the gap where V3 meme lanes (MOONSHOT/SHITCOIN/TREASURY/BLUECHIP/
 * MANIPULATED/SNIPER/QUALITY) read STATIC take-profit/stop-loss literals that
 * FluidLearningAI only lerps along a global bootstrap->mature curve (and leaves
 * high-upside modes unchanged). Nothing moved a lane's TP/SL based on how that
 * lane ACTUALLY performed. This makes the exit ladder a real feedback loop.
 *
 * Emits two BOUNDED multipliers per lane from realized outcomes:
 *   tpMult in [0.60,1.40] applied to the lane base take-profit
 *   slMult in [0.70,1.30] applied to the MAGNITUDE of the lane base stop
 *
 * DOCTRINE GUARANTEES:
 *  - The -15% unconditional hard floor is NEVER touched here. slMult only scales
 *    a lane's own (tighter-than-floor) stop; the caller still clamps to -15%.
 *  - Soft-shape only: never vetoes/zeroes a trade. Fail-open: errors return 1.0.
 *  - Bootstrap-safe: requires MIN_SAMPLE matured closes per lane before nudging
 *    (doctrine #5 - read the curve, not the noise).
 *  - Persisted via LearningPersistence (sacred-persistence rule).
 */
object LaneExitTuner {

    private const val TP_MIN = 0.60
    private const val TP_MAX = 1.40
    private const val SL_MIN = 0.70
    private const val SL_MAX = 1.30
    private const val STEP   = 0.04

    private const val WINDOW       = 60
    private const val MIN_SAMPLE   = 20
    private const val RECALC_EVERY = 10

    private data class Outcome(
        val pnlPct: Double,
        val peakPct: Double,
        val win: Boolean,
        val stopHit: Boolean,
    )

    private class LaneState {
        val window = ArrayDeque<Outcome>()
        var sinceRecalc = 0
        var lifetimeCloses = 0L
        @Volatile var tpMult = 1.0
        @Volatile var slMult = 1.0
    }

    private val lanes = ConcurrentHashMap<String, LaneState>()

    private fun canon(lane: String): String {
        val u = lane.uppercase()
        return when {
            u.contains("MOONSHOT")                          -> "MOONSHOT"
            u.contains("MANIPUL")                           -> "MANIPULATED"
            u.contains("SHITCOIN") || u.contains("EXPRESS") -> "SHITCOIN"
            u.contains("TREASURY") || u.contains("CASH")    -> "TREASURY"
            u.contains("SNIPER")                            -> "SNIPER"
            u.contains("QUALITY")                           -> "QUALITY"
            u.contains("BLUE")                              -> "BLUECHIP"
            u.contains("DIP")                               -> "DIP_HUNTER"
            else                                            -> "STANDARD"
        }
    }

    private val STOP_REASONS = listOf(
        "STOP_LOSS", "HARD_FLOOR", "DISTRIBUTION_STOP", "V8_DISTRIBUTION",
        "RAPID_ENTRY_PROTECT_STOP", "SWEEP_FLUID_FLOOR", "PROTECT_STOP"
    )

    fun recordClose(lane: String, pnlPct: Double, peakPct: Double, exitReason: String) {
        try {
            val key = canon(lane)
            val st = lanes.getOrPut(key) { LaneState() }
            val stopHit = STOP_REASONS.any { exitReason.uppercase().contains(it) }
            val peakSane = when {
                peakPct.isNaN() || peakPct.isInfinite() -> 0.0
                peakPct < 0.0 -> 0.0
                peakPct > 5000.0 -> 5000.0
                else -> peakPct
            }
            val o = Outcome(
                pnlPct = if (pnlPct.isNaN() || pnlPct.isInfinite()) 0.0 else pnlPct,
                peakPct = peakSane,
                win = pnlPct > 0.0,
                stopHit = stopHit,
            )
            synchronized(st) {
                st.window.addLast(o)
                while (st.window.size > WINDOW) st.window.removeFirst()
                st.lifetimeCloses++
                st.sinceRecalc++
                if (st.sinceRecalc >= RECALC_EVERY && st.window.size >= MIN_SAMPLE) {
                    st.sinceRecalc = 0
                    recompute(st)
                }
            }
        } catch (_: Throwable) { }
    }

    private fun recompute(st: LaneState) {
        val w = st.window.toList()
        val n = w.size
        if (n < MIN_SAMPLE) return
        val wins = w.count { it.win }
        val wr = wins.toDouble() / n
        val avgPeak = w.map { it.peakPct }.average()
        val avgReal = w.map { it.pnlPct }.average()
        val giveBack = avgPeak - avgReal
        val slHitRate = w.count { it.stopHit }.toDouble() / n
        val losers = w.filter { !it.win }
        val avgLoss = if (losers.isEmpty()) 0.0 else losers.map { it.pnlPct }.average()

        var tp = st.tpMult
        when {
            wr >= 0.45 && avgPeak >= 25.0 && giveBack >= 15.0 -> tp += STEP
            wr < 0.30 && avgPeak >= 20.0 && avgReal <= 5.0 -> tp -= STEP
            wr >= 0.50 && giveBack < 8.0 && tp < 1.0 -> tp += STEP * 0.5
        }
        st.tpMult = tp.coerceIn(TP_MIN, TP_MAX)

        var sl = st.slMult
        when {
            slHitRate >= 0.50 && avgPeak < 8.0 -> sl += STEP
            slHitRate < 0.25 && avgLoss <= -10.0 -> sl -= STEP
        }
        st.slMult = sl.coerceIn(SL_MIN, SL_MAX)
    }

    fun getTpMult(lane: String): Double = try {
        lanes[canon(lane)]?.tpMult ?: 1.0
    } catch (_: Throwable) { 1.0 }

    fun getSlMult(lane: String): Double = try {
        lanes[canon(lane)]?.slMult ?: 1.0
    } catch (_: Throwable) { 1.0 }

    fun formatForPipelineDump(): String = try {
        if (lanes.isEmpty()) return ""
        buildString {
            append("\n===== Lane Exit Tuner (V5.9.1379 - closed-loop TP/SL) =====\n")
            lanes.entries.sortedBy { it.key }.forEach { (lane, st) ->
                val n = st.window.size
                val matured = n >= MIN_SAMPLE
                val tag = if (matured) "" else "  (bootstrap n=$n - neutral)"
                append(String.format(
                    "  %-12s tpMult=%.2f  slMult=%.2f  lifetime=%d%s\n",
                    lane, st.tpMult, st.slMult, st.lifetimeCloses, tag))
            }
            append("  Read: tpMult>1 => lane lets winners run further; <1 => banks sooner.\n")
            append("        slMult>1 => wider stop (still clamped to -15%); <1 => tighter.\n")
        }
    } catch (_: Throwable) { "" }

    fun exportState(): String = try {
        val root = JSONObject()
        lanes.forEach { (lane, st) ->
            synchronized(st) {
                val o = JSONObject()
                o.put("tp", st.tpMult)
                o.put("sl", st.slMult)
                o.put("life", st.lifetimeCloses)
                val arr = org.json.JSONArray()
                st.window.forEach { oc ->
                    arr.put(JSONObject().apply {
                        put("p", oc.pnlPct); put("k", oc.peakPct)
                        put("w", oc.win); put("s", oc.stopHit)
                    })
                }
                o.put("win", arr)
                o.put("sr", st.sinceRecalc)
                root.put(lane, o)
            }
        }
        root.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank()) return
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val lane = keys.next()
                val o = root.optJSONObject(lane) ?: continue
                val st = lanes.getOrPut(lane) { LaneState() }
                synchronized(st) {
                    st.tpMult = o.optDouble("tp", 1.0).coerceIn(TP_MIN, TP_MAX)
                    st.slMult = o.optDouble("sl", 1.0).coerceIn(SL_MIN, SL_MAX)
                    st.lifetimeCloses = o.optLong("life", 0L)
                    st.sinceRecalc = o.optInt("sr", 0)
                    st.window.clear()
                    val arr = o.optJSONArray("win")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            st.window.addLast(Outcome(
                                pnlPct = e.optDouble("p", 0.0),
                                peakPct = e.optDouble("k", 0.0),
                                win = e.optBoolean("w", false),
                                stopHit = e.optBoolean("s", false),
                            ))
                        }
                        while (st.window.size > WINDOW) st.window.removeFirst()
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    fun reset() { lanes.clear() }
}
