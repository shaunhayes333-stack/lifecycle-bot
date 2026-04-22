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

    fun getTrustWeight(layerName: String): Double {
        return stats[layerName]?.trustWeight() ?: 1.0
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
