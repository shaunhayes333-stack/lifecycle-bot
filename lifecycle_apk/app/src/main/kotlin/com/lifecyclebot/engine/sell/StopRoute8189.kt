package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8189 — A STOP TAKES THE ROUTE THAT ANSWERS, ONCE, INSIDE ITS BUDGET.
 *
 * 5.0.8186 live: a TRAILING_STOP took 54 s from trigger to broadcast over 16 attempts; the sell spine recorded
 * failStage VENUE_RESOLVE=12 and SIGN=9 (FAILED_RETRYABLE x21), RAYDIUM_SELL_FAILED_7311=7, ROUTE_FAILED_LIVE=170.
 * The spine names the stage AFTER the last one that closed (SellRouteSpine8144.Timer8144.fail):
 *  - VENUE_RESOLVE = the attempt ended after the balance read with no route ever selected: PumpPortal-first
 *    (liveSellInScope7314, every exit, shouldTryPumpDirectFirst6099) skipped or refused the mint, the racing Raydium
 *    build was not used, and the attempt returned before a quote — every one of 16 attempts repeated it;
 *  - SIGN = a transaction was BUILT (SELL_TX_BUILT: the Raydium serial / race build, a Jupiter order) and its send
 *    threw before the signed-tx ledger saw a signature — the Raydium trade API has no pool for a curve or PumpSwap-only
 *    mint, so its send fails on every attempt, and nothing remembered that for the position.
 *
 * Here: [jupiterFirst8189] — every protective / stop exit asks the aggregator (Jupiter Ultra / lite, which routes
 * pump curves, PumpSwap and Raydium) FIRST for any mint it is not blocked on, the venue-native routes after it;
 * [noteVenueFailure8189] / [venueBlocked8189] — a direct venue that failed for a mint is not rebuilt for
 * [VENUE_FAILURE_TTL_MS_8189] (Raydium is also skipped for a confirmed pump curve, where it can never answer);
 * [pastBudget8189] — once a stop is [STOP_BROADCAST_BUDGET_MS_8189] past its trigger with nothing broadcast, the slow
 * optional Raydium build is skipped and the attempt goes straight to the remaining route. Every attempt still builds
 * its own transaction (Jupiter / PumpPortal build per attempt; a Raydium prebuild is reused only while fresh), so each
 * carries a current blockhash. Nothing here raises slippage, widens a stop or skips a safety check.
 *
 * V5.0.8315 — two rules disagreed on the route and a live position could not exit. 5.0.8314: Au (6mYfg1, confirmed
 * Raydium venue) made ~334 emergency attempts, none reached a venue after the first: the 7807 escalation skipped the
 * Jupiter ladder expecting Raydium to sell (Executor.directRouteViable8144 reads the endpoint policy, which never
 * records a Raydium outcome), [skipRaydium8189] skipped Raydium because the stop was past its 10 s budget (an emergency
 * spine keeps its first trigger time, so it stayed past budget for the whole loop; raydiumSkipped=565), PumpPortal was
 * hard-blocked. Now a confirmed RAYDIUM venue is never budget-skipped — past its budget, its own route is the one that
 * answers ([raydiumSkipWhy8315]); and [raydiumDirectSkipped8315] lets skipQuoteLadder8009 keep the aggregator whenever
 * the Raydium build it would fall to is skipped on this attempt, so the escalation can never skip Jupiter while
 * Raydium is also off the table. [raydiumSkipWhy8189] answers without counting a skip.
 */
object StopRoute8189 {
    private const val STOP_BROADCAST_BUDGET_MS_8189 = 10_000L
    private const val VENUE_FAILURE_TTL_MS_8189 = 90_000L

    private val failures = ConcurrentHashMap<String, Long>()
    private val skipped = AtomicLong(0)
    private val jupiterFirst = AtomicLong(0)

    // ── pure ──

    /** Pure: is [reason] a protective / stop exit (capital preservation, structural, hard stop, trail / profit protection)? */
    fun stopExit8189(reason: String?): Boolean {
        val p = ProtectiveExitClass7807.of(reason)
        return p != ProtectiveExitClass7807.Priority.NONE && p.rank <= ProtectiveExitClass7807.Priority.PROFIT_PROTECTION.rank
    }

    /** Pure: a stop exit asks the aggregator first whenever the aggregator is not blocked for the mint. */
    fun jupiterFirst8189(reason: String?, jupiterRoutable: Boolean): Boolean = jupiterRoutable && stopExit8189(reason)

    /** Pure: is a remembered failure at [failedAtMs] still blocking at [nowMs]? */
    fun failureActive8189(failedAtMs: Long?, nowMs: Long): Boolean =
        failedAtMs != null && nowMs - failedAtMs in 0L until VENUE_FAILURE_TTL_MS_8189

    /** Pure: may the Raydium trade API answer this mint? Never for a confirmed live pump curve. */
    fun raydiumViable8189(venue: SellRouteEndpoint8144.Venue8144): Boolean =
        venue != SellRouteEndpoint8144.Venue8144.PUMP_CURVE

    /** Pure: is a stop past its trigger -> broadcast budget with nothing broadcast? */
    fun pastBudget8189(stop: Boolean, triggerAtMs: Long, broadcastAtMs: Long, nowMs: Long): Boolean =
        stop && triggerAtMs > 0L && broadcastAtMs <= 0L && nowMs - triggerAtMs > STOP_BROADCAST_BUDGET_MS_8189

