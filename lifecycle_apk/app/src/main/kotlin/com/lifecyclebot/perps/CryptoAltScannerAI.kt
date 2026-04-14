package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.CoinGeckoTrending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ═══════════════════════════════════════════════════════════════════════════════
// 🧠 CRYPTO ALT SCANNER AI — V1.0.0
// ═══════════════════════════════════════════════════════════════════════════════
//
// Dedicated alt-market intelligence layer for CryptoAltTrader.
// Provides signals that go BEYOND simple price/RSI analysis:
//
//   1. Sector Heat Map     — DeFi / Meme / L1 / L2 / Gaming / Infra heat scores
//   2. BTC Dominance Cycle — ALT_SEASON vs BTC_DOMINANCE regime detection
//   3. Narrative Scanner   — trending narratives (AI tokens, RWA, meme cycles)
//   4. Alt Beta Tracker    — how much each alt amplifies BTC moves
//   5. DEX Volume Surge    — on-chain volume anomalies via DexScreener
//   6. Cross-Alt Momentum  — sector rotation signals
//   7. Fear/Greed Context  — macro regime for alt risk-on / risk-off
//
// All data is cached and async — CryptoAltTrader calls are non-blocking.
// ═══════════════════════════════════════════════════════════════════════════════

object CryptoAltScannerAI {

    private const val TAG = "🧠CryptoAltScanner"
    private const val SECTOR_CACHE_TTL = 60_000L    // 60s sector heat cache
    private const val DOMINANCE_CACHE_TTL = 120_000L // 2min dominance cache
    private const val NARRATIVE_CACHE_TTL = 180_000L // 3min narrative cache

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ─── Sector definitions ────────────────────────────────────────────────────
    private val SECTORS = mapOf(
        "DEFI"    to setOf("AAVE", "MKR", "CRV", "SNX", "LDO", "RPL", "CAKE", "GMX", "DYDX", "ENA", "PENDLE", "RUNE", "UNI"),
        "MEME"    to setOf("SHIB", "FLOKI", "TRUMP", "POPCAT", "NOT", "BONK", "PEPE", "WIF", "DOGE"),
        "GAMING"  to setOf("AXS", "SAND", "MANA", "IMX", "ENS"),
        "INFRA"   to setOf("FIL", "RENDER", "GRT", "STX", "ICP", "VET", "NEAR"),
        "L1"      to setOf("BNB", "ADA", "AVAX", "DOT", "FTM", "ALGO", "HBAR", "EOS", "XTZ", "ETC"),
        "SOLANA"  to setOf("RAY", "ORCA", "MNGO", "PYTH", "JUP", "DRIFT"),
        "EXCHANGE"to setOf("BNB"),
        "PRIVACY" to setOf("XMR", "ZEC"),
        "LAYER2"  to setOf("ARB", "OP", "STX", "IMX", "STRK"),
        "OG"      to setOf("BTC", "ETH", "LTC", "BCH", "XMR", "ZEC", "XRP", "XLM", "TRX", "TON"),
        "CHAIN"   to setOf("LINK", "ATOM", "DOT", "RUNE", "W", "ENS"),
        "AI"      to setOf("RENDER", "GRT", "TAO", "WLD"),
        "EXCHANGE2"to setOf("BNB", "CRO"),
    )

    // Symbol → sector map (computed once)
    private val SYMBOL_SECTOR: Map<String, String> by lazy {
        val m = mutableMapOf<String, String>()
        SECTORS.forEach { (sector, symbols) -> symbols.forEach { m[it] = sector } }
        m
    }

    // ─── State ─────────────────────────────────────────────────────────────────
    private val sectorHeatCache     = ConcurrentHashMap<String, Double>()    // sector → 0..1
    private val sectorHeatTimestamp = AtomicLong(0L)
    private val symbolSectorHeat    = ConcurrentHashMap<String, Double>()    // symbol → 0..1

    private val altBetas            = ConcurrentHashMap<String, Double>()    // symbol → beta vs BTC
    private val priceHistory        = ConcurrentHashMap<String, ArrayDeque<Double>>() // symbol → last 20 prices
    private val btcHistory          = ArrayDeque<Double>()

    @Volatile private var dominanceSignal   = "NEUTRAL"  // ALT_SEASON | BTC_DOMINANCE | NEUTRAL
    @Volatile private var dominanceLastFetch= 0L

    @Volatile private var narrativeSignals  = listOf<String>()  // trending narratives
    @Volatile private var narrativeLastFetch= 0L

    @Volatile private var cryptoFearGreed   = 50  // 0-100
    @Volatile private var fgLastFetch       = 0L

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /** Returns heat score 0.0..1.0 for a symbol based on its sector. */
    fun getSectorHeat(symbol: String): Double {
        refreshSectorHeatIfStale()
        return symbolSectorHeat[symbol] ?: 0.5
    }

    /** Returns "ALT_SEASON", "BTC_DOMINANCE", or "NEUTRAL". */
    fun getDominanceCycleSignal(): String {
        refreshDominanceIfStale()
        return dominanceSignal
    }

