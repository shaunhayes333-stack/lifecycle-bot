package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.9.495z48 — operator P0 (Message 472):
 * "If the bot can't price a wallet token, stop-losses and trailing stops
 *  fail silently."
 *
 * Single canonical price-resolver fallback chain:
 *   1. DexScreener (latest pair priceUsd)         — fastest, broadest
 *   2. GeckoTerminal (token-info v2)              — covers tokens DexScreener misses
 *   3. Jupiter (1-token-→-SOL quote × SOL/USD)    — works whenever a route exists
 *   4. HostWalletTokenTracker cached lastPrice    — last good observation
 *   5. HostWalletTokenTracker.entryPriceUsd       — better than 0 for SL math
 *   6. null — caller must treat as UNKNOWN, NEVER 0.
 *
 * Each successful fetch is also written back into a small in-process cache
 * so a brief multi-source outage doesn't spike SL/TP false positives.
 */
object PriceResolverFallback {

    private const val TAG = "PriceResolverFallback"

    /** Last successful price + ts, keyed by mint. Survives until process death. */
    private data class Cached(val priceUsd: Double, val source: String, val tsMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val httpClient = SharedHttpClient.builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    /** V5.0.6894 — one instance so DexscreenerApi's 45s pairCache actually
     *  survives between resolves. See the note at the DexScreener step. */
    private val sharedDexscreener6894 by lazy { DexscreenerApi() }

    enum class Source {
        DEXSCREENER, GECKOTERMINAL, JUPITER, CACHED, ENTRY, UNKNOWN,
        // V5.0.6914 — three additional keyless sources. See §6914 below.
        JUPITER_PRICE, RAYDIUM, PUMPFUN,
    }

    data class Resolved(val priceUsd: Double, val source: Source)

    /**
     * V5.0.6914 §COVERAGE_AND_ORDER_ARE_BOTH_THE_PROBLEM.
     *
     * OPERATOR EVIDENCE (5.0.6911 outage snapshot):
     *
     *   dexscreener    sr=  0%  s=0  4xx=10  5xx=62     <- scanner-critical, dead
     *   dexpaprika     sr=  0%  5xx=30, HTTP 402         <- now a paid product
     *   birdeye        sr=  0%  401                      <- key dead
     *   geckoterminal  sr= 66%
     *   jupiter        sr=100%  ·  pumpfun sr=90%
     *
     * Operator: "we need better free reliable token source coverage."
     *
     * Two separate defects, both fixed here.
     *
     * COVERAGE. This chain had three live sources: DexScreener, GeckoTerminal,
     * and a Jupiter *quote* probe. When DexScreener died and GeckoTerminal sat
     * at 66%, everything funnelled onto the quote probe — the most expensive
     * option in the set, because it needs a route to exist AND the token's
     * decimals to be right (it guesses 6 when unknown, and the archive reports
     * decimals known 0/2518). Three more keyless sources are added:
     *
     *   JUPITER_PRICE  lite-api.jup.ag/price/v3   — jupiter is the healthiest
     *                  host in the fleet at 100%, and its direct price endpoint
     *                  needs no route simulation and no decimals. It was
     *                  already used by PriceAggregator/AlternativeOracles and
     *                  simply never reached the meme sell path.
     *   RAYDIUM        api-v3.raydium.io/mint/price — native #1 Solana DEX,
     *                  best long-tail coverage for graduated memes. Also
     *                  already in the repo (PriceAggregator §6065), also only
     *                  on the perps path.
     *   PUMPFUN        frontend-api-v3.pump.fun/coins/<mint> — authoritative
     *                  for PRE-graduation bonding-curve tokens, which are the
     *                  majority of this bot's intake (306 of 519 this session
     *                  came from PUMP_PORTAL_WS). DexScreener frequently has
     *                  no pair at all for these, so this covers the exact gap
     *                  the other sources cannot.
     *
     * Every URL here is one this repository already calls in another code
     * path, with the same parse shape, rather than an endpoint invented from
     * memory — this environment's egress policy denies these hosts (403 on
     * CONNECT) so they could not be probed live from the build host.
     *
     * ORDER. The chain was a hardcoded sequence with DexScreener first,
     * unconditionally. During its outage every single resolve paid a full
     * failing round-trip to a host known to be at 0% before falling through —
     * per position, per tick. Health data to avoid that already existed in
     * ApiHealthMonitor and nothing consulted it here.
     *
     * Sources are now tried in descending measured health, with
     * circuit-broken hosts demoted behind everything else rather than
     * removed: a breaker can be wrong, and a demoted source is still tried
     * before the chain gives up and returns a cached or entry price. An
     * unsampled host reports 1.0 (see ApiHealthMonitor.successRate) so a
     * provider that has not been called yet is optimistically ranked, which is
     * what lets a fresh boot discover the fleet instead of freezing an order.
     */
    private data class Candidate6914(
        val source: Source,
        val host: String,
        val label: String,
        val fetch: () -> Double,
    )

    /** Health score used for ordering. Circuit-broken hosts are demoted. */
    private fun healthScore6914(host: String): Double {
        if (host.isBlank()) return 1.0
        return try {
            val broken = com.lifecyclebot.engine.ApiHealthMonitor.isCircuitBroken(host)
            val sr = com.lifecyclebot.engine.ApiHealthMonitor.successRate(host)
            if (broken) sr - 10.0 else sr
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * @param mint            token mint (Solana)
     * @param solUsdHint      latest SOL/USD price (used by Jupiter step). Pass
     *                        `WalletManager.lastKnownSolPrice` from caller.
     * @return null if every fallback failed. The caller MUST treat null as
     *         UNKNOWN and skip exit gates, not as 0.
     */
    fun resolve(mint: String, solUsdHint: Double): Resolved? {
        if (mint.isBlank()) return null

        // V5.0.6914 — six keyless sources, tried in descending measured health.
        // The DexScreener step keeps the V5.0.6894 shared instance so its 45s
        // pairCache survives between resolves (a fresh instance per call threw
        // the cache away and re-hit the network for a mint it had just priced).
        val candidates6914 = listOf(
            Candidate6914(Source.DEXSCREENER, "dexscreener", "DEXSCREENER") {
                sharedDexscreener6894.getBestPair(mint)?.candle?.priceUsd ?: 0.0
            },
            Candidate6914(Source.JUPITER_PRICE, "jupiter", "JUPITER_PRICE") {
                fetchJupiterLitePrice6914(mint)
            },
            Candidate6914(Source.RAYDIUM, "raydium", "RAYDIUM") {
                fetchRaydiumPrice6914(mint)
            },
            Candidate6914(Source.PUMPFUN, "pumpfun", "PUMPFUN") {
                fetchPumpFunPrice6914(mint)
            },
            Candidate6914(Source.GECKOTERMINAL, "geckoterminal", "GECKOTERMINAL") {
                fetchGeckoTerminalPrice(mint)
            },
            // Quote-derived is intentionally last among the live sources: it is
            // the only one that needs a route to exist AND the token's decimals
            // to be correct, and it guesses 6 when they are unknown.
            Candidate6914(Source.JUPITER, "jupiter_quote", "JUPITER") {
                if (solUsdHint > 0.0) fetchJupiterDerivedPrice(mint, solUsdHint) else 0.0
            },
        ).sortedByDescending { healthScore6914(it.host) }

        for (c in candidates6914) {
            try {
                val price = c.fetch()
                if (price > 0.0 && price.isFinite()) {
                    cache[mint] = Cached(price, c.label, System.currentTimeMillis())
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector
                            .labelInc("PRICE_FALLBACK_RESOLVED_6914_${c.label}")
                    } catch (_: Throwable) {}
                    return Resolved(price, c.source)
                }
            } catch (_: Throwable) { /* try the next source */ }
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector
                .labelInc("PRICE_FALLBACK_ALL_LIVE_SOURCES_FAILED_6914")
        } catch (_: Throwable) {}

        // 4. In-process cache (last good)
        cache[mint]?.let { c ->
            if (c.priceUsd > 0.0) return Resolved(c.priceUsd, Source.CACHED)
        }

        // 5. HostWalletTokenTracker entry price (last resort, prevents SL silent-fail)
        try {
            val tracked = HostWalletTokenTracker.getEntry(mint)
            val cached = tracked?.currentPriceUsd?.takeIf { it > 0.0 }
                ?: tracked?.entryPriceUsd?.takeIf { it > 0.0 }
            if (cached != null) {
                return Resolved(cached, Source.ENTRY)
            }
        } catch (_: Throwable) { /* fall through */ }

        ErrorLogger.warn(TAG, "all price sources failed for ${mint.take(8)}… — UNKNOWN")
        return null
    }

    /**
     * GeckoTerminal token endpoint. Free, no API key, ~150ms.
     * https://api.geckoterminal.com/api/v2/networks/solana/tokens/<mint>
     */
    private fun fetchGeckoTerminalPrice(mint: String): Double {
        val request = Request.Builder()
            .url("https://api.geckoterminal.com/api/v2/networks/solana/tokens/$mint")
            // V5.9.1567 — GECKO HEADER FIX. Plain `Accept: application/json`
            // was returning 4xx ~47% of the time. GeckoTerminal v2 requires
            // (or strongly prefers) the version-pinned Accept header and a
            // real User-Agent; many Android-default UAs get 403/406'd. Adding
            // both lifts the sell-side fallback price-resolver hit rate so
            // tick-time exits don't fall through to Jupiter probes.
            .header("Accept", "application/json;version=20230302")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return 0.0
            val body = response.body?.string() ?: return 0.0
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return 0.0
            val attrs = data.optJSONObject("attributes") ?: return 0.0
            // GeckoTerminal returns price_usd as a string.
            val priceStr = attrs.optString("price_usd", "")
            return priceStr.toDoubleOrNull() ?: 0.0
        }
    }

    /**
     * Jupiter quote-derived price. Assumes 6 decimals as a starting probe is
     * fine because we only need a ratio (out / in). Uses HostWalletTokenTracker
     * to read decimals when known; otherwise probes with 6.
     */
    private fun fetchJupiterDerivedPrice(mint: String, solUsdHint: Double): Double {
        val tracked = try { HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null }
        val decimals = tracked?.decimals?.takeIf { it > 0 } ?: 6
        // Quote 1 whole token (10^decimals) → SOL.
        val oneToken = java.math.BigInteger.TEN.pow(decimals).toLong()
        val quote = try {
            JupiterApi().getQuote(mint, JupiterApi.SOL_MINT, oneToken, 100)
        } catch (_: Throwable) { return 0.0 }
        // outAmount is in lamports (SOL has 9 decimals). Convert to SOL, then USD.
        val solOut = quote.outAmount / 1_000_000_000.0
        if (solOut <= 0.0) return 0.0
        return solOut * solUsdHint
    }

    /**
     * V5.0.6914 — shared request/health wrapper for the new keyless sources.
     *
     * Every new source records into ApiHealthMonitor under its own host name.
     * That is what makes the health ordering above self-correcting: a source
     * whose endpoint is wrong or whose upstream dies simply accumulates
     * failures and sinks to the back of the chain, and shows up as a dead
     * provider in the operator's API health table instead of silently costing
     * a round-trip forever. It also means a bad URL degrades this resolver
     * rather than breaking it.
     */
    /**
     * V5.0.6969 §I_PUT_A_LIVE_WALLET_PATH_ON_A_1HZ_LOOP_WITHOUT_A_RATE_BUDGET.
     *
     * This chain had exactly one caller (LiveWalletReconciler) until V5.0.6946
     * wired it into openPositionTickLoop, and V5.0.6958 then raised its per-tick
     * cap from 8 mints to 24 whenever exit pressure is high — which, with 80 open
     * positions, is always. Neither change added a rate budget, so a chain built
     * for occasional wallet reconciliation started running at up to 24 mints per
     * second against free keyless endpoints.
     *
     * The operator's 5.0.6967 table: jupiter at 669 calls in 213 seconds, 3.1/sec
     * sustained. That is my regression. I fixed geckoterminal's identical problem
     * in V5.0.6944 with exactly this governor and did not apply it here.
     *
     * 300ms minimum interval per host (~3.3/sec) — enough to keep marks flowing
     * for 80 positions, low enough that a free endpoint will serve them. Callers
     * arriving inside the window get null immediately rather than queueing: this
     * is best-effort enrichment on a 1Hz loop, so a skipped fetch costs one tick
     * of staleness, whereas a queue would pile coroutines behind a shared lock on
     * the hot path.
     */
    private val lastHostCallMs6969 = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private const val HOST_MIN_INTERVAL_MS_6969 = 300L

    private fun hostPaceOpen6969(host: String): Boolean {
        return try {
            val cell = lastHostCallMs6969.computeIfAbsent(host) { java.util.concurrent.atomic.AtomicLong(0L) }
            val now = System.currentTimeMillis()
            val prev = cell.get()
            if (now - prev < HOST_MIN_INTERVAL_MS_6969) false
            else cell.compareAndSet(prev, now)
        } catch (_: Throwable) { true }
    }

    private fun getJson6914(host: String, url: String): JSONObject? {
        if (!hostPaceOpen6969(host)) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("KEYLESS_HOST_PACED_6969_${host.uppercase()}")
            } catch (_: Throwable) {}
            return null
        }
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8)")
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                try {
                    // V5.0.6969 §WE_COUNTED_OUR_OWN_BLOCKS_AGAINST_THE_PROVIDER.
                    //
                    // HostCircuitInterceptor short-circuits a locked-out host by
                    // returning a SYNTHETIC 599 without touching the network.
                    // This line recorded resp.code unconditionally, so every call
                    // the bot declined to make was filed in ApiHealthMonitor as a
                    // provider 5xx.
                    //
                    // The operator's 5.0.6967 table shows the signature exactly:
                    //     jupiter  sr=16%  avg=1ms  s=110  5xx=559
                    // A real 5xx from a remote server cannot return in 1ms. Those
                    // 559 "failures" are this app's own circuit breaker, and
                    // meanwhile KeyValidator reports jupiter live=true http=200
                    // JUPITER_HEALTHY — the endpoint was fine the whole time.
                    //
                    // It is self-reinforcing and it reaches routing: the false
                    // failures collapse successRate, LiveProviderQuorum.hostHealthy
                    // requires >= 0.45, so a host the bot merely throttled gets
                    // dropped from the quorum as if the provider had died.
                    //
                    // A synthetic block is now skipped entirely. Absence of data
                    // is the honest record for a call that never happened.
                    if (com.lifecyclebot.network.HostCircuitInterceptor.isSyntheticBlock(resp)) {
                        com.lifecyclebot.engine.PipelineHealthCollector
                            .labelInc("PROVIDER_SELF_BLOCKED_NOT_COUNTED_6969_${host.uppercase()}")
                    } else {
                        com.lifecyclebot.engine.ApiHealthMonitor
                            .record(host, resp.code, System.currentTimeMillis() - started)
                    }
                } catch (_: Throwable) {}
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                if (body.isBlank()) return null
                JSONObject(body)
            }
        } catch (e: Throwable) {
            try {
                com.lifecyclebot.engine.ApiHealthMonitor.recordNetworkError(host, e.message)
            } catch (_: Throwable) {}
            null
        }
    }

    /**
     * V5.0.6914 — Jupiter Price v3, keyless. Shape mirrors
     * PriceAggregator.fetchJupiterLite exactly, including the v3/v2 tolerance:
     *   v3: {"<mint>":{"usdPrice":1.23,...}}         (unwrapped, numeric)
     *   v2: {"data":{"<mint>":{"price":"1.23",...}}}  (wrapped, string)
     * No route simulation and no decimals needed, which is why it is preferred
     * over the quote probe.
     */
    private fun fetchJupiterLitePrice6914(mint: String): Double {
        val json = getJson6914("jupiter", "https://lite-api.jup.ag/price/v3?ids=$mint") ?: return 0.0
        val obj = json.optJSONObject(mint)
            ?: json.optJSONObject("data")?.optJSONObject(mint)
            ?: return 0.0
        val v3 = obj.optDouble("usdPrice", Double.NaN)
        if (v3.isFinite() && v3 > 0.0) return v3
        return obj.optString("price", "0").toDoubleOrNull() ?: 0.0
    }

    /**
     * V5.0.6914 — Raydium v3 keyless mint price. Shape mirrors
     * PriceAggregator.fetchRaydiumV3 (§6065): {"data":{"<mint>":"1.23"}}.
     * Native #1 Solana DEX, so this is the best long-tail coverage for
     * graduated memes that DexScreener has not indexed yet.
     */
    private fun fetchRaydiumPrice6914(mint: String): Double {
        val json = getJson6914("raydium", "https://api-v3.raydium.io/mint/price?mints=$mint") ?: return 0.0
        val data = json.optJSONObject("data") ?: return 0.0
        return data.optString(mint, "0").toDoubleOrNull() ?: 0.0
    }

    /**
     * V5.0.6914 — pump.fun frontend, keyless. Authoritative for PRE-graduation
     * bonding-curve tokens, which DexScreener often has no pair for at all.
     *
     * UNIT NOTE, carried verbatim from the existing BotService derivation so
     * the two agree: pump.fun's own `price` field is denominated in SOL, not
     * USD, so USD price must be derived as usd_market_cap / total_supply. That
     * derivation is already live in this codebase and already writes
     * ts.lastPrice under source PUMP_FUN_FRONTEND_API, so reusing it keeps one
     * definition rather than introducing a second.
     *
     * V5.0.7017 — "reusing the existing derivation" reused its bug: total_supply
     * is raw base units, not whole tokens. Both sites now call
     * PumpFunPriceUnits7017, which resolves the scaling explicitly.
     */
    private fun fetchPumpFunPrice6914(mint: String): Double {
        // V5.0.7100 §ASK_PUMPFUN_ONLY_ABOUT_PUMPFUN_MINTS — see BotService's
        // sibling call site. frontend-api-v3/coins/<mint> can only answer for a
        // pump.fun mint; for any other mint a 404 is the correct reply and the
        // request is spent for nothing, against a host the device reports at
        // sr=17% with 306 4xx. One predicate for one fact: V5.0.7089's
        // isPumpFunMint, not a second copy of it.
        val pumpFunMint7100 = try {
            com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(mint)
        } catch (_: Throwable) { false }
        if (!pumpFunMint7100) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("PUMPFUN_PRICE_SKIPPED_NOT_PUMPFUN_MINT_7100")
            } catch (_: Throwable) {}
            return 0.0
        }
        val original = "https://frontend-api-v3.pump.fun/coins/$mint"
        val url = try {
            com.lifecyclebot.engine.AutoEndpointMigrator.rewrite(original)
        } catch (_: Throwable) { original }
        val json = getJson6914("pumpfun", url) ?: return 0.0
        // V5.0.7017 — one derivation, shared with BotService. Both sites had the
        // same unit bug (raw base units treated as whole tokens); keeping the
        // arithmetic in one object is what stops them diverging on the fix the
        // way they agreed on the mistake.
        return com.lifecyclebot.engine.PumpFunPriceUnits7017.priceUsd(json)
    }

    /** Test/diagnostic accessor: snapshot of in-memory cache. */
    fun cacheSnapshot(): Map<String, Pair<Double, String>> =
        cache.mapValues { (_, c) -> c.priceUsd to c.source }
}
