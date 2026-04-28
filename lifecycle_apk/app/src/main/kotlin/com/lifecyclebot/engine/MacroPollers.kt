package com.lifecyclebot.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * V5.9.357 — Macro Pollers
 *
 * Fills the previously-orphaned data feeds for three outer-ring AI layers
 * that have been voting 0 because nobody was pumping data into them:
 *
 *   • FundingRateAwarenessAI ← Binance public funding rate (5 min)
 *   • NewsShockAI            ← Gemini market-sentiment poll  (15 min)
 *   • StablecoinFlowAI       ← CoinGecko USDC/USDT 24h Δ supply (60 min)
 *
 * All polls are best-effort and fail-soft. No new API keys required:
 *   - Binance fapi/v1/premiumIndex is public/no-auth
 *   - CoinGecko /coins/{id} public endpoint (rate-limited but fine at 1/h)
 *   - Gemini sentiment uses the existing GeminiCopilot universal key
 *
 * Crucially this module is INDEPENDENT of the scanner and watchlist flow.
 * It only WRITES into the layer feeders; it never influences which tokens
 * get scanned or traded.
 */
object MacroPollers {

    private const val TAG = "MacroPollers"

    // V5.9.357 — Binance perps symbols we care about for funding feed.
    // Keep this list aligned with PerpsTraderAI's tracked universe.
    private val FUNDING_SYMBOLS = listOf(
        "SOLUSDT", "BTCUSDT", "ETHUSDT", "BNBUSDT", "XRPUSDT",
        "DOGEUSDT", "AVAXUSDT", "LINKUSDT", "MATICUSDT", "TRXUSDT",
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var running = false
    @Volatile private var fundingJob: Job? = null
    @Volatile private var newsJob: Job? = null
    @Volatile private var stablesJob: Job? = null

    // Rolling 15-minute sentiment history for news-shock slope estimation.
    private val sentimentHistory = AtomicReference<List<Pair<Long, Double>>>(emptyList())

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        ErrorLogger.info(TAG, "🛰️ MacroPollers START — funding(5m) news(15m) stables(60m)")

        fundingJob = scope.launch(Dispatchers.IO + SupervisorJob()) {
            // Stagger the very first call by a few seconds so we don't pile
            // network traffic on top of bot startup.
            delay(15_000L)
            while (isActive && running) {
                try { pollFunding() } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "funding poll failed: ${e.message}")
                }
                delay(5L * 60_000L)
            }
        }

        newsJob = scope.launch(Dispatchers.IO + SupervisorJob()) {
            delay(60_000L)
            while (isActive && running) {
                try { pollNewsSentiment() } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "news poll failed: ${e.message}")
                }
                delay(15L * 60_000L)
            }
        }

        stablesJob = scope.launch(Dispatchers.IO + SupervisorJob()) {
            delay(30_000L)
            while (isActive && running) {
                try { pollStables() } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "stables poll failed: ${e.message}")
                }
                delay(60L * 60_000L)
            }
        }
    }

    fun stop() {
        running = false
        fundingJob?.cancel(); fundingJob = null
        newsJob?.cancel(); newsJob = null
        stablesJob?.cancel(); stablesJob = null
    }

    // ── Funding (Binance public) ─────────────────────────────────────────
    private fun pollFunding() {
        val url = "https://fapi.binance.com/fapi/v1/premiumIndex"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body?.string() ?: return
            val arr = JSONArray(body)
            var fed = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sym = o.optString("symbol", "")
                if (sym !in FUNDING_SYMBOLS) continue
                // lastFundingRate is per 8-hour interval, e.g. 0.0001 = 0.01% per 8h.
                // Annualised APR = lastFundingRate * 3 (intervals/day) * 365.
                val perInterval = o.optString("lastFundingRate", "0.0").toDoubleOrNull() ?: 0.0
                val apr = perInterval * 3.0 * 365.0
                try {
                    com.lifecyclebot.v3.scoring.FundingRateAwarenessAI.updateFundingRate(sym, apr)
                    // Also strip USDT suffix to feed any layer keyed by base symbol.
                    val base = sym.removeSuffix("USDT")
                    if (base.isNotEmpty()) {
                        com.lifecyclebot.v3.scoring.FundingRateAwarenessAI.updateFundingRate(base, apr)
                    }
                    fed++
                } catch (_: Exception) {}
            }
            if (fed > 0) ErrorLogger.info(TAG, "🛰️ funding fed $fed symbols")
        }
    }

    // ── News sentiment (Gemini, reuses existing universal key) ───────────
    private fun pollNewsSentiment() {
        if (!GeminiCopilot.isConfigured()) return
        // Build a minimal prompt context from the trade history. Cheap and
        // self-contained so we don't depend on TradingMemory/SymbolicContext.
        val recentTrades = try { TradeHistoryStore.getAllTrades().takeLast(50) } catch (_: Exception) { emptyList() }
        val recentTokens = recentTrades.mapNotNull {
            it.mint.takeIf { m -> m.isNotBlank() }?.take(8)
        }.distinct().take(8)
        val recentWr = run {
            val sells = recentTrades.filter { it.side == "SELL" && it.pnlPct != 0.0 }
            if (sells.isEmpty()) 0.5
            else sells.count { it.pnlPct > 0 }.toDouble() / sells.size
        }
        val sentiment = try {
            GeminiCopilot.analyzeMarketSentiment(
                recentWinRate = recentWr * 100.0,
                recentTokens = recentTokens,
                avgHoldTime = 0.0,
                marketTrend = "NEUTRAL",
            )
        } catch (_: Exception) { null } ?: return

        // Normalise -100..+100 → -1..+1
        val now = System.currentTimeMillis()
        val normScore = (sentiment.sentimentScore / 100.0).coerceIn(-1.0, 1.0)
        // Slope = change vs 15 min ago (or earliest sample if shorter)
        val history = sentimentHistory.get()
        val updated = (history + (now to normScore))
            .filter { now - it.first <= 30L * 60_000L }
            .takeLast(20)
        sentimentHistory.set(updated)
        val cutoff = now - 15L * 60_000L
        val older = updated.firstOrNull { it.first <= cutoff } ?: updated.firstOrNull()
        val slope15m = if (older != null && older.first != now) {
            (normScore - older.second).coerceIn(-2.0, 2.0)
        } else 0.0
        try {
            com.lifecyclebot.v3.scoring.NewsShockAI.updateFromPoll(normScore, slope15m)
            ErrorLogger.info(TAG, "🛰️ news sent=${"%.2f".format(normScore)} slope=${"%.2f".format(slope15m)}")
        } catch (_: Exception) {}
    }

    // ── Stablecoin supply (CoinGecko) ────────────────────────────────────
    private fun pollStables() {
        val usdcDelta = fetchCoingeckoMarketCapDelta24h("usd-coin") ?: return
        val usdtDelta = fetchCoingeckoMarketCapDelta24h("tether") ?: return
        try {
            com.lifecyclebot.v3.scoring.StablecoinFlowAI.updateFromPoll(usdcDelta, usdtDelta)
        } catch (_: Exception) {}
    }

    private fun fetchCoingeckoMarketCapDelta24h(coinId: String): Double? {
        // Public endpoint, no key required. Use the lighter /coins/markets variant.
        val url = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=$coinId"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val arr = JSONArray(body)
            val o = arr.optJSONObject(0) ?: return null
            // market_cap_change_percentage_24h is the % delta we want (matches
            // StablecoinFlowAI's expectation of "24h % delta").
            val pct = o.opt("market_cap_change_percentage_24h")
            return when (pct) {
                is Number -> pct.toDouble()
                is String -> pct.toDoubleOrNull()
                else -> null
            }?.let { if (abs(it) > 50.0) null else it }
        }
    }
}
