package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6829 §STALE_MARK_RUNNER_PROTECTION — operator diagnosis item #6
 * for build 5.0.6828:
 *   "fresh quotes: 17,064 stale: 17,874 missing: 11,186
 *    OPEN_PNL_BASIS_REJECTED=1267 STALE_LIVE_PRICE_HOLD_PAPER_NOT_HARDFLOOR=1126
 *    mark ratio quarantine=350 canonical mark rejected=361.
 *    A strategy learner cannot correctly distinguish bad strategy from
 *    bad pricing if stale/fallback marks are determining terminal P&L.
 *    QUALITY showing ~-99.8% average terminal P&L is a major warning
 *    sign for precisely this reason."
 *
 * DESIGN — additive gate consulted at the paper-stale-scratch decision
 * surface in BotService. Consumers call `shouldHoldRunner(...)` and
 * `markScratchedForNonTrainable(positionId)` to:
 *   1. Enforce a 5-minute minimum hold after buy (Guard A).
 *   2. Never scratch a position whose lastKnownPnlPct >= 0 (Guard B).
 *   3. Require at least 6 refresh attempts before considering scratch
 *      (Guard C).
 *   4. When a scratch DOES fire, stamp positionId so the finalized
 *      envelope carries learningEligible=false.
 *
 * This is the same runner-protection contract the operator originally
 * shipped as V5.0.6818 on the branch fix/6756-pipeline-recovery, now
 * consolidated as a re-usable authority on main.
 */
object StaleMarkRunnerProtection6829 {

    private const val MIN_HOLD_AFTER_BUY_MS: Long = 5L * 60L * 1_000L
    private const val REFRESH_BUDGET = 6

    enum class Verdict {
        HOLD_MIN_HOLD,      // within 5-min buy protection
        HOLD_WINNER,        // last-known PnL >= 0
        HOLD_REFRESH_BUDGET,// refresh attempts < 6
        HOLD_DEFER,         // reserved for future
        SCRATCH_ALLOWED,    // budget exhausted AND loser AND past min-hold
    }

    private val refreshAttempts = ConcurrentHashMap<String, Int>()
    private val nonTrainableClosures = ConcurrentHashMap<String, Boolean>()

    private val evaluations = AtomicLong(0L)
    private val holdMinHold = AtomicLong(0L)
    private val holdWinner = AtomicLong(0L)
    private val holdRefreshBudget = AtomicLong(0L)
    private val scratchAllowed = AtomicLong(0L)
    private val nonTrainableStamps = AtomicLong(0L)

    /**
     * Consult at the stale-scratch decision surface.
     *
     * @param latchKey stable per-position key (e.g. "$mint:$entryTime")
     * @param positionId canonical position id (used for finalization stamp)
     * @param heldMsSinceBuy System.currentTimeMillis() - entryTime
     * @param lastKnownPnlPct last observed PnL % (Double)
     * @param lastKnownPnlOk true iff lastKnownPnlPct came from a valid mark
     */
    fun evaluate(
        latchKey: String,
        positionId: String,
        heldMsSinceBuy: Long,
        lastKnownPnlPct: Double,
        lastKnownPnlOk: Boolean,
    ): Verdict {
        evaluations.incrementAndGet()
        return try {
            // Guard A — minimum 5-minute hold after buy
            if (heldMsSinceBuy < MIN_HOLD_AFTER_BUY_MS) {
                holdMinHold.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("PAPER_STALE_MIN_HOLD_AFTER_BUY_6829")
                } catch (_: Throwable) {}
                return Verdict.HOLD_MIN_HOLD
            }
            // Guard B — never scratch a winner
            if (lastKnownPnlOk && lastKnownPnlPct >= 0.0) {
                holdWinner.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("PAPER_STALE_WINNER_PROTECTION_6829")
                    ForensicLogger.lifecycle(
                        "PAPER_STALE_WINNER_PROTECTION_6829",
                        "latchKey=${latchKey.take(40)} " +
                            "lastPnlPct=${"%.1f".format(lastKnownPnlPct)} " +
                            "heldMs=$heldMsSinceBuy " +
                            "action=hold_winner_never_scratch_at_breakeven_or_profit",
                    )
                } catch (_: Throwable) {}
                return Verdict.HOLD_WINNER
            }
            // Guard C — bump refresh attempts and require budget
            val attempts = refreshAttempts.compute(latchKey) { _, cur -> (cur ?: 0) + 1 }!!
            if (attempts < REFRESH_BUDGET) {
                holdRefreshBudget.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("PAPER_STALE_REFRESH_BUDGET_HOLD_6829")
                } catch (_: Throwable) {}
                return Verdict.HOLD_REFRESH_BUDGET
            }
            // All three guards cleared — scratch is permitted, but
            // stamp non-trainable so the finalized envelope excludes it.
            scratchAllowed.incrementAndGet()
            if (positionId.isNotBlank()) {
                nonTrainableClosures[positionId] = true
                nonTrainableStamps.incrementAndGet()
            }
            try {
                PipelineHealthCollector.labelInc("PAPER_STALE_SCRATCH_ALLOWED_6829")
                PipelineHealthCollector.labelInc("PAPER_STALE_SCRATCH_NON_TRAINABLE_6829")
                ForensicLogger.lifecycle(
                    "PAPER_STALE_SCRATCH_ALLOWED_6829",
                    "latchKey=${latchKey.take(40)} positionId=${positionId.take(24)} " +
                        "lastPnlPct=${"%.1f".format(lastKnownPnlPct)} " +
                        "refreshAttempts=$attempts action=scratch_permitted_non_trainable",
                )
            } catch (_: Throwable) {}
            Verdict.SCRATCH_ALLOWED
        } catch (_: Throwable) { Verdict.HOLD_REFRESH_BUDGET }
    }

    /** Finalized envelope consults this to override learningEligible. */
    fun isNonTrainable(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        return nonTrainableClosures.containsKey(positionId)
    }

    fun onPositionClosed(positionId: String, latchKey: String) {
        try {
            if (positionId.isNotBlank()) nonTrainableClosures.remove(positionId)
            if (latchKey.isNotBlank()) refreshAttempts.remove(latchKey)
        } catch (_: Throwable) {}
    }

    fun clearRefreshAttempts() { refreshAttempts.clear() }

    fun statusLine(): String =
        "evals=${evaluations.get()} minHold=${holdMinHold.get()} " +
            "winnerProtected=${holdWinner.get()} budgetHold=${holdRefreshBudget.get()} " +
            "scratchAllowed=${scratchAllowed.get()} " +
            "nonTrainableStamps=${nonTrainableStamps.get()}"

    internal fun clearForTest() {
        refreshAttempts.clear(); nonTrainableClosures.clear()
        evaluations.set(0L); holdMinHold.set(0L); holdWinner.set(0L)
        holdRefreshBudget.set(0L); scratchAllowed.set(0L)
        nonTrainableStamps.set(0L)
    }
}
