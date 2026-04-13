package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DataPipeline — Structured Multi-Layer Data Flow
 *
 * Layer 1 — Discovery:     Dexscreener, Pump.fun
 * Layer 2 — Validation:    RugCheck, Solscan holders
 * Layer 3 — Live Signal:   Birdeye, Helius
 * Layer 4 — AI Features:   Computed alpha features
 *
 * Defensive rewrite:
 * - safer HTTP handling
 * - stronger null/shape guards
 * - bounded memory growth
 * - more honest confidence calculation
 * - thread-safe history tracking
 */
object DataPipeline {

    private const val TAG = "DataPipeline"

    private const val CACHE_TTL_MS = 30_000L
    private const val HOLDER_HISTORY_WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_HOLDER_HISTORY_POINTS = 32
    private const val MAX_TRACKED_WALLETS = 10_000
    private const val MAX_TOKENS_PER_WALLET = 16
    private const val CACHE_CLEANUP_MULTIPLIER = 10L

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class CachedData(
        val data: JSONObject,
        val timestamp: Long,
    )

    private data class WalletTokenState(
        val tokens: LinkedHashSet<String> = LinkedHashSet(),
    )

    private val cache = ConcurrentHashMap<String, CachedData>()
    private val holderHistory = ConcurrentHashMap<String, MutableList<HolderSnapshot>>()
    private val walletTokenMap = ConcurrentHashMap<String, WalletTokenState>()

    data class HolderSnapshot(
        val count: Int,
        val timestamp: Long,
    )

    data class AlphaSignals(
        val buyPressure: Double,
        val volumeH1: Double,
        val liquidityUsd: Double,
        val mcapUsd: Double,
        val pairAgeMinutes: Int,

        val holderAcceleration: Double,
        val whaleRatio: Double,
        val repeatWalletScore: Double,
        val buyClusteringScore: Double,
        val volumePriceDivergence: Double,

        val rugScore: Int,
        val topHolderPct: Double,
        val mintAuthorityDisabled: Boolean,
        val freezeAuthorityDisabled: Boolean,

        val txVelocity: Double,
        val priceChange5m: Double,
        val priceChange1h: Double,

        val overallGrade: String,
        val confidence: Double,
    )

    suspend fun getAlphaSignals(
        mint: String,
        cfg: BotConfig,
        onLog: (String) -> Unit = {},
    ): AlphaSignals? = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val dexDeferred = async { fetchDexscreener(mint) }
                val rugDeferred = async { fetchRugcheck(mint) }
                val holdersDeferred = async { fetchSolscanHolders(mint) }

                val dexData = dexDeferred.await()
                val rugData = rugDeferred.await()
                val holdersData = holdersDeferred.await()

                if (dexData == null) {
                    onLog("⚠️ No Dexscreener data for ${mint.take(8)}")
                    return@coroutineScope null
                }

                val pair = extractBestPair(dexData, mint)
                if (pair == null) {
                    onLog("⚠️ No usable pair in Dexscreener for ${mint.take(8)}")
                    return@coroutineScope null
                }

                val liquidityUsd = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val volumeH1 = pair.optJSONObject("volume")?.optDouble("h1", 0.0) ?: 0.0

                val marketCap = safeDouble(pair.opt("marketCap"))
                val fdv = safeDouble(pair.opt("fdv"))
                val mcapUsd = when {
                    marketCap > 0.0 -> marketCap
                    fdv > 0.0 -> fdv
                    else -> 0.0
                }

                val buysH1 = pair.optJSONObject("txns")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sellsH1 = pair.optJSONObject("txns")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val priceChange5m = pair.optJSONObject("priceChange")?.optDouble("m5", 0.0) ?: 0.0
                val priceChange1h = pair.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0

                val now = System.currentTimeMillis()
                val pairCreatedAt = parseLong(pair.opt("pairCreatedAt"))
                val pairAgeMinutes = if (pairCreatedAt in 1..now) {
                    ((now - pairCreatedAt) / 60_000L).toInt().coerceIn(0, 365 * 24 * 60)
                } else {
                    0
                }

                val totalTxns = buysH1 + sellsH1
                val buyPressure = if (totalTxns > 0) {
                    buysH1.toDouble() / totalTxns.toDouble()
                } else {
                    0.5
                }

                val txVelocity = totalTxns / 60.0

