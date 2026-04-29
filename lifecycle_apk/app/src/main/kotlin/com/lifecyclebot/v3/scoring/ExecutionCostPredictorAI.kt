package com.lifecyclebot.v3.scoring

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — ExecutionCostPredictorAI
 *
 * Learns per-liquidity-band "expected vs. quoted" slippage on live swaps.
 * Jupiter's quote is optimistic — real fills include Jito tips, pool drift
 * during the signing window, and route changes. This layer records the
 * delta per mcap/liquidity band and offsets TP targets in future trades
 * of similar size.
 *
 * Wired via:
 *   Executor.recordFill(...) — after every live/paper sell, we feed
 *     (liqBand, quotedPrice, realizedPrice) into learn().
 *   UnifiedScorer → score() penalizes the candidate if the liquidity band
 *     has historically exceeded 5% unexpected slippage.
 */
object ExecutionCostPredictorAI {

    private const val TAG = "ExecCost"
    private const val PREFS = "exec_cost_predictor_v1"

    private data class Band(var samples: Int = 0, var sumSlipPct: Double = 0.0) {
        fun avgSlipPct(): Double = if (samples == 0) 0.0 else sumSlipPct / samples
    }

    private val bands = ConcurrentHashMap<String, Band>()

    /** V5.9.362 — wiring health: total samples across all liquidity bands vs floor 10. */
    fun getWiringHealth(): Triple<Int, Int, Boolean> {
        val total = bands.values.sumOf { it.samples }
        return Triple(total, 10, total >= 10)
    }
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val blob = prefs?.getString("bands_json", null) ?: return
            val root = JSONObject(blob)
            root.keys().forEach { k ->
                val o = root.getJSONObject(k)
                bands[k] = Band(o.optInt("n", 0), o.optDouble("s", 0.0))
            }
            ErrorLogger.info(TAG, "📉 loaded ${bands.size} liquidity bands")
        } catch (_: Exception) {}
    }

    private fun bandFor(liq: Double): String = when {
        liq < 5_000    -> "LIQ_0_5K"
        liq < 25_000   -> "LIQ_5_25K"
        liq < 100_000  -> "LIQ_25_100K"
        liq < 500_000  -> "LIQ_100K_500K"
        else           -> "LIQ_500K_PLUS"
    }

    fun learn(liqUsd: Double, quotedPxUsd: Double, realizedPxUsd: Double) {
        if (quotedPxUsd <= 0 || realizedPxUsd <= 0) return
        val slip = kotlin.math.abs((quotedPxUsd - realizedPxUsd) / quotedPxUsd * 100.0)
        val b = bands.getOrPut(bandFor(liqUsd)) { Band() }
        b.samples++
        b.sumSlipPct += slip
        // Decay so learning stays current
        if (b.samples > 200) {
            b.sumSlipPct *= 0.5
            b.samples = (b.samples * 0.5).toInt()
        }
        save()
    }

    fun expectedExtraSlipPct(liqUsd: Double): Double = bands[bandFor(liqUsd)]?.avgSlipPct() ?: 0.0

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val slip = expectedExtraSlipPct(candidate.liquidityUsd)
        val value = when {
            slip > 8.0 -> -6
            slip > 4.0 -> -3
            slip > 2.0 -> -1
            else       -> 0
        }
        return ScoreComponent(
            "ExecutionCostPredictorAI", value,
            "📉 expected ${"%.1f".format(slip)}%% slip in ${bandFor(candidate.liquidityUsd)}"
        )
    }

    private fun save() {
        val p = prefs ?: return
        try {
            val root = JSONObject()
            bands.forEach { (k, v) ->
                root.put(k, JSONObject().apply { put("n", v.samples); put("s", v.sumSlipPct) })
            }
            p.edit().putString("bands_json", root.toString()).apply()
        } catch (_: Exception) {}
    }
}
