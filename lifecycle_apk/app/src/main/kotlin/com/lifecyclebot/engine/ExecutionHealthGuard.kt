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

    /** Cap on direct-route sell defers per mint (5 ticks ≈ 30s of patience). */
    private const val DIRECT_ROUTE_DEFER_MAX = 5

    // ─── Slippage alarm ──────────────────────────────────────────────────
    /** A realized-vs-quoted gap larger than this fraction triggers the alarm. */
    private const val SLIPPAGE_VIOLATION_FRACTION = 0.20

    // ─── Emergency reasons that bypass every defer ───────────────────────
    private val EMERGENCY_REASON_KEYS = listOf(
        "RUG", "HONEYPOT", "EMERGENCY", "SHUTDOWN", "PHANTOM",
        "STALE", "MAX_HOLD", "MUST_SELL", "CATASTROPHIC",
        "STEALTH_MINT", "DRAIN", "PANIC", "REFLEX", "LIQ"
    )

    /** True iff the reason should always broadcast, never defer. */
    fun isEmergencyReason(reason: String): Boolean {
        val r = reason.uppercase()
        return EMERGENCY_REASON_KEYS.any { r.contains(it) }
    }

    /** Snapshot Jupiter health. Returns true when Jupiter is alive enough. */
    fun isJupiterHealthy(): Boolean {
        return try {
            val snap = ApiHealthMonitor.snapshot()["jupiter"] ?: return true
            val sr = snap.successRate()
            val lastSuccessMs = snap.lastSuccessMs.get()
            val freshSuccess = lastSuccessMs > 0L &&
                (System.currentTimeMillis() - lastSuccessMs) <= JUPITER_FRESH_SUCCESS_WINDOW_MS
            // Healthy if EITHER the rolling success rate is up OR Jupiter
            // logged a success in the recent window. Fail-open on no samples.
            sr >= JUPITER_HEALTHY_SR || freshSuccess
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

    /**
     * Sell-side gate. Returns true when we should defer a NON-emergency
     * direct-route sell because Jupiter is dead. Caps at [DIRECT_ROUTE_DEFER_MAX]
     * defers per mint so a genuinely-rugging position still gets liquidated.
     *
     * Emergency reasons (RUG / CATASTROPHIC / STEALTH_MINT / etc) always
     * return false — they MUST broadcast at any cost.
     */
    fun shouldDeferDirectRouteSell(mint: String, reason: String): Boolean {
        if (isEmergencyReason(reason)) return false
        if (isJupiterHealthy()) {
            // Jupiter is alive again — clear any prior defers so we don't
            // carry stale state once recovery happens.
            directRouteDeferCounts.remove(mint)
            return false
        }
        val counter = directRouteDeferCounts.getOrPut(mint) { AtomicInteger(0) }
        val n = counter.incrementAndGet()
        if (n > DIRECT_ROUTE_DEFER_MAX) {
            directRouteDeferCounts.remove(mint)
            return false // force-proceed to avoid permanent freeze
        }
        return true
    }

    /** Current defer count for telemetry / debug. */
    fun directRouteDeferCount(mint: String): Int =
        directRouteDeferCounts[mint]?.get() ?: 0

    /** Clear the defer counter for a mint after a successful sell. */
    fun clearDirectRouteDefer(mint: String) {
        directRouteDeferCounts.remove(mint)
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
