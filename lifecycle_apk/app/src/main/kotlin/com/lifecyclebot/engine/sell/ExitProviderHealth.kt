package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * ExitProviderHealth — V5.0.4102 (Wave B of P0 sell-failure patch)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0 mandate (mayham/MCAPYB exit-loop forensics):
 *   "If Jupiter exit returns 503 twice within 30 seconds → disable for 90s.
 *    If Pump direct returns 0x1788 → mark route cache stale; second 0x1788
 *    after refresh suppresses Pump direct for 60s."
 *
 * GLOBAL circuit breaker complementing the existing per-mint
 * CloseLease.scheduleBackoff (which only delays the same mint after the
 * same error class). When Jupiter is broadly degraded, EVERY emergency
 * sell wastes time burning the 200/350/500 bps quote ladder. This
 * breaker opens once after 2 sell-side 503s in a 30s window and forces
 * the route ladder to skip Jupiter entirely for 90s, preferring native
 * (PumpPortal) routes for full exits.
 *
 * Doctrine:
 *   • Soft-shape: callers always check isJupiterExitDegraded() before
 *     entering the Jupiter ladder; if open, they jump straight to
 *     PumpPortal/native for FULL_EXIT/STOP_LOSS/HARD_RUG_EXIT/
 *     RECOVERY_MANAGEMENT classes.
 *   • Probe-once: when cooldown elapses, the next sell is allowed as a
 *     probe. If the probe also 503s, cooldown extends 60s.
 *   • Pump 0x1788 is per-mint; second-strike → 60s mint-level Pump
 *     suppression. A different mint can still try Pump direct.
 *   • Buy-side 503s on Jupiter are NOT counted here — the operator
 *     specifically called out sell-side degradation. Buy-side has its
 *     own slip-ladder that already handles transient quote failures.
 */
object ExitProviderHealth {

    private const val TAG = "ExitProviderHealth"

    // ── Jupiter sell-side 503 circuit breaker ─────────────────────────
    private const val JUP_WINDOW_MS = 30_000L
    private const val JUP_THRESHOLD = 2
    private const val JUP_COOLDOWN_MS = 90_000L
    private const val JUP_PROBE_EXTEND_MS = 60_000L

    private val jupiterSell503History = ConcurrentLinkedDeque<Long>()
    @Volatile private var jupiterExitDisabledUntilMs: Long = 0L
    @Volatile private var jupiterProbeArmedAtMs: Long = 0L

    fun recordJupiterSell503() {
        val now = System.currentTimeMillis()
        jupiterSell503History.add(now)
        // prune older than window
        while (jupiterSell503History.isNotEmpty() &&
               (jupiterSell503History.peekFirst() ?: now) < now - JUP_WINDOW_MS) {
            jupiterSell503History.pollFirst()
        }
        val countInWindow = jupiterSell503History.size
        if (countInWindow >= JUP_THRESHOLD && now >= jupiterExitDisabledUntilMs) {
            jupiterExitDisabledUntilMs = now + JUP_COOLDOWN_MS
            jupiterProbeArmedAtMs = jupiterExitDisabledUntilMs
            try {
                ErrorLogger.warn(
                    TAG,
                    "🧯 JUPITER_EXIT_CIRCUIT_OPEN: $countInWindow 503s in ${JUP_WINDOW_MS / 1000}s, cooldown ${JUP_COOLDOWN_MS / 1000}s"
                )
                ForensicLogger.lifecycle(
                    "JUPITER_EXIT_CIRCUIT_OPEN",
                    "count=$countInWindow windowMs=$JUP_WINDOW_MS cooldownMs=$JUP_COOLDOWN_MS"
                )
                PipelineHealthCollector.labelInc("JUPITER_EXIT_CIRCUIT_OPEN")
            } catch (_: Throwable) { }
        }
    }

    /** True if sell-side Jupiter calls should be skipped right now. */
    fun isJupiterExitDegraded(): Boolean =
        System.currentTimeMillis() < jupiterExitDisabledUntilMs

    /** Probe attempt — call this BEFORE making a Jupiter sell call when
     *  isJupiterExitDegraded() is false. If it returns true, the caller
     *  is allowed exactly one probe; on success call recordJupiterSellOk(),
     *  on failure call recordJupiterSell503() which will extend the cooldown. */
    fun jupiterProbeReady(): Boolean {
        val now = System.currentTimeMillis()
        return now >= jupiterExitDisabledUntilMs
    }

