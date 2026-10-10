package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4161 — EXECUTION HEALTH GUARD
 * ════════════════════════════════════════════════════════════════════════════
 * Last-line execution-side defense against the V5.0.4160 dump scenario:
 * trades closing at -71% / -58% despite STRICT_SL configured at -10%. Root
 * cause was a Jupiter DNS blackout (`tokens.jup.ag` unresolvable) that nuked
 * quotes; the executor fell through to a PUMP/HELIUS direct route with no
 * slippage projection and filled into dying liquidity for catastrophic loss.
 *
 * V5.0.4160's `CATASTROPHIC_HARD_BACKSTOP_-25` correctly DETECTS the bleed
 * but it calls the SAME doSell pipeline → if execution can't broadcast at
 * a clean price, the backstop still bleeds. This guard sits one layer
 * deeper, at the broadcast chokepoints.
 *
 * Three surgical rules — VOLUME-PRESERVING by design:
 *
 *   1. [shouldDeferBuy] — Pause new BUYS when Jupiter is dead. We do not
 *      acquire bags we cannot safely unwind. Self-resets the moment Jupiter
 *      logs ONE success — no permanent throttle on the meme trader.
 *
 *   2. [shouldDeferDirectRouteSell] — When Jupiter quote is unavailable
 *      AND the reason is NON-emergency, allow N short retries (5 ticks
 *      ≈ 30s) for Jupiter to recover before falling through to the direct
 *      route. Emergency reasons (RUG, HONEYPOT, CATASTROPHIC, STEALTH_MINT,
 *      STALE, MAX_HOLD, MUST_SELL, EMERGENCY, SHUTDOWN, PHANTOM) ALWAYS
 *      broadcast immediately — better a bad fill than a frozen rug.
 *
 *   3. [recordSlippageOutcome] — Post-execution alarm: if realized SOL is
 *      worse than quoted by >20%, log `EXECUTION_SLIPPAGE_VIOLATION` so we
 *      can detect the failure mode in telemetry and feed
 *      `ExecutionCostPredictorAI` properly.
 *
 * Meme trader / volume promise: this module NEVER vetoes the meme trader.
 * - Buys defer at most until Jupiter logs one success (typically seconds).
 * - Sells defer at most ~30s before force-proceeding to direct route.
 * - Emergencies always broadcast.
 * - State is in-memory and self-resets — bot restart = clean slate.
 */
object ExecutionHealthGuard {

    // ─── Jupiter health gate ─────────────────────────────────────────────
    /** Min Jupiter success rate to consider it healthy enough for new buys. */
    private const val JUPITER_HEALTHY_SR = 0.25

    /** Treat Jupiter as dead if no success within this many ms. */
    private const val JUPITER_FRESH_SUCCESS_WINDOW_MS = 60_000L

    /** Cap on direct-route sell defers per mint; wall-clock cap is the real authority. */
    private const val DIRECT_ROUTE_DEFER_MAX = 5
    // V5.0.7807 — B1: non-emergency retry/defer windows are capped at 15s (was 30s).
    private const val DIRECT_ROUTE_DEFER_MAX_MS = 15_000L

    // ─── Slippage alarm ──────────────────────────────────────────────────
    /** A realized-vs-quoted gap larger than this fraction triggers the alarm. */
    private const val SLIPPAGE_VIOLATION_FRACTION = 0.20

    // ─── Emergency reasons that bypass every defer ───────────────────────
    private val EMERGENCY_REASON_KEYS = listOf(
        "RUG", "HONEYPOT", "EMERGENCY", "SHUTDOWN", "PHANTOM",
        "STALE", "MAX_HOLD", "MUST_SELL", "CATASTROPHIC",
        "STEALTH_MINT", "DRAIN", "PANIC", "REFLEX", "LIQUIDITY_COLLAPSE",
        "LIQUIDITY_DRAIN", "NO_LIQUIDITY_EXIT", "STOP", "STRICT_SL",
        "HARD_FLOOR", "PROTECTIVE", "FORCED_LIQUIDATION"
    )

