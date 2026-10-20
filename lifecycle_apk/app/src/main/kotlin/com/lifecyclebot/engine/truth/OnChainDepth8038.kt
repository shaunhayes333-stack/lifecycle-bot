package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8038 — the pool, read from the chain.
 *
 * Owner: "ensure we use blockchain validation where possible". Supply (OnChainSupplyAuthority7075), mint / freeze
 * authority (OnChainMintAuthorityProof7248), holders (OnChainHolderConcentration7379), pump.fun curve reserves
 * (CurveReserves7951), pool age (PoolCreationTime7385) and fill cost (OnChainCost8024) were already chain truth.
 * An AMM pool's depth and price were not: they came from aggregator APIs — the $204M USDP cap on a thin pool, and
 * VOCAT's $0 liquidity (8037), were both API numbers.
 *
 * A constant-product pool's state IS its two vault token accounts. The pool account names them (decoded by its
 * owner program: PumpSwap, Raydium AMM v4, Raydium CPMM), and one getMultipleAccounts (jsonParsed) returns both
 * balances with their mints. A read is accepted only when one vault holds THIS coin and the other wrapped SOL — a
 * wrong pool or an unknown layout yields no reading, never a wrong one. From it:
 *   depth  = 2 x the SOL vault (both sides, the convention the impact model uses) x SOL/USD;
 *   price  = SOL vault / coin vault x SOL/USD.
 *
 * Used: LiquidityTruth8037 prefers a fresh chain depth over any API figure; LiveEdgeGate asks for a read on live
 * candidates (async); the live buy door ([buyRefusal8038], before any lease or quote) verifies the pool on chain and
 * refuses when the API depth is [CONTRADICTION_MULT_8038]x+ the chain's (LIQUIDITY_CONTRADICTED_ONCHAIN_8038) or
 * the chain price is [PRICE_DROP_TOLERANCE_8038] below the price the bot is buying on (PRICE_COLLAPSED_ONCHAIN_8038
 * — a drained or rugged pool behind a stale quote). A pool the chain cannot confirm is not refused (curve coins,
 * other venues, RPC down): the coin's other gates decide it, and the diag counts it as unverified.
 */
object OnChainDepth8038 {
    const val PUMP_AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    const val RAYDIUM_V4 = "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"
    const val RAYDIUM_CPMM = "CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C"
    const val WSOL = "So11111111111111111111111111111111111111112"
    private const val CONTRADICTION_MULT_8038 = 4.0
    private const val PRICE_DROP_TOLERANCE_8038 = 0.40
    private const val FRESH_MS = 90_000L
    private const val UNSUPPORTED_RETRY_MS = 30L * 60_000L
    private const val BLOCKING_TIMEOUT_S = 3L
    private val BASE58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    data class Read(val pool: String, val quoteSol: Double, val baseTokens: Double, val atMs: Long)

    private val vaultsOf = ConcurrentHashMap<String, Pair<String, String>>()     // pool -> vaults
    private val unsupported = ConcurrentHashMap<String, Long>()                  // pool -> at
    private val reads = ConcurrentHashMap<String, Read>()                        // mint -> read
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val apiBeforeChain = ConcurrentHashMap<String, Double>()            // mint -> API depth the chain replaced
    private val verified = AtomicLong(0)
    private val unverified = AtomicLong(0)
    private val liqContradicted = AtomicLong(0)
    private val priceCollapsed = AtomicLong(0)
    private val fetched = AtomicLong(0)
    private val failed = AtomicLong(0)

    // ── pure ──

    /** Byte offsets of the two vault pubkeys in a pool account owned by [owner], or null for an unknown layout. */
    fun vaultOffsets8038(owner: String): Pair<Int, Int>? = when (owner) {
        PUMP_AMM -> 139 to 171          // disc 8, bump u8, index u16, creator, base_mint, quote_mint, lp_mint, base vault, quote vault
        RAYDIUM_V4 -> 336 to 368        // 32 u64 + swap u128/u64 block (336), base vault, quote vault
        RAYDIUM_CPMM -> 72 to 104       // disc 8, amm_config, pool_creator, token_0_vault, token_1_vault
        else -> null
    }

