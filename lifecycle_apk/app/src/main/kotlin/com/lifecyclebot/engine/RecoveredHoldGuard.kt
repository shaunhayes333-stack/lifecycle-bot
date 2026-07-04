package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * RecoveredHoldGuard — V5.0.4104 (Wave D of P0 sell-failure patch)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0 patch §4 + §13:
 *   "Wallet-recovered inventory must not be immediately sold because entry
 *    is unknown. Unknown entry means 'manage cautiously', not 'dump'.
 *
 *    Constants:
 *      RECOVERED_HOLD_GRACE_MS = 15 * 60 * 1000   // 15 minutes
 *
 *    First 15 minutes after recovery: hold unless confirmed rug/dev dump/
 *    liquidity removal/manual close."
 *
 * Sits alongside WalletReconciler.recoverOrphanPosition. When the
 * reconciler discovers a wallet-held mint that wasn't in the canonical
 * store, it calls markRecovered(mint). Sell paths (Executor + ExitWatchdog)
 * check isInHoldGrace(mint) before issuing a non-emergency sell on the
 * mint; if true, the sell is suppressed unless the reason is in the
 * "true emergency" list (rug/honeypot/dev-dump/liquidity-removed/manual).
 *
 * Doctrine: NEVER blocks a confirmed safety-class exit. Only protects
 * against trail/partial/stale-feed/weak-momentum exits while the bot
 * gathers price proof on the recovered position.
 *
 * Cleared on confirmed full exit so re-entry doesn't carry the stale
 * grace window.
 */
object RecoveredHoldGuard {

    private const val TAG = "RecoveredHoldGuard"

    /** Operator spec: 15 minutes. */
    const val RECOVERED_HOLD_GRACE_MS: Long = 15L * 60_000L

    private val recoveredAt = ConcurrentHashMap<String, Long>()

    /** Called by WalletReconciler when an orphan/wallet-recovered mint
     *  is reattached as an OPEN position. Idempotent — re-marking
     *  refreshes the grace window if the bot saw the recovery again. */
    fun markRecovered(mint: String) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        recoveredAt[mint] = now
        try {
            ErrorLogger.info(
                TAG,
                "🆘 RECOVERED_HOLD_GRACE_ACTIVE mint=${mint.take(10)} until=${now + RECOVERED_HOLD_GRACE_MS}"
            )
            ForensicLogger.lifecycle(
                "RECOVERED_HOLD_GRACE_ACTIVE",
                "mint=${mint.take(10)} graceMs=$RECOVERED_HOLD_GRACE_MS"
            )
            PipelineHealthCollector.labelInc("RECOVERED_HOLD_GRACE_ACTIVE")
        } catch (_: Throwable) { }
    }

    /** True if the mint was recovered within the last 15 min. */
    fun isInHoldGrace(mint: String): Boolean {
        val t = recoveredAt[mint] ?: return false
        return (System.currentTimeMillis() - t) < RECOVERED_HOLD_GRACE_MS
    }

    fun graceRemainingMs(mint: String): Long {
        val t = recoveredAt[mint] ?: return 0L
        val rem = (t + RECOVERED_HOLD_GRACE_MS) - System.currentTimeMillis()
        return rem.coerceAtLeast(0L)
    }

    /** Called on confirmed sell finality so the next time this mint is
     *  re-entered the grace window starts fresh from a real buy event. */
    fun clearOnFullExit(mint: String) {
        recoveredAt.remove(mint)
    }

    /** True when the reason represents a confirmed safety emergency that
     *  must always punch through the recovered hold-grace window.
     *  Mirrors the operator §10 canExitBeforeMinHold doctrine. */
    fun isEmergencyExitOverride(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("HONEYPOT") ||
               r.contains("DEV_DUMP") ||
               r.contains("DEV_SELL") ||
               r.contains("LIQUIDITY_REMOVED") ||
               r.contains("RUG") ||
               r.contains("MANUAL") ||
               r.contains("RAPID_CATASTROPHE") ||
               r.contains("WALLET_DRAIN") ||
               r.contains("SHUTDOWN") ||
               r.contains("HARD_FLOOR") ||
               r.contains("WALLET_RECOVERED_ZERO_BASIS_CLEANUP")
    }

    /** Single-call gate consulted by sell paths. Returns true if the sell
     *  should be SUPPRESSED (held) because the recovered hold-grace window
     *  is active and the reason is not a confirmed emergency. */
    fun shouldSuppress(mint: String, reason: String): Boolean {
        if (!isInHoldGrace(mint)) return false
        if (isEmergencyExitOverride(reason)) return false
        return true
    }

    fun reconcileWithHeldMints(heldMints: Set<String>): Int {
        var removed = 0
        for (mint in recoveredAt.keys.toList()) {
            if (!heldMints.contains(mint)) {
                recoveredAt.remove(mint)
                removed++
            }
        }
        if (removed > 0) {
            try { PipelineHealthCollector.labelInc("RECOVERED_HOLD_GHOST_GRACE_CLEARED_4504") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("RECOVERED_HOLD_GHOST_GRACE_CLEARED_4504", "removed=$removed held=${heldMints.size}") } catch (_: Throwable) {}
        }
        return removed
    }

    fun summary(): String {
        val now = System.currentTimeMillis()
        val active = recoveredAt.entries.count { (_, t) -> (now - t) < RECOVERED_HOLD_GRACE_MS }
        return "RecoveredHoldGuard (V5.0.4104): tracked=${recoveredAt.size} activeGrace=$active"
    }
}
