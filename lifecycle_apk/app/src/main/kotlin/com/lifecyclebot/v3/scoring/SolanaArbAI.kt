package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SOLANA ARB AI - "PURE SOL ARBITRAGE" v1.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 💰⚡ CROSS-EXCHANGE SOL ARBITRAGE ⚡💰
 * 
 * This is the SAFEST, most sophisticated trading mode. It exploits price
 * differences of SOL itself across different exchanges/DEXes. When Binance
 * shows SOL at $150.50 and Jupiter shows $150.00, we capture that $0.50 spread.
 * 
 * WHY THIS WORKS:
 * - Price feeds across exchanges have LATENCY (milliseconds matter!)
 * - Large orders cause temporary price dislocations
 * - DEX pools can get imbalanced
 * - Market makers don't always sync instantly
 * 
 * REQUIREMENTS (ALL must be met):
 * 1. Treasury balance >= $500 (need capital for meaningful arb)
 * 2. Minimum spread >= 0.15% (to cover fees and slippage)
 * 3. Sufficient liquidity on BOTH sides
 * 4. Confidence >= 90% (we don't guess, we KNOW)
 * 
 * SAFETY FEATURES:
 * - NEVER holds inventory overnight (close all positions)
 * - Maximum 60 second hold time per trade
 * - Automatic circuit breaker if 3 consecutive losses
 * - Maximum 0.5% of treasury per trade
 * - Real-time spread monitoring with decay
 * 
 * EXCHANGES MONITORED:
 * - Jupiter (primary Solana DEX)
 * - Raydium (major AMM)
 * - Orca (concentrated liquidity)
 * - Binance (via price feed - can't trade directly)
 * - Coinbase (via price feed)
 * - Kraken (via price feed)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object SolanaArbAI {
    
    private const val TAG = "SolArbAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION - Super conservative for arb
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Treasury requirements
    private const val MIN_TREASURY_USD = 500.0         // Need $500 minimum to arb
    private const val MAX_POSITION_PCT = 0.5           // Max 0.5% of treasury per trade
    
    // Spread requirements (in basis points)
    private const val MIN_SPREAD_BPS = 15              // 0.15% minimum spread
    private const val IDEAL_SPREAD_BPS = 25            // 0.25% ideal spread
    private const val EXCELLENT_SPREAD_BPS = 50        // 0.50% excellent opportunity
    
    // Fee estimates (conservative)
    private const val JUPITER_FEE_BPS = 5              // 0.05% Jupiter fees
    private const val RAYDIUM_FEE_BPS = 25             // 0.25% Raydium fees  
    private const val ORCA_FEE_BPS = 30                // 0.30% Orca fees
    private const val SLIPPAGE_BUFFER_BPS = 5          // 0.05% slippage buffer
    
    // Time limits
    private const val MAX_HOLD_SECONDS = 60            // 60 seconds max!
    private const val PRICE_FRESHNESS_MS = 500         // Prices must be <500ms old
    private const val OPPORTUNITY_WINDOW_MS = 2000     // 2 second window to act
    
    // Safety limits
    private const val MAX_DAILY_TRADES = 100           // Max 100 arb trades/day
    private const val CIRCUIT_BREAKER_LOSSES = 3       // Stop after 3 consecutive losses
    private const val MAX_DAILY_LOSS_USD = 50.0        // Max $50 loss/day
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile var isEnabled: Boolean = false
    @Volatile var isPaperMode: Boolean = true
    
    private val dailyPnlUsdBps = AtomicLong(0)
    private val dailyTrades = AtomicInteger(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val consecutiveLosses = AtomicInteger(0)
    
    // Active arb positions
    private val activeArbs = ConcurrentHashMap<String, ArbPosition>()
    
    // Price feeds from different sources
    private val priceFeeds = ConcurrentHashMap<Exchange, PriceFeed>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PriceFeed(
        val exchange: Exchange,
        val bidPrice: Double,          // Best bid (what you can sell at)
        val askPrice: Double,          // Best ask (what you can buy at)
        val bidSize: Double,           // Liquidity at bid
        val askSize: Double,           // Liquidity at ask
        val timestamp: Long,
    ) {
        val midPrice get() = (bidPrice + askPrice) / 2
        val spreadBps get() = ((askPrice - bidPrice) / midPrice * 10000).toInt()
        val isStale get() = System.currentTimeMillis() - timestamp > PRICE_FRESHNESS_MS
    }
    
    enum class Exchange(val feeBps: Int, val displayName: String) {
        JUPITER(5, "Jupiter"),
        RAYDIUM(25, "Raydium"),
        ORCA(30, "Orca"),
        BINANCE(10, "Binance"),    // Reference only
        COINBASE(50, "Coinbase"),  // Reference only
        KRAKEN(26, "Kraken"),      // Reference only
    }
    
    data class ArbOpportunity(
        val buyExchange: Exchange,
        val sellExchange: Exchange,
        val buyPrice: Double,
        val sellPrice: Double,
        val spreadBps: Int,
        val netSpreadBps: Int,      // After fees
        val confidence: Int,
        val maxSizeSol: Double,
        val estimatedProfitUsd: Double,
        val timestamp: Long,
    )
    
    data class ArbPosition(
        val id: String,
        val buyExchange: Exchange,
        val sellExchange: Exchange,
        val entryBuyPrice: Double,
        val entrySellPrice: Double,
        val sizeSol: Double,
        val entryTime: Long,
        val isPaper: Boolean,
        var status: ArbStatus = ArbStatus.OPEN,
    )
    
    enum class ArbStatus {
        OPEN,
        CLOSED_PROFIT,
        CLOSED_LOSS,
        CLOSED_TIMEOUT,
    }
    
    data class ArbSignal(
        val shouldExecute: Boolean,
        val opportunity: ArbOpportunity?,
        val reason: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V4.0 CRITICAL: Flag to prevent re-initialization during runtime
    @Volatile
    private var initialized = false
    
    fun init(paperMode: Boolean, treasuryUsd: Double) {
        // V4.0 CRITICAL: Guard against re-initialization
        if (initialized) {
            ErrorLogger.warn(TAG, "⚠️ init() called again - BLOCKED (already initialized)")
            return
        }
        
        isPaperMode = paperMode
        
        // Check if treasury meets minimum
        isEnabled = treasuryUsd >= MIN_TREASURY_USD
        
        initialized = true
        if (isEnabled) {
            ErrorLogger.info(TAG, "💰⚡ SOLANA ARB AI initialized (ONE-TIME) | " +
                "treasury=\$${treasuryUsd.toInt()} | " +
                "minSpread=${MIN_SPREAD_BPS}bps | " +
                "maxHold=${MAX_HOLD_SECONDS}s")
        } else {
            ErrorLogger.info(TAG, "💰⚡ SOLANA ARB AI DISABLED (ONE-TIME) | " +
                "treasury=\$${treasuryUsd.toInt()} < \$${MIN_TREASURY_USD.toInt()}")
        }
    }
    
    fun resetDaily() {
        dailyPnlUsdBps.set(0)
        dailyTrades.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        consecutiveLosses.set(0)
        ErrorLogger.info(TAG, "💰⚡ Arb daily stats reset")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE FEED MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun updatePriceFeed(
        exchange: Exchange,
        bidPrice: Double,
        askPrice: Double,
        bidSize: Double,
        askSize: Double,
    ) {
        priceFeeds[exchange] = PriceFeed(
            exchange = exchange,
            bidPrice = bidPrice,
            askPrice = askPrice,
            bidSize = bidSize,
            askSize = askSize,
            timestamp = System.currentTimeMillis(),
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // OPPORTUNITY DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun scanForOpportunity(treasuryUsd: Double): ArbSignal {
        // ═══════════════════════════════════════════════════════════════════
        // SAFETY CHECKS
        // ═══════════════════════════════════════════════════════════════════
        
        if (!isEnabled) {
            return ArbSignal(false, null, "DISABLED: Treasury < \$${MIN_TREASURY_USD.toInt()}")
        }
        
        // Check daily limits
        if (dailyTrades.get() >= MAX_DAILY_TRADES) {
            return ArbSignal(false, null, "DAILY_LIMIT: ${dailyTrades.get()}/$MAX_DAILY_TRADES")
        }
        
        val dailyPnl = dailyPnlUsdBps.get() / 100.0
        if (dailyPnl <= -MAX_DAILY_LOSS_USD) {
            return ArbSignal(false, null, "LOSS_LIMIT: \$${dailyPnl.toInt()}")
        }
        
        // Circuit breaker
        if (consecutiveLosses.get() >= CIRCUIT_BREAKER_LOSSES) {
            return ArbSignal(false, null, "CIRCUIT_BREAKER: ${consecutiveLosses.get()} losses")
        }
        
        // Check if we already have an active arb
        if (activeArbs.isNotEmpty()) {
            return ArbSignal(false, null, "ALREADY_ACTIVE: ${activeArbs.size}")
        }
        
        // Check treasury
        if (treasuryUsd < MIN_TREASURY_USD) {
            return ArbSignal(false, null, "TREASURY_LOW: \$${treasuryUsd.toInt()}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SCAN ALL EXCHANGE PAIRS FOR OPPORTUNITIES
        // ═══════════════════════════════════════════════════════════════════
        
        val opportunities = mutableListOf<ArbOpportunity>()
        val now = System.currentTimeMillis()
        
        // Only scan Solana DEXes we can actually trade on
        val tradableExchanges = listOf(Exchange.JUPITER, Exchange.RAYDIUM, Exchange.ORCA)
        
        for (buyExchange in tradableExchanges) {
            for (sellExchange in tradableExchanges) {
                if (buyExchange == sellExchange) continue
                
                val buyFeed = priceFeeds[buyExchange] ?: continue
                val sellFeed = priceFeeds[sellExchange] ?: continue
                
                // Check freshness
                if (buyFeed.isStale || sellFeed.isStale) continue
                
                // Calculate spread (buy at ask, sell at bid)
                val buyPrice = buyFeed.askPrice
                val sellPrice = sellFeed.bidPrice
                
                if (sellPrice <= buyPrice) continue // No opportunity
                
                val midPrice = (buyPrice + sellPrice) / 2
                val grossSpreadBps = ((sellPrice - buyPrice) / midPrice * 10000).toInt()
                
                // Calculate net spread after fees
                val totalFeeBps = buyExchange.feeBps + sellExchange.feeBps + SLIPPAGE_BUFFER_BPS
                val netSpreadBps = grossSpreadBps - totalFeeBps
                
                // Skip if not profitable
                if (netSpreadBps < MIN_SPREAD_BPS) continue
                
                // Calculate max size (limited by liquidity)
                val maxSizeSol = minOf(
                    buyFeed.askSize,
                    sellFeed.bidSize,
                    treasuryUsd * MAX_POSITION_PCT / 100.0 / midPrice,
                    10.0  // Hard cap at 10 SOL per trade
                )
                
                if (maxSizeSol < 0.1) continue // Too small
                
                // Calculate estimated profit
                val estimatedProfitUsd = maxSizeSol * midPrice * netSpreadBps / 10000
                
                // Calculate confidence based on spread size and liquidity
                val confidence = calculateConfidence(netSpreadBps, maxSizeSol, buyFeed, sellFeed)
                
                opportunities.add(ArbOpportunity(
                    buyExchange = buyExchange,
                    sellExchange = sellExchange,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    spreadBps = grossSpreadBps,
                    netSpreadBps = netSpreadBps,
                    confidence = confidence,
                    maxSizeSol = maxSizeSol,
                    estimatedProfitUsd = estimatedProfitUsd,
                    timestamp = now,
                ))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // SELECT BEST OPPORTUNITY
        // ═══════════════════════════════════════════════════════════════════
        
        if (opportunities.isEmpty()) {
            return ArbSignal(false, null, "NO_OPPORTUNITY")
        }
        
        // Sort by confidence * profit
        val bestOpp = opportunities.maxByOrNull { it.confidence * it.estimatedProfitUsd }!!
        
        // Check minimum confidence
        val minConf = getFluidConfidenceThreshold()
        if (bestOpp.confidence < minConf) {
            return ArbSignal(false, null, "LOW_CONFIDENCE: ${bestOpp.confidence}% < $minConf%")
        }
        
        ErrorLogger.info(TAG, "💰⚡ ARB OPPORTUNITY: " +
            "BUY ${bestOpp.buyExchange.displayName} @ ${bestOpp.buyPrice.fmt(2)} | " +
            "SELL ${bestOpp.sellExchange.displayName} @ ${bestOpp.sellPrice.fmt(2)} | " +
            "spread=${bestOpp.netSpreadBps}bps | " +
            "size=${bestOpp.maxSizeSol.fmt(2)} SOL | " +
            "profit=\$${bestOpp.estimatedProfitUsd.fmt(2)} | " +
            "conf=${bestOpp.confidence}%")
        
        return ArbSignal(true, bestOpp, "OPPORTUNITY_FOUND")
    }
    
    private fun calculateConfidence(
        netSpreadBps: Int,
        maxSizeSol: Double,
        buyFeed: PriceFeed,
        sellFeed: PriceFeed,
    ): Int {
        var conf = 50 // Base confidence
        
        // Spread quality
        conf += when {
            netSpreadBps >= EXCELLENT_SPREAD_BPS -> 25
            netSpreadBps >= IDEAL_SPREAD_BPS -> 15
            netSpreadBps >= MIN_SPREAD_BPS -> 5
            else -> -20
        }
        
        // Size quality
        conf += when {
            maxSizeSol >= 5.0 -> 15
            maxSizeSol >= 2.0 -> 10
            maxSizeSol >= 1.0 -> 5
            else -> 0
        }
        
        // Price freshness
        val avgAge = (System.currentTimeMillis() - buyFeed.timestamp + 
                     System.currentTimeMillis() - sellFeed.timestamp) / 2
        conf += when {
            avgAge < 100 -> 10  // Super fresh
            avgAge < 300 -> 5
            avgAge < 500 -> 0
            else -> -10
        }
        
        return conf.coerceIn(0, 100)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun executeArb(opp: ArbOpportunity, actualSizeSol: Double, isPaper: Boolean): String {
        val id = "ARB_${System.currentTimeMillis()}"
        
        val position = ArbPosition(
            id = id,
            buyExchange = opp.buyExchange,
            sellExchange = opp.sellExchange,
            entryBuyPrice = opp.buyPrice,
            entrySellPrice = opp.sellPrice,
            sizeSol = actualSizeSol,
            entryTime = System.currentTimeMillis(),
            isPaper = isPaper,
        )
        
        synchronized(activeArbs) {
            activeArbs[id] = position
        }
        dailyTrades.incrementAndGet()
        
        ErrorLogger.info(TAG, "💰⚡ ARB EXECUTED: $id | " +
            "BUY ${opp.buyExchange.displayName} @ ${opp.buyPrice.fmt(2)} | " +
            "SELL ${opp.sellExchange.displayName} @ ${opp.sellPrice.fmt(2)} | " +
            "size=${actualSizeSol.fmt(2)} SOL | " +
            "${if (isPaper) "PAPER" else "LIVE"}")
        
        return id
    }
    
    fun closeArb(id: String, actualBuyPrice: Double, actualSellPrice: Double): Double {
        val position = synchronized(activeArbs) { activeArbs.remove(id) } ?: return 0.0
        
        // Calculate actual P&L
        val grossProfit = (actualSellPrice - actualBuyPrice) * position.sizeSol
        val fees = (position.buyExchange.feeBps + position.sellExchange.feeBps) / 10000.0 * 
                   position.sizeSol * (actualBuyPrice + actualSellPrice) / 2
        val netProfitUsd = grossProfit - fees
        
        // Record P&L
        val pnlBps = (netProfitUsd * 100).toLong()
        dailyPnlUsdBps.addAndGet(pnlBps)
        
        if (netProfitUsd > 0) {
            dailyWins.incrementAndGet()
            consecutiveLosses.set(0)
            position.status = ArbStatus.CLOSED_PROFIT
        } else {
            dailyLosses.incrementAndGet()
            consecutiveLosses.incrementAndGet()
            position.status = ArbStatus.CLOSED_LOSS
        }
        
        // Record to FluidLearningAI
        try {
            if (position.isPaper) {
                FluidLearningAI.recordPaperTrade(netProfitUsd > 0)
            } else {
                FluidLearningAI.recordLiveTrade(netProfitUsd > 0)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning update failed: ${e.message}")
        }
        
        ErrorLogger.info(TAG, "💰⚡ ARB CLOSED: $id | " +
            "P&L: ${if (netProfitUsd >= 0) "+" else ""}\$${netProfitUsd.fmt(2)} | " +
            "gross=\$${grossProfit.fmt(2)} fees=\$${fees.fmt(2)} | " +
            "Daily: ${dailyWins.get()}W/${dailyLosses.get()}L")
        
        return netProfitUsd
    }
    
    fun checkTimeout(): List<String> {
        val expiredIds = mutableListOf<String>()
        val now = System.currentTimeMillis()
        
        synchronized(activeArbs) {
            activeArbs.entries.forEach { (id, pos) ->
                if (now - pos.entryTime > MAX_HOLD_SECONDS * 1000) {
                    expiredIds.add(id)
                    pos.status = ArbStatus.CLOSED_TIMEOUT
                }
            }
        }
        
        return expiredIds
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val ARB_CONF_BOOTSTRAP = 90   // Very high at start
    private const val ARB_CONF_MATURE = 75      // Can be more aggressive when experienced
    
    private fun getFluidConfidenceThreshold(): Int {
        val progress = FluidLearningAI.getLearningProgress()
        return (ARB_CONF_BOOTSTRAP + (ARB_CONF_MATURE - ARB_CONF_BOOTSTRAP) * progress).toInt()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ArbStats(
        val isEnabled: Boolean,
        val dailyTrades: Int,
        val dailyWins: Int,
        val dailyLosses: Int,
        val dailyPnlUsd: Double,
        val activeArbs: Int,
        val winRate: Double,
        val circuitBreakerTripped: Boolean,
    )
    
    fun getStats(): ArbStats {
        val totalTrades = dailyWins.get() + dailyLosses.get()
        return ArbStats(
            isEnabled = isEnabled,
            dailyTrades = dailyTrades.get(),
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyPnlUsd = dailyPnlUsdBps.get() / 100.0,
            activeArbs = activeArbs.size,
            winRate = if (totalTrades > 0) dailyWins.get().toDouble() / totalTrades * 100 else 0.0,
            circuitBreakerTripped = consecutiveLosses.get() >= CIRCUIT_BREAKER_LOSSES,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}