    /** True iff the reason should always broadcast, never defer. */
    fun isEmergencyReason(reason: String): Boolean {
        val r = reason.uppercase()
        // V5.0.7807 — B3: the shared protective emergency class (dev_dump,
        // freeze/mint authority threat, ... were missing from the key list) never
        // waits for Jupiter to recover (Field Manual L248).
        return EMERGENCY_REASON_KEYS.any { r.contains(it) } ||
            com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason)
    }

    /** Snapshot Jupiter health. Returns true when Jupiter is alive enough. */
    fun isJupiterHealthy(): Boolean {
        return try {
            val all = ApiHealthMonitor.snapshot()
            fun healthy(key: String): Boolean? {
                val snap = all[key] ?: return null
                val sr = snap.successRate()
                val lastSuccessMs = snap.lastSuccessMs.get()
                val freshSuccess = lastSuccessMs > 0L &&
                    (System.currentTimeMillis() - lastSuccessMs) <= JUPITER_FRESH_SUCCESS_WINDOW_MS
                if (sr >= JUPITER_HEALTHY_SR || freshSuccess) return true

                // V5.0.4164 — do not globally park buys on token-specific Jupiter
                // quote 4xxs. 4xx proves the endpoint is reachable; it usually means
                // this mint/amount has no route. The executor can rotate/fail per-token.
                // Only network/5xx health collapse should freeze new entries globally.
                val http4xxOnly = snap.failures4xx.get() > 0 &&
                    snap.failures5xx.get() == 0 && snap.networkErrors.get() == 0
                if (http4xxOnly) return true
                return false
            }

            // V5.0.4162 — split execution health from token-list/general health.
            // Operator runtime showed `jupiter sr=0` due tokens.jup.ag DNS while
            // `jupiter_quote` was still healthy enough. New buys only need the
            // unwind/execution path to be reachable (quote/send). Token-specific
            // quote 4xxs and dead token-list endpoints must not freeze MemeTrader entries.
            val quoteHealthy = healthy("jupiter_quote")
            val sendHealthy = healthy("jupiter_send")
            if (quoteHealthy != null || sendHealthy != null) {
                return (quoteHealthy != false) && (sendHealthy != false)
            }

            // Fallback for older telemetry builds that only emit generic `jupiter`.
            healthy("jupiter") ?: true
        } catch (_: Throwable) { true /* fail-open: never block on health-monitor failure */ }
    }

    /**
     * Buy-side gate. Returns true when we should defer this BUY because the
     * sell-side execution layer (Jupiter) is currently dead. Self-resets
     * immediately on the next Jupiter success — no permanent throttle.
     *
     * Volume promise: this returns false in the steady state. It only
     * returns true during an active oracle blackout.
     */
    fun shouldDeferBuy(): Boolean = !isJupiterHealthy()

    private val directRouteDeferCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val directRouteFirstDeferMs = ConcurrentHashMap<String, Long>()

    /**
     * Sell-side gate. Returns true when we should defer a NON-emergency
     * direct-route sell because Jupiter is dead. Caps at [DIRECT_ROUTE_DEFER_MAX]
     * defers per mint so a genuinely-rugging position still gets liquidated.
     *
     * Emergency reasons (RUG / CATASTROPHIC / STEALTH_MINT / etc) always
     * return false — they MUST broadcast at any cost.
     */
    /**
     * V5.0.7979 — owner: "swap routing can go thru helius with no issue 100% of the time."
     * The direct route (pump.fun / PumpSwap / Raydium built and broadcast through the Helius
     * Sender) does not need Jupiter, so a dead Jupiter quote no longer holds a sell for up to
     * 15 s waiting for it to recover: the sell goes direct now. Kept as the one call site's
     * answer (always "do not defer"); the defer counters below stay for telemetry.
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldDeferDirectRouteSell(mint: String, reason: String): Boolean = false

    /** Current defer count for telemetry / debug. */
    fun directRouteDeferCount(mint: String): Int =
        directRouteDeferCounts[mint]?.get() ?: 0

    fun directRouteDeferAgeMs(mint: String): Long {
        val first = directRouteFirstDeferMs[mint] ?: return 0L
        return (System.currentTimeMillis() - first).coerceAtLeast(0L)
    }

    /** Clear the defer counter for a mint after a successful sell. */
    fun clearDirectRouteDefer(mint: String) {
        directRouteDeferCounts.remove(mint)
        directRouteFirstDeferMs.remove(mint)
    }

    // ─── Slippage violation alarm ────────────────────────────────────────
    private val slippageViolationCount = AtomicLong(0L)

    fun slippageViolationsToday(): Long = slippageViolationCount.get()

    /**
     * Post-execution alarm. Compares the quoted out-SOL against the actual
     * realized SOL. If the gap exceeds [SLIPPAGE_VIOLATION_FRACTION], log a
     * forensic event so the failure mode is visible in telemetry.
     *
     * No control-flow effect — this is observability only. Used to drive
     * the daily "Catastrophic backstops fired today" UI strip and to feed
     * `ExecutionCostPredictorAI` calibration.
     */
    fun recordSlippageOutcome(
        mint: String,
        symbol: String,
        quotedSol: Double,
        realizedSol: Double,
        reason: String,
    ) {
        try {
            if (quotedSol <= 0.0 || realizedSol < 0.0) return
            val gap = quotedSol - realizedSol
            val gapFrac = gap / quotedSol
            if (gapFrac >= SLIPPAGE_VIOLATION_FRACTION) {
                slippageViolationCount.incrementAndGet()
                ForensicLogger.lifecycle(
                    "EXECUTION_SLIPPAGE_VIOLATION",
                    "mint=${mint.take(10)} symbol=$symbol reason=${reason.take(40)} " +
                        "quotedSol=${"%.6f".format(quotedSol)} realizedSol=${"%.6f".format(realizedSol)} " +
                        "gap=${"%.6f".format(gap)} gapFrac=${"%.3f".format(gapFrac)}"
                )
            }
        } catch (_: Throwable) {}
    }
}