    /** Successful Jupiter sell — clears the breaker. */
    fun recordJupiterSellOk() {
        if (jupiterExitDisabledUntilMs > 0L) {
            try {
                ErrorLogger.info(TAG, "✅ JUPITER_EXIT_CIRCUIT_CLOSED (probe ok)")
                ForensicLogger.lifecycle("JUPITER_EXIT_CIRCUIT_CLOSED", "probe=ok")
                PipelineHealthCollector.labelInc("JUPITER_EXIT_CIRCUIT_CLOSED")
            } catch (_: Throwable) { }
        }
        jupiterSell503History.clear()
        jupiterExitDisabledUntilMs = 0L
    }

    fun jupiterCooldownRemainingMs(): Long {
        val r = jupiterExitDisabledUntilMs - System.currentTimeMillis()
        return r.coerceAtLeast(0L)
    }

    // ── Pump direct 0x1788 per-mint suppression ───────────────────────
    private const val PUMP_SUPPRESSION_MS = 60_000L
    private const val PUMP_RETRIES_BEFORE_SUPPRESS = 2

    private data class PumpStrike(
        val countAtFirst: Int,
        val firstAtMs: Long,
        @Volatile var count: Int = 1,
        @Volatile var suppressedUntilMs: Long = 0L,
    )

    private val pump1788ByMint = ConcurrentHashMap<String, PumpStrike>()
    private val routeInvalidatedAt = ConcurrentHashMap<String, AtomicLong>()

    fun recordPump1788(mint: String) {
        val now = System.currentTimeMillis()
        val s = pump1788ByMint.compute(mint) { _, prev ->
            if (prev == null) PumpStrike(1, now)
            else prev.also { it.count++ }
        } ?: return
        // Invalidate route cache (signal — actual route resolver reads this)
        routeInvalidatedAt.computeIfAbsent(mint) { AtomicLong(0L) }.set(now)
        try {
            ForensicLogger.lifecycle(
                "PUMP_1788_ROUTE_INVALIDATED",
                "mint=${mint.take(10)} strikeCount=${s.count}"
            )
            PipelineHealthCollector.labelInc("PUMP_1788_ROUTE_INVALIDATED")
        } catch (_: Throwable) { }
        if (s.count >= PUMP_RETRIES_BEFORE_SUPPRESS && s.suppressedUntilMs <= now) {
            s.suppressedUntilMs = now + PUMP_SUPPRESSION_MS
            try {
                ErrorLogger.warn(
                    TAG,
                    "🧯 PUMP_DIRECT_SUPPRESSED mint=${mint.take(10)} strikes=${s.count} cooldownMs=$PUMP_SUPPRESSION_MS"
                )
                ForensicLogger.lifecycle(
                    "PUMP_1788_RETRY_SUPPRESSED_UNTIL",
                    "mint=${mint.take(10)} until=${s.suppressedUntilMs}"
                )
                PipelineHealthCollector.labelInc("PUMP_DIRECT_SUPPRESSED")
            } catch (_: Throwable) { }
        }
    }

    /** True if Pump direct should be skipped for this mint right now. */
    fun isPumpDirectSuppressed(mint: String): Boolean {
        val s = pump1788ByMint[mint] ?: return false
        return System.currentTimeMillis() < s.suppressedUntilMs
    }

    /** True if this mint's Pump route cache was recently invalidated and the
     *  route resolver should refresh venue/migration state before retry. */
    fun pumpRouteInvalidatedRecently(mint: String, withinMs: Long = 5_000L): Boolean {
        val t = routeInvalidatedAt[mint]?.get() ?: return false
        return (System.currentTimeMillis() - t) < withinMs
    }

    /** Successful Pump sell — clears the suppression for this mint. */
    fun recordPumpSellOk(mint: String) {
        val s = pump1788ByMint.remove(mint) ?: return
        try {
            ErrorLogger.info(
                TAG,
                "✅ PUMP_DIRECT_RESET mint=${mint.take(10)} strikesCleared=${s.count}"
            )
            ForensicLogger.lifecycle("PUMP_DIRECT_RESET", "mint=${mint.take(10)}")
        } catch (_: Throwable) { }
    }

    /** Telemetry summary surfaced in pipeline-health dump. */
    fun summary(): String {
        val jupRem = jupiterCooldownRemainingMs()
        val pumpSuppressed = pump1788ByMint.entries.count {
            System.currentTimeMillis() < it.value.suppressedUntilMs
        }
        val sb = StringBuilder("ExitProviderHealth (V5.0.4102): ")
        sb.append(
            if (jupRem > 0) "🧯 JUP_503_OPEN remainMs=$jupRem  "
            else            "✅ JUP_OK  "
        )
        sb.append("pumpSuppressedMints=$pumpSuppressed  ")
        sb.append("trackedMints=${pump1788ByMint.size}")
        return sb.toString()
    }
}
