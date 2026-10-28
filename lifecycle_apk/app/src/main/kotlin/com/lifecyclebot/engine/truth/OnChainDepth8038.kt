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
    // V5.0.8418 — concentrated / dynamic venues: their vaults are the pool's real reserves (depth), but the vault ratio
    // is NOT the price (liquidity sits in bins / ranges), so their reads give depth only — never a price or a
    // constant-product sell-out.
    const val RAYDIUM_CLMM_8418 = "CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK"
    const val METEORA_DLMM_8418 = "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo"
    // superseded by V5.0.8418 — the program's own declare_id! (MeteoraAg/damm-v2 programs/cp-amm/src/lib.rs) is
    // cpamdp…n1sGG, the id ChainLaunchFeed8179 already uses; the earlier id matched no pool, so every DAMM v2 pool fell to OTHER.
    const val METEORA_DAMM_V2_8418 = "cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG"
    /** V5.0.8418 — Orca Whirlpool (concentrated): vaults are reserves (depth only), never the price. */
    const val ORCA_WHIRLPOOL_8418 = "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"
    /** DAMM v1 holds its reserves as shares of Meteora dynamic vaults (not two token accounts): depth stays UNKNOWN. */
    const val METEORA_DAMM_V1_8418 = "Eo7WjKq67rjJQSZxS6z3YkapzY3eMj6Xy8X5EQVn5UaB"
    private val CONCENTRATED_8418 = setOf(RAYDIUM_CLMM_8418, METEORA_DLMM_8418, METEORA_DAMM_V2_8418, ORCA_WHIRLPOOL_8418)
    private const val CONTRADICTION_MULT_8038 = 4.0
    private const val PRICE_DROP_TOLERANCE_8038 = 0.40
    private const val FRESH_MS = 90_000L
    private const val UNSUPPORTED_RETRY_MS = 30L * 60_000L
    private const val BLOCKING_TIMEOUT_S = 3L
    private val BASE58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    data class Read(val pool: String, val quoteSol: Double, val baseTokens: Double, val atMs: Long)

    private val vaultsOf = ConcurrentHashMap<String, Pair<String, String>>()     // pool -> vaults
    private val poolOwner8418 = ConcurrentHashMap<String, String>()              // pool -> owner program
    private val depthOnlyReads8418 = ConcurrentHashMap<String, Read>()           // mint -> concentrated-venue read (depth only)
    private val unsupportedByVenue8418 = ConcurrentHashMap<String, AtomicLong>()
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
        RAYDIUM_CLMM_8418 -> 137 to 169   // disc 8, bump u8, amm_config, owner, mint_0, mint_1, token_vault_0, token_vault_1
        METEORA_DLMM_8418 -> 152 to 184   // disc 8, static params 32, variable params 32, 16 bytes of u8/u16/i32, mint_x, mint_y, reserve_x, reserve_y
        METEORA_DAMM_V2_8418 -> 232 to 264 // disc 8, pool_fees 160, token_a_mint, token_b_mint, token_a_vault, token_b_vault
        // V5.0.8418 — disc 8, config 32, bump u8, tick_spacing u16, fee seed [u8;2], fee_rate u16, protocol_fee_rate u16,
        // liquidity u128, sqrt_price u128, tick i32, fees owed 2x u64, mint_a (101), vault_a (133), fee_growth_a u128, mint_b (181), vault_b (213)
        ORCA_WHIRLPOOL_8418 -> 133 to 213
        else -> null
    }

    /** V5.0.8418 — pure: a venue whose vault ratio is not its price (depth only). */
    fun concentrated8418(owner: String): Boolean = owner in CONCENTRATED_8418

    /** V5.0.8418 — pure: the venue name of a pool-owner program for the diag, or "OTHER". */
    fun venueName8418(owner: String): String = when (owner) {
        PUMP_AMM -> "PumpSwap"
        RAYDIUM_V4 -> "RaydiumV4"
        RAYDIUM_CPMM -> "RaydiumCPMM"
        RAYDIUM_CLMM_8418 -> "RaydiumCLMM"
        METEORA_DLMM_8418 -> "MeteoraDLMM"
        METEORA_DAMM_V2_8418 -> "MeteoraDAMMv2"
        METEORA_DAMM_V1_8418 -> "MeteoraDAMMv1"
        ORCA_WHIRLPOOL_8418 -> "OrcaWhirlpool"
        "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P" -> "PumpCurve"
        else -> "OTHER"
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
        if (!sol.second.isFinite() || !coin.second.isFinite() || !(sol.second > 0.0) || !(coin.second > 0.0)) return null
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
        reads[mint]?.takeIf { usableRead(it, maxAgeMs, nowMs) }

    internal fun usableRead(read: Read, maxAgeMs: Long, nowMs: Long): Boolean =
        read.pool.isNotBlank() && nowMs - read.atMs in 0L..maxAgeMs &&
            read.quoteSol.isFinite() && read.quoteSol > 0.0 &&
            read.baseTokens.isFinite() && read.baseTokens > 0.0

    /** V5.0.8418 — a concentrated-venue (depth-only) read of [mint] at most [maxAgeMs] old exists. */
    fun depthOnlyFresh8418(mint: String, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        depthOnlyReads8418[mint]?.let { nowMs - it.atMs in 0L..maxAgeMs } == true

    /** LiquidityTruth8037: the fresh on-chain depth (USD, both sides) of [mint]'s pool, or NaN. Never requests. */
    fun chainDepthUsd8038(mint: String, nowMs: Long = System.currentTimeMillis()): Double =
        (fresh(mint, nowMs) ?: depthOnlyReads8418[mint]?.takeIf { nowMs - it.atMs in 0L..FRESH_MS })
            ?.let { depthUsd8038(it.quoteSol, solUsd()) } ?: Double.NaN

    private fun pools(ts: TokenState): List<String> =
        listOf(ts.lastPricePoolAddr, ts.pairAddress, ts.tokenMap.poolAddress, ts.tokenMap.pairAddress)
            .map { it.trim() }.filter { BASE58.matches(it) && it != ts.mint }.distinct()
            .filter { p -> unsupported[p]?.let { System.currentTimeMillis() - it > UNSUPPORTED_RETRY_MS } ?: true }

    /** Ask for a read of [ts]'s pool in the background (LiveEdgeGate, exits). Returns immediately. */
    fun request8038(ts: TokenState, maxAgeMs: Long = FRESH_MS) {
        val mint = ts.mint
        if (mint.isBlank() || freshRead8198(mint, maxAgeMs, System.currentTimeMillis()) != null || depthOnlyFresh8418(mint, maxAgeMs) || !inFlight.add(mint)) return
        val ps = pools(ts)
        if (ps.isEmpty()) { inFlight.remove(mint); return }
        // V5.0.8418 — background reads are batched (PoolBatch8418: pool + vault accounts, up to 100 keys a
        // getMultipleAccounts, one entry per mint); the per-mint executor is the fallback when the batch refuses.
        if (PoolBatch8418.enqueue8418(mint, ps.take(2))) return
        try {
            pool.execute { try { fetch(mint, ps) } finally { inFlight.remove(mint) } }
        } catch (_: Throwable) { inFlight.remove(mint) }
    }

    /** V5.0.8418 — PoolBatch8418 has finished with [mint] (answered or not): a later request may ask again. */
    internal fun batchDone8418(mint: String) { inFlight.remove(mint) }

    /** V5.0.8418 — the vault keys and owner program of [poolAddr] when already decoded, else null. */
    internal fun knownPool8418(poolAddr: String): Pair<Pair<String, String>, String>? {
        val v = vaultsOf[poolAddr] ?: return null
        val o = poolOwner8418[poolAddr] ?: return null
        return v to o
    }

    /** V5.0.8418 — a pool marked unsupported inside its retry window (not asked again yet). */
    internal fun unsupportedNow8418(poolAddr: String, nowMs: Long): Boolean =
        unsupported[poolAddr]?.let { nowMs - it <= UNSUPPORTED_RETRY_MS } ?: false

    /** V5.0.8418 — a batch answered no account for [poolAddr]. */
    internal fun noPoolAccount8418(poolAddr: String) { unsupported[poolAddr] = System.currentTimeMillis() }

    /** V5.0.8418 — a batched vault read for [mint] got no RPC answer (the cause is counted by RpcOutcome8418). */
    internal fun batchFailed8418(mint: String) { failed.incrementAndGet() }

    /**
     * V5.0.8418 — the account keys one read of [poolAddr] needs: its two vaults, plus the pool account itself on a venue
     * whose spot price lives in pool state (ConcentratedPrice8418).
     */
    internal fun readKeys8418(vaults: Pair<String, String>, poolAddr: String, owner: String): List<String> =
        if (priceInState8418(owner)) listOf(vaults.first, vaults.second, poolAddr) else listOf(vaults.first, vaults.second)

    /** V5.0.8418 — venues whose spot price is decoded from the pool account (CLMM sqrt, Whirlpool sqrt, DLMM bin). */
    internal fun priceInState8418(owner: String): Boolean =
        owner == RAYDIUM_CLMM_8418 || owner == ORCA_WHIRLPOOL_8418 || owner == METEORA_DLMM_8418

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

    // V5.0.8359 — pool vault depth is a market read: keyless public nodes first, Helius only behind them (KeylessRpc8359).
    private fun rpc(method: String, params: JSONArray): JSONObject? =
        com.lifecyclebot.network.KeylessRpc8359.post8359(http, method, params, "8038", maxRungs = 3)

    private fun vaultsFor(poolAddr: String): Pair<String, String>? {
        vaultsOf[poolAddr]?.let { return it }
        val res = rpc("getAccountInfo", JSONArray().put(poolAddr).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed"))) ?: return null
        val v = res.optJSONObject("value") ?: run { unsupported[poolAddr] = System.currentTimeMillis(); return null }
        return adoptPool8418(poolAddr, v.optString("owner"), accountData8418(v))
    }

    /** V5.0.8418 — the raw bytes of an account answer encoded base64 (also jsonParsed's fallback for unparsed programs). */
    internal fun accountData8418(acct: JSONObject?): ByteArray? {
        val arr = acct?.optJSONArray("data") ?: return null
        if (arr.optString(1) != "base64") return null
        return try { java.util.Base64.getDecoder().decode(arr.optString(0).orEmpty()) } catch (_: Throwable) { null }
    }

    /** V5.0.8418 — decode a fetched pool account's vault keys (or mark it unsupported, counted by venue). */
    internal fun adoptPool8418(poolAddr: String, owner: String, data: ByteArray?): Pair<String, String>? {
        val keys = data?.let { vaultKeys8038(owner, it) } ?: run {
            unsupported[poolAddr] = System.currentTimeMillis()
            // V5.0.8418 — an unreadable venue's depth is UNKNOWN (never estimated); the diag names the venue.
            unsupportedByVenue8418.computeIfAbsent(venueName8418(owner)) { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ONCHAIN_DEPTH_UNKNOWN_VENUE_8418_${venueName8418(owner)}") } catch (_: Throwable) {}
            return null
        }
        vaultsOf[poolAddr] = keys
        poolOwner8418[poolAddr] = owner
        if (vaultsOf.size > 3_000) vaultsOf.keys.take(300).forEach { vaultsOf.remove(it); poolOwner8418.remove(it) }
        return keys
    }

    private fun parsedSide(acct: JSONObject?): Pair<String, Double>? {
        val info = acct?.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: return null
        val amt = info.optJSONObject("tokenAmount")?.optString("uiAmountString")?.toDoubleOrNull() ?: return null
        return info.optString("mint") to amt
    }

    private fun parsedDecimals8418(acct: JSONObject?): Int? =
        acct?.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info")?.optJSONObject("tokenAmount")
            ?.takeIf { it.has("decimals") }?.optInt("decimals", -1)?.takeIf { it in 0..18 }

    private fun fetch(mint: String, poolAddrs: List<String>): Read? {
        for (p in poolAddrs.take(2)) {
            val vaults = vaultsFor(p) ?: continue
            // V5.0.8418 — the venue decides how the vaults may be read; vaults without a known owner are re-read.
            val owner8418 = poolOwner8418[p] ?: run { vaultsOf.remove(p); null } ?: continue
            val observedAt = System.currentTimeMillis()
            val keys8418 = JSONArray().apply { readKeys8418(vaults, p, owner8418).forEach { put(it) } }
            val res = rpc("getMultipleAccounts", JSONArray().put(keys8418).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
            val arr = res?.optJSONArray("value")
            if (arr == null) { failed.incrementAndGet(); continue }
            val outcome8418 = accept8418(mint, p, owner8418, arr.optJSONObject(0), arr.optJSONObject(1), arr.optJSONObject(2), observedAt)
            if (outcome8418 == Accept8418.NOT_COIN_SOL) continue
            return if (outcome8418 == Accept8418.CONSTANT_PRODUCT) reads[mint] else null
        }
        return null
    }

    /** V5.0.8418 — what one answered vault read became. */
    internal enum class Accept8418 { CONSTANT_PRODUCT, CONCENTRATED, NOT_COIN_SOL }

    /**
     * V5.0.8418 — accept one answered read of [p] (owner [owner8418]): the two vault accounts and, on a price-in-state
     * venue, the pool account. Shared by the blocking buy-door read and PoolBatch8418.
     */
    internal fun accept8418(mint: String, p: String, owner8418: String, acctA: JSONObject?, acctB: JSONObject?, poolAcct: JSONObject?, observedAt: Long): Accept8418 {
        val sides = sides8038(mint, parsedSide(acctA), parsedSide(acctB))
        if (sides == null) {
            unsupported[p] = System.currentTimeMillis()
            unsupportedByVenue8418.computeIfAbsent(venueName8418(owner8418) + "_NOT_COIN_SOL") { AtomicLong(0) }.incrementAndGet()
            return Accept8418.NOT_COIN_SOL
        }
        val read = Read(p, sides.first, sides.second, observedAt)
        if (concentrated8418(owner8418)) {
            // V5.0.8418 — the spot price from pool state (layout-proved; tagged with its source), never the vault ratio.
            if (priceInState8418(owner8418)) recordStatePrice8418(mint, owner8418, acctA, acctB, poolAcct, observedAt)
            // V5.0.8418 — depth only: kept apart from [reads] so no price check or constant-product sell-out reads it.
            depthOnlyReads8418[mint] = read
            if (depthOnlyReads8418.size > 3_000) depthOnlyReads8418.entries.sortedBy { it.value.atMs }.take(300).forEach { depthOnlyReads8418.remove(it.key) }
            CandidateAdmission8136.evidenceArrived8252(mint, setOf("liq", "liquidity"), read.atMs)
            try { com.lifecyclebot.engine.HydrationReeval8343.onDepthRead8343(mint, depthUsd8038(read.quoteSol, solUsd()), read.atMs) } catch (_: Throwable) {}
            fetched.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ONCHAIN_DEPTH_CONCENTRATED_READ_8418_${venueName8418(owner8418)}") } catch (_: Throwable) {}
            return Accept8418.CONCENTRATED
        }
        reads[mint] = read
        CandidateAdmission8136.evidenceArrived8252(mint, setOf("liq", "liquidity"), read.atMs)
        try { com.lifecyclebot.engine.HydrationReeval8343.onDepthRead8343(mint, depthUsd8038(read.quoteSol, solUsd()), read.atMs) } catch (_: Throwable) {}  // V5.0.8343 — the vault answer ends a liquidity deferral
        if (reads.size > 3_000) reads.entries.sortedBy { it.value.atMs }.take(300).forEach { reads.remove(it.key) }
        fetched.incrementAndGet()
        return Accept8418.CONSTANT_PRODUCT
    }

    /** V5.0.8418 — decode and record the concentrated pool's spot price (ConcentratedPrice8418) from this same read. */
    private fun recordStatePrice8418(mint: String, owner: String, acctA: JSONObject?, acctB: JSONObject?, poolAcct: JSONObject?, atMs: Long) {
        try {
            val data = accountData8418(poolAcct) ?: return
            val sideA = parsedSide(acctA) ?: return
            val decA = parsedDecimals8418(acctA) ?: return
            val decB = parsedDecimals8418(acctB) ?: return
            val coinIsA = sideA.first == mint
            val px = ConcentratedPrice8418.decode8418(owner, data, mint, if (coinIsA) decA else decB, if (coinIsA) decB else decA) ?: run {
                PipelineHealthCollector.labelInc("ONCHAIN_STATE_PRICE_LAYOUT_UNPROVEN_8418_${venueName8418(owner)}")
                return
            }
            ConcentratedPrice8418.record8418(mint, px, owner, atMs)
            PipelineHealthCollector.labelInc("ONCHAIN_STATE_PRICE_8418_${venueName8418(owner)}")
        } catch (_: Throwable) {}
    }

    /**
     * Executor live buy door: verify [ts]'s pool on chain (cached if fresh, else read now, ~3 s at most) and return
     * the refusal label when the chain contradicts the numbers the bot is buying on, else null.
     */
    // Explicitly enumerated pre-submission data waits. No strategy or hard-risk refusal is recoverable here.
    const val STALE_RESERVES_8385 = "CURVE_RESERVES_STALE_DEFER_8174"
    const val UNVERIFIED_RESERVES_8385 = "CURVE_RESERVES_UNVERIFIED_DEFER_8253"
    fun reserveCause8385(reason: String): String? = listOf(STALE_RESERVES_8385, UNVERIFIED_RESERVES_8385)
        .firstOrNull { cause -> reason.split(':').any { it == cause || it == "DEFERRED_$cause" } }
    sealed class Preflight8385 {
        object Ready : Preflight8385()
        data class DataDefer(val reason: String, val mint: String, val observedAfterMs: Long, val accountRequired: Boolean) : Preflight8385()
        data class HardRefusal(val reason: String) : Preflight8385()
    }
    fun classifyPreflight8385(mint: String, reason: String?, nowMs: Long): Preflight8385 = when {
        reason == null -> Preflight8385.Ready
        reserveCause8385(reason) != null -> Preflight8385.DataDefer(reason, mint, nowMs, reason == UNVERIFIED_RESERVES_8385)
        else -> Preflight8385.HardRefusal(reason)
    }

    fun buyRefusal8038(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        // V5.0.8046 — owner: "these are all ... shit buys": DOGO, Gawkbot, BUNKER bought at pump.fun's creation cap with
        // $1-2 real in the curve. An active curve whose buyers have put in under [MIN_CURVE_REAL_SOL_8046] is refused;
        // an unknown curve on a pump mint is read from the chain first.
        val pumpLike = curveReadable8252(ts.mint)
        if (pumpLike && (try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }) == null) {
            try { needCurve8039(ts.mint, nowMs, approved8385 = true) } catch (_: Throwable) {}
        }
        var curveReal8046 = try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }
        if (pumpLike && curveReal8046 == null && !com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(ts.mint))
            return UNVERIFIED_RESERVES_8385
        // Revalidate an old create/trade reserve before live buy. Formula-implied liquidity
        // is provisional discovery evidence, never confirmed chain depth.
        val observedAt8174 = if (curveReal8046 != null) try { CurveReserves7951.readingAtMs8173(ts.mint) } catch (_: Throwable) { null } else null
        if (observedAt8174 != null && !LaunchCohort8173.reserveFresh8174(observedAt8174, nowMs)) {
            try { needCurve8039(ts.mint, nowMs, approved8385 = true) } catch (_: Throwable) {}
            val refreshedAt8174 = try { CurveReserves7951.readingAtMs8173(ts.mint) } catch (_: Throwable) { null }
            if (!LaunchCohort8173.reserveFresh8174(refreshedAt8174, System.currentTimeMillis())) {
                try { needCurve8039(ts.mint, nowMs) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("CURVE_RESERVES_STALE_DEFER_8174") } catch (_: Throwable) {}
                return "CURVE_RESERVES_STALE_DEFER_8174"
            }
            curveReal8046 = try { CurveReserves7951.realSolIfActive8046(ts.mint) } catch (_: Throwable) { null }
        }
        // A fresh create frame can still describe the opening curve while another venue is now active.
        // Re-read the account before binding a negative reserve verdict; unknown is not "nobody bought".
        if (curveNobodyBought8046(curveReal8046) && CurveReserves7951.accountRealSol8253(ts.mint, nowMs) == null) {
            try { needCurve8039(ts.mint, nowMs, approved8385 = true) } catch (_: Throwable) {}
            val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(ts.mint) } catch (_: Throwable) { false }
            curveReal8046 = CurveReserves7951.accountRealSol8253(ts.mint, System.currentTimeMillis())
            if (!graduated && curveReal8046 == null) {
                try { needCurve8039(ts.mint, nowMs) } catch (_: Throwable) {}
                return "CURVE_RESERVES_UNVERIFIED_DEFER_8253"
            }
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
            when (launchBuyers8252(curveReal8046)) {
                LaunchBuyers8252.BUYERS_PROVEN -> {
                    try { PipelineHealthCollector.labelInc("AT_LAUNCH_CAP_STOOD_DOWN_CURVE_BUYERS_8252") } catch (_: Throwable) {}
                }
                LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> {
                    // Failed requests do not establish absent buyers. Keep the attempt
                    // non-executable, request the applicable venue, and retain UNKNOWN
                    // so a later observation can be evaluated on its own evidence.
                    try { needDepth8047(ts, nowMs) } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("LAUNCH_BUYERS_UNKNOWN_DEFER_8252") } catch (_: Throwable) {}
                    return "LAUNCH_BUYERS_UNKNOWN_DEFER_8252"
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
            // V5.0.8418 — a concentrated pool has no constant-product read, but its decoded spot price (pool state,
            // tagged with its source) checks the buy price the same way. Depth-only reads never refuse here.
            val cp8418 = ConcentratedPrice8418.fresh8418(ts.mint, FRESH_MS, nowMs)
            val cpUsd8418 = cp8418?.let { it.solPerCoin * solUsd() } ?: Double.NaN
            if (cp8418 != null && priceCollapsed8038(ts.lastPrice, cpUsd8418)) {
                priceCollapsed.incrementAndGet()
                lastRefusal8047 = "PRICE ${ts.symbol} src=${cp8418.source} chainPx=${"%.3g".format(cpUsd8418)} buyPx=${"%.3g".format(ts.lastPrice)}"
                try {
                    PipelineHealthCollector.labelInc("LIVE_BUY_REFUSED_PRICE_COLLAPSED_ONCHAIN_8038")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle("PRICE_COLLAPSED_ONCHAIN_8038",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} source=${cp8418.source} chainPx=$cpUsd8418 buyPx=${ts.lastPrice}")
                } catch (_: Throwable) {}
                return "PRICE_COLLAPSED_ONCHAIN_8038"
            }
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
            if (chainUsd.isFinite() && chainUsd > 0.0) com.lifecyclebot.engine.HydrationReeval8343.writeTick8345(ts, chainUsd, "CHAIN_VERIFIED_8038")  // V5.0.8345
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
    private val curveRequested = AtomicLong(0)
    private val curveRead = AtomicLong(0)
    private val curveScheduler8385 = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AATE-reserve-drain-8385").apply { isDaemon = true }
    }
    private val refresh8385 by lazy {
        CurveRefreshQueue8385(System::currentTimeMillis,
            { delay, run -> try { curveScheduler8385.schedule(run, delay, java.util.concurrent.TimeUnit.MILLISECONDS); true } catch (_: Throwable) { false } },
            { run -> try { pool.execute(run); true } catch (_: Throwable) { false } },
            { batch ->
                curveRequested.addAndGet(batch.size.toLong())
                com.lifecyclebot.network.ParallelMarkFanout7088.curvePrices7392(batch)
                val usable = batch.filter { mint ->
                    val obs = CurveReserves7951.acceptedObservation8385(mint)
                    LaunchCohort8173.reserveFresh8174(obs?.atMs, System.currentTimeMillis()) ||
                        com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint)
                }.toSet()
                curveRead.addAndGet(usable.size.toLong())
                try { PipelineHealthCollector.labelInc("ONCHAIN_CURVE_DEPTH_READ_8039") } catch (_: Throwable) {}
                usable.associateWith { CurveReserves7951.acceptedObservation8385(it)?.atMs ?: System.currentTimeMillis() }
            }, { mint, reason -> com.lifecyclebot.engine.TicketRetry8327.onReserveTimeout8385(mint, reason) })
    }

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
    fun needCurve8039(mint: String, nowMs: Long = System.currentTimeMillis(), approved8385: Boolean = false) {
        refresh8385.request(mint, approved8385, nowMs)
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
        val real = try { CurveReserves7951.accountRealSol8253(ts.mint, nowMs) } catch (_: Throwable) { null }
        val why = when {
            curveNobodyBought8046(real) -> "CURVE_NOBODY_BOUGHT_8046"
            atLaunchCap8047(try { EconomicUnitInvariant7061.usdToSol(TrustedMcap8019.mcap8019(ts), solUsd()) } catch (_: Throwable) { Double.NaN }) -> {
                // V5.0.8094 — the launch-cap rule stands down where its own ladder replays prove it refuses runners
                // (4abd6b13a: AT_LAUNCH_CAP n92 +67.5% run9; VEILDOGE +23,804%, PentAGI +1,605%, WAIT +1,263% missed). Real
                // buyers must be in the curve (CURVE_NOBODY_BOUGHT above stays binding) and every later gate still runs.
                if (launchCapStoodDown8094(ts.mint, nowMs)) return null
                // V5.0.8252 — one three-state verdict with the buy door: proven buyers stand the proxy down; an unread
                // readable curve is UNKNOWN (the chain is asked, the door decides on the reading), never a refusal here.
                when (launchBuyers8252(real)) {
                    LaunchBuyers8252.BUYERS_PROVEN -> return null
                    LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> {
                        try { needDepth8047(ts, nowMs) } catch (_: Throwable) {}
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
     * valid nonnegative reading states less (CURVE_NOBODY_BOUGHT_8046 territory).
     * Missing, invalid or unsupported observations stay UNKNOWN regardless of retry count.
     * Market cap and provider capability are not observations of actual buyers.
     */
    enum class LaunchBuyers8252 { BUYERS_PROVEN, NO_BUYERS_PROVEN, BUYER_EVIDENCE_UNKNOWN }

    fun launchBuyers8252(curveRealSol: Double?): LaunchBuyers8252 = when {
        curveBuyersProven8252(curveRealSol) -> LaunchBuyers8252.BUYERS_PROVEN
        curveRealSol != null && curveRealSol.isFinite() && curveRealSol >= 0.0 -> LaunchBuyers8252.NO_BUYERS_PROVEN
        else -> LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN
    }

    /** A curve the bot can read on chain: a pump-suffixed mint or a known pump curve key. */
    fun curveReadable8252(mint: String): Boolean = mint.endsWith("pump", ignoreCase = true) ||
        (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) } catch (_: Throwable) { null }) != null

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
            "poolDepthAsked=${poolDepthAsked8047.get()} curves[asked=${curveRequested.get()} read=${curveRead.get()} queued=${refresh8385.size}] verified=${verified.get()} unverified=${unverified.get()} refused[liquidity=${liqContradicted.get()} price=${priceCollapsed.get()}] " +
            "reads=${fetched.get()} failed=${failed.get()} pools=${vaultsOf.size} unsupportedPools=${unsupported.size} " +
            "statePrices8418=${ConcentratedPrice8418.size8418()} batch8418[${PoolBatch8418.statusLine8418()}] unsupportedByVenue8418={${unsupportedByVenue8418.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }}} depthOnly8418=${depthOnlyReads8418.size} " +
            "venues=PumpSwap,RaydiumV4,RaydiumCPMM,RaydiumCLMM(depth),MeteoraDLMM(depth),MeteoraDAMMv2(depth),OrcaWhirlpool(depth) unknownDepth=MeteoraDAMMv1,other rule=refuse when API depth >=${CONTRADICTION_MULT_8038.toInt()}x chain or chain price ${(PRICE_DROP_TOLERANCE_8038 * 100).toInt()}%+ under the buy price"
}

/** Queue implementation owned by OnChainDepth8038. Wire pacing is unchanged; failed admission never consumes a wire slot. */
internal class CurveRefreshQueue8385(
    private val clock: () -> Long,
    private val schedule: (Long, Runnable) -> Boolean,
    private val execute: (Runnable) -> Boolean,
    private val fetch: (List<String>) -> Map<String, Long>,
    private val expired: (String, String) -> Unit = { _, _ -> },
) {
    private data class Work(val mint: String, val firstMs: Long, var priority: Boolean, var attempts: Int = 0)
    private val pending = LinkedHashMap<String, Work>()
    private val successfullyObservedAt = LinkedHashMap<String, Long>()
    private var attemptedAt = 0L
    private var scheduled = false
    private var active = false
    val size: Int get() = synchronized(this) { pending.size }
    fun request(mint: String, approved: Boolean, nowMs: Long = clock()) = synchronized(this) {
        if (mint.isBlank()) return@synchronized
        val prior = pending[mint]
        if (prior != null) { prior.priority = prior.priority || approved; return@synchronized }
        if (!approved && successfullyObservedAt[mint]?.let { nowMs - it in 0L until 60_000L } == true) return@synchronized
        if (pending.size >= 2_000) return@synchronized
        pending[mint] = Work(mint, nowMs, approved)
        arm(0L)
    }
    private fun arm(delay: Long) {
        if (scheduled || active || pending.isEmpty()) return
        scheduled = true
        if (!schedule(delay.coerceAtLeast(0L), Runnable { drain() })) scheduled = false
    }
    private fun drain() {
        val batch = synchronized(this) {
            scheduled = false
            val now = clock()
            val ended = pending.values.filter { now - it.firstMs >= 90_000L || it.attempts >= 4 }
            ended.forEach { w ->
                pending.remove(w.mint)
                expired(w.mint, if (w.attempts >= 4) "CURVE_REFRESH_RETRY_BUDGET_8385" else "CURVE_REFRESH_TIMEOUT_8385")
            }
            if (pending.isEmpty() || active) return
            if (attemptedAt > 0L && now - attemptedAt < 10_000L) { arm(10_000L - (now - attemptedAt)); return }
            active = true
            pending.values.sortedWith(compareByDescending<Work> { it.priority }.thenBy { it.firstMs }).take(40)
        }
        val accepted = try { execute(Runnable {
            synchronized(this) { attemptedAt = clock(); batch.forEach { it.attempts++ } }
            val usable = try { fetch(batch.map { it.mint }) } catch (_: Throwable) { emptyMap() }
            synchronized(this) {
                val now = clock()
                usable.forEach { (mint, observedAt) ->
                    if (batch.any { it.mint == mint } && LaunchCohort8173.reserveFresh8174(observedAt, now)) {
                        pending.remove(mint); successfullyObservedAt[mint] = observedAt
                    }
                }
                while (successfullyObservedAt.size > 6_000) successfullyObservedAt.remove(successfullyObservedAt.keys.first())
                active = false
                arm(10_000L)
            }
        }) } catch (_: Throwable) { false }
        if (!accepted) synchronized(this) { active = false; arm(1_000L) }
    }
}
