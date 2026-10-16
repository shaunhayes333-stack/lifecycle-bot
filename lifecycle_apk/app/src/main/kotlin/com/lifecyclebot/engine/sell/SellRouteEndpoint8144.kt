package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8144 — SELL ROUTE ENDPOINT POLICY (P0-E).
 *
 * 5.0.8142: the PumpPortal trade endpoint answered 1,121 HTTP 4xx. Every one was thrown as PumpSellBadRequest,
 * logged "venue failover" and forgotten, so the NEXT attempt (and the rescue of the same attempt) sent the same mint to
 * the same endpoint again. Worse, an emergency that failed once escalates past the Jupiter quote ladder (7807 B4 /
 * 8009) straight to the "direct routes" — PumpPortal again (4xx), then Raydium (no pool for a curve token), then the
 * PumpPortal rescue (4xx): ROUTE_FAILED_NO_SIGNATURE on every attempt, forever, with Jupiter never asked.
 *
 * This object is the one memory of how each route answered each mint:
 *  - [classifyHttp8144] separates HARD endpoint failures (400/404/410/413/422 for an unsupported / migrated mint or a
 *    payload the endpoint refuses, 401/403 auth) from SOFT ones (408/425/429 rate limits, 5xx, transport).
 *  - a HARD failure blocks that (mint, route) — an auth failure blocks the route for every mint — so it is never
 *    resubmitted unchanged. The block lifts early only when confirmed venue metadata changed since the failure
 *    (e.g. the bonding curve completed: the mint migrated to PumpSwap), which is the one case the same endpoint can
 *    answer differently.
 *  - every route has a bounded attempt budget per mint and a short backoff after soft failures.
 *  - [venueFrom8144] / [routeOrder8144] turn confirmed on-chain venue metadata (curve account, complete flag, pool)
 *    into a route order; [directRouteViable8144] tells an escalated emergency whether skipping the aggregator still
 *    leaves a direct route that can answer.
 * Pure: time is always passed in; nothing here touches Android, the network or a logger.
 */
object SellRouteEndpoint8144 {

    enum class Route8144 { PUMPPORTAL, JUPITER, RAYDIUM }

    enum class Failure8144(val hard: Boolean, val global: Boolean) {
        OK(false, false),
        HARD_UNSUPPORTED_MINT(true, false),
        HARD_BAD_REQUEST(true, false),
        HARD_AUTH(true, true),
        SOFT_RATE_LIMIT(false, false),
        SOFT_SERVER(false, false),
        SOFT_TRANSPORT(false, false),
    }

    enum class Admit8144 { ALLOWED, HARD_BLOCKED, AUTH_BLOCKED, BUDGET_EXHAUSTED, BACKOFF }

    /** Confirmed venue of a mint (on-chain curve account / pool), UNKNOWN when nothing was confirmed. */
    enum class Venue8144 { PUMP_CURVE, PUMPSWAP, RAYDIUM, AGGREGATOR_ONLY, UNKNOWN }

    private const val MAX_ATTEMPTS_PER_ROUTE_8144 = 3
    const val ATTEMPT_WINDOW_MS_8144 = 120_000L
    const val HARD_BLOCK_MS_8144 = 10L * 60_000L
    private const val AUTH_BLOCK_MS_8144 = 15L * 60_000L
    private const val SOFT_BACKOFF_BASE_MS_8144 = 2_000L
    private const val SOFT_BACKOFF_CAP_MS_8144 = 15_000L

    private val UNSUPPORTED_MARKERS_8144 = listOf(
        "migrat", "complete", "graduat", "not found", "no pool", "pool not", "unsupported", "invalid mint",
        "bonding curve", "not tradable", "not tradeable", "no route", "could not find",
    )

    /** Pure: an HTTP status (and body) from a sell endpoint. */
    fun classifyHttp8144(code: Int, body: String?): Failure8144 {
        val b = body.orEmpty().lowercase()
        return when {
            code in 200..299 -> Failure8144.OK
            code == 401 || code == 403 -> Failure8144.HARD_AUTH
            code == 404 || code == 410 -> Failure8144.HARD_UNSUPPORTED_MINT
            code == 408 || code == 425 || code == 429 -> Failure8144.SOFT_RATE_LIMIT
            code in 400..499 -> if (UNSUPPORTED_MARKERS_8144.any { b.contains(it) }) Failure8144.HARD_UNSUPPORTED_MINT else Failure8144.HARD_BAD_REQUEST
            code >= 500 -> Failure8144.SOFT_SERVER
            else -> Failure8144.SOFT_TRANSPORT
        }
    }

    private val HTTP_CODE_8144 = Regex("HTTP (\\d{3})")

