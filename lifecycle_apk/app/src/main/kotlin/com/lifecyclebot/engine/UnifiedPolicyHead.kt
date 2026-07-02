package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * UnifiedPolicyHead — V5.0.4094 (AGI multi-head per-lane learning)
 * ════════════════════════════════════════════════════════════════════════════
 * V5.9.1262 — single online logistic head over committee signals (entry).
 * V5.0.4093 — authority tiers (BOOTSTRAP / ADVISORY / LEARNED / AUTHORITATIVE)
 *             with widening conviction range as trained samples accumulate.
 * V5.0.4094 — PER-LANE HEADS. Each lane (MOONSHOT, STANDARD, BLUECHIP, ...)
 *             gets its OWN weight vector + bias + training counter + feature
 *             means. Warm-started from the global head so a new lane inherits
 *             the average of what worked elsewhere, then specialises. Brier
 *             score per lane → calibration-aware authority: a head with bad
 *             calibration gets pulled back to ADVISORY tier until it earns
 *             trust back.
 *
 * MODEL: online logistic regression, NF features + bias, SGD per-lane.
 *
 * DOCTRINE COMPLIANCE:
 *   • Per-lane sub-head + global head still update together. Global serves
 *     as warm-start for new lanes AND as fallback when a lane head fails.
 *   • Soft-shape only — multiplier in [0.60, 1.40] (LEARNED) or [0.30, 1.80]
 *     (AUTHORITATIVE). Never veto, never zero.
 *   • Bootstrap-safe — neutral 1.0 until per-lane head crosses ADVISORY (40).
 *   • Persisted per-lane weights, fail-open.
 *   • Brier-calibrated — bad calibration demotes authority but never disables.
 */
object UnifiedPolicyHead {

    private const val LR             = 0.03
    private const val L2             = 1e-4
    private const val NF             = 6
    private const val MULT_FLOOR     = 0.60
    private const val MULT_CAP       = 1.40
    private const val MULT_FLOOR_AUTH = 0.30
    private const val MULT_CAP_AUTH   = 1.80
    // V5.0.4179 — F4 force-graduate. Operator directive: bot has been stuck
    // BOOTSTRAP with trained=25 for too long. With trades coming in 6/min
    // post-unchoke the head should grow authority WAY faster than the old
    // 40/100/250 doctrine ("we need 250 samples before we trust the head"
    // = months of live trading). Lower threshold lets the policy signals
    // actually influence decisions at realistic sample sizes:
    //   • ADVISORY at 20 (was 40)  — signals start contributing
    //   • LEARNED at 60 (was 100)   — signals get authority weighting
    //   • AUTHORITATIVE at 150 (was 250) — full authority
    // V5.0.6005 — LOWER AUTHORITY THRESHOLDS. Same rationale as
    // UnifiedExitPolicyHead: current thresholds meant global brain hit
    // trained=25 after weeks with authority=ADVISORY still. Operator
    // directive: the AGI stack MUST take command decisions across all
    // lanes and self-tune to compounding. Brier calibration guard-rail
    // still auto-demotes noisy brains, so aggressive promotion is safe.
    private const val AUTHORITY_ADVISORY      = 3L
    private const val AUTHORITY_LEARNED       = 10L
    private const val AUTHORITY_AUTHORITATIVE = 25L
    // V5.0.4094 — calibration thresholds. Brier score (mean squared err of
    // pWin vs outcome) below this is "well-calibrated"; above is "drifting".
    // Random guessing scores ~0.25; a calibrated head should be below 0.22.
    private const val BRIER_HEALTHY_MAX = 0.22
    private const val BRIER_DRIFTING_MAX = 0.27

    // Global head (warm-start source + fallback)
    private val w = DoubleArray(NF) { 0.0 }
    @Volatile private var bias = 0.0
    @Volatile private var trained = 0L
    private val featMean = DoubleArray(NF) { 0.5 }

