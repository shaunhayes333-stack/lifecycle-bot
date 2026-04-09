package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 PERPS TRADER AI - SUPER SMART LEVERAGE INTELLIGENCE - V5.7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The BRAIN behind SOL Perps & Tokenized Stock trading.
 * 
 * PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📈 FLUID SIZING    - Position size adapts to market conditions & confidence
 *   🎯 STRICT DISCIPLINE - Hard rules prevent emotional trading
 *   🧠 LEARNING        - Improves from every trade (paper → live)
 *   ⚖️ RISK MANAGEMENT - Never risk more than you can afford to lose
 *   🔥 LEVERAGE INTELLIGENCE - AI decides optimal leverage based on conviction
 * 
 * RISK TIERS:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🎯 SNIPER (2x)     - Low leverage, high probability setups
 *   ⚔️ TACTICAL (5x)   - Moderate leverage for confident trades
 *   💥 ASSAULT (10x)   - High leverage for high conviction
 *   ☢️ NUCLEAR (20x)   - Maximum leverage for "sure things" (paper only until proven)
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Paper/Live mode separation with transfer learning
 *   • Live Readiness Gauge - tracks when paper performance justifies live trading
 *   • Tokenized stocks support (AAPL, TSLA, NVDA, etc.)
 *   • Auto-deleverage when approaching liquidation
 *   • Trailing stops for profitable positions
 *   • Daily loss limits to prevent blowups
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsTraderAI {
    
    private const val TAG = "📊PerpsAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Position limits
    private const val MAX_CONCURRENT_POSITIONS = 5
    private const val MAX_POSITION_PCT_OF_BALANCE = 25.0
    private const val MIN_POSITION_SOL = 0.05
    
    // Daily limits
    private const val DAILY_MAX_LOSS_PCT = 15.0           // Max 15% daily drawdown
    private const val DAILY_MAX_TRADES = 30               // Prevent overtrading
    
    // Readiness thresholds
    private const val MIN_PAPER_TRADES_FOR_LIVE = 50
    private const val MIN_WIN_RATE_FOR_LIVE = 45.0
    private const val MIN_READINESS_SCORE_FOR_LIVE = 75
    
    // Learning thresholds
    private const val LEARNING_BOOTSTRAP_TRADES = 20
    private const val LEARNING_MATURE_TRADES = 100
    
    // Leverage intelligence
    private const val BASE_LEVERAGE_PAPER = 5.0
    private const val BASE_LEVERAGE_LIVE = 2.0
    private const val MAX_LEVERAGE_PAPER = 20.0
    private const val MAX_LEVERAGE_LIVE = 10.0
    
    // V5.7.3: Perps trading fee configuration
    private const val PERPS_FEE_WALLET_1 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
    private const val PERPS_FEE_WALLET_2 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"
    private const val PERPS_TRADING_FEE_PERCENT = 0.01  // 1% fee for perps/leverage trades
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isEnabled = AtomicBoolean(false)
    private val hasAcknowledgedRisk = AtomicBoolean(false)
    
    @Volatile var isPaperMode: Boolean = true
    
    // Balances (stored as basis points for precision)
    private val paperBalanceBps = AtomicLong(500_0000)  // 5 SOL paper balance
    private val liveBalanceBps = AtomicLong(0)
    
    // Daily stats
    private val dailyTrades = AtomicInteger(0)
    private val dailyWins = AtomicInteger(0)
    private val dailyLosses = AtomicInteger(0)
    private val dailyPnlBps = AtomicLong(0)
    
    // Lifetime stats
    private val lifetimeTrades = AtomicInteger(0)
    private val lifetimeWins = AtomicInteger(0)
    private val lifetimeLosses = AtomicInteger(0)
    private val lifetimePnlBps = AtomicLong(0)
    private val lifetimeBestPnlBps = AtomicLong(0)
    private val lifetimeWorstPnlBps = AtomicLong(Long.MAX_VALUE)
    
    // Streak tracking
    private val currentStreak = AtomicInteger(0)
    private val maxWinStreak = AtomicInteger(0)
    private val maxLossStreak = AtomicInteger(0)
    private val consecutiveLosses = AtomicInteger(0)
    
    // Learning progress (0-100)
    private val learningProgress = AtomicInteger(0)
    private val disciplineScore = AtomicInteger(50)
    
    // Active positions
    private val activePositions = ConcurrentHashMap<String, PerpsPosition>()
    private val paperPositions = ConcurrentHashMap<String, PerpsPosition>()
    private val livePositions = ConcurrentHashMap<String, PerpsPosition>()
    
    // Trade history for learning
    private val recentTrades = mutableListOf<PerpsTrade>()
    private const val MAX_RECENT_TRADES = 200
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE - SharedPreferences
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L
    
    /**
     * Initialize persistence - call from BotService.onCreate()
     */
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("perps_trader_ai", android.content.Context.MODE_PRIVATE)
        restore()
        
        // V5.7.3: AUTO-ENABLE in paper mode for continuous learning
        // The perps system should ALWAYS be learning, even if not live trading
        if (!isEnabled.get()) {
            isEnabled.set(true)
            isPaperMode.set(true)  // Force paper mode for safety
            ErrorLogger.info(TAG, "📊 PerpsTraderAI AUTO-ENABLED in paper mode for learning")
            save()
        }
        
        ErrorLogger.info(TAG, "📊 PerpsTraderAI ONLINE - Ready for leverage trading (enabled=${isEnabled.get()}, paper=${isPaperMode.get()})")
    }
    
    /**
     * Restore state from SharedPreferences
     */
    private fun restore() {
        val p = prefs ?: return
        
        // Restore flags
        isEnabled.set(p.getBoolean("isEnabled", false))
        hasAcknowledgedRisk.set(p.getBoolean("hasAcknowledgedRisk", false))
        
        // Balances
        paperBalanceBps.set(p.getLong("paperBalanceBps", 500_0000))
        liveBalanceBps.set(p.getLong("liveBalanceBps", 0))
        
        // Lifetime stats
        lifetimeTrades.set(p.getInt("lifetimeTrades", 0))
        lifetimeWins.set(p.getInt("lifetimeWins", 0))
        lifetimeLosses.set(p.getInt("lifetimeLosses", 0))
        lifetimePnlBps.set(p.getLong("lifetimePnlBps", 0))
        lifetimeBestPnlBps.set(p.getLong("lifetimeBestPnlBps", 0))
        lifetimeWorstPnlBps.set(p.getLong("lifetimeWorstPnlBps", Long.MAX_VALUE))
        
        // Streaks
        maxWinStreak.set(p.getInt("maxWinStreak", 0))
        maxLossStreak.set(p.getInt("maxLossStreak", 0))
        
        // Learning
        learningProgress.set(p.getInt("learningProgress", 0))
        disciplineScore.set(p.getInt("disciplineScore", 50))
        
        // Daily stats (only restore if same day)
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyTrades.set(p.getInt("dailyTrades", 0))
            dailyWins.set(p.getInt("dailyWins", 0))
            dailyLosses.set(p.getInt("dailyLosses", 0))
            dailyPnlBps.set(p.getLong("dailyPnlBps", 0))
            currentStreak.set(p.getInt("currentStreak", 0))
            consecutiveLosses.set(p.getInt("consecutiveLosses", 0))
        }
        
        ErrorLogger.info(TAG, "📊 RESTORED: trades=${lifetimeTrades.get()} | " +
            "wins=${lifetimeWins.get()} | losses=${lifetimeLosses.get()} | " +
            "pnl=${lifetimePnlBps.get()/10000.0}◎ | learning=${learningProgress.get()}%")
    }
    
    /**
     * Save state to SharedPreferences (throttled)
     */
    fun save(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        
        p.edit().apply {
            // Flags
            putBoolean("isEnabled", isEnabled.get())
            putBoolean("hasAcknowledgedRisk", hasAcknowledgedRisk.get())
            
            // Balances
            putLong("paperBalanceBps", paperBalanceBps.get())
            putLong("liveBalanceBps", liveBalanceBps.get())
            
            // Lifetime stats
            putInt("lifetimeTrades", lifetimeTrades.get())
            putInt("lifetimeWins", lifetimeWins.get())
            putInt("lifetimeLosses", lifetimeLosses.get())
            putLong("lifetimePnlBps", lifetimePnlBps.get())
            putLong("lifetimeBestPnlBps", lifetimeBestPnlBps.get())
            putLong("lifetimeWorstPnlBps", lifetimeWorstPnlBps.get())
            
            // Streaks
            putInt("maxWinStreak", maxWinStreak.get())
            putInt("maxLossStreak", maxLossStreak.get())
            
            // Learning
            putInt("learningProgress", learningProgress.get())
            putInt("disciplineScore", disciplineScore.get())
            
            // Daily stats
            putLong("savedDay", now / (24 * 60 * 60 * 1000))
            putInt("dailyTrades", dailyTrades.get())
            putInt("dailyWins", dailyWins.get())
            putInt("dailyLosses", dailyLosses.get())
            putLong("dailyPnlBps", dailyPnlBps.get())
            putInt("currentStreak", currentStreak.get())
            putInt("consecutiveLosses", consecutiveLosses.get())
            
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION & MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun isEnabled(): Boolean = isEnabled.get()
    fun hasAcknowledgedRisk(): Boolean = hasAcknowledgedRisk.get()
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        save(force = true)
        ErrorLogger.info(TAG, "📊 PerpsTraderAI ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    fun acknowledgeRisk() {
        hasAcknowledgedRisk.set(true)
        save(force = true)
        ErrorLogger.info(TAG, "📊 Risk acknowledged - Perps trading unlocked")
    }
    
    fun setTradingMode(isPaper: Boolean) {
        val wasInPaper = isPaperMode
        isPaperMode = isPaper
        
        // Transfer learning when going from paper to live
        if (!isPaper && wasInPaper) {
            ErrorLogger.info(TAG, "📊 MODE SWITCH: PAPER → LIVE | Transferring learning...")
        }
        
        save(force = true)
    }
    
    fun resetDaily() {
        dailyTrades.set(0)
        dailyWins.set(0)
        dailyLosses.set(0)
        dailyPnlBps.set(0)
        currentStreak.set(0)
        consecutiveLosses.set(0)
        save(force = true)
        ErrorLogger.info(TAG, "📊 Daily stats reset")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SIGNAL GENERATION - The AI Brain
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Generates a trading signal for the given market
     */
    fun generateSignal(
        market: PerpsMarket,
        marketData: PerpsMarketData,
        isPaper: Boolean,
    ): PerpsSignal {
        
        val reasons = mutableListOf<String>()
        var score = 50  // Base score
        var confidence = 50
        
        // ═══════════════════════════════════════════════════════════════════
        // PRE-FLIGHT CHECKS
        // ═══════════════════════════════════════════════════════════════════
        
        // Daily limits
        if (dailyTrades.get() >= DAILY_MAX_TRADES) {
            return noTradeSignal(market, "DAILY_TRADE_LIMIT", reasons)
        }
        
        val dailyPnlPct = getDailyPnlPct()
        if (dailyPnlPct <= -DAILY_MAX_LOSS_PCT) {
            return noTradeSignal(market, "DAILY_LOSS_LIMIT", reasons)
        }
        
        // Max positions
        if (activePositions.size >= MAX_CONCURRENT_POSITIONS) {
            return noTradeSignal(market, "MAX_POSITIONS", reasons)
        }
        
        // Stock market hours (simplified - would need real market hours check)
        if (market.isStock && !isMarketOpen(market)) {
            return noTradeSignal(market, "MARKET_CLOSED", reasons)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // DIRECTION ANALYSIS
        // ═══════════════════════════════════════════════════════════════════
        
        val direction: PerpsDirection
        
        // Trend analysis
        val trend = marketData.getTrend()
        when (trend) {
            "BULLISH" -> {
                direction = PerpsDirection.LONG
                score += 15
                confidence += 10
                reasons.add("📈 Bullish trend: +${marketData.priceChange24hPct.fmt(1)}%")
            }
            "BEARISH" -> {
                direction = PerpsDirection.SHORT
                score += 15
                confidence += 10
                reasons.add("📉 Bearish trend: ${marketData.priceChange24hPct.fmt(1)}%")
            }
            else -> {
                // Neutral - use funding rate
                direction = if (marketData.isFundingFavorableLong()) PerpsDirection.LONG else PerpsDirection.SHORT
                reasons.add("➡️ Neutral trend - using funding bias")
            }
        }
        
        // Funding rate bonus
        val fundingFavorable = when (direction) {
            PerpsDirection.LONG -> marketData.isFundingFavorableLong()
            PerpsDirection.SHORT -> marketData.isFundingFavorableShort()
        }
        if (fundingFavorable) {
            score += 10
            confidence += 5
            reasons.add("💰 Funding rate favorable: ${(marketData.fundingRate * 100).fmt(4)}%")
        }
        
        // Open interest analysis
        val lsRatio = marketData.getLongShortRatio()
        val oiScore = when {
            direction == PerpsDirection.LONG && lsRatio < 0.8 -> {
                reasons.add("🐻 Crowded shorts - potential squeeze")
                15
            }
            direction == PerpsDirection.SHORT && lsRatio > 1.2 -> {
                reasons.add("🐂 Crowded longs - potential flush")
                15
            }
            else -> 0
        }
        score += oiScore
        
        // Volume analysis
        if (marketData.volume24h > 0) {
            score += 5
            confidence += 5
            reasons.add("📊 24h volume: \$${(marketData.volume24h/1_000_000).fmt(1)}M")
        }
        
        // Volatility check
        if (marketData.isVolatile()) {
            score += 5
            reasons.add("⚡ High volatility - increased opportunity")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // LEVERAGE INTELLIGENCE
        // ═══════════════════════════════════════════════════════════════════
        
        val baseMax = if (isPaper) MAX_LEVERAGE_PAPER else MAX_LEVERAGE_LIVE
        val baseLev = if (isPaper) BASE_LEVERAGE_PAPER else BASE_LEVERAGE_LIVE
        
        // Calculate optimal leverage based on confidence and streak
        val streakBonus = when {
            currentStreak.get() >= 5 -> 2.0   // Hot streak - can be more aggressive
            currentStreak.get() >= 3 -> 1.0
            currentStreak.get() <= -3 -> -2.0  // Cold streak - reduce leverage
            else -> 0.0
        }
        
        val confidenceLeverage = baseLev + (confidence - 50) * 0.1 + streakBonus
        val recommendedLeverage = confidenceLeverage.coerceIn(1.0, min(baseMax, market.maxLeverage))
        
        // Determine risk tier based on leverage
        val riskTier = when {
            recommendedLeverage <= 2.5 -> PerpsRiskTier.SNIPER
            recommendedLeverage <= 6.0 -> PerpsRiskTier.TACTICAL
            recommendedLeverage <= 12.0 -> PerpsRiskTier.ASSAULT
            else -> PerpsRiskTier.NUKE
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═══════════════════════════════════════════════════════════════════
        
        val baseSize = 5.0  // 5% of balance
        val sizeMultiplier = when {
            confidence >= 85 -> 2.0
            confidence >= 75 -> 1.5
            confidence >= 65 -> 1.2
            else -> 1.0
        }
        val recommendedSizePct = (baseSize * sizeMultiplier).coerceIn(2.0, MAX_POSITION_PCT_OF_BALANCE)
        
        // ═══════════════════════════════════════════════════════════════════
        // RISK PARAMETERS
        // ═══════════════════════════════════════════════════════════════════
        
        val takeProfitPct = riskTier.takeProfitPct
        val stopLossPct = riskTier.stopLossPct
        
        // ═══════════════════════════════════════════════════════════════════
        // FINAL SCORING
        // ═══════════════════════════════════════════════════════════════════
        
        // Learning bonus - more aggressive in paper mode during bootstrap
        if (isPaper && lifetimeTrades.get() < LEARNING_BOOTSTRAP_TRADES) {
            score += 10
            reasons.add("📚 Bootstrap mode - learning aggressively")
        }
        
        // Discipline bonus
        val discipline = disciplineScore.get()
        if (discipline >= 70) {
            confidence += 5
            reasons.add("🎯 High discipline score: $discipline")
        }
        
        val aiReasoning = buildString {
            append("${direction.emoji} ${direction.symbol} ${market.symbol} @ ${riskTier.emoji} ${riskTier.displayName}\n")
            append("Leverage: ${recommendedLeverage.fmt(1)}x | Size: ${recommendedSizePct.fmt(1)}%\n")
            append("TP: +${takeProfitPct.fmt(1)}% | SL: -${stopLossPct.fmt(1)}%")
        }
        
        return PerpsSignal(
            market = market,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            recommendedLeverage = recommendedLeverage,
            recommendedSizePct = recommendedSizePct,
            recommendedRiskTier = riskTier,
            takeProfitPct = takeProfitPct,
            stopLossPct = stopLossPct,
            reasons = reasons,
            aiReasoning = aiReasoning,
        )
    }
    
    private fun noTradeSignal(market: PerpsMarket, reason: String, reasons: MutableList<String>): PerpsSignal {
        reasons.add("🚫 $reason")
        return PerpsSignal(
            market = market,
            direction = PerpsDirection.LONG,
            score = 0,
            confidence = 0,
            recommendedLeverage = 0.0,
            recommendedSizePct = 0.0,
            recommendedRiskTier = PerpsRiskTier.SNIPER,
            takeProfitPct = 0.0,
            stopLossPct = 0.0,
            reasons = reasons,
            aiReasoning = "NO_TRADE: $reason",
        )
    }
    
    private fun isMarketOpen(market: PerpsMarket): Boolean {
        if (!market.isStock) return true  // Crypto is 24/7
        
        // Simplified check - would need proper market hours API
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        
        // Skip weekends
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            return false
        }
        
        // US market hours (9:30 AM - 4:00 PM ET, roughly 14:30-21:00 UTC)
        return hour in 14..21
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun hasPosition(market: PerpsMarket): Boolean = activePositions.values.any { it.market == market }
    
    fun getPosition(market: PerpsMarket): PerpsPosition? = activePositions.values.find { it.market == market }
    
    fun getActivePositions(): List<PerpsPosition> = activePositions.values.toList()
    
    fun getPositionCount(): Int = activePositions.size
    
    /**
     * Open a new position
     */
    fun openPosition(
        market: PerpsMarket,
        direction: PerpsDirection,
        entryPrice: Double,
        sizeSol: Double,
        leverage: Double,
        signal: PerpsSignal,
        isPaper: Boolean,
    ): PerpsPosition? {
        
        if (hasPosition(market)) {
            ErrorLogger.warn(TAG, "Already have position in ${market.symbol}")
            return null
        }
        
        val sizeUsd = sizeSol * entryPrice  // Simplified - would need SOL price
        val marginUsd = sizeUsd / leverage
        
        // Calculate liquidation price
        val liqDistance = (1.0 / leverage) * 0.9  // 90% of margin
        val liquidationPrice = when (direction) {
            PerpsDirection.LONG -> entryPrice * (1 - liqDistance)
            PerpsDirection.SHORT -> entryPrice * (1 + liqDistance)
        }
        
        // Calculate TP/SL prices
        val tpPrice = when (direction) {
            PerpsDirection.LONG -> entryPrice * (1 + signal.takeProfitPct / 100)
            PerpsDirection.SHORT -> entryPrice * (1 - signal.takeProfitPct / 100)
        }
        
        val slPrice = when (direction) {
            PerpsDirection.LONG -> entryPrice * (1 - signal.stopLossPct / 100)
            PerpsDirection.SHORT -> entryPrice * (1 + signal.stopLossPct / 100)
        }
        
        val position = PerpsPosition(
            id = "${market.symbol}_${System.currentTimeMillis()}",
            market = market,
            direction = direction,
            entryPrice = entryPrice,
            currentPrice = entryPrice,
            sizeSol = sizeSol,
            sizeUsd = sizeUsd,
            leverage = leverage,
            marginUsd = marginUsd,
            liquidationPrice = liquidationPrice,
            entryTime = System.currentTimeMillis(),
            isPaper = isPaper,
            riskTier = signal.recommendedRiskTier,
            takeProfitPrice = tpPrice,
            stopLossPrice = slPrice,
            trailingStopPct = signal.stopLossPct * 1.5,
            highestPrice = entryPrice,
            lowestPrice = entryPrice,
            entryScore = signal.score,
            entryConfidence = signal.confidence,
            aiLeverage = leverage,
            aiReasoning = signal.aiReasoning,
        )
        
        // Store position
        activePositions[position.id] = position
        if (isPaper) paperPositions[position.id] else livePositions[position.id]
        
        // Update stats
        dailyTrades.incrementAndGet()
        
        // Deduct margin from balance
        val balanceRef = if (isPaper) paperBalanceBps else liveBalanceBps
        val marginBps = (sizeSol * 10000).toLong()
        balanceRef.addAndGet(-marginBps)
        
        // V5.7.3: Collect 1% trading fee for perps (split 50/50 between two wallets)
        if (!isPaper) {
            try {
                val feeAmountSol = sizeSol * PERPS_TRADING_FEE_PERCENT
                if (feeAmountSol >= 0.0001) {
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val wallet = com.lifecyclebot.engine.WalletManager.getWallet()
                            if (wallet != null) {
                                val feeWallet1 = feeAmountSol * 0.5
                                val feeWallet2 = feeAmountSol * 0.5
                                
                                if (feeWallet1 >= 0.0001) {
                                    wallet.sendSol(PERPS_FEE_WALLET_1, feeWallet1)
                                }
                                if (feeWallet2 >= 0.0001) {
                                    wallet.sendSol(PERPS_FEE_WALLET_2, feeWallet2)
                                }
                                
                                ErrorLogger.info(TAG, "💸 PERPS FEE: ${feeAmountSol.fmt(6)} SOL (1%) split to both wallets")
                            }
                        } catch (e: Exception) {
                            ErrorLogger.warn(TAG, "💸 PERPS FEE failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Fee collection error: ${e.message}")
            }
        }
        
        ErrorLogger.info(TAG, "📊 ${direction.emoji} POSITION OPENED: ${market.emoji} ${market.symbol} | " +
            "${signal.recommendedRiskTier.emoji} ${leverage.fmt(1)}x | " +
            "size=${sizeSol.fmt(3)}◎ | entry=\$${entryPrice.fmt(2)} | " +
            "TP=\$${tpPrice.fmt(2)} SL=\$${slPrice.fmt(2)}")
        
        save()
        return position
    }
    
    /**
     * Close a position
     */
    fun closePosition(positionId: String, exitPrice: Double, exitReason: PerpsExitSignal): PerpsTrade? {
        val position = activePositions.remove(positionId) ?: return null
        
        if (position.isPaper) paperPositions.remove(positionId) else livePositions.remove(positionId)
        
        // Calculate P&L
        val pnlPct = position.getUnrealizedPnlPct()
        val pnlUsd = position.getUnrealizedPnlUsd()
        val pnlSol = position.sizeSol * (pnlPct / 100)
        
        val isWin = pnlPct > 0
        
        // Update daily stats
        if (isWin) dailyWins.incrementAndGet() else dailyLosses.incrementAndGet()
        dailyPnlBps.addAndGet((pnlSol * 10000).toLong())
        
        // Update lifetime stats
        lifetimeTrades.incrementAndGet()
        if (isWin) lifetimeWins.incrementAndGet() else lifetimeLosses.incrementAndGet()
        lifetimePnlBps.addAndGet((pnlSol * 10000).toLong())
        
        // Track best/worst
        val pnlBps = (pnlSol * 10000).toLong()
        if (pnlBps > lifetimeBestPnlBps.get()) lifetimeBestPnlBps.set(pnlBps)
        if (pnlBps < lifetimeWorstPnlBps.get()) lifetimeWorstPnlBps.set(pnlBps)
        
        // Update streaks
        if (isWin) {
            val streak = currentStreak.incrementAndGet()
            if (streak > 0 && streak > maxWinStreak.get()) maxWinStreak.set(streak)
            consecutiveLosses.set(0)
        } else {
            val streak = currentStreak.decrementAndGet()
            if (streak < 0 && abs(streak) > maxLossStreak.get()) maxLossStreak.set(abs(streak))
            consecutiveLosses.incrementAndGet()
        }
        
        // Update balance - return margin + P&L
        val balanceRef = if (position.isPaper) paperBalanceBps else liveBalanceBps
        val marginBps = (position.sizeSol * 10000).toLong()
        balanceRef.addAndGet(marginBps + pnlBps)
        
        // V5.7.3: Collect 1% trading fee on close for live trades (split 50/50)
        if (!position.isPaper) {
            try {
                val feeAmountSol = position.sizeSol * PERPS_TRADING_FEE_PERCENT
                if (feeAmountSol >= 0.0001) {
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val wallet = com.lifecyclebot.engine.WalletManager.getWallet()
                            if (wallet != null) {
                                val feeWallet1 = feeAmountSol * 0.5
                                val feeWallet2 = feeAmountSol * 0.5
                                
                                if (feeWallet1 >= 0.0001) {
                                    wallet.sendSol(PERPS_FEE_WALLET_1, feeWallet1)
                                }
                                if (feeWallet2 >= 0.0001) {
                                    wallet.sendSol(PERPS_FEE_WALLET_2, feeWallet2)
                                }
                                
                                ErrorLogger.info(TAG, "💸 PERPS CLOSE FEE: ${feeAmountSol.fmt(6)} SOL (1%) split to both wallets")
                            }
                        } catch (e: Exception) {
                            ErrorLogger.warn(TAG, "💸 PERPS CLOSE FEE failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Fee collection error: ${e.message}")
            }
        }
        
        // Update learning
        updateLearning(pnlPct, isWin, position.isPaper)
        
        // Create trade record
        val trade = PerpsTrade(
            id = "${position.id}_close",
            market = position.market,
            direction = position.direction,
            side = "CLOSE",
            entryPrice = position.entryPrice,
            exitPrice = exitPrice,
            sizeSol = position.sizeSol,
            leverage = position.leverage,
            pnlUsd = pnlUsd,
            pnlPct = pnlPct,
            openTime = position.entryTime,
            closeTime = System.currentTimeMillis(),
            closeReason = exitReason.displayName,
            isPaper = position.isPaper,
            aiScore = position.entryScore,
            aiConfidence = position.entryConfidence,
            riskTier = position.riskTier,
        )
        
        // Add to recent trades
        synchronized(recentTrades) {
            recentTrades.add(0, trade)
            while (recentTrades.size > MAX_RECENT_TRADES) {
                recentTrades.removeAt(recentTrades.lastIndex)
            }
        }
        
        val emoji = when {
            pnlPct >= 50 -> "🚀"
            pnlPct >= 20 -> "🎯"
            pnlPct > 0 -> "✅"
            pnlPct > -10 -> "😐"
            else -> "💀"
        }
        
        ErrorLogger.info(TAG, "$emoji POSITION CLOSED [${if (position.isPaper) "PAPER" else "LIVE"}]: " +
            "${position.market.emoji} ${position.market.symbol} | " +
            "P&L: ${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)}◎ (${if (pnlPct >= 0) "+" else ""}${pnlPct.fmt(1)}%) | " +
            "reason=${exitReason.displayName}")
        
        save()
        return trade
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT CHECKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(positionId: String, currentPrice: Double): PerpsExitSignal {
        val position = activePositions[positionId] ?: return PerpsExitSignal.HOLD
        
        // Update current price
        position.currentPrice = currentPrice
        position.lastUpdateTime = System.currentTimeMillis()
        
        val pnlPct = position.getUnrealizedPnlPct()
        val holdMinutes = position.getHoldDurationMinutes()
        
        // Update high/low water marks
        when (position.direction) {
            PerpsDirection.LONG -> {
                if (currentPrice > position.highestPrice) {
                    position.highestPrice = currentPrice
                }
            }
            PerpsDirection.SHORT -> {
                if (currentPrice < position.lowestPrice) {
                    position.lowestPrice = currentPrice
                }
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // EXIT CONDITIONS
        // ═══════════════════════════════════════════════════════════════════
        
        // 1. LIQUIDATION RISK - Emergency exit
        if (position.isNearLiquidation(10.0)) {
            ErrorLogger.warn(TAG, "💀 LIQUIDATION RISK: ${position.market.symbol} | dist=${position.getDistanceToLiquidation().fmt(1)}%")
            return PerpsExitSignal.LIQUIDATION_RISK
        }
        
        // 2. STOP LOSS
        if (position.shouldStopLoss()) {
            return PerpsExitSignal.STOP_LOSS
        }
        
        // 3. TAKE PROFIT
        if (position.shouldTakeProfit()) {
            return PerpsExitSignal.TAKE_PROFIT
        }
        
        // 4. TRAILING STOP - Dynamic trailing for profits
        if (pnlPct > 10.0) {
            val trailPct = position.trailingStopPct ?: 10.0
            val trailFromPeak = when (position.direction) {
                PerpsDirection.LONG -> (position.highestPrice - currentPrice) / position.highestPrice * 100
                PerpsDirection.SHORT -> (currentPrice - position.lowestPrice) / position.lowestPrice * 100
            }
            
            if (trailFromPeak >= trailPct) {
                ErrorLogger.info(TAG, "📉 TRAILING STOP: ${position.market.symbol} | trail=${trailFromPeak.fmt(1)}%")
                return PerpsExitSignal.TRAILING_STOP
            }
        }
        
        // 5. STOCK MARKET CLOSE
        if (position.market.isStock && !isMarketOpen(position.market)) {
            if (holdMinutes > 5) {  // Give 5 min buffer
                return PerpsExitSignal.MARKET_CLOSE
            }
        }
        
        // 6. TIMEOUT - Long holds in paper mode for learning
        val maxHoldHours = if (position.isPaper) 24 else 12
        if (holdMinutes >= maxHoldHours * 60 && abs(pnlPct) < 5.0) {
            return PerpsExitSignal.TIMEOUT
        }
        
        return PerpsExitSignal.HOLD
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun updateLearning(pnlPct: Double, isWin: Boolean, isPaper: Boolean) {
        // Learning progress
        val increment = when {
            pnlPct >= 50 -> 3   // Big win = big learning
            pnlPct >= 20 -> 2
            isWin -> 1
            pnlPct > -20 -> 1  // Small loss = learning opportunity
            else -> 2          // Big loss = important lesson
        }
        
        val newProgress = (learningProgress.get() + increment).coerceIn(0, 100)
        learningProgress.set(newProgress)
        
        // Discipline score update
        val disciplineChange = when {
            isWin && pnlPct <= 30 -> 1      // Disciplined win (didn't let it run wild)
            isWin && pnlPct > 30 -> 2       // Let winners run
            !isWin && abs(pnlPct) <= 15 -> 1  // Disciplined loss (stopped out properly)
            !isWin -> -1                      // Undisciplined loss
            else -> 0
        }
        
        val newDiscipline = (disciplineScore.get() + disciplineChange).coerceIn(0, 100)
        disciplineScore.set(newDiscipline)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE READINESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getLiveReadiness(): PerpsLiveReadiness {
        val trades = lifetimeTrades.get()
        val wins = lifetimeWins.get()
        val losses = lifetimeLosses.get()
        
        val winRate = if (trades > 0) (wins.toDouble() / trades * 100) else 0.0
        val pnlPct = if (paperBalanceBps.get() > 0) {
            ((paperBalanceBps.get() - 500_0000).toDouble() / 500_0000 * 100)
        } else 0.0
        
        // Calculate average leverage from recent trades
        val avgLeverage = synchronized(recentTrades) {
            if (recentTrades.isEmpty()) 0.0
            else recentTrades.take(20).map { it.leverage }.average()
        }
        
        // Max drawdown estimation
        val maxDrawdown = abs(lifetimeWorstPnlBps.get() / 10000.0 / paperBalanceBps.get() * 10000.0 * 100)
            .coerceIn(0.0, 100.0)
        
        val consecutiveLoss = consecutiveLosses.get()
        val discipline = disciplineScore.get()
        
        // Calculate readiness score
        var readiness = 0
        
        // Trade count (max 25 points)
        readiness += when {
            trades >= MIN_PAPER_TRADES_FOR_LIVE -> 25
            trades >= 30 -> 20
            trades >= 20 -> 15
            trades >= 10 -> 10
            else -> 5
        }
        
        // Win rate (max 30 points)
        readiness += when {
            winRate >= 55.0 -> 30
            winRate >= 50.0 -> 25
            winRate >= 45.0 -> 20
            winRate >= 40.0 -> 15
            else -> 5
        }
        
        // Discipline (max 20 points)
        readiness += (discipline * 0.2).toInt()
        
        // Drawdown control (max 15 points)
        readiness += when {
            maxDrawdown <= 10.0 -> 15
            maxDrawdown <= 20.0 -> 12
            maxDrawdown <= 30.0 -> 8
            else -> 3
        }
        
        // Streak stability (max 10 points)
        readiness += when {
            consecutiveLoss == 0 -> 10
            consecutiveLoss <= 2 -> 7
            consecutiveLoss <= 4 -> 4
            else -> 0
        }
        
        // Determine phase
        val phase = when {
            readiness >= 75 && trades >= MIN_PAPER_TRADES_FOR_LIVE && winRate >= MIN_WIN_RATE_FOR_LIVE -> ReadinessPhase.READY
            consecutiveLoss >= 5 || maxDrawdown > 40.0 -> ReadinessPhase.CAUTION
            trades >= 20 -> ReadinessPhase.PRACTICING
            else -> ReadinessPhase.LEARNING
        }
        
        val recommendation = when (phase) {
            ReadinessPhase.READY -> "✅ Ready for live trading! Start with reduced position sizes."
            ReadinessPhase.CAUTION -> "⚠️ Recent losses detected. Continue paper trading."
            ReadinessPhase.PRACTICING -> "🏋️ Good progress! Need ${MIN_PAPER_TRADES_FOR_LIVE - trades} more trades."
            ReadinessPhase.LEARNING -> "📚 Keep learning. ${MIN_PAPER_TRADES_FOR_LIVE - trades} trades to go."
        }
        
        return PerpsLiveReadiness(
            paperTrades = trades,
            paperWinRate = winRate,
            paperPnlPct = pnlPct,
            averageLeverage = avgLeverage,
            maxDrawdownPct = maxDrawdown,
            consecutiveLosses = consecutiveLoss,
            disciplineScore = discipline,
            readinessScore = readiness.coerceIn(0, 100),
            phase = phase,
            recommendation = recommendation,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS GETTERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getWinRatePct(): Int {
        val total = dailyWins.get() + dailyLosses.get()
        return if (total > 0) ((dailyWins.get().toDouble() / total) * 100).toInt() else 0
    }
    
    fun getLifetimeWinRatePct(): Int {
        val total = lifetimeWins.get() + lifetimeLosses.get()
        return if (total > 0) ((lifetimeWins.get().toDouble() / total) * 100).toInt() else 0
    }
    
    fun getDailyPnlSol(): Double = dailyPnlBps.get() / 10000.0
    fun getDailyPnlPct(): Double {
        val balance = if (isPaperMode) paperBalanceBps.get() else liveBalanceBps.get()
        return if (balance > 0) (dailyPnlBps.get().toDouble() / balance * 100) else 0.0
    }
    
    fun getDailyTrades(): Int = dailyTrades.get()
    fun getDailyWins(): Int = dailyWins.get()
    fun getDailyLosses(): Int = dailyLosses.get()
    
    fun getLifetimeTrades(): Int = lifetimeTrades.get()
    fun getLifetimeWins(): Int = lifetimeWins.get()
    fun getLifetimeLosses(): Int = lifetimeLosses.get()
    fun getLifetimePnlSol(): Double = lifetimePnlBps.get() / 10000.0
    
    fun getLearningProgress(): Int = learningProgress.get()
    fun getDisciplineScore(): Int = disciplineScore.get()
    
    fun getBalance(isPaper: Boolean): Double {
        return (if (isPaper) paperBalanceBps.get() else liveBalanceBps.get()) / 10000.0
    }
    
    fun getCurrentStreak(): Int = currentStreak.get()
    fun getMaxWinStreak(): Int = maxWinStreak.get()
    fun getMaxLossStreak(): Int = maxLossStreak.get()
    
    fun getRecentTrades(): List<PerpsTrade> = synchronized(recentTrades) { recentTrades.toList() }
    
    /**
     * Get state for UI display
     */
    fun getState(): PerpsState {
        return PerpsState(
            isPaperMode = isPaperMode,
            isEnabled = isEnabled.get(),
            hasAcknowledgedRisk = hasAcknowledgedRisk.get(),
            paperBalanceSol = paperBalanceBps.get() / 10000.0,
            liveBalanceSol = liveBalanceBps.get() / 10000.0,
            dailyTrades = dailyTrades.get(),
            dailyWins = dailyWins.get(),
            dailyLosses = dailyLosses.get(),
            dailyPnlSol = dailyPnlBps.get() / 10000.0,
            dailyPnlPct = getDailyPnlPct(),
            lifetimeTrades = lifetimeTrades.get(),
            lifetimeWins = lifetimeWins.get(),
            lifetimeLosses = lifetimeLosses.get(),
            lifetimePnlSol = lifetimePnlBps.get() / 10000.0,
            lifetimeBest = lifetimeBestPnlBps.get() / 10000.0,
            lifetimeWorst = if (lifetimeWorstPnlBps.get() == Long.MAX_VALUE) 0.0 else lifetimeWorstPnlBps.get() / 10000.0,
            learningProgress = learningProgress.get() / 100.0,
            maxConsecutiveWins = maxWinStreak.get(),
            maxConsecutiveLosses = maxLossStreak.get(),
            currentStreak = currentStreak.get(),
            aiConfidence = disciplineScore.get(),
            lastSignalTime = System.currentTimeMillis(),
        )
    }
    
    /**
     * Clear all positions (for shutdown)
     */
    fun clearAllPositions() {
        activePositions.clear()
        paperPositions.clear()
        livePositions.clear()
        ErrorLogger.info(TAG, "📊 PERPS: Cleared all positions")
    }
}
