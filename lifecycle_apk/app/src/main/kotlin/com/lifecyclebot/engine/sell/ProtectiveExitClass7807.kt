package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * V5.0.7807 — PROTECTIVE EXIT CLASS (operator-approved exit policy B1-B4).
 *
 * 5.0.7805 measured catastrophic stop trigger -> broadcast at ~34 s. The
 * stops themselves sat where they should; the time was spent in retry and
 * coalescing windows (30 s risk-clock redispatch, 60 s off-loop coalesce,
 * up to 60 s CloseLease backoff inherited from an earlier non-emergency
 * attempt, a 30 s Jupiter-dead defer) and in hold/defer gates that read
 * reason strings with three different keyword lists.
 *
 * This object is the ONE place that answers, from the exit reason alone:
 *   - which priority an exit carries (B2 hierarchy, [Priority]);
 *   - whether it is in the shared emergency bypass class (B3, [isEmergency]);
 *   - whether it may override a Moonshot hold ([overridesMoonshotHold]);
 *   - the retry cadence (B1, [retryDelayMs]) and the slippage / route
 *     escalation ladders (B4, [slippageLadderBps], [shouldEscalateRoute]).
 *
 * It reads nothing but the reason string and an attempt number: no learner,
 * council, oracle, lane-performance or advisory state is consulted, so an
 * emergency exit's bypass can never depend on a learned opinion
 * (docs/EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md L35; Field Manual L248, L311).
 *
 * Nothing here moves a stop. Thresholds stay where they are; this only
 * decides how fast and how hard an exit that already fired is executed.
 */
object ProtectiveExitClass7807 {

    /**
     * B2 — the single explicit ordering. Lower [rank] wins. Ranks 1-3 are the
     * emergency bypass class. Ranks 5 and 6 are HOLD reasons (a lane's hold
     * preference, a style minimum hold) and never exit reasons; they exist so
     * the arbiter can compare an exit against the hold that would delay it.
     */
    enum class Priority(val rank: Int) {
        CAPITAL_PRESERVATION(1),
        STRUCTURAL_EMERGENCY(2),
        HARD_SL(3),
        PROFIT_PROTECTION(4),
        STRATEGY_HOLD(5),
        MIN_HOLD(6),
        NONE(7),
    }

    /** B4 — escalation rungs after the route's normal emergency slippage. */
    private const val SLIP_RUNG_2_BPS_7807 = 2_500
    private const val SLIP_RUNG_3_BPS_7807 = 3_500
    /** B4 — emergency slippage cap (50%). */
    const val EMERGENCY_SLIP_CAP_BPS_7807 = 5_000
    /** B4 — start rung when the route has no emergency slippage of its own. */
    private const val DEFAULT_EMERGENCY_START_BPS_7807 = 1_500
    /** B1 — normal (non-emergency) retry / coalesce windows are capped here. */
    private const val NORMAL_RETRY_CAP_MS_7807 = 15_000L
    /** B1 — emergency retry cadence: 2 s, 3 s, 5 s, 8 s, then every 10 s. */
    private val EMERGENCY_RETRY_LADDER_MS_7807 = longArrayOf(2_000L, 3_000L, 5_000L, 8_000L, 10_000L)

