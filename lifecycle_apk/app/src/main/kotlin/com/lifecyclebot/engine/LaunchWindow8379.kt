package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8379 §LAUNCH_WINDOW_STOP. Owner-approved (option 1): a coin in its launch window is not stopped out on noise.
 *
 * Evidence (the operator's collective_trades, live, 2026-09-28..10-25): of 246 live buys made within an hour of the coin's
 * creation, 41 were coins that later ran +100..+5000%; those were held 2.7 min on average. The rapid tick stops
 * (RAPID_CATASTROPHE_STOP 80, RAPID_FLUID_STOP 52 closes, ~2 min holds, -13% / -9% means) closed 25 coins that later ran.
 * A fresh launch routinely trades 15-30% under its first prints before it runs; a flat 9-15% tick floor
 * (RegressionRestore8298) sells exactly those.
 *
 * In the window — a MOONSHOT / SHITCOIN / EXPRESS position bought when the coin was under [TOKEN_AGE_MS_8379] old, while
 * the coin is still under that age or the position under [MIN_HOLD_MS_8379] — an ordinary LOSS exit (tick stops, the
 * catastrophe tick, hard-floor ticks, time / flat culls) is held above [FLOOR_PCT_8379] gross. Never held:
 *   - any exit at or below the floor (the window's own structural stop);
 *   - rug / liquidity pull / dev dump / honeypot / freeze / dead token / manual / kill switch / wallet / shutdown / rotation
 *     (TailBag8034.structural8034, ProtectiveExitClass7807 STRUCTURAL_EMERGENCY, non-price CAPITAL_PRESERVATION);
 *   - a coin whose own metrics collapsed (pool liquidity under half of entry depth, or sellers own the fresh tape —
 *     LifecycleHold8065.metricsCollapsed8109);
 *   - every profit-side exit (gross >= 0) and every profit-protection exit: winners are untouched;
 *   - an exit with no usable price.
 * Live and paper alike (paper mirrors the live exit policy). Read by both sell doors (Executor.requestSellCore7948,
 * Executor.doSell). ExitRegret7752's 5 / 15 / 60 min after-exit horizons grade the window against the rest.
 */
object LaunchWindow8379 {
    val LANES_8379: Set<String> = setOf("MOONSHOT", "SHITCOIN", "EXPRESS")
    const val TOKEN_AGE_MS_8379 = 30L * 60_000L
    private const val MIN_HOLD_MS_8379 = 10L * 60_000L
    const val FLOOR_PCT_8379 = 35.0
    const val HOLD_LABEL_8379 = "LAUNCH_WINDOW_HOLD_8379"

    private val held = AtomicLong(0)
    private val released = ConcurrentHashMap<String, AtomicLong>()
    private val heldReasons = ConcurrentHashMap<String, AtomicLong>()
    /** mint -> last time an exit was held (ExitRegret7752 splits the held cohort by it). */
    private val heldMints = ConcurrentHashMap<String, Long>()

    /** Pure: is a position in the window? [entryTokenAgeMs] = coin age when bought (null = unknown birth: never). */
    fun inWindow8379(entryTokenAgeMs: Long?, tokenAgeNowMs: Long?, positionAgeMs: Long): Boolean {
        if (entryTokenAgeMs == null || entryTokenAgeMs !in 0L until TOKEN_AGE_MS_8379) return false
        val coinStillYoung = tokenAgeNowMs != null && tokenAgeNowMs < TOKEN_AGE_MS_8379
        return coinStillYoung || positionAgeMs in 0L until MIN_HOLD_MS_8379
    }

    /** Pure: a capital-preservation exit that is a PRICE line (held above the floor), not a wallet / kill / manual one. */
    private fun priceCatastrophe8379(reasonUpper: String): Boolean =
        (reasonUpper.contains("CATASTROPH") || reasonUpper.contains("GAP_GUARD")) &&
            !reasonUpper.contains("WALLET") && !reasonUpper.contains("KILL") && !reasonUpper.contains("MANUAL") &&
            !reasonUpper.contains("EMERGENCY_LIQUIDATE")

    /** Pure: does the window hold the exit [reason] at [grossPct]? (null = released, else the release reason is absent). */
    fun holds8379(reason: String, grossPct: Double, metricsCollapsed: Boolean): Boolean = releaseCause8379(reason, grossPct, metricsCollapsed) == null

    /** Pure: why the window does NOT hold this exit, or null when it holds it. */
    fun releaseCause8379(reason: String, grossPct: Double, metricsCollapsed: Boolean): String? {
        if (!grossPct.isFinite()) return "NO_PRICE"
        if (grossPct >= 0.0) return "PROFIT_SIDE"
        if (grossPct <= -FLOOR_PCT_8379) return "FLOOR"
        if (metricsCollapsed) return "METRICS_COLLAPSED"
        val r = reason.uppercase()
        if (r.startsWith("LIFECYCLE_") || r.startsWith(com.lifecyclebot.engine.truth.OpportunityRotation8098.REASON)) return "OWN_OR_ROTATION"
        if (TailBag8034.structural8034(r)) return "STRUCTURAL"
        return when (com.lifecyclebot.engine.sell.ProtectiveExitClass7807.of(r)) {
            com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority.STRUCTURAL_EMERGENCY -> "STRUCTURAL"
            com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority.CAPITAL_PRESERVATION -> if (priceCatastrophe8379(r)) null else "CAPITAL_PRESERVATION"
            com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority.PROFIT_PROTECTION -> "PROFIT_PROTECTION"
            else -> null
        }
    }

    private fun lane(ts: TokenState): String = try {
        com.lifecyclebot.engine.truth.Entry8114Mode8326.canon8326(ts.position.tradingMode)
    } catch (_: Throwable) { ts.position.tradingMode.trim().uppercase() }

    /** Is [ts]'s open position in the launch window now? */
    private fun active8379(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L || !(pos.entryPrice > 0.0)) return false
        if (lane(ts) !in LANES_8379) return false
        val ageNow = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null } ?: return false
        val posAge = nowMs - pos.entryTime
        return inWindow8379(ageNow - posAge, ageNow, posAge)
    }

    /** Both sell doors: non-null (the held label) when the window holds this exit. */
    fun defer8379(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (!active8379(ts, nowMs)) return null
        val pos = ts.position
        val px = ts.lastPrice
        val g = if (px.isFinite() && px > 0.0) (px / pos.entryPrice - 1.0) * 100.0 else Double.NaN
        val collapsed = try {
            LifecycleHold8065.metricsCollapsed8109(g, pos.entryLiquidityUsd, ts.lastLiquidityUsd, ts.lastBuyPressurePct,
                nowMs - ts.buyPressureObservedAtMs8084 in 0L..120_000L)
        } catch (_: Throwable) { true }
        val cause = releaseCause8379(reason, g, collapsed)
        if (cause != null) {
            if (cause != "PROFIT_SIDE") {
                released.computeIfAbsent(cause) { AtomicLong() }.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LAUNCH_WINDOW_RELEASED_${cause}_8379") } catch (_: Throwable) {}
            }
            return null
        }
        held.incrementAndGet()
        heldReasons.computeIfAbsent(com.lifecyclebot.engine.truth.ExitRegret7752.family(reason)) { AtomicLong() }.incrementAndGet()
        heldMints[ts.mint] = nowMs
        if (heldMints.size > 500) heldMints.entries.removeIf { nowMs - it.value > 6L * 3_600_000L }
        try { PipelineHealthCollector.labelInc(HOLD_LABEL_8379) } catch (_: Throwable) {}
        return HOLD_LABEL_8379
    }

    /** ExitRegret7752: was an exit of [mint] held by the window within [withinMs] of [atMs]? */
    fun wasHeld8379(mint: String, atMs: Long, withinMs: Long): Boolean {
        val t = heldMints[mint] ?: return false
        return atMs - t in 0L..withinMs
    }

    fun statusLine8379(): String =
        "launchWindow8379[held=${held.get()} floor=-${FLOOR_PCT_8379.toInt()}% window<${TOKEN_AGE_MS_8379 / 60_000L}m/min${MIN_HOLD_MS_8379 / 60_000L}m " +
            "heldBy=${heldReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "released=${released.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}]"

    internal fun resetForTest8379() { held.set(0); released.clear(); heldReasons.clear(); heldMints.clear() }
}
