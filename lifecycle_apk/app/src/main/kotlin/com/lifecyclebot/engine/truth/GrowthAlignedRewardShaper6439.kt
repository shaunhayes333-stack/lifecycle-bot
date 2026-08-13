package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6439 — GROWTH-ALIGNED REWARD SHAPER.
 *
 * OPERATOR DIRECTIVE:
 *   "The meta cognition and sentience module and agi stack also need
 *    to be checked. Their priority should be wallet growth while
 *    protecting capital ... Bad behaviour and consistently buying
 *    into losers, the wallet balance shrinking, bad trading logic
 *    and strategy should NEVER be seen or recognised as good
 *    behaviour."
 *
 * This shaper is the ONE function every learner MUST call to convert
 * a closed trade into a training reward. Prior behaviour: SentienceAutoTune
 * / AdaptiveLearning / LabUniverseTick each computed their own reward
 * from mixes of ROI, hold time and "confidence" heuristics — meaning a
 * -30% loss held for 3 seconds could look "efficient" (small time) and
 * a break-even trade could count as "positive" if the confidence bar
 * was moved. That is the exact reward-hacking the operator called out.
 *
 * Shaping rules (all read from CapitalPreservationCreed6439 so tuning
 * the creed retunes the shaper automatically):
 *
 *   realized SOL delta > 0  → reward = delta × (1.0 + bonus for aligned
 *                                              ROI, penalty for over-hold)
 *   realized SOL delta = 0  → reward = -CREED_BREAKEVEN_PENALTY_SOL
 *                                      (break-even is NEVER positive)
 *   realized SOL delta < 0  → reward = delta × loss_amplifier(holdMs)
 *                                      (bag-holding scales the pain)
 *
 * The shaper also updates LosingStreakReflex6439 as a single funnel,
 * so any learner that respects the shaper automatically feeds the
 * capital-preservation cooldown as well.
 */
object GrowthAlignedRewardShaper6439 {

    /** Break-even is treated as a mild negative to prevent reward hacking. */
    private const val BREAKEVEN_PENALTY_SOL: Double = 0.0001

    /** Loss amplifier: every 5 minutes of bag-holding adds another 25% to the pain. */
    private const val BAGHOLD_STEP_MS: Long = 5L * 60L * 1000L
    private const val BAGHOLD_STEP_MULT: Double = 0.25
    private const val BAGHOLD_MAX_MULT: Double = 3.0

    private val totalShaped = AtomicLong(0L)
    private val totalLossShaped = AtomicLong(0L)
    private val totalWinShaped = AtomicLong(0L)
    private val totalBreakevenShaped = AtomicLong(0L)

    /**
     * Convert a closed trade into a training reward aligned with the
     * $50→$1M creed. All learners MUST call this for every closed
     * position — never invent their own reward formula.
     *
     * @param realizedSolDelta wallet SOL change from open→close (may be negative)
     * @param openedAtMs epoch ms the position opened at (for bag-holding penalty)
     * @param closedAtMs epoch ms the position closed at (usually now())
     * @param mint mint for forensic attribution
     * @return the shaped reward (unit: SOL, can be negative or positive)
     */
    fun shape(
        realizedSolDelta: Double,
        openedAtMs: Long,
        closedAtMs: Long,
        mint: String,
    ): Double {
        totalShaped.incrementAndGet()
        val holdMs = (closedAtMs - openedAtMs).coerceAtLeast(0L)
        val shaped = when {
            realizedSolDelta > 0.0 -> {
                totalWinShaped.incrementAndGet()
                shapeWin(realizedSolDelta, holdMs)
            }
            realizedSolDelta < 0.0 -> {
                totalLossShaped.incrementAndGet()
                shapeLoss(realizedSolDelta, holdMs)
            }
            else -> {
                totalBreakevenShaped.incrementAndGet()
                -BREAKEVEN_PENALTY_SOL
            }
        }
        // Funnel into the losing-streak reflex so every learner respecting
        // the shaper also feeds the capital-preservation cooldown.
        try { LosingStreakReflex6439.onTradeClosed(realizedSolDelta, mint) } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "REWARD_SHAPED_6439",
                "mint=${mint.take(12)} rawSol=${"%.5f".format(realizedSolDelta)} " +
                    "shapedSol=${"%.5f".format(shaped)} holdSec=${holdMs / 1000L}",
            )
        } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("REWARD_SHAPED_6439") } catch (_: Throwable) {}
        return shaped
    }

    private fun shapeWin(delta: Double, holdMs: Long): Double {
        // Reward held-too-long wins less. If a win took > 60 min, halve it —
        // we're a runner-compounding bot, slow wins are opportunity cost.
        val holdMin = holdMs / 60_000L
        val timePenalty = when {
            holdMin > 60L -> 0.5
            holdMin > 20L -> 0.75
            holdMin > 5L  -> 0.9
            else -> 1.0
        }
        return delta * timePenalty
    }

    private fun shapeLoss(delta: Double, holdMs: Long): Double {
        // Losses held longer than 5 min compound the negative signal to
        // teach every learner: cut losers FAST.
        val steps = (holdMs / BAGHOLD_STEP_MS).coerceAtMost((BAGHOLD_MAX_MULT / BAGHOLD_STEP_MULT).toLong())
        val mult = (1.0 + steps * BAGHOLD_STEP_MULT).coerceAtMost(BAGHOLD_MAX_MULT)
        return delta * mult   // delta is negative, so pain scales up
    }

    fun statusLine(): String {
        val n = totalShaped.get()
        val w = totalWinShaped.get()
        val l = totalLossShaped.get()
        val b = totalBreakevenShaped.get()
        val winRate = if (n > 0) 100.0 * w.toDouble() / n.toDouble() else 0.0
        return "shaped=$n wins=$w losses=$l breakevens=$b winRate=${"%.1f".format(winRate)}%"
    }
}