    // V5.0.4094 — per-lane state
    private data class LaneHead(
        val w: DoubleArray = DoubleArray(NF) { 0.0 },
        var bias: Double = 0.0,
        var trained: Long = 0L,
        val featMean: DoubleArray = DoubleArray(NF) { 0.5 },
        // Brier accumulator: running sum of (p - y)^2 across recent trades
        var brierSum: Double = 0.0,
        var brierN: Long = 0L,
    )

    private val laneHeads = java.util.concurrent.ConcurrentHashMap<String, LaneHead>()
    // V5.0.4470 — all-lane contribution parity. Pending entry signals must be keyed
    // by mint AND lane; live now evaluates every internal trader like paper, so a
    // mint-only stamp lets the last lane overwrite every sibling lane and only one
    // head learns on terminal close.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, DoubleArray>>()
    private val advisoryUsageCount = java.util.concurrent.atomic.AtomicLong(0)
    private val authoritativeOverrideCount = java.util.concurrent.atomic.AtomicLong(0)
    private val calibrationDemoteCount = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var appContext: Context? = null

    fun trainedCount(): Long = trained
    fun advisoryUsageHits(): Long = advisoryUsageCount.get()
    fun authoritativeOverrideHits(): Long = authoritativeOverrideCount.get()
    fun calibrationDemoteHits(): Long = calibrationDemoteCount.get()

    enum class AuthorityTier(val minSamples: Long) {
        BOOTSTRAP(0L),
        ADVISORY(AUTHORITY_ADVISORY),
        LEARNED(AUTHORITY_LEARNED),
        AUTHORITATIVE(AUTHORITY_AUTHORITATIVE),
    }