                val rugScore = parseRugScore(rugData)
                val mintAuthorityDisabled = parseMintAuthorityDisabled(rugData)
                val freezeAuthorityDisabled = parseFreezeAuthorityDisabled(rugData)

                val holderCount = parseHolderCount(holdersData)
                val topHolders = parseHolderArray(holdersData)
                val topHolderPct = calculateTopHolderPct(topHolders)
                val whaleRatio = calculateWhaleRatio(topHolders)

                val holderAcceleration = calculateHolderAcceleration(mint, holderCount)
                val repeatWalletScore = calculateRepeatWalletScore(mint, topHolders)
                val buyClusteringScore = calculateBuyClusteringScore(buysH1, sellsH1, txVelocity)
                val volumePriceDivergence = calculateVolumePriceDivergence(
                    volumeH1 = volumeH1,
                    liquidityUsd = liquidityUsd,
                    priceChange5m = priceChange5m,
                    priceChange1h = priceChange1h,
                )

                val dataCoverage = calculateDataCoverage(
                    dexOk = true,
                    rugOk = rugData != null,
                    holdersOk = holdersData != null,
                    totalTxns = totalTxns,
                    holderCount = holderCount,
                    liquidityUsd = liquidityUsd,
                    mcapUsd = mcapUsd,
                )

                val (grade, confidence) = calculateGrade(
                    buyPressure = buyPressure,
                    holderAcceleration = holderAcceleration,
                    whaleRatio = whaleRatio,
                    repeatWalletScore = repeatWalletScore,
                    volumePriceDivergence = volumePriceDivergence,
                    rugScore = rugScore,
                    topHolderPct = topHolderPct,
                    txVelocity = txVelocity,
                    dataCoverage = dataCoverage,
                )

