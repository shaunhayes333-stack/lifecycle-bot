package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.0.8128 — Raydium api-v3 as the second source for a candidate's pool, TVL and new pools.
 *
 * Audit: a candidate's liquidity / pool address came from DexScreener only. When getBestPair returned
 * null the stand-in pair copied stale liquidity (often 0) and wrote pairAddress="", and the
 * "Raydium" new-pool scanner was really GeckoTerminal, so one rate limit there killed it too.
 * Raydium answers keyless at sr=100% in production. Every figure here is Raydium's own; a mint
 * Raydium has no pool for returns null, never an estimate.
 */
object RaydiumMintPools8128 {

    /** The deepest Raydium pool for a mint. [priceUsd] only when the pool is USDC-quoted (Raydium's price is a pool ratio). */
    data class Pool8128(val poolId: String, val tvlUsd: Double, val priceUsd: Double?)

    data class NewPool8128(
        val mint: String,
        val symbol: String,
        val name: String,
        val poolId: String,
        val tvlUsd: Double,
        val volume24hUsd: Double,
        val openTimeSec: Long,
    )

    private const val SOL_MINT_8128 = "So11111111111111111111111111111111111111112"
    private const val USDC_MINT_8128 = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private const val MINT_URL_8128 =
        "https://api-v3.raydium.io/pools/info/mint?poolType=all&poolSortField=liquidity&sortType=desc&pageSize=1&page=1&mint1="
    private const val LIST_URL_8128 =
        "https://api-v3.raydium.io/pools/info/list?poolType=all&poolSortField=default&sortType=desc&pageSize=100&page=1"
    // V5.0.8128 — a pool answer is good for 3 min; a mint with no Raydium pool is not re-asked for 10 min.
    private const val POOL_TTL_MS_8128 = 3L * 60_000L
    private const val MISS_TTL_MS_8128 = 10L * 60_000L
    // V5.0.8128 — "new pool" means opened in the last 48 h; an old major on the list is not a launch.
    private const val NEW_POOL_MAX_AGE_SEC_8128 = 48L * 3_600L

    private val http = SharedHttpClient.builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private class Entry8128(val pool: Pool8128?, val atMs: Long)
    private val cache8128 = ConcurrentHashMap<String, Entry8128>()
    private val inFlight8128 = ConcurrentHashMap.newKeySet<String>()

