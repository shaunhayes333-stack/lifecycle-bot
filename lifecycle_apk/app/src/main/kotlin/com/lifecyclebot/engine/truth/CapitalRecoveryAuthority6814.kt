package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6814 §CAPITAL_RECOVERY — operator diagnosis Feb 2026:
 *   "92 BUY vs 39 SELL, 53 open, 0 partial sells, cash=0.0215 SOL,
 *    open MV=16.58 SOL. When cash starves, ordinary new BUY intents
 *    must stop and the bot must prioritise exit evaluation to
 *    recover capital velocity."
 *
 * This authority provides a single global read that BUY-consuming
 * paths (FDG, ExecutableOpenGate, sizers) can check WITHOUT mutating
 * their existing control flow. Entering CAPITAL_RECOVERY simply
 * flips `isActive()` to true; callers reject BUY intents with the
 * `CAPITAL_RECOVERY_6814` blockReason using their normal blockReason
 * path (no early return / no hand-built FinalDecision).
 *
 * Entry conditions (any):
 *   • availableCash < max(0.05 SOL, equity * 0.05)
 *   • openPositions >= 75% of canonical slot capacity
 *   • buysLastWindow > sellsLastWindow * 1.35
 *
 * Exit conditions (ALL required — hysteresis):
 *   • cash >= 15% of equity
 *   • openPositions <= 55% of slot capacity
 *   • buy/sell rolling ratio <= 1.20
 *
 * Exits, TP, SL, catastrophic paths remain unaffected — this only
 * gates NEW ordinary entries. Learning/shadow evaluation continues.
 */
object CapitalRecoveryAuthority6814 {

    private const val CASH_ABS_FLOOR_SOL = 0.05
    private const val CASH_EQUITY_ENTRY_PCT = 0.05
    private const val CASH_EQUITY_EXIT_PCT = 0.15
    private const val OPEN_ENTRY_PCT = 0.75
    private const val OPEN_EXIT_PCT = 0.55
    private const val BUY_SELL_ENTRY_RATIO = 1.35
    private const val BUY_SELL_EXIT_RATIO = 1.20

    private val active = AtomicBoolean(false)
    private val entries = AtomicLong(0L)
    private val exits = AtomicLong(0L)
    private val lastEvalMs = AtomicLong(0L)
    private val lastReason = java.util.concurrent.atomic.AtomicReference("")

    fun isActive(): Boolean = active.get()
    fun lastReason(): String = lastReason.get()

    /**
     * Called by BotService's periodic loop with the current pipeline
     * snapshot. Idempotent, cheap: pure arithmetic + one atomic.
     */
    fun evaluate(
        availableCashSol: Double,
        equitySol: Double,
        openPositions: Int,
        slotCapacity: Int,
        buysLastWindow: Long,
        sellsLastWindow: Long,
    ): Boolean {
        return try {
            lastEvalMs.set(System.currentTimeMillis())
            val cashFloor = maxOf(CASH_ABS_FLOOR_SOL, equitySol * CASH_EQUITY_ENTRY_PCT)
            val slotAdmitCap = if (slotCapacity > 0) slotCapacity else 1
            val buySellRatio = if (sellsLastWindow > 0L)
                buysLastWindow.toDouble() / sellsLastWindow.toDouble()
            else if (buysLastWindow > 0L) Double.POSITIVE_INFINITY else 0.0

            val enterReasons = mutableListOf<String>()
            if (availableCashSol < cashFloor) enterReasons += "cash_starved"
            if (openPositions.toDouble() >= slotAdmitCap * OPEN_ENTRY_PCT) enterReasons += "slot_saturated"
            if (buySellRatio > BUY_SELL_ENTRY_RATIO) enterReasons += "buy_sell_imbalance"

            val exitCashOk = availableCashSol >= equitySol * CASH_EQUITY_EXIT_PCT
            val exitSlotOk = openPositions.toDouble() <= slotAdmitCap * OPEN_EXIT_PCT
            val exitRatioOk = buySellRatio <= BUY_SELL_EXIT_RATIO

            val currentlyActive = active.get()
            val nextActive = when {
                enterReasons.isNotEmpty() -> true
                currentlyActive -> !(exitCashOk && exitSlotOk && exitRatioOk)
                else -> false
            }
            if (nextActive != currentlyActive) {
                active.set(nextActive)
                if (nextActive) {
                    entries.incrementAndGet()
                    lastReason.set(enterReasons.joinToString(","))
                    try {
                        PipelineHealthCollector.labelInc("CAPITAL_RECOVERY_ENTERED_6814")
                        PipelineHealthCollector.labelInc(
                            "CAPITAL_RECOVERY_ENTERED_6814_${enterReasons.joinToString("_").uppercase()}"
                        )
                    } catch (_: Throwable) {}
                } else {
                    exits.incrementAndGet()
                    lastReason.set("recovered")
                    try {
                        PipelineHealthCollector.labelInc("CAPITAL_RECOVERY_EXITED_6814")
                    } catch (_: Throwable) {}
                }
            }
            nextActive
        } catch (_: Throwable) { active.get() }
    }

    fun statusLine(): String =
        "active=${active.get()} entries=${entries.get()} exits=${exits.get()} " +
            "lastReason=${lastReason.get()} lastEvalAgeMs=${System.currentTimeMillis() - lastEvalMs.get()}"

    /** Test-only reset. */
    internal fun clearForTest() {
        active.set(false); entries.set(0L); exits.set(0L)
        lastEvalMs.set(0L); lastReason.set("")
    }
}