    /**
     * B3 — map an exit reason into the hierarchy. Every existing emergency
     * reason string in the codebase is covered here, by family:
     *
     *  1 CAPITAL_PRESERVATION  RAPID_CATASTROPHE_STOP, CATASTROPHIC_HARD_BACKSTOP_*,
     *                          PROTECTIVE_EXIT_CATASTROPHE_6450_*, DEEP_CATASTROPHE_NET,
     *                          ZOMBIE_CATASTROPHE_*, TICK_CATASTROPHIC_CONFIRMED_*,
     *                          catastrophic_gap_guard_*, WALLET_DRAIN, KILL_SWITCH,
     *                          EMERGENCY_LIQUIDATE, MANUAL_EMERGENCY
     *  2 STRUCTURAL_EMERGENCY  rug family (RUG_DRAIN, HARD_RUG, CONFIRMED_RUG,
     *                          RUGCHECK_CONFIRMED, learned_rug_pattern,
     *                          STEALTH_MINT_RUG_CATASTROPHIC, THIN_LIQ_EARLY_RUG_BACKSTOP_*),
     *                          HONEYPOT, CANNOT_SELL, liquidity collapse
     *                          (liquidity_collapse, liquidity_drain, LIQ_DRAIN,
     *                          reflex_liq_drain, LIQUIDITY_REMOVED, LP_PULL/LP_REMOVED,
     *                          NO_LIQUIDITY_EXIT), dev dump (dev_dump, DEV_SELL),
     *                          freeze / mint authority threat (FREEZE_AUTH*,
     *                          MINT_AUTH* unless *_DISABLED), stale-but-dangerous
     *                          (STALE_PRICE_RUG_ESCAPE, STALE_LIVE_PRICE_RUG_ESCAPE,
     *                          STALE_QUOTE_EMERGENCY_*_BACKSTOP)
     *  3 HARD_SL               STOP, STRICT_SL_*, stop_loss, *_STOP_LOSS, HARD_STOP,
     *                          HARD_FLOOR (RAPID_HARD_FLOOR_STOP, UNIVERSAL_HARD_FLOOR_SL),
     *                          LANE_HARD_15PCT_SL_*, PROTECTIVE_EXIT_STOP_LOSS_6450_*,
     *                          STRUCTURE_STOP_7739_* (a breached plan invalidation),
     *                          MODE_EXIT_STOP_7744_*
     *  4 PROFIT_PROTECTION     trail / profit-lock / breakeven / peak giveback / take-profit
     *
     * Trailing and profit stops are checked BEFORE the hard-SL family so a
     * TRAIL_STOP or PROFIT_LOCK_STOP is never promoted into the bypass class.
     */
    fun of(reason: String?): Priority {
        val r = reason.orEmpty().uppercase()
        if (r.isBlank()) return Priority.NONE
        if (isCapitalPreservation(r)) return Priority.CAPITAL_PRESERVATION
        if (isStructural(r)) return Priority.STRUCTURAL_EMERGENCY
        if (isProfitProtection(r)) return Priority.PROFIT_PROTECTION
        if (isHardSl(r)) return Priority.HARD_SL
        return Priority.NONE
    }

    private fun isCapitalPreservation(r: String): Boolean =
        r.contains("CATASTROPH") || r.contains("GAP_GUARD") || r.contains("WALLET_DRAIN") ||
            r.contains("KILL_SWITCH") || r.contains("EMERGENCY_LIQUIDATE") || r.contains("MANUAL_EMERGENCY")

    private fun isStructural(r: String): Boolean {
        if (r.contains("RUG") || r.contains("HONEYPOT") || r.contains("CANNOT_SELL")) return true
        if (r.contains("LIQUIDITY_COLLAPSE") || r.contains("LIQUIDITY_DRAIN") || r.contains("LIQ_DRAIN") ||
            r.contains("LIQUIDITY_REMOVED") || r.contains("LP_PULL") || r.contains("LP_REMOVED") ||
            r.contains("NO_LIQUIDITY_EXIT")
        ) return true
        if (r.contains("DEV_DUMP") || r.contains("DEV_SELL")) return true
        if ((r.contains("FREEZE_AUTH") || r.contains("MINT_AUTH")) && !r.contains("DISABLED")) return true
        if (r.contains("STALE") && (r.contains("EMERGENCY") || r.contains("BACKSTOP"))) return true
        return false
    }

    private fun isProfitProtection(r: String): Boolean =
        r.contains("TRAIL") || r.contains("PROFIT") || r.contains("BREAKEVEN") ||
            r.contains("PEAK_GIVEBACK") || r.contains("DRAWDOWN_FROM_PEAK") || r.contains("MFE_FLOOR") ||
            r.contains("BANK")

