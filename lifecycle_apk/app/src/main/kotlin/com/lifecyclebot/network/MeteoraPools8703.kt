package com.lifecyclebot.network

import org.json.JSONObject

/**
 * V5.0.8703 §METEORA_FROM_METEORA — owner: "it's meant to be the entire sol network, not just pump fun".
 *
 * Meteora pools reached the bot through one GeckoTerminal page (/dexes/meteora/pools) on the shared free Gecko budget.
 * 8689: `geckoterminal state=backoff (429)`, `Scanner METEORA_POOL IDLE intake=0`, and the chain feed's Meteora streams
 * parked as swap firehoses — so the venue the other launchpads trade on (Bags, Believe, Jupiter Studio and every Dynamic
 * Bonding Curve coin graduate into DAMM v2) delivered nothing. Meteora publishes the same pools itself, keyless, with a
 * server-side sort: https://damm-v2.datapi.meteora.ag/pools and https://dlmm.datapi.meteora.ag/pools
 * (docs.meteora.ag/api-reference: page, page_size <= 1000, sort_by=<field>:<asc|desc> incl. volume_30m / pool_created_at,
 * filter_by e.g. `is_blacklisted=false && tvl>=5000`; each pool: address, name, created_at, tvl, volume{30m,1h,...},
 * token_x / token_y {address, symbol, name, market_cap, ...}, launchpad, is_blacklisted).
 *
 * This file is the request list and the pure parser. Every row enters the same protected intake as any other scanner
 * source (SolanaMarketScanner: passesFilter -> emitWithRugcheck); nothing here admits, sizes or trades.
 */
object MeteoraPools8703 {
    private const val DAMM_V2 = "https://damm-v2.datapi.meteora.ag/pools"
    private const val DLMM = "https://dlmm.datapi.meteora.ag/pools"
    private const val LISTED = "is_blacklisted%3Dfalse"
    /** New pools are asked for above the depth the bot's own record separates on (MarketCohort8701: under $5k loses). */
    private const val LISTED_WITH_DEPTH = "is_blacklisted%3Dfalse%20%26%26%20tvl%3E%3D5000"

    /** (feed, url): what is trading now on each pool type, and DAMM v2's newest pools that hold real depth. */
    val REQUESTS_8703: List<Pair<String, String>> = listOf(
        "DAMM_V2_VOLUME" to "$DAMM_V2?page=1&page_size=50&sort_by=volume_30m%3Adesc&filter_by=$LISTED",
        "DAMM_V2_NEW" to "$DAMM_V2?page=1&page_size=50&sort_by=pool_created_at%3Adesc&filter_by=$LISTED_WITH_DEPTH",
        "DLMM_VOLUME" to "$DLMM?page=1&page_size=50&sort_by=volume_30m%3Adesc&filter_by=$LISTED",
    )

    private val QUOTES = setOf(
        "So11111111111111111111111111111111111111112",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
    )

    data class Pool8703(
        val mint: String, val symbol: String, val name: String, val pool: String,
        val liquidityUsd: Double, val volumeH1Usd: Double, val mcapUsd: Double,
        /** Pool age in hours; NaN when the API gave no creation time. */
        val ageHours: Double,
        val launchpad: String,
    )

    private fun num(o: JSONObject?, key: String): Double {
        if (o == null || o.isNull(key)) return Double.NaN
        val v = o.optDouble(key, Double.NaN)
        return if (v.isFinite()) v else o.optString(key, "").toDoubleOrNull() ?: Double.NaN
    }

    /** Pure: [createdAt] (epoch seconds or milliseconds) as hours before [nowMs]; NaN when absent or in the future. */
    internal fun ageHours8703(createdAt: Double, nowMs: Long): Double {
        if (!createdAt.isFinite() || createdAt <= 0.0) return Double.NaN
        val ms = if (createdAt < 100_000_000_000.0) createdAt * 1000.0 else createdAt
        val age = (nowMs - ms) / 3_600_000.0
        return if (age >= 0.0) age else Double.NaN
    }

    /**
     * Pure: the tradable coins in one /pools response. A pool is kept only when exactly one side is SOL / USDC / USDT (the
     * other side is the coin), it is not blacklisted, and its depth was stated. Malformed rows are skipped, never guessed.
     */
    fun parse8703(body: String, nowMs: Long): List<Pool8703> {
        val data = try { JSONObject(body).optJSONArray("data") } catch (_: Throwable) { null } ?: return emptyList()
        val out = ArrayList<Pool8703>(data.length())
        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            if (p.optBoolean("is_blacklisted", false)) continue
            val x = p.optJSONObject("token_x") ?: continue
            val y = p.optJSONObject("token_y") ?: continue
            val xMint = x.optString("address", ""); val yMint = y.optString("address", "")
            val coin = when {
                yMint in QUOTES && xMint !in QUOTES -> x
                xMint in QUOTES && yMint !in QUOTES -> y
                else -> null
            } ?: continue
            val mint = coin.optString("address", "")
            if (!HeliusSolanaScope7819.isSolanaMint7819(mint)) continue
            val tvl = num(p, "tvl")
            if (!tvl.isFinite() || tvl <= 0.0) continue
            val symbol = coin.optString("symbol", "").trim()
            out += Pool8703(
                mint = mint, symbol = symbol.ifBlank { mint.take(6) }, name = coin.optString("name", "").trim().ifBlank { symbol },
                pool = p.optString("address", ""), liquidityUsd = tvl,
                volumeH1Usd = num(p.optJSONObject("volume"), "1h").let { if (it.isFinite() && it >= 0.0) it else 0.0 },
                mcapUsd = num(coin, "market_cap").let { if (it.isFinite() && it >= 0.0) it else 0.0 },
                ageHours = ageHours8703(num(p, "created_at"), nowMs),
                launchpad = if (p.isNull("launchpad")) "" else p.optString("launchpad", "").trim(),
            )
        }
        return out
    }
}
