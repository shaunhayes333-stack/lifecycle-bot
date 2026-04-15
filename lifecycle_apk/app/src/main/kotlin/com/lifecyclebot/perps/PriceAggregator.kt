package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🌐 UNIFIED PRICE AGGREGATOR - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Single entry point for ALL price data sources. Tries sources in priority order
 * until one succeeds. Tracks success rates per source for optimization.
 * 
 * SUPPORTED SOURCES (20+):
 * ─────────────────────────────────────────────────────────────────────────────
 * ORACLES:
 *   • Pyth Network (primary for stocks/crypto)
 *   • Switchboard (Solana-native backup)
 *   • Chainlink (limited Solana support)
 * 
 * CRYPTO:
 *   • Jupiter Price API
 *   • Birdeye
 *   • DexScreener
 *   • CoinGecko
 *   • CoinMarketCap
 *   • Binance
 *   • Coinbase
 *   • Kraken
 * 
 * STOCKS:
 *   • Yahoo Finance (v7 + v8)
 *   • Stooq
 *   • CNBC
 *   • Google Finance
 *   • Finnhub
 *   • Alpha Vantage
 *   • Twelve Data
 *   • IEX Cloud
 *   • Polygon.io
 *   • Financial Modeling Prep
 *   • Tiingo
 *   • Marketstack
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PriceAggregator {
    
    private const val TAG = "🌐PriceAgg"
    
    // Unified price cache
    private val priceCache = ConcurrentHashMap<String, CachedPrice>()
    // V5.9.5: Track previous prices to calculate real 24h change for on-chain sources
    private val prevPriceCache = ConcurrentHashMap<String, Double>()
    private val prevPriceTime  = ConcurrentHashMap<String, Long>()

    /** Calculate real % change from price delta vs previous cached price (max 24h old) */
    private fun calcChange(symbol: String, currentPrice: Double): Double {
        val prev = prevPriceCache[symbol] ?: run {
            prevPriceCache[symbol] = currentPrice
            prevPriceTime[symbol]  = System.currentTimeMillis()
            return 0.0
        }
        val ageMs = System.currentTimeMillis() - (prevPriceTime[symbol] ?: 0L)
        // Only use delta if we have a reference price from > 1 min ago
        return if (ageMs > 60_000L && prev > 0.0) {
            val pct = (currentPrice - prev) / prev * 100.0
            // Update reference periodically (every ~1h) so change doesn't accumulate forever
            if (ageMs > 3_600_000L) {
                prevPriceCache[symbol] = currentPrice
                prevPriceTime[symbol]  = System.currentTimeMillis()
            }
            pct
        } else 0.0
    }
    private const val CACHE_TTL_MS = 3_000L  // 3 second cache
    
    // Source success tracking
    private val sourceSuccessCount = ConcurrentHashMap<String, AtomicInteger>()
    private val sourceFailCount = ConcurrentHashMap<String, AtomicInteger>()
    
    // HTTP Client (shared)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    data class CachedPrice(
        val price: Double,
        val change24h: Double,
        val source: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class PriceResult(
        val price: Double,
        val change24h: Double = 0.0,
        val source: String,
        val confidence: Double = 1.0
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get price for ANY symbol - tries all sources automatically
     */
    suspend fun getPrice(symbol: String, assetType: AssetType = AssetType.AUTO): PriceResult? = withContext(Dispatchers.IO) {
        // Check cache first
        val cached = priceCache[symbol]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return@withContext PriceResult(cached.price, cached.change24h, cached.source)
        }
        
        // Determine asset type if AUTO
        val type = if (assetType == AssetType.AUTO) detectAssetType(symbol) else assetType
        
        // Get sources for this asset type
        val sources = getSourcesForType(type)
        
        // Try each source
        for (source in sources) {
            try {
                val result = fetchFromSource(symbol, source)
                if (result != null && result.price > 0) {
                    // Cache it
                    priceCache[symbol] = CachedPrice(result.price, result.change24h, result.source)
                    trackSuccess(result.source)
                    return@withContext result
                }
            } catch (e: Exception) {
                trackFailure(source.name)
                ErrorLogger.debug(TAG, "${source.name} failed for $symbol")
            }
        }
        
        ErrorLogger.warn(TAG, "⚠️ ALL ${sources.size} sources failed for $symbol")
        return@withContext null
    }
    
    /**
     * Batch fetch prices for multiple symbols
     */
    suspend fun getPrices(symbols: List<String>): Map<String, PriceResult> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, PriceResult>()
        
        // Parallel fetch
        symbols.map { symbol ->
            async {
                val result = getPrice(symbol)
                if (result != null) {
                    results[symbol] = result
                }
            }
        }.awaitAll()
        
        return@withContext results.toMap()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ASSET TYPE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class AssetType {
        AUTO, CRYPTO, STOCK, FOREX, COMMODITY, ETF
    }
    
    private fun detectAssetType(symbol: String): AssetType {
        return when {
            // Crypto detection
            symbol in listOf("BTC", "ETH", "SOL", "BNB", "XRP", "ADA", "DOGE", "AVAX", "DOT",
                "LINK", "MATIC", "SHIB", "LTC", "ATOM", "UNI", "ARB", "OP", "APT", "SUI", "SEI",
                "INJ", "TIA", "JUP", "PEPE", "WIF", "BONK", "PYTH", "RAY", "ORCA",
                "NEAR", "FTM", "ALGO", "HBAR", "ICP", "VET", "FIL", "RENDER", "GRT", "AAVE",
                "MKR", "SNX", "CRV", "RUNE", "STX", "IMX", "SAND", "MANA", "AXS", "ENS",
                "LDO", "RPL", "MNGO", "DRIFT",
                // V5.8
                "TRX", "TON", "BCH", "XLM", "XMR", "ETC", "ZEC", "XTZ", "EOS",
                "CAKE", "GMX", "DYDX", "ENA", "PENDLE",
                "WLD", "JTO", "W", "STRK", "TAO",
                "FLOKI", "NOT", "POPCAT", "TRUMP",
                // V5.9: expanded to match full PerpsMarket enum
                "FIL", "WIN", "ONT", "ONE", "KMNO", "CVXF", "TURBO", "XDC", "FTM",
                "FXS", "WAVES", "GALA", "BABYDOGE", "STETH", "THETA", "EGLD", "ZIL",
                "IOTA", "DASH", "ZEN", "DCR", "QTUM", "SC", "BTT", "JST", "KAS",
                "COTI", "CELR", "ROSE", "CELO", "FLOW", "KAVA", "FLR", "ICX", "ZRX",
                "ANKR", "SKL", "GNO", "METIS", "MANTLE", "MANTA", "ZK", "COMP",
                "SUSHI", "BAL", "OSMO", "LQTY", "SPELL", "PERP", "DODO", "ALPHA",
                "FIDA", "ALT", "IO", "VIRTUAL", "HYPE", "MOVE", "TNSR", "PIXEL",
                "RON", "MAGIC", "ENJ", "CHZ", "AUDIO", "WBTC", "PAXG", "MSOL",
                "WOJAK", "MOG", "NEIRO", "BRETT", "DEGEN", "JASMY", "HBAR", "ICP",
                "VET", "RENDER", "GRT", "AAVE", "MKR", "SNX", "CRV", "RUNE", "STX",
                "IMX", "SAND", "MANA", "AXS", "ENS", "LDO", "RPL", "PYTH", "RAY",
                "ORCA", "DRIFT", "NEAR", "ALGO") -> AssetType.CRYPTO
            
            // Forex detection
            symbol.length == 6 && symbol.matches(Regex("[A-Z]{6}")) -> AssetType.FOREX
            
            // Commodity/Metal detection
            symbol in listOf("XAU", "XAG", "XPT", "XPD", "WTI", "BRENT", "NATGAS", 
                "CORN", "WHEAT", "SOYBEAN", "COFFEE", "COCOA", "SUGAR") -> AssetType.COMMODITY
            
            // ETF detection
            symbol in listOf("SPY", "QQQ", "DIA", "IWM", "VTI", "EEM", "EFA", "GLD", "SLV",
                "TLT", "XLF", "XLE", "XLK", "XLV", "ARKK", "VOO", "VEA") -> AssetType.ETF
            
            // Default to stock
            else -> AssetType.STOCK
        }
    }
    
    private fun getSourcesForType(type: AssetType): List<DataSource> {
        return when (type) {
            AssetType.CRYPTO -> listOf(
                DataSource.PYTH,
                DataSource.JUPITER,
                DataSource.COINGECKO,
                DataSource.BINANCE,
                DataSource.COINBASE,
                DataSource.BIRDEYE,
                DataSource.DEXSCREENER,
                DataSource.KRAKEN,
                DataSource.SWITCHBOARD
            )
            AssetType.STOCK, AssetType.ETF -> listOf(
                // V5.9.5: Yahoo first — it returns REAL 24h % change. On-chain sources
                // (Jupiter, Birdeye, DexScreener, Pyth) all hardcode change24h=0.0 which
                // kills signal generation (analyzeStock needs non-zero change for momentum).
                DataSource.YAHOO_V7,
                DataSource.YAHOO_V8,
                DataSource.STOOQ,
                DataSource.FINNHUB,
                DataSource.TWELVE_DATA,
                DataSource.ALPHA_VANTAGE,
                DataSource.FMP,
                DataSource.CNBC,
                DataSource.GOOGLE_FINANCE,
                DataSource.IEX,
                DataSource.POLYGON,
                DataSource.TIINGO,
                DataSource.MARKETSTACK,
                // On-chain fallback for price only (change will be calculated from cache delta)
                DataSource.PYTH,
                DataSource.JUPITER,
                DataSource.BIRDEYE,
                DataSource.DEXSCREENER
            )
            AssetType.FOREX -> listOf(
                DataSource.PYTH,
                DataSource.YAHOO_V7,
                DataSource.TWELVE_DATA,
                DataSource.ALPHA_VANTAGE,
                DataSource.FINNHUB,
                DataSource.FIXER,
                DataSource.EXCHANGERATE
            )
            AssetType.COMMODITY -> listOf(
                DataSource.PYTH,
                DataSource.YAHOO_V7,
                DataSource.TWELVE_DATA,
                DataSource.STOOQ,
                DataSource.ALPHA_VANTAGE
            )
            AssetType.AUTO -> listOf(DataSource.PYTH, DataSource.YAHOO_V7)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA SOURCES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class DataSource {
        // Oracles
        PYTH, SWITCHBOARD, CHAINLINK,
        // Crypto
        JUPITER, BIRDEYE, DEXSCREENER, COINGECKO, COINMARKETCAP, BINANCE, COINBASE, KRAKEN,
        // Stocks
        YAHOO_V7, YAHOO_V8, STOOQ, CNBC, GOOGLE_FINANCE, FINNHUB, ALPHA_VANTAGE, 
        TWELVE_DATA, IEX, POLYGON, FMP, TIINGO, MARKETSTACK,
        // Forex
        FIXER, EXCHANGERATE
    }
    
    private suspend fun fetchFromSource(symbol: String, source: DataSource): PriceResult? {
        return when (source) {
            DataSource.PYTH -> fetchPyth(symbol)
            DataSource.SWITCHBOARD -> fetchSwitchboard(symbol)
            DataSource.JUPITER -> fetchJupiter(symbol)
            DataSource.COINGECKO -> fetchCoinGecko(symbol)
            DataSource.BINANCE -> fetchBinance(symbol)
            DataSource.COINBASE -> fetchCoinbase(symbol)
            DataSource.KRAKEN -> fetchKraken(symbol)
            DataSource.BIRDEYE -> fetchBirdeye(symbol)
            DataSource.DEXSCREENER -> fetchDexScreener(symbol)
            DataSource.YAHOO_V7 -> fetchYahooV7(symbol)
            DataSource.YAHOO_V8 -> fetchYahooV8(symbol)
            DataSource.STOOQ -> fetchStooq(symbol)
            DataSource.CNBC -> fetchCnbc(symbol)
            DataSource.GOOGLE_FINANCE -> fetchGoogleFinance(symbol)
            DataSource.FINNHUB -> fetchFinnhub(symbol)
            DataSource.ALPHA_VANTAGE -> fetchAlphaVantage(symbol)
            DataSource.TWELVE_DATA -> fetchTwelveData(symbol)
            DataSource.IEX -> fetchIex(symbol)
            DataSource.POLYGON -> fetchPolygon(symbol)
            DataSource.FMP -> fetchFmp(symbol)
            DataSource.TIINGO -> fetchTiingo(symbol)
            DataSource.MARKETSTACK -> fetchMarketstack(symbol)
            DataSource.FIXER -> fetchFixer(symbol)
            DataSource.EXCHANGERATE -> fetchExchangeRate(symbol)
            else -> null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ORACLE SOURCES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun fetchPyth(symbol: String): PriceResult? {
        return try {
            val data = PythOracle.getPrice(symbol)
            if (data != null && data.price > 0) {
                PriceResult(data.price, calcChange(symbol, data.price), "PYTH", data.confidence)
            } else null
        } catch (e: Exception) { null }
    }
    
    private suspend fun fetchSwitchboard(symbol: String): PriceResult? {
        return try {
            val data = SwitchboardOracle.getPrice(symbol)
            if (data != null && data.price > 0) {
                PriceResult(data.price, calcChange(symbol, data.price), "SWITCHBOARD")
            } else null
        } catch (e: Exception) { null }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CRYPTO SOURCES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun fetchJupiter(symbol: String): PriceResult? {
        return try {
            val price = JupiterPriceOracle.getPrice(symbol)
            if (price != null && price > 0) {
                PriceResult(price, calcChange(symbol, price), "JUPITER")
            } else null
        } catch (e: Exception) { null }
    }
    
    private suspend fun fetchCoinGecko(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val id = getCoinGeckoId(symbol) ?: return@withContext null
            val request = Request.Builder()
                .url("https://api.coingecko.com/api/v3/simple/price?ids=$id&vs_currencies=usd&include_24hr_change=true")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val data = json.optJSONObject(id)
                val price = data?.optDouble("usd", 0.0) ?: 0.0
                val change = data?.optDouble("usd_24h_change", 0.0) ?: 0.0
                
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, change, "COINGECKO")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchBinance(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val pair = "${symbol}USDT"
            val request = Request.Builder()
                .url("https://api.binance.com/api/v3/ticker/24hr?symbol=$pair")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val price = json.optString("lastPrice", "0").toDoubleOrNull() ?: 0.0
                val change = json.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0
                
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, change, "BINANCE")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchCoinbase(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.coinbase.com/v2/prices/${symbol}-USD/spot")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val data = json.optJSONObject("data")
                val price = data?.optString("amount", "0")?.toDoubleOrNull() ?: 0.0
                
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, 0.0, "COINBASE")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchKraken(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val pair = "${symbol}USD"
            val request = Request.Builder()
                .url("https://api.kraken.com/0/public/Ticker?pair=$pair")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val result = json.optJSONObject("result")
                
                if (result != null && result.length() > 0) {
                    val key = result.keys().next()
                    val data = result.optJSONObject(key)
                    val priceArr = data?.optJSONArray("c")
                    val price = priceArr?.optString(0, "0")?.toDoubleOrNull() ?: 0.0
                    
                    if (price > 0) {
                        response.close()
                        return@withContext PriceResult(price, 0.0, "KRAKEN")
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.7: xSTOCK MINT ADDRESSES - Tokenized Stocks on Solana (24/7 Trading!)
    // ═══════════════════════════════════════════════════════════════════════════
    private val xStockMints = mapOf(
        // ═══════════════════════════════════════════════════════════════════════
        // Backed.fi xStocks - Official SPL mints from api.backed.fi (24/7 DEX!)
        // 57 stocks + ETFs with verified Solana addresses
        // ═══════════════════════════════════════════════════════════════════════
        "AAPL" to "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp",
        "ABBV" to "XswbinNKyPmzTa5CskMbCPvMW6G5CMnZXZEeQSSQoie",
        "ADBE" to "XsDZMGEU8zadWFCkTtPBoPWYcUX3JHVmghnwf2Mve2q",
        "AMD" to "XsXcJ6GZ9kVnjqGsjBnktRcuwMBmvKWh8S93RefZ1rF",
        "AMZN" to "Xs3eBt7uRfJX8QUs4suhyU8p2M6DoUDrJyWBa8LLZsg",
        "ASML" to "XshuHQ6o6SVpUNawvnnTMxsZ4tacZsNgVCLorv7TkFq",
        "AVGO" to "XsgSaSvNSqLTtFuyWPBhK9196Xb9Bbdyjj4fH3cPJGo",
        "AZN" to "Xs3ZFkPYT2BN7qBMqf1j1bfTeTm1rFzEFSsQ1z3wAKU",
        "BAC" to "XswsQk4duEQmCbGzfqUUWYmi7pV7xpJ9eEmLHXCaEQP",
        "COIN" to "Xs7ZdzSHLU9ftNJsii5fCeJhoRWSC32SQGzGQtePxNu",
        "CRCL" to "XsueG8BtpquVJX9LVLLEGuViXUungE6WmK5YZ3p3bd1",
        "CRM" to "XsczbcQ3zfcgAEt9qHQES8pxKAVG5rujPSHQEXi4kaN",
        "CRWD" to "Xs7xXqkcK7K8urEqGg52SECi79dRp2cEKKuYjUePYDw",
        "CVX" to "XsNNMt7WTNA2sV3jrb1NNfNgapxRF5i4i6GcnTRRHts",
        "GLD" to "Xsv9hRk1z5ystj9MhnA7Lq4vjSsLwzL2nxrwmwtD3re",
        "GOOGL" to "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN",
        "GS" to "XsgaUyp4jd1fNBCxgtTKkW64xnnhQcvgaxzsbAq5ZD1",
        "HD" to "XszjVtyhowGjSC5odCqBpW1CtXXwXjYokymrk7fGKD3",
        "HOOD" to "XsvNBAYkrDRNhA7wPHQfX3ZUXZyZLdnCQDfHZ56bzpg",
        "INTC" to "XshPgPdXFRWB8tP1j82rebb2Q9rPgGX37RuqzohmArM",
        "IWM" to "XsbELVbLGBkn7xfMfyYuUipKGt1iRUc2B7pYRvFTFu3",
        "JNJ" to "XsGVi5eo1Dh2zUpic4qACcjuWGjNv8GCt3dm5XcX6Dn",
        "JPM" to "XsMAqkcKsUewDrzVkait4e5u4y8REgtyS7jWgCpLV2C",
        "KO" to "XsaBXg8dU5cPM6ehmVctMkVqoiRG2ZjMo1cyBJ3AykQ",
        "LLY" to "Xsnuv4omNoHozR6EEW5mXkw8Nrny5rB3jVfLqi6gKMH",
        "MA" to "XsApJFV9MAktqnAc6jqzsHVujxkGm9xcSUffaBoYLKC",
        "MCD" to "XsqE9cRRpzxcGKDXj1BJ7Xmg4GRhZoyY1KpmGSxAWT2",
        "META" to "Xsa62P5mvPszXL1krVUnU5ar38bBSVcWAB6fmPCo5Zu",
        "MRVL" to "XsuxRGDzbLjnJ72v74b7p9VY6N66uYgTCyfwwRjVCJA",
        "MSFT" to "XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX",
        "MSTR" to "XsP7xzNPvEHS1m6qfanPUGjNmdnmsLKEoNAnHjdxxyZ",
        "MU" to "XsQLZycSZ7QnBBdBXQaTbQdiUcbRqjNJgyBGAMzhHav",
        "NFLX" to "XsEH7wWfJJu2ZT3UCFeVfALnVA6CP5ur7Ee11KmzVpL",
        "NVDA" to "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh",
        "NVO" to "XsfAzPzYrYjd4Dpa9BU3cusBsvWfVB9gBcyGC87S57n",
        "ORCL" to "XsjFwUPiLofddX5cWFHW35GCbXcSu1BCUGfxoQAQjeL",
        "PEP" to "Xsv99frTRUeornyvCfvhnDesQDWuvns1M852Pez91vF",
        "PFE" to "XsAtbqkAP1HJxy7hFDeq7ok6yM43DQ9mQ1Rh861X8rw",
        "PG" to "XsYdjDjNUygZ7yGKfQaB6TxLh2gC6RRjzLtLAGJrhzV",
        "PLTR" to "XsoBhf2ufR8fTyNSjqfU71DYGaE6Z3SUGAidpzriAA4",
        "PM" to "Xsba6tUnSjDae2VcopDB6FGGDaxRrewFCDa5hKn5vT3",
        "PYPL" to "XshWQWYVp5ff8CrAEsGmLVKD47nBWi3Ygn5v8wXK27G",
        "QQQ" to "Xs8S1uUs1zvS2p7iwtsG3b6fkhpvmwz4GYU3gWAmWHZ",
        "RBLX" to "Xss5RAku5EH6UViFdvW7ss9xQjwQLsrs2opPMhb3k43",
        "SLV" to "XsxAd6okt8y1RRK6gNg7iJaqiWNiq5Md5EDf3ZrF2dm",
        "SMCI" to "XsMxAoJP47FQGLsVUvSS2QfBaHdNsd7DRU6nWRL8RSa",
        "SPY" to "XsoCS1TfEyfFhfvj8EtZ528L3CaKBDBRqRapnBbDF2W",
        "TMO" to "Xs8drBWy3Sd5QY3aifG9kt9KFs2K3PGZmx7jWrsrk57",
        "TMUS" to "XswCi2U1G6Ppbw1QhG45yKb8UKuR1FKLJrquv2FZSD4",
        "TSLA" to "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB",
        "TSM" to "XsafvsGtzFqqHgTnA3aPC83EAMkacU5mcGtcSayhpVV",
        "UBER" to "XsAsZLF4MmsvS1sDxRMrUz7REjHfwbC9UAMXSRBqgEB",
        "UNH" to "XszvaiXGPwvk2nwb3o9C1CX4K6zH8sez11E6uyup6fe",
        "V" to "XsqgsbXwWogGJsNcVZ3TyVouy2MbTkfCFhCGGGcQZ2p",
        "VTI" to "XsssYEQjzxBCFgvYFFNuhJFBeHNdLWYeUSP8F45cDr9",
        "WMT" to "Xs151QeqTCiuKtinzfRATnUESM2xTU6V9Wy8Vy538ci",
        "XLE" to "Xs54CrhmpVp6uxZXwgSTegrRH2kShh88XFPzgf4BExu",
        "XOM" to "XsaHND8sHyfMfsWPj6kSdd5VwvCayZvjYgKmmcNL5qh",
        // ═══════════════════════════════════════════════════════════════════════
        // Crypto SPL tokens (native Solana)
        // ═══════════════════════════════════════════════════════════════════════
        "SOL"   to "So11111111111111111111111111111111111111112",
        "JUP"   to "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
        "BONK"  to "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
        "WIF"   to "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm",
        "PYTH"  to "HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3",
    )
    
    private suspend fun fetchBirdeye(symbol: String): PriceResult? {
        // Get mint address for symbol
        val mint = xStockMints[symbol] ?: return null
        return try {
            val price = BirdeyeOracle.getPriceByAddress(mint)
            if (price != null && price > 0) {
                ErrorLogger.debug(TAG, "🐦 Birdeye: $symbol = \$${"%.4f".format(price)}")
                PriceResult(price, calcChange(symbol, price), "BIRDEYE")
            } else null
        } catch (e: Exception) { null }
    }
    
    private suspend fun fetchDexScreener(symbol: String): PriceResult? {
        // Get mint address for symbol
        val mint = xStockMints[symbol] ?: return null
        return try {
            val price = DexScreenerOracle.getPriceByAddress(mint)
            if (price != null && price > 0) {
                ErrorLogger.debug(TAG, "📊 DexScreener: $symbol = \$${"%.4f".format(price)}")
                PriceResult(price, calcChange(symbol, price), "DEXSCREENER")
            } else null
        } catch (e: Exception) { null }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SYMBOL MAPPING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert internal symbol to Yahoo Finance ticker symbol.
     * - Commodity futures use the exchange futures code (e.g. NG=F for Henry Hub natural gas)
     * - Precious/industrial metals mapped to CME/COMEX futures
     * - Forex pairs appended with "=X" (e.g. EURUSD=X)
     * - Stocks/crypto passed through unchanged
     */
    private fun toYahooSymbol(symbol: String): String {
        val futuresMap = mapOf(
            // Energy
            "NATGAS"  to "NG=F",
            "WTI"     to "CL=F",
            "BRENT"   to "BZ=F",
            "RBOB"    to "RB=F",
            "HEATING" to "HO=F",
            // Agricultural
            "CORN"    to "ZC=F",
            "WHEAT"   to "ZW=F",
            "SOYBEAN" to "ZS=F",
            "COFFEE"  to "KC=F",
            "COCOA"   to "CC=F",
            "SUGAR"   to "SB=F",
            "COTTON"  to "CT=F",
            "LUMBER"  to "LBS=F",
            "OJ"      to "OJ=F",
            "CATTLE"  to "LE=F",
            "HOGS"    to "HE=F",
            // Precious metals (CME/COMEX futures)
            "XAU"     to "GC=F",
            "XAG"     to "SI=F",
            "XPT"     to "PL=F",
            "XPD"     to "PA=F",
            // Industrial metals
            "XCU"     to "HG=F",
            "ZINC"    to "ZINC=F",
            "IRON"    to "TIO=F"
        )
        return when {
            futuresMap.containsKey(symbol) -> futuresMap[symbol]!!
            // Forex 6-char pairs: EURUSD → EURUSD=X
            symbol.length == 6 && symbol.matches(Regex("[A-Z]{6}")) -> "$symbol=X"
            else -> symbol
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STOCK SOURCES
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun fetchYahooV7(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val yahooSymbol = toYahooSymbol(symbol)
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v7/finance/quote?symbols=$yahooSymbol")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body?.contains("quoteResponse") == true) {
                    val json = JSONObject(body)
                    val result = json.optJSONObject("quoteResponse")?.optJSONArray("result")
                    if (result != null && result.length() > 0) {
                        val quote = result.getJSONObject(0)
                        val price = quote.optDouble("regularMarketPrice", 0.0)
                        val change = quote.optDouble("regularMarketChangePercent", 0.0)
                        if (price > 0) {
                            response.close()
                            return@withContext PriceResult(price, change, "YAHOO_V7")
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchYahooV8(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val yahooSymbol = toYahooSymbol(symbol)
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v8/finance/chart/$yahooSymbol?interval=1d&range=1d")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body?.contains("chart") == true) {
                    val json = JSONObject(body)
                    val result = json.optJSONObject("chart")?.optJSONArray("result")
                    if (result != null && result.length() > 0) {
                        val meta = result.getJSONObject(0).optJSONObject("meta")
                        val price = meta?.optDouble("regularMarketPrice", 0.0) ?: 0.0
                        val prevClose = meta?.optDouble("chartPreviousClose", price) ?: price
                        val change = if (prevClose > 0) ((price - prevClose) / prevClose) * 100 else 0.0
                        if (price > 0) {
                            response.close()
                            return@withContext PriceResult(price, change, "YAHOO_V8")
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private fun toStooqSymbol(symbol: String): String {
        // Stooq uses lowercase .f for futures, bare pair for forex, .US for US stocks
        val yahooSym = toYahooSymbol(symbol)
        return when {
            yahooSym.endsWith("=F") -> yahooSym.replace("=F", ".F")  // NG=F → NG.F
            yahooSym.endsWith("=X") -> yahooSym.replace("=X", "")     // EURUSD=X → EURUSD
            else -> "${symbol}.US"
        }
    }

    private suspend fun fetchStooq(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val stooqSymbol = toStooqSymbol(symbol)
            val request = Request.Builder()
                .url("https://stooq.com/q/l/?s=$stooqSymbol&f=sd2t2ohlcvn&h&e=csv")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null && !body.contains("N/D")) {
                    val lines = body.trim().split("\n")
                    if (lines.size >= 2) {
                        val data = lines[1].split(",")
                        if (data.size >= 7) {
                            val price = data[6].toDoubleOrNull() ?: 0.0
                            if (price > 0) {
                                response.close()
                                return@withContext PriceResult(price, 0.0, "STOOQ")
                            }
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchCnbc(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol?symbols=$symbol")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body?.contains("FormattedQuoteResult") == true) {
                    val json = JSONObject(body)
                    val data = json.optJSONObject("FormattedQuoteResult")?.optJSONArray("FormattedQuote")
                    if (data != null && data.length() > 0) {
                        val quote = data.getJSONObject(0)
                        val price = quote.optString("last", "0").replace(",", "").toDoubleOrNull() ?: 0.0
                        val change = quote.optString("change_pct", "0").replace("%", "").toDoubleOrNull() ?: 0.0
                        if (price > 0) {
                            response.close()
                            return@withContext PriceResult(price, change, "CNBC")
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchGoogleFinance(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            for (exchange in listOf("NASDAQ", "NYSE", "NYSEARCA")) {
                val request = Request.Builder()
                    .url("https://www.google.com/finance/quote/$symbol:$exchange")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val regex = """data-last-price="([0-9.]+)"""".toRegex()
                    val match = regex.find(body ?: "")
                    if (match != null) {
                        val price = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        if (price > 0) {
                            response.close()
                            return@withContext PriceResult(price, 0.0, "GOOGLE")
                        }
                    }
                }
                response.close()
            }
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchFinnhub(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://finnhub.io/api/v1/quote?symbol=$symbol&token=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val price = json.optDouble("c", 0.0)
                val prevClose = json.optDouble("pc", price)
                val change = if (prevClose > 0) ((price - prevClose) / prevClose) * 100 else 0.0
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, change, "FINNHUB")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchAlphaVantage(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=$symbol&apikey=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val quote = json.optJSONObject("Global Quote")
                val price = quote?.optString("05. price", "0")?.toDoubleOrNull() ?: 0.0
                val change = quote?.optString("10. change percent", "0%")?.replace("%", "")?.toDoubleOrNull() ?: 0.0
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, change, "ALPHAVANTAGE")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchTwelveData(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.twelvedata.com/price?symbol=$symbol&apikey=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val price = json.optString("price", "0").toDoubleOrNull() ?: 0.0
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, 0.0, "TWELVEDATA")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchIex(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://cloud.iexapis.com/stable/stock/$symbol/quote?token=pk_demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val price = json.optDouble("latestPrice", 0.0)
                val change = json.optDouble("changePercent", 0.0) * 100
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, change, "IEX")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchPolygon(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.polygon.io/v2/aggs/ticker/$symbol/prev?apiKey=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val data = results.getJSONObject(0)
                    val price = data.optDouble("c", 0.0)
                    if (price > 0) {
                        response.close()
                        return@withContext PriceResult(price, 0.0, "POLYGON")
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchFmp(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://financialmodelingprep.com/api/v3/quote-short/$symbol?apikey=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val arr = JSONArray(body ?: "[]")
                if (arr.length() > 0) {
                    val data = arr.getJSONObject(0)
                    val price = data.optDouble("price", 0.0)
                    if (price > 0) {
                        response.close()
                        return@withContext PriceResult(price, 0.0, "FMP")
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchTiingo(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.tiingo.com/iex/$symbol?token=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val arr = JSONArray(body ?: "[]")
                if (arr.length() > 0) {
                    val data = arr.getJSONObject(0)
                    val price = data.optDouble("last", 0.0)
                    if (price > 0) {
                        response.close()
                        return@withContext PriceResult(price, 0.0, "TIINGO")
                    }
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    private suspend fun fetchMarketstack(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://api.marketstack.com/v1/tickers/$symbol/eod/latest?access_key=demo")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val price = json.optDouble("close", 0.0)
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, 0.0, "MARKETSTACK")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FOREX SOURCES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun fetchFixer(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        // Requires API key - skip for demo
        return@withContext null
    }
    
    private suspend fun fetchExchangeRate(symbol: String): PriceResult? = withContext(Dispatchers.IO) {
        try {
            if (symbol.length != 6) return@withContext null
            val from = symbol.substring(0, 3)
            val to = symbol.substring(3, 6)
            
            val request = Request.Builder()
                .url("https://api.exchangerate-api.com/v4/latest/$from")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                val rates = json.optJSONObject("rates")
                val price = rates?.optDouble(to, 0.0) ?: 0.0
                if (price > 0) {
                    response.close()
                    return@withContext PriceResult(price, 0.0, "EXCHANGERATE")
                }
            }
            response.close()
        } catch (e: Exception) {}
        return@withContext null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun getCoinGeckoId(symbol: String): String? {
        return mapOf(
            "BTC" to "bitcoin",
            "ETH" to "ethereum",
            "SOL" to "solana",
            "BNB" to "binancecoin",
            "XRP" to "ripple",
            "ADA" to "cardano",
            "DOGE" to "dogecoin",
            "AVAX" to "avalanche-2",
            "DOT" to "polkadot",
            "LINK" to "chainlink",
            "MATIC" to "matic-network",
            "SHIB" to "shiba-inu",
            "LTC" to "litecoin",
            "ATOM" to "cosmos",
            "UNI" to "uniswap",
            "ARB" to "arbitrum",
            "OP" to "optimism",
            "APT" to "aptos",
            "SUI" to "sui",
            "SEI" to "sei-network",
            "INJ" to "injective-protocol",
            "TIA" to "celestia",
            "JUP" to "jupiter-exchange-solana",
            "PEPE" to "pepe",
            "WIF" to "dogwifcoin",
            "BONK" to "bonk",
            // V5.8 additions
            "TRX" to "tron",
            "TON" to "the-open-network",
            "BCH" to "bitcoin-cash",
            "XLM" to "stellar",
            "XMR" to "monero",
            "ETC" to "ethereum-classic",
            "ZEC" to "zcash",
            "XTZ" to "tezos",
            "EOS" to "eos",
            "CAKE" to "pancakeswap-token",
            "GMX" to "gmx",
            "DYDX" to "dydx",
            "ENA" to "ethena",
            "PENDLE" to "pendle",
            "WLD" to "worldcoin-wld",
            "JTO" to "jito-governance-token",
            "W" to "wormhole",
            "STRK" to "starknet",
            "TAO" to "bittensor",
            "FLOKI" to "floki",
            "NOT" to "notcoin",
            "POPCAT" to "popcat",
            "TRUMP" to "official-trump",
            // V5.8 missing tokens — were showing MC:— L:— because getCoinGeckoId returned null
            "FIL" to "filecoin", "WIN" to "wink", "ONT" to "ontology",
            "ONE" to "harmony", "KMNO" to "kamino", "CVXF" to "convex-finance",
            "TURBO" to "turbo", "XDC" to "xdce-crowd-sale", "FTM" to "fantom",
            "FXS" to "frax-share", "WAVES" to "waves", "GALA" to "gala",
            "BABYDOGE" to "baby-doge-coin", "STETH" to "staked-ether",
            "THETA" to "theta-token", "EGLD" to "elrond-erd-2", "ZIL" to "zilliqa",
            "IOTA" to "iota", "DASH" to "dash", "ZEN" to "zencash",
            "DCR" to "decred", "QTUM" to "qtum", "SC" to "siacoin",
            "BTT" to "bittorrent", "JST" to "just", "KAS" to "kaspa",
            "COTI" to "coti", "CELR" to "celer-network", "ROSE" to "oasis-network",
            "CELO" to "celo", "FLOW" to "flow", "KAVA" to "kava",
            "FLR" to "flare-networks", "ICX" to "icon", "ZRX" to "0x",
            "ANKR" to "ankr", "SKL" to "skale", "GNO" to "gnosis",
            "METIS" to "metis-token", "MANTLE" to "mantle", "MANTA" to "manta-network",
            "ZK" to "zksync", "COMP" to "compound-governance-token",
            "SUSHI" to "sushi", "BAL" to "balancer", "OSMO" to "osmosis",
            "LQTY" to "liquity", "SPELL" to "spell-token", "PERP" to "perpetual-protocol",
            "DODO" to "dodo", "ALPHA" to "alpha-finance", "FIDA" to "bonfida",
            "ALT" to "altlayer", "IO" to "io-net", "VIRTUAL" to "virtual-protocol",
            "HYPE" to "hyperliquid", "MOVE" to "movement", "TNSR" to "tensor",
            "PIXEL" to "pixels", "RON" to "ronin", "MAGIC" to "magic",
            "ENJ" to "enjincoin", "CHZ" to "chiliz", "AUDIO" to "audius",
            "WBTC" to "wrapped-bitcoin", "PAXG" to "pax-gold", "MSOL" to "msol",
            "WOJAK" to "wojak", "MOG" to "mog-coin", "NEIRO" to "neiro-on-eth",
            "BRETT" to "based-brett", "DEGEN" to "degen-base", "JASMY" to "jasmycoin",
            "HBAR" to "hedera-hashgraph", "ICP" to "internet-computer",
            "VET" to "vechain", "RENDER" to "render-token", "GRT" to "the-graph",
            "AAVE" to "aave", "MKR" to "maker", "SNX" to "havven",
            "CRV" to "curve-dao-token", "RUNE" to "thorchain", "STX" to "blockstack",
            "IMX" to "immutable-x", "SAND" to "the-sandbox", "MANA" to "decentraland",
            "AXS" to "axie-infinity", "ENS" to "ethereum-name-service",
            "LDO" to "lido-dao", "RPL" to "rocket-pool", "PYTH" to "pyth-network",
            "RAY" to "raydium", "ORCA" to "orca", "DRIFT" to "drift-protocol",
            "NEAR" to "near", "ALGO" to "algorand"
        )[symbol]
    }
    
    private fun trackSuccess(source: String) {
        sourceSuccessCount.getOrPut(source) { AtomicInteger(0) }.incrementAndGet()
    }
    
    private fun trackFailure(source: String) {
        sourceFailCount.getOrPut(source) { AtomicInteger(0) }.incrementAndGet()
    }
    
    /**
     * Get source statistics
     */
    fun getSourceStats(): Map<String, Map<String, Int>> {
        val stats = mutableMapOf<String, Map<String, Int>>()
        DataSource.values().forEach { source ->
            val success = sourceSuccessCount[source.name]?.get() ?: 0
            val fail = sourceFailCount[source.name]?.get() ?: 0
            if (success > 0 || fail > 0) {
                stats[source.name] = mapOf("success" to success, "fail" to fail)
            }
        }
        return stats
    }
    
    /**
     * Clear all caches
     */
    fun clearCache() {
        priceCache.clear()
    }
}
