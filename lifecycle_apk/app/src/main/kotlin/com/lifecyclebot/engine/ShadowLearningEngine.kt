package com.lifecyclebot.engine

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * ShadowLearningEngine — AI learning through parallel simulated trades
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Runs simulated "shadow" trades in the background alongside live trading:
 *
 *   1. PARAMETER VARIANTS — Tests different settings simultaneously:
 *      - Entry thresholds (±5, ±10 from current)
 *      - Stop loss levels (10%, 15%, 20%)
 *      - Take profit targets
 *      - Position sizes
 *
 *   2. STRATEGY VARIANTS — Tests alternative approaches:
 *      - More aggressive entries
 *      - More conservative entries
 *      - Different exit timing
 *      - Different phase preferences
 *
 *   3. LEARNING FEEDBACK — Compares results and suggests improvements:
 *      - "Variant A would have made +15% more profit"
 *      - "Lower entry threshold caught 3 more winners"
 *      - "Tighter stops reduced drawdown by 20%"
 *
 *   4. AUTO-ADAPTATION — Optionally auto-applies winning strategies
 *
 * This creates a continuous A/B testing environment that learns from
 * every market condition without risking real capital.
 */
object ShadowLearningEngine {

    // ══════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ══════════════════════════════════════════════════════════════════

    data class ShadowVariant(
        val id: String,
        val name: String,
        val description: String,
        val entryThresholdOffset: Int = 0,      // +/- from base
        val exitThresholdOffset: Int = 0,
        val stopLossMultiplier: Double = 1.0,   // 1.0 = same as live
        val takeProfitMultiplier: Double = 1.0,
        val positionSizeMultiplier: Double = 1.0,
        val phaseBoosts: Map<String, Int> = emptyMap(),  // phase -> extra points
    )

    data class ShadowTrade(
        val variantId: String,
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entryTimeMs: Long,
        val entrySizeSol: Double,
        val entryScore: Int,
        var exitPrice: Double = 0.0,
        var exitTimeMs: Long = 0,
        var pnlSol: Double = 0.0,
        var pnlPct: Double = 0.0,
        var exitReason: String = "",
        var isOpen: Boolean = true,
    )

    data class VariantPerformance(
        val variantId: String,
        val variantName: String,
        val totalTrades: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val totalPnlSol: Double = 0.0,
        val avgPnlPct: Double = 0.0,
        val winRate: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val sharpeRatio: Double = 0.0,
        val comparedToLive: Double = 0.0,  // +/- % vs live trading
    )

    data class LearningInsight(
        val timestamp: Long = System.currentTimeMillis(),
        val type: InsightType,
        val message: String,
        val suggestedAction: String,
        val confidence: Double,  // 0-1
        val variantId: String,
        val metric: String,
        val improvement: Double,
    )

    enum class InsightType {
        ENTRY_THRESHOLD,
        EXIT_THRESHOLD,
        STOP_LOSS,
        TAKE_PROFIT,
        POSITION_SIZE,
        PHASE_PREFERENCE,
        TIMING,
    }

    // ══════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════

    private val variants = ConcurrentHashMap<String, ShadowVariant>()
    private val shadowTrades = ConcurrentHashMap<String, MutableList<ShadowTrade>>()
    private val performance = ConcurrentHashMap<String, VariantPerformance>()
    private val insights = mutableListOf<LearningInsight>()
    
    private var learningJob: Job? = null
    private var isEnabled = false
    
    // Live trading baseline for comparison
    private var liveTotalPnl = 0.0
    private var liveTrades = 0
    private var liveWins = 0

