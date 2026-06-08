package com.lifecyclebot.perps.crypto.brain

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1442 — Isolated LosingPatternMemory analogue for the CRYPTO universe.
 *
 * Tracks (tier, scoreBand) buckets the same way the meme
 * [com.lifecyclebot.engine.LosingPatternMemory] does, but for crypto only.
 *
 * Bucket key:  "TIER1|S60-69" / "TIER2|S70-79" / "TIER3|S0-49" / ...
 * Danger predicate: n ≥ 20 AND lossRate ≥ 75% AND mean PnL ≤ -4%.
 * SHADOW_TRAIN_ONLY predicate adds: losses ≥ 20 AND meanPnl ≤ -10%.
 *
 * Outputs:
 *   • [recommendedSizeMult]   — 1.0 normally, 0.05–0.5 in danger
 *   • [recommendedShadowOnly] — true → route LIVE to paper book
 */
object CryptoLosingPatternMemory {

    private data class Bucket(
        var wins: Int = 0,
        var losses: Int = 0,
        var pnlSum: Double = 0.0,
    ) {
        fun n(): Int = wins + losses
        fun lossRate(): Double = if (n() == 0) 0.0 else losses.toDouble() / n().toDouble()
        fun meanPnl(): Double = if (n() == 0) 0.0 else pnlSum / n().toDouble()
        val isDangerous: Boolean
            get() = n() >= 20 && lossRate() >= 0.75 && meanPnl() <= -4.0
        val isShadowOnly: Boolean
            get() = isDangerous && losses >= 20 && meanPnl() <= -10.0
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    private fun scoreBand(score: Int): String = when {
        score < 50 -> "S0-49"
        score < 60 -> "S50-59"
        score < 70 -> "S60-69"
        score < 80 -> "S70-79"
        else       -> "S80+"
    }

    fun key(tier: String, score: Int): String = "${tier.uppercase()}|${scoreBand(score)}"

    fun record(tier: String, score: Int, pnlPct: Double) {
        val k = key(tier, score)
        val b = buckets.getOrPut(k) { Bucket() }
        if (pnlPct >= 1.0) b.wins++ else if (pnlPct <= -1.0) b.losses++
        b.pnlSum += pnlPct
    }

    /** Multiplicative sizing nudge for the entry path. */
    fun recommendedSizeMult(tier: String, score: Int): Double {
        val b = buckets[key(tier, score)] ?: return 1.0
        if (!b.isDangerous) return 1.0
        // Deeper bleed → smaller size, floor at 0.05x.
        val byPnl = (1.0 + (b.meanPnl() / 20.0)).coerceIn(0.05, 1.0)
        val byLoss = (1.0 - (b.lossRate() - 0.75) * 2.0).coerceIn(0.05, 1.0)
        return (byPnl * byLoss).coerceIn(0.05, 1.0)
    }

    /** True → route the LIVE entry to paper-mode shadow book. */
    fun recommendedShadowOnly(tier: String, score: Int): Boolean {
        val b = buckets[key(tier, score)] ?: return false
        return b.isShadowOnly
    }

    fun summary(): String {
        val danger = buckets.entries.filter { it.value.isDangerous }
        if (danger.isEmpty()) return "Crypto LosingPatterns: ✅ no danger buckets"
        val sb = StringBuilder("Crypto LosingPatterns danger:\n")
        danger.sortedByDescending { it.value.losses }.take(8).forEach { (k, v) ->
            val shadow = if (v.isShadowOnly) "  🔒 SHADOW_TRAIN_ONLY" else ""
            sb.append("  $k  W=${v.wins} L=${v.losses}  meanPnl=${"%+.2f".format(v.meanPnl())}%$shadow\n")
        }
        return sb.toString()
    }

    // ── Persistence (compact JSON) ────────────────────────────────────────────
    private const val K_BLOB = "lpm.json"

    fun loadFrom(state: CryptoBrainState) {
        buckets.clear()
        val blob = state.getString(K_BLOB, "")
        if (blob.isBlank()) return
        try {
            val obj = org.json.JSONObject(blob)
            for (k in obj.keys()) {
                val o = obj.getJSONObject(k)
                buckets[k] = Bucket(
                    wins = o.optInt("w", 0),
                    losses = o.optInt("l", 0),
                    pnlSum = o.optDouble("p", 0.0),
                )
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        val obj = org.json.JSONObject()
        for ((k, v) in buckets) {
            val o = org.json.JSONObject()
            o.put("w", v.wins); o.put("l", v.losses); o.put("p", v.pnlSum)
            obj.put(k, o)
        }
        ed.putString(K_BLOB, obj.toString())
    }
}
