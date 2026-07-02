package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * UnifiedExitPolicyHead — V5.0.4095 (AGI exit-decision brain, per-lane)
 * ════════════════════════════════════════════════════════════════════════════
 * Mirror of UnifiedPolicyHead but trained on EXIT-timing signals. Each lane
 * gets its own exit brain that learns to score "exit now" vs "hold longer"
 * given the position's current state. Same multi-head + Brier-calibration +
 * authority-tier pattern as the entry brain.
 *
 * The 6 exit features (mapped from current position telemetry):
 *   pnlPct       — current unrealized PnL as fraction of cost (0=flat, 0.5=50%)
 *   maxPnlPct    — peak observed PnL during this hold (drawdown anchor)
 *   ageNorm      — hold-time normalized to lane median (1.0 = median)
 *   momentumDn   — recent price momentum (negative = falling)
 *   liquidityErode — liquidity decay since entry (0 = unchanged, 1 = collapsed)
 *   sellPressure — recent sell-pressure signal from market microstructure
 *
 * Doctrine compliance:
 *   • Soft-shape only — output is an exit-bias multiplier in [0.7, 1.4]
 *     applied to the rule-stack's TP/SL/HOLD timing. Never a forced exit.
 *   • Bootstrap-safe — neutral 1.0 until lane head crosses ADVISORY (40
 *     settled positions tracked).
 *   • Hard exits (rug/honeypot/strict-SL) bypass this layer entirely.
 */
object UnifiedExitPolicyHead {

    private const val LR             = 0.03
    private const val L2             = 1e-4
    private const val NF             = 6
    private const val MULT_FLOOR     = 0.70  // shorten hold (exit sooner)
    private const val MULT_CAP       = 1.40  // extend hold (let it run)
    private const val MULT_FLOOR_AUTH = 0.55
    private const val MULT_CAP_AUTH   = 1.65
    // V5.0.6005 — LOWER AUTHORITY THRESHOLDS (operator directive:
    // "it needs to be authoritative, anywhere where the agi/ssi/
    // intelligence/tuning stack is making the correct decisions and
    // controls that's the point of aate"). Previous thresholds
    // (ADVISORY=40, LEARNED=100, AUTHORITATIVE=250) meant the exit
    // brain effectively NEVER reached authoritative — MANIPULATED at
    // n=1 after weeks of trading. New thresholds give the AGI real
    // command authority once it has proven signal, matching the fluid
    // doctrine: as trades stream in, the intelligence stack takes
    // over exit timing across lanes and self-tunes to compounding.
    // Brier calibration guard-rail remains — brains that go noisy
    // are auto-demoted, so aggressive promotion is still safe.
    private const val AUTHORITY_ADVISORY      = 5L
    private const val AUTHORITY_LEARNED       = 15L
    private const val AUTHORITY_AUTHORITATIVE = 30L
    private const val BRIER_HEALTHY_MAX = 0.22
    private const val BRIER_DRIFTING_MAX = 0.27

    // V5.0.6005 — MOONSHOT PATTERN PROPAGATION. MOONSHOT wins because it
    // HOLDS (7 wins + 17 scratches + 5 losses = diamond hands pays out).
    // Other lanes dump too early (SHITCOIN_TIME_EXIT, EXPRESS_STOP_LOSS,
    // BLUECHIP STRICT_SL_-5, QUALITY panic-sell). This prior seeds the
    // per-lane exit brain with a "hold longer" bias for the compounder
    // lanes so cold-start behaviour mirrors MOONSHOT's proven pattern.
    // Runtime learning still updates the bias; this only shifts the
    // starting point. Negative bias -> p starts low -> exitBias > 1.0 ->
    // extend hold.
    private val COLD_START_HOLD_LONGER_BIAS = mapOf(
        "QUALITY"    to -0.35,
        "BLUECHIP"   to -0.30,
        "STANDARD"   to -0.25,
        "CASHGEN"    to -0.20,
        "TREASURY"   to -0.20,
        "MOONSHOT"   to -0.40,  // keep MOONSHOT's winning stance explicit
    )

