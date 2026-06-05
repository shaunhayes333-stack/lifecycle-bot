package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
import java.util.concurrent.ConcurrentHashMap

/**
 * HeldPositionPivotArbiter (V5.9.1351)
 * ════════════════════════════════════════════════════════════════════════════
 * THE PROBLEM IT SOLVES
 *   Every open position's exit technique was welded to its ENTRY lane:
 *   BotService derives `positionTradeType` purely from `ts.position.tradingMode`
 *   (stamped once at buy), and ModeSpecificExits is a `when(tradeType)` switch.
 *   So a token entered as a snipe is managed as a snipe until it dies — even
 *   when its LIVE metrics now match a different, more profitable technique.
 *   The backtest screamed this: the lane-agnostic "let-run + -15% floor" bucket
 *   netted positive while rigid specialist lanes (MOONSHOT, SHITCOIN) went
 *   net-negative and got flagged STOP TRADING. Money left on the table =
 *   winners round-tripped because the entry style never let them run, and
 *   stalled positions held because the entry style never told them to cut.
 *
 * WHAT IT DOES
 *   Once per supervisor tick, per open position, it asks the EXISTING predictive
 *   brains "given what this token looks like RIGHT NOW, which exit technique does
 *   the accumulated database expect to capture the most?" and pivots the
 *   position's live exit mode toward it — ahead of the move, not reacting to it:
 *     • ForwardOutcomeModel.forecast(lane,...) — forward pWin × expectedPnl per
 *       candidate technique, keyed on the token's CURRENT signature. The same
 *       brain FDG uses at entry, now consulted mid-hold.
 *     • MomentumPredictorAI.getPrediction(mint) — is it still climbing or rolling?
 *     • CollectiveIntelligenceAI.getModeRecommendation(mode) — does the hive
 *       favour this mode right now?
 *
 * DISCIPLINE (doctrine-compliant — see PERFORMANCE_DOCTRINE + active instructions)
 *   • SOFT-SHAPE ONLY. It changes HOW we hold/exit, never blocks a trade. No veto,
 *     no new gate. FDG and the unconditional -15% hard floor are untouched.
 *   • Anti-flip-flop: a pivot only fires when the candidate's blended score beats
 *     the current style's by CONVICTION_MARGIN, and not more than once per
 *     PIVOT_COOLDOWN_MS per position.
 *   • Self-crediting: on pivot it re-stamps ForwardOutcomeModel with the new lane
 *     so Executor.recordOutcome (already wired on close) credits the PIVOTED
 *     signature — the learning stack scores the decision it actually made.
 *   • Fail-open: any exception leaves the position on its entry style.
 *
 * It does NOT scale size / add to winners — that touches the buy/exposure path and
 * is a deliberate fast-follow. This push is pure exit-side.
 */
object HeldPositionPivotArbiter {

    private const val TAG = "PivotArbiter"

    // A pivot must clear the incumbent by this much blended-score margin to fire.
    private const val CONVICTION_MARGIN = 0.12
    // Minimum gap between pivots on the same position (anti-oscillation).
    private const val PIVOT_COOLDOWN_MS = 45_000L
    // Don't pivot in the first few seconds — let the entry thesis play out first.
    private const val MIN_HOLD_BEFORE_PIVOT_MS = 20_000L

    private val lastPivotMs = ConcurrentHashMap<String, Long>()
    private val pivotCount  = ConcurrentHashMap<String, Int>()

    /** Candidate live exit techniques, expressed as tradingMode strings that the
     *  existing BotService→ModeSpecificExits switch already understands. */
    private val CANDIDATES = listOf(
        "MOONSHOT",      // GRADUATION — let a confirmed runner run (the proven winner)
        "PUMP_SNIPER",   // SENTIMENT_IGNITION — ride an igniting pump, tighter
        "STANDARD",      // TREND_PULLBACK — widest patience, tightest stop (diamond-ish)
        "REVIVAL",       // REVERSAL_RECLAIM — take first target quick on a bounce
        "MICRO_CAP",     // FRESH_LAUNCH — fastest stop/partial for a fading fresh
    )

