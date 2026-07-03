package com.lifecyclebot.engine

import android.content.Context
import android.util.Log
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
 *   • TRADE-1 TUNING: sample 0 is neutral, but once a context has even one
 *     settled outcome the posterior shapes with a small ramp instead of waiting
 *     behind an arbitrary sample cliff. Volume is still protected by soft floors.
 *   • Persisted (exportState/importState) so the learned surface survives
 *     restarts — amnesia-class bug prevention.
 *   • Fail-open everywhere: any error → neutral conviction 1.0.
 */
object AutonomousMetaPolicy {

    // ── Tunables ─────────────────────────────────────────────────────────
    private const val TAG = "MetaPolicy"
    // V5.0.4597 — MIN_SAMPLES LOWERED (operator directive: "self tuning
    // from trade 1"). V5.0.6077 removes the remaining actuator cliff: sample
    // 0 is neutral, but samples 1..4 now shape with a ramped Bayesian posterior
    // instead of returning pure 1.0 until five closes. This preserves soft-shape
    // safety while making SSI/AGI tune from the first settled trade in both
    // paper and live.
    private const val MIN_SAMPLES        = 5       // ramp target, not activation cliff
    private const val TRADE1_RAMP_FLOOR  = 0.25    // one sample gets 25% authority, then ramps to 100%
    private const val CONVICTION_FLOOR   = 0.55    // worst damp — never starve volume
    private const val CONVICTION_CAP     = 1.45    // best lean-in
    private const val PRIOR_ALPHA        = 1.0     // Beta(1,1) uniform prior
    private const val PRIOR_BETA         = 1.0
    // V5.9.1289 — catastrophic-context starve thresholds (separate from conviction floor)
    private const val PROVEN_DEAD_N      = 20L      // need real maturity before starving
    private const val PROVEN_DEAD_WINP   = 0.12     // posterior win-prob below this
    private const val PROVEN_DEAD_AVGPNL = -18.0    // AND average realized pnl below this
    private const val STARVE_FLOOR       = 0.08     // never fully zero (keeps a probe alive)
    // V5.9.1290 — HARD VETO (one tier stricter than starve). Only fires on the
    // MOST statistically certain graves, AND only when the Forward Outcome Model
    // independently agrees (dual-brain consensus). A probabilistic bypass keeps
    // a trickle of fresh evidence flowing so a vetoed context can self-heal.
    private const val VETO_N             = 40L      // far past maturity (2× starve)
    private const val VETO_WINP          = 0.08     // posterior win-prob below this
    private const val VETO_AVGPNL        = -22.0    // AND avg realized pnl below this
    private const val VETO_BYPASS_EVERY  = 25L      // 1-in-25 fresh probe escapes the veto
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
    fun contextCount(): Int = arms.size  // V5.9.1355 P0.5 audit
    @Volatile private var totalUpdates: Long = 0L
    @Volatile private var appContext: Context? = null

    // ── Context key ──────────────────────────────────────────────────────
    fun contextKey(lane: String, score: Int, regime: String): String {
        // V5.9.1294 — FINER LOW-END BANDING. The 3259 snapshot showed EVERY
        // learned context collapsed into "S00" because the vast majority of meme
        // entries score <20 (regime v3Median=7). With all volume in one bucket the
        // policy could not separate a score-3 rug from a score-15 survivor, so the
        // soft-shapers damped the whole blob uniformly and the bleed persisted.
        // Split the high-volume <20 range to match LosingPatternMemory's proven-
        // useful granularity (it already isolates e.g. SHITCOIN|S41-60 = +573% vs
        // S0-10 = -18%). Now meta-policy + forward-model can target the truly dead
        // micro-contexts and lean into the rare good ones. Doctrine: soft-shape,
        // finer signal — NOT a new veto.
        val band = when {
            score >= 80 -> "S80"
            score >= 60 -> "S60"
            score >= 40 -> "S40"
            score >= 20 -> "S20"
            score >= 10 -> "S10"   // was folded into S00
            score >= 5  -> "S05"   // was folded into S00
            else        -> "S00"
        }
        val l = lane.uppercase().take(16).ifBlank { "MEME" }
        val r = regime.uppercase().take(12).ifBlank { "NORMAL" }
        return "$l|$band|$r"
    }

