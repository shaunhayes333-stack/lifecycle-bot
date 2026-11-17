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

    // V5.0.8535 §C08 — a scratch (|pnl| < 1 %) entered pnlSum but neither wins nor losses, so meanPnl = pnlSum / (wins+losses)
    // divided scratch money by a denominator that excluded it (one -4 % loss + ten -0.9 % scratches read mean -13 %).
    // Trainable money and the trainable count live together; every settled close is counted on its own line.
    internal data class Bucket(
        var wins: Int = 0,
        var losses: Int = 0,
        var pnlSum: Double = 0.0,
        var scratches: Int = 0,
        var pnlSumTrainable: Double = 0.0,
    ) {
        /** Trainable closes (net win or net loss). */
        fun n(): Int = wins + losses
        /** Every settled close. */
        fun settledN(): Int = wins + losses + scratches
        fun lossRate(): Double = if (n() == 0) 0.0 else losses.toDouble() / n().toDouble()
        /** Mean over the trainable closes — the cohort the danger rule counts. */
        fun meanPnl(): Double = if (n() == 0) 0.0 else pnlSumTrainable / n().toDouble()
        /** Mean over every settled close. */
        fun meanPnlAll(): Double = if (settledN() == 0) 0.0 else pnlSum / settledN().toDouble()
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

    // V5.0.8832: a paper loss is evidence about the paper execution model, not
    // a proven live-loss bucket that may shrink or shadow the live trader.
    private fun scopedKey8832(tier: String, score: Int, isPaper: Boolean): String =
        "${if (isPaper) "PAPER" else "LIVE"}|${key(tier, score)}"

    fun record(tier: String, score: Int, pnlPct: Double, isPaper: Boolean = false) {
        val k = scopedKey8832(tier, score, isPaper)
        val b = buckets.getOrPut(k) { Bucket() }
        if (!pnlPct.isFinite()) return
        synchronized(b) {
            when (CryptoOutcome8535.classify(pnlPct)) {
                CryptoOutcome8535.Kind.NET_WIN -> { b.wins++; b.pnlSumTrainable += pnlPct }
                CryptoOutcome8535.Kind.NET_LOSS -> { b.losses++; b.pnlSumTrainable += pnlPct }
                else -> b.scratches++
            }
            b.pnlSum += pnlPct
        }
    }

    /** V5.0.8535 — the bucket's counts for a test or report: [wins, losses, scratches, meanTrainable, meanAll]. */
    internal fun snapshot8535(tier: String, score: Int, isPaper: Boolean = false): DoubleArray? = buckets[scopedKey8832(tier, score, isPaper)]?.let {
        doubleArrayOf(it.wins.toDouble(), it.losses.toDouble(), it.scratches.toDouble(), it.meanPnl(), it.meanPnlAll())
    }

    /** Multiplicative sizing nudge for the entry path. */
    fun recommendedSizeMult(tier: String, score: Int, isPaper: Boolean = false): Double {
        val b = buckets[scopedKey8832(tier, score, isPaper)] ?: return 1.0
        if (!b.isDangerous) return 1.0
        // Deeper bleed → smaller size, floor at 0.05x.
        val byPnl = (1.0 + (b.meanPnl() / 20.0)).coerceIn(0.05, 1.0)
        val byLoss = (1.0 - (b.lossRate() - 0.75) * 2.0).coerceIn(0.05, 1.0)
        return (byPnl * byLoss).coerceIn(0.05, 1.0)
    }

    /** True → route the LIVE entry to paper-mode shadow book. */
    fun recommendedShadowOnly(tier: String, score: Int, isPaper: Boolean = false): Boolean {
        val b = buckets[scopedKey8832(tier, score, isPaper)] ?: return false
        return b.isShadowOnly
    }

    fun summary(): String {
        val danger = buckets.entries.filter { it.value.isDangerous }
        if (danger.isEmpty()) return "Crypto LosingPatterns: ✅ no danger buckets"
        val sb = StringBuilder("Crypto LosingPatterns danger:\n")
        danger.sortedByDescending { it.value.losses }.take(8).forEach { (k, v) ->
            val shadow = if (v.isShadowOnly) "  🔒 SHADOW_TRAIN_ONLY" else ""
            sb.append("  $k  W=${v.wins} L=${v.losses} S=${v.scratches}  meanTrainable=${"%+.2f".format(v.meanPnl())}% meanAll=${"%+.2f".format(v.meanPnlAll())}%$shadow\n")
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
                val p = o.optDouble("p", 0.0)
                // Pre-8832 state has no execution mode; it cannot earn live
                // authority. Preserve it as research-only historical paper.
                val scoped8832 = if (k.startsWith("LIVE|") || k.startsWith("PAPER|")) k else "PAPER|$k"
                buckets[scoped8832] = Bucket(
                    wins = o.optInt("w", 0),
                    losses = o.optInt("l", 0),
                    pnlSum = p,
                    scratches = o.optInt("s", 0),
                    pnlSumTrainable = if (o.has("pt")) o.optDouble("pt", p) else p,   // a pre-8535 blob kept one sum: carried as the trainable sum
                )
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        val obj = org.json.JSONObject()
        for ((k, v) in buckets) {
            val o = org.json.JSONObject()
            o.put("w", v.wins); o.put("l", v.losses); o.put("p", v.pnlSum); o.put("s", v.scratches); o.put("pt", v.pnlSumTrainable)
            obj.put(k, o)
        }
        ed.putString(K_BLOB, obj.toString())
    }
}