    private fun isHardSl(r: String): Boolean =
        r == "STOP" || r.contains("STRICT_SL") || r.contains("STOP_LOSS") || r.contains("STOP LOSS") ||
            r.contains("STOPLOSS") || r.contains("HARD_STOP") || r.contains("HARD_FLOOR") ||
            r.contains("HARD_15PCT_SL") || r.contains("HARD_SL") ||
            r.contains("STRUCTURE_STOP_7739") || r.contains("MODE_EXIT_STOP_7744")

    /** B3 — the one shared emergency bypass class (ranks 1-3). */
    fun isEmergency(reason: String?): Boolean = of(reason).rank <= Priority.HARD_SL.rank

    /** Ranks 1-2: accept poor price impact up to the cap (B4) and skip impact deferral. */
    fun acceptsPoorImpact(reason: String?): Boolean = of(reason).rank <= Priority.STRUCTURAL_EMERGENCY.rank

    /**
     * B3 Moonshot rule — runners keep their normal drawdown / trailing
     * behaviour. Only the structural emergency class and the existing hard
     * catastrophe stop override a Moonshot hold (Field Manual L267-L268).
     */
    private fun overridesMoonshotHold(reason: String?): Boolean {
        val p = of(reason)
        if (p == Priority.STRUCTURAL_EMERGENCY) return true
        return p == Priority.CAPITAL_PRESERVATION && reason.orEmpty().uppercase().contains("CATASTROPH")
    }

    private fun isMoonshotLane(lane: String?): Boolean = lane.orEmpty().uppercase().contains("MOONSHOT")

    /**
     * B2 — does this exit bypass the hold gates (style min-hold, lane hold
     * preference, council disagreement, learning advisory, moonbag hold,
     * profit-dust suppression)? Class 1-3 always do, except that on a
     * Moonshot lane only [overridesMoonshotHold] reasons do.
     */
    fun bypassesHolds(reason: String?, lane: String?): Boolean {
        if (!isEmergency(reason)) return false
        return if (isMoonshotLane(lane)) overridesMoonshotHold(reason) else true
    }

    /**
     * B2 — generic arbiter comparison: an exit at [exit] priority overrides a
     * hold at [hold] priority when it ranks strictly higher (lower rank).
     */
    fun exitOverridesHold(exit: Priority, hold: Priority): Boolean = exit.rank < hold.rank

    /**
     * B1 — delay before retry number [attempt] (1 = the first retry after the
     * first failed attempt). Emergency: 2 s, 3 s, 5 s, 8 s, then 10 s forever
     * (never give up on a funded emergency; the callers stop only when the
     * position is closed / the wallet is dust). Normal: [normalMs] capped at
     * [NORMAL_RETRY_CAP_MS_7807].
     */
    fun retryDelayMs(reason: String?, attempt: Int, normalMs: Long): Long =
        if (isEmergency(reason)) emergencyRetryDelayMs(attempt) else normalMs.coerceAtMost(NORMAL_RETRY_CAP_MS_7807)

    fun emergencyRetryDelayMs(attempt: Int): Long {
        val i = (attempt - 1).coerceIn(0, EMERGENCY_RETRY_LADDER_MS_7807.size - 1)
        return EMERGENCY_RETRY_LADDER_MS_7807[i]
    }

    /** B1 — cap for any non-emergency retry / coalesce window. */
    fun capNormalWindowMs(ms: Long): Long = ms.coerceAtMost(NORMAL_RETRY_CAP_MS_7807)

    /**
     * B4 — emergency slippage ladder for sell attempt [attempt] (1-based, the
     * CloseLease attempt count). Full ladder: route's normal emergency
     * slippage (or 15% when the route has none), 25%, 35%, 50% cap. Each
     * later attempt starts one rung higher, so attempt 4+ goes straight to the
     * 50% cap.
     */
    fun slippageLadderBps(attempt: Int, routeEmergencyStartBps: Int?): List<Int> {
        val start = (routeEmergencyStartBps?.takeIf { it > 0 } ?: DEFAULT_EMERGENCY_START_BPS_7807)
            .coerceAtMost(EMERGENCY_SLIP_CAP_BPS_7807)
        val full = (listOf(start) + listOf(SLIP_RUNG_2_BPS_7807, SLIP_RUNG_3_BPS_7807, EMERGENCY_SLIP_CAP_BPS_7807).filter { it > start })
            .distinct()
        val skip = (attempt - 1).coerceIn(0, full.size - 1)
        return full.drop(skip)
    }

