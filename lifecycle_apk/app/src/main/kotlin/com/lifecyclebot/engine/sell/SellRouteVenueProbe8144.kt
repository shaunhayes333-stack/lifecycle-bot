package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.sell.SellRouteEndpoint8144.Venue8144
import com.lifecyclebot.network.SolanaWallet
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8144 — the venue a sell routes to, read from the chain instead of a label.
 *
 * MemeVenueRouter (7779) classified a mint from its "pump" suffix and the registry's source tag, so a pump mint that
 * graduated still read PUMP_FUN_BONDING and a non-pump mint was sent to PumpPortal first on every attempt. The pump.fun
 * bonding curve is the PDA ["bonding-curve", mint] (PumpCurveKeys7269.canonicalCurveKey7392); its account says whether
 * the curve exists and whether it is complete (byte 48, migrated). One bounded getAccountInfo (49 bytes) answers it;
 * the Raydium pool comes from RaydiumMintPools8128's non-blocking cache.
 *
 * Cached per mint: a complete curve never un-completes (kept), an active curve is re-read after 30 s, an absent curve
 * after 10 min. A read that does not answer in its budget proves nothing (UNKNOWN keeps the legacy route order).
 */
object SellRouteVenueProbe8144 {

    data class Probe8144(
        val venue: Venue8144,
        val curveExists: Boolean?,
        val curveComplete: Boolean?,
        val raydiumPool: Boolean?,
        val atMs: Long,
    )

