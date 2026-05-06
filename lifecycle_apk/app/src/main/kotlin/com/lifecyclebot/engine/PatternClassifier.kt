package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * V5.9.69: PatternClassifier — online logistic-regression classifier that
 * learns from every closed trade to predict win probability at entry.
 *
 * This is the "local pattern brain" — no LLM calls, no external libs.
 * Features are continuous and normalised so the SGD update stays stable.
 *
 * Training loop:
 *   Executor.recordTrade(BUY)  → noteEntry(mint, features) stashes the vector
 *   Executor.recordTrade(SELL) → noteExit(mint, pnlPct, isLive) pops it and
 *                                runs one SGD step on the outcome
 *
 * Prediction loop:
 *   SmartSizer asks getConfidenceBoost(features, isPaperMode).
 *   Boost is scaled by classifier confidence AND gated for live mode until
 *   the classifier has seen at least MIN_LIVE_GRADUATION outcomes.
 *
 * Paper-only gate (per user requirement):
 *   - In paper mode: boost applied immediately, feeds learning fast.
 *   - In live mode: boost is ZERO until trainedLiveSamples >= 50 AND the
 *     live-sample validation win-rate is at least 55%. After that it phases
 *     in linearly.
 */
object PatternClassifier {

    private const val TAG = "PatternClassifier"
    private const val PREFS = "pattern_classifier_v1"

    // ── feature config ──────────────────────────────────────────────────
    // Feature order (14 dims, all roughly normalised to [-1, 1]):
    //   0  entryScore  ((x - 50) / 50)
    //   1  rsi         ((x - 50) / 50)
    //   2  momentum    ((x - 50) / 50)
    //   3  volScore    ((x - 50) / 50)
    //   4  pressScore  ((x - 50) / 50)
    //   5  velocity    ((x - 50) / 50)
    //   6  liquidityLog10 ((log10(max(liq,1)) - 4) / 2)   // 4 = $10k anchor
    //   7  mcapLog10      ((log10(max(mcap,1)) - 5) / 2)  // 5 = $100k anchor
    //   8  holderGrowth  (clamp(rate / 10, -1, 1))
    //   9  rangePct      (clamp(x / 30, -1, 1))
    //  10  posInRange    ((x - 50) / 50)
    //  11  move3Pct      (clamp(x / 20, -1, 1))
    //  12  move8Pct      (clamp(x / 40, -1, 1))
    //  13  isMeme        (+1 if symbol looks meme-y, else -1)
    private const val DIM = 14

    // weights[0] = bias, weights[1..DIM] = feature weights
    private val weights = DoubleArray(DIM + 1) { 0.0 }

    // ── counters ────────────────────────────────────────────────────────
    @Volatile private var totalSamples: Int = 0
    @Volatile private var trainedLiveSamples: Int = 0
    @Volatile private var liveWinningSamples: Int = 0
    @Volatile private var paperSamples: Int = 0
    @Volatile private var paperWinningSamples: Int = 0
    @Volatile private var lastTrainedAt: Long = 0L

    // ── learning rate (decays with sample count) ────────────────────────
    private const val ALPHA_0 = 0.05
    private const val L2 = 0.0001

    // ── live-mode gate ──────────────────────────────────────────────────
    private const val MIN_LIVE_GRADUATION = 50
    private const val MIN_LIVE_WINRATE = 0.55

    // ── pending entries, keyed by mint ──────────────────────────────────
    private val pending = ConcurrentHashMap<String, DoubleArray>()

    private var prefs: SharedPreferences? = null