    /** B4 — the first rung for a single-shot route (PumpPortal, Raydium) on [attempt]. */
    fun singleShotSlippageBps(attempt: Int, routeEmergencyStartBps: Int?): Int =
        slippageLadderBps(attempt, routeEmergencyStartBps).first()

    /**
     * B4 — route escalation. After a failed emergency attempt (attempt >= 2)
     * the aggregator quote ladder is skipped and the sell goes straight to the
     * direct routes already in liveSell (PumpPortal -> Raydium / Helius Sender
     * -> PumpPortal rescue).
     */
    fun shouldEscalateRoute(reason: String?, attempt: Int): Boolean = isEmergency(reason) && attempt >= 2

    /**
     * When an emergency arrives on a lease opened for a softer exit, the sell
     * must run under the emergency reason so slippage, routing and bypass all
     * read the emergency. Returns the reason the sell should carry.
     */
    fun effectiveReason(leaseReason: String, requestReason: String): String =
        if (isEmergency(requestReason) && of(requestReason).rank < of(leaseReason).rank) requestReason else leaseReason

    // ── Attempt memory that survives a released lease ──────────────────────
    // ROUTE_FAILED_NO_SIGNATURE deliberately releases the CloseLease (a held
    // lease blocked buys in 3795), which reset closeAttemptCount to 1 on every
    // re-acquire: an emergency that failed on every route kept retrying at its
    // first slippage rung on its first route forever. Failed emergency attempts
    // are remembered here per mint (10 min horizon) so B4 escalation advances.
    private const val ATTEMPT_MEMORY_MS_7807 = 10L * 60_000L
    private data class Failed7807(val count: Int, val atMs: Long)
    private val failedAttempts7807 = java.util.concurrent.ConcurrentHashMap<String, Failed7807>()

    fun noteFailedAttempt(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (failedAttempts7807.size > 2_000) failedAttempts7807.entries.removeIf { nowMs - it.value.atMs > ATTEMPT_MEMORY_MS_7807 }
        failedAttempts7807.merge(mint, Failed7807(1, nowMs)) { old, _ ->
            if (nowMs - old.atMs > ATTEMPT_MEMORY_MS_7807) Failed7807(1, nowMs) else Failed7807(old.count + 1, nowMs)
        }
    }

    fun clearAttempts(mint: String) {
        if (mint.isNotBlank()) failedAttempts7807.remove(mint)
    }

    /** The attempt number to escalate on: the larger of the lease's count and remembered failures + 1. */
    fun attemptFor(mint: String, leaseAttempt: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val f = failedAttempts7807[mint]
        val remembered = if (f != null && nowMs - f.atMs <= ATTEMPT_MEMORY_MS_7807) f.count + 1 else 1
        return maxOf(leaseAttempt, remembered, 1)
    }

    fun label(name: String) {
        try { PipelineHealthCollector.labelInc(name) } catch (_: Throwable) {}
    }
}

/**
 * V5.0.7807 — B1 "no queueing behind normal exits or discovery work".
 *
 * Risk-clock and tick-loop sells were launched on Dispatchers.IO, the same
 * pool intake/discovery fills with blocking provider calls. A protective
 * emergency now runs its (synchronous, network-bound) requestSell on a small
 * elastic pool nothing else uses, so it starts on the tick it is detected.
 * Normal exits keep Dispatchers.IO. Field Manual L248.
 */
object EmergencyExitDispatcher7807 {
    private val executor7807: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "AATE-EmergencyExit-7807").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
        }
    private val dispatcher7807: CoroutineDispatcher = executor7807.asCoroutineDispatcher()

    /** Dedicated dispatcher for a protective emergency, Dispatchers.IO otherwise. */
    fun forReason(reason: String?): CoroutineDispatcher =
        if (ProtectiveExitClass7807.isEmergency(reason)) dispatcher7807 else kotlinx.coroutines.Dispatchers.IO
}