    data class PivotResult(
        val pivoted: Boolean,
        val fromMode: String,
        val toMode: String,
        val score: Double,
        val incumbentScore: Double,
        val reason: String,
    )

    /**
     * Evaluate one held position. If a better-fitting technique is found with
     * conviction, mutate ts.position.tradingMode in place and re-stamp the
     * outcome model. Returns a PivotResult for logging. Never throws.
     */
    fun evaluate(
        ts: TokenState,
        pnlPct: Double,
        peakPnlPct: Double,
        holdTimeMs: Long,
    ): PivotResult {
        val current = ts.position.tradingMode.uppercase().ifBlank { "STANDARD" }
        try {
            val mint = ts.mint
            val now = System.currentTimeMillis()

            // Gate 1: respect the entry thesis for the first moments.
            if (holdTimeMs < MIN_HOLD_BEFORE_PIVOT_MS) {
                return PivotResult(false, current, current, 0.0, 0.0, "too_early")
            }
            // Gate 2: cooldown — don't thrash.
            val last = lastPivotMs[mint] ?: 0L
            if (now - last < PIVOT_COOLDOWN_MS) {
                return PivotResult(false, current, current, 0.0, 0.0, "cooldown")
            }

            // ── Build the live signature the forecast model is keyed on ──
            val score = (ts.lastV3Score ?: 0).coerceIn(0, 100)
            val quality = resolveQuality(ts)
            val regime = try {
                MarketRegimeAI.getCurrentRegime().label
            } catch (_: Throwable) { "DEFAULT" }
            val edgePhase = "UNKNOWN"  // not tracked on held TokenState; coarse-key handles it

            // ── Predictive brains (shared, read-only) ──
            val momentum = try { MomentumPredictorAI.getPrediction(mint) } catch (_: Throwable) { null }
            val momentumBias = momentumBiasFor(momentum)   // [-0.15, +0.20]
            val drawFromPeak = if (peakPnlPct > 0.0) (peakPnlPct - pnlPct) else 0.0

            // ── Score the incumbent technique ──
            val incumbentScore = blendedScore(
                lane = current, score = score, quality = quality, regime = regime,
                edgePhase = edgePhase, momentumBias = momentumBias,
                pnlPct = pnlPct, drawFromPeak = drawFromPeak, isIncumbent = true,
            )

            // ── Find the best candidate ──
            var bestLane = current
            var bestScore = incumbentScore
            var bestReason = "incumbent"
            for (cand in CANDIDATES) {
                if (cand == current) continue
                val s = blendedScore(
                    lane = cand, score = score, quality = quality, regime = regime,
                    edgePhase = edgePhase, momentumBias = momentumBias,
                    pnlPct = pnlPct, drawFromPeak = drawFromPeak, isIncumbent = false,
                )
                if (s > bestScore) { bestScore = s; bestLane = cand; bestReason = "fwd+mom+hive" }
            }

            // Gate 3: conviction margin — only pivot if clearly better.
            if (bestLane == current || (bestScore - incumbentScore) < CONVICTION_MARGIN) {
                return PivotResult(false, current, current, bestScore, incumbentScore, "below_margin")
            }

            // ── PIVOT. Soft-shape: only changes the live exit style. ──
            ts.position.tradingMode = bestLane
            ts.position.tradingModeEmoji = emojiFor(bestLane)
            lastPivotMs[mint] = now
            pivotCount[mint] = (pivotCount[mint] ?: 0) + 1

            // Re-stamp so the settled outcome credits the PIVOTED signature.
            try {
                ForwardOutcomeModel.stamp(mint, bestLane, score, quality, regime, edgePhase)
            } catch (_: Throwable) {}

            try {
                ForensicLogger.lifecycle(
                    "HELD_PIVOT",
                    "mint=${mint.take(8)} sym=${ts.symbol} $current->$bestLane " +
                    "score=${"%.3f".format(bestScore)} inc=${"%.3f".format(incumbentScore)} " +
                    "pnl=${pnlPct.toInt()}% peak=${peakPnlPct.toInt()}% mom=${momentum?.name ?: "?"} " +
                    "n=${pivotCount[mint]}"
                )
            } catch (_: Throwable) {}

            return PivotResult(true, current, bestLane, bestScore, incumbentScore, bestReason)
        } catch (_: Throwable) {
            // Fail-open: never disturb a held position on error.
            return PivotResult(false, current, current, 0.0, 0.0, "error")
        }
    }

