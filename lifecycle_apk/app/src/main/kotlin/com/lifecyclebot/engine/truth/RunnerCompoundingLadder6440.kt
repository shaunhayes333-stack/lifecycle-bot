package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6440 — RUNNER COMPOUNDING LADDER.
 *
 * OPERATOR DIRECTIVE:
 *   "Runner Compounding Ladder: add automatic size ladder that scales
 *    BUY size with wallet SOL so a \$50→\$500 leg trades 10x bigger
 *    than the \$50→\$100 leg."
 *
 * $50→$1M mindset math
 * ─────────────────────
 * Starting at ~0.3 SOL (~$50) with size 0.02 SOL/trade means each trade
 * risks ~7% of the wallet. At $500 that same 0.02 SOL is only 0.7% — the
 * bot has lost its compounding leverage. The ladder scales size linearly
 * with wallet SOL so the *risk fraction* stays approximately constant
 * across the $50 → $1M journey.
 *
 * Ladder tiers (SOL held → per-trade SOL)
 * ────────────────────────────────────────
 *   ≤ 0.3   → 0.02       (starter: $50 wallet)
 *   ≤ 0.6   → 0.04       ($100)
 *   ≤ 1.5   → 0.10       ($250)
 *   ≤ 3.0   → 0.20       ($500)
 *   ≤ 6.0   → 0.40       ($1k)
 *   ≤ 15.0  → 1.00       ($2.5k)
 *   ≤ 30.0  → 2.00       ($5k)
 *   ≤ 60.0  → 4.00       ($10k)
 *   ≤ 150.0 → 10.00      ($25k)
 *   ≤ 300.0 → 20.00      ($50k)
 *   > 300.0 → 40.00      ($75k+)
 *
 * Each tier is ~2x the previous (except the first hop). The tiers are
 * intentionally coarser as the wallet grows so single-trade drawdowns
 * cannot wipe the account. Under losing-streak cooldown the ladder is
 * IRRELEVANT — LosingStreakReflex6439 blocks buys entirely.
 *
 * The ladder is READ-ONLY. It does NOT force any trader to use the
 * suggested size; it returns the recommended size and each trader can
 * cap it further via lane discipline or wallet-position safety.
 */
object RunnerCompoundingLadder6440 {

    // (walletSolMax, suggestedSizeSol) tiers. Sorted by walletSolMax ASC.
    private val LADDER: List<Pair<Double, Double>> = listOf(
        0.3   to 0.02,
        0.6   to 0.04,
        1.5   to 0.10,
        3.0   to 0.20,
        6.0   to 0.40,
        15.0  to 1.00,
        30.0  to 2.00,
        60.0  to 4.00,
        150.0 to 10.00,
        300.0 to 20.00,
    )
    private const val TERMINAL_SIZE_SOL = 40.0

    private val queryCount = AtomicLong(0L)
    private val lastRecommendation = AtomicReference<Double>(0.02)
    private val lastWalletObserved = AtomicReference<Double>(0.0)

    /**
     * Return the ladder-recommended per-trade SOL size for the current
     * wallet balance.
     */
    fun recommendedSizeSol(walletSol: Double): Double {
        queryCount.incrementAndGet()
        lastWalletObserved.set(walletSol)
        val size = when {
            walletSol <= 0.0 -> LADDER.first().second
            else -> LADDER.firstOrNull { walletSol <= it.first }?.second ?: TERMINAL_SIZE_SOL
        }
        lastRecommendation.set(size)
        return size
    }

    /**
     * Clamp a caller-provided desired size against the ladder ceiling.
     * Callers that already computed a size can still use this to prevent
     * a stale learning artefact from firing a $10 trade on a $50 wallet.
     *
     * If desiredSizeSol is smaller than the ladder recommendation, it is
     * returned unchanged (traders can trade SMALLER if they choose, e.g.
     * for a probe trade). If it's larger, we cap it at the ladder.
     */
    fun clampToLadder(desiredSizeSol: Double, walletSol: Double): Double {
        val recommended = recommendedSizeSol(walletSol)
        val clamped = kotlin.math.min(desiredSizeSol, recommended)
        if (clamped < desiredSizeSol) {
            try {
                ForensicLogger.lifecycle(
                    "RUNNER_LADDER_CLAMP_6440",
                    "walletSol=${"%.3f".format(walletSol)} desired=${"%.3f".format(desiredSizeSol)} " +
                        "clampedTo=${"%.3f".format(clamped)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("RUNNER_LADDER_CLAMP_6440") } catch (_: Throwable) {}
        }
        return clamped
    }

    /**
     * When the wallet grows past a ladder rung, emit a lifecycle event so
     * the operator sees the compounding step visibly. Callers pass the
     * previous and new size; the emission is idempotent per (prev, next)
     * pair via a short-lived remembered value.
     */
    private val lastEmittedRung = AtomicReference<Double>(0.0)
    fun noteLadderStep(walletSol: Double) {
        val newSize = recommendedSizeSol(walletSol)
        val prev = lastEmittedRung.get()
        if (newSize != prev) {
            lastEmittedRung.set(newSize)
            try {
                ForensicLogger.lifecycle(
                    "RUNNER_LADDER_STEP_6440",
                    "walletSol=${"%.3f".format(walletSol)} sizeSol=${"%.3f".format(newSize)} " +
                        "prevSize=${"%.3f".format(prev)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("RUNNER_LADDER_STEP_6440") } catch (_: Throwable) {}
            // V5.0.6445 RUNNER LADDER AUTO-FEEDBACK — when the wallet
            // crosses UP to a higher rung, feed a positive shadow
            // reinforcement signal into the reward bus so the AGI stack
            // literally learns "compounding IS the goal". Down-crossings
            // are NOT reinforced (protects the model from learning to
            // shrink when a loss temporarily drops us a rung).
            if (newSize > prev) {
                try {
                    com.lifecyclebot.engine.truth.RewardPurityGate6441.acceptShadowSignal(
                        namespace = "RUNNER_LADDER_STEP_UP",
                        tag = "prev=${"%.3f".format(prev)}_new=${"%.3f".format(newSize)}_wallet=${"%.3f".format(walletSol)}",
                    )
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("RUNNER_LADDER_STEP_UP_REINFORCE_6445") } catch (_: Throwable) {}
            }
        }
    }

    fun statusLine(): String {
        val q = queryCount.get()
        val wallet = lastWalletObserved.get()
        val rec = lastRecommendation.get()
        return "queries=$q lastWalletSol=${"%.3f".format(wallet)} recommendedSizeSol=${"%.3f".format(rec)}"
    }
}
