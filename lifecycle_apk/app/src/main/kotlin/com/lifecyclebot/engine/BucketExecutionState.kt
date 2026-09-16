package com.lifecyclebot.engine

/**
 * V5.9.1373 — SHADOW_TRAIN_ONLY authority (P0 spec #1: split learning from execution).
 *
 * THE PROBLEM (operator 5.0.3362): danger buckets only ever SHRANK entry size
 * (LosingPatternMemory.recommendedSizeMult -> x0.35) — they still opened real
 * paper/live BUYs. So provably-toxic buckets like SHITCOIN|S0-10 (27L/0W,
 * mean -45.96%) and SHITCOIN|S26-40 (32L/6W, mean -16.0%) kept executing,
 * contaminating headline winrate and P&L while "still learning on garbage".
 *
 * THE RULE (operator):
 *   if bucket.samples >= 20 and (bucket.lossRate >= 75% OR bucket.meanPnl <= -10%)
 *   then bucket.executionState = SHADOW_TRAIN_ONLY
 *
 * SHADOW_TRAIN_ONLY keeps the bucket fully TRAINABLE (counterfactual replay,
 * MFE/MAE, hypothesis engine, forward outcome model, learning memory — all of
 * which run upstream of the execution gate and on the NoTradeObservation path),
 * but it must NOT create an executable paper/live BUY, EXEC_OPEN_REQUEST,
 * EXEC_GATE_ALLOW, or count toward headline winrate.
 *
 * This object is the SINGLE SOURCE OF TRUTH for that decision. It is pure,
 * read-only over the existing learning stores (LosingPatternMemory +
 * ScoreExpectancyTracker), and fail-open: any error => EXECUTABLE (never
 * starve the trader on a telemetry glitch — doctrine).
 *
 * It deliberately does NOT touch tuning, SL/TP, or the scanner. It only answers
 * "may this bucket execute, or is it train-only?".
 */
object BucketExecutionState {

    enum class State { EXECUTABLE, SHADOW_TRAIN_ONLY }

    // Operator thresholds (5.0.3362 spec). Kept as named constants so a future
    // regression check can see exactly what gates execution.
    const val MIN_SAMPLES = 20
    const val MAX_LOSS_RATE = 0.75      // >= 75% loss rate => toxic
    const val MAX_MEAN_PNL_PCT = -10.0  // <= -10% mean pnl => toxic

    // V5.0.6756 §EXPRESS_EARNED_RECOVERY.
    // Runtime 5.0.6755: EXPRESS closed 12 trades at 9.1% WR / -29.2% mean and
    // the S41-60 cohort was 1W/9L, yet MIN_SAMPLES=20 meant none of that evidence
    // could stop normal economic execution. EXPRESS is deliberately the fastest
    // lane, so a collapsed lane needs an EARLY recovery mode: learning continues
    // on every candidate, but economic execution is reserved for unusually strong
    // scores until same-mode terminal truth proves recovery. Nothing is persisted;
    // the restriction self-clears immediately when the lane recovers.
    private const val EXPRESS_RECOVERY_MIN_TRADES = 5
    private const val EXPRESS_RECOVERY_MAX_WR_PCT = 20.0
    private const val EXPRESS_RECOVERY_SCORE_FLOOR = 70

    private fun expressRecoveryShadow6756(lane: String, score: Int): Boolean {
        val laneU = lane.trim().uppercase().replace('-', '_').replace(' ', '_')
        if (laneU != "EXPRESS" || score >= EXPRESS_RECOVERY_SCORE_FLOOR) return false
        return try {
            val board = if (RuntimeModeAuthority.isPaper())
                StrategyTelemetry.computeCleanPaperTerminalLeaderboard()
            else
                StrategyTelemetry.computeCleanLiveTerminalLeaderboard()
            val express = board.firstOrNull { it.strategy.trim().equals("EXPRESS", true) } ?: return false
            express.trades >= EXPRESS_RECOVERY_MIN_TRADES &&
                express.winRatePct < EXPRESS_RECOVERY_MAX_WR_PCT &&
                express.totalSolPnl < 0.0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Decide execution state for a (lane, score) bucket.
     *
     * Toxicity requires a MATURED sample (>= MIN_SAMPLES) so bootstrap noise
     * never boxes the bot out — consistent with the existing danger-bucket
     * doctrine (never disable learning, never starve on small samples).
     */
    fun stateFor(lane: String, score: Int): State {
        return try {
            // V5.0.6684 — old failed-strategy bucket history cannot permanently
            // shadow a new exact Lab-proven replacement.
            if (AdaptiveLaneReproof6684.activeStrategy(lane, LosingPatternMemory.scoreBand(score)) != null) {
                return State.EXECUTABLE
            }

            // V5.0.6756 — lane-level early collapse protection for EXPRESS.
            // High-score recovery attempts (S70+) remain executable so the lane
            // can re-prove itself; lower-score candidates stay fully trainable but
            // cannot keep dragging economic WR down while the lane is collapsed.
            if (expressRecoveryShadow6756(lane, score)) return State.SHADOW_TRAIN_ONLY

            val samples = ScoreExpectancyTracker.bucketSamples(lane, score)
            if (samples < MIN_SAMPLES) return State.EXECUTABLE

            val meanPnl = ScoreExpectancyTracker.bucketMean(lane, score)  // null if under-sampled
            val meanToxic = meanPnl != null && meanPnl <= MAX_MEAN_PNL_PCT

            // Loss-rate toxicity reuses the matured danger-zone flag, which is
            // exactly losses>=8 && n>=20 && lossRate>=75% — the operator's rule.
            val lossToxic = LosingPatternMemory.isDangerZone(lane, score)

            if (meanToxic || lossToxic) State.SHADOW_TRAIN_ONLY else State.EXECUTABLE
        } catch (_: Throwable) {
            State.EXECUTABLE  // fail-open: never starve on telemetry error
        }
    }

    fun isShadowTrainOnly(lane: String, score: Int): Boolean =
        stateFor(lane, score) == State.SHADOW_TRAIN_ONLY

    /** Human-readable diagnostic for the pipeline-health dump. */
    fun describe(lane: String, score: Int): String {
        return try {
            val n = ScoreExpectancyTracker.bucketSamples(lane, score)
            val mean = ScoreExpectancyTracker.bucketMean(lane, score)
            val danger = LosingPatternMemory.isDangerZone(lane, score)
            val expressRecovery = expressRecoveryShadow6756(lane, score)
            "lane=$lane score=$score n=$n mean=${mean?.let { "%.1f%%".format(it) } ?: "n/a"} danger=$danger expressRecovery=$expressRecovery state=${stateFor(lane, score)}"
        } catch (e: Throwable) {
            "lane=$lane score=$score state=EXECUTABLE(err:${e.message})"
        }
    }
}
