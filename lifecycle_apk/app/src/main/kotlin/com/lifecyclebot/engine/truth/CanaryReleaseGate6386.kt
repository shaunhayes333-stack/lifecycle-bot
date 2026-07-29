package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6386 — CANARY RELEASE GATE (Section 13 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "After tests pass, enable live BUY only in CANARY mode:
 *     - maximum order 0.003 to 0.005 SOL,
 *     - maximum one open canary position,
 *     - maximum one BUY per mint,
 *     - minimum confirmation level finalized,
 *     - no unknown decimals,
 *     - no wallet-total fill fallback,
 *     - no estimated proceeds,
 *     - no broadcast accounting,
 *     - no alias merge,
 *     - no phantom quantity healing.
 *    Complete 20 consecutive round trips with:
 *     - 100% matching BUY and SELL signatures,
 *     - 100% raw token deltas,
 *     - 100% lamport deltas,
 *     - zero decimal skew,
 *     - zero cost-basis replacement,
 *     - zero duplicate tickets,
 *     - journal PnL matching wallet delta within fees.
 *    Then complete at least 100 clean finalized live trades before
 *    increasing size."
 */
object CanaryReleaseGate6386 {

    /** Modes progress in one direction: LOCKED → CANARY → PROBATION → FULL. */
    enum class Mode { LOCKED, CANARY, PROBATION, FULL }

    private val mode = AtomicReference(Mode.LOCKED)
    private val consecutiveCleanRoundTrips = AtomicInteger(0)
    private val totalFinalizedTrades = AtomicInteger(0)
    private val currentOpenCanaryCount = AtomicInteger(0)

    // Canary spending window (SOL) — per directive Section 13.
    const val CANARY_MIN_SOL: Double = 0.003
    const val CANARY_MAX_SOL: Double = 0.005

    // Advancement thresholds.
    const val CANARY_ROUND_TRIPS_REQUIRED: Int = 20
    const val PROBATION_FINALIZED_TRADES_REQUIRED: Int = 100

    fun currentMode(): Mode = mode.get()

    fun canAcceptBuy(requestedSol: Double, alreadyOpenForMint: Boolean): Verdict {
        val m = mode.get()
        if (m == Mode.LOCKED) {
            return Verdict(false, "CANARY_LOCKED — repair mode still active", null)
        }
        if (m == Mode.CANARY) {
            if (requestedSol !in CANARY_MIN_SOL..CANARY_MAX_SOL) {
                return Verdict(false, "CANARY_SIZE_OUT_OF_RANGE requested=$requestedSol allowed=[$CANARY_MIN_SOL,$CANARY_MAX_SOL]", m)
            }
            if (alreadyOpenForMint) {
                return Verdict(false, "CANARY_MINT_ALREADY_OPEN", m)
            }
            if (currentOpenCanaryCount.get() >= 1) {
                return Verdict(false, "CANARY_MAX_ONE_OPEN_POSITION", m)
            }
        }
        if (m == Mode.PROBATION) {
            // Probation: same as canary except size ceiling can lift; still one-open-per-mint.
            if (alreadyOpenForMint) return Verdict(false, "PROBATION_MINT_ALREADY_OPEN", m)
        }
        return Verdict(true, "ALLOWED_${m.name}", m)
    }

    /**
     * Called when a canary BUY is accepted. Increments the open counter.
     */
    fun onCanaryBuyAccepted() {
        currentOpenCanaryCount.incrementAndGet()
    }

    /**
     * Called when a canary round trip completes cleanly (matching sigs +
     * deltas + no skew + PnL == wallet delta within fees). Advances the
     * gate if thresholds are met.
     */
    fun onCleanRoundTrip() {
        currentOpenCanaryCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        val newTrips = consecutiveCleanRoundTrips.incrementAndGet()
        totalFinalizedTrades.incrementAndGet()
        val m = mode.get()
        if (m == Mode.CANARY && newTrips >= CANARY_ROUND_TRIPS_REQUIRED) {
            mode.compareAndSet(Mode.CANARY, Mode.PROBATION)
        }
        if (m == Mode.PROBATION && totalFinalizedTrades.get() >= PROBATION_FINALIZED_TRADES_REQUIRED) {
            mode.compareAndSet(Mode.PROBATION, Mode.FULL)
        }
    }

    /**
     * Any invariant failure (decimal skew, cost-basis replacement, duplicate
     * ticket, PnL/wallet-delta mismatch, missing signature) resets the
     * consecutive-clean counter to zero and may demote the gate.
     */
    fun onInvariantFailure(reason: String) {
        consecutiveCleanRoundTrips.set(0)
        // Also decrement open counter for safety.
        currentOpenCanaryCount.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * Bundle 6390 will call this once the operator explicitly promotes
     * the build to canary AFTER the regression tests pass.
     */
    internal fun promoteToCanary() {
        mode.compareAndSet(Mode.LOCKED, Mode.CANARY)
    }

    /** Snapshot for pipeline health / operator dumps. */
    fun snapshot(): String = "mode=${mode.get()} consecCleanRT=${consecutiveCleanRoundTrips.get()} " +
        "totalFinalized=${totalFinalizedTrades.get()} openCanary=${currentOpenCanaryCount.get()}"

    internal fun resetAllForTest() {
        mode.set(Mode.LOCKED)
        consecutiveCleanRoundTrips.set(0)
        totalFinalizedTrades.set(0)
        currentOpenCanaryCount.set(0)
    }

    data class Verdict(val allowed: Boolean, val reason: String, val mode: Mode?)
}
