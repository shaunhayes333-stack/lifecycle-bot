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
 *
 * V5.0.8418 §THE_LOSS_HOLD_IS_OFF_UNTIL_PROVEN — the owner has not confirmed this hold (no DIRECTIVE_LEDGER_8367 row;
 * it overrides ledger #13 RAPID_FLUID_STOP and #29 backstops, and the -22 / -25 % "catastrophic" lines), and the evidence
 * does not show holding to -35 % beats stopping: the 8379 commit's sample (246 live buys < 1 h old, 25 later runners sold by
 * rapid stops) counts the runners a stop sold but not the losers the hold would have ridden to -35 %; the only post-8379
 * report (5.0.8416, 3.7 h) shows held=0, no LAUNCH / LAUNCH_HELD after-exit cohort and one OTHER stop. So [lossHoldEnabled8418]
 * is false: every exit is released. The window still evaluates each loss exit and counts what it WOULD have held
 * (`wouldHold`), and marks the mint so ExitRegret7752's after-exit horizons grade those stopped exits as the LAUNCH_HELD
 * cohort (realised stop vs the price 5 / 15 / 60 min later) — the evidence the owner needs to turn it back on.
 * Whatever the switch, rug, liquidity, the $500 non-curve floor and the 8320 curve exit-safety floor are released by
 * reason code ([FLOOR_EXIT_CODES_8418], ProtectiveExitClass7807 STRUCTURAL) and by the pool's own state, never held.
 */
object LaunchWindow8379 {
    /** V5.0.8418 — the owner's switch for the loss hold. Off: unconfirmed and unproven (see above). */
    @Volatile internal var lossHoldEnabled8418: Boolean = false

    /**
     * V5.0.8418 — the liquidity / rug floor exits by their reason CODE (the code the exit door stamps, matched as the
     * reason's leading code), independent of whether the text happens to contain "LIQUIDITY" or "RUG".
     */
    private val FLOOR_EXIT_CODES_8418 = listOf(
        "THIN_LIQ_EARLY_RUG_BACKSTOP", "LIQUIDITY_BELOW_EXIT_SAFE_FLOOR", "LIQUIDITY_DRAIN_EXIT", "LIQUIDITY_COLLAPSE",
        "LIQUIDITY_DRAIN", "LIQUIDITY_REMOVED", "LIQ_DRAIN", "LIQ_HALVED_MARK_INVALIDATED", "NO_LIQUIDITY_EXIT",
        "VENUE_NOT_EXIT_SAFE", "CURVE_DEPTH_UNVERIFIED", "CURVE_NOT_EXIT_SAFE", "EXIT_SAFE_FLOOR", "LP_PULL", "LP_REMOVED",
        "RUG", "HONEYPOT", "CANNOT_SELL", "UNSELLABLE", "FROZEN_HELD_UNSELLABLE", "BOUNCE_ON_DRAINING_LIQ",
    )
    /** The owner's non-curve exit floor (PreTradeHardGate MIN_LIVE_LIQ_HARD_FLOOR_USD). */
    private const val NON_CURVE_FLOOR_USD_8418 = 500.0

    /** Pure (V5.0.8418): is [reason]'s leading code one of the floor / rug exit codes? */
    fun floorExitCode8418(reason: String): Boolean {
        val r = reason.trim().uppercase()
        return FLOOR_EXIT_CODES_8418.any { r == it || r.startsWith(it + "_") || r.startsWith("$it:") || r.startsWith("$it ") }
    }

    /**
     * Pure (V5.0.8418): is the pool itself under the owner's exit floors — a known pool under $500 that is not an exit-safe
     * active curve (the $500 rule), or an active curve that is no longer exit-safe (the 8320 rule)? Unknown depth is not a breach.
     */
    fun floorBreached8418(liquidityUsd: Double, activeCurve: Boolean, curveExitSafe: Boolean): Boolean {
        if (activeCurve) return !curveExitSafe
        return liquidityUsd.isFinite() && liquidityUsd > 0.0 && liquidityUsd < NON_CURVE_FLOOR_USD_8418
    }

