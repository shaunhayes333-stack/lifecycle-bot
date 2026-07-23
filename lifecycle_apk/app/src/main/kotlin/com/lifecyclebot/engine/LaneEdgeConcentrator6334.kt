package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.TacticSwitcher

/**
 * V5.0.6334 — LANE EDGE CONCENTRATOR (Self-Tuning From Trade 1).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *
 *   "we aren't meant to have to tune anything. the bot is supposed to
 *    from trade 1 tune the lanes and traders into the winning groove!!!"
 *
 * The bot ALREADY has fine-grained (lane × scoreBand) performance
 * tracking via the TacticSwitcher — snapshotAll() returns wins/losses/
 * meanPnlPct per bucket. But that data was only used to ROTATE tactic
 * choice; it was NOT feeding back into per-buy SIZING. So even when a
 * bucket had clearly proved itself (BLUECHIP|S61+ MOMENTUM: 8W/12L,
 * μ=+146.9% avg return in the 6332 snapshot) the executor still spent
 * the same capital as it did on losing MOONSHOT buckets. This module
 * closes the loop: winning buckets attract more capital, bleeding
 * buckets get faded — automatically, per trade, no operator input.
 *
 * INVARIANT: this module NEVER blocks a trade. It only shapes size.
 *            Rotation / hold decisions stay with TacticSwitcher /
 *            LiveEntrySafetyHold / ImmediateCollapseGuard.
 *
 * SHAPING RULES (all thresholds sample-gated so noise never trips them):
 *
 *   WINNER   (n >= 3, wr >= 40%, meanPnlPct > 0)
 *     multiplier = 1.0 + winScore   where winScore ∈ [0, 0.50]
 *     → up to 1.50× on the highest-conviction winning bucket
 *
 *   NEUTRAL  (n < 3, OR (wr in [25, 40) and meanPnlPct in [-3, +3]))
 *     multiplier = 1.0
 *     → cold-start: let every fresh bucket run at baseline until it
 *       accumulates 3+ closes that prove edge or bleed
 *
 *   BLEEDER  (n >= 5, wr < 25%, meanPnlPct < 0)
 *     multiplier = 0.60 .. 0.85   (fade, never zero)
 *     → the trade still goes through so the sample keeps growing and
 *       TacticSwitcher can eventually rotate away from the losing
 *       tactic; we simply commit less capital in the meantime.
 *
 * All decisions are per (lane, scoreBand). No global average is used.
 * A fantastic BLUECHIP bucket cannot be dragged down by bad MOONSHOT
 * data, and a bleeding SHITCOIN bucket cannot benefit from BLUECHIP
 * wins.
 */
object LaneEdgeConcentrator6334 {

    /** Minimum n for the WINNER lane to activate concentration. */
    private const val WINNER_MIN_N: Int = 3

    /** Winner qualifying WR (%) — 40% covers the operator's proven
     *  BLUECHIP|S61+ MOMENTUM bucket (8W/12L = 40%, μ=+146.9%). */
    private const val WINNER_WR_PCT: Double = 40.0

    /** Maximum multiplier applied to a proven-winning bucket. */
    private const val WINNER_MAX_MULT: Double = 1.50

    /** Minimum n before we're willing to fade a bucket for bleeding. */
    private const val BLEEDER_MIN_N: Int = 5

    /** Bleeding-bucket WR (%) — below this and net-negative we fade. */
    private const val BLEEDER_WR_PCT: Double = 25.0

    /** Floor of the bleeder fade multiplier (never zero — sample still
     *  needs to grow so TacticSwitcher can rotate away). */
    private const val BLEEDER_MIN_MULT: Double = 0.60
    private const val BLEEDER_MAX_MULT: Double = 0.85

    /** Ceiling on the WR scaling curve — WR beyond this is treated as
     *  saturated at the maximum multiplier. */
    private const val WR_SATURATION_PCT: Double = 60.0

    data class ConcentratorVerdict(
        val bucketKey: String,
        val classification: String,          // WINNER / NEUTRAL / BLEEDER / NO_DATA
        val multiplier: Double,              // never negative
        val bucketN: Int,
        val bucketWrPct: Double,
        val bucketMeanPnlPct: Double,
    )