    /**
     * V5.0.8315 — pure: why the Raydium build is skipped on this attempt (FAILED_RECENTLY / PUMP_CURVE / STOP_BUDGET),
     * or null. [triggerAtMs] null = no open spine. A confirmed RAYDIUM [venue] is never budget-skipped: with the
     * aggregator skipped by the escalation and PumpPortal blocked, its own route was the only one left (5.0.8314 Au).
     */
    fun raydiumSkipWhy8315(venue: SellRouteEndpoint8144.Venue8144, failedRecently: Boolean, stop: Boolean,
                           triggerAtMs: Long?, broadcastAtMs: Long, nowMs: Long): String? = when {
        failedRecently -> "FAILED_RECENTLY"
        !raydiumViable8189(venue) -> "PUMP_CURVE"
        triggerAtMs != null && venue != SellRouteEndpoint8144.Venue8144.RAYDIUM &&
            pastBudget8189(stop, triggerAtMs, broadcastAtMs, nowMs) -> "STOP_BUDGET"
        else -> null
    }

    // ── live ──

    private fun key(mint: String, route: SellRouteEndpoint8144.Route8144) = "$mint|${route.name}"

    fun noteVenueFailure8189(mint: String, route: SellRouteEndpoint8144.Route8144, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (failures.size > 2_000) failures.clear()
        failures[key(mint, route)] = nowMs
        try { PipelineHealthCollector.labelInc("SELL_VENUE_FAILURE_REMEMBERED_8189_${route.name}") } catch (_: Throwable) {}
    }

    fun venueBlocked8189(mint: String, route: SellRouteEndpoint8144.Route8144, nowMs: Long = System.currentTimeMillis()): Boolean =
        failureActive8189(failures[key(mint, route)], nowMs)

    /**
     * Executor (Raydium serial / race step): skip the Raydium build for [mint] on this attempt? True when it failed for
     * the mint inside the TTL, the mint is a confirmed pump curve, or a stop is past its broadcast budget.
     */
    fun skipRaydium8189(mint: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val why = raydiumSkipWhy8189(mint, reason, nowMs) ?: return false  // V5.0.8315 — the decision, then the count
        skipped.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SELL_RAYDIUM_SKIPPED_8189_$why") } catch (_: Throwable) {}
        return true
    }

    private fun cachedVenue8315(mint: String, nowMs: Long): SellRouteEndpoint8144.Venue8144 =
        try { SellRouteVenueProbe8144.cached8144(mint, nowMs) } catch (_: Throwable) { SellRouteEndpoint8144.Venue8144.UNKNOWN }

    /**
     * V5.0.8315 — the skip reason for [mint] on this attempt, or null, WITHOUT counting a skip (asked by
     * [skipRaydium8189] and by Executor.skipQuoteLadder8009 through [raydiumDirectSkipped8315]).
     */
    fun raydiumSkipWhy8189(mint: String, reason: String, nowMs: Long = System.currentTimeMillis()): String? {
        val t = try { SellRouteSpine8144.timer8144(mint) } catch (_: Throwable) { null }
        return raydiumSkipWhy8315(
            cachedVenue8315(mint, nowMs), venueBlocked8189(mint, SellRouteEndpoint8144.Route8144.RAYDIUM, nowMs),
            stopExit8189(reason), t?.triggerAtMs, t?.broadcastAtMs ?: 0L, nowMs,
        )
    }

    /**
     * V5.0.8315 — Executor.skipQuoteLadder8009: is the direct route an escalated emergency would fall to a confirmed
     * Raydium venue whose build this attempt skips? directRouteViable8144 answers from the endpoint policy alone, which
     * never records a Raydium outcome, so it said "viable" while [skipRaydium8189] refused the build: with the
     * aggregator skipped too, nothing was left (ROUTE_FAILED_NO_SIGNATURE x334). A pump venue is unaffected: there the
     * direct route is PumpPortal and the 8009 direct-first skip keeps its first-attempt latency.
     */
    fun raydiumDirectSkipped8315(mint: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (cachedVenue8315(mint, nowMs) != SellRouteEndpoint8144.Venue8144.RAYDIUM) return false
        val why = raydiumSkipWhy8189(mint, reason, nowMs) ?: return false
        try { PipelineHealthCollector.labelInc("SELL_QUOTE_LADDER_KEPT_RAYDIUM_SKIPPED_8315_$why") } catch (_: Throwable) {}
        return true
    }

    /** Executor.exitPumpFirst8189: the aggregator is asked first for this stop. */
    fun noteJupiterFirst8189() {
        jupiterFirst.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SELL_STOP_JUPITER_FIRST_8189") } catch (_: Throwable) {}
    }

    fun statusLine8189(): String = "stopRoute8189[jupiterFirst=${jupiterFirst.get()} raydiumSkipped=${skipped.get()} venueFailures=${failures.size}]" +
        " " + (try { EmergencySellRoute8308.statusLine8308() } catch (_: Throwable) { "emergencyRoute8308[-]" }) +  // V5.0.8308
        " " + (try { com.lifecyclebot.engine.truth.RuntimeBasisSync8308.statusLine8308() } catch (_: Throwable) { "runtimeBasisSync8308[-]" })

    internal fun resetForTest8189() { failures.clear(); skipped.set(0); jupiterFirst.set(0) }
}
