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
                "FLOKI", "NOT", "POPCAT", "TRUMP") -> AssetType.CRYPTO
            
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
                DataSource.JUPITER,      // V5.7.7: Jupiter has xStocks (TSLAx, AAPLx, NVDAx) - 24/7 on-chain!
                DataSource.BIRDEYE,      // V5.7.7: Birdeye for Solana token prices
                DataSource.DEXSCREENER,  // V5.7.7: DexScreener for DEX prices
                DataSource.PYTH,
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
                DataSource.MARKETSTACK
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
                PriceResult(data.price, 0.0, "PYTH", data.confidence)
            } else null
        } catch (e: Exception) { null }
    }
    
    private suspend fun fetchSwitchboard(symbol: String): PriceResult? {
        return try {
            val data = SwitchboardOracle.getPrice(symbol)
            if (data != null && data.price > 0) {
                PriceResult(data.price, 0.0, "SWITCHBOARD")
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
                PriceResult(price, 0.0, "JUPITER")
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
        // Backed.fi xStocks - Real SPL tokens backed 1:1 by shares (24/7 DEX!)
        // All verified on Solscan/Solflare/CMC - Official Backed.fi addresses
        // ═══════════════════════════════════════════════════════════════════════
        "TSLA"  to "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB",
        "AAPL"  to "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp",
        "NVDA"  to "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh",
        "META"  to "Xsa62P5mvPszXL1krVUnU5ar38bBSVcWAB6fmPCo5Zu",
        "GOOGL" to "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN",
        "AMZN"  to "Xs3eBt7uRfJX8QUs4suhyU8p2M6DoUDrJyWBa8LLZsg",
        "MSFT"  to "XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX",
        "COIN"  to "Xs7ZdzSHLU9ftNJsii5fCeJhoRWSC32SQGzGQtePxNu",
        "SPY"   to "XsoCS1TfEyfFhfvj8EtZ528L3CaKBDBRqRapnBbDF2W",
        "QQQ"   to "Xs8S1uUs1zvS2p7iwtsG3b6fkhpvmwz4GYU3gWAmWHZ",
        "MSTR"  to "XsP7xzNPvEHS1m6qfanPUGjNmdnmsLKEoNAnHjdxxyZ",
        "JPM"   to "XsMAqkcKsUewDrzVkait4e5u4y8REgtyS7jWgCpLV2C",
        "CRCL"  to "XsueG8BtpquVJX9LVLLEGuViXUungE6WmK5YZ3p3bd1",
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
                PriceResult(price, 0.0, "BIRDEYE")
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
                PriceResult(price, 0.0, "DEXSCREENER")
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
            "TRUMP" to "official-trump"
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
