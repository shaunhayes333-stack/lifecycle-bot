package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4132 — SCANNER → LANE BRIDGE BRAIN
 * ════════════════════════════════════════════════════════════════════════════
 * Operator: "give the scanner lanes brains and wire and educate them on their
 * job ie providing the right tokens at the right times for their respective
 * lanes."
 *
 * Existing ScannerSourceBrain learns ONE multiplier per source (e.g.,
 * "PUMP_FUN_NEW = 0.67×"). That's coarse — it can't distinguish that
 * PUMP_FUN_NEW tokens do great in MOONSHOT but terribly in SHITCOIN.
 *
 * This bridge records per-(source, downstream-lane) win rates and exposes:
 *   - bestLaneFor(source): which lane historically converts this source best
 *   - laneAffinityBoost(source, lane): per-pair multiplier [-15..+15] score bias
 *   - shouldRoute(source, lane): false if the pair has proven net-toxic
 *
 * Bounded, fail-open. Toxic pairs sink the affinity; gold pairs lift it.
 * Asymmetric (toxic dominates gold ~2×) — same doctrine as PatternGoldenGoose.
 */
object ScannerLaneBridge {

    private const val PREFS_NAME = "scanner_lane_bridge"
    private const val WINDOW = 60   // rolling sample size per (src, lane) bucket
    private const val MIN_N_FOR_BIAS = 8

    private data class Bucket(
        val recent: ArrayDeque<Double> = ArrayDeque(WINDOW + 1),
        @Volatile var n: Int = 0,
        @Volatile var wrPct: Double = 0.0,
        @Volatile var meanPnlPct: Double = 0.0,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>() // key="$src|$lane"
    @Volatile private var prefs: SharedPreferences? = null

    private fun key(src: String, lane: String): String =
        "${src.uppercase()}|${lane.uppercase()}"

    fun init(context: Context) {
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            restoreAll(p)
        } catch (_: Throwable) {}
    }

    private fun restoreAll(p: SharedPreferences) {
        try {
            for ((k, v) in p.all) {
                if (v !is String || !k.contains('|')) continue
                val bucket = buckets.getOrPut(k) { Bucket() }
                synchronized(bucket) {
                    bucket.recent.clear()
                    v.split(',').mapNotNull { it.toDoubleOrNull() }.forEach { pnl ->
                        if (bucket.recent.size >= WINDOW) bucket.recent.removeFirst()
                        bucket.recent.addLast(pnl)
                    }
                }
                recomputeOne(bucket)
            }
        } catch (_: Throwable) {}
    }

    private fun persistOne(k: String, b: Bucket) {
        val p = prefs ?: return
        try {
            val snap = synchronized(b) { b.recent.toList() }
            p.edit().putString(k, snap.joinToString(",") { it.toString() }).apply()
        } catch (_: Throwable) {}
    }

    /** Called from close path. `src` should be the scanner source the token entered on. */
    fun recordOutcome(src: String, lane: String, pnlPct: Double) {
        try {
            if (src.isBlank() || lane.isBlank()) return
            if (pnlPct.isNaN() || pnlPct.isInfinite()) return
            val sane = pnlPct.coerceIn(-100.0, 5000.0)
            val k = key(src, lane)
            val b = buckets.getOrPut(k) { Bucket() }
            synchronized(b) {
                b.recent.addLast(sane)
                while (b.recent.size > WINDOW) b.recent.removeFirst()
            }
            recomputeOne(b); persistOne(k, b)
        } catch (_: Throwable) {}
    }

    private fun recomputeOne(b: Bucket) {
        val snap = synchronized(b) { b.recent.toList() }
        val n = snap.size
        val wins = snap.count { it > 0.0 }
        val wr = if (n > 0) (wins.toDouble() / n) * 100.0 else 0.0
        val mean = if (n > 0) snap.average() else 0.0
        b.n = n; b.wrPct = wr; b.meanPnlPct = mean
    }

    /** Score bias [-15..+15] for an entry coming via `src` into `lane`. */
    fun affinityBias(src: String, lane: String): Int {
        val b = buckets[key(src, lane)] ?: return 0
        if (b.n < MIN_N_FOR_BIAS) return 0
        // Asymmetric — toxic dominates gold. Bias on WR + mean PnL combined.
        return when {
            b.wrPct >= 65.0 && b.meanPnlPct >= 20.0 -> +12
            b.wrPct >= 50.0 && b.meanPnlPct > 0.0    -> +6
            b.wrPct <= 10.0 && b.meanPnlPct <= -25.0 -> -15
            b.wrPct <= 20.0 && b.meanPnlPct < -10.0  -> -10
            else -> 0
        }
    }

    /** False iff (src, lane) pair is proven net-toxic. Catastrophic veto. */
    fun shouldRoute(src: String, lane: String): Boolean {
        val b = buckets[key(src, lane)] ?: return true
        if (b.n < MIN_N_FOR_BIAS * 2) return true // need more data for veto
        // Both metrics catastrophic → don't route.
        return !(b.wrPct <= 5.0 && b.meanPnlPct <= -40.0)
    }

    /** Best lane for a given source by mean PnL. Returns null if no data. */
    fun bestLaneFor(src: String): String? {
        val prefix = "${src.uppercase()}|"
        return buckets.entries
            .filter { it.key.startsWith(prefix) && it.value.n >= MIN_N_FOR_BIAS }
            .maxByOrNull { it.value.meanPnlPct }
            ?.key?.substringAfter('|')
    }

    fun tag(src: String, lane: String): String {
        val b = buckets[key(src, lane)] ?: return "${src}_${lane}_NO_DATA"
        return "${src}→${lane}_wr${"%.0f".format(b.wrPct)}_pnl${"%.0f".format(b.meanPnlPct)}%_n${b.n}"
    }
}
