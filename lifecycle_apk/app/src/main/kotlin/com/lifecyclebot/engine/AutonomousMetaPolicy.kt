package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.exp

/**
 * AutonomousMetaPolicy — V5.9.1260
 * ════════════════════════════════════════════════════════════════════════
 * A self-improving DELIBERATIVE policy layer that sits above the rule-based
 * BrainConsensusGate. Where the consensus gate collects rule OBJECTIONS, this
 * layer LEARNS — from real settled PnL — the true win-probability of each
 * decision-context and decides explore-vs-exploit autonomously.
 *
 * WHY THIS IS A STEP TOWARD AUTONOMOUS HYPER-INTELLIGENCE (not another gate):
 *   • Thompson sampling (Beta-Bernoulli posterior per context) — the system
 *     decides what to TRY based on its own uncertainty, not a fixed rule. High
 *     uncertainty → it explores; converged-good → it exploits with conviction;
 *     converged-bad → it self-suppresses. This is genuine autonomous curiosity.
 *   • Context = lane × score-band × regime. The policy builds an internal map
 *     of "where my edge actually lives" purely from outcomes — a learned world
 *     model of its own performance surface.
 *   • Conviction multiplier is the policy's ACTION: it sizes by posterior
 *     confidence, so capital flows to contexts it has earned belief in.
 *   • Counterfactual credit: settled PnL is fed back keyed by the SAME context
 *     captured at decision time → the loop is closed, the belief is honest.
 *
 * DOCTRINE COMPLIANCE:
 *   • SOFT-SHAPE ONLY. Never an outright veto (the veto whitelist is owned by
 *     the original FDG path). It returns a conviction multiplier in [floor, cap]
 *     — it can damp a bad context toward a floor but never to zero, and can lean
 *     into a proven context up to a cap. Volume is never starved.
 *   • BOOTSTRAP-SAFE: while a context has < MIN_SAMPLES, conviction = 1.0
 *     (neutral) so the bot keeps gathering sample volume per the doctrine.
 *   • Persisted (exportState/importState) so the learned surface survives
 *     restarts — amnesia-class bug prevention.
 *   • Fail-open everywhere: any error → neutral conviction 1.0.
 */
object AutonomousMetaPolicy {

    // ── Tunables ─────────────────────────────────────────────────────────
    private const val MIN_SAMPLES        = 12      // below this, stay neutral (bootstrap-safe)
    private const val CONVICTION_FLOOR   = 0.55    // worst damp — never starve volume
    private const val CONVICTION_CAP     = 1.45    // best lean-in
    private const val PRIOR_ALPHA        = 1.0     // Beta(1,1) uniform prior
    private const val PRIOR_BETA         = 1.0
    // V5.9.1289 — catastrophic-context starve thresholds (separate from conviction floor)
    private const val PROVEN_DEAD_N      = 20L      // need real maturity before starving
    private const val PROVEN_DEAD_WINP   = 0.12     // posterior win-prob below this
    private const val PROVEN_DEAD_AVGPNL = -18.0    // AND average realized pnl below this
    private const val STARVE_FLOOR       = 0.08     // never fully zero (keeps a probe alive)
    private const val DECAY_EVERY        = 400     // soft-forget so the policy tracks regime drift
    private const val DECAY_FACTOR       = 0.97

    // ── Per-context posterior (Beta-Bernoulli) ───────────────────────────
    private data class Arm(
        @Volatile var alpha: Double = PRIOR_ALPHA,   // wins + prior
        @Volatile var beta:  Double = PRIOR_BETA,    // losses + prior
        @Volatile var samples: Long = 0L,
        @Volatile var pnlSum: Double = 0.0,          // for expectancy / avg
    ) {
        val mean: Double get() = alpha / (alpha + beta)
        val avgPnl: Double get() = if (samples > 0) pnlSum / samples else 0.0
    }

    private val arms = ConcurrentHashMap<String, Arm>()
    @Volatile private var totalUpdates: Long = 0L
    @Volatile private var appContext: Context? = null

