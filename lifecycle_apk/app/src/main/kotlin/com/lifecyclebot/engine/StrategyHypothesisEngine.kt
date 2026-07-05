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

    // V5.9.1286 — EXIT-PROFILE EVOLUTION. The engine now tests a second dimension:
    // a stop-WIDTH multiplier. The tuning console proved tight stops (BLUECHIP -4/-7%)
    // bleed on a lottery asset by cutting would-be runners. ×>1.0 = wider stop (let
    // the trade breathe toward its peak); ×<1.0 = tighter. The lane trader multiplies
    // its base stop by the promoted value, so a lane can EVOLVE from tight→wide if
    // wider demonstrably earns more on real settled PnL. Soft, bounded, A/B-proven.
    private const val STOP_MULT_MIN  = 0.6
    private const val STOP_MULT_MAX  = 2.2
    private const val STOP_MUT_STEP  = 0.25

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
        @Volatile var variantSizeBias: Double,   // what the variant arm tests (size dim)
        @Volatile var variantStopMult: Double = 1.0,   // V5.9.1286 — stop-width dim
        val control: Arm = Arm(),
        val variant: Arm = Arm(),
        @Volatile var spawnedAt: Long = System.currentTimeMillis(),
    )

    // promoted baseline size bias per context (starts 1.0)
    private val baseline = ConcurrentHashMap<String, Double>()
    // V5.9.1286 — promoted stop-width multiplier per context (starts 1.0)
    private val stopBaseline = ConcurrentHashMap<String, Double>()
    private val active = ConcurrentHashMap<String, Hypothesis>()
    private val pending = ConcurrentHashMap<String, Pair<String, Boolean>>()  // mint -> (context, isVariant)

    /** V5.9.1353 — TRUE RESET: drop baselines, active hypotheses + pending. */
    fun reset() { baseline.clear(); stopBaseline.clear(); active.clear(); pending.clear() }
    @Volatile private var promotions = 0L
    @Volatile private var retirements = 0L
    @Volatile private var appContext: Context? = null

    private fun band(score: Int) = when {
        score >= 80 -> "S80"; score >= 60 -> "S60"; score >= 40 -> "S40"
        score >= 20 -> "S20"; else -> "S00"
    }
    private fun ctxKey(lane: String, score: Int, regime: String) =
        "${lane.uppercase().take(14)}|${band(score)}|${regime.uppercase().take(10)}"

    private fun suppressVariantForContext(lane: String, score: Int, regime: String): Boolean {
        val l = lane.uppercase()
        val r = regime.uppercase()
        val hostileRegime = r.contains("DUMP") || r.contains("CHOP")
        val hostileExperiment = hostileRegime && score < 80
        val knownBleederLane = l.contains("MOONSHOT") || l.contains("SHITCOIN") ||
            l.contains("EXPRESS") || l.contains("TREASURY") || l.contains("MANIPULATED")
        val dangerBucket = try { LaneToxicityGuard.isNetNegativeDanger(l, score) } catch (_: Throwable) { false }
        // V5.0.3869 — report 3868 still showed DUMP variants active at S60
        // (MOONSHOT|S60|DUMP, MANIPULATED|S60|DUMP) while regime=DUMP had 6.3% WR
        // and -13.88% mean PnL. Hostile-regime A/B sizing is not useful learning;
        // it teaches the bot to scale trash weather. Suppress experimental mutation
        // in DUMP/CHOP below elite score and in known bleeder lanes/danger buckets.
        // This is not a trade veto; FDG/executor still own entry and sizing.
        return hostileExperiment || (hostileRegime && knownBleederLane) || dangerBucket
    }

    /** Deterministic arm assignment so a mint always lands in the same arm. */
    private fun isVariant(mint: String): Boolean = (mint.hashCode() and 0x1) == 0

    private fun spawn(context: String): Hypothesis {
        // propose a mutation around the current baseline: explore +/- one step
        val base = baseline[context] ?: 1.0
        val up = (mint(base) + MUTATION_STEP).coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)
        val down = (base - MUTATION_STEP).coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)
        // V5.9.1360 P0.2 — DIRECTION GUARD. Never propose a LARGER size bet in a
        // context whose control arm is currently NEGATIVE. The old logic
        // alternated up/down on parity regardless of context health, so half the
        // size variants in a losing pocket tested a BIGGER bet — that is exactly
        // the "promoting/sizing-up a losing variant" the operator flagged (the
        // promotion guard blocks it becoming baseline, but the variant arm still
        // traded at 1.10× while losing). When the context's control mean is
        // negative, explore size DOWN only; up-exploration is allowed solely once
        // the context is at/above breakeven.
        val ctrlMean = try { active[context]?.control?.let { if (it.n >= MIN_ARM) it.mean else null } } catch (_: Throwable) { null }
        val contextNegative = ctrlMean != null && ctrlMean < 0.0
        val variant = if (contextNegative) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HYPOTHESIS_SIZE_UP_BLOCKED_NEG_CONTEXT") } catch (_: Throwable) {}
            down  // losing context → only ever test SMALLER size
        } else if ((promotions + retirements) % 2L == 0L) up else down
        // Alternate the explored dimension: even cycles test SIZE, odd cycles test
        // STOP WIDTH (so both evolve without a combinatorial arm explosion).
        val baseStop = stopBaseline[context] ?: 1.0
        val stopUp = (baseStop + STOP_MUT_STEP).coerceIn(STOP_MULT_MIN, STOP_MULT_MAX)
        val stopDown = (baseStop - STOP_MUT_STEP).coerceIn(STOP_MULT_MIN, STOP_MULT_MAX)
        val exploreStop = (promotions + retirements) % 2L == 1L
        val stopVariant = if (exploreStop) (if ((promotions + retirements) % 4L == 1L) stopUp else stopDown) else baseStop
        val sizeVariant = if (exploreStop) (baseline[context] ?: 1.0) else variant
        return Hypothesis(context, sizeVariant, stopVariant)
    }
    private fun mint(d: Double) = d  // tiny helper to keep coerce readable

    /**
     * Returns the size bias to apply for this mint in this context, and stamps the
     * arm assignment so the settled outcome credits the right arm. Soft nudge.
     */
    fun getSizeBias(lane: String, score: Int, regime: String, mint: String): Double {
        return try {
            val ctx = ctxKey(lane, score, regime)
            if (suppressVariantForContext(lane, score, regime)) {
                active.remove(ctx)
                pending.remove(mint)
                try { PipelineHealthCollector.labelInc("HYPOTHESIS_HOSTILE_BLEEDER_VARIANT_SUPPRESSED") } catch (_: Throwable) {}
                return 1.0
            }
            val h = active.getOrPut(ctx) { spawn(ctx) }
            val variant = isVariant(mint)
            pending[mint] = ctx to variant
            val bias = if (variant) h.variantSizeBias else (baseline[ctx] ?: 1.0)
            val reviewedLabBias = try { AsyncStrategyLab.reviewedSizeBias(lane, score, regime) } catch (_: Throwable) { 1.0 }
            if (reviewedLabBias != 1.0) {
                try { PipelineHealthCollector.labelInc("ASYNC_STRATEGY_LAB_REVIEWED_SIZE_BIAS_4245") } catch (_: Throwable) {}
            }
            val strategyVariantBias4342 = try {
                val v = com.lifecyclebot.engine.learning.StrategyVariantStore.activeFor(lane)
                if (v != null) {
                    try { PipelineHealthCollector.labelInc("STRATEGY_VARIANT_STORE_SIZE_BIAS_4342|${lane.uppercase()}") } catch (_: Throwable) {}
                    when {
                        v.state == com.lifecyclebot.engine.learning.StrategyVariantStore.State.PROMOTED -> 1.04
                        v.expectancy() > 2.0 -> 1.03
                        v.expectancy() < -5.0 && v.samples.get() >= 10 -> 0.96
                        else -> 1.0
                    }
                } else 1.0
            } catch (_: Throwable) { 1.0 }
            (bias * reviewedLabBias * strategyVariantBias4342).coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * V5.9.1286 — stop-width multiplier for this mint/context. A lane multiplies
     * its base stop-loss pct by this. Returns the variant's tested mult when the
     * mint is in the variant arm, else the promoted baseline (default 1.0 = no
     * change). Never mutates pending (getSizeBias owns the arm stamp); read-only.
     */
    fun getStopBias(lane: String, score: Int, regime: String, mint: String): Double {
        return try {
            val ctx = ctxKey(lane, score, regime)
            if (suppressVariantForContext(lane, score, regime)) {
                active.remove(ctx)
                pending.remove(mint)
                return 1.0
            }
            // Self-activating: spawn the arm if absent so the stop dimension evolves
            // independently of whether the FDG happened to stamp the same ctx via
            // getSizeBias. Stamp the arm assignment so the settled PnL credits it.
            val h = active.getOrPut(ctx) { spawn(ctx) }
            val variant = isVariant(mint)
            pending[mint] = ctx to variant
            val m = if (variant) h.variantStopMult else (stopBaseline[ctx] ?: 1.0)
            m.coerceIn(STOP_MULT_MIN, STOP_MULT_MAX)
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
            try {
                val laneForVariant4342 = ctx.substringBefore("|").uppercase()
                com.lifecyclebot.engine.learning.StrategyVariantStore.activeFor(laneForVariant4342)?.let { activeVariant4342 ->
                    com.lifecyclebot.engine.learning.StrategyVariantStore.recordOutcome(activeVariant4342.id, pnl > 0.0, pnl < 0.0, pnl)
                    PipelineHealthCollector.labelInc("STRATEGY_VARIANT_STORE_OUTCOME_4342|$laneForVariant4342")
                }
            } catch (_: Throwable) {}
            maybeResolve(ctx, h)
            if ((promotions + retirements) % 5L == 0L) appContext?.let { save(it) }
        } catch (_: Throwable) {}
    }

    private fun maybeResolve(ctx: String, h: Hypothesis) {
        // V5.0.6115 — DYNAMIC MIN_ARM. MIN_ARM=12 is too high for profitable
        // lanes — by the time 24 trades accumulate (12 control + 12 variant),
        // the bot has bled capital on a losing variant. For lanes with proven
        // lifetime EV, lower the threshold to 6 so winning strategies promote
        // 2x faster. The profitability guard (variantProfitable, variantNetPos)
        // still prevents promoting losing strategies.
        val laneForArm6115 = ctx.substringBefore("|").uppercase()
        val isWinningLane6115 = try {
            val lm = StrategyTelemetry.computeLeaderboard(environment = null, includePartials = false, limit = 2_500)
                .firstOrNull { it.strategy.equals(laneForArm6115, ignoreCase = true) }
            lm != null && lm.trades >= 20 && (lm.totalSolPnl > 0.0 || lm.meanPnlPct >= 15.0)
        } catch (_: Throwable) { false }
        val effectiveMinArm6115 = if (isWinningLane6115) (MIN_ARM / 2) else MIN_ARM
        if (h.control.n < effectiveMinArm6115 || h.variant.n < effectiveMinArm6115) return
        // Welch t-stat: variant mean - control mean
        val vc = h.control; val vv = h.variant
        val se = sqrt(vv.variance / vv.n + vc.variance / vc.n).coerceAtLeast(1e-6)
        val t = (vv.mean - vc.mean) / se
        // V5.9.1355 P0.4 — PROMOTION INTEGRITY GUARD. Previously promotion fired
        // on the t-stat alone, so a variant whose mean was merely "less negative"
        // than a negative control got promoted and its sizeBias increased —
        // scaling a LOSING strategy. Promote ONLY when the variant is genuinely
        // better AND profitable in absolute terms.
        val variantBetter = vv.mean > vc.mean
        val variantProfitable = vv.mean > 0.0
        val variantNetPos = (vv.mean * vv.n) > 0.0
        val variantPfBetter = (vv.mean / (sqrt(vv.variance) + 1e-6)) > (vc.mean / (sqrt(vc.variance) + 1e-6))
        val sampleOk = vv.n >= MIN_ARM && vc.n >= MIN_ARM
        val ddAcceptable = (vv.mean - sqrt(vv.variance)) > -25.0
        val promoteOk = t >= PROMOTE_T && variantBetter && variantProfitable &&
            variantNetPos && variantPfBetter && sampleOk && ddAcceptable
        if (promoteOk) {
            // variant wins → promote BOTH dimensions to the new baseline, spawn next
            baseline[ctx] = h.variantSizeBias
            stopBaseline[ctx] = h.variantStopMult   // V5.9.1286 — persist proven stop width
            // V5.0.4305 — one promotion event must increment the experiment
            // clock once; double-counting distorts next mutation cadence.
            promotions += 1
            active[ctx] = spawn(ctx)
        } else if (t >= PROMOTE_T && !promoteOk) {
            // statistically distinguishable but NOT genuinely better/profitable
            // (e.g. both arms negative, variant only "less bad") — reject promotion.
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HYPOTHESIS_PROMOTION_REJECTED|reason=NEGATIVE_OR_WORSE_VARIANT") } catch (_: Throwable) {}
            retirements += 1
            active[ctx] = spawn(ctx)
        } else if (t <= -PROMOTE_T) {
            // variant clearly worse → retire, keep baseline, spawn a different probe
            retirements += 1
            active[ctx] = spawn(ctx)
        }
        // else: inconclusive → keep gathering
    }

    // ── Persistence ──────────────────────────────────────────────────────
    fun attachContext(context: Context) { try { appContext = context.applicationContext; load(context); seedControlArmsFromHistory() } catch (_: Throwable) {} }

    /**
     * V5.0.6057 — COLD-START SEED (operator P1).
     * The A/B engine ran for 5 hypotheses with ctrl=0 var=0 in the
     * V5.0.6053 runtime because MIN_ARM=12 gates the first evaluation
     * and live trades trickle in one at a time. Seed each active
     * hypothesis's CONTROL arm from historical closed trades so the
     * engine has an immediate baseline; variant arms still fill only
     * from real live A/B assignments. Bounded to 200 recent closes so
     * cold-start is fast and doesn't dominate later live signal.
     * Safe to call multiple times — only seeds contexts that are
     * empty (control.n == 0).
     */
    fun seedControlArmsFromHistory() {
        try {
            val recent = try {
                TradeHistoryStore.getRecentValidClosedTrades(limit = 200, includePartials = false)
            } catch (_: Throwable) { emptyList() }
            if (recent.isEmpty()) return
            var seeded = 0
            for (t in recent) {
                if (!t.side.equals("SELL", true)) continue
                val lane = t.tradingMode.trim().uppercase().ifBlank { "STANDARD" }
                val scoreInt = t.score.toInt().coerceIn(0, 100)
                // Best-effort regime: historical trades don't carry regime,
                // so bucket into NORMAL. When the same context sees a real
                // live trade in a specific regime, that regime's ctx will
                // spawn fresh — this seed only warms the mainline baseline.
                val ctx = ctxKey(lane, scoreInt, "NORMAL")
                if (suppressVariantForContext(lane, scoreInt, "NORMAL")) continue
                val h = active.getOrPut(ctx) { spawn(ctx) }
                // Only seed if this control arm is genuinely cold.
                if (h.control.n >= MIN_ARM.toLong()) continue
                h.control.update(t.pnlPct.coerceIn(-95.0, 1000.0))
                seeded += 1
            }
            if (seeded > 0) {
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "HYPOTHESIS_ENGINE_COLD_START_SEEDED_6057",
                        "closes=$seeded contexts=${active.size} note=control_arms_warmed_from_trade_history"
                    )
                    PipelineHealthCollector.labelInc("HYPOTHESIS_ENGINE_COLD_START_SEEDED_6057")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* seed must never break attach */ }
    }

    fun exportState(): String = try {
        JSONObject().apply {
            put("promotions", promotions); put("retirements", retirements)
            val b = JSONObject(); baseline.forEach { (k,v) -> b.put(k, v) }; put("baseline", b)
            val sb = JSONObject(); stopBaseline.forEach { (k,v) -> sb.put(k, v) }; put("stopBaseline", sb)
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            promotions = o.optLong("promotions", 0L); retirements = o.optLong("retirements", 0L)
            o.optJSONObject("baseline")?.let { b -> val ks = b.keys(); while (ks.hasNext()) { val k = ks.next(); baseline[k] = b.optDouble(k, 1.0) } }
            o.optJSONObject("stopBaseline")?.let { b -> val ks = b.keys(); while (ks.hasNext()) { val k = ks.next(); stopBaseline[k] = b.optDouble(k, 1.0) } }
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
                sb.append("  ⚗ $k  vBias=${"%.2f".format(h.variantSizeBias)} vStop×${"%.2f".format(h.variantStopMult)}  ctrl[n=${h.control.n} μ=${"%+.1f".format(h.control.mean)}%]  var[n=${h.variant.n} μ=${"%+.1f".format(h.variant.mean)}%]\n")
            }
            baseline.entries.filter { abs(it.value - 1.0) > 0.001 }.take(4).forEach { (k, v) ->
                sb.append("  ✓ promoted $k → sizeBias ${"%.2f".format(v)}\n")
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}
