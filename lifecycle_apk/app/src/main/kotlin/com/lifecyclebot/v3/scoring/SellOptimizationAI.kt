package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SellOptimizationAI - Layer 24: Intelligent Exit Strategy
 * 
 * PROBLEM: "400% runs and nothing gained" - Bot watches massive gains evaporate
 * because it doesn't know when/how to take profits.
 * 
 * SOLUTION: Multi-strategy exit optimization that:
 *   1. Takes profits progressively (chunk selling)
 *   2. Detects momentum exhaustion before reversals
 *   3. Learns from historical trade outcomes
 *   4. Adapts exit strategy based on token characteristics
 *   5. Uses FLUID thresholds that tighten with learning
 * 
 * PHILOSOPHY:
 *   Bootstrap (0 trades): AGGRESSIVE profit taking - secure gains early
 *   Mature (500+ trades): Can let winners run when patterns support it
 * 
 * EXIT STRATEGIES:
 *   - CHUNK_SELL: Sell portions at profit milestones (25%, 50%, etc.)
 *   - TRAILING_LOCK: Lock in % of peak gains with trailing stops
 *   - MOMENTUM_EXIT: Exit when buy pressure/volume fades
 *   - WHALE_FOLLOW: Exit when whales start dumping
 *   - TIME_DECAY: Increasing urgency to exit as hold time grows
 *   - LEARNED_PATTERN: Exit based on similar historical outcomes
 */
object SellOptimizationAI {
    