    private val wouldHold8418 = AtomicLong(0)

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
    fun releaseCause8379(reason: String, grossPct: Double, metricsCollapsed: Boolean, poolFloorBreached: Boolean = false): String? {
        // V5.0.8418 — the floor / rug exits first, by code and by the pool's state: released at any P&L, never held.
        if (floorExitCode8418(reason)) return "FLOOR_EXIT_CODE"
        if (poolFloorBreached) return "POOL_FLOOR"
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
        com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.desk8392(ts.position.tradingMode)
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
        // V5.0.8418 — a stale (> 120 s) buy-pressure read is UNKNOWN, not "not collapsed": the exit is released.
        val bpFresh = nowMs - ts.buyPressureObservedAtMs8084 in 0L..120_000L
        val collapsed = !bpFresh || try {
            LifecycleHold8065.metricsCollapsed8109(g, pos.entryLiquidityUsd, ts.lastLiquidityUsd, ts.lastBuyPressurePct, bpFresh)
        } catch (_: Throwable) { true }
        val poolFloor = try {
            val curve = ts.tokenMap.pumpFunExecutable && !com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(ts.mint)
            floorBreached8418(ts.lastLiquidityUsd, curve, curve && LaneAgeLiquidityTruth8140.activeCurveExitSafe8319(ts.mint, nowMs))
        } catch (_: Throwable) { true }
        val cause = releaseCause8379(reason, g, collapsed, poolFloor)
        if (cause != null) {
            if (cause != "PROFIT_SIDE") {
                released.computeIfAbsent(cause) { AtomicLong() }.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LAUNCH_WINDOW_RELEASED_${cause}_8379") } catch (_: Throwable) {}
            }
            return null
        }
        heldMints[ts.mint] = nowMs   // ExitRegret7752 LAUNCH_HELD cohort: exits the window holds, or (switch off) would hold
        if (heldMints.size > 500) heldMints.entries.removeIf { nowMs - it.value > 6L * 3_600_000L }
        if (!lossHoldEnabled8418) {
            wouldHold8418.incrementAndGet()
            heldReasons.computeIfAbsent(com.lifecyclebot.engine.truth.ExitRegret7752.family(reason)) { AtomicLong() }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LAUNCH_WINDOW_WOULD_HOLD_RELEASED_8418") } catch (_: Throwable) {}
            return null
        }
        held.incrementAndGet()
        heldReasons.computeIfAbsent(com.lifecyclebot.engine.truth.ExitRegret7752.family(reason)) { AtomicLong() }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(HOLD_LABEL_8379) } catch (_: Throwable) {}
        return HOLD_LABEL_8379
    }

    /** ExitRegret7752: was an exit of [mint] held by the window within [withinMs] of [atMs]? */
    fun wasHeld8379(mint: String, atMs: Long, withinMs: Long): Boolean {
        val t = heldMints[mint] ?: return false
        return atMs - t in 0L..withinMs
    }

    fun statusLine8379(): String =
        "launchWindow8379[lossHold=${if (lossHoldEnabled8418) "ON" else "OFF_UNCONFIRMED_8418"} held=${held.get()} wouldHold=${wouldHold8418.get()} (after-exit: LAUNCH_HELD cohort) labelsGraded=${try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.windowGraded8379() } catch (_: Throwable) { -1L }} floor=-${FLOOR_PCT_8379.toInt()}% window<${TOKEN_AGE_MS_8379 / 60_000L}m/min${MIN_HOLD_MS_8379 / 60_000L}m " +
            "heldBy=${heldReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "released=${released.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}]"

    /** ForwardReturnLabeler7731: the window's minimum position age (labels grade the same window). */
    fun minHoldMs8379(): Long = MIN_HOLD_MS_8379

    internal fun resetForTest8379() { held.set(0); wouldHold8418.set(0); released.clear(); heldReasons.clear(); heldMints.clear() }
}