    // ── Context key ──────────────────────────────────────────────────────
    fun contextKey(lane: String, score: Int, regime: String): String {
        val band = when {
            score >= 80 -> "S80"
            score >= 60 -> "S60"
            score >= 40 -> "S40"
            score >= 20 -> "S20"
            else        -> "S00"
        }
        val l = lane.uppercase().take(16).ifBlank { "MEME" }
        val r = regime.uppercase().take(12).ifBlank { "NORMAL" }
        return "$l|$band|$r"
    }

    /**
     * Thompson-sample the context posterior and return a CONVICTION MULTIPLIER.
     * 1.0 = neutral (bootstrap or unknown). >1 = lean in. <1 = damp (never 0).
     * Captures the decision so the outcome can be credited to the same context.
     */
    fun conviction(lane: String, score: Int, regime: String): Double {
        return try {
            val key = contextKey(lane, score, regime)
            val arm = arms.getOrPut(key) { Arm() }
            if (arm.samples < MIN_SAMPLES) return 1.0  // bootstrap-safe neutral
            // Thompson sample from Beta(alpha, beta)
            val sample = sampleBeta(arm.alpha, arm.beta)
            // Map the sampled win-prob to a conviction multiplier around a 0.5 hinge.
            // sample 0.5 → 1.0 ; sample 0.8 → lean in ; sample 0.2 → damp.
            val raw = 1.0 + (sample - 0.5) * 1.8
            raw.coerceIn(CONVICTION_FLOOR, CONVICTION_CAP)
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * V5.9.1289 — CATASTROPHIC-CONTEXT STARVE (separate from conviction()).
     *
     * conviction() deliberately floors at CONVICTION_FLOOR (0.55) so it never
     * starves volume during exploration — correct for the throughput doctrine.
     * But once a context is MATURE and STATISTICALLY PROVEN DEAD, sizing 0.55×
     * into a 0%-win / -28%-avg pocket is just slower bleeding, not exploration.
     *
     * This returns a DEEP size multiplier (down to 0.08×) ONLY when ALL hold:
     *   • samples >= PROVEN_DEAD_N (well past bootstrap)
     *   • posterior mean win-prob < PROVEN_DEAD_WINP
     *   • average realized pnl < PROVEN_DEAD_AVGPNL
     * Otherwise 1.0 (no-op). It does NOT veto and does NOT touch the scanner
     * pool — the candidate still flows through FDG fail-open; it just gets
     * sized to dust so a known grave can't drain the wallet. Soft-shape > veto.
     */
    fun starveFactor(lane: String, score: Int, regime: String): Double {
        return try {
            val key = contextKey(lane, score, regime)
            val arm = arms[key] ?: return 1.0
            if (arm.samples < PROVEN_DEAD_N) return 1.0
            val winP = arm.alpha / (arm.alpha + arm.beta)          // posterior mean
            val avgPnl = if (arm.samples > 0) arm.pnlSum / arm.samples else 0.0
            if (winP < PROVEN_DEAD_WINP && avgPnl < PROVEN_DEAD_AVGPNL) {
                // Scale the cut by how dead it is: worse winP → deeper starve.
                val deadness = ((PROVEN_DEAD_WINP - winP) / PROVEN_DEAD_WINP).coerceIn(0.0, 1.0)
                val mult = (0.35 - deadness * 0.27).coerceIn(STARVE_FLOOR, 0.35)
                ErrorLogger.info(TAG, "💀 STARVE $key winP=${"%.0f".format(winP*100)}% avgPnl=${"%.1f".format(avgPnl)}% n=${arm.samples} → size×${"%.2f".format(mult)}")
                mult
            } else 1.0
        } catch (_: Throwable) { 1.0 }
    }

    /** Pending decision contexts keyed by mint, set at decision time. */
    private val pending = ConcurrentHashMap<String, String>()

    /** Stamp the context chosen for a mint at decision time (for credit). */
    fun stampDecision(mint: String, lane: String, score: Int, regime: String) {
        try { pending[mint] = contextKey(lane, score, regime) } catch (_: Throwable) {}
    }

    /** Credit a settled outcome back to the context that produced the decision. */
    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val key = pending.remove(mint) ?: return
            val arm = arms.getOrPut(key) { Arm() }
            val win = pnlPct > 0.0
            synchronized(arm) {
                if (win) arm.alpha += 1.0 else arm.beta += 1.0
                arm.samples += 1
                arm.pnlSum += pnlPct.coerceIn(-95.0, 1000.0)
            }
            totalUpdates += 1
            if (totalUpdates % DECAY_EVERY == 0L) decayAll()
            // opportunistic persist every 25 updates (cheap)
            if (totalUpdates % 25L == 0L) appContext?.let { save(it) }
        } catch (_: Throwable) {}
    }