    // ═══════════════════════════════════════════════════════════════════
    // FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════
    fun extract(ts: TokenState): DoubleArray {
        val m = ts.meta
        val x = DoubleArray(DIM)
        x[0] = norm50(ts.entryScore)
        x[1] = norm50(m.rsi)
        x[2] = norm50(m.momScore)
        x[3] = norm50(m.volScore)
        x[4] = norm50(m.pressScore)
        x[5] = norm50(m.velocityScore)
        x[6] = (safeLog10(ts.lastLiquidityUsd.coerceAtLeast(1.0)) - 4.0) / 2.0
        x[7] = (safeLog10(ts.lastMcap.coerceAtLeast(1.0)) - 5.0) / 2.0
        x[8] = clamp(ts.holderGrowthRate / 10.0, -1.0, 1.0)
        x[9] = clamp(m.rangePct / 30.0, -1.0, 1.0)
        x[10] = norm50(m.posInRange)
        x[11] = clamp(m.move3Pct / 20.0, -1.0, 1.0)
        x[12] = clamp(m.move8Pct / 40.0, -1.0, 1.0)
        x[13] = if (isMemeyLike(ts)) 1.0 else -1.0
        // V5.9.71: sanitise — any NaN / ±Inf from an upstream divide-by-zero
        // (e.g., rangePct on a flat candle) becomes 0.0 so the SGD step
        // never corrupts the weights.
        for (i in x.indices) {
            val v = x[i]
            if (v.isNaN() || v.isInfinite()) x[i] = 0.0
        }
        return x
    }

    private fun norm50(v: Double): Double {
        if (v.isNaN() || v.isInfinite()) return 0.0
        return clamp((v - 50.0) / 50.0, -1.0, 1.0)
    }
    private fun safeLog10(v: Double): Double = ln(v) / ln(10.0)
    private fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
    private fun isMemeyLike(ts: TokenState): Boolean {
        val s = ts.symbol.lowercase()
        return s.length <= 6 && !s.contains("usd") && !s.contains("wsol")
    }