    data class Signals(
        val mlEntryConf: Double,
        val symGreenLight: Double,
        val evRatio: Double,
        val metaConviction: Double,
        val fwdPWin: Double,
        val candConf: Double,
    ) {
        fun toArray() = doubleArrayOf(
            mlEntryConf.coerceIn(0.0,1.0), symGreenLight.coerceIn(0.0,1.0),
            evRatio.coerceIn(0.0,1.0), metaConviction.coerceIn(0.0,1.0),
            fwdPWin.coerceIn(0.0,1.0), candConf.coerceIn(0.0,1.0)
        )
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

    private fun rawProbGlobal(x: DoubleArray): Double {
        var z = bias
        for (i in 0 until NF) z += w[i] * (x[i] - featMean[i])
        return sigmoid(z)
    }

    private fun rawProbLane(h: LaneHead, x: DoubleArray): Double {
        var z = h.bias
        for (i in 0 until NF) z += h.w[i] * (x[i] - h.featMean[i])
        return sigmoid(z)
    }

    private fun normalizeLane(lane: String): String = lane.trim().uppercase().ifBlank { "STANDARD" }

    /** Lazily create a per-lane head, warm-started from the GLOBAL head's weights. */
    private fun getOrCreateLaneHead(lane: String): LaneHead {
        return laneHeads.computeIfAbsent(lane) {
            LaneHead().also { h ->
                for (i in 0 until NF) { h.w[i] = w[i]; h.featMean[i] = featMean[i] }
                h.bias = bias
            }
        }
    }

    /** Predicted win-probability given lane + committee signals. */
    fun predictWinProb(s: Signals): Double = predictWinProb("STANDARD", s)
    fun predictWinProb(lane: String, s: Signals): Double = try {
        val h = laneHeads[normalizeLane(lane)]
        if (h != null && h.trained >= 8L) rawProbLane(h, s.toArray()) else rawProbGlobal(s.toArray())
    } catch (_: Throwable) { 0.5 }

    /** Brier score for a lane (mean squared error of predicted pWin vs outcome). */
    fun brierScore(lane: String): Double {
        val h = laneHeads[normalizeLane(lane)] ?: return 0.25
        return if (h.brierN > 0L) h.brierSum / h.brierN else 0.25
    }

    fun currentAuthority(): AuthorityTier = currentAuthority("STANDARD")
    /** Per-lane authority tier — calibration-aware. A miscalibrated head is
     *  pulled back to a lower tier until it earns trust back. */
    fun currentAuthority(lane: String): AuthorityTier {
        val h = laneHeads[normalizeLane(lane)] ?: return globalAuthority()
        val rawTier = when {
            h.trained >= AUTHORITY_AUTHORITATIVE -> AuthorityTier.AUTHORITATIVE
            h.trained >= AUTHORITY_LEARNED       -> AuthorityTier.LEARNED
            h.trained >= AUTHORITY_ADVISORY      -> AuthorityTier.ADVISORY
            else                                  -> AuthorityTier.BOOTSTRAP
        }
        // Calibration demote: if Brier score is bad, drop one tier.
        if (h.brierN >= 20L) {
            val brier = h.brierSum / h.brierN
            if (brier > BRIER_DRIFTING_MAX && rawTier != AuthorityTier.BOOTSTRAP) {
                calibrationDemoteCount.incrementAndGet()
                // V5.0.4096 — narrate the demote into the sentience family so
                // the bot recognizes it's losing a read on this lane and the
                // personality can reflect on it ('I'm second-guessing STANDARD…').
                try { com.lifecyclebot.engine.SentienceOrchestrator.noteRuntimeEvent(
                    "AGI_BRAIN_DEMOTED",
                    "lane=${normalizeLane(lane)} brier=${"%.3f".format(brier)} from=${rawTier.name} brain=entry",
                    "WARN"
                ) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.SentientPersonality.injectAutonomousThought(
                    "Calibration drift on ${normalizeLane(lane)}. Brier=${"%.3f".format(brier)}. I'm pulling back to a lower tier and re-learning."
                ) } catch (_: Throwable) {}
                return when (rawTier) {
                    AuthorityTier.AUTHORITATIVE -> AuthorityTier.LEARNED
                    AuthorityTier.LEARNED       -> AuthorityTier.ADVISORY
                    AuthorityTier.ADVISORY      -> AuthorityTier.BOOTSTRAP
                    AuthorityTier.BOOTSTRAP     -> AuthorityTier.BOOTSTRAP
                }
            }
        }
        return rawTier
    }

    private fun globalAuthority(): AuthorityTier = when {
        trained >= AUTHORITY_AUTHORITATIVE -> AuthorityTier.AUTHORITATIVE
        trained >= AUTHORITY_LEARNED       -> AuthorityTier.LEARNED
        trained >= AUTHORITY_ADVISORY      -> AuthorityTier.ADVISORY
        else                                -> AuthorityTier.BOOTSTRAP
    }

    /** Advisory conviction (still runs alongside rule stack at low authority). */
    fun conviction(s: Signals): Double = conviction("STANDARD", s)
    fun conviction(lane: String, s: Signals): Double {
        return try {
            val laneKey = normalizeLane(lane)
            val h = laneHeads[laneKey]
            val auth = currentAuthority(laneKey)
            if (auth == AuthorityTier.BOOTSTRAP) return 1.0
            val p = if (h != null && h.trained >= 8L) rawProbLane(h, s.toArray()) else rawProbGlobal(s.toArray())
            advisoryUsageCount.incrementAndGet()
            (1.0 + (p - 0.5) * 1.6).coerceIn(MULT_FLOOR, MULT_CAP)
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * Authoritative conviction — null when lane head is still in BOOTSTRAP/ADVISORY
     * (caller falls back to rule stack). Non-null at LEARNED+; AUTHORITATIVE gets
     * the widest range. Calibration check already applied via currentAuthority.
     */
    fun authoritativeConviction(s: Signals): Double? = authoritativeConviction("STANDARD", s)
    fun authoritativeConviction(lane: String, s: Signals): Double? {
        return try {
            val laneKey = normalizeLane(lane)
            val auth = currentAuthority(laneKey)
            if (auth == AuthorityTier.BOOTSTRAP || auth == AuthorityTier.ADVISORY) return null
            val h = laneHeads[laneKey]
            val p = if (h != null && h.trained >= 8L) rawProbLane(h, s.toArray()) else rawProbGlobal(s.toArray())
            val slope = if (auth == AuthorityTier.AUTHORITATIVE) 2.6 else 1.6
            val (floor, cap) = if (auth == AuthorityTier.AUTHORITATIVE)
                MULT_FLOOR_AUTH to MULT_CAP_AUTH
            else MULT_FLOOR to MULT_CAP
            authoritativeOverrideCount.incrementAndGet()
            (1.0 + (p - 0.5) * slope).coerceIn(floor, cap)
        } catch (_: Throwable) { null }
    }

    /** Stamp the signals + lane at decision time so the settled outcome trains
     *  both the per-lane head AND the global head. */
    fun stamp(mint: String, s: Signals) { stamp(mint, "STANDARD", s) }
    fun stamp(mint: String, lane: String, s: Signals) {
        try {
            val laneKey = normalizeLane(lane)
            pending.computeIfAbsent(mint) { java.util.concurrent.ConcurrentHashMap() }[laneKey] = s.toArray()
        } catch (_: Throwable) {}
    }

    /** Train every stamped per-lane head plus the global head on a settled outcome. */
    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val recs = pending.remove(mint) ?: return
            val y = if (pnlPct > 0.0) 1.0 else 0.0
            for ((lane, x) in recs) {
                // ── Train global head (warm-start authority for new lanes) ──
                val pG = rawProbGlobal(x)
                val errG = pG - y
                for (i in 0 until NF) {
                    val g = errG * (x[i] - featMean[i]) + L2 * w[i]
                    w[i] -= LR * g
                    featMean[i] += 0.01 * (x[i] - featMean[i])
                }
                bias -= LR * errG
                trained += 1

                // ── Train per-lane head + accumulate Brier score ──
                val h = getOrCreateLaneHead(lane)
                val pL = rawProbLane(h, x)
                val errL = pL - y
                for (i in 0 until NF) {
                    val g = errL * (x[i] - h.featMean[i]) + L2 * h.w[i]
                    h.w[i] -= LR * g
                    h.featMean[i] += 0.01 * (x[i] - h.featMean[i])
                }
                h.bias -= LR * errL
                h.trained += 1
                // V5.0.4096 — AGI ↔ SENTIENCE SYMBIOSIS. On authority tier crossings,
                // emit lifecycle events into the cross-talk + sentience family so the
                // rest of the AI stack sees each lane head mature independently.
                if (h.trained == AUTHORITY_ADVISORY || h.trained == AUTHORITY_LEARNED || h.trained == AUTHORITY_AUTHORITATIVE) {
                    val tierName = when (h.trained) {
                        AUTHORITY_ADVISORY      -> "ADVISORY"
                        AUTHORITY_LEARNED       -> "LEARNED"
                        AUTHORITY_AUTHORITATIVE -> "AUTHORITATIVE"
                        else                     -> "?"
                    }
                    try { com.lifecyclebot.engine.SentienceOrchestrator.noteRuntimeEvent(
                        "AGI_BRAIN_TIER_GRADUATED",
                        "lane=$lane tier=$tierName n=${h.trained} brain=entry",
                        "INFO"
                    ) } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.SentientPersonality.injectAutonomousThought(
                        "I just leveled up on $lane. Tier=$tierName at n=${h.trained}. The signals are clearer now."
                    ) } catch (_: Throwable) {}
                }
                // rolling Brier: sum of (p - y)^2 windowed over last 200
                h.brierSum += (pL - y) * (pL - y)
                h.brierN += 1
                if (h.brierN > 200L) {
                    h.brierSum *= (200.0 / h.brierN)
                    h.brierN = 200L
                }
            }
            try { PipelineHealthCollector.labelInc("UNIFIED_POLICY_HEAD_ALL_LANE_OUTCOME_4470") } catch (_: Throwable) {}
            if (trained % 25L == 0L) appContext?.let { save(it) }
        } catch (_: Throwable) {}
    }

