package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.HostCircuitInterceptor
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.0.7297 §A THE MARKET SCANNER, NOT ONLY A TOKEN SCANNER.
 *
 * Operator: "its meant to be a parallel sweep of providers. even helius can
 * feed the scanner. theres other free token sources already in the app as
 * well" … "each individual lane had its own scanner and brain to help the
 * scanner tune" … "its meant to have a market scanner not just a token
 * scanner".
 *
 * Every discovery source in SolanaMarketScanner answers "is there a new
 * token?". Most are DexScreener or GeckoTerminal endpoints; one DexScreener
 * 429 locks them all out for minutes and GeckoTerminal is dead, so on device
 * nearly all intake was PumpPortal launches and the specialist lanes whose
 * prey is established tokens saw nothing in their band.
 *
 * This sweeps the MARKET instead, from every free provider at once:
 *   Jupiter Tokens v2  toptrending / toptraded / toporganicscore (1h), recent
 *   Raydium api-v3     pools ranked by 24h volume
 *   Helius RPC         the latest PumpSwap AMM swaps, i.e. what is trading
 *                      on-chain right now
 * Raydium and Helius rows carry no market cap, so their mints are enriched
 * through one Jupiter /search call. Rows are merged by mint; every figure is
 * a provider value, and a field no provider supplied stays 0 (unknown).
 *
 * From the merged rows it keeps a market view per market-cap band: how many
 * tokens are active, breadth (the share up over 1h), median 1h move and
 * 1h volume share. LaneHunter7297 reads the rows and the band view; nothing
 * here gates, sizes or books a trade.
 */
object MarketSweep7297 {

    data class Row(
        val mint: String,
        val symbol: String,
        val name: String,
        val priceUsd: Double,
        val mcapUsd: Double,
        val liquidityUsd: Double,
        val volumeH1Usd: Double,
        val volumeH24Usd: Double,
        val priceChangeH1Pct: Double,
        val txCountH1: Int,
        val holders: Int,
        val organicScore: Double,
        val verified: Boolean,
        val ageHours: Double,
        val providers: Set<String>,
    )

    enum class Band(val minMcap: Double, val maxMcap: Double) {
        MICRO(0.0, 50_000.0),
        SMALL(50_000.0, 500_000.0),
        MID(500_000.0, 5_000_000.0),
        LARGE(5_000_000.0, 100_000_000.0),
        MAJOR(100_000_000.0, Double.MAX_VALUE);

        companion object {
            fun of(mcap: Double): Band? =
                if (!mcap.isFinite() || mcap <= 0.0) null
                else values().firstOrNull { mcap >= it.minMcap && mcap < it.maxMcap }
        }
    }

    data class BandState(
        val band: Band,
        val count: Int,
        val breadthPct: Double,
        val medianChangeH1Pct: Double,
        val volumeShare: Double,
    )

    data class Snapshot(
        val rows: List<Row>,
        val bands: Map<Band, BandState>,
        val providerRows: Map<String, Int>,
        val atMs: Long,
    )

    private const val JUP = "https://lite-api.jup.ag/tokens/v2"
    private const val RAYDIUM_POOLS =
        "https://api-v3.raydium.io/pools/info/list?poolType=all&poolSortField=volume24h&sortType=desc&pageSize=100&page=1"
    private const val PUMPSWAP_AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    private const val MIN_SWEEP_INTERVAL_MS = 45_000L
    private const val HELIUS_INTERVAL_MS = 120_000L
    private const val HELIUS_SIGNATURES = 15
    private const val PROVIDER_TIMEOUT_MS = 12_000L

    private val QUOTE_MINTS = setOf(
        "So11111111111111111111111111111111111111112",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
    )

    private val http = SharedHttpClient.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile private var last: Snapshot? = null
    @Volatile private var lastHeliusAtMs = 0L
    private val providerServed = ConcurrentHashMap<String, Long>()
    private val providerEmpty = ConcurrentHashMap<String, Long>()

    fun latest(): Snapshot? = last

