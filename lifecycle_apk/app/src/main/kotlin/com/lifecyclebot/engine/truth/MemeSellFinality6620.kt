package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6620 §MEME_SOURCE_LEVEL_EXECUTION_PROVENANCE §12 (operator directive
 * Feb 2026):
 *
 *   "Moonshot contains a proven source defect: it calls requestSell()
 *    and then immediately performs specialist close and exposure
 *    release. Change EVERY MemeTrader exit to wait for
 *    SellResult.CONFIRMED/PAPER_CONFIRMED before finalizing. Never
 *    finalize from an attempted sell."
 *
 * FORENSIC EVIDENCE (fresh install V5.0.6619):
 *   46 open positions stuck / not finalizing / +$21 SOL journal raw
 *   parity but hero showed -$6,827.45 unrealized loss. Exits fired
 *   but state release happened BEFORE the sell was actually confirmed
 *   by the executor, leaving the specialist registry / exposure /
 *   learning-arm in a torn state and the canonical position still
 *   OPEN. Subsequent tick tried to re-exit → re-torn state. Repeat.
 *
 * This gate is a source-level seal every MemeTrader exit caller MUST
 * route through:
 *
 *   MemeSellFinality6620.awaitConfirmationOrKeepOpen(
 *       lane           = "MOONSHOT",
 *       positionId     = pos.id,
 *       sellResult     = executor.requestSell(...),
 *   ) { confirmed ->
 *       // Only reached when confirmed == CONFIRMED / PAPER_CONFIRMED.
 *       // Callers MUST place all specialist-registry closure /
 *       // exposure release / learning arming inside this block.
 *       canonicalFinalizer.finalize(...)
 *   }
 *
 * When the sell is NOT confirmed (PENDING / FAILED / REJECTED), the
 * gate emits counters and RETURNS WITHOUT invoking onConfirmed. The
 * canonical position stays OPEN, the specialist registry stays OPEN,
 * exposure stays occupied, and no terminal learning is delivered —
 * exactly the operator's mandated invariant.
 *
 * Broad rollout across every specialist requestSell caller is Slice 2
 * of the operator's directive; this module is the receiver so Slice 2
 * is a mechanical rewrite of exit sites to route through it.
 */
object MemeSellFinality6620 {

    /**
     * The four sell-outcome semantics the operator's directive
     * distinguishes. Callers pass whatever their executor returned;
     * this gate normalizes it into confirmed / not-confirmed.
     */
    enum class Outcome { CONFIRMED, PAPER_CONFIRMED, PENDING, FAILED, REJECTED, UNKNOWN }

    private val confirmed = AtomicLong(0L)
    private val paperConfirmed = AtomicLong(0L)
    private val notConfirmedKeptOpen = AtomicLong(0L)
    private val stateReleasedWithoutConfirmationAttempts = AtomicLong(0L)

    /**
     * Route every MemeTrader exit through here. onConfirmed is the
     * ONLY branch that may run canonical finalization / specialist
     * state release / exposure clear / learning arm.
     *
     * Returns true when confirmed (onConfirmed ran), false when the
     * position was kept open.
     */
    inline fun awaitConfirmationOrKeepOpen(
        lane: String,
        positionId: String,
        sellOutcome: Outcome,
        note: String = "",
        onConfirmed: () -> Unit,
    ): Boolean {
        if (sellOutcome == Outcome.CONFIRMED || sellOutcome == Outcome.PAPER_CONFIRMED) {
            recordConfirmed6620(lane, positionId, sellOutcome, note)
            try { onConfirmed() } catch (_: Throwable) {
                // Even if the caller's finalization throws, we've
                // already counted the confirmation. Don't double-count.
            }
            return true
        }
        recordKeptOpen6620(lane, positionId, sellOutcome, note)
        return false
    }

    /** Direct confirmation counter — for callers that don't want the inline block. */
    fun recordConfirmed6620(lane: String, positionId: String, outcome: Outcome, note: String) {
        when (outcome) {
            Outcome.CONFIRMED       -> confirmed.incrementAndGet()
            Outcome.PAPER_CONFIRMED -> paperConfirmed.incrementAndGet()
            else -> {}
        }
        try {
            PipelineHealthCollector.labelInc("MEME_SELL_CONFIRMED_6620")
            PipelineHealthCollector.labelInc("MEME_SELL_CONFIRMED_${lane.uppercase()}_6620")
        } catch (_: Throwable) {}
    }

    /** Kept-open counter — position remains OPEN because sell did not confirm. */
    fun recordKeptOpen6620(lane: String, positionId: String, outcome: Outcome, note: String) {
        notConfirmedKeptOpen.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MEME_SELL_KEPT_OPEN_${outcome.name}_6620")
            PipelineHealthCollector.labelInc("MEME_SELL_KEPT_OPEN_${lane.uppercase()}_6620")
            ForensicLogger.lifecycle(
                "MEME_SELL_KEPT_OPEN_6620",
                "lane=$lane positionId=${positionId.take(18)} outcome=$outcome " +
                    "note=${note.take(60)} action=position_stays_open_registry_preserved",
            )
        } catch (_: Throwable) {}
    }

    /**
     * Diagnostic counter for the operator to prove the "release before
     * confirmation" defect no longer happens. Existing exit paths that
     * bypass awaitConfirmationOrKeepOpen call this so the operator can
     * see remaining bypass sites in the pipeline dump. Steady-state
     * target = 0 after Slice 2 rollout.
     */
    fun recordStateReleasedWithoutConfirmation6620(lane: String, positionId: String, source: String) {
        stateReleasedWithoutConfirmationAttempts.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MEME_SELL_STATE_RELEASED_WITHOUT_CONFIRMATION_6620")
            PipelineHealthCollector.labelInc("MEME_SELL_STATE_RELEASED_WITHOUT_CONFIRMATION_${lane.uppercase()}_6620")
            ForensicLogger.lifecycle(
                "MEME_SELL_STATE_RELEASED_WITHOUT_CONFIRMATION_6620",
                "lane=$lane positionId=${positionId.take(18)} source=${source.take(60)}",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "confirmed=${confirmed.get()} paperConfirmed=${paperConfirmed.get()} " +
            "keptOpen=${notConfirmedKeptOpen.get()} " +
            "bypassAttempts=${stateReleasedWithoutConfirmationAttempts.get()}"

    internal fun resetForTest() {
        confirmed.set(0L); paperConfirmed.set(0L)
        notConfirmedKeptOpen.set(0L); stateReleasedWithoutConfirmationAttempts.set(0L)
    }
}
