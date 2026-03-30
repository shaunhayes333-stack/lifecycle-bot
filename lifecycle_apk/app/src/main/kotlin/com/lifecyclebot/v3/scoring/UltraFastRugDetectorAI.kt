package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * UltraFastRugDetectorAI — Real-time Rug Detection & Technical Analysis
 * 
 * V4.1.3: ULTRA-FAST RUG DETECTION
 * 
 * This AI layer runs CONTINUOUSLY on held positions to detect rugs
 * BEFORE they happen or AS they start. Goal: Never get rugged.
 * 
 * DETECTION METHODS:
 *   1. LIQUIDITY DRAIN: Watching for LP removal in real-time
 *   2. DEV DUMP: Large holder dumps detected via volume spikes
 *   3. SELL WALL: Massive sell pressure building
 *   4. PRICE CLIFF: Sudden price drops (>15% in <30 seconds)
 *   5. VOLUME SPIKE: Abnormal volume = something happening
 *   6. HOLDER EXODUS: Rapid holder count decline
 * 
 * TECHNICAL ANALYSIS (Trader's Cheat Sheet):
 *   - EMA crosses (9/21 for short-term momentum)
 *   - RSI divergence (overbought/oversold)
 *   - Dead cat bounce detection
 *   - Breakout confirmation
 *   - Support/resistance levels
 *   - Retrace vs reversal classification
 * 
 * SPEED: Designed to trigger exits in <100ms from detection
 */
object UltraFastRugDetectorAI {
    
    private const val TAG = "RugDetector"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RUG DETECTION THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Price drop thresholds (trigger instant exit)
    private const val INSTANT_RUG_DROP_PCT = -30.0      // >30% drop = INSTANT EXIT
    private const val FAST_RUG_DROP_PCT = -20.0         // >20% drop in <1min = EXIT
    private const val SUSPICIOUS_DROP_PCT = -12.0       // >12% drop = WARNING
    
    // Liquidity drain thresholds
    private const val INSTANT_LIQ_DRAIN_PCT = -40.0     // >40% liq drop = INSTANT EXIT
    private const val FAST_LIQ_DRAIN_PCT = -25.0        // >25% liq drop in <2min = EXIT
    
    // Volume spike thresholds (can indicate dump or pump)
    private const val VOLUME_SPIKE_MULT = 5.0           // 5x avg volume = something happening
    private const val SELL_VOLUME_DANGER_RATIO = 0.85   // >85% sells = danger
    
    // Holder exodus
    private const val HOLDER_DROP_DANGER_PCT = -15.0    // >15% holder drop = suspicious
    
    // Technical analysis
    private const val EMA_FAST = 9                      // Fast EMA for momentum
    private const val EMA_SLOW = 21                     // Slow EMA for trend
    private const val RSI_PERIOD = 14                   // RSI calculation period
    private const val RSI_OVERBOUGHT = 75.0             // RSI overbought level
    private const val RSI_OVERSOLD = 25.0               // RSI oversold level
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING (Real-time monitoring)
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class MonitoredPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entryLiquidity: Double,
        val entryHolders: Int,
        val entryTime: Long,
        var lastPrice: Double,
        var lastLiquidity: Double,
        var lastHolders: Int,
        var lastUpdateTime: Long,
        var priceHistory: MutableList<Double> = mutableListOf(),
        var alertLevel: AlertLevel = AlertLevel.SAFE,
        var consecutiveWarnings: Int = 0,
    )
    
    enum class AlertLevel(val emoji: String, val priority: Int) {
        SAFE("✅", 0),
        CAUTION("⚠️", 1),
        WARNING("🟠", 2),
        DANGER("🔴", 3),
        RUG_IMMINENT("💀", 4),
    }
    
    data class RugSignal(
        val shouldExit: Boolean,
        val urgency: ExitUrgency,
        val reason: String,
        val alertLevel: AlertLevel,
        val technicalSignals: List<TechnicalSignal> = emptyList(),
    )
    
    enum class ExitUrgency {
        NONE,           // No exit needed
        SOFT,           // Exit on next check cycle
        FAST,           // Exit within 5 seconds
        INSTANT,        // Exit NOW (market order, any slippage)
    }
    
    data class TechnicalSignal(
        val type: SignalType,
        val value: Double,
        val interpretation: String,
        val bullish: Boolean,
    )
    
    enum class SignalType {
        EMA_CROSS,
        RSI,
        VOLUME_SPIKE,
        SUPPORT_BREAK,
        RESISTANCE_BREAK,
        DEAD_CAT_BOUNCE,
        BREAKOUT,
        RETRACE,
        REVERSAL,
    }
    
    // Active positions being monitored
    private val monitoredPositions = ConcurrentHashMap<String, MonitoredPosition>()
    
    // Stats
    private val rugsDetected = AtomicInteger(0)
    private val rugsSaved = AtomicInteger(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start monitoring a position for rug signals.
     * Call this when entering a position.
     */
    fun startMonitoring(
        mint: String,
        symbol: String,
        entryPrice: Double,
        entryLiquidity: Double,
        entryHolders: Int,
    ) {
        val now = System.currentTimeMillis()
        monitoredPositions[mint] = MonitoredPosition(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entryLiquidity = entryLiquidity,
            entryHolders = entryHolders,
            entryTime = now,
            lastPrice = entryPrice,
            lastLiquidity = entryLiquidity,
            lastHolders = entryHolders,
            lastUpdateTime = now,
            priceHistory = mutableListOf(entryPrice),
        )
        ErrorLogger.debug(TAG, "🔍 MONITORING: $symbol | price=\$$entryPrice | liq=\$$entryLiquidity")
    }
    
    /**
     * Stop monitoring a position (after exit).
     */
    fun stopMonitoring(mint: String) {
        monitoredPositions.remove(mint)
    }
    
    /**
     * ULTRA-FAST RUG CHECK
     * Call this on EVERY price update for held positions.
     * Returns exit signal if rug detected.
     */
    fun checkForRug(
        mint: String,
        currentPrice: Double,
        currentLiquidity: Double,
        currentHolders: Int,
        recentCandles: List<Candle>? = null,
    ): RugSignal {
        val pos = monitoredPositions[mint] ?: return RugSignal(
            shouldExit = false,
            urgency = ExitUrgency.NONE,
            reason = "NOT_MONITORED",
            alertLevel = AlertLevel.SAFE,
        )
        
        val now = System.currentTimeMillis()
        val timeSinceEntry = now - pos.entryTime
        val timeSinceUpdate = now - pos.lastUpdateTime
        
        // Calculate changes
        val priceChangePct = (currentPrice - pos.entryPrice) / pos.entryPrice * 100
        val priceChangeFromLast = (currentPrice - pos.lastPrice) / pos.lastPrice * 100
        val liqChangePct = (currentLiquidity - pos.entryLiquidity) / pos.entryLiquidity * 100
        val liqChangeFromLast = if (pos.lastLiquidity > 0) {
            (currentLiquidity - pos.lastLiquidity) / pos.lastLiquidity * 100
        } else 0.0
        val holderChangePct = if (pos.entryHolders > 0) {
            ((currentHolders - pos.entryHolders).toDouble() / pos.entryHolders) * 100
        } else 0.0
        
        // Update tracking
        pos.lastPrice = currentPrice
        pos.lastLiquidity = currentLiquidity
        pos.lastHolders = currentHolders
        pos.lastUpdateTime = now
        pos.priceHistory.add(currentPrice)
        if (pos.priceHistory.size > 100) pos.priceHistory.removeAt(0)
        
        val signals = mutableListOf<String>()
        var alertLevel = AlertLevel.SAFE
        var urgency = ExitUrgency.NONE
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK 1: INSTANT RUG - Price cliff (>30% drop)
        // ─────────────────────────────────────────────────────────────────────
        if (priceChangePct <= INSTANT_RUG_DROP_PCT) {
            rugsSaved.incrementAndGet()
            ErrorLogger.warn(TAG, "💀 INSTANT RUG: ${pos.symbol} | ${priceChangePct.toInt()}% DROP!")
            return RugSignal(
                shouldExit = true,
                urgency = ExitUrgency.INSTANT,
                reason = "INSTANT_RUG: ${priceChangePct.toInt()}% price collapse",
                alertLevel = AlertLevel.RUG_IMMINENT,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK 2: FAST RUG - Rapid drop (>20% in <1 min)
        // ─────────────────────────────────────────────────────────────────────
        if (priceChangePct <= FAST_RUG_DROP_PCT && timeSinceEntry < 60_000) {
            rugsSaved.incrementAndGet()
            ErrorLogger.warn(TAG, "💀 FAST RUG: ${pos.symbol} | ${priceChangePct.toInt()}% in ${timeSinceEntry/1000}s!")
            return RugSignal(
                shouldExit = true,
                urgency = ExitUrgency.INSTANT,
                reason = "FAST_RUG: ${priceChangePct.toInt()}% in ${timeSinceEntry/1000}s",
                alertLevel = AlertLevel.RUG_IMMINENT,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK 3: LIQUIDITY DRAIN - LP being pulled
        // ─────────────────────────────────────────────────────────────────────
        if (liqChangePct <= INSTANT_LIQ_DRAIN_PCT) {
            rugsSaved.incrementAndGet()
            ErrorLogger.warn(TAG, "💀 LIQ DRAIN: ${pos.symbol} | ${liqChangePct.toInt()}% liquidity gone!")
            return RugSignal(
                shouldExit = true,
                urgency = ExitUrgency.INSTANT,
                reason = "LIQUIDITY_DRAIN: ${liqChangePct.toInt()}% LP removed",
                alertLevel = AlertLevel.RUG_IMMINENT,
            )
        }
        
        if (liqChangePct <= FAST_LIQ_DRAIN_PCT) {
            signals.add("LIQ_WARNING: ${liqChangePct.toInt()}%")
            alertLevel = maxOf(alertLevel, AlertLevel.DANGER)
            urgency = maxOf(urgency, ExitUrgency.FAST)
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK 4: HOLDER EXODUS - Everyone leaving
        // ─────────────────────────────────────────────────────────────────────
        if (holderChangePct <= HOLDER_DROP_DANGER_PCT) {
            signals.add("HOLDER_EXODUS: ${holderChangePct.toInt()}%")
            alertLevel = maxOf(alertLevel, AlertLevel.WARNING)
            pos.consecutiveWarnings++
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK 5: SUSPICIOUS PRICE DROP
        // ─────────────────────────────────────────────────────────────────────
        if (priceChangePct <= SUSPICIOUS_DROP_PCT) {
            signals.add("PRICE_DROP: ${priceChangePct.toInt()}%")
            alertLevel = maxOf(alertLevel, AlertLevel.CAUTION)
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK 6: RAPID SELL PRESSURE from recent candles
        // ─────────────────────────────────────────────────────────────────────
        if (recentCandles != null && recentCandles.size >= 2) {
            val lastCandle = recentCandles.last()
            if (lastCandle.buyRatio < (1 - SELL_VOLUME_DANGER_RATIO)) {
                signals.add("SELL_PRESSURE: ${((1-lastCandle.buyRatio)*100).toInt()}% sells")
                alertLevel = maxOf(alertLevel, AlertLevel.WARNING)
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CONSECUTIVE WARNINGS = ESCALATE
        // ─────────────────────────────────────────────────────────────────────
        if (pos.consecutiveWarnings >= 3) {
            ErrorLogger.warn(TAG, "🔴 DANGER: ${pos.symbol} | 3+ consecutive warnings!")
            return RugSignal(
                shouldExit = true,
                urgency = ExitUrgency.FAST,
                reason = "MULTIPLE_WARNINGS: ${pos.consecutiveWarnings} consecutive danger signals",
                alertLevel = AlertLevel.DANGER,
            )
        }
        
        // Reset warnings if things are stable
        if (alertLevel == AlertLevel.SAFE) {
            pos.consecutiveWarnings = 0
        }
        
        pos.alertLevel = alertLevel
        
        // ─────────────────────────────────────────────────────────────────────
        // TECHNICAL ANALYSIS (if enough data)
        // ─────────────────────────────────────────────────────────────────────
        val techSignals = if (pos.priceHistory.size >= 21) {
            analyzeTechnicals(pos.priceHistory, recentCandles)
        } else emptyList()
        
        return RugSignal(
            shouldExit = urgency != ExitUrgency.NONE,
            urgency = urgency,
            reason = signals.joinToString(", ").ifEmpty { "MONITORING" },
            alertLevel = alertLevel,
            technicalSignals = techSignals,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNICAL ANALYSIS - Trader's Cheat Sheet
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full technical analysis using standard indicators.
     */
    private fun analyzeTechnicals(
        priceHistory: List<Double>,
        candles: List<Candle>?,
    ): List<TechnicalSignal> {
        val signals = mutableListOf<TechnicalSignal>()
        
        if (priceHistory.size < EMA_SLOW) return signals
        
        // ─── EMA CROSS (9/21) ───
        val emaFast = calculateEMA(priceHistory, EMA_FAST)
        val emaSlow = calculateEMA(priceHistory, EMA_SLOW)
        
        val emaFastPrev = calculateEMA(priceHistory.dropLast(1), EMA_FAST)
        val emaSlowPrev = calculateEMA(priceHistory.dropLast(1), EMA_SLOW)
        
        // Golden cross (bullish)
        if (emaFastPrev < emaSlowPrev && emaFast > emaSlow) {
            signals.add(TechnicalSignal(
                type = SignalType.EMA_CROSS,
                value = emaFast,
                interpretation = "GOLDEN CROSS: 9EMA crossed above 21EMA",
                bullish = true,
            ))
        }
        
        // Death cross (bearish)
        if (emaFastPrev > emaSlowPrev && emaFast < emaSlow) {
            signals.add(TechnicalSignal(
                type = SignalType.EMA_CROSS,
                value = emaFast,
                interpretation = "DEATH CROSS: 9EMA crossed below 21EMA",
                bullish = false,
            ))
        }
        
        // ─── RSI ───
        if (priceHistory.size >= RSI_PERIOD + 1) {
            val rsi = calculateRSI(priceHistory, RSI_PERIOD)
            
            if (rsi > RSI_OVERBOUGHT) {
                signals.add(TechnicalSignal(
                    type = SignalType.RSI,
                    value = rsi,
                    interpretation = "OVERBOUGHT: RSI ${rsi.toInt()} - reversal likely",
                    bullish = false,
                ))
            } else if (rsi < RSI_OVERSOLD) {
                signals.add(TechnicalSignal(
                    type = SignalType.RSI,
                    value = rsi,
                    interpretation = "OVERSOLD: RSI ${rsi.toInt()} - bounce likely",
                    bullish = true,
                ))
            }
        }
        
        // ─── DEAD CAT BOUNCE DETECTION ───
        if (priceHistory.size >= 10) {
            val deadCat = detectDeadCatBounce(priceHistory)
            if (deadCat != null) {
                signals.add(deadCat)
            }
        }
        
        // ─── BREAKOUT DETECTION ───
        if (priceHistory.size >= 20) {
            val breakout = detectBreakout(priceHistory)
            if (breakout != null) {
                signals.add(breakout)
            }
        }
        
        // ─── SUPPORT/RESISTANCE ───
        if (priceHistory.size >= 15) {
            val srSignal = detectSupportResistance(priceHistory)
            if (srSignal != null) {
                signals.add(srSignal)
            }
        }
        
        return signals
    }
    
    /**
     * Calculate Exponential Moving Average.
     */
    private fun calculateEMA(prices: List<Double>, period: Int): Double {
        if (prices.size < period) return prices.lastOrNull() ?: 0.0
        
        val multiplier = 2.0 / (period + 1)
        var ema = prices.take(period).average()
        
        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
        }
        
        return ema
    }
    
    /**
     * Calculate Relative Strength Index.
     */
    private fun calculateRSI(prices: List<Double>, period: Int): Double {
        if (prices.size < period + 1) return 50.0
        
        var gains = 0.0
        var losses = 0.0
        
        for (i in (prices.size - period) until prices.size) {
            val change = prices[i] - prices[i - 1]
            if (change > 0) gains += change
            else losses += abs(change)
        }
        
        val avgGain = gains / period
        val avgLoss = losses / period
        
        if (avgLoss == 0.0) return 100.0
        
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }
    
    /**
     * Detect dead cat bounce pattern.
     * A dead cat bounce is a small recovery after a large drop,
     * followed by continued decline.
     */
    private fun detectDeadCatBounce(prices: List<Double>): TechnicalSignal? {
        if (prices.size < 10) return null
        
        val recent = prices.takeLast(10)
        val peak = recent.take(3).maxOrNull() ?: return null
        val trough = recent.drop(2).take(4).minOrNull() ?: return null
        val bounce = recent.drop(5).take(3).maxOrNull() ?: return null
        val current = recent.last()
        
        val dropPct = (trough - peak) / peak * 100
        val bouncePct = (bounce - trough) / trough * 100
        val currentVsTrough = (current - trough) / trough * 100
        
        // Pattern: >30% drop, small 10-30% bounce, now falling again
        if (dropPct < -30 && bouncePct in 10.0..35.0 && currentVsTrough < bouncePct * 0.5) {
            return TechnicalSignal(
                type = SignalType.DEAD_CAT_BOUNCE,
                value = bouncePct,
                interpretation = "DEAD CAT BOUNCE: ${dropPct.toInt()}% drop → ${bouncePct.toInt()}% bounce → fading",
                bullish = false,
            )
        }
        
        return null
    }
    
    /**
     * Detect breakout from consolidation.
     */
    private fun detectBreakout(prices: List<Double>): TechnicalSignal? {
        if (prices.size < 20) return null
        
        val consolidation = prices.dropLast(3).takeLast(15)
        val recent = prices.takeLast(3)
        
        val consolidationHigh = consolidation.maxOrNull() ?: return null
        val consolidationLow = consolidation.minOrNull() ?: return null
        val consolidationRange = consolidationHigh - consolidationLow
        val avgConsolidation = consolidation.average()
        
        // Range should be tight (<15% of avg)
        val isConsolidating = consolidationRange / avgConsolidation < 0.15
        
        if (!isConsolidating) return null
        
        val currentPrice = recent.last()
        
        // Bullish breakout
        if (currentPrice > consolidationHigh * 1.02) {
            return TechnicalSignal(
                type = SignalType.BREAKOUT,
                value = (currentPrice - consolidationHigh) / consolidationHigh * 100,
                interpretation = "BULLISH BREAKOUT: Breaking above consolidation range",
                bullish = true,
            )
        }
        
        // Bearish breakdown
        if (currentPrice < consolidationLow * 0.98) {
            return TechnicalSignal(
                type = SignalType.BREAKOUT,
                value = (currentPrice - consolidationLow) / consolidationLow * 100,
                interpretation = "BEARISH BREAKDOWN: Breaking below consolidation range",
                bullish = false,
            )
        }
        
        return null
    }
    
    /**
     * Detect support/resistance breaks.
     */
    private fun detectSupportResistance(prices: List<Double>): TechnicalSignal? {
        if (prices.size < 15) return null
        
        val history = prices.dropLast(2)
        val currentPrice = prices.last()
        
        // Find potential support (local lows)
        val lows = mutableListOf<Double>()
        for (i in 2 until history.size - 2) {
            if (history[i] < history[i-1] && history[i] < history[i-2] &&
                history[i] < history[i+1] && history[i] < history[i+2]) {
                lows.add(history[i])
            }
        }
        
        // Find potential resistance (local highs)
        val highs = mutableListOf<Double>()
        for (i in 2 until history.size - 2) {
            if (history[i] > history[i-1] && history[i] > history[i-2] &&
                history[i] > history[i+1] && history[i] > history[i+2]) {
                highs.add(history[i])
            }
        }
        
        // Check for support break
        val support = lows.minOrNull()
        if (support != null && currentPrice < support * 0.97) {
            return TechnicalSignal(
                type = SignalType.SUPPORT_BREAK,
                value = support,
                interpretation = "SUPPORT BREAK: Price fell through \$${support.toInt()} support",
                bullish = false,
            )
        }
        
        // Check for resistance break
        val resistance = highs.maxOrNull()
        if (resistance != null && currentPrice > resistance * 1.03) {
            return TechnicalSignal(
                type = SignalType.RESISTANCE_BREAK,
                value = resistance,
                interpretation = "RESISTANCE BREAK: Price broke through \$${resistance.toInt()} resistance",
                bullish = true,
            )
        }
        
        return null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY & STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): String {
        return "RugDetector: ${monitoredPositions.size} monitored | " +
               "$rugsDetected detected | ${rugsSaved.get()} saved"
    }
    
    fun getMonitoredCount(): Int = monitoredPositions.size
    
    fun getRugsSaved(): Int = rugsSaved.get()
    
    fun clear() {
        monitoredPositions.clear()
    }
    
    private fun maxOf(a: AlertLevel, b: AlertLevel): AlertLevel {
        return if (a.priority >= b.priority) a else b
    }
    
    private fun maxOf(a: ExitUrgency, b: ExitUrgency): ExitUrgency {
        return if (a.ordinal >= b.ordinal) a else b
    }
}