    /**
     * Sweep every provider in parallel. Returns the cached snapshot when the
     * last sweep is younger than [MIN_SWEEP_INTERVAL_MS], so the scanner can
     * call this on every cycle without exceeding the free-tier rates.
     */
    suspend fun sweep(heliusKey: String): Snapshot? {
        val now = System.currentTimeMillis()
        last?.let { if (now - it.atMs < MIN_SWEEP_INTERVAL_MS) return it }
        val runHelius = heliusKey.isNotBlank() && now - lastHeliusAtMs >= HELIUS_INTERVAL_MS
        if (runHelius) lastHeliusAtMs = now

        val tasks = mutableListOf<Pair<String, suspend () -> List<Row>>>(
            "JUP_TRENDING" to { jupiterList("$JUP/toptrending/1h?limit=100", "JUP_TRENDING") },
            "JUP_TRADED" to { jupiterList("$JUP/toptraded/1h?limit=100", "JUP_TRADED") },
            "JUP_ORGANIC" to { jupiterList("$JUP/toporganicscore/1h?limit=100", "JUP_ORGANIC") },
            "JUP_RECENT" to { jupiterList("$JUP/recent?limit=100", "JUP_RECENT") },
            "RAYDIUM_VOLUME" to { raydiumPools() },
        )
        if (runHelius) tasks += "HELIUS_SWAPS" to { heliusSwaps(heliusKey) }

        val results = coroutineScope {
            tasks.map { (name, fn) ->
                async(Dispatchers.IO) {
                    val rows = try {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { fn() } ?: emptyList()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        ErrorLogger.debug("MarketSweep7297", "$name failed: ${t.message}")
                        emptyList()
                    }
                    name to rows
                }
            }.awaitAll()
        }

        val providerRows = results.associate { it.first to it.second.size }
        results.forEach { (name, rows) ->
            if (rows.isEmpty()) providerEmpty.merge(name, 1L, Long::plus)
            else providerServed.merge(name, 1L, Long::plus)
            try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_${name}_${if (rows.isEmpty()) "EMPTY" else "SERVED"}") } catch (_: Throwable) {}
        }

        val merged = merge(results.flatMap { it.second })
        val enriched = enrichMissingCaps(merged)
        if (enriched.isEmpty()) return last
        val snap = Snapshot(enriched, bandStates(enriched), providerRows, System.currentTimeMillis())
        last = snap
        return snap
    }

    /** Pure: combine rows for one mint, keeping every provider-supplied value. */
    fun merge(rows: List<Row>): List<Row> =
        rows.filter { it.mint.length >= 32 && it.mint !in QUOTE_MINTS }
            .groupBy { it.mint }
            .map { (_, g) ->
                fun pick(f: (Row) -> Double) = g.map(f).filter { it.isFinite() && it > 0.0 }.maxOrNull() ?: 0.0
                val named = g.firstOrNull { it.symbol.isNotBlank() } ?: g.first()
                Row(
                    mint = named.mint,
                    symbol = named.symbol,
                    name = named.name,
                    priceUsd = pick { it.priceUsd },
                    mcapUsd = pick { it.mcapUsd },
                    liquidityUsd = pick { it.liquidityUsd },
                    volumeH1Usd = pick { it.volumeH1Usd },
                    volumeH24Usd = pick { it.volumeH24Usd },
                    priceChangeH1Pct = g.firstOrNull { it.priceChangeH1Pct != 0.0 }?.priceChangeH1Pct ?: 0.0,
                    txCountH1 = g.maxOf { it.txCountH1 },
                    holders = g.maxOf { it.holders },
                    organicScore = g.maxOf { it.organicScore },
                    verified = g.any { it.verified },
                    ageHours = g.map { it.ageHours }.filter { it > 0.0 }.minOrNull() ?: 0.0,
                    providers = g.flatMap { it.providers }.toSet(),
                )
            }

    /** Pure: breadth, median move and volume share per market-cap band. */
    private fun bandStates(rows: List<Row>): Map<Band, BandState> {
        val byBand = rows.mapNotNull { r -> Band.of(r.mcapUsd)?.let { it to r } }.groupBy({ it.first }, { it.second })
        val totalVol = rows.sumOf { it.volumeH1Usd.coerceAtLeast(0.0) }
        return byBand.mapValues { (band, rs) ->
            val moves = rs.map { it.priceChangeH1Pct }.sorted()
            val median = if (moves.isEmpty()) 0.0 else moves[moves.size / 2]
            BandState(
                band = band,
                count = rs.size,
                breadthPct = if (rs.isEmpty()) 0.0 else 100.0 * rs.count { it.priceChangeH1Pct > 0.0 } / rs.size,
                medianChangeH1Pct = median,
                volumeShare = if (totalVol > 0.0) rs.sumOf { it.volumeH1Usd.coerceAtLeast(0.0) } / totalVol else 0.0,
            )
        }
    }

