package com.lifecyclebot.engine.truth

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Entry timing is a separate prediction from terminal profit. Never enters the Cortex return ledger. */
internal object SetupTimingGrades8528 {
    data class Plan(val setup: String, val band: String, val horizons: List<Long>, val rankHorizon: Long) {
        fun json() = JSONObject().put("setup", setup).put("band", band)
            .put("horizons", JSONArray(horizons)).put("rank", rankHorizon)
    }
    private val fast = setOf("DEGEN_MICRO_SNIPE", "MICRO_SNIPE", "QUICK_FLIP", "EXHAUSTION_QUICK_FLIP",
        "OPP_EARLY_IGNITION", "VOLUME_IGNITION", "MICRO_CAP_IGNITION", "IGNITION_5M",
        "FAST_CROWD", "ACCELERATING_TAPE", "TX_VELOCITY_BREAK", "FRESH_LAUNCH_MOMENTUM")
    private val patient = listOf("PRE_IGNITION", "ACCUM", "BASE", "COIL", "CONSOLID", "SWING", "HOLD")
    fun plan(setup: String, band: String): Plan? {
        val s = setup.trim().uppercase()
        if (s.isBlank() || s in setOf("NO_TRIGGER", "DATA_INCOMPLETE", "MATCHES_UNRANKABLE", "CLASSIFIER_ERROR", "UNKNOWN")) return null
        val seconds = when {
            s in fast || s.contains("MICRO_SNIPE") -> listOf(15L, 30L, 60L)
            patient.any { s.contains(it) } -> listOf(60L, 300L)
            else -> listOf(30L, 60L, 300L)
        }
        return Plan(s, band, seconds.map { it * 1000L }, seconds[1] * 1000L)
    }
    fun restorePlan(o: JSONObject?): Plan? {
        if (o == null) return null
        val a = o.optJSONArray("horizons") ?: return null
        val hs = (0 until a.length()).map { a.optLong(it) }
        val rank = o.optLong("rank")
        if (hs.isEmpty() || hs.size > 3 || hs.any { it !in setOf(15_000L, 30_000L, 60_000L, 300_000L) } || rank !in hs) return null
        return Plan(o.optString("setup"), o.optString("band"), hs, rank).takeIf { it.setup.isNotBlank() }
    }
    fun grace(horizon: Long): Long = (horizon / 3).coerceIn(5_000L, 30_000L)
    fun inWindow(decisionAt: Long, horizon: Long, sourceAt: Long): Boolean =
        decisionAt > 0 && sourceAt - decisionAt in horizon..(horizon + grace(horizon))

    enum class Grade { WAIT, OBSERVED, UNAVAILABLE }
    fun grade(decisionAt: Long, horizon: Long, basisAt: Long, sourceAt: Long, nowMs: Long, eligible: Boolean): Grade = when {
        eligible && basisAt > 0 && basisAt < sourceAt && basisAt <= decisionAt + horizon &&
            inWindow(decisionAt, horizon, sourceAt) -> Grade.OBSERVED
        nowMs > decisionAt + horizon + grace(horizon) -> Grade.UNAVAILABLE
        else -> Grade.WAIT
    }

    data class Stat(var n: Int = 0, var mean: Double = 0.0, var m2: Double = 0.0) {
        fun add(gross: Double) {
            // Bounded recent evidence. It remains timing direction, never a forecast profit estimate.
            if (n >= 200) { n = 199; m2 *= 199.0 / 200.0 }
            n++; val d = gross - mean; mean += d / n; m2 += d * (gross - mean)
        }
        fun adjustment(): Double {
            if (n < 30) return 0.0
            val se = sqrt((m2.coerceAtLeast(0.0) / (n - 1)) / n)
            return when { mean - 2 * se > 0 -> (mean - 2 * se).coerceAtMost(2.0)
                mean + 2 * se < 0 -> (mean + 2 * se).coerceAtLeast(-2.0)
                else -> 0.0 }
        }
    }
    class Book {
        private val rows = LinkedHashMap<String, Stat>()
        var observed: Long = 0; private set
        var unavailable: Long = 0; private set
        private fun key(lane: String, p: Plan, h: Long) = "${lane.uppercase()}|${p.setup}|${p.band}|$h"
        /** Caller checkpoints this book and done together. Repeated ticks cannot add another contribution. */
        @Synchronized fun observe(lane: String, p: Plan, done: MutableSet<Long>, decisionAt: Long,
                                  basisAt: Long, sourceAt: Long, nowMs: Long, eligible: Boolean, gross: Double) {
            for (h in p.horizons) {
                if (h in done) continue
                when (grade(decisionAt, h, basisAt, sourceAt, nowMs, eligible && gross.isFinite())) {
                    Grade.OBSERVED -> { record(lane, p, h, gross); done.add(h) }
                    Grade.UNAVAILABLE -> { missing(); done.add(h) }
                    Grade.WAIT -> Unit
                }
            }
        }
        @Synchronized fun record(lane: String, p: Plan, horizon: Long, gross: Double) {
            if (horizon !in p.horizons || !gross.isFinite()) return
            val k = key(lane, p, horizon)
            if (k !in rows && rows.size >= 4096) rows.remove(rows.keys.first())
            rows.getOrPut(k) { Stat() }.add(gross.coerceIn(-100.0, 300.0)); observed++
        }
        @Synchronized fun missing() { unavailable++ }
        @Synchronized fun adjustment(lane: String, setup: String, band: String): Double {
            val p = plan(setup, band) ?: return 0.0
            return rows[key(lane, p, p.rankHorizon)]?.adjustment() ?: 0.0
        }
        @Synchronized fun encode(): String = JSONObject().put("observed", observed).put("unavailable", unavailable)
            .put("rows", JSONObject().also { out -> rows.forEach { (k, s) -> out.put(k, JSONArray(listOf(s.n, s.mean, s.m2))) } }).toString()
        @Synchronized fun restore(raw: String?) {
            if (raw.isNullOrBlank()) return
            val o = JSONObject(raw); val r = o.optJSONObject("rows") ?: return
            val restored = LinkedHashMap<String, Stat>()
            for (k in r.keys()) {
                if (restored.size >= 4096) break
                val a = r.optJSONArray(k) ?: continue
                val n = a.optInt(0); val mean = a.optDouble(1); val m2 = a.optDouble(2)
                if (n in 1..200 && mean.isFinite() && m2.isFinite() && m2 >= 0) restored[k] = Stat(n, mean, m2)
            }
            rows.clear(); rows.putAll(restored)
            observed = o.optLong("observed").coerceAtLeast(0); unavailable = o.optLong("unavailable").coerceAtLeast(0)
        }
        @Synchronized fun line() = "setupTiming8528[observed=$observed unavailable=$unavailable cells=${rows.size} clocks=15s/30s/60s/300s]"
    }
}