                AlphaSignals(
                    buyPressure = buyPressure,
                    volumeH1 = volumeH1,
                    liquidityUsd = liquidityUsd,
                    mcapUsd = mcapUsd,
                    pairAgeMinutes = pairAgeMinutes,

                    holderAcceleration = holderAcceleration,
                    whaleRatio = whaleRatio,
                    repeatWalletScore = repeatWalletScore,
                    buyClusteringScore = buyClusteringScore,
                    volumePriceDivergence = volumePriceDivergence,

                    rugScore = rugScore,
                    topHolderPct = topHolderPct,
                    mintAuthorityDisabled = mintAuthorityDisabled,
                    freezeAuthorityDisabled = freezeAuthorityDisabled,

                    txVelocity = txVelocity,
                    priceChange5m = priceChange5m,
                    priceChange1h = priceChange1h,

                    overallGrade = grade,
                    confidence = confidence,
                )
            }
        } catch (e: Exception) {
            onLog("⚠️ DataPipeline error: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FETCH LAYERS
    // ═══════════════════════════════════════════════════════════════════

    private fun fetchDexscreener(mint: String): JSONObject? {
        val cacheKey = "dex_$mint"
        getCachedJson(cacheKey, CACHE_TTL_MS)?.let { return it }

        // FIX: Use token-pairs/v1 endpoint — more reliable, returns JSONArray
        // Wrap in {"pairs": [...]} so extractBestPair still works
        val raw = fetchRaw("https://api.dexscreener.com/token-pairs/v1/solana/$mint")
        if (raw != null) {
            return try {
                // token-pairs endpoint returns a JSONArray directly
                val arr = org.json.JSONArray(raw)
                val wrapper = JSONObject().put("pairs", arr)
                cache[cacheKey] = CachedData(wrapper, System.currentTimeMillis())
                wrapper
            } catch (_: Exception) {
                // fallback: maybe it returned an object with "pairs" already
                try {
                    val obj = JSONObject(raw)
                    cache[cacheKey] = CachedData(obj, System.currentTimeMillis())
                    obj
                } catch (_: Exception) { null }
            }
        }

        // Fallback to legacy endpoint
        return fetchJson("https://api.dexscreener.com/latest/dex/tokens/$mint")?.also {
            cache[cacheKey] = CachedData(it, System.currentTimeMillis())
        }
    }

    private fun fetchRugcheck(mint: String): JSONObject? {
        val cacheKey = "rug_$mint"
        getCachedJson(cacheKey, CACHE_TTL_MS * 2)?.let { return it }

        return fetchJson("https://api.rugcheck.xyz/v1/tokens/$mint/report/summary")?.also {
            cache[cacheKey] = CachedData(it, System.currentTimeMillis())
        }
    }

    private fun fetchSolscanHolders(mint: String): JSONObject? {
        val cacheKey = "holders_$mint"
        getCachedJson(cacheKey, CACHE_TTL_MS * 3)?.let { return it }

        return fetchJson("https://public-api.solscan.io/token/holders?tokenAddress=$mint&limit=20")?.also {
            cache[cacheKey] = CachedData(it, System.currentTimeMillis())
        }
    }

    private fun fetchRaw(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "lifecycle-bot-android/6.0")
                .build()
            val resp = http.newCall(request).execute()
            if (resp.isSuccessful) resp.body?.string() else null
        } catch (_: Exception) { null }
    }

    private fun fetchJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "lifecycle-bot-android/1.0")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body?.string()?.trim()
                if (body.isNullOrEmpty()) return null
                if (!body.startsWith("{")) return null

                JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getCachedJson(key: String, ttlMs: Long): JSONObject? {
        val cached = cache[key] ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < ttlMs) {
            cached.data
        } else {
            cache.remove(key)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PARSING HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun extractBestPair(dexData: JSONObject, mint: String): JSONObject? {
        val pairs = dexData.optJSONArray("pairs") ?: return null
        if (pairs.length() == 0) return null

        var best: JSONObject? = null
        var bestScore = -1.0

        for (i in 0 until pairs.length()) {
            val pair = pairs.optJSONObject(i) ?: continue
            val baseToken = pair.optJSONObject("baseToken")
            val baseAddr = baseToken?.optString("address", "") ?: ""

            // CRITICAL FIX: Only accept pairs where our token is the BASE.
            // If the token is the QUOTE (e.g. SOL/MEME), priceUsd reflects
            // the SOL price (~$150) not the meme token price — completely wrong data.
            if (baseAddr.isNotBlank() && baseAddr != mint) continue

            // Score by liquidity + volume for best pair selection
            val liq = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
            val vol = pair.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0
            val txns = pair.optJSONObject("txns")?.optJSONObject("h24")
            val cnt = (txns?.optInt("buys", 0) ?: 0) + (txns?.optInt("sells", 0) ?: 0)
            val score = liq * 1.5 + vol + cnt * 10.0

            if (score > bestScore) {
                bestScore = score
                best = pair
            }
        }

        return best
    }

    private fun parseRugScore(rugData: JSONObject?): Int {
        if (rugData == null) return 50
        return when {
            rugData.has("score_normalised") -> rugData.optInt("score_normalised", 50)
            rugData.has("scoreNormalized") -> rugData.optInt("scoreNormalized", 50)
            rugData.has("score") -> rugData.optInt("score", 50)
            else -> 50
        }.coerceIn(0, 100)
    }

    private fun parseMintAuthorityDisabled(rugData: JSONObject?): Boolean {
        if (rugData == null) return false
        return when {
            rugData.has("mintAuthorityDisabled") -> rugData.optBoolean("mintAuthorityDisabled", false)
            rugData.has("mint_authority_disabled") -> rugData.optBoolean("mint_authority_disabled", false)
            else -> false
        }
    }

    private fun parseFreezeAuthorityDisabled(rugData: JSONObject?): Boolean {
        if (rugData == null) return false
        return when {
            rugData.has("freezeAuthorityDisabled") -> rugData.optBoolean("freezeAuthorityDisabled", false)
            rugData.has("freeze_authority_disabled") -> rugData.optBoolean("freeze_authority_disabled", false)
            else -> false
        }
    }

    private fun parseHolderCount(holdersData: JSONObject?): Int {
        if (holdersData == null) return 0
        return when {
            holdersData.has("total") -> holdersData.optInt("total", 0)
            holdersData.has("totalCount") -> holdersData.optInt("totalCount", 0)
            holdersData.has("count") -> holdersData.optInt("count", 0)
            holdersData.has("data") -> holdersData.optJSONArray("data")?.length() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    private fun parseHolderArray(holdersData: JSONObject?): JSONArray? {
        if (holdersData == null) return null
        return when {
            holdersData.has("data") -> holdersData.optJSONArray("data")
            holdersData.has("holders") -> holdersData.optJSONArray("holders")
            else -> null
        }
    }

    private fun calculateDataCoverage(
        dexOk: Boolean,
        rugOk: Boolean,
        holdersOk: Boolean,
        totalTxns: Int,
        holderCount: Int,
        liquidityUsd: Double,
        mcapUsd: Double,
    ): Double {
        var score = 0.0

        if (dexOk) score += 40.0
        if (rugOk) score += 20.0
        if (holdersOk) score += 20.0
        if (totalTxns > 0) score += 10.0
        if (holderCount > 0) score += 5.0
        if (liquidityUsd > 0.0) score += 3.0
        if (mcapUsd > 0.0) score += 2.0

        return score.coerceIn(0.0, 100.0)
    }

    // ═══════════════════════════════════════════════════════════════════
    // FEATURE CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════

    private fun calculateHolderAcceleration(mint: String, currentCount: Int): Double {
        val history = holderHistory.getOrPut(mint) { mutableListOf() }
        val now = System.currentTimeMillis()

        synchronized(history) {
            history.add(HolderSnapshot(currentCount, now))
            history.removeAll { now - it.timestamp > HOLDER_HISTORY_WINDOW_MS }

            while (history.size > MAX_HOLDER_HISTORY_POINTS) {
                history.removeAt(0)
            }

            if (history.size < 2) return 0.0

            val oldest = history.first()
            val newest = history.last()
            val timeDeltaMinutes = (newest.timestamp - oldest.timestamp) / 60_000.0
            if (timeDeltaMinutes <= 0.0) return 0.0

            val holderDelta = newest.count - oldest.count
            return (holderDelta / timeDeltaMinutes).coerceIn(-100.0, 100.0)
        }
    }

    private fun calculateRepeatWalletScore(mint: String, topHolders: JSONArray?): Double {
        if (topHolders == null || topHolders.length() == 0) return 0.0

        var repeatCount = 0
        val walletsSeenThisToken = mutableSetOf<String>()
        val limit = min(topHolders.length(), 10)

        for (i in 0 until limit) {
            val holder = topHolders.optJSONObject(i) ?: continue
            val wallet = holder.optString("owner", holder.optString("address", "")).trim()
            if (wallet.isEmpty()) continue
            if (!walletsSeenThisToken.add(wallet)) continue

            val state = walletTokenMap.getOrPut(wallet) { WalletTokenState() }
            synchronized(state) {
                if (state.tokens.any { it != mint }) {
                    repeatCount++
                }

                if (!state.tokens.contains(mint)) {
                    state.tokens.add(mint)
                    while (state.tokens.size > MAX_TOKENS_PER_WALLET) {
                        val first = state.tokens.firstOrNull() ?: break
                        state.tokens.remove(first)
                    }
                }
            }
        }

        return (repeatCount.toDouble() / max(walletsSeenThisToken.size, 1) * 100.0)
            .coerceIn(0.0, 100.0)
    }

    private fun calculateBuyClusteringScore(buys: Int, sells: Int, txVelocity: Double): Double {
        val total = buys + sells
        if (total < 10) return 0.0

        val buyRatio = buys.toDouble() / total.toDouble()

        return when {
            txVelocity > 8.0 && buyRatio > 0.90 -> 85.0
            txVelocity > 5.0 && buyRatio > 0.85 -> 70.0
            txVelocity > 3.0 && buyRatio > 0.80 -> 45.0
            txVelocity > 5.0 -> 25.0
            buyRatio > 0.90 -> 20.0
            else -> 0.0
        }
    }

    private fun calculateVolumePriceDivergence(
        volumeH1: Double,
        liquidityUsd: Double,
        priceChange5m: Double,
        priceChange1h: Double,
    ): Double {
        if (liquidityUsd <= 0.0) return 0.0

        val volLiqRatio = volumeH1 / liquidityUsd
        val priceFlatShort = abs(priceChange5m) < 3.0
        val priceFlatHour = abs(priceChange1h) < 5.0
        val priceWeakDown = priceChange1h < -5.0

        return when {
            volLiqRatio > 0.50 && priceFlatShort && priceFlatHour -> 80.0
            volLiqRatio > 0.30 && priceFlatShort && priceFlatHour -> 50.0
            volLiqRatio > 0.50 && priceWeakDown -> 70.0
            volLiqRatio > 0.25 && priceWeakDown -> 45.0
            else -> 0.0
        }
    }

    private fun calculateTopHolderPct(topHolders: JSONArray?): Double {
        if (topHolders == null || topHolders.length() == 0) return 0.0

        var totalPct = 0.0
        val limit = min(topHolders.length(), 10)

        for (i in 0 until limit) {
            val holder = topHolders.optJSONObject(i) ?: continue
            totalPct += holder.optDouble("percentage", holder.optDouble("pct", 0.0))
        }

        return totalPct.coerceIn(0.0, 100.0)
    }

    private fun calculateWhaleRatio(topHolders: JSONArray?): Double {
        return (calculateTopHolderPct(topHolders) / 100.0).coerceIn(0.0, 1.0)
    }

    private fun calculateGrade(
        buyPressure: Double,
        holderAcceleration: Double,
        whaleRatio: Double,
        repeatWalletScore: Double,
        volumePriceDivergence: Double,
        rugScore: Int,
        topHolderPct: Double,
        txVelocity: Double,
        dataCoverage: Double,
    ): Pair<String, Double> {
        var score = 50.0

        if (buyPressure > 0.55) score += 15.0
        if (buyPressure > 0.60) score += 10.0
        if (holderAcceleration > 5.0) score += 10.0
        if (rugScore > 70) score += 10.0
        if (txVelocity > 1.0) score += 5.0

        if (buyPressure < 0.45) score -= 20.0
        if (whaleRatio > 0.50) score -= 15.0
        if (repeatWalletScore > 50.0) score -= 20.0
        if (volumePriceDivergence > 50.0) score -= 25.0
        if (rugScore < 40) score -= 25.0
        if (topHolderPct > 50.0) score -= 15.0
        if (holderAcceleration < -5.0) score -= 15.0

        score = score.coerceIn(0.0, 100.0)

        val grade = when {
            score >= 80.0 -> "A"
            score >= 65.0 -> "B"
            score >= 50.0 -> "C"
            score >= 35.0 -> "D"
            else -> "F"
        }

        val confidence = dataCoverage.coerceIn(20.0, 100.0)
        return grade to confidence
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOGGING
    // ═══════════════════════════════════════════════════════════════════

    fun formatAlphaSignals(mint: String, signals: AlphaSignals): String {
        val gradeEmoji = when (signals.overallGrade) {
            "A" -> "🅰️"
            "B" -> "🅱️"
            "C" -> "©️"
            "D" -> "🔸"
            else -> "❌"
        }

        val warnings = mutableListOf<String>()
        if (signals.volumePriceDivergence > 50.0) warnings.add("DISTRIB")
        if (signals.repeatWalletScore > 50.0) warnings.add("BOTS")
        if (signals.whaleRatio > 0.5) warnings.add("WHALES")
        if (signals.holderAcceleration < -3.0) warnings.add("EXODUS")

        return buildString {
            append("$gradeEmoji ${mint.take(6)}... | ")
            append("Buy:${(signals.buyPressure * 100).toInt()}% ")
            append("Acc:${signals.holderAcceleration.toInt()}/min ")
            append("RC:${signals.rugScore} ")
            append("Vol/Div:${signals.volumePriceDivergence.toInt()} ")
            append("Conf:${signals.confidence.toInt()}%")
            if (warnings.isNotEmpty()) {
                append(" ⚠️ ${warnings.joinToString(",")}")
            }
        }
    }

    fun cleanup() {
        val now = System.currentTimeMillis()

        cache.entries.removeIf { now - it.value.timestamp > CACHE_TTL_MS * CACHE_CLEANUP_MULTIPLIER }

        holderHistory.entries.removeIf { (_, history) ->
            synchronized(history) {
                history.removeAll { now - it.timestamp > HOLDER_HISTORY_WINDOW_MS }
                history.isEmpty()
            }
        }

        if (walletTokenMap.size > MAX_TRACKED_WALLETS) {
            val overflow = walletTokenMap.size - MAX_TRACKED_WALLETS
            val keysToRemove = walletTokenMap.keys.take(overflow)
            keysToRemove.forEach { walletTokenMap.remove(it) }
        }

        walletTokenMap.values.forEach { state ->
            synchronized(state) {
                while (state.tokens.size > MAX_TOKENS_PER_WALLET) {
                    val first = state.tokens.firstOrNull() ?: break
                    state.tokens.remove(first)
                }
            }
        }
    }

    private fun parseLong(value: Any?): Long {
        return when (value) {
            null -> 0L
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    private fun safeDouble(value: Any?): Double {
        return when (value) {
            null -> 0.0
            is Double -> if (value.isFinite()) value else 0.0
            is Float -> if (value.isFinite()) value.toDouble() else 0.0
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> {
                val d = value.toDouble()
                if (d.isFinite()) d else 0.0
            }
            is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            else -> 0.0
        }
    }
}
