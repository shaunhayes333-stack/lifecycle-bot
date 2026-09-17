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
    /**
     * V5.0.6963 §THE_SAME_TOKEN_LIST_BUG, A_SECOND_TIME, AND_HERE_IT_BLOCKS_EXITS.
     *
     * This list decides which exits may punch through a 15-MINUTE hold-grace
     * window. Anything it misses is SUPPRESSED for the full 15 minutes.
     * Enumerated against the exit reasons riskCheck actually emits, the old list
     * missed — even taking the union with the caller's own isUnconditionalSafety
     * list in Executor.blockIfSellInFlight:
     *
     *     trailing_stop                liquidity_collapse
     *     whale_dump                   velocity_dump
     *     crosstalk_coordinated_dump   accelerating_loss
     *     reflex_abort                 gemini_immediate_exit
     *     catastrophic_gap_guard_*     PROTECTIVE_EXIT_*_6450
     *
     * So on a recovered position, a whale dump, a velocity dump, a liquidity
     * collapse, a catastrophic gap or a reflex abort could not sell for fifteen
     * minutes. On a memecoin that is not a delay, it is the entire loss.
     *
     * The V5.0.4151 note at the call site records that this exact class of bug
     * already bit once here — "creating -98% MOONSHOT stop rows" — and the fix
     * then was to add a second keyword list at the CALL SITE rather than repair
     * this one. Two lists, both incomplete, neither aware of the other.
     *
     * "CATASTROPHE" is the sharpest miss and it is the SECOND time this session:
     * the reason string is catastrophic_gap_guard_*, and "CATASTROPHIC" does not
     * contain "CATASTROPHE". V5.0.6951 found the identical defect in
     * SellAmountAuthority's EMERGENCY_REASON_TOKENS. The same wrong word, in two
     * unrelated files, each gating a different safety path.
     *
     * Repaired HERE, in the authority, rather than at the call site, so every
     * caller of shouldSuppress gets the corrected set rather than each growing
     * its own copy.
     */
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
               // V5.0.6963 — the vocabulary riskCheck actually emits.
               r.contains("DUMP") ||            // whale_dump, velocity_dump, crosstalk_coordinated_dump
               r.contains("COLLAPSE") ||        // liquidity_collapse
               r.contains("DRAIN") ||           // liquidity_drain, reflex_liq_drain
               r.contains("CATASTROPH") ||      // catastrophic_gap_guard_* (CATASTROPHE never matched it)
               r.contains("GAP_GUARD") ||
               r.contains("ACCELERATING_LOSS") ||
               r.contains("REFLEX") ||          // reflex_abort
               r.contains("IMMEDIATE_EXIT") ||  // gemini_immediate_exit
               r.contains("PROTECTIVE_EXIT") || // PROTECTIVE_EXIT_*_6450
               r.contains("STOP_LOSS") ||
               r.contains("TRAILING_STOP") ||
               r.contains("STRICT_SL") ||
               r.contains("BACKSTOP") ||
               r.contains("EMERGENCY")
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