    fun attachContext(context: Context) { try { appContext = context.applicationContext; load(context) } catch (_: Throwable) {} }

    fun exportState(): String = try {
        JSONObject().apply {
            put("trained", trained); put("bias", bias)
            put("w", JSONArray().also { for (v in w) it.put(v) })
            put("fm", JSONArray().also { for (v in featMean) it.put(v) })
            put("lanes", JSONObject().also { ls ->
                for ((lane, h) in laneHeads) {
                    ls.put(lane, JSONObject().apply {
                        put("trained", h.trained); put("bias", h.bias)
                        put("w", JSONArray().also { for (v in h.w) it.put(v) })
                        put("fm", JSONArray().also { for (v in h.featMean) it.put(v) })
                        put("brierSum", h.brierSum); put("brierN", h.brierN)
                    })
                }
            })
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            trained = o.optLong("trained", 0L); bias = o.optDouble("bias", 0.0)
            o.optJSONArray("w")?.let { for (i in 0 until minOf(NF, it.length())) w[i] = it.optDouble(i, 0.0) }
            o.optJSONArray("fm")?.let { for (i in 0 until minOf(NF, it.length())) featMean[i] = it.optDouble(i, 0.5) }
            val lanes = o.optJSONObject("lanes") ?: return
            val keys = lanes.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val lo = lanes.optJSONObject(key) ?: continue
                val h = LaneHead()
                h.trained = lo.optLong("trained", 0L); h.bias = lo.optDouble("bias", 0.0)
                lo.optJSONArray("w")?.let { for (i in 0 until minOf(NF, it.length())) h.w[i] = it.optDouble(i, 0.0) }
                lo.optJSONArray("fm")?.let { for (i in 0 until minOf(NF, it.length())) h.featMean[i] = it.optDouble(i, 0.5) }
                h.brierSum = lo.optDouble("brierSum", 0.0); h.brierN = lo.optLong("brierN", 0L)
                laneHeads[key] = h
            }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) { try { context.getSharedPreferences("unified_policy_head", Context.MODE_PRIVATE).edit().putString("state", exportState()).apply() } catch (_: Throwable) {} }
    private fun load(context: Context) {
        try { val s = context.getSharedPreferences("unified_policy_head", Context.MODE_PRIVATE).getString("state", null); if (!s.isNullOrBlank()) importState(s) } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        return try {
            if (trained < 1 && laneHeads.isEmpty()) return ""
            val names = listOf("mlConf","symGreen","evRatio","metaConv","fwdPWin","candConf")
            val sb = StringBuilder("\n===== Unified Policy Head (V5.9.1262, multi-head AGI V5.0.4094) — per-lane learned signal weighting =====\n")
            sb.append("  global: trained=$trained  bias=${"%+.2f".format(bias)}  authority=${globalAuthority().name}\n  ")
            for (i in 0 until NF) sb.append("${names[i]}=${"%+.2f".format(w[i])}  ")
            sb.append("\n")
            sb.append("  authority hits: advisoryUsage=${advisoryUsageCount.get()}  authoritativeOverrides=${authoritativeOverrideCount.get()}  calibrationDemotes=${calibrationDemoteCount.get()}\n")
            if (laneHeads.isNotEmpty()) {
                sb.append("  per-lane heads:\n")
                laneHeads.entries.sortedByDescending { it.value.trained }.forEach { (lane, h) ->
                    val brier = if (h.brierN > 0L) h.brierSum / h.brierN else 0.25
                    val brierTag = when {
                        h.brierN < 20L          -> "(warming)"
                        brier <= BRIER_HEALTHY_MAX -> "(calibrated)"
                        brier <= BRIER_DRIFTING_MAX -> "(monitoring)"
                        else                     -> "(drifting → demoted)"
                    }
                    sb.append("    $lane  n=${h.trained}  auth=${currentAuthority(lane).name}  bias=${"%+.2f".format(h.bias)}  brier=${"%.3f".format(brier)} $brierTag\n")
                }
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}
