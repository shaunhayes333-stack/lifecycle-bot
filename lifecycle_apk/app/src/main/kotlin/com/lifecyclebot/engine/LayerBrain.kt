package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

/**
 * LayerBrain — V5.0.4111 (generic per-layer online-learning framework).
 * ════════════════════════════════════════════════════════════════════════════
 * Operator vision: "aate isnt meant to be a series of gates — it's meant to be
 * a super agi intelligence stack". The previous AGI plumbing (V5.0.4093–4096)
 * promoted ~26 brains (13 lanes × 2 decision types) BUT left ~19 layer scorers
 * (`MEVDetectionAI`, `CultMomentumAI`, `ReflexAI`, `OrderFlowImbalanceAI`,
 * `LiquidityCycleAI`, `MemeEdgeAI`, ...) as PURE HEURISTICS — they emit
 * scores but never learn. They are data hubs, not brains.
 *
 * V5.0.4111 ships a SHARED, allocation-cheap, NEVER-LOCKING framework so any
 * heuristic scorer can register a brain instance and immediately gain:
 *   1. An online logistic-regression head trained on its OWN feature vector.
 *   2. A learned multiplicative bias on its emitted score (soft-shape only —
 *      bounded by tier, never veto, never zero).
 *   3. Per-brain 4-tier authority (BOOTSTRAP / ADVISORY / LEARNED /
 *      AUTHORITATIVE) with calibration-aware demote (mirrors UnifiedPolicyHead).
 *   4. Persistent state across reboots via SharedPreferences.
 *   5. A central `recordOutcomeAll(mint, pnl)` swept once per trade settlement
 *      that trains every brain that stamped that mint.
 *
 * SAFETY / DEADLOCK NOTES (post-V5.0.4109 root cause):
 *   • Zero `suspend` functions. Zero `Mutex`. Zero `synchronized`.
 *   • `ConcurrentHashMap` + `@Volatile` doubles are sufficient because each
 *     brain is independent and its SGD update is lock-free; the only race is
 *     a slightly stale gradient that converges anyway. We accept that.
 *   • `pending` map keyed by mint may receive multiple stamps from the same
 *     brain for the same mint — the latest stamp wins, matching the behavior
 *     of `UnifiedPolicyHead`. No retry logic, no allocation churn.
 *   • Persistence write is bounded — at most once per `SAVE_EVERY_N` updates,
 *     async-safe via SharedPreferences.apply().
 *
 * USAGE (from a layer scorer):
 *
 *   private val brain = LayerBrain.register("MEVDetectionAI", nFeatures = 2)
 *
 *   fun score(candidate, ctx): ScoreComponent {
 *       val feats = doubleArrayOf(candidate.buyPressurePct / 100.0,
 *                                  (candidate.liquidityUsd / 100_000.0).coerceIn(0.0,1.0))
 *       val rawScore = ...                       // existing heuristic
 *       val biased = brain.applyBias(rawScore.toDouble(), feats)
 *       brain.stamp(candidate.mint, feats)
 *       return ScoreComponent("MEVDetectionAI", biased.toInt(), reason)
 *   }
 *
 * BotService.onTradeSettled(mint, pnlPct) →  LayerBrain.recordOutcomeAll(mint, pnlPct)
 */
object LayerBrain {

    private const val LR = 0.03
    private const val L2 = 1e-4
    private const val SAVE_EVERY_N = 25L

    // Brier-calibrated demote thresholds (mirror UnifiedPolicyHead).
    private const val BRIER_DRIFTING_MAX = 0.27

    // Authority thresholds — identical to UnifiedPolicyHead for consistency.
    private const val AUTHORITY_ADVISORY      = 40L
    private const val AUTHORITY_LEARNED       = 100L
    private const val AUTHORITY_AUTHORITATIVE = 250L

    enum class AuthorityTier(val minSamples: Long) {
        BOOTSTRAP(0L),
        ADVISORY(AUTHORITY_ADVISORY),
        LEARNED(AUTHORITY_LEARNED),
        AUTHORITATIVE(AUTHORITY_AUTHORITATIVE),
    }

