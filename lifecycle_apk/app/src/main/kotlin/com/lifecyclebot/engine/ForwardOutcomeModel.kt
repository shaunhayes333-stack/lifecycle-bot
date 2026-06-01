package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * ForwardOutcomeModel — V5.9.1261  (Roadmap STEP 2: forward-simulation / counterfactual)
 * ════════════════════════════════════════════════════════════════════════════
 * Where AutonomousMetaPolicy (1260) learns WHERE the edge lives, this layer
 * PREDICTS what will happen BEFORE entry. For a candidate it returns the
 * expected outcome distribution — P(win), expected PnL, P(rug), and a dispersion
 * (uncertainty) — learned online from settled trades keyed by a richer
 * feature-signature than the meta-policy context. The FDG can then PLAN against
 * the predicted distribution rather than only react to the current tick.
 *
 * This is "forward simulation" in the empirical sense: instead of a hand-coded
 * price simulator, the model is a learned conditional outcome estimator — given
 * this feature signature, here is the outcome distribution observed historically,
 * with Welford running mean/variance so the estimate sharpens as samples arrive.
 *
 * SIGNATURE = lane × score-band × quality × regime × edgePhase. Coarser fallback
 * (lane × score-band × regime) when the fine signature is data-starved, so a
 * brand-new fine bucket still gets a usable prior from its parent.
 *
 * DOCTRINE COMPLIANCE:
 *   • Returns a PLANNING verdict (predicted dist + a soft conviction nudge in
 *     [0.6, 1.4]); soft-shape only, never a veto, never zero → volume safe.
 *   • Bootstrap-safe: < MIN_SAMPLES on BOTH fine+coarse → neutral (P=0.5, nudge 1.0).
 *   • Welford online stats (O(1) per update), fully persisted, fail-open.
 */
object ForwardOutcomeModel {

    private const val MIN_SAMPLES   = 10
    private const val RUG_PNL       = -50.0   // pnl <= this counts as a rug-class outcome
    private const val NUDGE_FLOOR   = 0.45   // V5.9.1265: deeper cut on proven negative-EV/high-rug signatures (snapshot: AGGRESSIVE|S00 pWin=0% E=-19.5%)
    private const val NUDGE_CAP     = 1.40
    private const val DECAY_EVERY   = 500
    private const val DECAY_FACTOR  = 0.98

    /** Welford running stats for PnL + win/rug rates per signature. */
    private data class Cell(
        @Volatile var n: Long = 0L,
        @Volatile var mean: Double = 0.0,   // running mean PnL%
        @Volatile var m2: Double = 0.0,     // running sum of squares (Welford)
        @Volatile var wins: Long = 0L,
        @Volatile var rugs: Long = 0L,
    ) {
        val pWin: Double get() = if (n > 0) wins.toDouble() / n else 0.5
        val pRug: Double get() = if (n > 0) rugs.toDouble() / n else 0.0
        val variance: Double get() = if (n > 1) m2 / (n - 1) else 0.0
        val stdev: Double get() = sqrt(variance.coerceAtLeast(0.0))
    }

    private val fine   = ConcurrentHashMap<String, Cell>()   // full signature
    private val coarse = ConcurrentHashMap<String, Cell>()   // lane×band×regime parent
    @Volatile private var totalUpdates = 0L
    @Volatile private var appContext: Context? = null

    /** Prediction returned to the planner. */
    data class Forecast(
        val pWin: Double,
        val expectedPnl: Double,
        val pRug: Double,
        val dispersion: Double,   // stdev of PnL — uncertainty
        val samples: Long,
        val convictionNudge: Double,
        val source: String,       // "fine" | "coarse" | "bootstrap"
    )

    private fun band(score: Int) = when {
        score >= 80 -> "S80"; score >= 60 -> "S60"; score >= 40 -> "S40"
        score >= 20 -> "S20"; else -> "S00"
    }
    private fun fineKey(lane: String, score: Int, quality: String, regime: String, edgePhase: String): String =
        "${lane.uppercase().take(14)}|${band(score)}|${quality.take(3)}|${regime.uppercase().take(10)}|${edgePhase.uppercase().take(10)}"
    private fun coarseKey(lane: String, score: Int, regime: String): String =
        "${lane.uppercase().take(14)}|${band(score)}|${regime.uppercase().take(10)}"

    /** Predict the outcome distribution for a candidate (no side effects). */
    fun forecast(
        lane: String, score: Int, quality: String, regime: String, edgePhase: String
    ): Forecast {
        return try {
            val fk = fineKey(lane, score, quality, regime, edgePhase)
            val ck = coarseKey(lane, score, regime)
            val fc = fine[fk]
            val cc = coarse[ck]
            val cell: Cell?; val src: String
            when {
                fc != null && fc.n >= MIN_SAMPLES -> { cell = fc; src = "fine" }
                cc != null && cc.n >= MIN_SAMPLES -> { cell = cc; src = "coarse" }
                else -> return Forecast(0.5, 0.0, 0.0, 0.0, (fc?.n ?: 0L) + (cc?.n ?: 0L), 1.0, "bootstrap")
            }
            // Conviction nudge: lean in on high pWin + positive expectancy, damp on
            // rug-risk or negative expectancy. Penalise high dispersion (uncertain).
            val edge = (cell.pWin - 0.5) * 2.0                  // [-1,1]
            val rugPenalty = cell.pRug * 0.8
            // V5.9.1265 — magnitude-aware expectancy term (was a flat ±0.15/0.20).
            // A signature averaging -19.5% should bite far harder than one at -2%.
            val expSign = if (cell.mean > 0) (cell.mean / 100.0).coerceAtMost(0.18)
                          else (cell.mean / 60.0).coerceAtLeast(-0.40)
            val dispPenalty = (cell.stdev / 100.0).coerceAtMost(0.25)  // wide outcomes → trim
            val nudge = (1.0 + edge * 0.35 + expSign - rugPenalty - dispPenalty)
                .coerceIn(NUDGE_FLOOR, NUDGE_CAP)
            Forecast(cell.pWin, cell.mean, cell.pRug, cell.stdev, cell.n, nudge, src)
        } catch (_: Throwable) { Forecast(0.5, 0.0, 0.0, 0.0, 0L, 1.0, "bootstrap") }
    }

