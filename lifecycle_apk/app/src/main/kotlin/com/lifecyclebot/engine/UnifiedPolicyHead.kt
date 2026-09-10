package com.lifecyclebot.engine

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.lifecyclebot.util.AppDispatchers

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
 * V5.0.6681 — CAUSAL ENTRY BINDING. Entry features are frozen against the
 *             canonical opened position + owner lane. A terminal outcome trains
 *             exactly one owner-lane head and the global head exactly once.
 *             Contributor/read-only lanes can no longer receive the owner's
 *             realised label, and later same-mint evaluations cannot overwrite
 *             the entry features that actually caused the position.
 *
 * MODEL: online logistic regression, NF features + bias, SGD per-lane.
 *
 * DOCTRINE COMPLIANCE:
 *   • Per-lane sub-head + global head update together ONCE per real terminal trade.
 *   • Soft-shape only — multiplier in [0.60, 1.40] (LEARNED) or [0.30, 1.80]
 *     (AUTHORITATIVE). Terminal-veto authority is lane-own only.
 *   • Bootstrap-safe — trade-one shaping ramps into lane-local authority.
 *   • Persisted per-lane weights and position-bound causal snapshots, fail-open.
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
    // V5.0.4179 / V5.0.6005 — aggressive online authority thresholds.
    private const val AUTHORITY_ADVISORY      = 3L
    private const val AUTHORITY_LEARNED       = 10L
    private const val AUTHORITY_AUTHORITATIVE = 25L
    private const val BRIER_HEALTHY_MAX = 0.22
    private const val BRIER_DRIFTING_MAX = 0.27

    // V5.0.6681 — old persisted weights were trained by mint-wide multi-lane
    // fanout (one close could update global N times and label non-owner lanes).
    // They are not statistically compatible with owner-bound training.
    private const val MODEL_VERSION_V6681 = 6681

    private val w = DoubleArray(NF) { 0.0 }
    @Volatile private var bias = 0.0
    @Volatile private var trained = 0L
    private val featMean = DoubleArray(NF) { 0.5 }

    private data class LaneHead(
        val w: DoubleArray = DoubleArray(NF) { 0.0 },
        var bias: Double = 0.0,
        var trained: Long = 0L,
        val featMean: DoubleArray = DoubleArray(NF) { 0.5 },
        var brierSum: Double = 0.0,
        var brierN: Long = 0L,
    )

    private data class BoundEntry6681(
        val mint: String,
        val ownerLane: String,
        val features: DoubleArray,
    )

    private val laneHeads = java.util.concurrent.ConcurrentHashMap<String, LaneHead>()

    // Decision-time scratchpad. Multiple desks may evaluate the same mint, but
    // these observations are NOT outcomes. At canonical open, only the elected
    // owner's feature vector is copied into pendingByPosition6681 and this map is
    // cleared for that mint.
    private val pending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, DoubleArray>>()
    private val pendingByPosition6681 = java.util.concurrent.ConcurrentHashMap<String, BoundEntry6681>()
    private val trainingLock6681 = Any()

    private val advisoryUsageCount = java.util.concurrent.atomic.AtomicLong(0)
    private val authoritativeOverrideCount = java.util.concurrent.atomic.AtomicLong(0)
    private val calibrationDemoteCount = java.util.concurrent.atomic.AtomicLong(0)
    private val causalBoundCount6681 = java.util.concurrent.atomic.AtomicLong(0)
    private val causalOutcomeCount6681 = java.util.concurrent.atomic.AtomicLong(0)
    private val causalMissCount6681 = java.util.concurrent.atomic.AtomicLong(0)
    private val legacyAmbiguousDropCount6681 = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var appContext: Context? = null

    fun trainedCount(): Long = trained
    fun advisoryUsageHits(): Long = advisoryUsageCount.get()
    fun authoritativeOverrideHits(): Long = authoritativeOverrideCount.get()
    fun calibrationDemoteHits(): Long = calibrationDemoteCount.get()
    fun causalBoundCount6681(): Long = causalBoundCount6681.get()
    fun causalOutcomeCount6681(): Long = causalOutcomeCount6681.get()
    fun causalMissCount6681(): Long = causalMissCount6681.get()

    /**
     * V5.0.6605 §PWIN_BOOTSTRAP_SEMANTICS.
     * Returns the LANE'S OWN-head trained count with no global fallback.
     */
    fun laneOwnHeadTrainedCount6605(lane: String): Long {
        val h = laneHeads[normalizeLane(lane)] ?: return 0L
        return h.trained
    }

    /** Explicit tier of the LANE'S OWN head, without global fallback. */
    fun laneOwnHeadAuthority6605(lane: String): AuthorityTier {
        val n = laneOwnHeadTrainedCount6605(lane)
        return when {
            n >= AUTHORITY_AUTHORITATIVE -> AuthorityTier.AUTHORITATIVE
            n >= AUTHORITY_LEARNED       -> AuthorityTier.LEARNED
            n >= AUTHORITY_ADVISORY      -> AuthorityTier.ADVISORY
            else                          -> AuthorityTier.BOOTSTRAP
        }
    }

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

    private fun getOrCreateLaneHead(lane: String): LaneHead {
        return laneHeads.computeIfAbsent(lane) {
            LaneHead().also { h ->
                for (i in 0 until NF) { h.w[i] = w[i]; h.featMean[i] = featMean[i] }
                h.bias = bias
            }
        }
    }

    fun predictWinProb(s: Signals): Double = predictWinProb("STANDARD", s)
    fun predictWinProb(lane: String, s: Signals): Double = try {
        val h = laneHeads[normalizeLane(lane)]
        if (h != null && h.trained >= 8L) rawProbLane(h, s.toArray()) else rawProbGlobal(s.toArray())
    } catch (_: Throwable) { 0.5 }

    fun brierScore(lane: String): Double {
        val h = laneHeads[normalizeLane(lane)] ?: return 0.25
        return if (h.brierN > 0L) h.brierSum / h.brierN else 0.25
    }

    fun currentAuthority(): AuthorityTier = currentAuthority("STANDARD")

    /**
     * V5.0.6681 §LANE_OWN_TERMINAL_AUTHORITY.
     *
     * V5.0.6596 correctly prevented a cold lane from being terminal-vetoed by
     * the global head. V5.0.6604 later reintroduced that exact failure for the
     * MEME family by treating global trained>=50 as if the lane itself were
     * authoritative. That made a cross-lane/global bias capable of vetoing a
     * fresh specialist before the specialist had causal owner-labelled samples.
     *
     * The global head remains a warm-start and soft-shaping fallback, but a
     * terminal learned veto requires the lane's OWN calibrated sample history.
     */
    fun laneHasOwnAuthoritativeHead(lane: String): Boolean {
        val h = laneHeads[normalizeLane(lane)] ?: return false
        return h.trained >= AUTHORITY_AUTHORITATIVE
    }

    /** Per-lane authority tier — calibration-aware. */
    fun currentAuthority(lane: String): AuthorityTier {
        val h = laneHeads[normalizeLane(lane)] ?: return globalAuthority()
        val rawTier = when {
            h.trained >= AUTHORITY_AUTHORITATIVE -> AuthorityTier.AUTHORITATIVE
            h.trained >= AUTHORITY_LEARNED       -> AuthorityTier.LEARNED
            h.trained >= AUTHORITY_ADVISORY      -> AuthorityTier.ADVISORY
            else                                  -> AuthorityTier.BOOTSTRAP
        }
        if (h.brierN >= 20L) {
            val brier = h.brierSum / h.brierN
            if (brier > BRIER_DRIFTING_MAX && rawTier != AuthorityTier.BOOTSTRAP) {
                calibrationDemoteCount.incrementAndGet()
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

    fun conviction(s: Signals): Double = conviction("STANDARD", s)
    fun conviction(lane: String, s: Signals): Double {
        return try {
            val laneKey = normalizeLane(lane)
            val h = laneHeads[laneKey]
            val auth = currentAuthority(laneKey)
            val trainedForRamp6077 = h?.trained ?: trained
            if (trainedForRamp6077 <= 0L && auth == AuthorityTier.BOOTSTRAP) return 1.0
            val p = if (h != null && h.trained >= 1L) rawProbLane(h, s.toArray()) else rawProbGlobal(s.toArray())
            advisoryUsageCount.incrementAndGet()
            val trade1Ramp6077 = if (auth == AuthorityTier.BOOTSTRAP)
                (trainedForRamp6077.toDouble() / AUTHORITY_ADVISORY.toDouble()).coerceIn(0.25, 1.0)
            else 1.0
            (1.0 + (p - 0.5) * 1.6 * trade1Ramp6077).coerceIn(MULT_FLOOR, MULT_CAP)
        } catch (_: Throwable) { 1.0 }
    }

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

    /** Decision-time observation only; no outcome is attached here. */
    fun stamp(mint: String, s: Signals) { stamp(mint, "STANDARD", s) }
    fun stamp(mint: String, lane: String, s: Signals) {
        try {
            val laneKey = normalizeLane(lane)
            pending.computeIfAbsent(mint) { java.util.concurrent.ConcurrentHashMap() }[laneKey] = s.toArray()
            appContext?.let { ctx -> GlobalScope.launch(AppDispatchers.sideEffect) { save(ctx) } }
        } catch (_: Throwable) {}
    }

    private fun ownerFeatureCandidateKeys6681(ownerLane: String): List<String> {
        val owner = normalizeLane(ownerLane)
        val keys = mutableListOf(owner)
        when (owner) {
            "BLUECHIP" -> keys += "BLUE_CHIP"
            "BLUE_CHIP" -> keys += "BLUECHIP"
        }
        if (owner in setOf(
                "QUALITY", "BLUECHIP", "BLUE_CHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
                "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
            )
        ) {
            // FDG still has legacy TradingModeTag stamps (notably MEME_GENERIC /
            // BLUE_CHIP) in some paths. At the canonical open boundary we may
            // use that feature vector, but the TRAINING IDENTITY is always the
            // real ExecutionBook owner lane supplied by the canonical position.
            keys += "MEME_GENERIC"
        }
        keys += "STANDARD"
        return keys.distinct()
    }

    /**
     * V5.0.6681 — freeze the exact entry observation against the canonical
     * position ID. This is called only after an executable position exists.
     */
    fun bindPosition6681(positionId: String, mint: String, ownerLane: String): Boolean {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return false
        return try {
            val observations = pending.remove(mint)
            if (observations == null || observations.isEmpty()) {
                causalMissCount6681.incrementAndGet()
                try { PipelineHealthCollector.labelInc("UNIFIED_POLICY_POSITION_BIND_MISSING_6681") } catch (_: Throwable) {}
                false
            } else {
                val owner = normalizeLane(ownerLane)
                var selected: DoubleArray? = null
                var sourceLane = ""
                for (k in ownerFeatureCandidateKeys6681(owner)) {
                    val x = observations[k]
                    if (x != null) { selected = x.copyOf(); sourceLane = k; break }
                }
                if (selected == null && observations.size == 1) {
                    val only = observations.entries.first()
                    selected = only.value.copyOf(); sourceLane = only.key
                }
                if (selected == null) {
                    causalMissCount6681.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("UNIFIED_POLICY_POSITION_BIND_AMBIGUOUS_6681") } catch (_: Throwable) {}
                    false
                } else {
                    pendingByPosition6681[positionId] = BoundEntry6681(mint, owner, selected)
                    causalBoundCount6681.incrementAndGet()
                    try {
                        ForensicLogger.lifecycle(
                            "UNIFIED_POLICY_POSITION_BOUND_6681",
                            "positionId=$positionId mint=$mint ownerLane=$owner sourceLane=$sourceLane observations=${observations.keys.sorted().joinToString(",")}",
                        )
                        PipelineHealthCollector.labelInc("UNIFIED_POLICY_POSITION_BOUND_6681")
                    } catch (_: Throwable) {}
                    appContext?.let { ctx -> GlobalScope.launch(AppDispatchers.sideEffect) { save(ctx) } }
                    true
                }
            }
        } catch (_: Throwable) { false }
    }

    /**
     * V5.0.6713 — deterministic recovery for a valid canonical OPEN whose
     * transient UnifiedPolicy scratchpad observation was lost before position
     * binding. Inputs come only from the immutable pre-open AATE decision.
     */
    fun bindDecisionFallback6713(
        positionId: String,
        mint: String,
        ownerLane: String,
        scoreFinal: Double,
        pWin: Double,
        expectedPnlPct: Double,
        rugP: Double,
        contributorEffect01: Double,
    ): Boolean {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return false
        if (pendingByPosition6681.containsKey(positionId)) return true
        return try {
            val owner = normalizeLane(ownerLane)
            val score01 = (scoreFinal / 100.0).coerceIn(0.0, 1.0)
            val ev01 = (0.5 + expectedPnlPct / 200.0).coerceIn(0.0, 1.0)
            val signals = Signals(
                mlEntryConf = score01,
                symGreenLight = (1.0 - rugP).coerceIn(0.0, 1.0),
                evRatio = ev01,
                metaConviction = contributorEffect01.coerceIn(0.0, 1.0),
                fwdPWin = pWin.coerceIn(0.0, 1.0),
                candConf = score01,
            )
            pendingByPosition6681[positionId] = BoundEntry6681(mint, owner, signals.toArray())
            causalBoundCount6681.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("UNIFIED_POLICY_DECISION_FALLBACK_BOUND_6713")
                ForensicLogger.lifecycle(
                    "UNIFIED_POLICY_DECISION_FALLBACK_BOUND_6713",
                    "positionId=$positionId mint=$mint ownerLane=$owner source=SEALED_AATE_DECISION",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    private fun trainOneOutcome6681(lane: String, x: DoubleArray, pnlPct: Double) {
        val y = if (pnlPct > 0.0) 1.0 else 0.0

        // Exactly ONE global update per terminal canonical position.
        val pG = rawProbGlobal(x)
        val errG = pG - y
        for (i in 0 until NF) {
            val g = errG * (x[i] - featMean[i]) + L2 * w[i]
            w[i] -= LR * g
            featMean[i] += 0.01 * (x[i] - featMean[i])
        }
        bias -= LR * errG
        trained += 1

        // Exactly ONE owner-lane update. Contributors/read-only lanes are not
        // labelled as if they executed this trade.
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
        h.brierSum += (pL - y) * (pL - y)
        h.brierN += 1
        if (h.brierN > 200L) {
            h.brierSum *= (200.0 / h.brierN)
            h.brierN = 200L
        }
    }

    /**
     * V5.0.6681 canonical training path. The position-bound entry snapshot must
     * match the finalized mint and owner lane; otherwise we SKIP rather than
     * poison the model with a guessed attribution.
     */
    fun recordOutcome6681(positionId: String, mint: String, ownerLane: String, pnlPct: Double): Boolean {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return false
        return try {
            val bound = pendingByPosition6681.remove(positionId)
            // Discard any observations accumulated while this mint was already
            // open. They were not entry causes and must not leak into re-entry.
            pending.remove(mint)
            val owner = normalizeLane(ownerLane)
            if (bound == null || bound.mint != mint || bound.ownerLane != owner) {
                causalMissCount6681.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "UNIFIED_POLICY_CAUSAL_OUTCOME_MISSING_6681",
                        "positionId=$positionId mint=$mint ownerLane=$owner boundMint=${bound?.mint ?: "none"} boundLane=${bound?.ownerLane ?: "none"}",
                    )
                    PipelineHealthCollector.labelInc("UNIFIED_POLICY_CAUSAL_OUTCOME_MISSING_6681")
                } catch (_: Throwable) {}
                false
            } else {
                synchronized(trainingLock6681) {
                    trainOneOutcome6681(owner, bound.features, pnlPct)
                }
                causalOutcomeCount6681.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "UNIFIED_POLICY_CAUSAL_OUTCOME_6681",
                        "positionId=$positionId mint=$mint ownerLane=$owner pnlPct=$pnlPct globalTrained=$trained laneTrained=${laneOwnHeadTrainedCount6605(owner)}",
                    )
                    PipelineHealthCollector.labelInc("UNIFIED_POLICY_CAUSAL_OUTCOME_6681")
                } catch (_: Throwable) {}
                appContext?.let { ctx -> GlobalScope.launch(AppDispatchers.sideEffect) { save(ctx) } }
                true
            }
        } catch (_: Throwable) { false }
    }

    /**
     * Compatibility path for any old caller/test. It is intentionally strict:
     * if more than one lane observation exists, no guessed multi-lane training
     * occurs. Production finality uses recordOutcome6681(positionId,...).
     */
    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val recs = pending.remove(mint) ?: return
            if (recs.size != 1) {
                legacyAmbiguousDropCount6681.incrementAndGet()
                try { PipelineHealthCollector.labelInc("UNIFIED_POLICY_LEGACY_AMBIGUOUS_DROP_6681") } catch (_: Throwable) {}
                return
            }
            val (lane, x) = recs.entries.first()
            synchronized(trainingLock6681) { trainOneOutcome6681(normalizeLane(lane), x.copyOf(), pnlPct) }
            try { PipelineHealthCollector.labelInc("UNIFIED_POLICY_LEGACY_SINGLE_OUTCOME_6681") } catch (_: Throwable) {}
            appContext?.let { ctx -> GlobalScope.launch(AppDispatchers.sideEffect) { save(ctx) } }
        } catch (_: Throwable) {}
    }

    fun attachContext(context: Context) { try { appContext = context.applicationContext; load(context) } catch (_: Throwable) {} }

    private fun resetModelState6681() {
        synchronized(trainingLock6681) {
            for (i in 0 until NF) { w[i] = 0.0; featMean[i] = 0.5 }
            bias = 0.0
            trained = 0L
            laneHeads.clear()
        }
        pending.clear()
        pendingByPosition6681.clear()
    }

    fun exportState(): String = try {
        JSONObject().apply {
            put("modelVersion", MODEL_VERSION_V6681)
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
            put("pending", JSONObject().also { po ->
                for ((mint, byLane) in pending) po.put(mint, JSONObject().also { lo ->
                    for ((lane, features) in byLane) lo.put(lane, JSONArray().also { a -> features.forEach(a::put) })
                })
            })
            put("boundPositions6681", JSONObject().also { bo ->
                for ((positionId, b) in pendingByPosition6681) {
                    bo.put(positionId, JSONObject().apply {
                        put("mint", b.mint)
                        put("ownerLane", b.ownerLane)
                        put("features", JSONArray().also { a -> b.features.forEach(a::put) })
                    })
                }
            })
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            val savedVersion = o.optInt("modelVersion", 0)
            if (savedVersion < MODEL_VERSION_V6681) {
                resetModelState6681()
                try {
                    ForensicLogger.lifecycle(
                        "UNIFIED_POLICY_HEAD_CAUSAL_STATE_RESET_6681",
                        "savedModelVersion=$savedVersion currentModelVersion=$MODEL_VERSION_V6681 reason=multi_lane_outcome_contamination",
                    )
                    PipelineHealthCollector.labelInc("UNIFIED_POLICY_HEAD_CAUSAL_STATE_RESET_6681")
                } catch (_: Throwable) {}
                return
            }
            trained = o.optLong("trained", 0L); bias = o.optDouble("bias", 0.0)
            o.optJSONArray("w")?.let { for (i in 0 until minOf(NF, it.length())) w[i] = it.optDouble(i, 0.0) }
            o.optJSONArray("fm")?.let { for (i in 0 until minOf(NF, it.length())) featMean[i] = it.optDouble(i, 0.5) }
            laneHeads.clear()
            o.optJSONObject("lanes")?.let { lanes ->
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
            }
            pending.clear()
            o.optJSONObject("pending")?.let { po ->
                val mints = po.keys()
                while (mints.hasNext()) {
                    val mint = mints.next()
                    val lo = po.optJSONObject(mint) ?: continue
                    val byLane = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()
                    val lanesPending = lo.keys()
                    while (lanesPending.hasNext()) {
                        val lane = lanesPending.next()
                        val a = lo.optJSONArray(lane) ?: continue
                        byLane[lane] = DoubleArray(a.length()) { i -> a.optDouble(i, 0.0) }
                    }
                    if (byLane.isNotEmpty()) pending[mint] = byLane
                }
            }
            pendingByPosition6681.clear()
            o.optJSONObject("boundPositions6681")?.let { bo ->
                val ids = bo.keys()
                while (ids.hasNext()) {
                    val positionId = ids.next()
                    val b = bo.optJSONObject(positionId) ?: continue
                    val mint = b.optString("mint", "")
                    val ownerLane = normalizeLane(b.optString("ownerLane", ""))
                    val a = b.optJSONArray("features") ?: continue
                    if (mint.isBlank() || ownerLane.isBlank() || a.length() != NF) continue
                    pendingByPosition6681[positionId] = BoundEntry6681(
                        mint, ownerLane, DoubleArray(NF) { i -> a.optDouble(i, 0.0) }
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) { try { context.getSharedPreferences("unified_policy_head", Context.MODE_PRIVATE).edit().putString("state", exportState()).apply() } catch (_: Throwable) {} }
    private fun load(context: Context) {
        try { val s = context.getSharedPreferences("unified_policy_head", Context.MODE_PRIVATE).getString("state", null); if (!s.isNullOrBlank()) importState(s) } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        return try {
            if (trained < 1 && laneHeads.isEmpty() && pendingByPosition6681.isEmpty()) return ""
            val names = listOf("mlConf","symGreen","evRatio","metaConv","fwdPWin","candConf")
            val sb = StringBuilder("\n===== Unified Policy Head (V5.9.1262, multi-head AGI V5.0.6681) — position-bound owner learning =====\n")
            sb.append("  global: trained=$trained  bias=${"%+.2f".format(bias)}  authority=${globalAuthority().name}\n  ")
            for (i in 0 until NF) sb.append("${names[i]}=${"%+.2f".format(w[i])}  ")
            sb.append("\n")
            sb.append("  authority hits: advisoryUsage=${advisoryUsageCount.get()}  authoritativeOverrides=${authoritativeOverrideCount.get()}  calibrationDemotes=${calibrationDemoteCount.get()}\n")
            sb.append("  causal6681: bound=${causalBoundCount6681.get()} outcomes=${causalOutcomeCount6681.get()} misses=${causalMissCount6681.get()} pendingPositions=${pendingByPosition6681.size} legacyAmbiguousDrops=${legacyAmbiguousDropCount6681.get()}\n")
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
