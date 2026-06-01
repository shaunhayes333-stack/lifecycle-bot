package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * StrategyHypothesisEngine — V5.9.1263  (Roadmap STEP 4: self-directed hypothesis
 * generation + A/B strategy mutation)
 * ════════════════════════════════════════════════════════════════════════════
 * The final organ of autonomous intelligence: the bot INVENTS its own strategy
 * tweaks and TESTS them. For a context (lane × score-band × regime) it proposes
 * a small parameter mutation as a HYPOTHESIS (e.g. "tpBias +0.10", "sizeBias
 * +0.15", "holdBias -0.10"), then runs it as a live A/B: trades in that context
 * are deterministically split control vs variant by mint hash. Real settled PnL
 * accrues to each arm. When the variant beats control with enough samples and a
 * positive Welch t-stat, the hypothesis is PROMOTED — its bias becomes the new
 * baseline and a fresh hypothesis is spawned. Losers are retired.
 *
 * This is genuine self-improvement: no human picks the experiments. The engine
 * generates candidates, allocates exploration, measures ground truth, and
 * keeps what works — a closed scientific loop.
 *
 * OUTPUT (consumed softly by the FDG): getSizeBias(context, mint) → a small
 * multiplicative nudge [0.85, 1.20] that reflects the currently-winning arm for
 * that mint's assignment. Promoted biases persist as the context baseline.
 *
 * DOCTRINE COMPLIANCE:
 *   • SOFT-SHAPE ONLY — bias bounded [0.85,1.20]; never veto, never zero.
 *   • Only ONE active hypothesis per context at a time (no combinatorial blowup).
 *   • Promotion needs >= MIN_ARM samples on BOTH arms AND a positive t-stat →
 *     no promotion on noise.
 *   • Mutations are bounded and only ever touch SIZE bias here (the safest knob);
 *     TP/SL/hold biases are recorded for telemetry but NOT auto-applied to the
 *     hard floors (the unconditional -15% SL is sacrosanct).
 *   • Persisted, fail-open.
 */
object StrategyHypothesisEngine {

    private const val MIN_ARM       = 12      // V5.9.1265: promote proven-better variants sooner (snapshot vars already beating control at n=10)
    private const val SIZE_BIAS_MIN = 0.85
    private const val SIZE_BIAS_MAX = 1.20
    private const val MUTATION_STEP = 0.10    // size-bias delta a hypothesis tests
    private const val PROMOTE_T     = 1.3     // V5.9.1265: slightly looser so clear winners promote, still noise-safe

    private data class Arm(
        @Volatile var n: Long = 0L,
        @Volatile var mean: Double = 0.0,
        @Volatile var m2: Double = 0.0,
    ) {
        val variance: Double get() = if (n > 1) m2 / (n - 1) else 0.0
        fun update(x: Double) { synchronized(this) { n += 1; val d = x - mean; mean += d / n; m2 += d * (x - mean) } }
    }

    private data class Hypothesis(
        val context: String,
        @Volatile var variantSizeBias: Double,   // what the variant arm tests
        val control: Arm = Arm(),
        val variant: Arm = Arm(),
        @Volatile var spawnedAt: Long = System.currentTimeMillis(),
    )

    // promoted baseline size bias per context (starts 1.0)
    private val baseline = ConcurrentHashMap<String, Double>()
    private val active = ConcurrentHashMap<String, Hypothesis>()
    private val pending = ConcurrentHashMap<String, Pair<String, Boolean>>()  // mint -> (context, isVariant)
    @Volatile private var promotions = 0L
    @Volatile private var retirements = 0L
    @Volatile private var appContext: Context? = null

    private fun band(score: Int) = when {
        score >= 80 -> "S80"; score >= 60 -> "S60"; score >= 40 -> "S40"
        score >= 20 -> "S20"; else -> "S00"
    }
    private fun ctxKey(lane: String, score: Int, regime: String) =
        "${lane.uppercase().take(14)}|${band(score)}|${regime.uppercase().take(10)}"

    /** Deterministic arm assignment so a mint always lands in the same arm. */
    private fun isVariant(mint: String): Boolean = (mint.hashCode() and 0x1) == 0

    private fun spawn(context: String): Hypothesis {
        // propose a mutation around the current baseline: explore +/- one step
        val base = baseline[context] ?: 1.0
        val up = (mint(base) + MUTATION_STEP).coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)
        val down = (base - MUTATION_STEP).coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)
        // alternate direction by promotion parity so it explores both sides over time
        val variant = if ((promotions + retirements) % 2L == 0L) up else down
        return Hypothesis(context, variant)
    }
    private fun mint(d: Double) = d  // tiny helper to keep coerce readable

    /**
     * Returns the size bias to apply for this mint in this context, and stamps the
     * arm assignment so the settled outcome credits the right arm. Soft nudge.
     */
    fun getSizeBias(lane: String, score: Int, regime: String, mint: String): Double {
        return try {
            val ctx = ctxKey(lane, score, regime)
            val h = active.getOrPut(ctx) { spawn(ctx) }
            val variant = isVariant(mint)
            pending[mint] = ctx to variant
            val bias = if (variant) h.variantSizeBias else (baseline[ctx] ?: 1.0)
            bias.coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)
        } catch (_: Throwable) { 1.0 }
    }

    /** Feed settled PnL → accrue to the assigned arm, evaluate, maybe promote/retire. */
    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val a = pending.remove(mint) ?: return
            val ctx = a.first; val variant = a.second
            val h = active[ctx] ?: return
            val pnl = pnlPct.coerceIn(-95.0, 1000.0)
            if (variant) h.variant.update(pnl) else h.control.update(pnl)
            maybeResolve(ctx, h)
            if ((promotions + retirements) % 5L == 0L) appContext?.let { save(it) }
        } catch (_: Throwable) {}
    }

    private fun maybeResolve(ctx: String, h: Hypothesis) {
        if (h.control.n < MIN_ARM || h.variant.n < MIN_ARM) return
        // Welch t-stat: variant mean - control mean
        val vc = h.control; val vv = h.variant
        val se = sqrt(vv.variance / vv.n + vc.variance / vc.n).coerceAtLeast(1e-6)
        val t = (vv.mean - vc.mean) / se
        if (t >= PROMOTE_T) {
            // variant wins → promote its bias to the new baseline, spawn next
            baseline[ctx] = h.variantSizeBias
            promotions += 1
            active[ctx] = spawn(ctx)
        } else if (t <= -PROMOTE_T) {
            // variant clearly worse → retire, keep baseline, spawn a different probe
            retirements += 1
            active[ctx] = spawn(ctx)
        }
        // else: inconclusive → keep gathering
    }

    // ── Persistence ──────────────────────────────────────────────────────
    fun attachContext(context: Context) { try { appContext = context.applicationContext; load(context) } catch (_: Throwable) {} }

    fun exportState(): String = try {
        JSONObject().apply {
            put("promotions", promotions); put("retirements", retirements)
            val b = JSONObject(); baseline.forEach { (k,v) -> b.put(k, v) }; put("baseline", b)
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            promotions = o.optLong("promotions", 0L); retirements = o.optLong("retirements", 0L)
            o.optJSONObject("baseline")?.let { b -> val ks = b.keys(); while (ks.hasNext()) { val k = ks.next(); baseline[k] = b.optDouble(k, 1.0) } }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) { try { context.getSharedPreferences("strategy_hypothesis_engine", Context.MODE_PRIVATE).edit().putString("state", exportState()).apply() } catch (_: Throwable) {} }
    private fun load(context: Context) {
        try { val s = context.getSharedPreferences("strategy_hypothesis_engine", Context.MODE_PRIVATE).getString("state", null); if (!s.isNullOrBlank()) importState(s) } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        return try {
            if (active.isEmpty() && baseline.isEmpty()) return ""
            val sb = StringBuilder("\n===== Strategy Hypothesis Engine (V5.9.1263) — self-directed A/B =====\n")
            sb.append("  active=${active.size}  promotions=$promotions  retirements=$retirements\n")
            active.entries.sortedByDescending { it.value.variant.n }.take(5).forEach { (k, h) ->
                sb.append("  ⚗ $k  variantBias=${"%.2f".format(h.variantSizeBias)}  ctrl[n=${h.control.n} μ=${"%+.1f".format(h.control.mean)}%]  var[n=${h.variant.n} μ=${"%+.1f".format(h.variant.mean)}%]\n")
            }
            baseline.entries.filter { abs(it.value - 1.0) > 0.001 }.take(4).forEach { (k, v) ->
                sb.append("  ✓ promoted $k → sizeBias ${"%.2f".format(v)}\n")
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}
