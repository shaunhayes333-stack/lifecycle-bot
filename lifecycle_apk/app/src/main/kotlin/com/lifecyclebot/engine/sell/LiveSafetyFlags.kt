package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus
import com.lifecyclebot.engine.LiveTradeLogStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.495z45 — operator forensics_20260508_143519 spec item G.
 *
 * UI / forensics flags surfaced by the safety subsystem. Each flag is a
 * compute-on-demand counter so the forensics export stays a snapshot of
 * the canonical trackers — no parallel state.
 *
 * Flags:
 *   • WALLET_HELD_BOT_NOT_TRACKING   — wallet has positive token balance,
 *     no matching open position in any tracker.
 *   • TRACKED_BUT_PRICE_ZERO         — tracked position with currentPriceUsd
 *     stuck at 0 for ≥ 1 cycle.
 *   • SELL_VERIFYING_WITH_NO_SIGNATURE — operator's #4 (forensics 0508_143519);
 *     position in SELL_VERIFYING with empty sellSignature.
 *   • BUY_FAILED_BUT_WALLET_BALANCE_EXISTS — operator's #5; bot logged
 *     BUY_FAILED but wallet still holds the target token.
 *   • RECOVERED_WALLET_POSITION       — position freshly created by the
 *     reconciler from a wallet token the bot didn't know about.
 */
object LiveSafetyFlags {

    private const val TAG = "LiveSafetyFlags"

    enum class Flag {
        WALLET_HELD_BOT_NOT_TRACKING,
        TRACKED_BUT_PRICE_ZERO,
        SELL_VERIFYING_WITH_NO_SIGNATURE,
        BUY_FAILED_BUT_WALLET_BALANCE_EXISTS,
        RECOVERED_WALLET_POSITION,
    }

    private val active = ConcurrentHashMap<String, MutableSet<Flag>>()
    private val totalRaised = AtomicInteger(0)

    /** Mark a flag for a mint. Idempotent. */
    fun raise(mint: String, flag: Flag, note: String = "") {
        if (mint.isBlank()) return
        val set = active.getOrPut(mint) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        if (set.add(flag)) {
            totalRaised.incrementAndGet()
            try {
                LiveTradeLogStore.log(
                    tradeKey = "FLAG_${flag.name}_${mint.take(8)}",
                    mint = mint, symbol = mint.take(6),
                    side = "INFO",
                    phase = LiveTradeLogStore.Phase.WARNING,
                    message = "🚩 ${flag.name} ${if (note.isNotBlank()) " — $note" else ""}",
                    traderTag = "SAFETY_FLAGS",
                )
            } catch (_: Throwable) { /* fail-soft */ }
            ErrorLogger.warn(TAG, "🚩 ${flag.name} mint=${mint.take(8)}… $note")
        }
    }

    fun clear(mint: String, flag: Flag) {
        active[mint]?.remove(flag)
    }

    fun activeCount(flag: Flag): Int =
        active.values.count { it.contains(flag) }

    fun activeFor(mint: String): Set<Flag> = active[mint]?.toSet() ?: emptySet()

    fun totalRaised(): Int = totalRaised.get()

    /**
     * Re-evaluate flags from the canonical trackers. Called by the reconciler
     * after every wallet-snapshot pass.
     */
    fun reevaluate(walletBalances: Map<String, Pair<Double, Int>>) {
        // SELL_VERIFYING_WITH_NO_SIGNATURE
        try {
            for (p in HostWalletTokenTracker.snapshot()) {
                if (p.status == PositionStatus.SELL_VERIFYING && p.sellSignature.isNullOrBlank()) {
                    raise(p.mint, Flag.SELL_VERIFYING_WITH_NO_SIGNATURE,
                        "symbol=${p.symbol ?: p.mint.take(6)} qty=${p.uiAmount}")
                }
            }
        } catch (_: Throwable) { /* fail-soft */ }

        // WALLET_HELD_BOT_NOT_TRACKING + TRACKED_BUT_PRICE_ZERO
        try {
            val tracked = HostWalletTokenTracker.snapshot().associateBy { it.mint }
            for ((mint, pair) in walletBalances) {
                val (uiAmount, _) = pair
                if (uiAmount <= 0.0) continue
                if (mint == "So11111111111111111111111111111111111111112") continue
                if (tracked[mint] == null) {
                    raise(mint, Flag.WALLET_HELD_BOT_NOT_TRACKING,
                        "wallet=$uiAmount tracker=NONE")
                }
            }
            for (p in tracked.values) {
                if (p.status == PositionStatus.OPEN_TRACKING && (p.currentPriceUsd ?: 0.0) <= 0.0) {
                    raise(p.mint, Flag.TRACKED_BUT_PRICE_ZERO,
                        "symbol=${p.symbol ?: p.mint.take(6)} qty=${p.uiAmount}")
                }
            }
        } catch (_: Throwable) { /* fail-soft */ }
    }
}
