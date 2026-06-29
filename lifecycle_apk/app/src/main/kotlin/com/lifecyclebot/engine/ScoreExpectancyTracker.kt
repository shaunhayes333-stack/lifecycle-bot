package com.lifecyclebot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.436 — SCORE EXPECTANCY TRACKER (per-layer)
 *
 * Closes the open feedback loop in the meme learning system across EVERY
 * V3 sub-trader (ShitCoin, Moonshot, BlueChip, Quality, Manipulated,
 * CashGen). Before this tracker:
 *
 *   - sub-trader "learning" was a maturity progression (parameters lerp
 *     bootstrap→mature based on totalTrades + WR). Nothing attributed
 *     wins/losses back to the *score range* that produced them.
 *   - 5000 trades stuck at 30% WR with R:R ~2:1 = exact breakeven post-fee.
 *
 * After: every V3 close is bucketed by `(layer, score)`. Per-bucket rolling
 * window of last 200 pnlPct outcomes. An entry can be soft-rejected when
 * its bucket has 25+ samples AND mean pnlPct < -2.0% (provably losing
 * real money on average for that lane in that score range).
 *
 * Per-LAYER buckets prevent cross-contamination — a 60-69 score on
 * ShitCoin is a completely different signal than a 60-69 on BlueChip.
 *
 * Stays exploratory at low samples. Pure in-memory, thread-safe, fail-open.
 */
object ScoreExpectancyTracker {

    private const val TAG = "ScoreExpectancy"

    private const val BUCKET_WIDTH = 10
    private const val WINDOW = 200
    // V5.9.441 — RELAX. Was 25 / -2%. With 583 trades across 6 lanes +
    // 10-pt buckets, buckets were populating fast and many were already
    // past the -2% reject gate — the bot was choking itself into HOLD
    // forever. Reality check: a bucket with 25 samples at -2% mean could
    // easily be unlucky. We need MORE samples AND deeper loss before we
    // stop exploring a score range.
    //
    // V5.9.1241 — RE-CALIBRATE for the actual sample regime. Tuning Console
    // (5.0.3208) exposed that n=100 NEVER fires at real bucket sizes: live
    // SHITCOIN buckets were n=20..31 yet bleeding hard —
    //   S0-9   n=30  μ=-10.7%
    //   S20-29 n=7   μ=-21.3%
    //   S30-39 n=31  μ=-26.9%
    //   S50-59 n=4   μ=-57.1%
    // while S40-49 (n=20, μ=+76.4%) is the predictive winner. The n=100 gate
    // meant the ONLY active bleed-stop was the coarse 5-band danger-bucket
    // guard (75% loss-rate), which let the 10pt-granular bleeders through.
    // Lower the sample floor to 40 (statistically meaningful, not noise) and
    // deepen the reject mean to -8% so ONLY decisively-bleeding buckets are
    // skipped — the +76% winner band and any marginal bucket stay open for
    // exploration. This is a SOFT shape on the existing whitelist gate
    // (entry #86), not a new veto; it fires per-lane across all 6 consumers.
    // V5.9.1250 — SCORER-INVERSION FIX. Tuning Console (5.0.3217) confirmed the
    // scorer is inverted: SHITCOIN[40-49] μ+126.6% / MOONSHOT[40-49] μ+1198.3%
    // (mid bands win big) while SHITCOIN[50-59] μ-77.6% / MOONSHOT[50-59] μ-13.1%
    // (high bands bleed). The empirical feedback gate that is SUPPOSED to skip
    // net-losing score bands could never fire because the bleeders are tiny
    // (SHITCOIN[50-59] n=3, MOONSHOT[50-59] n=8) — all far below the n=40 floor.
    // So the inverted high-score losers entered unchecked and WR sat at 11.5%.
    // Lower the sample floor to 15: statistically meaningful for THIS bucket
    // regime (the comment above already shows real bleeders live at n=7..31),
    // not noise. Combined with the unchanged -8% reject mean, ONLY bands that
    // decisively bleed (worse than -8% over >=15 closes) get skipped — the +126%
    // / +1198% winner bands stay wide open. Pure soft-shape on the existing
    // entry #86 whitelist gate; no new veto, no scorer reweight, no lane starve.
    // Self-targeting: a healthy band averages positive and is never touched.
    private const val MIN_SAMPLES_FOR_REJECT = 15
    private const val REJECT_MEAN_PNL_PCT = -8.0

