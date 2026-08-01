package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LosingPatternMemory
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.learning.TacticSwitcher

/**
 * V5.0.6405 §19 — PAPER→LIVE BUCKET-EV GATE.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "the live wallet balance must grow and the memetrader must have a
 *  positive ev when trading live". Paper achieves 90% WR on the same
 *  logic; live is at 36.7% WR / expectancy = -0.0002 SOL per trade.
 *  Snapshot's Tactic Switcher table shows why:
 *      MOONSHOT|S41-60 REACCUMULATION  n=5  W/L=2/3  μ=+533.6%   ← runner
 *      STANDARD|S0-10  MOMENTUM        n=7  W/L=2/5  μ=-39.6%    ← loser
 *      STANDARD|S11-25 MOMENTUM        n=3  W/L=0/3  μ=-100.0%   ← rug bucket
 *  Live was firing into STANDARD losers while MOONSHOT runners went
 *  under-funded. This gate refuses LIVE entries into buckets whose
 *  own trade history has proven negative EV with enough sample
 *  weight to trust the signal.
 *
 * DECISION RULES
 * ──────────────
 * Given the (lane, scoreBand) bucket from the TacticSwitcher:
 *
 *   • Trades < MIN_SAMPLE:  ALLOW (bucket is still exploring; paper
 *                            behaviour is preserved so learning
 *                            continues).
 *   • meanPnlPct < HARD_BLOCK_MEAN_PCT:  BLOCK — proven loser bucket.
 *   • wins/trades < HARD_BLOCK_WR:       BLOCK — chronic low winrate.
 *   • Everything else:                   ALLOW.
 *
 * Every block emits PAPER_EV_BUCKET_HARD_BLOCK_6405 forensic + counter
 * with the exact bucket key and stats so operators can grep the
 * reason a live entry was refused.
 *
 * Paper is NEVER gated — the gate is called only on live paths.
 */
object PaperEvBucketGate6405 {

    private const val MIN_SAMPLE: Int = 6
    private const val HARD_BLOCK_MEAN_PCT: Double = -15.0
    private const val HARD_BLOCK_WR: Double = 0.20

    data class Verdict(
        val block: Boolean,
        val bucketKey: String,
        val trades: Int,
        val winRate: Double,
        val meanPnlPct: Double,
        val reason: String,
    )

    fun evaluate(mint: String, symbol: String, lane: String, scoreInt: Int, isPaper: Boolean): Verdict {
        // Paper never gated — needs exposure to learn.
        if (isPaper) return Verdict(false, "-", 0, 0.0, 0.0, "PAPER_UNGATED")

        val band = try { LosingPatternMemory.scoreBand(scoreInt) } catch (_: Throwable) { "" }
        val key = "${lane.uppercase().take(24)}|${band.uppercase().take(8)}"

        val snap = try {
            TacticSwitcher.snapshotAll().firstOrNull { it.key == key }
        } catch (_: Throwable) { null }

        if (snap == null || snap.tradesSinceRotation < MIN_SAMPLE) {
            return Verdict(false, key, snap?.tradesSinceRotation ?: 0, 0.0, 0.0, "SAMPLE_TOO_SMALL")
        }
        val trades = snap.tradesSinceRotation
        val wins = snap.winsSinceRotation
        val winRate = if (trades > 0) wins.toDouble() / trades else 0.0
        val meanPnl = snap.meanPnlPct

        val block: Boolean
        val reason: String
        when {
            meanPnl < HARD_BLOCK_MEAN_PCT -> {
                block = true
                reason = "BUCKET_MEAN_PNL_${meanPnl.toInt()}PCT_UNDER_${HARD_BLOCK_MEAN_PCT.toInt()}"
            }
            winRate < HARD_BLOCK_WR -> {
                block = true
                reason = "BUCKET_WR_${(winRate * 100).toInt()}PCT_UNDER_${(HARD_BLOCK_WR * 100).toInt()}"
            }
            else -> { block = false; reason = "ALLOW_EV_OK" }
        }
        if (block) {
            try {
                ForensicLogger.lifecycle(
                    "PAPER_EV_BUCKET_HARD_BLOCK_6405",
                    "mint=${mint.take(10)} sym=$symbol bucket=$key trades=$trades " +
                        "wr=${"%.2f".format(winRate)} meanPnlPct=${"%.1f".format(meanPnl)} " +
                        "reason=$reason",
                )
                PipelineHealthCollector.labelInc("PAPER_EV_BUCKET_HARD_BLOCK_6405")
            } catch (_: Throwable) {}
            // V5.0.6407 §3 — LIVE-TO-PAPER COPY-BACK.
            // Signal the tactic switcher to re-explore in paper when
            // a bucket confirms >= 5 live losses. Idempotent within a
            // 5-min cooldown to avoid spamming every tick.
            if (trades >= 5) {
                try {
                    LiveToPaperCopyBack6407.trigger(
                        bucketKey = key,
                        lane = lane.uppercase(),
                        scoreBand = band,
                        trades = trades,
                        winRate = winRate,
                        meanPnlPct = meanPnl,
                    )
                } catch (_: Throwable) {}
            }
        }
        return Verdict(block, key, trades, winRate, meanPnl, reason)
    }