    private const val TAG = "SellOptimizationAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Track outcomes by exit strategy to learn what works
    data class ExitOutcome(
        val strategy: ExitStrategy,
        val entryPnlPct: Double,      // P&L when exit was triggered
        val finalPnlPct: Double,      // What it would have been if held longer
        val wasOptimal: Boolean,      // Did we exit at right time?
        val tokenType: String,        // MEME, MIDCAP, etc.
        val holdTimeMinutes: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val exitHistory = ConcurrentHashMap<ExitStrategy, MutableList<ExitOutcome>>()
    private val strategyWinRates = ConcurrentHashMap<ExitStrategy, Double>()
    
    // Active position tracking for chunk selling
    data class PositionState(
        val mint: String,
        val entryPrice: Double,
        val entryTime: Long,
        val originalSizeSol: Double,
        var remainingSizeSol: Double,
        var peakPnlPct: Double = 0.0,
        var chunksSold: Int = 0,
        var lastChunkPnl: Double = 0.0,
        var lockedProfitSol: Double = 0.0,
    )
    
    private val activePositions = ConcurrentHashMap<String, PositionState>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT STRATEGIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class ExitStrategy(val label: String, val emoji: String) {
        HOLD("Hold", "⏳"),
        CHUNK_25("Chunk 25%", "📊"),
        CHUNK_50("Chunk 50%", "📊"),
        CHUNK_75("Chunk 75%", "📊"),
        FULL_EXIT("Full Exit", "🏁"),
        TRAILING_LOCK("Trailing Lock", "🔒"),
        MOMENTUM_EXIT("Momentum Exit", "📉"),
        WHALE_EXIT("Whale Exit", "🐋"),
        TIME_DECAY_EXIT("Time Decay", "⏰"),
        STOP_LOSS("Stop Loss", "🛑"),
        LEARNED_EXIT("Learned Pattern", "🧠"),
    }
    
    enum class ExitUrgency(val multiplier: Double) {
        NONE(0.0),
        LOW(0.25),
        MEDIUM(0.5),
        HIGH(0.75),
        CRITICAL(1.0),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Chunk sell thresholds (when to sell portions)
    // Bootstrap: Take profits early and often
    // Mature: Can let winners run more
    
    private fun getChunk1Threshold(): Double = FluidLearningAI.lerp(15.0, 50.0)   // 15% → 50%
    private fun getChunk2Threshold(): Double = FluidLearningAI.lerp(30.0, 100.0)  // 30% → 100%
    private fun getChunk3Threshold(): Double = FluidLearningAI.lerp(50.0, 200.0)  // 50% → 200%
    private fun getFullExitThreshold(): Double = FluidLearningAI.lerp(75.0, 300.0) // 75% → 300%
    
    // Chunk sizes (what % of position to sell at each level)
    private fun getChunk1Size(): Double = FluidLearningAI.lerp(0.30, 0.20)  // 30% → 20%
    private fun getChunk2Size(): Double = FluidLearningAI.lerp(0.30, 0.25)  // 30% → 25%
    private fun getChunk3Size(): Double = FluidLearningAI.lerp(0.25, 0.25)  // 25% → 25%
    // Remaining auto-exits at full threshold or stop
    
    // Trailing lock - how much of peak gains to protect
    private fun getTrailingLockPct(): Double = FluidLearningAI.lerp(0.50, 0.70)  // Lock 50% → 70% of peak
    
    // Momentum thresholds
    private fun getMomentumExitThreshold(): Double = FluidLearningAI.lerp(35.0, 25.0)  // Exit if buy% < 35% → 25%
    
    // Time decay - max hold before urgency increases
    private fun getMaxHoldMinutes(): Int = FluidLearningAI.lerp(15.0, 45.0).toInt()  // 15 → 45 min
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Register a new position for tracking.
     *
     * V5.9.137 — SAFE to call more than once for the same mint. If a stale
     * PositionState from a previously-closed trade is still hanging around
     * (the bot didn't call closePosition, or it re-entered the same mint
     * quickly), the old state WOULD cause ghost chunksSold / peakPnlPct
     * and break every exit decision. We now reset on each open.
     */
    fun registerPosition(mint: String, entryPrice: Double, sizeSol: Double) {
        activePositions[mint] = PositionState(
            mint = mint,
            entryPrice = entryPrice,
            entryTime = System.currentTimeMillis(),
            originalSizeSol = sizeSol,
            remainingSizeSol = sizeSol,
        )
        ErrorLogger.info(TAG, "📍 POSITION REGISTERED: $mint | size=${sizeSol}SOL | entry=$entryPrice")
    }
    
    /**
     * Update position after a chunk sell.
     */
    fun recordChunkSell(mint: String, soldSizeSol: Double, pnlPct: Double, profitSol: Double) {
        activePositions[mint]?.let { pos ->
            pos.remainingSizeSol -= soldSizeSol
            pos.chunksSold++
            pos.lastChunkPnl = pnlPct
            pos.lockedProfitSol += profitSol
            ErrorLogger.info(TAG, "📊 CHUNK SOLD: $mint | chunk #${pos.chunksSold} | " +
                "sold=${soldSizeSol}SOL @ ${pnlPct.toInt()}% | locked=${pos.lockedProfitSol}SOL")
        }
    }
    
    /**
     * Remove position after full exit.
     */
    fun closePosition(mint: String, finalPnlPct: Double) {
        activePositions.remove(mint)?.let { pos ->
            ErrorLogger.info(TAG, "🏁 POSITION CLOSED: $mint | " +
                "chunks=${pos.chunksSold} | locked=${pos.lockedProfitSol}SOL | final=${finalPnlPct.toInt()}%")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SellSignal(
        val strategy: ExitStrategy,
        val urgency: ExitUrgency,
        val sellPct: Double,           // What % of remaining position to sell (0-100)
        val reason: String,
        val confidence: Int,           // 0-100
        val signals: List<String>,     // Contributing factors
        val peakPnlPct: Double,
        val currentPnlPct: Double,
        val lockedProfitSol: Double,
        val suggestedStopLoss: Double? = null,  // Updated stop loss suggestion
    )
    
    /**
     * Evaluate a position for exit optimization.
     *
     * @param ts Current token state
     * @param currentPnlPct Current P&L percentage
     * @param holdTimeMinutes How long position has been held
     * @param entryPrice Original entry price
     * @param positionSizeSol V5.9.137 — REAL position cost in SOL. Used as a
     *        last-resort init value if registerPosition was never called for
     *        this mint (so chunk-sell sizing is not based on a fake 0.1◎).
     * @return SellSignal with recommended action
     */
    fun evaluate(
        ts: TokenState,
        currentPnlPct: Double,
        holdTimeMinutes: Int,
        entryPrice: Double,
        positionSizeSol: Double = 0.0,
    ): SellSignal {
        val mint = ts.mint
        val signals = mutableListOf<String>()
        var totalUrgency = 0.0
        var recommendedStrategy = ExitStrategy.HOLD
        var sellPct = 0.0

        // Get or create position state.
        // V5.9.137 — if registerPosition was skipped (or a prior trade rotted
        // in the map and was never closed) we used to drop in a dummy 0.1◎
        // PositionState. That meant pos.remainingSizeSol went NEGATIVE after
        // the first real chunk sell and the chunk-sell branch then got
        // permanently gated off ("remainingSizeSol > 0" = false). Every real
        // position silently turned into a HOLD-only monster. Now we use the
        // real size passed in from BotService, and when a stale entry exists
        // we compare entryTime vs holdTimeMinutes to detect a re-entry and
        // reset automatically.
        val incomingEntryTime = System.currentTimeMillis() - (holdTimeMinutes * 60_000L)
        val pos = activePositions.compute(mint) { _, existing ->
            val realSize = if (positionSizeSol > 0.0) positionSizeSol else 0.1
            if (existing == null) {
                PositionState(
                    mint = mint,
                    entryPrice = entryPrice,
                    entryTime = incomingEntryTime,
                    originalSizeSol = realSize,
                    remainingSizeSol = realSize,
                )
            } else {
                // Detect re-entry: bot's entryTime is newer than ours by > 90s
                // AND PnL reset (went from large peak back near zero).
                val looksLikeReentry =
                    (incomingEntryTime - existing.entryTime) > 90_000L &&
                    existing.peakPnlPct - currentPnlPct > 30.0
                if (looksLikeReentry) {
                    PositionState(
                        mint = mint,
                        entryPrice = entryPrice,
                        entryTime = incomingEntryTime,
                        originalSizeSol = realSize,
                        remainingSizeSol = realSize,
                    )
                } else {
                    // Heal silently: if remaining ever went non-positive due to
                    // the old 0.1◎ fallback bug, reset to true size without
                    // wiping peakPnlPct / chunksSold.
                    if (existing.remainingSizeSol <= 0.0 && realSize > 0.0) {
                        existing.copy(
                            originalSizeSol = realSize,
                            remainingSizeSol = (realSize * (1.0 - existing.chunksSold * 0.25))
                                .coerceAtLeast(realSize * 0.05)
                        )
                    } else existing
                }
            }
        }!!
        
        // Update peak P&L
        if (currentPnlPct > pos.peakPnlPct) {
            pos.peakPnlPct = currentPnlPct
        }
        
        val hist = ts.history.toList()
        val learningProgress = FluidLearningAI.getLearningProgress()
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 1: STOP LOSS CHECK (Always highest priority)
        // ─────────────────────────────────────────────────────────────────────
        val stopLossPct = FluidLearningAI.lerp(-8.0, -10.0)  // V5.2.11: Widened from -3%/-5% to -8%/-10%
        if (currentPnlPct <= stopLossPct) {
            signals.add("🛑 STOP LOSS: ${currentPnlPct.toInt()}% <= ${stopLossPct.toInt()}%")
            return SellSignal(
                strategy = ExitStrategy.STOP_LOSS,
                urgency = ExitUrgency.CRITICAL,
                sellPct = 100.0,
                reason = "Stop loss triggered at ${currentPnlPct.toInt()}%",
                confidence = 100,
                signals = signals,
                peakPnlPct = pos.peakPnlPct,
                currentPnlPct = currentPnlPct,
                lockedProfitSol = pos.lockedProfitSol,
            )
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 2: TRAILING LOCK (Protect gains from peak)
        // ─────────────────────────────────────────────────────────────────────
        if (pos.peakPnlPct > 20) {  // Only if we've seen decent gains
            val lockPct = getTrailingLockPct()
            val protectedLevel = pos.peakPnlPct * lockPct
            val drawdownFromPeak = pos.peakPnlPct - currentPnlPct
            val maxDrawdown = pos.peakPnlPct * (1 - lockPct)
            
            if (drawdownFromPeak > maxDrawdown && currentPnlPct > 0) {
                signals.add("🔒 TRAILING LOCK: peak=${pos.peakPnlPct.toInt()}% → now=${currentPnlPct.toInt()}% (lost ${drawdownFromPeak.toInt()}%)")
                totalUrgency += 0.8
                recommendedStrategy = ExitStrategy.TRAILING_LOCK
                sellPct = 100.0  // Exit fully to protect remaining gains
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 3: CHUNK SELLING (Progressive profit taking)
        // ─────────────────────────────────────────────────────────────────────
        if (currentPnlPct > 0 && pos.remainingSizeSol > 0) {
            val chunk1 = getChunk1Threshold()
            val chunk2 = getChunk2Threshold()
            val chunk3 = getChunk3Threshold()
            val fullExit = getFullExitThreshold()
            
            when {
                currentPnlPct >= fullExit && pos.chunksSold >= 2 -> {
                    signals.add("🏁 FULL EXIT: ${currentPnlPct.toInt()}% >= ${fullExit.toInt()}% (chunks=${pos.chunksSold})")
                    recommendedStrategy = ExitStrategy.FULL_EXIT
                    sellPct = 100.0
                    totalUrgency += 0.9
                }
                currentPnlPct >= chunk3 && pos.chunksSold < 3 -> {
                    signals.add("📊 CHUNK 3: ${currentPnlPct.toInt()}% >= ${chunk3.toInt()}%")
                    recommendedStrategy = ExitStrategy.CHUNK_75
                    sellPct = getChunk3Size() * 100
                    totalUrgency += 0.6
                }
                currentPnlPct >= chunk2 && pos.chunksSold < 2 -> {
                    signals.add("📊 CHUNK 2: ${currentPnlPct.toInt()}% >= ${chunk2.toInt()}%")
                    recommendedStrategy = ExitStrategy.CHUNK_50
                    sellPct = getChunk2Size() * 100
                    totalUrgency += 0.5
                }
                currentPnlPct >= chunk1 && pos.chunksSold < 1 -> {
                    signals.add("📊 CHUNK 1: ${currentPnlPct.toInt()}% >= ${chunk1.toInt()}%")
                    recommendedStrategy = ExitStrategy.CHUNK_25
                    sellPct = getChunk1Size() * 100
                    totalUrgency += 0.4
                }
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 4: MOMENTUM EXHAUSTION
        // Calculate buy pressure from recent candles (buysH1 vs total)
        // ─────────────────────────────────────────────────────────────────────
        val buyPressure = if (hist.size >= 3) {
            val recentCandles = hist.takeLast(3)
            val totalBuys = recentCandles.sumOf { it.buysH1 }
            val totalSells = recentCandles.sumOf { it.sellsH1 }
            if (totalBuys + totalSells > 0) {
                (totalBuys.toDouble() / (totalBuys + totalSells)) * 100
            } else 50.0
        } else 50.0
        
        val momentumThreshold = getMomentumExitThreshold()
        
        if (buyPressure < momentumThreshold && currentPnlPct > 5) {
            signals.add("📉 MOMENTUM FADE: buy%=${buyPressure.toInt()} < ${momentumThreshold.toInt()}%")
            totalUrgency += 0.5
            if (recommendedStrategy == ExitStrategy.HOLD) {
                recommendedStrategy = ExitStrategy.MOMENTUM_EXIT
                sellPct = 50.0  // Sell half on momentum fade
            }
        }
        
        // Volume dying check
        if (hist.size >= 10) {
            val recentVol = hist.takeLast(5).sumOf { it.volumeH1 } / 5.0
            val priorVol = hist.takeLast(10).take(5).sumOf { it.volumeH1 } / 5.0
            if (priorVol > 0 && recentVol < priorVol * 0.3 && currentPnlPct > 10) {
                signals.add("📉 VOLUME DYING: recent=${recentVol.toInt()} vs prior=${priorVol.toInt()}")
                totalUrgency += 0.4
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 5: WHALE DETECTION (Big sells happening)
        // Use sellsH1 vs buysH1 ratio as proxy for sell pressure
        // ─────────────────────────────────────────────────────────────────────
        if (hist.size >= 5) {
            val recentCandles = hist.takeLast(5)
            // Detect heavy selling: sells > 2x buys AND significant volume
            val bigSellCandles = recentCandles.count { candle ->
                candle.sellsH1 > candle.buysH1 * 2 && candle.volumeH1 > 5000 
            }
            if (bigSellCandles >= 2 && currentPnlPct > 0) {
                signals.add("🐋 WHALE SELLING: $bigSellCandles heavy sell candles in last 5")
                totalUrgency += 0.6
                if (recommendedStrategy == ExitStrategy.HOLD || recommendedStrategy == ExitStrategy.MOMENTUM_EXIT) {
                    recommendedStrategy = ExitStrategy.WHALE_EXIT
                    sellPct = kotlin.math.max(sellPct, 75.0)
                }
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 6: TIME DECAY (Holding too long increases risk)
        // ─────────────────────────────────────────────────────────────────────
        val maxHold = getMaxHoldMinutes()
        if (holdTimeMinutes > maxHold) {
            val overtimeRatio = (holdTimeMinutes - maxHold).toDouble() / maxHold
            val timeUrgency = (overtimeRatio * 0.5).coerceAtMost(0.5)
            signals.add("⏰ TIME DECAY: held ${holdTimeMinutes}min > ${maxHold}min max")
            totalUrgency += timeUrgency
            
            if (currentPnlPct > 5 && recommendedStrategy == ExitStrategy.HOLD) {
                recommendedStrategy = ExitStrategy.TIME_DECAY_EXIT
                sellPct = 50.0
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // SIGNAL 7: LEARNED PATTERN MATCHING
        // ─────────────────────────────────────────────────────────────────────
        val learnedSignal = checkLearnedPatterns(ts, currentPnlPct, holdTimeMinutes)
        if (learnedSignal != null) {
            signals.add("🧠 LEARNED: ${learnedSignal.first}")
            totalUrgency += learnedSignal.second
            if (learnedSignal.second > 0.5 && recommendedStrategy == ExitStrategy.HOLD) {
                recommendedStrategy = ExitStrategy.LEARNED_EXIT
                sellPct = 50.0
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // FINAL DECISION
        // ─────────────────────────────────────────────────────────────────────
        val urgency = when {
            totalUrgency >= 0.9 -> ExitUrgency.CRITICAL
            totalUrgency >= 0.7 -> ExitUrgency.HIGH
            totalUrgency >= 0.4 -> ExitUrgency.MEDIUM
            totalUrgency >= 0.2 -> ExitUrgency.LOW
            else -> ExitUrgency.NONE
        }
        
        val confidence = ((totalUrgency / 2.0) * 100).toInt().coerceIn(0, 100)
        
        // Calculate suggested stop loss (tighten as profits grow)
        val suggestedStop = when {
            currentPnlPct > 100 -> currentPnlPct * 0.7  // Lock 70% of 100%+ gains
            currentPnlPct > 50 -> currentPnlPct * 0.6   // Lock 60% of 50%+ gains
            currentPnlPct > 20 -> currentPnlPct * 0.5   // Lock 50% of 20%+ gains
            currentPnlPct > 10 -> 5.0                    // Move stop to +5%
            currentPnlPct > 5 -> 0.0                     // Move stop to breakeven
            else -> null
        }
        
        // Log decision
        if (recommendedStrategy != ExitStrategy.HOLD) {
            ErrorLogger.info(TAG, "🎯 ${ts.symbol} | ${recommendedStrategy.emoji} ${recommendedStrategy.label} | " +
                "sell=${sellPct.toInt()}% | pnl=${currentPnlPct.toInt()}% | peak=${pos.peakPnlPct.toInt()}% | " +
                "urgency=${urgency.name}")
        }
        
        return SellSignal(
            strategy = recommendedStrategy,
            urgency = urgency,
            sellPct = sellPct,
            reason = signals.firstOrNull() ?: "Holding",
            confidence = confidence,
            signals = signals,
            peakPnlPct = pos.peakPnlPct,
            currentPnlPct = currentPnlPct,
            lockedProfitSol = pos.lockedProfitSol,
            suggestedStopLoss = suggestedStop,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNED PATTERN MATCHING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun checkLearnedPatterns(
        ts: TokenState, 
        currentPnlPct: Double, 
        holdTimeMinutes: Int
    ): Pair<String, Double>? {
        // Get historical stats for similar situations
        val stats = try { TradeHistoryStore.getStats() } catch (_: Exception) { return null }
        
        // Pattern 1: Similar token type + hold time → what happened?
        // (This would ideally query a more detailed trade history)
        
        // Pattern 2: Current P&L level + momentum → historical outcome
        val avgWinPct = stats.avgWinPct
        if (currentPnlPct > avgWinPct * 1.5 && stats.totalTrades > 20) {
            return "Above 1.5x avg win (${avgWinPct.toInt()}%), consider taking" to 0.4
        }
        
        // Pattern 3: Hold time vs historical optimal
        val avgHoldTime = stats.avgHoldTimeMinutes
        if (holdTimeMinutes > avgHoldTime * 2 && currentPnlPct > 0) {
            return "Held 2x avg time (${avgHoldTime}min), risk increasing" to 0.3
        }
        
        return null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORD OUTCOMES FOR LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record the outcome of an exit for future learning.
     */
    fun recordExitOutcome(
        strategy: ExitStrategy,
        exitPnlPct: Double,
        wouldHaveBeen: Double,  // What P&L would have been if held 5 more minutes
        tokenType: String,
        holdTimeMinutes: Int,
    ) {
        val wasOptimal = when {
            exitPnlPct > 0 && wouldHaveBeen < exitPnlPct -> true  // Exited before drop
            exitPnlPct < 0 && wouldHaveBeen < exitPnlPct -> true  // Cut loss before worse
            else -> false
        }
        
        val outcome = ExitOutcome(
            strategy = strategy,
            entryPnlPct = exitPnlPct,
            finalPnlPct = wouldHaveBeen,
            wasOptimal = wasOptimal,
            tokenType = tokenType,
            holdTimeMinutes = holdTimeMinutes,
        )
        
        exitHistory.getOrPut(strategy) { mutableListOf() }.add(outcome)
        
        // Update strategy win rate
        val outcomes = exitHistory[strategy] ?: return
        val winRate = outcomes.count { it.wasOptimal }.toDouble() / outcomes.size * 100
        strategyWinRates[strategy] = winRate
        
        ErrorLogger.debug(TAG, "📚 LEARNED: ${strategy.label} | " +
            "exit=${exitPnlPct.toInt()}% vs would=${wouldHaveBeen.toInt()}% | " +
            "optimal=$wasOptimal | winRate=${winRate.toInt()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & UI
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SellOptState(
        val activePositions: Int,
        val totalLockedProfit: Double,
        val learningProgress: Int,
        val chunk1Threshold: Int,
        val chunk2Threshold: Int,
        val chunk3Threshold: Int,
        val trailingLockPct: Int,
        val maxHoldMinutes: Int,
        val strategyStats: Map<String, Int>,  // Strategy name → win rate
    )
    
    fun getState(): SellOptState {
        val totalLocked = activePositions.values.sumOf { it.lockedProfitSol }
        val stats = strategyWinRates.mapKeys { it.key.label }.mapValues { it.value.toInt() }
        
        return SellOptState(
            activePositions = activePositions.size,
            totalLockedProfit = totalLocked,
            learningProgress = (FluidLearningAI.getLearningProgress() * 100).toInt(),
            chunk1Threshold = getChunk1Threshold().toInt(),
            chunk2Threshold = getChunk2Threshold().toInt(),
            chunk3Threshold = getChunk3Threshold().toInt(),
            trailingLockPct = (getTrailingLockPct() * 100).toInt(),
            maxHoldMinutes = getMaxHoldMinutes(),
            strategyStats = stats,
        )
    }
    
    fun getSummary(): String {
        val state = getState()
        return buildString {
            append("🎯 SELL OPT AI: ${state.learningProgress}% learned | ")
            append("Chunks: ${state.chunk1Threshold}%/${state.chunk2Threshold}%/${state.chunk3Threshold}% | ")
            append("Lock: ${state.trailingLockPct}% of peak | ")
            append("MaxHold: ${state.maxHoldMinutes}min | ")
            append("Positions: ${state.activePositions} | ")
            append("Locked: ${state.totalLockedProfit.let { "%.3f".format(it) }}SOL")
        }
    }
}