    /** Rolling pnlPct windows keyed by "LAYER:bucket". */
    private val windows = ConcurrentHashMap<String, ArrayDeque<Double>>()

    private fun keyOf(layer: String, score: Int): String {
        val s = score.coerceAtLeast(0)
        return "${layer.uppercase()}:${s / BUCKET_WIDTH}"
    }

    /**
     * Record the outcome of a closed trade for [layer] at [score].
     */
    fun record(layer: String, score: Int, pnlPct: Double) {
        try {
            // V5.9.1360 P0.4 — INTAKE SANITY (defense-in-depth, single source of
            // truth). Even though V3JournalRecorder already clamps before calling,
            // guard here so NO caller can ever poison a bucket window with a feed
            // artifact (the +1,340,125% glitch that produced μ=+1,005,094%). NaN/Inf
            // dropped; finite values clamped to the same [-100%,+5000%] learning band.
            val sane = when {
                pnlPct.isNaN() || pnlPct.isInfinite() -> {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ACCOUNTING_OUTLIER_NOT_TRAINED") } catch (_: Throwable) {}
                    return
                }
                pnlPct > 5000.0 -> { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ACCOUNTING_OUTLIER_NOT_TRAINED") } catch (_: Throwable) {}; 5000.0 }
                pnlPct < -100.0 -> -100.0
                else -> pnlPct
            }
            val key = keyOf(layer, score)
            val w = windows.computeIfAbsent(key) { ArrayDeque(WINDOW + 1) }
            synchronized(w) {
                w.addLast(sane)
                while (w.size > WINDOW) w.removeFirst()
            }
            LearningPersistence.onRecord()  // V5.9.438 — durable save
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "record error: ${e.message}")
        }
    }

    /** Mean pnlPct for [layer]@[score] bucket, or null when under-sampled. */
    fun bucketMean(layer: String, score: Int): Double? {
        val w = windows[keyOf(layer, score)] ?: return null
        synchronized(w) {
            if (w.size < MIN_SAMPLES_FOR_REJECT) return null
            // V5.9.1360 P0.4 — read-side clamp so any pre-1357 persisted poison in
            // the window (recorded before the intake guard existed) can never
            // surface an impossible mean into tuning/readiness/live-gating.
            val sane = w.map { when {
                it.isNaN() || it.isInfinite() -> 0.0
                it > 5000.0 -> 5000.0
                it < -100.0 -> -100.0
                else -> it
            } }
            return if (sane.isEmpty()) null else sane.sum() / sane.size
        }
    }

    /** Sample count in this bucket (0 if never recorded). */
    fun bucketSamples(layer: String, score: Int): Int {
        val w = windows[keyOf(layer, score)] ?: return 0
        synchronized(w) { return w.size }
    }



    data class LiveSizeShape(
        val multiplier: Double,
        val samples: Int,
        val meanPnlPct: Double,
        val reason: String,
    )

    /**
     * V5.0.4510 — live score-band WR/PnL tuning is size-only, never a veto.
     * shouldReject() remains paper/training-only; live uses this shaper to turn
     * mature bleeding lane+score bands into cheap probes while preserving sample
     * flow and hard-safety doctrine.
     */
    fun liveSizeShape(layer: String, score: Int): LiveSizeShape {
        val samples = bucketSamples(layer, score)
        val mean = bucketMean(layer, score) ?: return LiveSizeShape(1.0, samples, 0.0, "bootstrap")
        if (samples < MIN_SAMPLES_FOR_REJECT) return LiveSizeShape(1.0, samples, mean, "under_sampled")
        return when {
            mean <= -60.0 -> LiveSizeShape(0.25, samples, mean, "catastrophic_score_band_probe")
            mean <= -35.0 -> LiveSizeShape(0.35, samples, mean, "toxic_score_band_probe")
            mean <= REJECT_MEAN_PNL_PCT -> LiveSizeShape(0.55, samples, mean, "negative_score_band_probe")
            mean >= 35.0 && samples >= MIN_SAMPLES_FOR_REJECT -> LiveSizeShape(1.10, samples, mean, "positive_score_band_press")
            else -> LiveSizeShape(1.0, samples, mean, "neutral")
        }
    }