    // ══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Initialize with default strategy variants to test.
     */
    fun init() {
        // Clear previous state
        variants.clear()
        shadowTrades.clear()
        performance.clear()
        
        // Create default variants to test
        val defaultVariants = listOf(
            // Entry threshold variants
            ShadowVariant(
                id = "aggressive_entry",
                name = "Aggressive Entry",
                description = "Lower entry threshold by 5 points",
                entryThresholdOffset = -5,
            ),
            ShadowVariant(
                id = "conservative_entry",
                name = "Conservative Entry",
                description = "Higher entry threshold by 5 points",
                entryThresholdOffset = 5,
            ),
            ShadowVariant(
                id = "very_aggressive",
                name = "Very Aggressive",
                description = "Lower entry threshold by 10 points",
                entryThresholdOffset = -10,
            ),
            
            // Stop loss variants
            ShadowVariant(
                id = "tight_stops",
                name = "Tight Stops",
                description = "10% tighter stop losses",
                stopLossMultiplier = 0.9,
            ),
            ShadowVariant(
                id = "loose_stops",
                name = "Loose Stops",
                description = "15% looser stop losses",
                stopLossMultiplier = 1.15,
            ),
            
            // Position size variants
            ShadowVariant(
                id = "smaller_positions",
                name = "Smaller Positions",
                description = "50% smaller position sizes",
                positionSizeMultiplier = 0.5,
            ),
            ShadowVariant(
                id = "larger_positions",
                name = "Larger Positions",
                description = "25% larger positions on high scores",
                positionSizeMultiplier = 1.25,
            ),
            
            // Phase preference variants
            ShadowVariant(
                id = "pump_focused",
                name = "Pump Focused",
                description = "Extra weight on pumping phases",
                phaseBoosts = mapOf("pumping" to 10, "pre_pump" to 5),
            ),
            ShadowVariant(
                id = "range_focused",
                name = "Range Focused",
                description = "Extra weight on range/reclaim phases",
                phaseBoosts = mapOf("range" to 10, "reclaim" to 8),
            ),
            
            // Combined variants
            ShadowVariant(
                id = "conservative_safe",
                name = "Conservative & Safe",
                description = "Higher threshold + tighter stops",
                entryThresholdOffset = 5,
                stopLossMultiplier = 0.85,
            ),
            ShadowVariant(
                id = "aggressive_quick",
                name = "Aggressive & Quick",
                description = "Lower threshold + quick exits",
                entryThresholdOffset = -8,
                takeProfitMultiplier = 0.8,
            ),
        )
        
        defaultVariants.forEach { variant ->
            variants[variant.id] = variant
            shadowTrades[variant.id] = mutableListOf()
            performance[variant.id] = VariantPerformance(
                variantId = variant.id,
                variantName = variant.name,
            )
        }
    }