    /**
     * Read the per-bucket performance snapshot from TacticSwitcher and
     * return a size shaping verdict for the given (lane, score).
     *
     * Called from the executor at buy time, layered into the combined
     * multiplier along with governor / guard / brain consensus. Any
     * throwable falls back to `NEUTRAL` at 1.0 — never blocks.
     */
    fun evaluate(lane: String, score: Int): ConcentratorVerdict {
        val laneKey = try { LaneAlias.normalize(lane) } catch (_: Throwable) { lane.uppercase() }
        val band = try { LosingPatternMemory.scoreBand(score) } catch (_: Throwable) { "" }
        val key = "$laneKey|$band"

        val snapshots = try { TacticSwitcher.snapshotAll() } catch (_: Throwable) { emptyList() }
        val bucket = snapshots.firstOrNull { it.key.equals(key, ignoreCase = true) }

        if (bucket == null || bucket.tradesSinceRotation <= 0) {
            return ConcentratorVerdict(
                bucketKey = key,
                classification = "NO_DATA",
                multiplier = 1.0,
                bucketN = 0,
                bucketWrPct = 0.0,
                bucketMeanPnlPct = 0.0,
            )
        }

        val n = bucket.tradesSinceRotation
        val wins = bucket.winsSinceRotation
        val losses = bucket.lossesSinceRotation
        val decided = wins + losses
        val wrPct = if (decided > 0) (wins.toDouble() * 100.0 / decided) else 0.0
        val meanPnl = bucket.meanPnlPct

        // ── WINNER ────────────────────────────────────────────────
        // Enough sample, clearly winning WR, and net-positive average.
        if (n >= WINNER_MIN_N && wrPct >= WINNER_WR_PCT && meanPnl > 0.0) {
            // Scale multiplier linearly with WR between WINNER_WR_PCT
            // and WR_SATURATION_PCT, then saturate.
            val range = (WR_SATURATION_PCT - WINNER_WR_PCT).coerceAtLeast(1.0)
            val above = (wrPct - WINNER_WR_PCT).coerceAtLeast(0.0).coerceAtMost(range)
            val wrTerm = above / range                     // 0 .. 1
            // Small additional bonus for very strong mean PnL. +50% avg
            // return adds another 0.10 to the shaping term.
            val pnlBonus = (meanPnl / 200.0).coerceIn(0.0, 0.20)
            val winScore = (wrTerm * 0.40 + pnlBonus).coerceIn(0.0, 0.50)
            val mult = (1.0 + winScore).coerceIn(1.0, WINNER_MAX_MULT)
            try {
                PipelineHealthCollector.labelInc("LANE_EDGE_CONCENTRATOR_WINNER_6334")
                ForensicLogger.lifecycle(
                    "LANE_EDGE_CONCENTRATOR_WINNER_6334",
                    "bucket=$key n=$n wr=${"%.1f".format(wrPct)}% mean=${"%.1f".format(meanPnl)}% mult=${"%.2f".format(mult)}",
                )
            } catch (_: Throwable) {}
            return ConcentratorVerdict(key, "WINNER", mult, n, wrPct, meanPnl)
        }

        // ── BLEEDER ───────────────────────────────────────────────
        // Enough sample, clearly losing WR, and net-negative average.
        if (n >= BLEEDER_MIN_N && wrPct < BLEEDER_WR_PCT && meanPnl < 0.0) {
            // Deeper bleed = deeper fade, floored at BLEEDER_MIN_MULT.
            // WR from 0..25% maps linearly (fully wiped → floor,
            // approaching 25% → BLEEDER_MAX_MULT).
            val wrTerm = (wrPct / BLEEDER_WR_PCT).coerceIn(0.0, 1.0)
            val pnlTerm = if (meanPnl < -30.0) 0.0 else (1.0 - (kotlin.math.abs(meanPnl) / 30.0)).coerceIn(0.0, 1.0)
            val severity = ((wrTerm + pnlTerm) / 2.0).coerceIn(0.0, 1.0)
            val mult = (BLEEDER_MIN_MULT + severity * (BLEEDER_MAX_MULT - BLEEDER_MIN_MULT))
                .coerceIn(BLEEDER_MIN_MULT, BLEEDER_MAX_MULT)
            try {
                PipelineHealthCollector.labelInc("LANE_EDGE_CONCENTRATOR_BLEEDER_6334")
                ForensicLogger.lifecycle(
                    "LANE_EDGE_CONCENTRATOR_BLEEDER_6334",
                    "bucket=$key n=$n wr=${"%.1f".format(wrPct)}% mean=${"%.1f".format(meanPnl)}% mult=${"%.2f".format(mult)}",
                )
            } catch (_: Throwable) {}
            return ConcentratorVerdict(key, "BLEEDER", mult, n, wrPct, meanPnl)
        }

        // ── NEUTRAL — cold-start / mixed / small sample. ──────────
        try { PipelineHealthCollector.labelInc("LANE_EDGE_CONCENTRATOR_NEUTRAL_6334") } catch (_: Throwable) {}
        return ConcentratorVerdict(key, "NEUTRAL", 1.0, n, wrPct, meanPnl)
    }
}