    /**
     * V5.0.6405 §19b — RUNNER FLOW BOOST.
     * Proven-winner buckets get a 1.5× size multiplier so real capital
     * finally reaches the runners the paper lane has already discovered.
     * Criteria (require ≥5 samples so the multiplier tracks real edge,
     * not just a lucky first trade):
     *
     *   • trades ≥ 5  AND (winRate ≥ 0.60 OR meanPnlPct ≥ +50)
     *
     * Returns 1.0 for everything else (baseline sizing preserved) and
     * for paper (paper sizing is governed separately by lab/lane logic).
     */
    fun sizeMultiplier(mint: String, symbol: String, lane: String, scoreInt: Int, isPaper: Boolean): Double {
        if (isPaper) return 1.0
        val band = try { LosingPatternMemory.scoreBand(scoreInt) } catch (_: Throwable) { "" }
        val key = "${lane.uppercase().take(24)}|${band.uppercase().take(8)}"
        val snap = try {
            TacticSwitcher.snapshotAll().firstOrNull { it.key == key }
        } catch (_: Throwable) { null } ?: return 1.0
        if (snap.tradesSinceRotation < 5) return 1.0
        val wins = snap.winsSinceRotation
        val trades = snap.tradesSinceRotation
        val winRate = if (trades > 0) wins.toDouble() / trades else 0.0
        val meanPnl = snap.meanPnlPct
        // V5.0.6407 — RUNNER BOOST AGGRESSION LADDER.
        //   tier 2: n >= 10 AND WR >= 70%       → 2.0× (elite bucket)
        //   tier 1: n >=  5 AND (WR >= 60% OR μ >= +50%) → 1.5× (winner)
        //   else                                → 1.0× baseline
        val boostMult: Double = when {
            trades >= 10 && winRate >= 0.70 -> 2.0
            winRate >= 0.60 || meanPnl >= 50.0 -> 1.5
            else -> 1.0
        }
        if (boostMult > 1.0) {
            try {
                ForensicLogger.lifecycle(
                    "RUNNER_FLOW_BOOST_6405",
                    "mint=${mint.take(10)} sym=$symbol bucket=$key trades=$trades " +
                        "wr=${"%.2f".format(winRate)} meanPnlPct=${"%.1f".format(meanPnl)} " +
                        "multiplier=$boostMult",
                )
                PipelineHealthCollector.labelInc(
                    if (boostMult >= 2.0) "RUNNER_FLOW_BOOST_ELITE_6407" else "RUNNER_FLOW_BOOST_6405",
                )
            } catch (_: Throwable) {}
        }
        return boostMult
    }
}
