package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6363 — BRAIN MULTIPLIER FLOOR (WR-restore).
 *
 * OPERATOR DIRECTIVE (V5.0.6362 emergency snapshot):
 *   "was over 80% winrate 3 updates ago..... rolling 50 is now 32%"
 *
 * ROOT CAUSE
 *   The V5.0.6362 snapshot showed MULTIPLIER_ATTRIBUTION_DUST_STACK_4272
 *   firing across STANDARD lane with components including `brain=0.262`
 *   → total `product=0.144` (14.4% of base size). Every STANDARD entry
 *   was being sized to dust, so even winning trades netted pennies while
 *   losses still bled at full slippage/fee cost — asymmetric bleed that
 *   collapses WR toward the random baseline.
 *
 *   `BotBrain.getRiskAdjustedSizeMultiplier(phase, emaFan, source)` per-
 *   context shrinks were operating on few-sample tuples where the
 *   evidence for a 0.26× crush is weak (< 20 trades in that exact bucket).
 *
 * FIX
 *   Floor the brain size multiplier at [FLOOR] (0.50) so no context
 *   crushes the entry below half base. Safety escape: TOXIC/CATASTROPHIC
 *   verdict callers can supply `hardVeto=true` to bypass the floor and
 *   preserve the original crush.
 *
 * NOT DONE HERE
 *   - We do NOT modify [BotBrain] itself — its per-context signal remains
 *     the source of truth for score tilt / other consumers.
 *   - We do NOT change the strict-STOP-LOSS floor (that's downside cap,
 *     not upside sizing).
 *   - We do NOT floor other multipliers (regime, strategyTuner, sourceBrain
 *     etc.) — they each have distinct semantics and legitimate 0.10× floors.
 */
object BrainMultiplierFloor6363 {

    /** No context, no matter how noisy, may crush the entry below this fraction of base. */
    const val FLOOR: Double = 0.50

    private val liftEvents = AtomicLong(0L)
    private val bypassEvents = AtomicLong(0L)

    /**
     * @param rawBrainMult   raw value from `BotBrain.getRiskAdjustedSizeMultiplier`
     * @param hardVeto       set true when caller has a hard-veto verdict (TOXIC/CATASTROPHIC/etc.);
     *                       the floor is skipped so protection semantics survive.
     * @return               the floored multiplier (>= FLOOR when !hardVeto), otherwise raw
     */
    fun apply(rawBrainMult: Double, hardVeto: Boolean = false): Double {
        if (hardVeto) {
            bypassEvents.incrementAndGet()
            return rawBrainMult
        }
        return if (rawBrainMult < FLOOR) {
            liftEvents.incrementAndGet()
            FLOOR
        } else {
            rawBrainMult
        }
    }

    fun liftCount(): Long = liftEvents.get()
    fun bypassCount(): Long = bypassEvents.get()

    /** Test-only. */
    fun reset() {
        liftEvents.set(0L)
        bypassEvents.set(0L)
    }
}