    /**
     * The internal brain state. One instance per registered layer.
     * All fields are concurrency-safe under our lock-free update model.
     *
     * NOTE: declared `internal` (not `private`) because [Handle] exposes it
     * via its `internal constructor` — the Kotlin compiler rejects a
     * private-in-class type leaking through an internal-visible signature.
     */
    internal class Brain(val name: String, val nFeatures: Int) {
        val w = DoubleArray(nFeatures) { 0.0 }
        @Volatile var bias: Double = 0.0
        @Volatile var trained: Long = 0L
        val featMean = DoubleArray(nFeatures) { 0.5 }

        // Brier accumulator: rolling sum of (p - y)^2.
        @Volatile var brierSum: Double = 0.0
        @Volatile var brierN: Long = 0L

        // Pending stamps keyed by mint — picked up by recordOutcomeAll.
        val pending = ConcurrentHashMap<String, DoubleArray>()

        // Counters for diagnostic dumps.
        val advisoryUses = AtomicLong(0L)
        val authoritativeOverrides = AtomicLong(0L)
        val calibrationDemotes = AtomicLong(0L)

        fun rawProb(x: DoubleArray): Double {
            var z = bias
            val n = minOf(nFeatures, x.size)
            for (i in 0 until n) z += w[i] * (x[i] - featMean[i])
            return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
        }

        fun authority(): AuthorityTier {
            val raw = when {
                trained >= AUTHORITY_AUTHORITATIVE -> AuthorityTier.AUTHORITATIVE
                trained >= AUTHORITY_LEARNED       -> AuthorityTier.LEARNED
                trained >= AUTHORITY_ADVISORY      -> AuthorityTier.ADVISORY
                else                                -> AuthorityTier.BOOTSTRAP
            }
            // Calibration demote: bad Brier drops one tier until trust earned back.
            if (brierN >= 20L) {
                val brier = brierSum / brierN
                if (brier > BRIER_DRIFTING_MAX && raw != AuthorityTier.BOOTSTRAP) {
                    calibrationDemotes.incrementAndGet()
                    return when (raw) {
                        AuthorityTier.AUTHORITATIVE -> AuthorityTier.LEARNED
                        AuthorityTier.LEARNED       -> AuthorityTier.ADVISORY
                        AuthorityTier.ADVISORY      -> AuthorityTier.BOOTSTRAP
                        AuthorityTier.BOOTSTRAP     -> AuthorityTier.BOOTSTRAP
                    }
                }
            }
            return raw
        }
    }

    /**
     * Handle returned by [register] — the only surface a layer scorer ever
     * touches. Keeps the call-site code minimal and shields callers from the
     * internal `Brain` representation.
     */
    class Handle internal constructor(private val brain: Brain) {
        val name: String get() = brain.name
        fun trainedCount(): Long = brain.trained
        fun authority(): AuthorityTier = brain.authority()

        /**
         * Apply a learned multiplicative bias to [rawScore]. The bias range
         * scales with authority tier:
         *   BOOTSTRAP    → 1.0  (neutral; pure heuristic still rules)
         *   ADVISORY     → [0.80, 1.20]
         *   LEARNED      → [0.60, 1.40]
         *   AUTHORITATIVE→ [0.40, 1.60]
         * The bias never flips the sign of [rawScore] (signed inputs still
         * carry their direction; we only attenuate / amplify magnitude).
         */
        fun applyBias(rawScore: Double, features: DoubleArray): Double {
            return try {
                val tier = brain.authority()
                if (tier == AuthorityTier.BOOTSTRAP) return rawScore
                val p = brain.rawProb(features)            // [0..1]
                val (floor, cap) = when (tier) {
                    AuthorityTier.ADVISORY      -> 0.80 to 1.20
                    AuthorityTier.LEARNED       -> 0.60 to 1.40
                    AuthorityTier.AUTHORITATIVE -> 0.40 to 1.60
                    else                         -> 1.0 to 1.0
                }
                val slope = when (tier) {
                    AuthorityTier.ADVISORY      -> 0.80
                    AuthorityTier.LEARNED       -> 1.60
                    AuthorityTier.AUTHORITATIVE -> 2.40
                    else                         -> 0.0
                }
                val mult = (1.0 + (p - 0.5) * slope).coerceIn(floor, cap)
                if (tier == AuthorityTier.AUTHORITATIVE) {
                    brain.authoritativeOverrides.incrementAndGet()
                } else {
                    brain.advisoryUses.incrementAndGet()
                }
                rawScore * mult
            } catch (_: Throwable) {
                rawScore
            }
        }

