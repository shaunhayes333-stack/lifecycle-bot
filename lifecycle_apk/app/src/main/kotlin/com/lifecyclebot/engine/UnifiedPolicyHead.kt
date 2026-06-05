package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * UnifiedPolicyHead — V5.9.1262  (Roadmap STEP 3: one learned policy head)
 * ════════════════════════════════════════════════════════════════════════════
 * The decision today is a COMMITTEE: ML conf, symbolic green-light, EV, meta-
 * policy conviction, forward-model pWin each shape the verdict independently.
 * That's an ensemble of votes with hand-set weights. This layer learns ONE
 * coherent weighting OVER those committee outputs — a single online logistic
 * head whose inputs are the other brains' signals and whose target is the real
 * trade outcome (win=1/loss=0). It answers: "given what all my sub-brains say,
 * what's the probability THIS trade wins, and how should that move my size?"
 *
 * WHY SURGICAL (not a rip-and-replace of the committee): replacing the voting
 * stack wholesale is butterfly-prone and would regress months of tuned gates.
 * Instead the committee keeps computing exactly as-is; the head sits at the END
 * as a learned META-AGGREGATOR. It owns HOW the signals combine, learned from
 * outcomes, and emits a soft size multiplier. Over time it can dominate the
 * hand-set per-signal multipliers because it's trained on ground truth.
 *
 * MODEL: online logistic regression, 6 features + bias, SGD with the same
 * pattern as OnDeviceMLEngine. Calibrated probability → conviction multiplier.
 *
 * DOCTRINE COMPLIANCE:
 *   • SOFT-SHAPE ONLY — multiplier in [0.6, 1.4]; never veto, never zero.
 *   • Bootstrap-safe — neutral 1.0 until >= MIN_SAMPLES trained.
 *   • Persisted weights, fail-open.
 */
object UnifiedPolicyHead {

    private const val MIN_SAMPLES = 40
    private const val LR          = 0.03
    private const val L2          = 1e-4
    private const val MULT_FLOOR  = 0.60
    private const val MULT_CAP    = 1.40
    private const val NF          = 6   // feature count

    // weights[0..NF-1] + bias
    private val w = DoubleArray(NF) { 0.0 }
    @Volatile private var bias = 0.0
    @Volatile private var trained = 0L
    fun trainedCount(): Long = trained  // V5.9.1355 P0.5 audit
    @Volatile private var appContext: Context? = null
    // running feature means for centring (stabilises SGD)
    private val featMean = DoubleArray(NF) { 0.5 }

    /** Feature vector, all roughly normalised to ~[0,1]. */
    data class Signals(
        val mlEntryConf: Double,     // OnDeviceMLEngine entryConfidence [0,1]
        val symGreenLight: Double,   // SymbolicContext green-light [0,1]
        val evRatio: Double,         // EV multiplier centred: clamp((ev-0.8)/0.8,0,1)
        val metaConviction: Double,  // AutonomousMetaPolicy conviction → map [0.55,1.45]→[0,1]
        val fwdPWin: Double,         // ForwardOutcomeModel pWin [0,1]
        val candConf: Double,        // candidate adjustedConfidence/100 [0,1]
    ) {
        fun toArray() = doubleArrayOf(
            mlEntryConf.coerceIn(0.0,1.0), symGreenLight.coerceIn(0.0,1.0),
            evRatio.coerceIn(0.0,1.0), metaConviction.coerceIn(0.0,1.0),
            fwdPWin.coerceIn(0.0,1.0), candConf.coerceIn(0.0,1.0)
        )
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

    private fun rawProb(x: DoubleArray): Double {
        var z = bias
        for (i in 0 until NF) z += w[i] * (x[i] - featMean[i])
        return sigmoid(z)
    }

    /** Predicted win-probability for a candidate given the committee signals. */
    fun predictWinProb(s: Signals): Double = try { rawProb(s.toArray()) } catch (_: Throwable) { 0.5 }

    /**
     * Conviction multiplier from the learned head. Neutral until trained enough.
     * pWin 0.5 → 1.0 ; pWin 0.8 → lean in ; pWin 0.2 → damp (floored).
     */
    fun conviction(s: Signals): Double {
        return try {
            if (trained < MIN_SAMPLES) return 1.0
            val p = rawProb(s.toArray())
            (1.0 + (p - 0.5) * 1.6).coerceIn(MULT_FLOOR, MULT_CAP)
        } catch (_: Throwable) { 1.0 }
    }

    /** Stamp the signals at decision time so the settled outcome trains the head. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()
    fun stamp(mint: String, s: Signals) { try { pending[mint] = s.toArray() } catch (_: Throwable) {} }

    /** Train on settled outcome: target = win(1)/loss(0). */
    fun recordOutcome(mint: String, pnlPct: Double) {
        try {
            val x = pending.remove(mint) ?: return
            val y = if (pnlPct > 0.0) 1.0 else 0.0
            val p = rawProb(x)
            val err = p - y                       // dL/dz for logistic
            for (i in 0 until NF) {
                val g = err * (x[i] - featMean[i]) + L2 * w[i]
                w[i] -= LR * g
                // update running feature mean slowly
                featMean[i] += 0.01 * (x[i] - featMean[i])
            }
            bias -= LR * err
            trained += 1
            if (trained % 25L == 0L) appContext?.let { save(it) }
        } catch (_: Throwable) {}
    }

    // ── Persistence ──────────────────────────────────────────────────────
    fun attachContext(context: Context) { try { appContext = context.applicationContext; load(context) } catch (_: Throwable) {} }

    fun exportState(): String = try {
        JSONObject().apply {
            put("trained", trained); put("bias", bias)
            put("w", org.json.JSONArray().also { for (v in w) it.put(v) })
            put("fm", org.json.JSONArray().also { for (v in featMean) it.put(v) })
        }.toString()
    } catch (_: Throwable) { "{}" }

    fun importState(json: String) {
        try {
            if (json.isBlank() || json == "{}") return
            val o = JSONObject(json)
            trained = o.optLong("trained", 0L); bias = o.optDouble("bias", 0.0)
            o.optJSONArray("w")?.let { for (i in 0 until minOf(NF, it.length())) w[i] = it.optDouble(i, 0.0) }
            o.optJSONArray("fm")?.let { for (i in 0 until minOf(NF, it.length())) featMean[i] = it.optDouble(i, 0.5) }
        } catch (_: Throwable) {}
    }

    private fun save(context: Context) { try { context.getSharedPreferences("unified_policy_head", Context.MODE_PRIVATE).edit().putString("state", exportState()).apply() } catch (_: Throwable) {} }
    private fun load(context: Context) {
        try { val s = context.getSharedPreferences("unified_policy_head", Context.MODE_PRIVATE).getString("state", null); if (!s.isNullOrBlank()) importState(s) } catch (_: Throwable) {}
    }

    fun formatForPipelineDump(): String {
        return try {
            if (trained < 1) return ""
            val names = listOf("mlConf","symGreen","evRatio","metaConv","fwdPWin","candConf")
            val sb = StringBuilder("\n===== Unified Policy Head (V5.9.1262) — learned signal weighting =====\n")
            sb.append("  trained=$trained  bias=${"%+.2f".format(bias)}  ${if (trained < MIN_SAMPLES) "(bootstrap — neutral until $MIN_SAMPLES)" else "(active)"}\n  ")
            for (i in 0 until NF) sb.append("${names[i]}=${"%+.2f".format(w[i])}  ")
            sb.append("\n")
            sb.toString()
        } catch (_: Throwable) { "" }
    }
}