    /**
     * Thompson-sample the context posterior and return a CONVICTION MULTIPLIER.
     * 1.0 = neutral only at sample 0/unknown. >1 = lean in. <1 = damp (never 0).
     * Captures the decision so the outcome can be credited to the same context.
     */
    fun conviction(lane: String, score: Int, regime: String): Double {
        return try {
            val key = contextKey(lane, score, regime)
            val arm = arms.getOrPut(key) { Arm() }
            if (arm.samples <= 0) return 1.0  // no evidence yet; after trade 1 we tune
            // Thompson sample from Beta(alpha, beta). V5.0.6077: samples 1..4 are
            // no longer actuator-dead; they shape with a ramp so the SSI/AGI stack
            // tunes from trade 1 without over-trusting a single noisy close.
            val sample = sampleBeta(arm.alpha, arm.beta)
            val trade1Ramp6077 = (arm.samples.toDouble() / MIN_SAMPLES.toDouble()).coerceIn(TRADE1_RAMP_FLOOR, 1.0)
            // Map the sampled win-prob to a conviction multiplier around a 0.5 hinge.
            // sample 0.5 → 1.0 ; sample 0.8 → lean in ; sample 0.2 → damp.
            val raw = 1.0 + (sample - 0.5) * 1.8 * trade1Ramp6077
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
                Log.i(TAG, "💀 STARVE $key winP=${"%.0f".format(winP*100)}% avgPnl=${"%.1f".format(avgPnl)}% n=${arm.samples} → size×${"%.2f".format(mult)}")
                mult
            } else 1.0
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * V5.9.1290 — HARD VETO. Returns true to REFUSE the trade outright (caller
     * blocks it), but ONLY for the most statistically certain graves:
     *   • Meta-Policy posterior: n>=VETO_N, winP<VETO_WINP, avgPnl<VETO_AVGPNL
     *   • Forward Outcome Model INDEPENDENTLY agrees: real (non-bootstrap) data
     *     with pWin<0.10 and E[pnl]<-18 — dual-brain consensus, no single point.
     * A 1-in-VETO_BYPASS_EVERY probe is ALWAYS let through so the context keeps
     * receiving fresh outcomes and can self-heal if the regime flips. This is the
     * mature-phase upgrade to starveFactor: once a pocket is THIS proven-dead,
     * dusting it still pays fees + slippage on a guaranteed loser; skipping is
     * strictly better. Returns false (allow) on any doubt or error — fail-open.
     */
    fun shouldVeto(lane: String, score: Int, regime: String, fwdPWin: Double, fwdEPnl: Double, fwdSamples: Long): Boolean {
        return try {
            val key = contextKey(lane, score, regime)
            val arm = arms[key] ?: return false
            if (arm.samples < VETO_N) return false
            val winP = arm.alpha / (arm.alpha + arm.beta)
            val avgPnl = if (arm.samples > 0) arm.pnlSum / arm.samples else 0.0
            val metaDead = winP < VETO_WINP && avgPnl < VETO_AVGPNL
            // Forward model must ALSO condemn it, with real (non-bootstrap) evidence.
            val fwdDead = fwdSamples >= 25L && fwdPWin < 0.10 && fwdEPnl < -18.0
            if (!(metaDead && fwdDead)) return false
            // Probabilistic escape valve: let 1-in-N through to keep evidence fresh.
            val n = vetoProbeCounter.incrementAndGet()
            if (n % VETO_BYPASS_EVERY == 0L) {
                Log.i(TAG, "🩺 VETO-PROBE $key — letting 1 fresh probe through (n=$n) to keep $key learnable")
                return false
            }
            Log.i(TAG, "⛔ VETO $key meta[winP=${"%.0f".format(winP*100)}% avg=${"%.1f".format(avgPnl)}% n=${arm.samples}] fwd[pWin=${"%.0f".format(fwdPWin*100)}% E=${"%.1f".format(fwdEPnl)}% n=$fwdSamples]")
            true
        } catch (_: Throwable) { false }
    }

    /** Pending decision contexts keyed by mint, set at decision time. */
    private val pending = ConcurrentHashMap<String, String>()

    /** V5.9.1353 — TRUE RESET: drop all learned arms + pending. */
    fun reset() { arms.clear(); pending.clear() }
    // V5.9.1290 — monotonic probe counter for the veto bypass valve
    private val vetoProbeCounter = java.util.concurrent.atomic.AtomicLong(0L)

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