        /** Stamp features against [mint] so the next trade settlement can train. */
        fun stamp(mint: String, features: DoubleArray) {
            try {
                if (mint.isBlank()) return
                // Defensive copy — caller may mutate the array.
                val copy = DoubleArray(brain.nFeatures)
                val n = minOf(brain.nFeatures, features.size)
                for (i in 0 until n) copy[i] = features[i]
                for (i in n until brain.nFeatures) copy[i] = brain.featMean[i]
                brain.pending[mint] = copy
                // Cap pending size — bot is long-running; old un-settled stamps
                // would otherwise leak memory across days.
                if (brain.pending.size > 5_000) {
                    val it = brain.pending.entries.iterator()
                    var drop = brain.pending.size - 4_000
                    while (it.hasNext() && drop > 0) { it.next(); it.remove(); drop-- }
                }
            } catch (_: Throwable) {}
        }
    }

    // ─── Registry ────────────────────────────────────────────────────────
    private val brains = ConcurrentHashMap<String, Brain>()
    @Volatile private var appContext: Context? = null

    /**
     * Register a new brain (or look up an existing one) by [name] and feature
     * count. Safe to call multiple times — the same Handle is returned. If
     * the call is made with a different `nFeatures` than the first call wins
     * (warn-only; the call site has a bug).
     */
    fun register(name: String, nFeatures: Int): Handle {
        require(name.isNotBlank()) { "LayerBrain name must be non-blank" }
        require(nFeatures in 1..32) { "nFeatures must be in 1..32 (got $nFeatures)" }
        val b = brains.computeIfAbsent(name) { Brain(name, nFeatures) }
        if (b.nFeatures != nFeatures) {
            try {
                ErrorLogger.warn("LayerBrain",
                    "register('$name', nFeatures=$nFeatures) called but existing brain has " +
                        "nFeatures=${b.nFeatures}; keeping existing")
            } catch (_: Throwable) {}
        }
        return Handle(b)
    }

    /**
     * Train every brain that stamped this mint, using `pnlPct` to derive a
     * binary outcome (`y=1` if pnl>0 else 0). Single-pass SGD update; lock-free.
     * Called from Executor.recordOutcome paths (paper + live).
     */
    fun recordOutcomeAll(mint: String, pnlPct: Double) {
        if (mint.isBlank()) return
        val y = if (pnlPct > 0.0) 1.0 else 0.0
        var trainedAny = false
        for ((_, b) in brains) {
            try {
                val x = b.pending.remove(mint) ?: continue
                val p = b.rawProb(x)
                val err = p - y
                for (i in 0 until b.nFeatures) {
                    val g = err * (x[i] - b.featMean[i]) + L2 * b.w[i]
                    b.w[i] -= LR * g
                    b.featMean[i] += 0.01 * (x[i] - b.featMean[i])
                }
                b.bias -= LR * err
                b.trained += 1
                // Tier graduation events — soft narrate into sentience family.
                if (b.trained == AUTHORITY_ADVISORY ||
                    b.trained == AUTHORITY_LEARNED ||
                    b.trained == AUTHORITY_AUTHORITATIVE) {
                    val tier = b.authority().name
                    try { SentienceOrchestrator.noteRuntimeEvent(
                        "LAYER_BRAIN_TIER_GRADUATED",
                        "layer=${b.name} tier=$tier n=${b.trained}",
                        "INFO"
                    ) } catch (_: Throwable) {}
                    try { SentientPersonality.injectAutonomousThought(
                        "Layer ${b.name} just leveled up to $tier (n=${b.trained})."
                    ) } catch (_: Throwable) {}
                }
                // Rolling Brier over last 200 samples.
                b.brierSum += (p - y) * (p - y)
                b.brierN += 1
                if (b.brierN > 200L) {
                    b.brierSum *= (200.0 / b.brierN)
                    b.brierN = 200L
                }
                trainedAny = true
            } catch (_: Throwable) {}
        }
        if (trainedAny) {
            // Throttled save: only when ANY brain crosses a multiple of N.
            val ctx = appContext ?: return
            for ((_, b) in brains) {
                if (b.trained > 0 && b.trained % SAVE_EVERY_N == 0L) {
                    save(ctx); return
                }
            }
        }
    }

