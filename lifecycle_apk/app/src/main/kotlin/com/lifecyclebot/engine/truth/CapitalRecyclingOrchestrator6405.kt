package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger

/**
 * V5.0.6405 §16 — CAPITAL RECYCLING ORCHESTRATOR.
 *
 * Ties compounding, lane profile, entry timing, and the raw-qty
 * ledger together into a single deterministic decision that answers:
 *
 *   "Given a closed position lifetime, what is the recommended
 *    action for the freed capital?"
 *
 * Result codes
 * ────────────
 *   • REINVEST_SAME_LANE            — capital compounds into next entry on same lane
 *   • REINVEST_ROTATE_TO_STABLE     — profits exceed maxLamports; park in treasury
 *   • HOLD_COOLDOWN                 — lane took a loss; cool the lane for a bit
 *   • BLOCKED_INTEGRITY             — invariants failed; halt entries until operator clears
 */
object CapitalRecyclingOrchestrator6405 {

    enum class Result {
        REINVEST_SAME_LANE, REINVEST_ROTATE_TO_STABLE, HOLD_COOLDOWN, BLOCKED_INTEGRITY,
    }

    data class Decision(val result: Result, val nextBaseLamports: BigInteger, val reason: String)

    data class Input(
        val lane: String,
        val closedRealisedLamports: BigInteger,
        val currentBaseLamports: BigInteger,
        val maxLamports: BigInteger,
        val compoundFraction: Double,
        val integrityPass: Boolean,
        val laneCoolDownAfterLossMs: Long,
        val mintForCooldown: String,
    )

    fun decide(i: Input): Decision {
        if (!i.integrityPass) {
            emit("BLOCKED_INTEGRITY", i.lane, BigInteger.ZERO)
            return Decision(
                Result.BLOCKED_INTEGRITY, i.currentBaseLamports,
                "PORTFOLIO_INVARIANT_VIOLATION",
            )
        }

        if (i.closedRealisedLamports.signum() < 0) {
            GlobalEntryPolicy6405.setCooldownMs(i.mintForCooldown, i.laneCoolDownAfterLossMs)
            emit("HOLD_COOLDOWN", i.lane, i.currentBaseLamports)
            return Decision(
                Result.HOLD_COOLDOWN, i.currentBaseLamports,
                "REALISED_LOSS_COOLDOWN_MS=${i.laneCoolDownAfterLossMs}",
            )
        }

        val nextBase = CompoundingEngine6405.nextBase(
            CompoundingEngine6405.Input(
                currentBaseLamports = i.currentBaseLamports,
                realisedLamports = i.closedRealisedLamports,
                compoundFraction = i.compoundFraction,
                maxLamports = i.maxLamports,
            ),
        )
        val hitCap = nextBase >= i.maxLamports
        val result = if (hitCap) Result.REINVEST_ROTATE_TO_STABLE else Result.REINVEST_SAME_LANE
        emit(result.name, i.lane, nextBase)
        return Decision(
            result, nextBase,
            if (hitCap) "MAX_REACHED_ROTATE" else "COMPOUNDED_INTO_LANE",
        )
    }

    private fun emit(result: String, lane: String, nextBase: BigInteger) {
        try {
            ForensicLogger.lifecycle(
                "CAPITAL_RECYCLING_${result}_6405",
                "lane=$lane nextBase=$nextBase",
            )
            PipelineHealthCollector.labelInc("CAPITAL_RECYCLING_${result}_6405")
        } catch (_: Throwable) {}
    }
}
