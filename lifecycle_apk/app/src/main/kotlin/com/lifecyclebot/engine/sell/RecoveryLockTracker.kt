package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z39 — Recovery position lock.
 *
 * Operator spec item 5: "Do not allow recovery re-registration to
 * trigger an immediate sell unless token balance is confirmed on-chain
 * AND original buy tx cost basis is loaded AND actual entry token
 * quantity is known AND live executable quote proves profit after
 * fees/slippage."
 *
 * Treasury / recovery flows MUST call:
 *   1. lock(mint) — at re-register time.
 *   2. tryUnlockWithChainBasis(mint, ...) — once chain basis is loaded.
 *
 * Sell paths MUST call:
 *   - isLockedAwaitingChainBasis(mint) → if true, skip the sell tick.
 */
object RecoveryLockTracker {

    enum class State { LOCKED_AWAITING_CHAIN_BASIS, UNLOCKED }

    private data class Entry(
        val state: State,
        val lockedAtMs: Long,
        val reason: String,
    )

    private val map = ConcurrentHashMap<String, Entry>()

    fun lock(mint: String, symbol: String, reason: String = "TREASURY_RECOVERY") {
        map[mint] = Entry(State.LOCKED_AWAITING_CHAIN_BASIS, System.currentTimeMillis(), reason)
        ErrorLogger.info("RecoveryLockTracker",
            "🔒 RECOVERY_POSITION_LOCKED_UNTIL_CHAIN_BASIS_CONFIRMED $symbol mint=${mint.take(8)}… reason=$reason")
    }

    fun isLockedAwaitingChainBasis(mint: String): Boolean =
        map[mint]?.state == State.LOCKED_AWAITING_CHAIN_BASIS

    /** Caller proves it has loaded chain basis (entry SOL spent, entry
     *  token raw, current wallet token balance) — and proves the live
     *  executable quote shows profit after fees/slippage. */
    fun tryUnlockWithChainBasis(
        mint: String,
        entrySolSpent: Double,
        entryTokenRaw: java.math.BigInteger,
        currentWalletTokenRaw: java.math.BigInteger,
        liveQuoteSolOut: Double,
        feesSol: Double,
    ): Boolean {
        if (entrySolSpent <= 0.0 ||
            entryTokenRaw.signum() <= 0 ||
            currentWalletTokenRaw.signum() <= 0 ||
            liveQuoteSolOut <= 0.0) return false
        val ratio = currentWalletTokenRaw.toBigDecimal()
            .divide(entryTokenRaw.toBigDecimal(), 18, java.math.RoundingMode.HALF_UP)
            .toDouble()
        val proportionalCost = entrySolSpent * ratio
        val netAfterFees = liveQuoteSolOut - feesSol
        val isProfit = netAfterFees > proportionalCost
        if (!isProfit) return false
        map.remove(mint)
        ErrorLogger.info("RecoveryLockTracker",
            "🔓 RECOVERY_UNLOCKED mint=${mint.take(8)}… cost=${"%.6f".format(proportionalCost)} quote=${"%.6f".format(liveQuoteSolOut)} fees=${"%.6f".format(feesSol)}")
        return true
    }

    /** Forced unlock — operator override + V5.9.1530 risk-exit/stale punch-through. */
    fun forceUnlock(mint: String) { map.remove(mint) }

    /** V5.9.1530 — age of the lock in ms, or 0 if not locked. Used by the executor
     *  to punch through a recovery lock that has clearly failed to load chain basis
     *  so a position is never trapped un-sellable forever. */
    fun lockAgeMs(mint: String): Long {
        val e = map[mint] ?: return 0L
        return System.currentTimeMillis() - e.lockedAtMs
    }

    fun lockedCount(): Int = map.values.count { it.state == State.LOCKED_AWAITING_CHAIN_BASIS }
}