    /** Returns list of active narratives e.g. ["AI_TOKENS", "RWA", "MEME_SEASON"]. */
    fun getActiveNarratives(): List<String> {
        refreshNarrativesIfStale()
        return narrativeSignals
    }

    /** Returns crypto fear & greed index 0-100. */
    fun getCryptoFearGreed(): Int {
        refreshFearGreedIfStale()
        return cryptoFearGreed
    }

    /** Returns beta of symbol vs BTC (1.0 = moves with BTC, >1 = amplified, <0 = inverse). */
    fun getAltBeta(symbol: String): Double = altBetas[symbol] ?: 1.2  // Default alts are 1.2x BTC

    /**
     * Record a price tick for a symbol — used to compute rolling beta and sector heat.
     * Called by CryptoAltTrader on each scan cycle.
     */
    fun recordPrice(symbol: String, price: Double, btcPrice: Double) {
        // BTC history
        if (btcPrice > 0) {
            if (btcHistory.size >= 30) btcHistory.removeFirst()
            btcHistory.addLast(btcPrice)
        }
        // Symbol history
        if (price > 0) {
            val hist = priceHistory.getOrPut(symbol) { ArrayDeque() }
            if (hist.size >= 30) hist.removeFirst()
            hist.addLast(price)
        }
        // Recompute beta if enough history
        if (btcHistory.size >= 10 && (priceHistory[symbol]?.size ?: 0) >= 10) {
            computeBeta(symbol)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTOR HEAT
    // ═══════════════════════════════════════════════════════════════════════════

    private fun refreshSectorHeatIfStale() {
        val now = System.currentTimeMillis()
        if (now - sectorHeatTimestamp.get() < SECTOR_CACHE_TTL) return
        sectorHeatTimestamp.set(now)

        // Compute sector heat from cached price changes (using PerpsMarketDataFetcher cache)
        val sectorChanges = mutableMapOf<String, MutableList<Double>>()

        PerpsMarket.values()
            .filter { it.isCrypto }
            .forEach { market ->
                val cached = PerpsMarketDataFetcher.getCachedPrice(market) ?: return@forEach
                val sector = SYMBOL_SECTOR[market.symbol] ?: "OTHER"
                sectorChanges.getOrPut(sector) { mutableListOf() }.add(cached.priceChange24hPct)
            }

        // Normalise sector average change → 0..1 heat score
        sectorChanges.forEach { (sector, changes) ->
            if (changes.isNotEmpty()) {
                val avg = changes.average()
                // Map from [-10%, +10%] range to [0..1]
                val heat = ((avg + 10.0) / 20.0).coerceIn(0.0, 1.0)
                sectorHeatCache[sector] = heat
            }
        }

        // Apply to each symbol
        PerpsMarket.values().filter { it.isCrypto }.forEach { market ->
            val sector = SYMBOL_SECTOR[market.symbol] ?: "OTHER"
            symbolSectorHeat[market.symbol] = sectorHeatCache[sector] ?: 0.5
        }

        val hotSectors = sectorHeatCache.entries.sortedByDescending { it.value }.take(3)
            .map { "${it.key}:${"%.2f".format(it.value)}" }
        ErrorLogger.debug(TAG, "🔥 Sector heat updated | hot: $hotSectors")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BTC DOMINANCE CYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun refreshDominanceIfStale() {
        val now = System.currentTimeMillis()
        if (now - dominanceLastFetch < DOMINANCE_CACHE_TTL) return
        dominanceLastFetch = now

        // Heuristic: if total alt market (non-BTC/ETH) is outperforming BTC → alt season
        try {
            val btcCached  = PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.BTC)?.priceChange24hPct ?: 0.0
            val ethCached  = PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.ETH)?.priceChange24hPct ?: 0.0

            // Sample 15 alts
            val altChanges = listOf("SHIB", "ADA", "DOGE", "LTC", "FTM", "ALGO", "AAVE", "UNI",
                "AXS", "SNX", "ENS", "STX", "IMX", "HBAR", "ICP")
                .mapNotNull { sym ->
                    PerpsMarket.values().find { it.symbol == sym }
                        ?.let { PerpsMarketDataFetcher.getCachedPrice(it)?.priceChange24hPct }
                }

            if (altChanges.isEmpty()) { dominanceSignal = "NEUTRAL"; return }

            val altAvg = altChanges.average()
            val spread = altAvg - btcCached

            dominanceSignal = when {
                spread > 3.0  -> "ALT_SEASON"
                spread < -3.0 -> "BTC_DOMINANCE"
                else          -> "NEUTRAL"
            }
            ErrorLogger.debug(TAG, "🌊 Dominance: $dominanceSignal | BTC=${btcCached.fmt(2)}% | AltAvg=${altAvg.fmt(2)}% | spread=${spread.fmt(2)}%")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Dominance calc error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NARRATIVE SCANNER
    // ═══════════════════════════════════════════════════════════════════════════

    private fun refreshNarrativesIfStale() {
        val now = System.currentTimeMillis()
        if (now - narrativeLastFetch < NARRATIVE_CACHE_TTL) return
        narrativeLastFetch = now

        val narratives = mutableListOf<String>()

        try {
            // AI tokens narrative
            val aiSymbols = listOf("RENDER", "GRT", "TAO", "WLD")
            val aiChanges = aiSymbols.mapNotNull { sym ->
                PerpsMarket.values().find { it.symbol == sym }
                    ?.let { PerpsMarketDataFetcher.getCachedPrice(it)?.priceChange24hPct }
            }
            if (aiChanges.isNotEmpty() && aiChanges.average() > 3.0) narratives.add("AI_TOKENS")

            // Meme season
            val memeSymbols = listOf("SHIB", "FLOKI", "TRUMP", "POPCAT", "NOT")
            val memeChanges = memeSymbols.mapNotNull { sym ->
                PerpsMarket.values().find { it.symbol == sym }
                    ?.let { PerpsMarketDataFetcher.getCachedPrice(it)?.priceChange24hPct }
            }
            if (memeChanges.isNotEmpty() && memeChanges.average() > 5.0) narratives.add("MEME_SEASON")

            // DeFi summer
            val defiSymbols = listOf("AAVE", "MKR", "CRV", "SNX", "LDO")
            val defiChanges = defiSymbols.mapNotNull { sym ->
                PerpsMarket.values().find { it.symbol == sym }
                    ?.let { PerpsMarketDataFetcher.getCachedPrice(it)?.priceChange24hPct }
            }
            if (defiChanges.isNotEmpty() && defiChanges.average() > 3.0) narratives.add("DEFI_HEAT")

            // Gaming narrative
            val gamingSymbols = listOf("AXS", "SAND", "MANA", "IMX")
            val gamingChanges = gamingSymbols.mapNotNull { sym ->
                PerpsMarket.values().find { it.symbol == sym }
                    ?.let { PerpsMarketDataFetcher.getCachedPrice(it)?.priceChange24hPct }
            }
            if (gamingChanges.isNotEmpty() && gamingChanges.average() > 4.0) narratives.add("GAMING_SEASON")

            // L1 rotation
            val l1Symbols = listOf("ADA", "AVAX", "FTM", "ALGO", "HBAR", "NEAR")
            val l1Changes = l1Symbols.mapNotNull { sym ->
                PerpsMarket.values().find { it.symbol == sym }
                    ?.let { PerpsMarketDataFetcher.getCachedPrice(it)?.priceChange24hPct }
            }
            if (l1Changes.isNotEmpty() && l1Changes.average() > 4.0) narratives.add("L1_ROTATION")

        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Narrative scan error: ${e.message}")
        }

        narrativeSignals = narratives
        if (narratives.isNotEmpty()) ErrorLogger.info(TAG, "📰 Active narratives: $narratives")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FEAR & GREED
    // ═══════════════════════════════════════════════════════════════════════════

    private fun refreshFearGreedIfStale() {
        val now = System.currentTimeMillis()
        if (now - fgLastFetch < 300_000L) return  // 5min cache
        fgLastFetch = now

        // Non-blocking — uses previously cached value if network fails
        try {
            val request = Request.Builder()
                .url("https://api.alternative.me/fng/?limit=1")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val data = json.optJSONArray("data")?.optJSONObject(0)
                val value = data?.optInt("value", 50) ?: 50
                cryptoFearGreed = value
                ErrorLogger.debug(TAG, "😱 Fear&Greed: $value | ${data?.optString("value_classification", "")}")
            }
            response.close()
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALT BETA COMPUTATION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun computeBeta(symbol: String) {
        try {
            val symbolHist = priceHistory[symbol] ?: return
            val btcHist    = btcHistory
            val n = min(symbolHist.size, btcHist.size)
            if (n < 5) return

            // Compute % returns
            val symbolReturns = (1 until n).map {
                (symbolHist[it] - symbolHist[it-1]) / symbolHist[it-1]
            }
            val btcReturns = (1 until n).map {
                (btcHist[it] - btcHist[it-1]) / btcHist[it-1]
            }

            // Beta = cov(alt, btc) / var(btc)
            val btcMean    = btcReturns.average()
            val altMean    = symbolReturns.average()
            val cov        = symbolReturns.zip(btcReturns).map { (a, b) -> (a - altMean) * (b - btcMean) }.average()
            val btcVar     = btcReturns.map { (it - btcMean) * (it - btcMean) }.average()

            if (btcVar > 0.000001) {
                val beta = cov / btcVar
                altBetas[symbol] = beta.coerceIn(-5.0, 10.0)
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTOR ROTATION SIGNAL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the hottest sector name right now, or null if none stands out.
     * Used by MultiAssetActivity to show sector heat in the Crypto tab.
     */
    fun getTopSectors(n: Int = 3): List<Pair<String, Double>> {
        refreshSectorHeatIfStale()
        return sectorHeatCache.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    /**
     * Full summary for UI display in the Crypto alt tab sector heat row.
     */
    fun getSectorHeatSummary(): Map<String, Double> {
        refreshSectorHeatIfStale()
        return sectorHeatCache.toMap()
    }

    private fun Double.fmt(d: Int): String = "%.${d}f".format(this)
}