    private fun decayAll() {
        try {
            arms.values.forEach { arm ->
                synchronized(arm) {
                    arm.alpha = PRIOR_ALPHA + (arm.alpha - PRIOR_ALPHA) * DECAY_FACTOR
                    arm.beta  = PRIOR_BETA  + (arm.beta  - PRIOR_BETA)  * DECAY_FACTOR
                }
            }
        } catch (_: Throwable) {}
    }

    // ── Beta sampler (Gamma ratio, Marsaglia-Tsang) ──────────────────────
    private fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a); val y = sampleGamma(b)
        val s = x + y
        return if (s > 0.0) x / s else 0.5
    }
    private val rng = java.util.Random()
    private fun sampleGamma(shape: Double): Double {
        if (shape < 1.0) {
            val u = rng.nextDouble().coerceIn(1e-9, 1.0)
            return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double; var v: Double
            do { x = rng.nextGaussian(); v = 1.0 + c * x } while (v <= 0.0)
            v = v * v * v
            val u = rng.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────
    fun attachContext(context: Context) {
        try {
            appContext = context.applicationContext
            load(context)
        } catch (_: Throwable) {}
    }

    fun exportState(): String = try {
        val o = JSONObject()
        o.put("totalUpdates", totalUpdates)
        val a = JSONObject()
        arms.forEach { (k, arm) ->
            a.put(k, JSONObject().apply {
                put("a", arm.alpha); put("b", arm.beta)
                put("n", arm.samples); put("p", arm.pnlSum)
            })
        }
        o.put("arms", a)
        o.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            totalUpdates = o.optLong("totalUpdates", 0L)
            val a = o.optJSONObject("arms") ?: return
            val keys = a.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val ao = a.getJSONObject(k)
                arms[k] = Arm(
                    alpha = ao.optDouble("a", PRIOR_ALPHA),
                    beta  = ao.optDouble("b", PRIOR_BETA),
                    samples = ao.optLong("n", 0L),
                    pnlSum = ao.optDouble("p", 0.0),
                )
            }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) {
        try {
            context.getSharedPreferences("autonomous_meta_policy", Context.MODE_PRIVATE)
                .edit().putString("state", exportState()).apply()
        } catch (_: Throwable) {}
    }
    private fun load(context: Context) {
        try {
            val s = context.getSharedPreferences("autonomous_meta_policy", Context.MODE_PRIVATE)
                .getString("state", null)
            if (!s.isNullOrBlank()) importState(s)
        } catch (_: Throwable) {}
    }

    // ── Operator telemetry ───────────────────────────────────────────────
    fun formatForPipelineDump(): String {
        return try {
            if (arms.isEmpty()) return ""
            val ranked = arms.entries
                .filter { it.value.samples >= MIN_SAMPLES }
                .sortedByDescending { it.value.mean }
            if (ranked.isEmpty()) return "\n===== Autonomous Meta-Policy (V5.9.1260) =====\n  (bootstrap — all contexts < $MIN_SAMPLES samples)\n"
            val sb = StringBuilder("\n===== Autonomous Meta-Policy (V5.9.1260) — learned edge surface =====\n")
            sb.append("  contexts=${arms.size}  updates=$totalUpdates\n")
            ranked.take(6).forEach { (k, arm) ->
                sb.append("  ▲ $k  winP=${pct(arm.mean)}  n=${arm.samples}  avgPnl=${"%+.1f".format(arm.avgPnl)}%  conv≈${"%.2f".format(1.0 + (arm.mean - 0.5) * 1.8)}\n")
            }
            ranked.takeLast(3).filter { it.value.mean < 0.45 }.forEach { (k, arm) ->
                sb.append("  ▼ $k  winP=${pct(arm.mean)}  n=${arm.samples}  avgPnl=${"%+.1f".format(arm.avgPnl)}% (damped)\n")
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }
    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}