    /**
     * Blended fitness of a technique for THIS token RIGHT NOW.
     * Core = forward expectancy from the accumulated database (pWin × E),
     * shaped by live momentum + the hive's view of the mode + a small geometry
     * term that rewards letting a confirmed runner run and cutting a staller.
     */
    private fun blendedScore(
        lane: String, score: Int, quality: String, regime: String, edgePhase: String,
        momentumBias: Double, pnlPct: Double, drawFromPeak: Double, isIncumbent: Boolean,
    ): Double {
        // Forward expectancy from the proven outcome database.
        val fwd = try {
            ForwardOutcomeModel.forecast(lane, score, quality, regime, edgePhase)
        } catch (_: Throwable) { null }

        // pWin in [0,1]; expectedPnl normalised; bootstrap -> neutral 0.5.
        val pWin = fwd?.pWin ?: 0.5
        val expTerm = ((fwd?.expectedPnl ?: 0.0) / 100.0).coerceIn(-0.40, 0.20)
        val rugPenalty = (fwd?.pRug ?: 0.0) * 0.25
        // Trust forecasts with real samples more than bootstrap ones.
        val sampleTrust = if ((fwd?.samples ?: 0L) >= 10L) 1.0 else 0.55

        var s = (pWin - 0.5) + expTerm - rugPenalty
        s *= sampleTrust

        // Hive-mind view of this mode (read-only).
        s += hiveBiasFor(lane)

        // Live momentum bias applies most to the "let it run" techniques.
        val runnerLane = lane == "MOONSHOT" || lane == "STANDARD"
        if (runnerLane) s += momentumBias else s += momentumBias * 0.4

        // Geometry nudge: a position in profit with momentum should lean to runner
        // techniques; one bleeding from peak should lean to quick-cut techniques.
        if (pnlPct > 8.0 && drawFromPeak < 6.0 && runnerLane) s += 0.06
        if (drawFromPeak > 18.0 && (lane == "MICRO_CAP" || lane == "REVIVAL")) s += 0.05

        // Tiny incumbency bonus so we don't pivot on noise-level ties.
        if (isIncumbent) s += 0.03

        return s
    }

    private fun momentumBiasFor(m: MomentumPredictorAI.MomentumPrediction?): Double = when (m?.name) {
        "STRONG_PUMP"   -> 0.20
        "PUMP_BUILDING" -> 0.10
        "WEAK"          -> -0.08
        "DISTRIBUTION"  -> -0.15
        else            -> 0.0
    }

    private fun hiveBiasFor(lane: String): Double {
        return try {
            when (CollectiveIntelligenceAI.getModeRecommendation(lane).name) {
                "STRONGLY_RECOMMENDED" -> 0.10
                "RECOMMENDED"          -> 0.05
                "CAUTION"              -> -0.05
                "AVOID"                -> -0.12
                else                   -> 0.0
            }
        } catch (_: Throwable) { 0.0 }
    }

    private fun resolveQuality(ts: TokenState): String {
        return try {
            val q = ts.meta.setupQuality
            if (q.isNotBlank() && q != "SKIP") q.take(1).uppercase() else "C"
        } catch (_: Throwable) { "C" }
    }

    private fun emojiFor(lane: String): String = when (lane) {
        "MOONSHOT"    -> "\uD83D\uDE80"
        "PUMP_SNIPER" -> "\uD83C\uDFAF"
        "REVIVAL"     -> "\uD83D\uDD04"
        "MICRO_CAP"   -> "\uD83D\uDD2C"
        else          -> "\uD83D\uDCC8"
    }

    /** Called by Executor on close to clean per-position state. */
    fun onClosed(mint: String) {
        lastPivotMs.remove(mint)
        pivotCount.remove(mint)
    }
}
