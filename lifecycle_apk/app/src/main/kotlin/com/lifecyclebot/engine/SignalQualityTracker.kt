package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1271 — SIGNAL QUALITY TRACKER
 *
 * THE MISSING INSTRUMENT. The bot has a real forward predictor
 * (ForwardOutcomeModel) that emits pWin / E[pnl] per signature and nudges
 * sizing off it. But NOTHING measured whether those predictions were
 * actually any good. We were trading on a forecast we never graded.
 *
 * This module closes that loop. At decision time we stamp the predicted
 * pWin + expected PnL. At close we compare prediction vs reality and
 * accumulate, per lane:
 *   - Brier score        — calibration of pWin (lower = better; 0.25 = coin-flip)
 *   - directional hit    — did "predicted win" (pWin>0.5) actually win?
 *   - realized vs E[pnl]  — is expected PnL biased high/low?
 *   - information edge    — hitRate - 0.5, the raw predictive lift
 *
 * This is the single number that answers "do we actually have edge?" — and
 * it is asset-agnostic by construction, so it ports to the crypto universe
 * unchanged. O(1) per update, fully persisted via LearningPersistence,
 * fail-open everywhere.
 */
object SignalQualityTracker {

    private const val MIN_GRADED = 20      // per-lane samples before the score is trusted

    private data class Q(
        @Volatile var n: Long = 0L,
        @Volatile var brierSum: Double = 0.0,
        @Volatile var hits: Long = 0L,
        @Volatile var predWins: Long = 0L,
        @Volatile var realWins: Long = 0L,
        @Volatile var ePnlSum: Double = 0.0,
        @Volatile var rPnlSum: Double = 0.0,
    ) {
        val brier: Double get() = if (n > 0) brierSum / n else 0.25
        val hitRate: Double get() = if (n > 0) hits.toDouble() / n else 0.5
        val infoEdge: Double get() = hitRate - 0.5
        val pnlBias: Double get() = if (n > 0) (ePnlSum - rPnlSum) / n else 0.0
        val realizedWinRate: Double get() = if (n > 0) realWins.toDouble() / n else 0.0
    }

    private val byLane = ConcurrentHashMap<String, Q>()
    private val all = Q()
    private val pending = ConcurrentHashMap<String, Triple<String, Double, Double>>()
    @Volatile private var appContext: Context? = null

    fun stamp(mint: String, lane: String, predictedPWin: Double, expectedPnl: Double) {
        try {
            if (mint.isBlank()) return
            if (predictedPWin == 0.5 && expectedPnl == 0.0) return
            pending[mint] = Triple(lane.ifBlank { "UNKNOWN" }, predictedPWin.coerceIn(0.0, 1.0), expectedPnl)
        } catch (_: Throwable) {}
    }

    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val triple = pending.remove(mint) ?: return
            val lane = triple.first; val pWin = triple.second; val ePnl = triple.third
            val won = pnlPct > 0.0
            val outcome = if (won) 1.0 else 0.0
            val predictedWin = pWin > 0.5
            val directionalCorrect = predictedWin == won
            val brierTerm = (pWin - outcome) * (pWin - outcome)
            fun apply(q: Q) {
                synchronized(q) {
                    q.n += 1
                    q.brierSum += brierTerm
                    if (directionalCorrect) q.hits += 1
                    if (predictedWin) q.predWins += 1
                    if (won) q.realWins += 1
                    q.ePnlSum += ePnl
                    q.rPnlSum += pnlPct
                }
            }
            apply(byLane.getOrPut(lane) { Q() })
            apply(all)
        } catch (_: Throwable) {}
    }

    fun isPredictive(lane: String): Boolean {
        val q = byLane[lane] ?: return false
        return q.n >= MIN_GRADED && q.infoEdge > 0.0 && q.brier < 0.25
    }

    fun summaryLine(): String {
        val q = all
        if (q.n < MIN_GRADED) return "🎯 SIGNAL QUALITY: warming (${q.n}/$MIN_GRADED graded)"
        val edgePct = (q.infoEdge * 100.0)
        val verdict = when {
            q.infoEdge > 0.15 && q.brier < 0.20 -> "STRONG EDGE"
            q.infoEdge > 0.05                    -> "REAL EDGE"
            q.infoEdge > 0.0                     -> "MARGINAL"
            else                                  -> "NO EDGE — noise"
        }
        return "🎯 SIGNAL QUALITY [$verdict] hit=${(q.hitRate*100).toInt()}% " +
            "edge=${"%+.1f".format(edgePct)}pp brier=${"%.3f".format(q.brier)} " +
            "E[pnl]bias=${"%+.1f".format(q.pnlBias)}% n=${q.n}"
    }

    fun detailBlock(): String = buildString {
        append("🎯 SIGNAL QUALITY — predictor accuracy by lane\n")
        append(summaryLine()).append("\n")
        byLane.entries.sortedByDescending { it.value.n }.forEach { entry ->
            val lane = entry.key; val q = entry.value
            if (q.n == 0L) return@forEach
            val icon = if (q.n < MIN_GRADED) "🟡" else if (q.infoEdge > 0.05) "🟢" else "🔴"
            append("$icon $lane: hit=${(q.hitRate*100).toInt()}% ")
            append("edge=${"%+.1f".format(q.infoEdge*100)}pp ")
            append("brier=${"%.3f".format(q.brier)} ")
            append("realWR=${(q.realizedWinRate*100).toInt()}% ")
            append("pnlBias=${"%+.1f".format(q.pnlBias)}% n=${q.n}\n")
        }
    }

    fun attachContext(context: Context) {
        try { appContext = context.applicationContext } catch (_: Throwable) {}
    }

    fun exportState(): String = try {
        val o = JSONObject()
        fun ser(q: Q) = JSONObject().apply {
            put("n", q.n); put("brierSum", q.brierSum); put("hits", q.hits)
            put("predWins", q.predWins); put("realWins", q.realWins)
            put("ePnlSum", q.ePnlSum); put("rPnlSum", q.rPnlSum)
        }
        o.put("all", ser(all))
        val lanes = JSONObject()
        byLane.forEach { (k, v) -> lanes.put(k, ser(v)) }
        o.put("lanes", lanes)
        o.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            fun deser(src: JSONObject?, q: Q) {
                if (src == null) return
                q.n = src.optLong("n"); q.brierSum = src.optDouble("brierSum")
                q.hits = src.optLong("hits"); q.predWins = src.optLong("predWins")
                q.realWins = src.optLong("realWins")
                q.ePnlSum = src.optDouble("ePnlSum"); q.rPnlSum = src.optDouble("rPnlSum")
            }
            deser(o.optJSONObject("all"), all)
            val lanes = o.optJSONObject("lanes")
            lanes?.keys()?.forEach { k ->
                deser(lanes.optJSONObject(k), byLane.getOrPut(k) { Q() })
            }
            ErrorLogger.info("SignalQuality", "Restored — ${all.n} graded predictions, ${byLane.size} lanes")
        } catch (_: Throwable) {}
    }
}
