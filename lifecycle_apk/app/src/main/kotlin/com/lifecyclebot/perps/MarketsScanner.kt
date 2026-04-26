package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🔍 MARKETS SCANNER - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Advanced scanner for filtering and discovering trading opportunities across:
 *   - Foreign Stocks (China, Japan, Europe)
 *   - Gold & Silver Miners
 *   - Sector-specific scans (Tech, AI, EV, Cannabis, etc.)
 *   - Commodity & Forex pairs
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🔍 Category-based filtering
 *   📊 Top movers detection
 *   📈 Volume spike detection
 *   🎯 Multi-criteria scanning
 *   ⚡ Real-time price integration
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object MarketsScanner {
    
    private const val TAG = "🔍Scanner"
    
    // Scan results cache
    private val scanResults = ConcurrentHashMap<ScanCategory, List<ScanResult>>()
    private var lastScanTime = 0L
    private const val SCAN_CACHE_TTL_MS = 30_000L  // 30 second cache
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CATEGORIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class ScanCategory(val displayName: String, val emoji: String) {
        // Regional
        CHINA_STOCKS("China ADR", "🇨🇳"),
        JAPAN_STOCKS("Japan ADR", "🇯🇵"),
        EUROPE_STOCKS("Europe ADR", "🇪🇺"),
        ALL_FOREIGN("All Foreign", "🌍"),
        
        // Mining
        GOLD_MINERS("Gold Miners", "⛏️"),
        SILVER_MINERS("Silver Miners", "⛏️"),
        ALL_MINERS("All Miners", "⛏️"),
        JUNIOR_MINERS("Junior Miners", "🔥"),
        
        // Sectors
        TECH_GIANTS("Tech Giants", "💻"),
        AI_STOCKS("AI Stocks", "🤖"),
        SEMICONDUCTORS("Semiconductors", "🔌"),
        EV_STOCKS("EV Stocks", "🚗"),
        CANNABIS("Cannabis", "🌿"),
        FINTECH("Fintech", "💳"),
        
        // Asset Classes
        CRYPTO_PERPS("Crypto Perps", "🪙"),
        COMMODITIES("Commodities", "🛢️"),
        PRECIOUS_METALS("Precious Metals", "🥇"),
        FOREX_MAJORS("Forex Majors", "💱"),
        FOREX_EMERGING("Forex Emerging", "🌏"),
        INDEX_ETFS("Index ETFs", "📊"),
        
        // Special
        TOP_GAINERS("Top Gainers", "🚀"),
        TOP_LOSERS("Top Losers", "📉"),
        HIGH_VOLUME("High Volume", "📊"),
        VOLATILE("High Volatility", "⚡"),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN RESULT MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ScanResult(
        val market: PerpsMarket,
        val price: Double,
        val change24hPct: Double,
        val volume24h: Double,
        val score: Int,           // Scanner score (0-100)
        val signal: String,       // "BULLISH", "BEARISH", "NEUTRAL"
        val reasons: List<String>
    ) {
        fun getDisplayChange(): String {
            val sign = if (change24hPct >= 0) "+" else ""
            return "$sign${"%.2f".format(change24hPct)}%"
        }
        
        fun getChangeEmoji(): String = when {
            change24hPct >= 5.0 -> "🚀"
            change24hPct >= 2.0 -> "📈"
            change24hPct >= 0 -> "➡️"
            change24hPct >= -2.0 -> "📉"
            else -> "💀"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCAN METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Scan a specific category and return sorted results
     */
    suspend fun scanCategory(category: ScanCategory, limit: Int = 20): List<ScanResult> {
        // Check cache
        if (System.currentTimeMillis() - lastScanTime < SCAN_CACHE_TTL_MS) {
            scanResults[category]?.let { return it.take(limit) }
        }
        
        ErrorLogger.info(TAG, "🔍 Scanning: ${category.emoji} ${category.displayName}")
        
        val markets = getMarketsForCategory(category)
        val results = mutableListOf<ScanResult>()
        
        for (market in markets) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                val result = analyzeScanResult(market, data)
                results.add(result)
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Scan error for ${market.symbol}: ${e.message}")
            }
        }
        
        // Sort by score or change depending on category
        val sorted = when (category) {
            ScanCategory.TOP_GAINERS -> results.sortedByDescending { it.change24hPct }
            ScanCategory.TOP_LOSERS -> results.sortedBy { it.change24hPct }
            ScanCategory.HIGH_VOLUME -> results.sortedByDescending { it.volume24h }
            ScanCategory.VOLATILE -> results.sortedByDescending { kotlin.math.abs(it.change24hPct) }
            else -> results.sortedByDescending { it.score }
        }
        
        scanResults[category] = sorted
        lastScanTime = System.currentTimeMillis()
        
        ErrorLogger.info(TAG, "🔍 Found ${sorted.size} results for ${category.displayName}")
        return sorted.take(limit)
    }
    
    /**
     * Get markets for a specific category
     */
    fun getMarketsForCategory(category: ScanCategory): List<PerpsMarket> {
        return PerpsMarket.values().filter { market ->
            when (category) {
                // Regional
                ScanCategory.CHINA_STOCKS -> market.isChinaStock
                ScanCategory.JAPAN_STOCKS -> market.isJapanStock
                ScanCategory.EUROPE_STOCKS -> market.isEuropeStock
                ScanCategory.ALL_FOREIGN -> market.isForeignStock
                
                // Mining
                ScanCategory.GOLD_MINERS -> market.isGoldMiner
                ScanCategory.SILVER_MINERS -> market.isSilverMiner
                ScanCategory.ALL_MINERS -> market.isPreciousMetalMiner
                ScanCategory.JUNIOR_MINERS -> market.isJuniorMiner
                
                // Sectors
                ScanCategory.TECH_GIANTS -> market.isTech
                ScanCategory.AI_STOCKS -> market.isAI
                ScanCategory.SEMICONDUCTORS -> market.isSemiconductor
                ScanCategory.EV_STOCKS -> market.isEV
                ScanCategory.CANNABIS -> market.isCannabis
                ScanCategory.FINTECH -> market.symbol in listOf("COIN", "PYPL", "V", "MA", "SQ", "HOOD", "SOFI", "NU")
                
                // Asset Classes
                ScanCategory.CRYPTO_PERPS -> market.isCrypto
                ScanCategory.COMMODITIES -> market.isCommodity
                ScanCategory.PRECIOUS_METALS -> market.isPreciousMetal
                ScanCategory.FOREX_MAJORS -> market.isForex && market.symbol in listOf("EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD")
                ScanCategory.FOREX_EMERGING -> market.isForex && market.symbol in listOf("USDMXN", "USDBRL", "USDINR", "USDCNY", "USDZAR", "USDTRY", "USDRUB", "USDSGD", "USDHKD", "USDKRW")
                ScanCategory.INDEX_ETFS -> market.isETF
                
                // Special scans - include all tradeable
                ScanCategory.TOP_GAINERS, ScanCategory.TOP_LOSERS,
                ScanCategory.HIGH_VOLUME, ScanCategory.VOLATILE -> market.isStock || market.isCrypto
            }
        }
    }
    
    /**
     * Analyze a market and create scan result
     */
    private fun analyzeScanResult(market: PerpsMarket, data: PerpsMarketData): ScanResult {
        val reasons = mutableListOf<String>()
        var score = 50
        
        // Change analysis
        val change = data.priceChange24hPct
        when {
            change >= 10.0 -> {
                score += 30
                reasons.add("🚀 Massive move +${change.fmt(1)}%")
            }
            change >= 5.0 -> {
                score += 20
                reasons.add("📈 Strong rally +${change.fmt(1)}%")
            }
            change >= 2.0 -> {
                score += 10
                reasons.add("📈 Up ${change.fmt(1)}%")
            }
            change <= -10.0 -> {
                score += 25  // Oversold bounce potential
                reasons.add("💀 Crashed ${change.fmt(1)}%")
            }
            change <= -5.0 -> {
                score += 15
                reasons.add("📉 Down ${change.fmt(1)}%")
            }
        }
        
        // Volume analysis
        if (data.volume24h > 100_000_000) {
            score += 10
            reasons.add("📊 High volume")
        }
        
        // Sector-specific bonuses
        if (market.isGoldMiner || market.isSilverMiner) {
            // Check gold/silver prices for correlation
            val goldPrice = PerpsMarketDataFetcher.getCachedPrice(PerpsMarket.XAU)?.price ?: 2650.0
            if (goldPrice > 2600) {
                score += 10
                reasons.add("🥇 Gold above $2600")
            }
        }
        
        if (market.isChinaStock && change >= 3.0) {
            score += 5
            reasons.add("🇨🇳 China momentum")
        }
        
        // Signal determination
        val signal = when {
            score >= 75 && change > 0 -> "BULLISH"
            score >= 75 && change < 0 -> "BEARISH"
            score >= 60 -> if (change > 0) "BULLISH" else "BEARISH"
            else -> "NEUTRAL"
        }
        
        return ScanResult(
            market = market,
            price = data.price,
            change24hPct = change,
            volume24h = data.volume24h,
            score = score.coerceIn(0, 100),
            signal = signal,
            reasons = reasons
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUICK SCAN METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Quick scan: Top gainers across all markets
     */
    suspend fun getTopGainers(limit: Int = 10): List<ScanResult> {
        return scanCategory(ScanCategory.TOP_GAINERS, limit)
    }
    
    /**
     * Quick scan: Top losers (potential bounce plays)
     */
    suspend fun getTopLosers(limit: Int = 10): List<ScanResult> {
        return scanCategory(ScanCategory.TOP_LOSERS, limit)
    }
    
    /**
     * Quick scan: Gold miners
     */
    suspend fun getGoldMiners(limit: Int = 15): List<ScanResult> {
        return scanCategory(ScanCategory.GOLD_MINERS, limit)
    }
    
    /**
     * Quick scan: Silver miners
     */
    suspend fun getSilverMiners(limit: Int = 15): List<ScanResult> {
        return scanCategory(ScanCategory.SILVER_MINERS, limit)
    }
    
    /**
     * Quick scan: China stocks
     */
    suspend fun getChinaStocks(limit: Int = 10): List<ScanResult> {
        return scanCategory(ScanCategory.CHINA_STOCKS, limit)
    }
    
    /**
     * Quick scan: Japan stocks
     */
    suspend fun getJapanStocks(limit: Int = 10): List<ScanResult> {
        return scanCategory(ScanCategory.JAPAN_STOCKS, limit)
    }
    
    /**
     * Quick scan: Europe stocks
     */
    suspend fun getEuropeStocks(limit: Int = 10): List<ScanResult> {
        return scanCategory(ScanCategory.EUROPE_STOCKS, limit)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-CRITERIA SCAN
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ScanCriteria(
        val minChange: Double? = null,        // Minimum 24h change %
        val maxChange: Double? = null,        // Maximum 24h change %
        val minPrice: Double? = null,         // Minimum price
        val maxPrice: Double? = null,         // Maximum price
        val minVolume: Double? = null,        // Minimum 24h volume
        val stocksOnly: Boolean = false,      // Only stocks
        val cryptoOnly: Boolean = false,      // Only crypto
        val forexOnly: Boolean = false,       // Only forex
        val commoditiesOnly: Boolean = false, // Only commodities
        val minersOnly: Boolean = false,      // Only mining stocks
        val foreignOnly: Boolean = false,     // Only foreign stocks
    )
    
    /**
     * Advanced multi-criteria scan
     */
    suspend fun advancedScan(criteria: ScanCriteria, limit: Int = 20): List<ScanResult> {
        ErrorLogger.info(TAG, "🔍 Running advanced scan with criteria")
        
        val allMarkets = PerpsMarket.values().filter { market ->
            // Asset type filters
            when {
                criteria.stocksOnly -> market.isStock
                criteria.cryptoOnly -> market.isCrypto
                criteria.forexOnly -> market.isForex
                criteria.commoditiesOnly -> market.isCommodity
                criteria.minersOnly -> market.isPreciousMetalMiner
                criteria.foreignOnly -> market.isForeignStock
                else -> true
            }
        }
        
        val results = mutableListOf<ScanResult>()
        
        for (market in allMarkets) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                
                // Apply criteria filters
                if (criteria.minChange != null && data.priceChange24hPct < criteria.minChange) continue
                if (criteria.maxChange != null && data.priceChange24hPct > criteria.maxChange) continue
                if (criteria.minPrice != null && data.price < criteria.minPrice) continue
                if (criteria.maxPrice != null && data.price > criteria.maxPrice) continue
                if (criteria.minVolume != null && data.volume24h < criteria.minVolume) continue
                
                results.add(analyzeScanResult(market, data))
            } catch (_: Exception) {}
        }
        
        return results.sortedByDescending { it.score }.take(limit)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get category statistics
     */
    fun getCategoryStats(): Map<ScanCategory, Int> {
        return ScanCategory.values().associateWith { category ->
            getMarketsForCategory(category).size
        }
    }
    
    /**
     * Get total tradeable assets count
     */
    fun getTotalAssetsCount(): Int = PerpsMarket.values().size
    
    /**
     * Get breakdown by asset type
     */
    fun getAssetBreakdown(): Map<String, Int> {
        val markets = PerpsMarket.values()
        return mapOf(
            "Stocks" to markets.count { it.isStock && !it.isETF },
            "ETFs" to markets.count { it.isETF },
            "Crypto" to markets.count { it.isCrypto },
            "Forex" to markets.count { it.isForex },
            "Commodities" to markets.count { it.isCommodity },
            "Metals" to markets.count { it.isMetal },
            "Foreign Stocks" to markets.count { it.isForeignStock },
            "Mining Stocks" to markets.count { it.isPreciousMetalMiner }
        )
    }
    
    // Helper
    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}
