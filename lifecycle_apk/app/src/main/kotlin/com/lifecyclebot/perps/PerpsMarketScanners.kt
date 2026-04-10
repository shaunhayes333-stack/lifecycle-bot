package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📡 PERPS MARKET SCANNERS - V5.7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Specialized scanners for each Perps trading mode, designed to work with the
 * existing AI layer infrastructure.
 * 
 * SCANNER TYPES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📈 SOL_MOMENTUM_SCANNER     - Detects SOL momentum plays (long/short)
 *   🎯 SOL_SNIPER_SCANNER       - Fast entry setups on SOL volatility
 *   💎 STOCK_QUALITY_SCANNER    - Tokenized stock quality setups
 *   🐋 WHALE_LIQUIDATION_SCANNER - Detects potential liquidation cascades
 *   📊 FUNDING_RATE_SCANNER     - Funding rate arbitrage opportunities
 *   ⚡ VOLATILITY_BREAKOUT_SCANNER - Volatility squeeze breakouts
 *   🔄 CORRELATION_SCANNER      - SOL vs stock correlation plays
 * 
 * Each scanner produces PerpsSignals that feed into PerpsTraderAI.
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsMarketScanners {
    
    private const val TAG = "📡PerpsScanner"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class ScannerType(val emoji: String, val displayName: String, val description: String) {
        SOL_MOMENTUM("📈", "SOL Momentum", "Trend-following on SOL price action"),
        SOL_SNIPER("🎯", "SOL Sniper", "Quick volatility plays on SOL"),
        STOCK_QUALITY("💎", "Stock Quality", "Quality setups on tokenized stocks"),
        WHALE_LIQUIDATION("🐋", "Whale Liquidation", "Liquidation cascade detection"),
        FUNDING_RATE("📊", "Funding Arb", "Funding rate arbitrage"),
        VOLATILITY_BREAKOUT("⚡", "Vol Breakout", "Volatility squeeze breakouts"),
        CORRELATION("🔄", "Correlation", "SOL vs stock correlation plays"),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN RESULTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ScanResult(
        val scanner: ScannerType,
        val market: PerpsMarket,
        val signal: PerpsSignal?,
        val priority: Int,                 // 1-10, higher = more urgent
        val scanTime: Long = System.currentTimeMillis(),
        val reasoning: List<String>,
    )
    
    // Cache for recent scans
    private val recentScans = ConcurrentHashMap<String, ScanResult>()
    private const val SCAN_CACHE_TTL_MS = 30_000L  // 30 second cache
    
    // Stats
    private val totalScans = AtomicInteger(0)
    private val signalsGenerated = AtomicInteger(0)
    private val lastScanTime = AtomicLong(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCAN ORCHESTRATOR
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Run all scanners and return prioritized signals
     */
    suspend fun runAllScanners(isPaperMode: Boolean): List<ScanResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<ScanResult>()
        
        // Fetch market data for all markets
        val marketDataMap = mutableMapOf<PerpsMarket, PerpsMarketData>()
        PerpsMarket.values().forEach { market ->
            try {
                marketDataMap[market] = PerpsMarketDataFetcher.getMarketData(market)
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Failed to fetch ${market.symbol}: ${e.message}")
            }
        }
        
        ErrorLogger.debug(TAG, "📊 PERPS SCAN: Fetched ${marketDataMap.size} markets")
        
        // Run each scanner
        ScannerType.values().forEach { scannerType ->
            try {
                val scanResults = when (scannerType) {
                    ScannerType.SOL_MOMENTUM -> scanSolMomentum(marketDataMap, isPaperMode)
                    ScannerType.SOL_SNIPER -> scanSolSniper(marketDataMap, isPaperMode)
                    ScannerType.STOCK_QUALITY -> scanStockQuality(marketDataMap, isPaperMode)
                    ScannerType.WHALE_LIQUIDATION -> scanWhaleLiquidation(marketDataMap, isPaperMode)
                    ScannerType.FUNDING_RATE -> scanFundingRate(marketDataMap, isPaperMode)
                    ScannerType.VOLATILITY_BREAKOUT -> scanVolatilityBreakout(marketDataMap, isPaperMode)
                    ScannerType.CORRELATION -> scanCorrelation(marketDataMap, isPaperMode)
                }
                
                // Log scanner results
                val withSignals = scanResults.filter { it.signal != null }
                if (withSignals.isNotEmpty()) {
                    ErrorLogger.info(TAG, "📊 ${scannerType.name}: ${withSignals.size} signals (priority: ${withSignals.maxOfOrNull { it.priority }})")
                }
                
                results.addAll(scanResults)
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "Scanner ${scannerType.name} failed: ${e.message}")
            }
        }
        
        totalScans.incrementAndGet()
        lastScanTime.set(System.currentTimeMillis())
        
        // Log summary
        val signalCount = results.count { it.signal != null }
        val highPriority = results.count { it.priority >= 5 && it.signal != null }
        ErrorLogger.info(TAG, "📊 PERPS SCAN COMPLETE: $signalCount signals, $highPriority high-priority (≥5)")
        
        // Sort by priority (highest first)
        results.sortedByDescending { it.priority }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 1: SOL MOMENTUM SCANNER
    // Detects strong trends in SOL for trend-following positions
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanSolMomentum(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val reasoning = mutableListOf<String>()
        
        val solData = marketData[PerpsMarket.SOL] ?: return emptyList()
        
        val change24h = solData.priceChange24hPct
        val trend = solData.getTrend()
        val lsRatio = solData.getLongShortRatio()
        
        // Strong trend detection
        val hasMomentum = abs(change24h) > 3.0
        
        if (!hasMomentum) {
            return listOf(ScanResult(
                scanner = ScannerType.SOL_MOMENTUM,
                market = PerpsMarket.SOL,
                signal = null,
                priority = 1,
                reasoning = listOf("No strong momentum detected"),
            ))
        }
        
        val direction = if (change24h > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        var score = 50
        var confidence = 50
        
        // Trend strength
        when {
            abs(change24h) > 10 -> {
                score += 30
                confidence += 20
                reasoning.add("📈 STRONG trend: ${change24h.fmt(1)}%")
            }
            abs(change24h) > 5 -> {
                score += 20
                confidence += 10
                reasoning.add("📈 Moderate trend: ${change24h.fmt(1)}%")
            }
            else -> {
                score += 10
                reasoning.add("📈 Weak trend: ${change24h.fmt(1)}%")
            }
        }
        
        // Open Interest alignment
        val oiAligned = (direction == PerpsDirection.LONG && lsRatio < 1.0) ||
                        (direction == PerpsDirection.SHORT && lsRatio > 1.0)
        if (oiAligned) {
            score += 15
            confidence += 10
            reasoning.add("🎯 Contrarian OI setup")
        }
        
        // Volume confirmation
        if (solData.volume24h > 100_000_000) {
            score += 10
            reasoning.add("📊 High volume: \$${(solData.volume24h/1_000_000).toInt()}M")
        }
        
        // Leverage recommendation based on confidence
        val leverage = when {
            confidence >= 80 -> if (isPaperMode) 10.0 else 5.0
            confidence >= 65 -> if (isPaperMode) 7.0 else 3.0
            else -> if (isPaperMode) 5.0 else 2.0
        }
        
        val riskTier = when {
            leverage >= 10 -> PerpsRiskTier.ASSAULT
            leverage >= 5 -> PerpsRiskTier.TACTICAL
            else -> PerpsRiskTier.SNIPER
        }
        
        val signal = PerpsSignal(
            market = PerpsMarket.SOL,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            recommendedLeverage = leverage,
            recommendedSizePct = 8.0,
            recommendedRiskTier = riskTier,
            takeProfitPct = riskTier.takeProfitPct,
            stopLossPct = riskTier.stopLossPct,
            reasons = reasoning,
            aiReasoning = "📈 MOMENTUM: ${direction.symbol} SOL @ ${leverage.fmt(1)}x",
        )
        
        results.add(ScanResult(
            scanner = ScannerType.SOL_MOMENTUM,
            market = PerpsMarket.SOL,
            signal = signal,
            priority = if (confidence >= 70) 8 else 5,
            reasoning = reasoning,
        ))
        
        signalsGenerated.incrementAndGet()
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 2: SOL SNIPER SCANNER
    // Quick volatility plays - fast entries on SOL price spikes
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanSolSniper(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val reasoning = mutableListOf<String>()
        
        val solData = marketData[PerpsMarket.SOL] ?: return emptyList()
        
        // Look for volatility spikes (price near high or low of day)
        val priceRange = solData.high24h - solData.low24h
        val priceFromLow = solData.price - solData.low24h
        val priceFromHigh = solData.high24h - solData.price
        
        // Near day low = potential long snipe
        val nearLow = (priceFromLow / priceRange) < 0.2
        // Near day high = potential short snipe  
        val nearHigh = (priceFromHigh / priceRange) < 0.2
        
        if (!nearLow && !nearHigh) {
            return listOf(ScanResult(
                scanner = ScannerType.SOL_SNIPER,
                market = PerpsMarket.SOL,
                signal = null,
                priority = 1,
                reasoning = listOf("Price in middle of range - no snipe setup"),
            ))
        }
        
        val direction = if (nearLow) PerpsDirection.LONG else PerpsDirection.SHORT
        var score = 60
        var confidence = 55
        
        if (nearLow) {
            reasoning.add("🎯 Near day low - potential bounce")
            if (solData.isFundingFavorableLong()) {
                score += 15
                confidence += 10
                reasoning.add("💰 Funding favors longs")
            }
        } else {
            reasoning.add("🎯 Near day high - potential reversal")
            if (solData.isFundingFavorableShort()) {
                score += 15
                confidence += 10
                reasoning.add("💰 Funding favors shorts")
            }
        }
        
        // Volatility check
        if (solData.isVolatile()) {
            score += 10
            reasoning.add("⚡ High volatility environment")
        }
        
        // Sniper = aggressive leverage, tight stops
        val leverage = if (isPaperMode) 10.0 else 5.0
        
        val signal = PerpsSignal(
            market = PerpsMarket.SOL,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            recommendedLeverage = leverage,
            recommendedSizePct = 5.0,  // Smaller size for snipes
            recommendedRiskTier = PerpsRiskTier.TACTICAL,
            takeProfitPct = 5.0,  // Quick profit
            stopLossPct = 3.0,    // Tight stop
            reasons = reasoning,
            aiReasoning = "🎯 SNIPE: ${direction.symbol} SOL @ ${leverage.fmt(1)}x (quick play)",
        )
        
        results.add(ScanResult(
            scanner = ScannerType.SOL_SNIPER,
            market = PerpsMarket.SOL,
            signal = signal,
            priority = 6,
            reasoning = reasoning,
        ))
        
        signalsGenerated.incrementAndGet()
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 3: STOCK QUALITY SCANNER
    // Quality setups on tokenized stocks (AAPL, TSLA, NVDA, etc.)
    // V5.7.5: Enhanced with MTF confirmation and better signal generation
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanStockQuality(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        
        // Scan all stock markets
        val stockMarkets = PerpsMarket.values().filter { it.isStock }
        
        ErrorLogger.debug(TAG, "💎 STOCK SCAN: Checking ${stockMarkets.size} stocks (paper=$isPaperMode)")
        
        stockMarkets.forEach { market ->
            val data = marketData[market] ?: return@forEach
            val reasoning = mutableListOf<String>()
            
            // Check if market is open (paper mode allows 24/7)
            if (!PerpsMarketDataFetcher.isMarketTradeable(market, isPaperMode)) {
                ErrorLogger.debug(TAG, "💎 ${market.symbol}: Market closed, skipping")
                return@forEach
            }
            
            // Skip if price is invalid
            if (data.price <= 0) {
                ErrorLogger.debug(TAG, "💎 ${market.symbol}: Invalid price ${data.price}, skipping")
                return@forEach
            }
            
            val change = data.priceChange24hPct
            // V5.7.5: Much lower threshold in paper mode to generate more trades
            val threshold = if (isPaperMode) 0.1 else 1.0
            val hasTrend = abs(change) > threshold
            
            // V5.7.5: In paper mode, generate signals even without strong trend for learning
            if (!hasTrend && !isPaperMode) return@forEach
            
            val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
            var score = 60  // Higher base score
            var confidence = 65
            var priority = 5  // Higher base priority to ensure execution
            
            reasoning.add("${market.emoji} ${market.displayName}")
            reasoning.add("📊 Price: \$${data.price.fmt(2)}")
            reasoning.add("📈 Change: ${if (change > 0) "+" else ""}${change.fmt(2)}%")
            
            // Quality stocks get higher base confidence
            when (market) {
                PerpsMarket.AAPL, PerpsMarket.MSFT, PerpsMarket.GOOGL -> {
                    confidence += 10
                    score += 5
                    priority = 6
                    reasoning.add("💎 Blue chip quality")
                }
                PerpsMarket.NVDA -> {
                    confidence += 15  // AI darling
                    score += 10
                    priority = 7  // Highest priority
                    reasoning.add("🔥 AI sector leader")
                }
                PerpsMarket.TSLA -> {
                    score += 8
                    confidence += 5
                    priority = 6
                    reasoning.add("⚡ High volatility name")
                }
                PerpsMarket.META, PerpsMarket.AMZN -> {
                    confidence += 8
                    priority = 6
                    reasoning.add("📱 Tech giant")
                }
                PerpsMarket.COIN -> {
                    score += 5
                    priority = 6
                    reasoning.add("🪙 Crypto exposure")
                }
                else -> {
                    // Other stocks still get decent priority in paper mode
                    if (isPaperMode) priority = 5
                }
            }
            
            // Stronger moves get higher priority and score
            when {
                abs(change) > 3.0 -> {
                    priority += 2
                    score += 15
                    confidence += 10
                    reasoning.add("🚀 Strong move >3%")
                }
                abs(change) > 2.0 -> {
                    priority += 1
                    score += 10
                    reasoning.add("📊 Solid move >2%")
                }
                abs(change) > 1.0 -> {
                    score += 5
                    reasoning.add("📈 Trending >1%")
                }
            }
            
            // V5.7.5: Paper mode always generates signals for learning
            if (isPaperMode && priority < 5) {
                priority = 5  // Ensure stocks get executed in paper mode
            }
            
            // Conservative leverage for stocks
            val leverage = if (isPaperMode) 5.0 else 3.0
            
            val signal = PerpsSignal(
                market = market,
                direction = direction,
                score = score.coerceIn(0, 100),
                confidence = confidence.coerceIn(0, 100),
                recommendedLeverage = leverage,
                recommendedSizePct = 8.0,  // Slightly larger for quality stocks
                recommendedRiskTier = PerpsRiskTier.SNIPER,  // Conservative for stocks
                takeProfitPct = 8.0,
                stopLossPct = 4.0,
                reasons = reasoning,
                aiReasoning = "💎 STOCK: ${direction.symbol} ${market.symbol} @ \$${data.price.fmt(2)} | ${leverage.fmt(0)}x",
            )
            
            ErrorLogger.info(TAG, "💎 STOCK SIGNAL: ${market.symbol} ${direction.symbol} | \$${data.price.fmt(2)} | change=${change.fmt(2)}% | priority=$priority | score=$score")
            
            results.add(ScanResult(
                scanner = ScannerType.STOCK_QUALITY,
                market = market,
                signal = signal,
                priority = priority,
                reasoning = reasoning,
            ))
            
            signalsGenerated.incrementAndGet()
        }
        
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 4: WHALE LIQUIDATION SCANNER
    // Detects potential liquidation cascades for counter-trading
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanWhaleLiquidation(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val reasoning = mutableListOf<String>()
        
        val solData = marketData[PerpsMarket.SOL] ?: return emptyList()
        
        val lsRatio = solData.getLongShortRatio()
        val change = solData.priceChange24hPct
        
        // Detect crowded positions that could cascade
        val crowdedLongs = lsRatio > 1.5
        val crowdedShorts = lsRatio < 0.67
        
        if (!crowdedLongs && !crowdedShorts) {
            return listOf(ScanResult(
                scanner = ScannerType.WHALE_LIQUIDATION,
                market = PerpsMarket.SOL,
                signal = null,
                priority = 1,
                reasoning = listOf("No crowded positions detected"),
            ))
        }
        
        var score = 55
        var confidence = 50
        
        // Crowded longs + price dropping = potential long liquidation cascade
        val longLiquidationRisk = crowdedLongs && change < -3.0
        // Crowded shorts + price pumping = potential short squeeze
        val shortSqueezeRisk = crowdedShorts && change > 3.0
        
        if (!longLiquidationRisk && !shortSqueezeRisk) {
            return emptyList()
        }
        
        val direction: PerpsDirection
        
        if (longLiquidationRisk) {
            direction = PerpsDirection.SHORT
            score += 20
            confidence += 15
            reasoning.add("🐋 LONG LIQUIDATION CASCADE")
            reasoning.add("📊 L/S Ratio: ${lsRatio.fmt(2)} (crowded longs)")
            reasoning.add("📉 Price dropping: ${change.fmt(1)}%")
            reasoning.add("💀 Longs getting liquidated → more selling")
        } else {
            direction = PerpsDirection.LONG
            score += 20
            confidence += 15
            reasoning.add("🐋 SHORT SQUEEZE SETUP")
            reasoning.add("📊 L/S Ratio: ${lsRatio.fmt(2)} (crowded shorts)")
            reasoning.add("📈 Price pumping: ${change.fmt(1)}%")
            reasoning.add("🚀 Shorts getting squeezed → more buying")
        }
        
        // This is a high conviction play
        val leverage = if (isPaperMode) 15.0 else 7.0
        
        val signal = PerpsSignal(
            market = PerpsMarket.SOL,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            recommendedLeverage = leverage,
            recommendedSizePct = 10.0,
            recommendedRiskTier = PerpsRiskTier.ASSAULT,
            takeProfitPct = 20.0,
            stopLossPct = 8.0,
            reasons = reasoning,
            aiReasoning = "🐋 LIQUIDATION: ${direction.symbol} SOL @ ${leverage.fmt(1)}x (cascade play)",
        )
        
        results.add(ScanResult(
            scanner = ScannerType.WHALE_LIQUIDATION,
            market = PerpsMarket.SOL,
            signal = signal,
            priority = 9,  // High priority
            reasoning = reasoning,
        ))
        
        signalsGenerated.incrementAndGet()
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 5: FUNDING RATE SCANNER
    // Detects funding rate arbitrage opportunities
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanFundingRate(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val reasoning = mutableListOf<String>()
        
        val solData = marketData[PerpsMarket.SOL] ?: return emptyList()
        
        val fundingRate = solData.fundingRate
        val fundingAnnualized = solData.fundingRateAnnualized
        
        // Extreme funding = opportunity
        val extremePositive = fundingRate > 0.0003  // 0.03%+ per 8h
        val extremeNegative = fundingRate < -0.0003
        
        if (!extremePositive && !extremeNegative) {
            return listOf(ScanResult(
                scanner = ScannerType.FUNDING_RATE,
                market = PerpsMarket.SOL,
                signal = null,
                priority = 1,
                reasoning = listOf("Funding rate neutral"),
            ))
        }
        
        var score = 60
        var confidence = 55
        
        val direction: PerpsDirection
        
        if (extremePositive) {
            // Longs are paying shorts → go short
            direction = PerpsDirection.SHORT
            score += 15
            confidence += 10
            reasoning.add("📊 HIGH POSITIVE FUNDING")
            reasoning.add("💰 Rate: ${(fundingRate * 100).fmt(4)}% / 8h")
            reasoning.add("📈 Annualized: ${fundingAnnualized.fmt(1)}%")
            reasoning.add("💵 Get paid to short!")
        } else {
            // Shorts are paying longs → go long
            direction = PerpsDirection.LONG
            score += 15
            confidence += 10
            reasoning.add("📊 HIGH NEGATIVE FUNDING")
            reasoning.add("💰 Rate: ${(fundingRate * 100).fmt(4)}% / 8h")
            reasoning.add("📈 Annualized: ${fundingAnnualized.fmt(1)}%")
            reasoning.add("💵 Get paid to long!")
        }
        
        // Funding plays are lower leverage
        val leverage = if (isPaperMode) 5.0 else 3.0
        
        val signal = PerpsSignal(
            market = PerpsMarket.SOL,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            recommendedLeverage = leverage,
            recommendedSizePct = 10.0,
            recommendedRiskTier = PerpsRiskTier.SNIPER,
            takeProfitPct = 5.0,
            stopLossPct = 3.0,
            reasons = reasoning,
            aiReasoning = "📊 FUNDING: ${direction.symbol} SOL @ ${leverage.fmt(1)}x (carry trade)",
        )
        
        results.add(ScanResult(
            scanner = ScannerType.FUNDING_RATE,
            market = PerpsMarket.SOL,
            signal = signal,
            priority = 4,
            reasoning = reasoning,
        ))
        
        signalsGenerated.incrementAndGet()
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 6: VOLATILITY BREAKOUT SCANNER
    // Detects volatility squeeze breakouts
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanVolatilityBreakout(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        val reasoning = mutableListOf<String>()
        
        val solData = marketData[PerpsMarket.SOL] ?: return emptyList()
        
        // Calculate volatility metrics
        val range = (solData.high24h - solData.low24h) / solData.price * 100
        val change = abs(solData.priceChange24hPct)
        
        // Breakout = narrow range suddenly expanding
        val isBreakout = range > 5.0 && change > 3.0
        
        if (!isBreakout) {
            return listOf(ScanResult(
                scanner = ScannerType.VOLATILITY_BREAKOUT,
                market = PerpsMarket.SOL,
                signal = null,
                priority = 1,
                reasoning = listOf("No volatility breakout detected"),
            ))
        }
        
        val direction = if (solData.priceChange24hPct > 0) PerpsDirection.LONG else PerpsDirection.SHORT
        var score = 65
        var confidence = 60
        
        reasoning.add("⚡ VOLATILITY BREAKOUT")
        reasoning.add("📊 Range: ${range.fmt(1)}%")
        reasoning.add("📈 Move: ${if (solData.priceChange24hPct > 0) "+" else ""}${solData.priceChange24hPct.fmt(1)}%")
        
        // Volume confirmation
        if (solData.volume24h > 150_000_000) {
            score += 15
            confidence += 10
            reasoning.add("🔊 High volume breakout")
        }
        
        // Breakouts = aggressive leverage
        val leverage = if (isPaperMode) 12.0 else 6.0
        
        val signal = PerpsSignal(
            market = PerpsMarket.SOL,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            recommendedLeverage = leverage,
            recommendedSizePct = 8.0,
            recommendedRiskTier = PerpsRiskTier.ASSAULT,
            takeProfitPct = 15.0,
            stopLossPct = 6.0,
            reasons = reasoning,
            aiReasoning = "⚡ BREAKOUT: ${direction.symbol} SOL @ ${leverage.fmt(1)}x",
        )
        
        results.add(ScanResult(
            scanner = ScannerType.VOLATILITY_BREAKOUT,
            market = PerpsMarket.SOL,
            signal = signal,
            priority = 7,
            reasoning = reasoning,
        ))
        
        signalsGenerated.incrementAndGet()
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER 7: CORRELATION SCANNER
    // SOL vs stock correlation plays
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun scanCorrelation(
        marketData: Map<PerpsMarket, PerpsMarketData>,
        isPaperMode: Boolean,
    ): List<ScanResult> {
        val results = mutableListOf<ScanResult>()
        
        val solData = marketData[PerpsMarket.SOL] ?: return emptyList()
        val solChange = solData.priceChange24hPct
        
        // Check stock correlations
        val stockMarkets = listOf(PerpsMarket.NVDA, PerpsMarket.COIN, PerpsMarket.TSLA)
        
        stockMarkets.forEach { stockMarket ->
            val stockData = marketData[stockMarket] ?: return@forEach
            if (!PerpsMarketDataFetcher.isMarketTradeable(stockMarket)) return@forEach
            
            val stockChange = stockData.priceChange24hPct
            val reasoning = mutableListOf<String>()
            
            // Detect divergence (SOL and stock moving opposite)
            val divergence = (solChange > 2.0 && stockChange < -1.0) || 
                            (solChange < -2.0 && stockChange > 1.0)
            
            if (!divergence) return@forEach
            
            // Trade the divergence - expect convergence
            val direction = if (solChange > stockChange) PerpsDirection.LONG else PerpsDirection.SHORT
            
            var score = 55
            var confidence = 50
            
            reasoning.add("🔄 CORRELATION DIVERGENCE")
            reasoning.add("◎ SOL: ${if (solChange > 0) "+" else ""}${solChange.fmt(1)}%")
            reasoning.add("${stockMarket.emoji} ${stockMarket.symbol}: ${if (stockChange > 0) "+" else ""}${stockChange.fmt(1)}%")
            reasoning.add("📊 Expect convergence on ${stockMarket.symbol}")
            
            // COIN has highest SOL correlation
            if (stockMarket == PerpsMarket.COIN) {
                confidence += 15
                reasoning.add("🪙 COIN is highly correlated with crypto")
            }
            
            val leverage = if (isPaperMode) 5.0 else 3.0
            
            val signal = PerpsSignal(
                market = stockMarket,
                direction = direction,
                score = score.coerceIn(0, 100),
                confidence = confidence.coerceIn(0, 100),
                recommendedLeverage = leverage,
                recommendedSizePct = 6.0,
                recommendedRiskTier = PerpsRiskTier.SNIPER,
                takeProfitPct = 6.0,
                stopLossPct = 4.0,
                reasons = reasoning,
                aiReasoning = "🔄 CORR: ${direction.symbol} ${stockMarket.symbol} @ ${leverage.fmt(1)}x (divergence)",
            )
            
            results.add(ScanResult(
                scanner = ScannerType.CORRELATION,
                market = stockMarket,
                signal = signal,
                priority = 4,
                reasoning = reasoning,
            ))
            
            signalsGenerated.incrementAndGet()
        }
        
        return results
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getTotalScans(): Int = totalScans.get()
    fun getSignalsGenerated(): Int = signalsGenerated.get()
    fun getLastScanTime(): Long = lastScanTime.get()
    
    fun getScannerStats(): Map<ScannerType, Int> {
        // Would track per-scanner signal counts in production
        return ScannerType.values().associateWith { 0 }
    }
}