    /** Pure: the vault keys of a pool account, or null. */
    fun vaultKeys8038(owner: String, data: ByteArray): Pair<String, String>? {
        val (a, b) = vaultOffsets8038(owner) ?: return null
        if (data.size < maxOf(a, b) + 32) return null
        return try {
            io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(a, a + 32)) to
                io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(b, b + 32))
        } catch (_: Throwable) { null }
    }

    /** Pure: (SOL in the pool, coin tokens in the pool) from two vaults' (mint, ui amount), or null if not coin/WSOL. */
    fun sides8038(mint: String, a: Pair<String, Double>?, b: Pair<String, Double>?): Pair<Double, Double>? {
        if (a == null || b == null) return null
        val sol = listOf(a, b).firstOrNull { it.first == WSOL } ?: return null
        val coin = listOf(a, b).firstOrNull { it.first == mint } ?: return null
        if (!(sol.second > 0.0) || !(coin.second > 0.0)) return null
        return sol.second to coin.second
    }

    fun depthUsd8038(quoteSol: Double, solUsd: Double): Double =
        if (quoteSol > 0.0 && solUsd > 0.0) 2.0 * quoteSol * solUsd else Double.NaN

    fun priceUsd8038(quoteSol: Double, baseTokens: Double, solUsd: Double): Double =
        if (quoteSol > 0.0 && baseTokens > 0.0 && solUsd > 0.0) quoteSol / baseTokens * solUsd else Double.NaN

    /** The API claims [CONTRADICTION_MULT_8038]x+ the depth the chain holds. */
    fun liquidityContradicted8038(apiUsd: Double, chainUsd: Double): Boolean =
        apiUsd.isFinite() && chainUsd.isFinite() && apiUsd > 0.0 && chainUsd > 0.0 && apiUsd >= CONTRADICTION_MULT_8038 * chainUsd

    /** The chain price is [PRICE_DROP_TOLERANCE_8038]+ below the price the bot is about to buy on. */
    fun priceCollapsed8038(buyPriceUsd: Double, chainPriceUsd: Double): Boolean =
        buyPriceUsd.isFinite() && chainPriceUsd.isFinite() && buyPriceUsd > 0.0 && chainPriceUsd > 0.0 &&
            chainPriceUsd < buyPriceUsd * (1.0 - PRICE_DROP_TOLERANCE_8038)

    // ── reads ──

    private fun solUsd(): Double = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }

    private fun fresh(mint: String, nowMs: Long): Read? = reads[mint]?.takeIf { nowMs - it.atMs in 0L..FRESH_MS }

    /** LiquidityTruth8037: the API depth a chain read is about to replace, kept for the buy door's comparison. */
    fun noteApi8038(mint: String, apiUsd: Double) {
        if (mint.isBlank() || !(apiUsd > 0.0)) return
        apiBeforeChain[mint] = apiUsd
        if (apiBeforeChain.size > 3_000) apiBeforeChain.keys.take(300).forEach { apiBeforeChain.remove(it) }
    }

    /** V5.0.8198 — the chain-confirmed vault read of [mint]'s pool when at most [maxAgeMs] old, else null. Never requests. */
    fun freshRead8198(mint: String, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Read? =
        reads[mint]?.takeIf { nowMs - it.atMs in 0L..maxAgeMs && it.quoteSol > 0.0 && it.baseTokens > 0.0 }

    /** LiquidityTruth8037: the fresh on-chain depth (USD, both sides) of [mint]'s pool, or NaN. Never requests. */
    fun chainDepthUsd8038(mint: String, nowMs: Long = System.currentTimeMillis()): Double =
        fresh(mint, nowMs)?.let { depthUsd8038(it.quoteSol, solUsd()) } ?: Double.NaN

    private fun pools(ts: TokenState): List<String> =
        listOf(ts.lastPricePoolAddr, ts.pairAddress, ts.tokenMap.poolAddress, ts.tokenMap.pairAddress)
            .map { it.trim() }.filter { BASE58.matches(it) && it != ts.mint }.distinct()
            .filter { p -> unsupported[p]?.let { System.currentTimeMillis() - it > UNSUPPORTED_RETRY_MS } ?: true }

    /** Ask for a read of [ts]'s pool in the background (LiveEdgeGate, exits). Returns immediately. */
    fun request8038(ts: TokenState) {
        val mint = ts.mint
        if (mint.isBlank() || fresh(mint, System.currentTimeMillis()) != null || !inFlight.add(mint)) return
        val ps = pools(ts)
        if (ps.isEmpty()) { inFlight.remove(mint); return }
        try {
            pool.execute { try { fetch(mint, ps) } finally { inFlight.remove(mint) } }
        } catch (_: Throwable) { inFlight.remove(mint) }
    }

    private val pool: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
            { r -> Thread(r, "onchain-depth-8038").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(BLOCKING_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(BLOCKING_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    private fun rungs(): List<String> = try {
        com.lifecyclebot.engine.RuntimeProviderAuthority6685.rpcCandidates().take(2)
    } catch (_: Throwable) { emptyList() }

    private fun rpc(method: String, params: JSONArray): JSONObject? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 8038).put("method", method).put("params", params).toString()
        for (url in rungs()) {
            val label = if (url.contains("helius", true)) "helius" else "solana_rpc"
            try {
                val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
                com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, label).use { r ->
                    if (r.header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969) != null || !r.isSuccessful) return@use null
                    val j = try { JSONObject(r.body?.string().orEmpty()) } catch (_: Throwable) { null }
                    if (j == null || j.has("error")) null else j.optJSONObject("result")
                }?.let { return it }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun vaultsFor(poolAddr: String): Pair<String, String>? {
        vaultsOf[poolAddr]?.let { return it }
        val res = rpc("getAccountInfo", JSONArray().put(poolAddr).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed"))) ?: return null
        val v = res.optJSONObject("value") ?: run { unsupported[poolAddr] = System.currentTimeMillis(); return null }
        val owner = v.optString("owner")
        val data = try { android.util.Base64.decode(v.optJSONArray("data")?.optString(0).orEmpty(), android.util.Base64.DEFAULT) } catch (_: Throwable) { null }
        val keys = data?.let { vaultKeys8038(owner, it) } ?: run { unsupported[poolAddr] = System.currentTimeMillis(); return null }
        vaultsOf[poolAddr] = keys
        if (vaultsOf.size > 3_000) vaultsOf.keys.take(300).forEach { vaultsOf.remove(it) }
        return keys
    }

    private fun parsedSide(acct: JSONObject?): Pair<String, Double>? {
        val info = acct?.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: return null
        val amt = info.optJSONObject("tokenAmount")?.optString("uiAmountString")?.toDoubleOrNull() ?: return null
        return info.optString("mint") to amt
    }

    private fun fetch(mint: String, poolAddrs: List<String>): Read? {
        for (p in poolAddrs.take(2)) {
            val (a, b) = vaultsFor(p) ?: continue
            val res = rpc("getMultipleAccounts", JSONArray().put(JSONArray().put(a).put(b)).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
            val arr = res?.optJSONArray("value")
            if (arr == null) { failed.incrementAndGet(); continue }
            val sides = sides8038(mint, parsedSide(arr.optJSONObject(0)), parsedSide(arr.optJSONObject(1)))
            if (sides == null) { unsupported[p] = System.currentTimeMillis(); continue }
            val read = Read(p, sides.first, sides.second, System.currentTimeMillis())
            reads[mint] = read
            CandidateAdmission8136.evidenceArrived8252(mint, setOf("liq", "liquidity"), read.atMs)
            if (reads.size > 3_000) reads.entries.sortedBy { it.value.atMs }.take(300).forEach { reads.remove(it.key) }
            fetched.incrementAndGet()
            return read
        }
        return null
    }

    /**
     * Executor live buy door: verify [ts]'s pool on chain (cached if fresh, else read now, ~3 s at most) and return
     * the refusal label when the chain contradicts the numbers the bot is buying on, else null.
     */
    fun buyRefusal8038(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        // V5.0.8046 — owner: "these are all ... shit buys": DOGO, Gawkbot, BUNKER bought at pump.fun's creation cap with
        // $1-2 real in the curve. An active curve whose buyers have put in under [MIN_CURVE_REAL_SOL_8046] is refused;
        // an unknown curve on a pump mint is read from the chain first.
        val pumpLike = ts.mint.endsWith("pump", ignoreCase = true) ||
            (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(ts.mint) } catch (_: Throwable) { null }) != null
        if (pumpLike && (try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }) == null) {
            try { com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(listOf(ts.mint)) } catch (_: Throwable) {}
        }
        var curveReal8046 = try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }
        // Revalidate an old create/trade reserve before live buy. Formula-implied liquidity
        // is provisional discovery evidence, never confirmed chain depth.
        val observedAt8174 = if (curveReal8046 != null) try { CurveReserves7951.readingAtMs8173(ts.mint) } catch (_: Throwable) { null } else null
        if (observedAt8174 != null && !LaunchCohort8173.reserveFresh8174(observedAt8174, nowMs)) {
            try { com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(listOf(ts.mint)) } catch (_: Throwable) {}
            val refreshedAt8174 = try { CurveReserves7951.readingAtMs8173(ts.mint) } catch (_: Throwable) { null }
            if (!LaunchCohort8173.reserveFresh8174(refreshedAt8174, System.currentTimeMillis())) {
                try { needCurve8039(ts.mint, nowMs) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("CURVE_RESERVES_STALE_DEFER_8174") } catch (_: Throwable) {}
                return "CURVE_RESERVES_STALE_DEFER_8174"
            }
            curveReal8046 = try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }
        }
        if (curveNobodyBought8046(curveReal8046)) {
            emptyCurveRefused8046.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LIVE_BUY_REFUSED_CURVE_NOBODY_BOUGHT_8046")
                com.lifecyclebot.engine.ForensicLogger.lifecycle("LIVE_BUY_REFUSED_CURVE_NOBODY_BOUGHT_8046",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} realSol=${"%.3f".format(curveReal8046 ?: 0.0)} min=$MIN_CURVE_REAL_SOL_8046 cap=${ts.lastMcap.toInt()}")
            } catch (_: Throwable) {}
            return "CURVE_NOBODY_BOUGHT_8046"
        }
        // V5.0.8047 — 5.0.8046 bought PUMPTg at a $3,218 cap (29 SOL at $109.67): a launchpad coin whose mint is not
        // pump-suffixed (LetsBonk, Meteora, un-suffixed pump.fun) skipped the curve check above. Any coin still at a
        // launch curve's opening cap has no buyers behind it, whichever launchpad minted it.
        val capSol8047 = try { EconomicUnitInvariant7061.usdToSol(TrustedMcap8019.mcap8019(ts), solUsd()) } catch (_: Throwable) { Double.NaN }
        if (atLaunchCap8047(capSol8047) && !launchCapStoodDown8094(ts.mint, nowMs)) {
            // V5.0.8252 — the one three-state launch-buyer verdict (same read as earlyLaunchRefusal8048).
            when (launchBuyers8252(curveReal8046, pumpLike)) {
                LaunchBuyers8252.BUYERS_PROVEN -> {
                    try { PipelineHealthCollector.labelInc("AT_LAUNCH_CAP_STOOD_DOWN_CURVE_BUYERS_8252") } catch (_: Throwable) {}
                }
                LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> if (unknownDeferAllowed8252(ts.mint)) {
                    // The curve is readable but unread even after the read above: ask again, bounded, decide on a reading.
                    try { needCurve8039(ts.mint, nowMs) } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("LAUNCH_BUYERS_UNKNOWN_DEFER_8252") } catch (_: Throwable) {}
                    return "LAUNCH_BUYERS_UNKNOWN_DEFER_8252"
                } else {
                    launchCapRefused8047.incrementAndGet()
                    lastRefusal8047 = "LAUNCH_CAP ${ts.symbol} cap=${"%.1f".format(capSol8047)}SOL (buyers unknown after $MAX_UNKNOWN_DEFERS_8252 reads)"
                    try { PipelineHealthCollector.labelInc("LIVE_BUY_REFUSED_AT_LAUNCH_CAP_8047") } catch (_: Throwable) {}
                    return "AT_LAUNCH_CAP_8047"
                }
                LaunchBuyers8252.NO_BUYERS_PROVEN -> {
                    launchCapRefused8047.incrementAndGet()
                    lastRefusal8047 = "LAUNCH_CAP ${ts.symbol} cap=${"%.1f".format(capSol8047)}SOL"
                    try {
                        PipelineHealthCollector.labelInc("LIVE_BUY_REFUSED_AT_LAUNCH_CAP_8047")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle("LIVE_BUY_REFUSED_AT_LAUNCH_CAP_8047",
                            "mint=${ts.mint.take(10)} sym=${ts.symbol} capSol=${"%.1f".format(capSol8047)} min=$LAUNCH_CAP_SOL_8047")
                    } catch (_: Throwable) {}
                    return "AT_LAUNCH_CAP_8047"
                }
            }
        }
        // V5.0.8045 — 5.0.8043: 13 of 16 sized MOONSHOT buys died TOKEN_MAP_INCOMPLETE (liquidity pending) at this door.
        // An unknown depth is read from the chain now (the coin's pump.fun curve, one call), before the token-map check.
        if (!(ts.lastLiquidityUsd > 0.0)) try {
            com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(listOf(ts.mint))
            LiquidityTruth8037.hydrate8037(ts)
            if (ts.lastLiquidityUsd > 0.0) PipelineHealthCollector.labelInc("BUY_DOOR_CURVE_DEPTH_READ_8045")
        } catch (_: Throwable) {}
        val read = fresh(ts.mint, nowMs) ?: pools(ts).takeIf { it.isNotEmpty() }?.let { fetch(ts.mint, it) }
        if (read == null) {
            unverified.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ONCHAIN_POOL_UNVERIFIED_8038") } catch (_: Throwable) {}
            return null
        }
        val sol = solUsd()
        val chainUsd = depthUsd8038(read.quoteSol, sol)
        val chainPx = priceUsd8038(read.quoteSol, read.baseTokens, sol)
        // What the APIs claimed: the field, unless a chain read already replaced it (then the value it replaced).
        val apiUsd = maxOf(ts.lastLiquidityUsd.takeIf { it.isFinite() && it != chainUsd } ?: 0.0, apiBeforeChain[ts.mint] ?: 0.0,
            ts.tokenMap.liquidityUsd?.takeIf { it.isFinite() } ?: 0.0)
        val why = when {
            liquidityContradicted8038(apiUsd, chainUsd) -> { liqContradicted.incrementAndGet(); "LIQUIDITY_CONTRADICTED_ONCHAIN_8038" }
            priceCollapsed8038(ts.lastPrice, chainPx) -> { priceCollapsed.incrementAndGet(); "PRICE_COLLAPSED_ONCHAIN_8038" }
            else -> null
        }
        // V5.0.8047 — the last refusal's numbers, so the diag shows whether the chain or the quote was wrong.
        if (why != null) lastRefusal8047 = "${if (why.startsWith("PRICE")) "PRICE" else "LIQ"} ${ts.symbol} pool=${read.pool.take(6)} " +
            "chainPx=${"%.3g".format(chainPx)} buyPx=${"%.3g".format(ts.lastPrice)} chainLiq=${"%.0f".format(chainUsd)} apiLiq=${"%.0f".format(apiUsd)}"
        if (why == null) {
            verified.incrementAndGet()
            if (chainUsd.isFinite() && chainUsd > 0.0) ts.lastLiquidityUsd = chainUsd
        }
        try {
            PipelineHealthCollector.labelInc(why?.let { "LIVE_BUY_REFUSED_$it" } ?: "ONCHAIN_POOL_VERIFIED_8038")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                why ?: "ONCHAIN_POOL_VERIFIED_8038",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} pool=${read.pool.take(8)} poolSol=${"%.2f".format(read.quoteSol)} chainLiq=${"%.0f".format(chainUsd)} apiLiq=${"%.0f".format(apiUsd)} chainPx=$chainPx buyPx=${ts.lastPrice}",
            )
        } catch (_: Throwable) {}
        return why
    }

    // ── V5.0.8039 — curve coins whose depth nobody has read ──
    //
    // 5.0.8038 log: liquidity stillUnknown=1,254 · SHITCOIN NATIVE_LIQ_TOO_LOW=1,162 · EXPRESS 1,154 · V3 ZERO_LIQUIDITY=165.
    // A pump.fun curve's depth is its own account (real SOL reserves); PumpPortal's trade stream (which used to carry
    // them) is refused for an unfunded key, and the batched chain read of curve accounts (ParallelMarkFanout7088
    // .curvePrices7392 -> CurveReserves7951) only ran for held coins and labels. A candidate whose liquidity is unknown
    // is now queued, and one getMultipleAccounts call reads up to 80 queued curves every [CURVE_FLUSH_MS] (a few
    // thousand calls a day). The next decision reads the reserves through TokenMapAuthority.observedLiquidityUsd.
    private const val CURVE_FLUSH_MS = 10_000L
    private const val CURVE_RETRY_MS = 60_000L
    private const val CURVE_BATCH = 40   // V5.0.8062
    private val curveQueue = ConcurrentHashMap<String, Long>()       // mint -> queued at
    private val curveAsked = ConcurrentHashMap<String, Long>()       // mint -> last read at
    private val curveFlushing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastCurveFlushMs = 0L
    private val curveRequested = AtomicLong(0)
    private val curveRead = AtomicLong(0)

    /** Pure: may [mint] be asked for again ([lastAskedMs] = last read, 0 = never)? */
    fun curveDue8039(lastAskedMs: Long, nowMs: Long): Boolean = lastAskedMs <= 0L || nowMs - lastAskedMs >= CURVE_RETRY_MS

    // V5.0.8047 — 5.0.8046: curves asked=884 read=52 while liquidity stillUnknown=971. Graduated coins and coins on
    // other venues have no curve to read (the batch skips them), so their depth stayed unknown. A coin with a pool
    // address now gets that pool's vaults read (throttled per mint like the curve queue).
    private val poolAsked8047 = ConcurrentHashMap<String, Long>()
    private val poolDepthAsked8047 = AtomicLong(0)

    /** LiquidityTruth8037: [ts]'s depth is unknown — read its curve and, when it names a pool, the pool's vaults. */
    fun needDepth8047(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (mint.isBlank()) return
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }
        // V5.0.8062 — only coins that can have a pump.fun curve are queued: every other mint was a wasted key (NO_ACCOUNT).
        val curveCoin = mint.endsWith("pump", ignoreCase = true) ||
            (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) } catch (_: Throwable) { null }) != null ||
            (try { CurveReserves7951.known7951(mint) } catch (_: Throwable) { false })
        if (!graduated && curveCoin) needCurve8039(mint, nowMs)
        if (!curveDue8039(poolAsked8047[mint] ?: 0L, nowMs) || pools(ts).isEmpty()) return
        poolAsked8047[mint] = nowMs
        if (poolAsked8047.size > 6_000) poolAsked8047.entries.removeIf { nowMs - it.value > 10 * CURVE_RETRY_MS }
        poolDepthAsked8047.incrementAndGet()
        request8038(ts)
    }

    /** LiquidityTruth8037: [mint]'s depth is unknown — read its curve from the chain in the next batch. Returns immediately. */
    fun needCurve8039(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !curveDue8039(curveAsked[mint] ?: 0L, nowMs)) return
        curveQueue.putIfAbsent(mint, nowMs)
        if (curveQueue.size > 2_000) curveQueue.keys.take(500).forEach { curveQueue.remove(it) }
        if (nowMs - lastCurveFlushMs < CURVE_FLUSH_MS || !curveFlushing.compareAndSet(false, true)) return
        lastCurveFlushMs = nowMs
        val batch = curveQueue.entries.sortedBy { it.value }.take(CURVE_BATCH).map { it.key }
        batch.forEach { curveQueue.remove(it); curveAsked[it] = nowMs }
        if (curveAsked.size > 6_000) curveAsked.entries.removeIf { nowMs - it.value > 10 * CURVE_RETRY_MS }
        try {
            pool.execute {
                try {
                    curveRequested.addAndGet(batch.size.toLong())
                    val got = com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(batch)
                    curveRead.addAndGet(got.size.toLong())
                    try { PipelineHealthCollector.labelInc("ONCHAIN_CURVE_DEPTH_READ_8039") } catch (_: Throwable) {}
                } catch (_: Throwable) {
                } finally { curveFlushing.set(false) }
            }
        } catch (_: Throwable) { curveFlushing.set(false) }
    }

    /** V5.0.8046 — SOL other buyers must have put into a curve before live money joins (a dev buy alone is not a market). */
    private const val MIN_CURVE_REAL_SOL_8046 = 3.0
    private val emptyCurveRefused8046 = AtomicLong(0)

    /** Pure: an active curve's real SOL is known and below the floor. Unknown is not judged here. */
    fun curveNobodyBought8046(realSol: Double?): Boolean = realSol != null && realSol.isFinite() && realSol < MIN_CURVE_REAL_SOL_8046

    /**
     * V5.0.8047 — a launch curve opens near 28 SOL of cap (pump.fun: 30 virtual SOL over 1.073B virtual tokens, 1B
     * supply) and reaches ~34 SOL once buyers have put in 3 SOL. A coin capped under this has no market behind it.
     */
    private const val LAUNCH_CAP_SOL_8047 = 34.0
    private val launchCapRefused8047 = AtomicLong(0)
    @Volatile private var lastRefusal8047 = "-"

    private val earlyRefused8048 = AtomicLong(0)

    /**
     * V5.0.8048 — LiveEdgeGate7877 (before the FDG, sizing and a ticket): the same two dead-launch refusals as the buy
     * door, on what is already known (no RPC). 5.0.8046: 28 buys reached the door only to die CURVE_NOBODY_BOUGHT /
     * launch cap after being sealed and sized, crowding the lane's tickets (SHITCOIN 35 tickets -> 2 executions).
     */
    fun earlyLaunchRefusal8048(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        val real = try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }
        val why = when {
            curveNobodyBought8046(real) -> "CURVE_NOBODY_BOUGHT_8046"
            atLaunchCap8047(try { EconomicUnitInvariant7061.usdToSol(TrustedMcap8019.mcap8019(ts), solUsd()) } catch (_: Throwable) { Double.NaN }) -> {
                // V5.0.8094 — the launch-cap rule stands down where its own ladder replays prove it refuses runners
                // (4abd6b13a: AT_LAUNCH_CAP n92 +67.5% run9; VEILDOGE +23,804%, PentAGI +1,605%, WAIT +1,263% missed). Real
                // buyers must be in the curve (CURVE_NOBODY_BOUGHT above stays binding) and every later gate still runs.
                if (launchCapStoodDown8094(ts.mint, nowMs)) return null
                // V5.0.8252 — one three-state verdict with the buy door: proven buyers stand the proxy down; an unread
                // readable curve is UNKNOWN (the chain is asked, the door decides on the reading), never a refusal here.
                when (launchBuyers8252(real, curveReadable8252(ts.mint))) {
                    LaunchBuyers8252.BUYERS_PROVEN -> return null
                    LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> {
                        try { needCurve8039(ts.mint, nowMs) } catch (_: Throwable) {}
                        try { PipelineHealthCollector.labelInc("LAUNCH_BUYERS_UNKNOWN_EARLY_READ_REQUESTED_8252") } catch (_: Throwable) {}
                        return null
                    }
                    LaunchBuyers8252.NO_BUYERS_PROVEN -> "AT_LAUNCH_CAP_8047"
                }
            }
            else -> return null
        }
        earlyRefused8048.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_EDGE_EARLY_${why}") } catch (_: Throwable) {}
        return why
    }

    /**
     * V5.0.8252 §LAUNCH_BUYERS — the one launch-buyer verdict, read identically at the early gate and the buy door.
     * BUYERS_PROVEN: a current curve reading states [MIN_CURVE_REAL_SOL_8046]+ real SOL from buyers. NO_BUYERS_PROVEN: a
     * reading states less (CURVE_NOBODY_BOUGHT_8046 territory), or the coin has no readable curve and only the cap proxy
     * speaks. BUYER_EVIDENCE_UNKNOWN: the curve is readable (pump key known / pump mint) but no reading exists yet.
     */
    enum class LaunchBuyers8252 { BUYERS_PROVEN, NO_BUYERS_PROVEN, BUYER_EVIDENCE_UNKNOWN }

    fun launchBuyers8252(curveRealSol: Double?, curveReadable: Boolean): LaunchBuyers8252 = when {
        curveBuyersProven8252(curveRealSol) -> LaunchBuyers8252.BUYERS_PROVEN
        curveRealSol != null && curveRealSol.isFinite() -> LaunchBuyers8252.NO_BUYERS_PROVEN
        curveReadable -> LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN
        else -> LaunchBuyers8252.NO_BUYERS_PROVEN
    }

    /** A curve the bot can read on chain: a pump-suffixed mint or a known pump curve key. */
    fun curveReadable8252(mint: String): Boolean = mint.endsWith("pump", ignoreCase = true) ||
        (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) } catch (_: Throwable) { null }) != null

    private val unknownDefers8252 = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val MAX_UNKNOWN_DEFERS_8252 = 3

    /** Bounded: the buy door defers an UNKNOWN launch-buyer verdict at most [MAX_UNKNOWN_DEFERS_8252] times per mint. */
    private fun unknownDeferAllowed8252(mint: String): Boolean {
        val n = unknownDefers8252.merge(mint, 1, Int::plus) ?: 1
        if (unknownDefers8252.size > 2_000) unknownDefers8252.clear()
        return n <= MAX_UNKNOWN_DEFERS_8252
    }

    private val launchCapStoodDownAt8094 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val STAND_DOWN_TTL_MS_8094 = 10L * 60_000L

    /** V5.0.8094 — LiveEdgeGate7877, before the early refusal: record a stand-down when the rule is tail-proven here. */
    fun standDownIfTailProven8094(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()) {
        if (lane.isBlank() || !atLaunchCap8047(try { EconomicUnitInvariant7061.usdToSol(TrustedMcap8019.mcap8019(ts), solUsd()) } catch (_: Throwable) { Double.NaN })) return
        if (!(try { TailHunter7996.refusalTailProven8094(ts, lane, "AT_LAUNCH_CAP_8047", nowMs) } catch (_: Throwable) { false })) return
        launchCapStoodDownAt8094[ts.mint] = nowMs
        if (launchCapStoodDownAt8094.size > 2_000) launchCapStoodDownAt8094.entries.removeIf { nowMs - it.value > STAND_DOWN_TTL_MS_8094 }
        try { PipelineHealthCollector.labelInc("AT_LAUNCH_CAP_STOOD_DOWN_TAIL_PROVEN_8094") } catch (_: Throwable) {}
    }

    /** V5.0.8094 — the edge gate stood the launch-cap rule down for this mint (tail-proven) within the last 10 minutes. */
    fun launchCapStoodDown8094(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        launchCapStoodDownAt8094[mint]?.let { nowMs - it in 0L..STAND_DOWN_TTL_MS_8094 } == true

    /** Pure: the coin's cap in SOL is known and still at a launch curve's opening level. */
    fun atLaunchCap8047(capSol: Double): Boolean = capSol.isFinite() && capSol > 0.0 && capSol < LAUNCH_CAP_SOL_8047

    /**
     * V5.0.8252 — 5.0.8251: the session's only two 20x runners (MF +2,201%, hllwn +2,343%) were refused AT_LAUNCH_CAP_8047
     * (runners>=+400%: caught=0 missed=2). The launch-cap rule is a PROXY for "no buyers behind it", written for coins
     * whose curve the bot cannot read; its cap is the API's trusted mcap, which lags a curve being bought right now.
     * When the chain reading of the same curve is fresh and states [MIN_CURVE_REAL_SOL_8046]+ real SOL from buyers, the
     * question the proxy asks is already answered by the account itself: the proxy stands down. CURVE_NOBODY_BOUGHT_8046
     * (the direct measurement) stays binding, as does every later gate (depth, route, quote, economics, sizing).
     */
    fun curveBuyersProven8252(realSol: Double?): Boolean = realSol != null && realSol.isFinite() && realSol >= MIN_CURVE_REAL_SOL_8046

    fun statusLine(): String =
        "emptyCurveRefused=${emptyCurveRefused8046.get()} launchCapRefused=${launchCapRefused8047.get()} earlyRefused8048=${earlyRefused8048.get()} last=[$lastRefusal8047] " +
            "poolDepthAsked=${poolDepthAsked8047.get()} curves[asked=${curveRequested.get()} read=${curveRead.get()} queued=${curveQueue.size}] verified=${verified.get()} unverified=${unverified.get()} refused[liquidity=${liqContradicted.get()} price=${priceCollapsed.get()}] " +
            "reads=${fetched.get()} failed=${failed.get()} pools=${vaultsOf.size} unsupportedPools=${unsupported.size} " +
            "venues=PumpSwap,RaydiumV4,RaydiumCPMM rule=refuse when API depth >=${CONTRADICTION_MULT_8038.toInt()}x chain or chain price ${(PRICE_DROP_TOLERANCE_8038 * 100).toInt()}%+ under the buy price"
}
