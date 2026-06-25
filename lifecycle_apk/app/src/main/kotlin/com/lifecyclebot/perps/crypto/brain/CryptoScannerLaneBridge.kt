package com.lifecyclebot.perps.crypto.brain

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4151 — CRYPTO SCANNER → LANE BRIDGE BRAIN (ISOLATED)
 * ════════════════════════════════════════════════════════════════════════════
 * Mirrors `engine.ScannerLaneBridge` but persists to its own SharedPreferences
 * file (`crypto_scanner_lane_bridge`). Per-(source, lane) WR + mean-PnL
 * memory used for affinity bias (-15..+15 score nudges) and a hard route
 * veto for proven net-toxic pairs. Identical algorithm to the meme version.
 */
object CryptoScannerLaneBridge {

    private const val PREFS_NAME = "crypto_scanner_lane_bridge"
    private const val WINDOW = 60
    private const val MIN_N_FOR_BIAS = 8

    private data class Bucket(
        val recent: ArrayDeque<Double> = ArrayDeque(WINDOW + 1),
        @Volatile var n: Int = 0,
        @Volatile var wrPct: Double = 0.0,
        @Volatile var meanPnlPct: Double = 0.0,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
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

    fun affinityBias(src: String, lane: String): Int {
        val b = buckets[key(src, lane)] ?: return 0
        if (b.n < MIN_N_FOR_BIAS) return 0
        return when {
            b.wrPct >= 65.0 && b.meanPnlPct >= 20.0 -> +12
            b.wrPct >= 50.0 && b.meanPnlPct > 0.0    -> +6
            b.wrPct <= 10.0 && b.meanPnlPct <= -25.0 -> -15
            b.wrPct <= 20.0 && b.meanPnlPct < -10.0  -> -10
            else -> 0
        }
    }

    fun shouldRoute(src: String, lane: String): Boolean {
        val b = buckets[key(src, lane)] ?: return true
        if (b.n < MIN_N_FOR_BIAS * 2) return true
        return !(b.wrPct <= 5.0 && b.meanPnlPct <= -40.0)
    }

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