    /**
     * Start the learning engine.
     */
    fun start() {
        if (variants.isEmpty()) init()
        isEnabled = true
        
        learningJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive && isEnabled) {
                analyzeAndLearn()
                delay(60_000)  // Analyze every minute
            }
        }
    }

    fun stop() {
        isEnabled = false
        learningJob?.cancel()
    }

    // ══════════════════════════════════════════════════════════════════
    // SHADOW TRADING
    // ══════════════════════════════════════════════════════════════════

    /**
     * Called when a live trade opportunity is detected.
     * Evaluates if each shadow variant would have entered.
     */
    fun onTradeOpportunity(
        mint: String,
        symbol: String,
        currentPrice: Double,
        liveEntryScore: Int,
        liveEntryThreshold: Int,
        liveSizeSol: Double,
        phase: String,
    ) {
        if (!isEnabled) return

        variants.values.forEach { variant ->
            // Calculate variant's adjusted score
            val adjustedScore = liveEntryScore + variant.entryThresholdOffset +
                (variant.phaseBoosts[phase] ?: 0)
            
            val adjustedThreshold = liveEntryThreshold + variant.entryThresholdOffset
            val adjustedSize = liveSizeSol * variant.positionSizeMultiplier

            // Would this variant enter?
            if (adjustedScore >= adjustedThreshold) {
                val trade = ShadowTrade(
                    variantId = variant.id,
                    mint = mint,
                    symbol = symbol,
                    entryPrice = currentPrice,
                    entryTimeMs = System.currentTimeMillis(),
                    entrySizeSol = adjustedSize,
                    entryScore = adjustedScore,
                )
                shadowTrades[variant.id]?.add(trade)
            }
        }
    }

    /**
     * Called on price updates to check shadow exits.
     */
    fun onPriceUpdate(
        mint: String,
        currentPrice: Double,
        liveStopLossPct: Double,
        liveTakeProfitPct: Double,
    ) {
        if (!isEnabled) return

        variants.values.forEach { variant ->
            val trades = shadowTrades[variant.id] ?: return@forEach
            
            trades.filter { it.mint == mint && it.isOpen }.forEach { trade ->
                val pnlPct = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100
                
                // Adjusted stop loss
                val adjustedStopLoss = liveStopLossPct * variant.stopLossMultiplier
                
                // Adjusted take profit
                val adjustedTakeProfit = liveTakeProfitPct * variant.takeProfitMultiplier
                
                // Check exit conditions
                val exitReason = when {
                    pnlPct <= -adjustedStopLoss -> "STOP_LOSS"
                    pnlPct >= adjustedTakeProfit -> "TAKE_PROFIT"
                    else -> null
                }
                
                if (exitReason != null) {
                    closeShadowTrade(trade, currentPrice, exitReason)
                }
            }
        }
    }

    /**
     * Called when a live trade exits - close corresponding shadow trades.
     */
    fun onLiveTradeExit(
        mint: String,
        exitPrice: Double,
        exitReason: String,
        livePnlSol: Double,
        isWin: Boolean,
    ) {
        if (!isEnabled) return

        // Update live baseline
        liveTotalPnl += livePnlSol
        liveTrades++
        if (isWin) liveWins++

        // Close all shadow trades for this mint
        variants.values.forEach { variant ->
            shadowTrades[variant.id]?.filter { it.mint == mint && it.isOpen }?.forEach { trade ->
                closeShadowTrade(trade, exitPrice, "LIVE_EXIT: $exitReason")
            }
        }
    }

    private fun closeShadowTrade(trade: ShadowTrade, exitPrice: Double, exitReason: String) {
        trade.exitPrice = exitPrice
        trade.exitTimeMs = System.currentTimeMillis()
        trade.pnlPct = ((exitPrice - trade.entryPrice) / trade.entryPrice) * 100
        trade.pnlSol = trade.entrySizeSol * (trade.pnlPct / 100)
        trade.exitReason = exitReason
        trade.isOpen = false
        
        // Update performance
        updateVariantPerformance(trade.variantId)
    }

    private fun updateVariantPerformance(variantId: String) {
        val trades = shadowTrades[variantId]?.filter { !it.isOpen } ?: return
        if (trades.isEmpty()) return
        
        val wins = trades.count { it.pnlSol > 0 }
        val losses = trades.count { it.pnlSol <= 0 }
        val totalPnl = trades.sumOf { it.pnlSol }
        val avgPnl = if (trades.isNotEmpty()) trades.sumOf { it.pnlPct } / trades.size else 0.0
        val winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100 else 0.0
        
        // Calculate max drawdown
        var peak = 0.0
        var maxDd = 0.0
        var equity = 0.0
        trades.sortedBy { it.exitTimeMs }.forEach { t ->
            equity += t.pnlSol
            if (equity > peak) peak = equity
            val dd = if (peak > 0) (peak - equity) / peak * 100 else 0.0
            if (dd > maxDd) maxDd = dd
        }
        
        // Compare to live
        val comparedToLive = if (liveTotalPnl != 0.0) {
            ((totalPnl - liveTotalPnl) / kotlin.math.abs(liveTotalPnl)) * 100
        } else 0.0
        
        performance[variantId] = VariantPerformance(
            variantId = variantId,
            variantName = variants[variantId]?.name ?: variantId,
            totalTrades = trades.size,
            wins = wins,
            losses = losses,
            totalPnlSol = totalPnl,
            avgPnlPct = avgPnl,
            winRate = winRate,
            maxDrawdownPct = maxDd,
            comparedToLive = comparedToLive,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // LEARNING & INSIGHTS
    // ══════════════════════════════════════════════════════════════════

    private fun analyzeAndLearn() {
        if (performance.isEmpty()) return
        
        // Find best performing variant
        val ranked = performance.values
            .filter { it.totalTrades >= 5 }  // Need minimum trades
            .sortedByDescending { it.totalPnlSol }
        
        if (ranked.isEmpty()) return
        
        val best = ranked.first()
        val variant = variants[best.variantId] ?: return
        
        // Generate insights if variant significantly outperforms live
        if (best.comparedToLive > 15 && best.winRate > 45) {
            val insight = when {
                variant.entryThresholdOffset != 0 -> LearningInsight(
                    type = InsightType.ENTRY_THRESHOLD,
                    message = "${variant.name} outperformed live by ${best.comparedToLive.toInt()}%",
                    suggestedAction = if (variant.entryThresholdOffset < 0) 
                        "Consider lowering entry threshold by ${-variant.entryThresholdOffset} points"
                    else 
                        "Consider raising entry threshold by ${variant.entryThresholdOffset} points",
                    confidence = min(0.9, best.winRate / 100 + 0.3),
                    variantId = variant.id,
                    metric = "entry_threshold",
                    improvement = best.comparedToLive,
                )
                variant.stopLossMultiplier != 1.0 -> LearningInsight(
                    type = InsightType.STOP_LOSS,
                    message = "${variant.name} outperformed live by ${best.comparedToLive.toInt()}%",
                    suggestedAction = if (variant.stopLossMultiplier < 1.0)
                        "Consider tightening stop losses by ${((1 - variant.stopLossMultiplier) * 100).toInt()}%"
                    else
                        "Consider loosening stop losses by ${((variant.stopLossMultiplier - 1) * 100).toInt()}%",
                    confidence = min(0.9, best.winRate / 100 + 0.3),
                    variantId = variant.id,
                    metric = "stop_loss",
                    improvement = best.comparedToLive,
                )
                else -> LearningInsight(
                    type = InsightType.POSITION_SIZE,
                    message = "${variant.name} outperformed live by ${best.comparedToLive.toInt()}%",
                    suggestedAction = "Review ${variant.description}",
                    confidence = min(0.9, best.winRate / 100 + 0.3),
                    variantId = variant.id,
                    metric = "general",
                    improvement = best.comparedToLive,
                )
            }
            
            // Avoid duplicate insights
            if (insights.none { it.variantId == insight.variantId && 
                    System.currentTimeMillis() - it.timestamp < 3600_000 }) {
                insights.add(insight)
                
                // Keep only last 50 insights
                while (insights.size > 50) {
                    insights.removeAt(0)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════

    /**
     * Get performance ranking of all variants.
     */
    fun getPerformanceRanking(): List<VariantPerformance> {
        return performance.values
            .filter { it.totalTrades >= 3 }
            .sortedByDescending { it.totalPnlSol }
    }

    /**
     * Get recent learning insights.
     */
    fun getInsights(limit: Int = 10): List<LearningInsight> {
        return insights.takeLast(limit).reversed()
    }

    /**
     * Get the best performing variant.
     */
    fun getBestVariant(): VariantPerformance? {
        return performance.values
            .filter { it.totalTrades >= 5 }
            .maxByOrNull { it.totalPnlSol }
    }

    /**
     * Format status for display/Telegram.
     */
    fun getStatusSummary(): String = buildString {
        appendLine("🧠 *SHADOW LEARNING ENGINE*")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Status: ${if (isEnabled) "✅ Active" else "❌ Disabled"}")
        appendLine("Variants: ${variants.size}")
        appendLine("")
        
        appendLine("*Live Baseline:*")
        appendLine("  Trades: $liveTrades (${liveWins}W)")
        appendLine("  P&L: ${liveTotalPnl.fmt(4)} SOL")
        appendLine("")
        
        val ranked = getPerformanceRanking().take(5)
        if (ranked.isNotEmpty()) {
            appendLine("*Top Performing Variants:*")
            ranked.forEachIndexed { i, p ->
                val emoji = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "  " }
                val vsLive = if (p.comparedToLive > 0) "+${p.comparedToLive.toInt()}%" else "${p.comparedToLive.toInt()}%"
                appendLine("$emoji ${p.variantName}")
                appendLine("     ${p.totalPnlSol.fmt(4)} SOL | ${p.winRate.toInt()}% WR | $vsLive vs live")
            }
            appendLine("")
        }
        
        val recentInsights = getInsights(3)
        if (recentInsights.isNotEmpty()) {
            appendLine("*Recent Insights:*")
            recentInsights.forEach { insight ->
                appendLine("💡 ${insight.message}")
                appendLine("   → ${insight.suggestedAction}")
            }
        }
    }

    /**
     * Add custom variant for testing.
     */
    fun addVariant(variant: ShadowVariant) {
        variants[variant.id] = variant
        shadowTrades[variant.id] = mutableListOf()
        performance[variant.id] = VariantPerformance(
            variantId = variant.id,
            variantName = variant.name,
        )
    }

    /**
     * Reset all shadow data (keep variants).
     */
    fun reset() {
        shadowTrades.values.forEach { it.clear() }
        performance.keys.forEach { id ->
            performance[id] = VariantPerformance(
                variantId = id,
                variantName = variants[id]?.name ?: id,
            )
        }
        insights.clear()
        liveTotalPnl = 0.0
        liveTrades = 0
        liveWins = 0
    }

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}
