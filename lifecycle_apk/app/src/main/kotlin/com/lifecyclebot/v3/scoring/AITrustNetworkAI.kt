package com.lifecyclebot.v3.scoring

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — AITrustNetworkAI
 *
 * The Neural Personality screen shows "Trust: SolArbAI=50% MANIPULA=50%
 * BlueChip=50%" — every sub-AI permanently at default 50%. That means no
 * layer has ever learned to be trusted more than another. The 72%
 * accuracy ceiling is the direct consequence: 25 equally-weighted voters
 * can't rise above their average.
 *
 * This layer is the meta. It records, per sub-AI:
 *   • predictions[ layerName ] += (score>0 ? WIN_EXPECTED : LOSS_EXPECTED)
 *   • outcomes[ layerName ] += (trade actually was win / loss)
 *
 * Trust weight = rolling (200-trade) precision of that layer's positive
 * scores. Layers that predict winners get amplified; layers that fire on
 * losers get muted. Weight range [0.4, 1.6].
 *
 * UnifiedScorer.score() multiplies each ScoreComponent.value by its trust
 * weight before summing. Static 50/50 → actual meta-learning.
 */
object AITrustNetworkAI {

    private const val TAG = "TrustNet"
    private const val PREFS = "ai_trust_network_v1"
    private const val WINDOW = 200

    private data class LayerStat(
        var positivePredictions: Int = 0,
        var positivePredictionsWon: Int = 0,
        var samplesInWindow: ArrayDeque<Int> = ArrayDeque(), // 1 = won after +score, 0 = lost, -1 = score was non-positive
    ) {
        fun recordPositivePrediction(won: Boolean) {
            positivePredictions++
            if (won) positivePredictionsWon++
            samplesInWindow.addLast(if (won) 1 else 0)
            while (samplesInWindow.size > WINDOW) samplesInWindow.removeFirst()
        }
        fun trustWeight(): Double {
            // Precision of positive scores; floor 0.4 so bad layers can still fire.
            val decisiveSamples = samplesInWindow.count { it >= 0 }
            if (decisiveSamples < 20) return 1.0  // not enough data, neutral
            val wins = samplesInWindow.count { it == 1 }
            val precision = wins.toDouble() / decisiveSamples
            // Precision 0.5 (random) → weight 1.0. Precision 1.0 → weight 1.6. 0.0 → 0.4.
            return (0.4 + (precision * 1.2)).coerceIn(0.4, 1.6)
        }
    }

    private val stats = ConcurrentHashMap<String, LayerStat>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val blob = prefs?.getString("stats_json", null) ?: return
            val root = JSONObject(blob)
            root.keys().forEach { key ->
                val o = root.getJSONObject(key)
                stats[key] = LayerStat(
                    positivePredictions = o.optInt("pp", 0),
                    positivePredictionsWon = o.optInt("ppw", 0),
                )
            }
            ErrorLogger.info(TAG, "🧠 Trust net loaded: ${stats.size} layers")
        } catch (_: Exception) {}
    }

    /** Called for each ScoreComponent before aggregation. Returns weighted value. */
    fun weightedValue(layerName: String, value: Int): Int {
        if (value <= 0) return value  // Only positive predictions earn/lose trust
        val stat = stats[layerName] ?: return value
        return (value * stat.trustWeight()).toInt()
    }

    // V5.9.325: outer ring layers that were added AFTER the original inner ring
    // have fewer accumulated samples. Using the same 20-sample floor means inner
    // layers can be downgraded (trust < 1.0) while outer layers stay pinned at
    // the neutral 1.0 default — creating an unintended positive priority for outer.
    // Fix: outer ring needs 50 decisive samples before trust can diverge from 1.0.
    private val OUTER_RING_LAYER_NAMES = setOf(
        "correlationhedgeai", "liquidityexitpathai", "mevdetectionai",
        "stablecoinflowai", "operatorfingerprintai", "sessionedgeai",
        "executioncostpredictorai", "drawdowncircuitai", "capitalefficiencyai",
        "tokendnaclusteringai", "peeralphaverificationai", "newsshockai",
        "fundingrateawarenessai", "orderbookimbalancepulseai",
    )

    fun getTrustWeight(layerName: String): Double {
        val stat = stats[layerName] ?: return 1.0
        val decisiveSamples = stat.samplesInWindow.count { it >= 0 }
        // V5.9.325: outer ring needs 50 decisive samples (inner needs 20) before
        // trust can be adjusted from neutral 1.0. Without this, inner layers that
        // underperform get downgraded while outer layers stay at 1.0 "by default."
        val minSamples = if (layerName.lowercase() in OUTER_RING_LAYER_NAMES) 50 else 20
        if (decisiveSamples < minSamples) return 1.0
        return stat.trustWeight()
    }

    /**
     * Called after a trade closes. Feed every layer's prediction on the
     * entry candidate and whether the trade ultimately won.
     */
    fun recordTradeOutcome(layerScores: Map<String, Int>, won: Boolean) {
        layerScores.forEach { (layer, score) ->
            if (score > 0) {
                stats.getOrPut(layer) { LayerStat() }.recordPositivePrediction(won)
            }
        }
        save()
    }

    private fun save() {
        val p = prefs ?: return
        try {
            val root = JSONObject()
            stats.forEach { (k, v) ->
                root.put(k, JSONObject().apply {
                    put("pp", v.positivePredictions)
                    put("ppw", v.positivePredictionsWon)
                })
            }
            p.edit().putString("stats_json", root.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Debug snapshot for UI/logs. */
    fun topWeights(n: Int = 5): List<Pair<String, Double>> =
        stats.entries.map { it.key to it.value.trustWeight() }
            .sortedByDescending { it.second }
            .take(n)
}