    /** Stamp the signature chosen at decision time so the settled outcome credits it. */
    private val pending = ConcurrentHashMap<String, Pair<String, String>>()  // mint -> (fineKey, coarseKey)
    fun stamp(mint: String, lane: String, score: Int, quality: String, regime: String, edgePhase: String) {
        try { pending[mint] = fineKey(lane, score, quality, regime, edgePhase) to coarseKey(lane, score, regime) } catch (_: Throwable) {}
    }

    /** Feed settled PnL back — updates BOTH the fine and coarse cells (Welford). */
    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val keys = pending.remove(mint) ?: return
            val pnl = pnlPct.coerceIn(-95.0, 1000.0)
            update(fine.getOrPut(keys.first) { Cell() }, pnl)
            update(coarse.getOrPut(keys.second) { Cell() }, pnl)
            totalUpdates += 1
            if (totalUpdates % DECAY_EVERY == 0L) decayAll()
            if (totalUpdates % 25L == 0L) appContext?.let { save(it) }
        } catch (_: Throwable) {}
    }

    private fun update(c: Cell, pnl: Double) {
        synchronized(c) {
            c.n += 1
            val delta = pnl - c.mean
            c.mean += delta / c.n
            c.m2 += delta * (pnl - c.mean)
            if (pnl > 0.0) c.wins += 1
            if (pnl <= RUG_PNL) c.rugs += 1
        }
    }

    private fun decayAll() {
        try {
            (fine.values + coarse.values).forEach { c ->
                synchronized(c) {
                    c.n = (c.n * DECAY_FACTOR).toLong().coerceAtLeast(0L)
                    c.wins = (c.wins * DECAY_FACTOR).toLong().coerceAtLeast(0L)
                    c.rugs = (c.rugs * DECAY_FACTOR).toLong().coerceAtLeast(0L)
                    c.m2 *= DECAY_FACTOR
                }
            }
        } catch (_: Throwable) {}
    }

    // ── Persistence ──────────────────────────────────────────────────────
    fun attachContext(context: Context) { try { appContext = context.applicationContext; load(context) } catch (_: Throwable) {} }

    private fun mapToJson(m: Map<String, Cell>): JSONObject {
        val o = JSONObject()
        m.forEach { (k, c) -> o.put(k, JSONObject().apply {
            put("n", c.n); put("mean", c.mean); put("m2", c.m2); put("w", c.wins); put("r", c.rugs)
        }) }
        return o
    }
    private fun jsonToMap(o: JSONObject?, m: ConcurrentHashMap<String, Cell>) {
        if (o == null) return
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next(); val co = o.getJSONObject(k)
            m[k] = Cell(co.optLong("n",0L), co.optDouble("mean",0.0), co.optDouble("m2",0.0), co.optLong("w",0L), co.optLong("r",0L))
        }
    }

    fun exportState(): String = try {
        JSONObject().apply {
            put("totalUpdates", totalUpdates)
            put("fine", mapToJson(fine))
            put("coarse", mapToJson(coarse))
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            totalUpdates = o.optLong("totalUpdates", 0L)
            jsonToMap(o.optJSONObject("fine"), fine)
            jsonToMap(o.optJSONObject("coarse"), coarse)
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) {
        try { context.getSharedPreferences("forward_outcome_model", Context.MODE_PRIVATE).edit().putString("state", exportState()).apply() } catch (_: Throwable) {}
    }
    private fun load(context: Context) {
        try {
            val s = context.getSharedPreferences("forward_outcome_model", Context.MODE_PRIVATE).getString("state", null)
            if (!s.isNullOrBlank()) importState(s)
        } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        return try {
            val ranked = fine.entries.filter { it.value.n >= MIN_SAMPLES }.sortedByDescending { it.value.mean }
            if (ranked.isEmpty()) return ""
            val sb = StringBuilder("\n===== Forward Outcome Model (V5.9.1261) — counterfactual edge map =====\n")
            sb.append("  signatures=${fine.size}  updates=$totalUpdates\n")
            ranked.take(5).forEach { (k, c) ->
                sb.append("  ▲ $k  pWin=${(c.pWin*100).toInt()}%  E[pnl]=${"%+.1f".format(c.mean)}%  pRug=${(c.pRug*100).toInt()}%  ±${c.stdev.toInt()}  n=${c.n}\n")
            }
            ranked.takeLast(3).filter { it.value.mean < 0 }.forEach { (k, c) ->
                sb.append("  ▼ $k  pWin=${(c.pWin*100).toInt()}%  E[pnl]=${"%+.1f".format(c.mean)}%  pRug=${(c.pRug*100).toInt()}%  n=${c.n}\n")
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}