    // ═══════════════════════════════════════════════════════════════════
    // INIT / PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        // V5.9.71: force-reset any weights persisted by V5.9.69/70 — those
        // sessions were poisoned by NaN-feature gradients. Users who've
        // already burned samples in that build get a clean slate on first
        // V5.9.71 launch and the sanitiser + guards keep it clean going
        // forward.
        val storedVer = prefs?.getInt("classifier_version", 0) ?: 0
        if (storedVer < 71) {
            ErrorLogger.warn(TAG, "🧹 Version bump v$storedVer → v71 — resetting to clear NaN-corrupted history")
            reset()
            prefs?.edit()?.putInt("classifier_version", 71)?.apply()
        }
        ErrorLogger.info(TAG, "🧠 INIT: samples=$totalSamples live=$trainedLiveSamples " +
            "paper=$paperSamples liveWinRate=${liveWinRatePct().toInt()}%")
    }

    private fun load() {
        prefs?.let { p ->
            for (i in 0..DIM) {
                val w = p.getFloat("w$i", 0f).toDouble()
                weights[i] = if (w.isNaN() || w.isInfinite()) 0.0 else w
            }
            totalSamples = p.getInt("total", 0)
            trainedLiveSamples = p.getInt("live", 0)
            liveWinningSamples = p.getInt("liveW", 0)
            paperSamples = p.getInt("paper", 0)
            paperWinningSamples = p.getInt("paperW", 0)
        }
        // V5.9.71: if any weight is non-finite (including loaded) hard reset
        // — an earlier NaN gradient could have poisoned the whole vector.
        if (weights.any { it.isNaN() || it.isInfinite() }) {
            ErrorLogger.warn(TAG, "⚠️ Non-finite weight on load — resetting classifier")
            for (i in weights.indices) weights[i] = 0.0
            save()
        }
    }

    private fun save() {
        prefs?.edit()?.apply {
            for (i in 0..DIM) putFloat("w$i", weights[i].toFloat())
            putInt("total", totalSamples)
            putInt("live", trainedLiveSamples)
            putInt("liveW", liveWinningSamples)
            putInt("paper", paperSamples)
            putInt("paperW", paperWinningSamples)
            apply()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LEARN
    // ═══════════════════════════════════════════════════════════════════
    /** Stash feature vector at BUY time. */
    fun noteEntry(mint: String, features: DoubleArray) {
        if (mint.isBlank()) return
        pending[mint] = features
    }

    /**
     * On SELL, look up the stashed vector and run one SGD step.
     * pnlPct convention: >=0.5 => win, <=-2.0 => loss, else scratch (ignored).
     */
    fun noteExit(mint: String, pnlPct: Double, isLive: Boolean) {
        if (mint.isBlank()) return
        val x = pending.remove(mint) ?: return

        val isWin = pnlPct >= 1.0  // V5.9.185: unified 1%
        val isLoss = pnlPct <= -2.0
        if (!isWin && !isLoss) return  // scratch — ignore

        val y = if (isWin) 1.0 else 0.0

        // Decaying learning rate so weights settle as samples accumulate.
        val alpha = ALPHA_0 / (1.0 + totalSamples / 200.0)

        // Forward pass
        val p = sigmoid(logit(x))
        val err = y - p

        // V5.9.71: hard guard — if we ever see a NaN/Inf in the forward pass,
        // the weights are poisoned. Reset to zero and skip this update so we
        // don't compound the corruption. All further steps then train cleanly
        // from a blank slate.
        if (p.isNaN() || p.isInfinite() || err.isNaN() || err.isInfinite()) {
            ErrorLogger.warn(TAG, "⚠️ NaN/Inf in forward pass (p=$p err=$err) — resetting weights")
            for (i in weights.indices) weights[i] = 0.0
            save()
            return
        }

        // SGD update with L2 regularisation
        weights[0] += alpha * err  // bias
        for (i in 0 until DIM) {
            val w = weights[i + 1]
            weights[i + 1] = w + alpha * (err * x[i] - L2 * w)
        }

        // Belt-and-braces: if the update somehow produced a non-finite weight
        // (shouldn't — features & err are both finite at this point — but
        // cheap to check), reset before it propagates.
        if (weights.any { it.isNaN() || it.isInfinite() }) {
            ErrorLogger.warn(TAG, "⚠️ Non-finite weight after SGD — resetting")
            for (i in weights.indices) weights[i] = 0.0
            save()
            return
        }

        totalSamples++
        if (isLive) {
            trainedLiveSamples++
            if (isWin) liveWinningSamples++
        } else {
            paperSamples++
            if (isWin) paperWinningSamples++
        }
        lastTrainedAt = System.currentTimeMillis()

        // Persist every 10 samples so we don't thrash SharedPreferences.
        if (totalSamples % 10 == 0) save()

        ErrorLogger.debug(TAG, "step mint=${mint.take(8)} y=${if (isWin) "W" else "L"} " +
            "p=${"%.3f".format(p)} err=${"%.3f".format(err)} α=${"%.4f".format(alpha)} " +
            "total=$totalSamples")
    }

    private fun logit(x: DoubleArray): Double {
        var s = weights[0]
        for (i in 0 until DIM) s += weights[i + 1] * x[i]
        return s
    }

    private fun sigmoid(z: Double): Double {
        // Numerically stable
        return if (z >= 0) 1.0 / (1.0 + exp(-z))
        else { val e = exp(z); e / (1.0 + e) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PREDICT
    // ═══════════════════════════════════════════════════════════════════
    fun predictWinProb(features: DoubleArray): Double {
        if (totalSamples < 5) return 0.5  // not enough data
        val z = logit(features)
        if (z.isNaN() || z.isInfinite()) return 0.5
        val p = sigmoid(z)
        return if (p.isNaN() || p.isInfinite()) 0.5 else p
    }

    /**
     * Returns an additive aiConfidence boost in [-15, +15].
     *
     *   p=0.50 → 0
     *   p=0.65 → +15
     *   p=0.35 → -15
     *
     * Gated in LIVE mode by the paper-only graduation rule:
     *   if live samples < 50 or liveWinRate < 55%, returns 0.
     */
    fun getConfidenceBoost(features: DoubleArray, isPaperMode: Boolean): Int {
        if (totalSamples < 20) return 0

        // V5.9.495z16 — operator mandate: a mature paper-trained classifier
        // must transfer to live, not restart from zero. Pre-fix: live got
        // ZERO confidence boost until 50 dedicated live trades at 55% WR,
        // even when totalSamples = 1000+ from paper. Post-fix: if the
        // classifier is paper-graduated (≥200 total samples and predictWinProb
        // not at chance), live inherits the same boost as paper.
        if (!isPaperMode) {
            val paperGraduated = totalSamples >= 200
            if (!paperGraduated && trainedLiveSamples < MIN_LIVE_GRADUATION) return 0
            // Tier 2: even if paper-graduated, if a live mini-cohort exists
            // and is severely underwater, throttle confidence (legitimate
            // real-money safety). Don't gate on liveWinRate alone — it could
            // be 1/3 = 33% from a tiny sample.
            if (trainedLiveSamples >= MIN_LIVE_GRADUATION && liveWinRate() < MIN_LIVE_WINRATE) return 0
        }

        val p = predictWinProb(features)
        val centred = (p - 0.5) * 2.0  // [-1..1]
        // Confidence scaling: low when we've seen little data, full strength
        // once we're past ~500 samples.
        val scale = min(1.0, totalSamples / 500.0)
        val raw = centred * 15.0 * scale
        return raw.toInt().coerceIn(-15, 15)
    }

    /**
     * Sizing multiplier — applied by SmartSizer after aiConfidence.
     * Range [0.7, 1.3] so a "good" pattern grows size by 30% and a "bad" one
     * shrinks by 30%. V5.9.495z16: live now inherits paper graduation.
     */
    fun getSizeMultiplier(features: DoubleArray, isPaperMode: Boolean): Double {
        if (totalSamples < 20) return 1.0
        if (!isPaperMode) {
            val paperGraduated = totalSamples >= 200
            if (!paperGraduated && trainedLiveSamples < MIN_LIVE_GRADUATION) return 1.0
            if (trainedLiveSamples >= MIN_LIVE_GRADUATION && liveWinRate() < MIN_LIVE_WINRATE) return 1.0
        }
        val p = predictWinProb(features)
        val centred = (p - 0.5) * 2.0
        val scale = min(1.0, totalSamples / 500.0)
        return (1.0 + centred * 0.3 * scale).coerceIn(0.7, 1.3)
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════
    fun liveWinRate(): Double =
        if (trainedLiveSamples > 0) liveWinningSamples.toDouble() / trainedLiveSamples else 0.0

    fun paperWinRate(): Double =
        if (paperSamples > 0) paperWinningSamples.toDouble() / paperSamples else 0.0

    fun liveWinRatePct(): Double = liveWinRate() * 100.0

    data class Stats(
        val totalSamples: Int,
        val liveSamples: Int,
        val paperSamples: Int,
        val liveWinRatePct: Double,
        val paperWinRatePct: Double,
        val liveGraduated: Boolean,
        val weightNorm: Double,
        val biggestFeatureIdx: Int,
        val biggestFeatureWeight: Double,
    )

    fun getStats(): Stats {
        var maxAbs = 0.0
        var idx = 0
        var normSq = 0.0
        for (i in 0 until DIM) {
            val w = weights[i + 1]
            normSq += w * w
            if (abs(w) > maxAbs) { maxAbs = abs(w); idx = i }
        }
        val graduated = trainedLiveSamples >= MIN_LIVE_GRADUATION &&
                        liveWinRate() >= MIN_LIVE_WINRATE
        return Stats(
            totalSamples = totalSamples,
            liveSamples = trainedLiveSamples,
            paperSamples = paperSamples,
            liveWinRatePct = liveWinRatePct(),
            paperWinRatePct = paperWinRate() * 100.0,
            liveGraduated = graduated,
            weightNorm = kotlin.math.sqrt(normSq),
            biggestFeatureIdx = idx,
            biggestFeatureWeight = weights[idx + 1],
        )
    }

    private val FEATURE_NAMES = arrayOf(
        "entryScore", "rsi", "momentum", "volScore", "pressScore", "velocity",
        "liqLog10", "mcapLog10", "holderGrowth", "rangePct", "posInRange",
        "move3", "move8", "isMemey"
    )

    fun getFeatureName(idx: Int): String = FEATURE_NAMES.getOrElse(idx) { "f$idx" }

    /** Force a save, e.g. on shutdown. */
    fun flush() { save() }

    /** Reset — wipe weights and counters. Use sparingly. */
    fun reset() {
        for (i in weights.indices) weights[i] = 0.0
        totalSamples = 0
        trainedLiveSamples = 0
        liveWinningSamples = 0
        paperSamples = 0
        paperWinningSamples = 0
        pending.clear()
        save()
        ErrorLogger.info(TAG, "🧹 RESET")
    }
}