    // ── providers ────────────────────────────────────────────────────────

    private fun getBody(url: String): String? = try {
        http.newCall(
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AATE")
                .build()
        ).execute().use { resp ->
            if (HostCircuitInterceptor.isSyntheticBlock(resp)) {
                try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_LOCAL_CIRCUIT_BLOCK") } catch (_: Throwable) {}
                null
            } else if (!resp.isSuccessful) {
                try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_HTTP_${resp.code}") } catch (_: Throwable) {}
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        ErrorLogger.debug("MarketSweep7297", "GET ${url.take(60)} failed: ${e.message}")
        null
    }

    private fun jupiterList(url: String, provider: String): List<Row> {
        val body = getBody(url)?.trim() ?: return emptyList()
        if (!body.startsWith("[")) return emptyList()
        return parseJupiter(JSONArray(body), provider)
    }

    private fun parseJupiter(arr: JSONArray, provider: String): List<Row> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mint = o.optString("id", "").trim()
            if (mint.length < 32) continue
            val s1 = o.optJSONObject("stats1h")
            val s24 = o.optJSONObject("stats24h")
            fun vol(s: JSONObject?) = if (s == null) 0.0 else s.optDouble("buyVolume", 0.0).finite() + s.optDouble("sellVolume", 0.0).finite()
            val created = o.optJSONObject("firstPool")?.optString("createdAt", "").orEmpty()
            val createdMs = try { if (created.isBlank()) 0L else java.time.Instant.parse(created).toEpochMilli() } catch (_: Throwable) { 0L }
            out += Row(
                mint = mint,
                symbol = o.optString("symbol", ""),
                name = o.optString("name", ""),
                priceUsd = o.optDouble("usdPrice", 0.0).finite(),
                mcapUsd = o.optDouble("mcap", 0.0).finite(),
                liquidityUsd = o.optDouble("liquidity", 0.0).finite(),
                volumeH1Usd = vol(s1),
                volumeH24Usd = vol(s24),
                priceChangeH1Pct = s1?.optDouble("priceChange", 0.0)?.finite() ?: 0.0,
                txCountH1 = (s1?.optInt("numBuys", 0) ?: 0) + (s1?.optInt("numSells", 0) ?: 0),
                holders = o.optInt("holderCount", 0),
                organicScore = o.optDouble("organicScore", 0.0).finite(),
                verified = o.optBoolean("isVerified", false),
                ageHours = if (createdMs > 0L) ((now - createdMs) / 3_600_000.0).coerceAtLeast(0.0) else 0.0,
                providers = setOf(provider),
            )
        }
        return out
    }