    /**
     * Decide whether to reject a candidate entry for [layer]@[score].
     * Allows trade through (returns false) when:
     *   - fewer than MIN_SAMPLES_FOR_REJECT closes in the bucket
     *   - rolling mean pnlPct >= REJECT_MEAN_PNL_PCT
     */
    fun shouldReject(layer: String, score: Int): Boolean {
        // V5.0.3847 — live entry authority is common-sense safety + route quote,
        // not learned expectancy veto. Keep this as paper/training shaping only;
        // live still records outcomes and reports bucket expectancy.
        if (try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }) {
            try { ForensicLogger.lifecycle("LIVE_EXPECTANCY_REJECT_BYPASSED", "layer=$layer score=$score") } catch (_: Throwable) {}
            return false
        }
        val mean = bucketMean(layer, score) ?: return false
        return mean < REJECT_MEAN_PNL_PCT
    }

    /**
     * V5.9.1257 — CALIBRATION-AWARE SIZE MULTIPLIER.
     * The Score-Band Calibration screen showed the scorer is INVERTED in several
     * lanes: high "confidence" bands (S40-60+) were net-NEGATIVE while some low
     * bands were net-positive. A high score therefore must NOT mean a big
     * position in a band that has proven to lose. This returns a graduated
     * soft-shape multiplier (NEVER a veto) based on this exact band's rolling
     * mean PnL:
     *   mean >= 0%            → 1.00  (healthy / positive band — untouched)
     *   -8% <= mean < 0%      → 0.70  (mild negative — trim)
     *   -15% <= mean < -8%    → 0.45  (clearly negative — already shouldReject territory)
     *   mean < -15%           → 0.25  (deeply negative band — heavy trim)
     * Returns 1.0 when the band has < MIN_SAMPLES_FOR_REJECT closes (don't shape
     * on noise) — so young/bootstrap bands are never shrunk. Independent of the
     * LosingPatternMemory danger path (that keys on loss COUNT; this keys on the
     * band's mean RETURN), so the two compose: a band can be trimmed for being
     * net-negative before it ever matures into a danger bucket.
     */
    fun calibrationSizeMult(layer: String, score: Int): Double {
        // V5.0.3847 — do not dust-size live probes from score expectancy.
        // Live uses tiny fixed executable entries; this multiplier remains active
        // for paper/backtest shaping and telemetry only.
        if (try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }) {
            return 1.0
        }
        val mean = bucketMean(layer, score) ?: return 1.0   // null = too few samples → no shaping
        return when {
            mean >= 0.0    -> 1.0
            mean >= -8.0   -> 0.70
            mean >= -15.0  -> 0.45
            else           -> 0.25
        }
    }

    /** One-line snapshot for [layer], or all layers if null. */
    fun snapshot(layer: String? = null): String {
        val parts = mutableListOf<String>()
        windows.toSortedMap().forEach { (key, w) ->
            if (layer != null && !key.startsWith("${layer.uppercase()}:")) return@forEach
            synchronized(w) {
                if (w.isNotEmpty()) {
                    val mean = w.sum() / w.size
                    val (lay, bucketStr) = key.split(":")
                    val bucket = bucketStr.toInt()
                    val lo = bucket * BUCKET_WIDTH
                    val hi = lo + BUCKET_WIDTH - 1
                    parts.add("$lay[$lo-$hi]n=${w.size}/μ=${"%+.1f".format(mean)}%")
                }
            }
        }
        return if (parts.isEmpty()) "no samples yet" else parts.joinToString(" ")
    }

    fun reset() { windows.clear() }

    // V5.9.438 — durable persistence hooks.
    fun exportState(): Map<String, List<Double>> {
        val out = mutableMapOf<String, List<Double>>()
        windows.forEach { (k, w) -> synchronized(w) { out[k] = w.toList() } }
        return out
    }
    fun importState(snapshot: Map<String, List<Double>>) {
        snapshot.forEach { (k, pnls) ->
            val q = ArrayDeque<Double>(pnls.size + 1)
            pnls.forEach { q.addLast(it) }
            windows[k] = q
        }
    }
}