    fun attachContext(context: Context) {
        try {
            appContext = context.applicationContext
            load(context)
        } catch (_: Throwable) {}
    }

    // ─── Persistence ─────────────────────────────────────────────────────

    fun exportState(): String = try {
        val root = JSONObject()
        for ((name, b) in brains) {
            root.put(name, JSONObject().apply {
                put("nf", b.nFeatures)
                put("trained", b.trained)
                put("bias", b.bias)
                put("w", JSONArray().also { for (v in b.w) it.put(v) })
                put("fm", JSONArray().also { for (v in b.featMean) it.put(v) })
                put("brierSum", b.brierSum)
                put("brierN", b.brierN)
            })
        }
        root.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val o = root.optJSONObject(name) ?: continue
                val nf = o.optInt("nf", 0)
                if (nf <= 0 || nf > 32) continue
                val b = brains.computeIfAbsent(name) { Brain(name, nf) }
                if (b.nFeatures != nf) continue
                b.trained = o.optLong("trained", 0L)
                b.bias = o.optDouble("bias", 0.0)
                o.optJSONArray("w")?.let { for (i in 0 until minOf(nf, it.length())) b.w[i] = it.optDouble(i, 0.0) }
                o.optJSONArray("fm")?.let { for (i in 0 until minOf(nf, it.length())) b.featMean[i] = it.optDouble(i, 0.5) }
                b.brierSum = o.optDouble("brierSum", 0.0)
                b.brierN = o.optLong("brierN", 0L)
            }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) {
        try {
            context.getSharedPreferences("layer_brains", Context.MODE_PRIVATE)
                .edit().putString("state", exportState()).apply()
        } catch (_: Throwable) {}
    }

    private fun load(context: Context) {
        try {
            val s = context.getSharedPreferences("layer_brains", Context.MODE_PRIVATE)
                .getString("state", null)
            if (!s.isNullOrBlank()) importState(s)
        } catch (_: Throwable) {}
    }

    // ─── Diagnostics ─────────────────────────────────────────────────────

    fun formatForPipelineDump(): String {
        return try {
            if (brains.isEmpty()) return ""
            val sb = StringBuilder("\n===== Layer Brains (V5.0.4111, per-layer online learning) =====\n")
            val sorted = brains.values.sortedByDescending { it.trained }
            for (b in sorted) {
                val brier = if (b.brierN > 0L) b.brierSum / b.brierN else 0.25
                val brierTag = when {
                    b.brierN < 20L                  -> "(warming)"
                    brier <= 0.22                   -> "(calibrated)"
                    brier <= BRIER_DRIFTING_MAX     -> "(monitoring)"
                    else                            -> "(drifting → demoted)"
                }
                sb.append("  ${b.name}  n=${b.trained}  auth=${b.authority().name}  ")
                    .append("bias=${"%+.2f".format(b.bias)}  brier=${"%.3f".format(brier)} $brierTag\n")
                sb.append("    advisoryUses=${b.advisoryUses.get()}  ")
                    .append("authoritativeOverrides=${b.authoritativeOverrides.get()}  ")
                    .append("calibrationDemotes=${b.calibrationDemotes.get()}  pending=${b.pending.size}\n")
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }

    /** Total registered brains. */
    fun count(): Int = brains.size

    /** Total trained samples across all brains. */
    fun totalTrained(): Long = brains.values.sumOf { it.trained }
}