    private fun raydiumPools(): List<Row> {
        val body = getBody(RAYDIUM_POOLS)?.trim() ?: return emptyList()
        if (!body.startsWith("{")) return emptyList()
        val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
        val nowSec = System.currentTimeMillis() / 1000.0
        val out = ArrayList<Row>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val a = p.optJSONObject("mintA") ?: continue
            val b = p.optJSONObject("mintB") ?: continue
            val base = when {
                b.optString("address") in QUOTE_MINTS && a.optString("address") !in QUOTE_MINTS -> a
                a.optString("address") in QUOTE_MINTS && b.optString("address") !in QUOTE_MINTS -> b
                else -> continue
            }
            val open = p.optString("openTime", "0").toDoubleOrNull() ?: 0.0
            out += Row(
                mint = base.optString("address"),
                symbol = base.optString("symbol", ""),
                name = base.optString("name", ""),
                priceUsd = 0.0,
                mcapUsd = 0.0,
                liquidityUsd = p.optDouble("tvl", 0.0).finite(),
                volumeH1Usd = 0.0,
                volumeH24Usd = p.optJSONObject("day")?.optDouble("volume", 0.0)?.finite() ?: 0.0,
                priceChangeH1Pct = 0.0,
                txCountH1 = 0,
                holders = 0,
                organicScore = 0.0,
                verified = false,
                ageHours = if (open > 0.0) ((nowSec - open) / 3600.0).coerceAtLeast(0.0) else 0.0,
                providers = setOf("RAYDIUM_VOLUME"),
            )
        }
        return out
    }

    private val JSON = "application/json".toMediaType()

    private fun rpc(key: String, payload: String): String? = try {
        http.newCall(
            Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=$key")
                .post(payload.toRequestBody(JSON)).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful || HostCircuitInterceptor.isSyntheticBlock(resp)) null else resp.body?.string()
        }
    } catch (e: Exception) { null }

    /** Mints traded in the latest PumpSwap AMM swaps, read from Helius RPC. */
    private fun heliusSwaps(key: String): List<Row> {
        val sigBody = rpc(
            key,
            """{"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress","params":["$PUMPSWAP_AMM",{"limit":$HELIUS_SIGNATURES}]}""",
        ) ?: return emptyList()
        val sigs = JSONObject(sigBody).optJSONArray("result") ?: return emptyList()
        val batch = JSONArray()
        for (i in 0 until sigs.length()) {
            val s = sigs.optJSONObject(i) ?: continue
            if (!s.isNull("err")) continue
            batch.put(JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", i); put("method", "getTransaction")
                put("params", JSONArray().apply {
                    put(s.optString("signature"))
                    put(JSONObject().apply { put("encoding", "jsonParsed"); put("maxSupportedTransactionVersion", 0) })
                })
            })
        }
        if (batch.length() == 0) return emptyList()
        val txBody = rpc(key, batch.toString())?.trim() ?: return emptyList()
        if (!txBody.startsWith("[")) return emptyList()
        val txs = JSONArray(txBody)
        val mints = linkedSetOf<String>()
        for (i in 0 until txs.length()) {
            val bals = txs.optJSONObject(i)?.optJSONObject("result")?.optJSONObject("meta")
                ?.optJSONArray("postTokenBalances") ?: continue
            for (j in 0 until bals.length()) {
                val m = bals.optJSONObject(j)?.optString("mint").orEmpty()
                if (m.length >= 32 && m !in QUOTE_MINTS) mints += m
            }
        }
        return mints.map {
            Row(it, "", "", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0, false, 0.0, setOf("HELIUS_SWAPS"))
        }
    }

    /** One Jupiter /search call fills cap, liquidity and 1h stats for rows that lack a cap. */
    private suspend fun enrichMissingCaps(rows: List<Row>): List<Row> {
        val missing = rows.filter { it.mcapUsd <= 0.0 }.map { it.mint }.take(100)
        if (missing.isEmpty()) return rows
        val found = withContext(Dispatchers.IO) {
            val body = getBody("$JUP/search?query=${missing.joinToString(",")}")?.trim()
            if (body == null || !body.startsWith("[")) emptyList() else parseJupiter(JSONArray(body), "JUP_SEARCH")
        }
        if (found.isEmpty()) return rows
        try { PipelineHealthCollector.labelInc("MARKET_SWEEP_7297_ENRICH_CALL_SERVED") } catch (_: Throwable) {}
        return merge(rows + found)
    }

    private fun Double.finite(): Double = if (isFinite()) this else 0.0

    fun statusLine(): String {
        val s = last ?: return "no sweep yet"
        val age = (System.currentTimeMillis() - s.atMs) / 1000
        val providers = s.providerRows.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val bands = Band.values().joinToString(" ") { b ->
            val st = s.bands[b]
            if (st == null) "${b.name}[0]"
            else "${b.name}[n=${st.count} up=${st.breadthPct.toInt()}% med=${"%+.1f".format(st.medianChangeH1Pct)}% vol=${(100 * st.volumeShare).toInt()}%]"
        }
        val served = providerServed.entries.joinToString(",") { "${it.key}:${it.value}/${it.value + (providerEmpty[it.key] ?: 0L)}" }
        return "rows=${s.rows.size} age=${age}s $providers | $bands | served=$served"
    }
}