    /** Pure: the HTTP status inside a route exception message ("PumpPortal HTTP 400: ..."), or null. */
    fun httpCodeFrom8144(message: String?): Int? =
        HTTP_CODE_8144.find(message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** Pure: a route exception message. A local lockout never reached the endpoint and says nothing about it. */
    fun classifyMessage8144(message: String?): Failure8144? {
        val m = message.orEmpty()
        if (m.contains("LOCAL_LOCKOUT")) return null
        // An HTTP 200 carrying a JSON error is the endpoint refusing the request, not a success.
        if (m.contains("JSON error", ignoreCase = true)) return classifyHttp8144(400, m)
        val code = httpCodeFrom8144(m) ?: return Failure8144.SOFT_TRANSPORT
        return classifyHttp8144(code, m.substringAfter("HTTP $code", ""))
    }

    private class RouteState8144 {
        var attempts = 0
        var windowStartMs = 0L
        var hardUntilMs = 0L
        var hardCause: Failure8144? = null
        var hardVenue = ""
        var softUntilMs = 0L
        var softStreak = 0
    }

    private val perMint8144 = ConcurrentHashMap<String, RouteState8144>()
    private val globalBlockUntil8144 = ConcurrentHashMap<Route8144, Long>()

    private fun key(mint: String, route: Route8144) = "$mint|${route.name}"

    /** Non-consuming: would [route] be admitted for [mint] now. [venueKey] is the confirmed venue (see [admit8144]). */
    @Synchronized
    fun peek8144(mint: String, route: Route8144, nowMs: Long, venueKey: String = ""): Admit8144 {
        if ((globalBlockUntil8144[route] ?: 0L) > nowMs) return Admit8144.AUTH_BLOCKED
        val s = perMint8144[key(mint, route)] ?: return Admit8144.ALLOWED
        if (s.hardUntilMs > nowMs) {
            val venueChanged = venueKey.isNotBlank() && s.hardVenue.isNotBlank() && venueKey != s.hardVenue
            if (!venueChanged) return Admit8144.HARD_BLOCKED
        }
        if (s.softUntilMs > nowMs) return Admit8144.BACKOFF
        val windowLive = nowMs - s.windowStartMs <= ATTEMPT_WINDOW_MS_8144
        if (windowLive && s.attempts >= MAX_ATTEMPTS_PER_ROUTE_8144) return Admit8144.BUDGET_EXHAUSTED
        return Admit8144.ALLOWED
    }

    /**
     * Consuming: admit one attempt of [route] for [mint]. A hard block recorded under a different confirmed venue
     * ([venueKey], e.g. "PUMP_CURVE" then "PUMPSWAP") is lifted: the venue changed, so the endpoint may answer.
     */
    @Synchronized
    fun admit8144(mint: String, route: Route8144, nowMs: Long, venueKey: String = ""): Admit8144 {
        val verdict = peek8144(mint, route, nowMs, venueKey)
        if (verdict != Admit8144.ALLOWED) return verdict
        val s = perMint8144.getOrPut(key(mint, route)) { RouteState8144() }
        if (s.hardUntilMs > nowMs) { s.hardUntilMs = 0L; s.hardCause = null; s.hardVenue = "" }   // venue changed
        if (nowMs - s.windowStartMs > ATTEMPT_WINDOW_MS_8144) { s.windowStartMs = nowMs; s.attempts = 0 }
        s.attempts += 1
        trim8144(nowMs)
        return Admit8144.ALLOWED
    }

    /** Record how [route] answered [mint]. */
    @Synchronized
    fun record8144(mint: String, route: Route8144, failure: Failure8144, nowMs: Long, venueKey: String = "") {
        val s = perMint8144.getOrPut(key(mint, route)) { RouteState8144() }
        when {
            failure == Failure8144.OK -> {
                s.softStreak = 0; s.softUntilMs = 0L; s.hardUntilMs = 0L; s.hardCause = null; s.hardVenue = ""; s.attempts = 0
            }
            failure.hard -> {
                s.hardUntilMs = nowMs + HARD_BLOCK_MS_8144
                s.hardCause = failure
                s.hardVenue = venueKey
                if (failure.global) globalBlockUntil8144[route] = nowMs + AUTH_BLOCK_MS_8144
            }
            else -> {
                s.softStreak += 1
                s.softUntilMs = nowMs + softBackoffMs8144(s.softStreak)
            }
        }
    }

    /** Pure: 2 s, 4 s, 8 s, then 15 s cap after consecutive soft failures. */
    fun softBackoffMs8144(streak: Int): Long =
        (SOFT_BACKOFF_BASE_MS_8144 shl (streak - 1).coerceIn(0, 4)).coerceAtMost(SOFT_BACKOFF_CAP_MS_8144)

    /** The hard cause blocking (mint, route), or null. */
    @Synchronized
    fun hardCause8144(mint: String, route: Route8144, nowMs: Long): Failure8144? {
        if ((globalBlockUntil8144[route] ?: 0L) > nowMs) return Failure8144.HARD_AUTH
        val s = perMint8144[key(mint, route)] ?: return null
        return if (s.hardUntilMs > nowMs) s.hardCause else null
    }

    /** Pure: the venue confirmed by on-chain metadata. A complete curve has migrated (PumpSwap, or a Raydium pool). */
    fun venueFrom8144(curveAccountExists: Boolean?, curveComplete: Boolean?, raydiumPool: Boolean?, pumpSwapPool: Boolean?): Venue8144 = when {
        curveAccountExists == true && curveComplete == false -> Venue8144.PUMP_CURVE
        curveAccountExists == true && curveComplete == true ->
            if (raydiumPool == true && pumpSwapPool != true) Venue8144.RAYDIUM else Venue8144.PUMPSWAP
        pumpSwapPool == true -> Venue8144.PUMPSWAP
        raydiumPool == true -> Venue8144.RAYDIUM
        curveAccountExists == false -> Venue8144.AGGREGATOR_ONLY
        else -> Venue8144.UNKNOWN
    }

    /** Pure: route order for a confirmed venue. UNKNOWN keeps the legacy order (PumpPortal first). */
    fun routeOrder8144(venue: Venue8144): List<Route8144> = when (venue) {
        Venue8144.PUMP_CURVE, Venue8144.PUMPSWAP, Venue8144.UNKNOWN -> listOf(Route8144.PUMPPORTAL, Route8144.JUPITER, Route8144.RAYDIUM)
        Venue8144.RAYDIUM -> listOf(Route8144.RAYDIUM, Route8144.JUPITER, Route8144.PUMPPORTAL)
        Venue8144.AGGREGATOR_ONLY -> listOf(Route8144.JUPITER, Route8144.RAYDIUM, Route8144.PUMPPORTAL)
    }

    /** The first route of [venue]'s order that is admissible now and not in [exclude], or null. */
    fun nextRoute8144(mint: String, venue: Venue8144, nowMs: Long, exclude: Set<Route8144> = emptySet()): Route8144? =
        routeOrder8144(venue).firstOrNull { it !in exclude && peek8144(mint, it, nowMs, venue.name) == Admit8144.ALLOWED }

    /**
     * May the FIRST attempt go to PumpPortal: it is admissible and the confirmed venue does not put the aggregator
     * ahead of it (a mint with no pump curve sells through Jupiter first; PumpPortal stays available as the rescue).
     */
    fun pumpPortalFirst8144(mint: String, venue: Venue8144, nowMs: Long): Boolean =
        routeOrder8144(venue).first() == Route8144.PUMPPORTAL &&
            peek8144(mint, Route8144.PUMPPORTAL, nowMs, venue.name) == Admit8144.ALLOWED

    /**
     * Pure decision for an escalated / direct-first emergency: skipping the aggregator quote ladder is only safe while
     * a direct route can still answer — PumpPortal admissible on a venue it serves, or a confirmed Raydium pool.
     */
    fun directRouteViable8144(mint: String, venue: Venue8144, nowMs: Long): Boolean {
        if (venue == Venue8144.RAYDIUM) return peek8144(mint, Route8144.RAYDIUM, nowMs, venue.name) == Admit8144.ALLOWED
        if (venue == Venue8144.AGGREGATOR_ONLY) return false
        return peek8144(mint, Route8144.PUMPPORTAL, nowMs, venue.name) == Admit8144.ALLOWED
    }

    private fun trim8144(nowMs: Long) {
        if (perMint8144.size <= 4_000) return
        perMint8144.entries.removeIf { nowMs - maxOf(it.value.windowStartMs, it.value.hardUntilMs, it.value.softUntilMs) > HARD_BLOCK_MS_8144 }
    }

    fun statusLine8144(nowMs: Long = System.currentTimeMillis()): String {
        val hard = perMint8144.values.count { it.hardUntilMs > nowMs }
        val soft = perMint8144.values.count { it.softUntilMs > nowMs }
        val global = globalBlockUntil8144.entries.filter { it.value > nowMs }.joinToString(",") { it.key.name }.ifBlank { "-" }
        return "routes=${perMint8144.size} hardBlocked=$hard softBackoff=$soft authBlocked=$global"
    }

    internal fun resetForTest8144() { perMint8144.clear(); globalBlockUntil8144.clear() }
}