    // V5.0.8128 — lookups run off the trading cycle: two daemon workers, a bounded queue, shed when full.
    private val workers8128: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            { r -> Thread(r, "raydium-mint-8128").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    /** Pure: the first pool of a `/pools/info/mint` body when it really holds [mint]; null otherwise. */
    fun parsePool8128(body: String?, mint: String): Pool8128? {
        if (body.isNullOrBlank() || mint.isBlank()) return null
        val root = try { JSONObject(body) } catch (_: Throwable) { return null }
        if (!root.optBoolean("success", true)) return null
        val p = root.optJSONObject("data")?.optJSONArray("data")?.optJSONObject(0) ?: return null
        val a = p.optJSONObject("mintA")?.optString("address").orEmpty()
        val b = p.optJSONObject("mintB")?.optString("address").orEmpty()
        if (mint != a && mint != b) return null
        val id = p.optString("id", "").trim()
        if (id.length < 32) return null
        val tvl = p.optDouble("tvl", Double.NaN).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val px = p.optDouble("price", Double.NaN)
        val priceUsd = when {
            !px.isFinite() || px <= 0.0 -> null
            a == mint && b == USDC_MINT_8128 -> px
            b == mint && a == USDC_MINT_8128 -> 1.0 / px
            else -> null
        }
        return Pool8128(id, tvl, priceUsd)
    }

    /**
     * Pure: SOL- or USDC-paired pools of a `/pools/info/list` body opened within [maxAgeSec] of
     * [nowSec], newest first. A pool with no open time is not known to be new and is left out.
     */
    fun parseNewPools8128(body: String?, nowSec: Long, maxAgeSec: Long = NEW_POOL_MAX_AGE_SEC_8128): List<NewPool8128> {
        if (body.isNullOrBlank()) return emptyList()
        val arr = try { JSONObject(body).optJSONObject("data")?.optJSONArray("data") } catch (_: Throwable) { null } ?: return emptyList()
        val quotes = setOf(SOL_MINT_8128, USDC_MINT_8128)
        val out = ArrayList<NewPool8128>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val a = p.optJSONObject("mintA") ?: continue
            val b = p.optJSONObject("mintB") ?: continue
            val aAddr = a.optString("address", "")
            val bAddr = b.optString("address", "")
            val base = when {
                bAddr in quotes && aAddr !in quotes -> a
                aAddr in quotes && bAddr !in quotes -> b
                else -> continue
            }
            val mint = base.optString("address", "").trim()
            val id = p.optString("id", "").trim()
            if (mint.length < 32 || id.length < 32) continue
            val open = p.optString("openTime", "0").toDoubleOrNull()?.toLong() ?: 0L
            if (open <= 0L || open > nowSec || nowSec - open > maxAgeSec) continue
            out += NewPool8128(
                mint = mint,
                symbol = base.optString("symbol", "").trim(),
                name = base.optString("name", "").trim(),
                poolId = id,
                tvlUsd = p.optDouble("tvl", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                volume24hUsd = p.optJSONObject("day")?.optDouble("volume", 0.0)?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                openTimeSec = open,
            )
        }
        return out.sortedByDescending { it.openTimeSec }
    }

    /** Pure: a stand-in pair's liquidity — the known reading wins; Raydium TVL fills only an unknown (<= 0) one. */
    fun standInLiquidity8128(knownUsd: Double, pool: Pool8128?): Double =
        if (knownUsd.isFinite() && knownUsd > 0.0) knownUsd else (pool?.tvlUsd?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0)

    /** Pure: a stand-in market cap — the known one wins; else price x on-chain supply when both are known; else 0 (unknown). */
    fun standInMcap8128(knownMcap: Double, priceUsd: Double, supply: Double): Double = when {
        knownMcap.isFinite() && knownMcap > 0.0 -> knownMcap
        priceUsd.isFinite() && priceUsd > 0.0 && supply.isFinite() && supply > 0.0 -> priceUsd * supply
        else -> 0.0
    }

    /**
     * Pure: the pool id a token's blank pair address may take. Never over a known pair, never for a
     * pump.fun mint/source (its route is the bonding curve, not a Raydium pool).
     */
    fun pairAddressFill8128(currentPair: String, source: String, pumpMint: Boolean, pool: Pool8128?): String? {
        if (currentPair.isNotBlank() || pumpMint || source.contains("PUMP", ignoreCase = true)) return null
        return pool?.poolId?.takeIf { it.length >= 32 }
    }

    /**
     * The cached Raydium pool for [mint], or null. Never blocks: a missing or stale answer is
     * requested in the background and served from the next call.
     */
    fun cached8128(mint: String, nowMs: Long = System.currentTimeMillis()): Pool8128? {
        if (mint.length < 32) return null
        val e = cache8128[mint]
        val ttl = if (e?.pool == null) MISS_TTL_MS_8128 else POOL_TTL_MS_8128
        if (e == null || nowMs - e.atMs >= ttl) requestAsync8128(mint)
        if (e == null || nowMs - e.atMs >= 3 * POOL_TTL_MS_8128) return null
        return e.pool
    }

    /**
     * V5.0.8128 — the stand-in pair itself keeps pairAddress="" (BotService reads a blank pair + blank dexId as
     * SYNTHETIC so a seed price is never relabelled a live DexScreener quote, 7271); the real Raydium pool id
     * goes to the token map's blank pair address instead.
     */
    fun fillPairAddress8128(ts: com.lifecyclebot.data.TokenState, pool: Pool8128?) {
        val current = ts.pairAddress.ifBlank { ts.tokenMap.pairAddress }
        val id = pairAddressFill8128(current, ts.source, PumpFunDirectApi.isPumpFunMint(ts.mint), pool) ?: return
        ts.tokenMap.pairAddress = id
        try { PipelineHealthCollector.labelInc("RAYDIUM_POOL_FILLED_PAIR_8128") } catch (_: Throwable) {}
    }

    /** Blocking: the newest SOL/USDC-paired Raydium pools (scanner thread). Empty when Raydium does not answer. */
    fun fetchNewPools8128(): List<NewPool8128> {
        val pools = parseNewPools8128(get8128(LIST_URL_8128), System.currentTimeMillis() / 1000L)
        try { PipelineHealthCollector.labelInc(if (pools.isEmpty()) "RAYDIUM_NEW_POOLS_EMPTY_8128" else "RAYDIUM_NEW_POOLS_SERVED_8128") } catch (_: Throwable) {}
        return pools
    }

    private fun requestAsync8128(mint: String) {
        if (!inFlight8128.add(mint)) return
        try {
            workers8128.execute {
                try {
                    val pool = parsePool8128(get8128(MINT_URL_8128 + mint), mint)
                    cache8128[mint] = Entry8128(pool, System.currentTimeMillis())
                    try { PipelineHealthCollector.labelInc(if (pool == null) "RAYDIUM_MINT_POOL_MISS_8128" else "RAYDIUM_MINT_POOL_HIT_8128") } catch (_: Throwable) {}
                } finally {
                    inFlight8128.remove(mint)
                }
            }
        } catch (_: Throwable) {
            inFlight8128.remove(mint)   // shed: nothing reached the wire, the next call asks again
        }
        if (cache8128.size > 4_000) {
            val now = System.currentTimeMillis()
            cache8128.entries.removeIf { now - it.value.atMs > MISS_TTL_MS_8128 }
        }
    }

    private fun get8128(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AATE")
            .build()
        http.newCall(req).execute().use { resp ->
            if (HostCircuitInterceptor.isSyntheticBlock(resp) || !resp.isSuccessful) {
                try { PipelineHealthCollector.labelInc("RAYDIUM_8128_HTTP_${resp.code}") } catch (_: Throwable) {}
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        ErrorLogger.debug("RaydiumMintPools8128", "GET ${url.take(70)} failed: ${e.message}")
        null
    }
}