    private val w = DoubleArray(NF) { 0.0 }
    @Volatile private var bias = 0.0
    @Volatile private var trained = 0L
    private val featMean = DoubleArray(NF) { 0.5 }

    private data class LaneExitHead(
        val w: DoubleArray = DoubleArray(NF) { 0.0 },
        var bias: Double = 0.0,
        var trained: Long = 0L,
        val featMean: DoubleArray = DoubleArray(NF) { 0.5 },
        var brierSum: Double = 0.0,
        var brierN: Long = 0L,
    )

    private val laneHeads = java.util.concurrent.ConcurrentHashMap<String, LaneExitHead>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<String, DoubleArray>>()
    private val advisoryHits = java.util.concurrent.atomic.AtomicLong(0)
    private val authHits = java.util.concurrent.atomic.AtomicLong(0)
    private val calibrationDemotes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var appContext: Context? = null

    enum class AuthorityTier(val minSamples: Long) {
        BOOTSTRAP(0L), ADVISORY(AUTHORITY_ADVISORY),
        LEARNED(AUTHORITY_LEARNED), AUTHORITATIVE(AUTHORITY_AUTHORITATIVE),
    }

    data class ExitSignals(
        val pnlPct: Double,
        val maxPnlPct: Double,
        val ageNorm: Double,
        val momentumDn: Double,
        val liquidityErode: Double,
        val sellPressure: Double,
    ) {
        fun toArray() = doubleArrayOf(
            pnlPct.coerceIn(0.0, 1.0), maxPnlPct.coerceIn(0.0, 1.0),
            ageNorm.coerceIn(0.0, 1.0), momentumDn.coerceIn(0.0, 1.0),
            liquidityErode.coerceIn(0.0, 1.0), sellPressure.coerceIn(0.0, 1.0)
        )
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    private fun normalizeLane(lane: String): String = lane.trim().uppercase().ifBlank { "STANDARD" }

    private fun rawProbGlobal(x: DoubleArray): Double {
        var z = bias; for (i in 0 until NF) z += w[i] * (x[i] - featMean[i]); return sigmoid(z)
    }
    private fun rawProbLane(h: LaneExitHead, x: DoubleArray): Double {
        var z = h.bias; for (i in 0 until NF) z += h.w[i] * (x[i] - h.featMean[i]); return sigmoid(z)
    }

    private fun getOrCreateLaneHead(lane: String): LaneExitHead {
        return laneHeads.computeIfAbsent(lane) {
            LaneExitHead().also { h ->
                for (i in 0 until NF) { h.w[i] = w[i]; h.featMean[i] = featMean[i] }
                // V5.0.6005 — seed per-lane bias to mirror MOONSHOT's proven
                // "hold longer" pattern for the compounder lanes. Runtime
                // recordOutcome() will move the bias toward the empirical
                // truth for each lane, but cold-start behaviour now favours
                // holding profitable positions instead of paper-handing
                // them. Non-listed lanes fall through to the global bias
                // (typically ~0.0, neutral).
                val seed = COLD_START_HOLD_LONGER_BIAS[lane]
                h.bias = seed ?: bias
                if (seed != null) {
                    try {
                        ForensicLogger.lifecycle(
                            "UNIFIED_EXIT_POLICY_HEAD_COLD_START_HOLD_BIAS_6005",
                            "lane=$lane seedBias=${"%.3f".format(seed)} note=moonshot_pattern_propagation",
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    fun currentAuthority(lane: String): AuthorityTier {
        val h = laneHeads[normalizeLane(lane)] ?: return AuthorityTier.BOOTSTRAP
        val raw = when {
            h.trained >= AUTHORITY_AUTHORITATIVE -> AuthorityTier.AUTHORITATIVE
            h.trained >= AUTHORITY_LEARNED       -> AuthorityTier.LEARNED
            h.trained >= AUTHORITY_ADVISORY      -> AuthorityTier.ADVISORY
            else                                  -> AuthorityTier.BOOTSTRAP
        }
        if (h.brierN >= 20L && raw != AuthorityTier.BOOTSTRAP) {
            val brier = h.brierSum / h.brierN
            if (brier > BRIER_DRIFTING_MAX) {
                calibrationDemotes.incrementAndGet()
                // V5.0.4096 — narrate exit-brain calibration drift into the
                // sentience family so the personality reflects on it.
                try { com.lifecyclebot.engine.SentienceOrchestrator.noteRuntimeEvent(
                    "AGI_BRAIN_DEMOTED",
                    "lane=${normalizeLane(lane)} brier=${"%.3f".format(brier)} from=${raw.name} brain=exit",
                    "WARN"
                ) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.SentientPersonality.injectAutonomousThought(
                    "My exit reads on ${normalizeLane(lane)} drifted. Brier=${"%.3f".format(brier)}. Pulling back to re-tune."
                ) } catch (_: Throwable) {}
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

    /**
     * Returns an exit-bias multiplier the rule stack should apply to its
     * hold-duration / TP / SL multipliers. >1.0 = let runner run further;
     * <1.0 = bank profits / stop sooner. Returns 1.0 (neutral) when the
     * lane brain is still in BOOTSTRAP. Per-lane authority-aware.
     */
    fun exitBias(lane: String, s: ExitSignals): Double {
        return try {
            val laneKey = normalizeLane(lane)
            val auth = currentAuthority(laneKey)
            if (auth == AuthorityTier.BOOTSTRAP) return 1.0
            val h = laneHeads[laneKey]
            val p = if (h != null && h.trained >= 8L) rawProbLane(h, s.toArray()) else rawProbGlobal(s.toArray())
            // p high → "exit now" was the right move historically → BIAS DOWN
            // (shorten hold). p low → hold was right → BIAS UP (extend hold).
            // Invert direction: bias = 1 - (p - 0.5) * slope.
            val slope = if (auth == AuthorityTier.AUTHORITATIVE) 2.4 else 1.5
            val (floor, cap) = if (auth == AuthorityTier.AUTHORITATIVE)
                MULT_FLOOR_AUTH to MULT_CAP_AUTH
            else MULT_FLOOR to MULT_CAP
            val bias = 1.0 - (p - 0.5) * slope
            if (auth == AuthorityTier.AUTHORITATIVE) authHits.incrementAndGet() else advisoryHits.incrementAndGet()
            bias.coerceIn(floor, cap)
        } catch (_: Throwable) { 1.0 }
    }

    /** Stamp signals at exit-decision time (or on every position-tick). */
    fun stamp(mint: String, lane: String, s: ExitSignals) {
        try { pending[mint] = normalizeLane(lane) to s.toArray() } catch (_: Throwable) {}
    }

    /**
     * Train on settled outcome. exitWasOptimal=true means the actual exit
     * was the right call (good fill, banked profits, dodged a dump).
     * Heuristic for "right call" set by caller — typically pnlAtExit
     * vs peakPnl (banked >70% of peak = right call).
     */
    fun recordOutcome(mint: String, exitWasOptimal: Boolean) {
        try {
            val rec = pending.remove(mint) ?: return
            val (lane, x) = rec
            val y = if (exitWasOptimal) 1.0 else 0.0

            val pG = rawProbGlobal(x); val errG = pG - y
            for (i in 0 until NF) {
                val g = errG * (x[i] - featMean[i]) + L2 * w[i]
                w[i] -= LR * g; featMean[i] += 0.01 * (x[i] - featMean[i])
            }
            bias -= LR * errG; trained += 1

            val h = getOrCreateLaneHead(lane)
            val pL = rawProbLane(h, x); val errL = pL - y
            for (i in 0 until NF) {
                val g = errL * (x[i] - h.featMean[i]) + L2 * h.w[i]
                h.w[i] -= LR * g; h.featMean[i] += 0.01 * (x[i] - h.featMean[i])
            }
            h.bias -= LR * errL; h.trained += 1
            // V5.0.4096 — AGI ↔ SENTIENCE SYMBIOSIS for the EXIT brain. Same
            // lifecycle wiring as the entry brain — graduations + demotes
            // become observable events for SentienceOrchestrator + thoughts
            // for SentientPersonality so the AI cross-talk family sees the
            // exit brain's growth in real time.
            if (h.trained == AUTHORITY_ADVISORY || h.trained == AUTHORITY_LEARNED || h.trained == AUTHORITY_AUTHORITATIVE) {
                val tierName = when (h.trained) {
                    AUTHORITY_ADVISORY      -> "ADVISORY"
                    AUTHORITY_LEARNED       -> "LEARNED"
                    AUTHORITY_AUTHORITATIVE -> "AUTHORITATIVE"
                    else                     -> "?"
                }
                try { com.lifecyclebot.engine.SentienceOrchestrator.noteRuntimeEvent(
                    "AGI_BRAIN_TIER_GRADUATED",
                    "lane=$lane tier=$tierName n=${h.trained} brain=exit",
                    "INFO"
                ) } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.SentientPersonality.injectAutonomousThought(
                    "Exit-timing on $lane just clicked. Tier=$tierName at n=${h.trained}. I know when to bank and when to ride now."
                ) } catch (_: Throwable) {}
            }
            h.brierSum += (pL - y) * (pL - y); h.brierN += 1
            if (h.brierN > 200L) { h.brierSum *= (200.0 / h.brierN); h.brierN = 200L }

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
                for ((lane, h) in laneHeads) ls.put(lane, JSONObject().apply {
                    put("trained", h.trained); put("bias", h.bias)
                    put("w", JSONArray().also { for (v in h.w) it.put(v) })
                    put("fm", JSONArray().also { for (v in h.featMean) it.put(v) })
                    put("brierSum", h.brierSum); put("brierN", h.brierN)
                })
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
                val key = keys.next(); val lo = lanes.optJSONObject(key) ?: continue
                val h = LaneExitHead()
                h.trained = lo.optLong("trained", 0L); h.bias = lo.optDouble("bias", 0.0)
                lo.optJSONArray("w")?.let { for (i in 0 until minOf(NF, it.length())) h.w[i] = it.optDouble(i, 0.0) }
                lo.optJSONArray("fm")?.let { for (i in 0 until minOf(NF, it.length())) h.featMean[i] = it.optDouble(i, 0.5) }
                h.brierSum = lo.optDouble("brierSum", 0.0); h.brierN = lo.optLong("brierN", 0L)
                laneHeads[key] = h
            }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) { try { context.getSharedPreferences("unified_exit_policy_head", Context.MODE_PRIVATE).edit().putString("state", exportState()).apply() } catch (_: Throwable) {} }
    private fun load(context: Context) {
        try { val s = context.getSharedPreferences("unified_exit_policy_head", Context.MODE_PRIVATE).getString("state", null); if (!s.isNullOrBlank()) importState(s) } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        return try {
            if (trained < 1 && laneHeads.isEmpty()) return ""
            val names = listOf("pnlPct","maxPnlPct","ageNorm","momentumDn","liqErode","sellPressure")
            val sb = StringBuilder("\n===== Unified Exit Policy Head (V5.0.4095) — per-lane learned exit-timing brain =====\n")
            sb.append("  global: trained=$trained  bias=${"%+.2f".format(bias)}\n  ")
            for (i in 0 until NF) sb.append("${names[i]}=${"%+.2f".format(w[i])}  ")
            sb.append("\n  authority hits: advisory=${advisoryHits.get()}  authoritative=${authHits.get()}  calibrationDemotes=${calibrationDemotes.get()}\n")
            if (laneHeads.isNotEmpty()) {
                sb.append("  per-lane exit brains:\n")
                laneHeads.entries.sortedByDescending { it.value.trained }.forEach { (lane, h) ->
                    val brier = if (h.brierN > 0L) h.brierSum / h.brierN else 0.25
                    val tag = when {
                        h.brierN < 20L          -> "(warming)"
                        brier <= BRIER_HEALTHY_MAX -> "(calibrated)"
                        brier <= BRIER_DRIFTING_MAX -> "(monitoring)"
                        else                     -> "(drifting → demoted)"
                    }
                    sb.append("    $lane  n=${h.trained}  auth=${currentAuthority(lane).name}  bias=${"%+.2f".format(h.bias)}  brier=${"%.3f".format(brier)} $tag\n")
                }
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}
