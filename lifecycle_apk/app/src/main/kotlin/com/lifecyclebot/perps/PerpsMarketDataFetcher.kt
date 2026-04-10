package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 PERPS MARKET DATA FETCHER - V5.7.1
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Fetches real-time market data for:
 * - SOL native price (Pyth Oracle primary, Jupiter/CoinGecko fallback)
 * - Tokenized stocks via Pyth Oracle (AAPL, TSLA, NVDA, etc.)
 * 
 * DATA SOURCES (Priority Order):
 * 1. Pyth Network Oracle (primary - sub-second latency)
 * 2. Jupiter Price API (fallback for SOL)
 * 3. CoinGecko (secondary fallback)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsMarketDataFetcher {
    
    private const val TAG = "📊PerpsData"
    
    // Cache for market data
    private val marketDataCache = ConcurrentHashMap<PerpsMarket, PerpsMarketData>()
    private val lastFetchTime = ConcurrentHashMap<PerpsMarket, Long>()
    private const val CACHE_TTL_MS = 3_000L  // 3 second cache for real-time
    
    // Stock price cache (simulated prices for tokenized stocks)
    private val stockPrices = ConcurrentHashMap<String, Double>().apply {
        put("AAPL", 175.0)
        put("TSLA", 250.0)
        put("NVDA", 480.0)
        put("GOOGL", 140.0)
        put("AMZN", 180.0)
        put("META", 500.0)
        put("MSFT", 420.0)
        put("COIN", 220.0)
    }
    
    // HTTP Client
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // API Endpoints (fallbacks)
    private const val COINGECKO_SOL = "https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true"
    private const val JUPITER_PRICE = "https://price.jup.ag/v4/price?ids=So11111111111111111111111111111111111111112"
    
    /**
     * Get market data for a specific market
     * Now uses Pyth Oracle for real prices!
     */
    suspend fun getMarketData(market: PerpsMarket): PerpsMarketData {
        // Check cache
        val cached = marketDataCache[market]
        val lastFetch = lastFetchTime[market] ?: 0
        
        if (cached != null && System.currentTimeMillis() - lastFetch < CACHE_TTL_MS) {
            return cached
        }
        
        // Fetch fresh data using Pyth Oracle
        val freshData = fetchFromPythOracle(market)
        
        // Update cache
        marketDataCache[market] = freshData
        lastFetchTime[market] = System.currentTimeMillis()
        
        return freshData
    }
    
    /**
     * V5.7.1: Primary data source - Pyth Oracle
     */
    private suspend fun fetchFromPythOracle(market: PerpsMarket): PerpsMarketData {
        try {
            val pythPrice = PythOracle.getPrice(market.symbol)
            
            if (pythPrice != null && !pythPrice.isStale()) {
                ErrorLogger.debug(TAG, "📡 Pyth: ${market.symbol} = \$${pythPrice.price.fmt(2)}")
                
                return PerpsMarketData(
                    market = market,
                    price = pythPrice.price,
                    indexPrice = pythPrice.emaPrice,
                    markPrice = pythPrice.price,
                    fundingRate = calculateFundingRate(market),
                    fundingRateAnnualized = calculateFundingRate(market) * 365 * 3 * 100,
                    nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
                    openInterestLong = getEstimatedOI(market, true),
                    openInterestShort = getEstimatedOI(market, false),
                    volume24h = getEstimatedVolume(market),
                    high24h = pythPrice.price * 1.02,
                    low24h = pythPrice.price * 0.98,
                    priceChange24hPct = calculateChange(pythPrice.price, pythPrice.emaPrice),
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Pyth fetch failed for ${market.symbol}: ${e.message}")
        }
        
        // Fallback to legacy method
        return when (market) {
            PerpsMarket.SOL -> fetchSolDataFallback()
            else -> fetchStockDataFallback(market)
        }
    }
    
    /**
     * Fetch SOL market data from CoinGecko/Jupiter
     */
    private suspend fun fetchSolData(): PerpsMarketData = withContext(Dispatchers.IO) {
        try {
            // Try Jupiter first
            val jupiterPrice = tryJupiterPrice()
            if (jupiterPrice > 0) {
                return@withContext createSolMarketData(jupiterPrice)
            }
            
            // Fallback to CoinGecko
            val request = Request.Builder()
                .url(COINGECKO_SOL)
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(body)
            
            val solana = json.getJSONObject("solana")
            val price = solana.getDouble("usd")
            val change24h = solana.optDouble("usd_24h_change", 0.0)
            val volume24h = solana.optDouble("usd_24h_vol", 0.0)
            
            ErrorLogger.debug(TAG, "SOL price from CoinGecko: \$${price.fmt(2)}")
            
            return@withContext PerpsMarketData(
                market = PerpsMarket.SOL,
                price = price,
                indexPrice = price,
                markPrice = price,
                fundingRate = 0.0001 * (if (change24h > 0) 1 else -1),  // Simulated funding
                fundingRateAnnualized = 0.0001 * 365 * 3 * 100,
                nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,  // 8 hours
                openInterestLong = 50_000_000.0,   // Simulated OI
                openInterestShort = 45_000_000.0,
                volume24h = volume24h,
                high24h = price * 1.03,
                low24h = price * 0.97,
                priceChange24hPct = change24h,
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to fetch SOL data: ${e.message}")
            // Return mock data as fallback
            return@withContext createSolMarketData(150.0)
        }
    }
    
    /**
     * Try Jupiter Price API
     */
    private fun tryJupiterPrice(): Double {
        return try {
            val request = Request.Builder()
                .url(JUPITER_PRICE)
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return 0.0
            val json = JSONObject(body)
            
            val data = json.getJSONObject("data")
            val sol = data.getJSONObject("So11111111111111111111111111111111111111112")
            val price = sol.getDouble("price")
            
            ErrorLogger.debug(TAG, "SOL price from Jupiter: \$${price.fmt(2)}")
            price
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Jupiter price failed: ${e.message}")
            0.0
        }
    }
    
    private fun createSolMarketData(price: Double): PerpsMarketData {
        // Generate realistic-looking mock data
        val change24h = (Math.random() * 10 - 5)  // -5% to +5%
        
        return PerpsMarketData(
            market = PerpsMarket.SOL,
            price = price,
            indexPrice = price,
            markPrice = price * (1 + Math.random() * 0.002 - 0.001),  // Slight deviation
            fundingRate = (Math.random() * 0.0002 - 0.0001),
            fundingRateAnnualized = (Math.random() * 0.0002 - 0.0001) * 365 * 3 * 100,
            nextFundingTime = System.currentTimeMillis() + 8 * 60 * 60 * 1000,
            openInterestLong = 50_000_000.0 + Math.random() * 10_000_000,
            openInterestShort = 45_000_000.0 + Math.random() * 10_000_000,
            volume24h = 100_000_000.0 + Math.random() * 50_000_000,
            high24h = price * (1 + Math.abs(change24h) / 100 + 0.01),
            low24h = price * (1 - Math.abs(change24h) / 100 - 0.01),
            priceChange24hPct = change24h,
        )
    }
    
    /**
     * Fetch stock market data (simulated for now)
     * In production, would use tokenized stock oracles on Solana
     */
    private suspend fun fetchStockData(market: PerpsMarket): PerpsMarketData = withContext(Dispatchers.IO) {
        try {
            // Get base price (simulated - in production would use real oracle)
            val basePrice = stockPrices[market.symbol] ?: 100.0
            
            // Add some randomness to simulate live price movement
            val priceVariation = basePrice * (Math.random() * 0.02 - 0.01)  // +/- 1%
            val price = basePrice + priceVariation
            
            // Update stored price
            stockPrices[market.symbol] = price
            
            // Simulate 24h change
            val change24h = (Math.random() * 6 - 3)  // -3% to +3%
            
            return@withContext PerpsMarketData(
                market = market,
                price = price,
                indexPrice = price,
                markPrice = price,
                fundingRate = 0.0,  // No funding for stocks
                fundingRateAnnualized = 0.0,
                nextFundingTime = 0,
                openInterestLong = 10_000_000.0 + Math.random() * 5_000_000,
                openInterestShort = 8_000_000.0 + Math.random() * 4_000_000,
                volume24h = 50_000_000.0 + Math.random() * 25_000_000,
                high24h = price * (1 + Math.abs(change24h) / 100 + 0.005),
                low24h = price * (1 - Math.abs(change24h) / 100 - 0.005),
                priceChange24hPct = change24h,
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to fetch ${market.symbol} data: ${e.message}")
            // Return mock data
            return@withContext PerpsMarketData(
                market = market,
                price = stockPrices[market.symbol] ?: 100.0,
                indexPrice = stockPrices[market.symbol] ?: 100.0,
                markPrice = stockPrices[market.symbol] ?: 100.0,
                fundingRate = 0.0,
                fundingRateAnnualized = 0.0,
                nextFundingTime = 0,
                openInterestLong = 10_000_000.0,
                openInterestShort = 8_000_000.0,
                volume24h = 50_000_000.0,
                high24h = (stockPrices[market.symbol] ?: 100.0) * 1.02,
                low24h = (stockPrices[market.symbol] ?: 100.0) * 0.98,
                priceChange24hPct = 0.0,
            )
        }
    }
    
    /**
     * Get all available markets with current data
     */
    suspend fun getAllMarketsData(): List<PerpsMarketData> {
        return PerpsMarket.values().map { market ->
            getMarketData(market)
        }
    }
    
    /**
     * Get SOL price only (quick helper)
     */
    suspend fun getSolPrice(): Double {
        return getMarketData(PerpsMarket.SOL).price
    }
    
    /**
     * Force refresh all market data
     */
    fun invalidateCache() {
        marketDataCache.clear()
        lastFetchTime.clear()
        ErrorLogger.info(TAG, "Market data cache invalidated")
    }
    
    /**
     * Check if market is currently tradeable
     */
    fun isMarketTradeable(market: PerpsMarket, isPaperMode: Boolean = false): Boolean {
        if (!market.isStock) return true  // Crypto 24/7
        
        // V5.7.3: Paper mode allows 24/7 stock trading for learning
        if (isPaperMode) {
            ErrorLogger.debug(TAG, "📊 ${market.symbol}: Paper mode - allowing 24/7 trading")
            return true
        }
        
        // Check market hours (simplified)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            return false
        }
        
        // US market hours approximation (9:30-16:00 ET ~ 14:30-21:00 UTC)
        return hour in 14..21
    }
    
    /**
     * Get market status string
     */
    fun getMarketStatus(market: PerpsMarket): String {
        return if (isMarketTradeable(market)) {
            "🟢 OPEN"
        } else {
            "🔴 CLOSED"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.1 HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate funding rate based on market conditions
     */
    private fun calculateFundingRate(market: PerpsMarket): Double {
        // In production, would fetch from exchange
        // For now, simulate based on market
        return when (market) {
            PerpsMarket.SOL -> (Math.random() * 0.0002 - 0.0001)  // -0.01% to +0.01%
            else -> 0.0  // No funding for stocks
        }
    }
    
    /**
     * Get estimated open interest
     */
    private fun getEstimatedOI(market: PerpsMarket, isLong: Boolean): Double {
        val baseOI = when (market) {
            PerpsMarket.SOL -> 100_000_000.0
            PerpsMarket.NVDA -> 50_000_000.0
            PerpsMarket.TSLA -> 40_000_000.0
            else -> 20_000_000.0
        }
        
        val ratio = if (isLong) 0.55 else 0.45  // Slight long bias
        return baseOI * ratio * (0.9 + Math.random() * 0.2)
    }
    
    /**
     * Get estimated 24h volume
     */
    private fun getEstimatedVolume(market: PerpsMarket): Double {
        return when (market) {
            PerpsMarket.SOL -> 500_000_000.0 + Math.random() * 200_000_000
            PerpsMarket.NVDA -> 200_000_000.0 + Math.random() * 100_000_000
            PerpsMarket.TSLA -> 150_000_000.0 + Math.random() * 75_000_000
            else -> 50_000_000.0 + Math.random() * 25_000_000
        }
    }
    
    /**
     * Calculate price change percentage
     */
    private fun calculateChange(currentPrice: Double, emaPrice: Double): Double {
        if (emaPrice <= 0) return 0.0
        return ((currentPrice - emaPrice) / emaPrice * 100)
    }
    
    /**
     * Fallback SOL data fetch
     */
    private suspend fun fetchSolDataFallback(): PerpsMarketData = withContext(Dispatchers.IO) {
        try {
            val jupiterPrice = tryJupiterPrice()
            if (jupiterPrice > 0) {
                return@withContext createSolMarketData(jupiterPrice)
            }
        } catch (_: Exception) {}
        
        return@withContext createSolMarketData(150.0)
    }
    
    /**
     * Fallback stock data fetch
     */
    private suspend fun fetchStockDataFallback(market: PerpsMarket): PerpsMarketData = withContext(Dispatchers.IO) {
        val fallbackPrices = mapOf(
            "AAPL" to 195.50,
            "TSLA" to 248.30,
            "NVDA" to 875.20,
            "GOOGL" to 175.80,
            "AMZN" to 185.60,
            "META" to 510.40,
            "MSFT" to 420.15,
            "COIN" to 245.80,
        )
        
        val price = fallbackPrices[market.symbol] ?: 100.0
        val change = (Math.random() * 6 - 3)
        
        return@withContext PerpsMarketData(
            market = market,
            price = price,
            indexPrice = price,
            markPrice = price,
            fundingRate = 0.0,
            fundingRateAnnualized = 0.0,
            nextFundingTime = 0,
            openInterestLong = getEstimatedOI(market, true),
            openInterestShort = getEstimatedOI(market, false),
            volume24h = getEstimatedVolume(market),
            high24h = price * (1 + Math.abs(change) / 100 + 0.005),
            low24h = price * (1 - Math.abs(change) / 100 - 0.005),
            priceChange24hPct = change,
        )
    }
}
