package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.105: LIVE-MODE SAFETY CIRCUIT BREAKER
 *
 * Two hard halts that stop the bot from bleeding real SOL:
 *
 *   1) STARTUP FLOOR — if the live wallet starts below `MIN_LIVE_SOL`
 *      (currently 0.1 SOL), refuse to accept any live trades at all.
 *      Every Jupiter swap costs ~0.0005 SOL in fees and rent; dust
 *      wallets just bleed fees on failed/clamped trades.
 *
 *      V5.9.283 FIX: If the breaker tripped on STARTUP_FLOOR only (not
 *      SESSION_DRAWDOWN), calling updateBalance() with a current wallet
 *      balance >= MIN_LIVE_SOL will auto-untrip it. This handles the case
 *      where the bot started with a dust wallet (e.g. 0.004 SOL from fees)
 *      but received a deposit during the session — the bot would previously
 *      stay locked all session until a full restart even though the wallet
 *      was now well above the floor.
 *
 *   2) SESSION DRAWDOWN — if cumulative live PnL since this session
 *      started drops by more than `MAX_SESSION_DRAWDOWN_PCT` (default
 *      10%), trip the breaker and reject all further live executions
 *      until `reset()` is called (e.g. next startBot()).
 *
 * Paper mode is never affected. Every trip also logs a human-readable
 * reason so the UI can surface it.
 */
object LiveSafetyCircuitBreaker {

    private const val TAG = "LiveSafetyCB"

    // Tunables — kept conservative to match the user's actual funding level.
    const val MIN_LIVE_SOL = 0.10
    const val MAX_SESSION_DRAWDOWN_PCT = 10.0

    // State
    private val tripped = AtomicBoolean(false)
    @Volatile private var trippedReason: String = ""
    @Volatile private var trippedByStartupFloor: Boolean = false   // V5.9.283
    @Volatile private var sessionStartSol: Double = 0.0
    @Volatile private var sessionStartedAt: Long = 0L
    private val cumulativePnlMicroSol = AtomicLong(0)  // micro-SOL for precision

    fun isTripped(): Boolean = tripped.get()
    fun trippedReason(): String = trippedReason
    fun sessionStartSol(): Double = sessionStartSol
    fun sessionStartedAt(): Long = sessionStartedAt
    fun sessionPnlSol(): Double = cumulativePnlMicroSol.get() / 1_000_000.0

    /**
     * Call at the start of every live session (after wallet connects).
     * If initialBalanceSol is below MIN_LIVE_SOL, the breaker trips
     * immediately and reject() will refuse all live trades.
     */
    fun beginSession(initialBalanceSol: Double) {
        sessionStartSol = initialBalanceSol
        sessionStartedAt = System.currentTimeMillis()
        cumulativePnlMicroSol.set(0)
        trippedByStartupFloor = false

        if (initialBalanceSol < MIN_LIVE_SOL) {
            val reason = "STARTUP_FLOOR: wallet=${"%.4f".format(initialBalanceSol)} SOL < ${MIN_LIVE_SOL} SOL minimum"
            tripped.set(true)
            trippedReason = reason
            trippedByStartupFloor = true
            ErrorLogger.warn(TAG, "🚨 $reason — live trades disabled this session")
        } else {
            tripped.set(false)
            trippedReason = ""
            ErrorLogger.info(TAG, "Session begin | start=${"%.4f".format(initialBalanceSol)} SOL | drawdown halt @ ${MAX_SESSION_DRAWDOWN_PCT}%")
        }
    }

    /**
     * V5.9.283: Update current wallet balance — auto-untrip STARTUP_FLOOR
     * breakers when the wallet has grown above the minimum floor.
     * Called by BotService's periodic balance refresh cycle.
     * SESSION_DRAWDOWN trips are never auto-cleared here.
     */
    fun updateBalance(currentBalanceSol: Double) {
        if (tripped.get() && trippedByStartupFloor && currentBalanceSol >= MIN_LIVE_SOL) {
            ErrorLogger.info(TAG, "✅ STARTUP_FLOOR auto-cleared: wallet now ${"%.4f".format(currentBalanceSol)} SOL >= $MIN_LIVE_SOL SOL — live trading re-enabled")
            tripped.set(false)
            trippedReason = ""
            trippedByStartupFloor = false
            // Reseed the session start balance so drawdown calc is correct
            sessionStartSol = currentBalanceSol
            sessionStartedAt = System.currentTimeMillis()
            cumulativePnlMicroSol.set(0)
        }
    }

    /**
     * Record a closed-trade PnL in SOL (positive = win, negative = loss).
     * Trips the breaker if session drawdown exceeds MAX_SESSION_DRAWDOWN_PCT.
     */
    fun recordTradeResult(pnlSol: Double) {
        if (pnlSol == 0.0 || sessionStartSol <= 0.0) return
        cumulativePnlMicroSol.addAndGet((pnlSol * 1_000_000.0).toLong())

        val pnl = sessionPnlSol()
        val drawdownPct = if (sessionStartSol > 0.0) (-pnl / sessionStartSol) * 100.0 else 0.0
        if (!tripped.get() && drawdownPct >= MAX_SESSION_DRAWDOWN_PCT) {
            val reason = "SESSION_DRAWDOWN: PnL=${"%.4f".format(pnl)} SOL (${"%.1f".format(-drawdownPct)}%) exceeds ${MAX_SESSION_DRAWDOWN_PCT}% halt"
            tripped.set(true)
            trippedReason = reason
            trippedByStartupFloor = false
            ErrorLogger.warn(TAG, "🚨 $reason — live trades disabled until reset")
        }
    }

    /**
     * Hard reset — called on fresh startBot() so the next session starts
     * clean. Does NOT untrip if already tripped in the current session.
     */
    fun reset() {
        tripped.set(false)
        trippedReason = ""
        trippedByStartupFloor = false
        sessionStartSol = 0.0
        sessionStartedAt = 0L
        cumulativePnlMicroSol.set(0)
        ErrorLogger.info(TAG, "Circuit breaker reset")
    }

    /** UI / log snapshot. */
    fun snapshot(): Map<String, Any> = mapOf(
        "tripped" to tripped.get(),
        "reason" to trippedReason,
        "sessionStartSol" to sessionStartSol,
        "sessionPnlSol" to sessionPnlSol(),
        "sessionStartedAt" to sessionStartedAt,
        "minLiveSol" to MIN_LIVE_SOL,
        "maxDrawdownPct" to MAX_SESSION_DRAWDOWN_PCT,
    )
}
