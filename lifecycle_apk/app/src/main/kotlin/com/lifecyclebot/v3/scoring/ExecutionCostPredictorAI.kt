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
        // V5.0.4184 — POISONED BAND PURGE on boot.
        // V5.0.4181 phantom-priced sells (+210,425% / +242,342% etc.) fed
        // millions-of-percent slip samples into bands → expectedExtraSlipPct()
        // returned 11,726,049% on a normal $5K liq token → F1_SLIP_HARD_REJECT
        // silently killed every buy decision (188 EXEC attempts, 0 BUY ok in
        // V5.0.4183 dump). Any band whose persisted avg slip is past the
        // PHANTOM_LEARN_CEILING is provably poisoned by phantom data — reset
        // it so the predictor can re-learn from real fills. New phantom
        // samples are now rejected at learn() time (see PHANTOM_LEARN_CEILING).
        try { purgePoisonedBands() } catch (_: Throwable) {}
    }

    /**
     * V5.0.4184 — Anything above this slip% in a recorded sample is a
     * phantom-price artifact (real Solana swaps cap well under this even
     * on extreme thin pools / sandwiches). Used both at learn() time to
     * reject the sample and at boot to purge any historic band whose
     * average has been polluted past this ceiling.
     */
    private const val PHANTOM_LEARN_CEILING = 200.0

    /**
     * V5.0.4184 — Max value `expectedExtraSlipPct()` can return to
     * downstream callers. Even if a band's avg is computed past this,
     * we hand the executor a sane number so F1 sizing/rejection never
     * sees the phantom. The F1_SLIP_HARD_REJECT bar is 18% — capping
     * here at 50% leaves headroom for legit slip-heavy pools while
     * never escalating to a million-percent rejection.
     */
    private const val EXPECTED_SLIP_RETURN_CEILING = 50.0

    private fun purgePoisonedBands() {
        var purged = 0
        val toReset = bands.entries.filter { (_, b) -> b.avgSlipPct() > PHANTOM_LEARN_CEILING }
        for ((k, _) in toReset) {
            bands[k] = Band()
            purged++
        }
        if (purged > 0) {
            ErrorLogger.warn(
                TAG,
                "🧹 V5.0.4184 PHANTOM_SLIP_BAND_PURGE: reset $purged poisoned band(s) (avg > ${PHANTOM_LEARN_CEILING.toInt()}%) — predictor will re-learn from real fills",
            )
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PHANTOM_SLIP_BAND_PURGE") } catch (_: Throwable) {}
            save()
        }
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
        // V5.0.4184 — REJECT PHANTOM SAMPLES. A real swap on Solana doesn't
        // slip 200%+ no matter how thin the pool. When the bot books a
        // phantom sell (oracle-priced fill instead of realized Jupiter route),
        // |quoted - realized| / quoted can hit millions of percent — those
        // poisoned samples then drive F1_SLIP_HARD_REJECT to reject every
        // future buy in that liq band. Filter at the source.
        if (!slip.isFinite() || slip > PHANTOM_LEARN_CEILING) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PHANTOM_SLIP_SAMPLE_REJECTED") } catch (_: Throwable) {}
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "PHANTOM_SLIP_SAMPLE_REJECTED",
                    "band=${bandFor(liqUsd)} liqUsd=${liqUsd.toInt()} quoted=$quotedPxUsd realized=$realizedPxUsd computedSlipPct=${"%.1f".format(slip)} ceiling=${PHANTOM_LEARN_CEILING.toInt()}",
                )
            } catch (_: Throwable) {}
            return
        }
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

    fun expectedExtraSlipPct(liqUsd: Double): Double {
        val raw = bands[bandFor(liqUsd)]?.avgSlipPct() ?: 0.0
        // V5.0.4184 — never hand the executor a phantom-poisoned value.
        // If the band data has somehow grown past the return ceiling
        // (e.g. boot-time purge didn't catch it), cap here. Real slip
        // observations under PHANTOM_LEARN_CEILING (200%) compose normally;
        // this cap only kicks in if the persisted band data is corrupt.
        return if (raw.isFinite() && raw >= 0.0) raw.coerceAtMost(EXPECTED_SLIP_RETURN_CEILING) else 0.0
    }

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