    private const val ACTIVE_TTL_MS_8144 = 30_000L
    private const val ABSENT_TTL_MS_8144 = 10L * 60_000L
    private const val DEFAULT_BUDGET_MS_8144 = 1_500L
    private val cache8144 = ConcurrentHashMap<String, Probe8144>()
    private val inFlight8144 = ConcurrentHashMap.newKeySet<String>()
    private val executor8144: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "sellroute-venue-8144").apply { isDaemon = true } }
    }

    /** Pure: is a cached probe still usable at [nowMs]. */
    fun fresh8144(p: Probe8144, nowMs: Long): Boolean = when {
        p.curveComplete == true -> true
        p.curveExists == true -> nowMs - p.atMs <= ACTIVE_TTL_MS_8144
        p.curveExists == false -> nowMs - p.atMs <= ABSENT_TTL_MS_8144
        else -> false
    }

    /** The cached confirmed venue, UNKNOWN when none is fresh. Never blocks. */
    fun cached8144(mint: String, nowMs: Long = System.currentTimeMillis()): Venue8144 {
        val p = cache8144[mint] ?: return Venue8144.UNKNOWN
        return if (fresh8144(p, nowMs)) p.venue else Venue8144.UNKNOWN
    }

    /** Warm the cache off the sell thread (emergency trigger): the sell's own resolve then answers from it. */
    fun prewarm8144(wallet: SolanaWallet?, mint: String) {
        if (wallet == null || mint.isBlank()) return
        val p = cache8144[mint]
        if (p != null && fresh8144(p, System.currentTimeMillis())) return
        if (!inFlight8144.add(mint)) return
        try {
            executor8144.execute { try { read8144(wallet, mint) } finally { inFlight8144.remove(mint) } }
        } catch (_: Throwable) { inFlight8144.remove(mint) }
    }

    /** Resolve the venue within [budgetMs] (cache first); an unanswered read returns UNKNOWN. */
    fun resolve8144(wallet: SolanaWallet?, mint: String, budgetMs: Long = DEFAULT_BUDGET_MS_8144): Venue8144 {
        val now = System.currentTimeMillis()
        cache8144[mint]?.let { if (fresh8144(it, now)) return it.venue }
        if (wallet == null || mint.isBlank()) return Venue8144.UNKNOWN
        val fut = try {
            executor8144.submit(java.util.concurrent.Callable { read8144(wallet, mint) })
        } catch (_: Throwable) { return Venue8144.UNKNOWN }
        return try {
            fut.get(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)?.venue ?: Venue8144.UNKNOWN
        } catch (_: Throwable) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_ROUTE_VENUE_PROBE_UNANSWERED_8144") } catch (_: Throwable) {}
            Venue8144.UNKNOWN
        }
    }

    /**
     * V5.0.8153 — pure: the venue an exit routes by without waiting. A fresh probe answers; a stale probe that had
     * confirmed a venue still answers (the venue only orders the routes — every route keeps its endpoint policy and
     * failover, and a migrated curve's PumpPortal 4xx falls through to the aggregator) and asks for a refresh; no
     * confirmed venue at all needs a read. Returns (venue, refresh-or-read needed).
     */
    fun exitVenue8153(p: Probe8144?, nowMs: Long): Pair<Venue8144, Boolean> = when {
        p == null -> Venue8144.UNKNOWN to true
        fresh8144(p, nowMs) -> p.venue to false
        p.venue != Venue8144.UNKNOWN -> p.venue to true
        else -> Venue8144.UNKNOWN to true
    }

    /** V5.0.8153 — pure: does [p] need a background refresh so it is still fresh [aheadMs] from now. */
    fun needsRefresh8153(p: Probe8144?, nowMs: Long, aheadMs: Long): Boolean = p == null || !fresh8144(p, nowMs + aheadMs.coerceAtLeast(0L))

    /**
     * V5.0.8153 — the venue of a live exit (tryPumpPortalSell; was a blocking 1.5 s read per attempt on a cold cache).
     * Fresh or last-confirmed probe: answered at once (a stale one is refreshed off the sell thread). Never confirmed:
     * one read bounded at StopLatencyCore8153.EXIT_VENUE_BUDGET_MS_8153 (UNKNOWN keeps the legacy route order).
     */
    fun resolveForExit8153(wallet: SolanaWallet?, mint: String, nowMs: Long = System.currentTimeMillis()): Venue8144 {
        val (venue, needsRead) = exitVenue8153(cache8144[mint], nowMs)
        if (!needsRead) return venue
        if (venue != Venue8144.UNKNOWN) {
            refreshNow8153(wallet, mint)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_ROUTE_VENUE_LAST_CONFIRMED_USED_8153") } catch (_: Throwable) {}
            return venue
        }
        return resolve8144(wallet, mint, StopLatencyCore8153.EXIT_VENUE_BUDGET_MS_8153)
    }

    /**
     * V5.0.8153 — background prewarm: re-read the curve of an open live position before its probe goes stale, on
     * [lane] (the low-priority prewarm lane), single-flight per mint. True when a read was started.
     */
    fun refreshAhead8153(wallet: SolanaWallet?, mint: String, nowMs: Long, aheadMs: Long, lane: java.util.concurrent.Executor): Boolean {
        if (wallet == null || mint.isBlank()) return false
        if (!needsRefresh8153(cache8144[mint], nowMs, aheadMs)) return false
        if (!inFlight8144.add(mint)) return false
        return try {
            lane.execute { try { read8144(wallet, mint) } finally { inFlight8144.remove(mint) } }
            true
        } catch (_: Throwable) { inFlight8144.remove(mint); false }
    }

    /** V5.0.8153 — a stale probe: re-read off the sell thread (single-flight), whatever its age. */
    private fun refreshNow8153(wallet: SolanaWallet?, mint: String) {
        if (wallet == null || mint.isBlank()) return
        if (!inFlight8144.add(mint)) return
        try {
            executor8144.execute { try { read8144(wallet, mint) } finally { inFlight8144.remove(mint) } }
        } catch (_: Throwable) { inFlight8144.remove(mint) }
    }

    /** One bounded curve read; stores and returns the probe, or null when the RPC did not answer. */
    private fun read8144(wallet: SolanaWallet, mint: String): Probe8144? {
        val curve = try { com.lifecyclebot.network.PumpCurveKeys7269.canonicalCurveKey7392(mint) } catch (_: Throwable) { null }
        var exists: Boolean? = null
        var complete: Boolean? = null
        if (curve != null) {
            try {
                val cfg = org.json.JSONObject()
                    .put("encoding", "base64")
                    .put("commitment", "confirmed")
                    .put("dataSlice", org.json.JSONObject().put("offset", 0).put("length", 49))
                val resp = wallet.rpcCall("getAccountInfo", org.json.JSONArray().put(curve).put(cfg))
                if (resp.has("error")) return null
                val result = resp.optJSONObject("result") ?: return null
                if (result.isNull("value")) {
                    exists = false
                } else {
                    val data = result.optJSONObject("value")?.optJSONArray("data")
                    val b64 = data?.optString(0, "").orEmpty()
                    val bytes = if (b64.isBlank()) null else android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    exists = true
                    complete = if (bytes != null && bytes.size >= 49) (bytes[48].toInt() and 0xFF) != 0 else null
                }
            } catch (_: Throwable) { return null }
        }
        val raydium: Boolean? = try { if (com.lifecyclebot.network.RaydiumMintPools8128.cached8128(mint) != null) true else null } catch (_: Throwable) { null }
        val venue = SellRouteEndpoint8144.venueFrom8144(exists, complete, raydium, null)
        val probe = Probe8144(venue, exists, complete, raydium, System.currentTimeMillis())
        cache8144[mint] = probe
        if (complete == true) try { com.lifecyclebot.network.PumpCurveKeys7269.markGraduated7392(mint) } catch (_: Throwable) {}
        if (cache8144.size > 3_000) {
            val now = System.currentTimeMillis()
            cache8144.entries.removeIf { !fresh8144(it.value, now) }
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_ROUTE_VENUE_${venue.name}_8144")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "SELL_ROUTE_VENUE_CONFIRMED_8144",
                "mint=${mint.take(10)} venue=${venue.name} curveExists=$exists curveComplete=$complete raydiumPool=$raydium source=onchain_curve_account",
            )
        } catch (_: Throwable) {}
        return probe
    }

    internal fun putForTest8144(mint: String, probe: Probe8144) { cache8144[mint] = probe }
    internal fun resetForTest8144() { cache8144.clear(); inFlight8144.clear() }
}
