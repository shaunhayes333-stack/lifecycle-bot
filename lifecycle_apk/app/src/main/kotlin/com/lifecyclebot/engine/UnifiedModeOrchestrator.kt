package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * UnifiedModeOrchestrator — Central control for all trading modes.
 * 
 * Manages the 18 trading modes with:
 * - Performance tracking per mode
 * - Dynamic mode activation based on market conditions
 * - Mode-specific parameter optimization
 * - Cross-mode coordination
 * 
 * DEFENSIVE DESIGN:
 * - Lazy initialization (no blocking in init)
 * - All methods wrapped in try/catch
 * - Graceful fallbacks to STANDARD mode
 * - No coroutine launches in object init
 */
object UnifiedModeOrchestrator {
    
    private const val TAG = "ModeOrchestrator"
    
    // ═══════════════════════════════════════════════════════════════════
    // EXTENDED TRADING MODES
    // ═══════════════════════════════════════════════════════════════════
    
    enum class ExtendedMode(
        val emoji: String,
        val label: String,
        val riskLevel: Int,  // 1-5, higher = riskier
        val description: String,
    ) {
        STANDARD("📈", "Standard", 3, "Balanced quality gates"),
        MOONSHOT("🌙", "Moonshot", 4, "Early high-potential detection"),
        PUMP_SNIPER("🎰", "Pump Sniper", 5, "Viral pump detection"),
        COPY_TRADE("👥", "Copy Trade", 3, "Whale wallet following"),
        LONG_HOLD("💎", "Long Hold", 2, "Diamond hands conviction"),
        BLUE_CHIP("🏆", "Blue Chip", 1, "Safe treasury growth"),
        CYCLIC("🔄", "Cyclic", 2, "Pattern-based trading"),
        SLEEPER("😴", "Sleeper", 4, "Dormant token revivals"),
        NICHE("💠", "Niche", 4, "Low supply opportunities"),
        PRESALE_SNIPE("🎯", "Presale Snipe", 5, "First-block entry"),
        ARBITRAGE("⚖️", "Arbitrage", 2, "Cross-DEX spreads"),
        MOMENTUM_SWING("📊", "Momentum Swing", 3, "Strong trend following"),
        MICRO_CAP("🔬", "Micro Cap", 5, "Ultra-small mcap plays"),
        REVIVAL("🔥", "Revival", 4, "Crashed token recovery"),
        WHALE_FOLLOW("🐋", "Whale Follow", 3, "Smart money tracking"),
        PUMP_DUMP("💣", "Pump & Dump", 5, "Aggressive accumulation"),
        MARKET_MAKER("🏦", "Market Maker", 2, "Spread profit capture"),
        LIQUIDATION_HUNTER("🎪", "Liquidation Hunter", 4, "Distressed selling"),
    }
    
    /**
     * Mode performance statistics.
     */
    data class ModeStats(
        val mode: ExtendedMode,
        var trades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnlPct: Double = 0.0,
        var avgHoldTimeMs: Long = 0,
        var lastTradeMs: Long = 0,
        var isActive: Boolean = true,
        var deactivationReason: String = "",
    ) {
        val winRate: Double get() = if (trades > 0) wins.toDouble() / trades * 100 else 0.0
        val avgPnlPct: Double get() = if (trades > 0) totalPnlPct / trades else 0.0
        val isHealthy: Boolean get() = winRate >= 40.0 || trades < 10  // Need 10+ trades to judge
    }
    
    /**
     * Mode recommendation for current market.
     */
    data class ModeRecommendation(
        val primaryMode: ExtendedMode,
        val secondaryModes: List<ExtendedMode>,
        val reason: String,
        val confidence: Double,  // 0-100
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // STATE (Thread-safe)
    // ═══════════════════════════════════════════════════════════════════
    
    private val modeStats = ConcurrentHashMap<ExtendedMode, ModeStats>()
    
    @Volatile private var currentPrimaryMode: ExtendedMode = ExtendedMode.STANDARD
    @Volatile private var activeModes: Set<ExtendedMode> = ExtendedMode.values().toSet()
    @Volatile private var lastEvaluationMs: Long = 0
    @Volatile private var initialized: Boolean = false
    
    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION (Lazy, non-blocking)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Initialize mode stats lazily. Safe to call multiple times.
     */
    fun ensureInitialized() {
        if (initialized) return
        try {
            ExtendedMode.values().forEach { mode ->
                modeStats.putIfAbsent(mode, ModeStats(mode))
            }
            initialized = true
            ErrorLogger.info(TAG, "Initialized ${ExtendedMode.values().size} trading modes")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Init error (non-fatal): ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MODE EVALUATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate and recommend modes based on current market conditions.
     * 
     * @param marketRegime Current market regime (BULL/BEAR/SIDEWAYS)
     * @param volatilityPct Market volatility
     * @param trendingCount Number of trending tokens
     * @param config Bot configuration
     * @return ModeRecommendation with primary and secondary modes
     */
    fun evaluateMarket(
        marketRegime: String,
        volatilityPct: Double,
        trendingCount: Int,
        config: BotConfig,
    ): ModeRecommendation {
        ensureInitialized()
        
        return try {
            lastEvaluationMs = System.currentTimeMillis()
            
            val primary: ExtendedMode
            val secondary = mutableListOf<ExtendedMode>()
            var reason: String
            var confidence: Double
            
            when {
                // High volatility + bull = aggressive modes
                volatilityPct > 20 && marketRegime == "BULL" -> {
                    primary = ExtendedMode.PUMP_SNIPER
                    secondary.addAll(listOf(ExtendedMode.MOONSHOT, ExtendedMode.MOMENTUM_SWING))
                    reason = "High volatility bull market - aggressive entries"
                    confidence = 75.0
                }
                
                // Low volatility + bull = steady growth
                volatilityPct < 10 && marketRegime == "BULL" -> {
                    primary = ExtendedMode.BLUE_CHIP
                    secondary.addAll(listOf(ExtendedMode.LONG_HOLD, ExtendedMode.STANDARD))
                    reason = "Low volatility bull - steady accumulation"
                    confidence = 80.0
                }
                
                // Bear market = defensive
                marketRegime == "BEAR" -> {
                    primary = ExtendedMode.STANDARD
                    secondary.addAll(listOf(ExtendedMode.BLUE_CHIP, ExtendedMode.ARBITRAGE))
                    reason = "Bear market - defensive positioning"
                    confidence = 70.0
                }
                
                // Many trending = pump detection
                trendingCount > 10 -> {
                    primary = ExtendedMode.PUMP_SNIPER
                    secondary.addAll(listOf(ExtendedMode.COPY_TRADE, ExtendedMode.MOMENTUM_SWING))
                    reason = "Many trending tokens - pump opportunity"
                    confidence = 65.0
                }
                
                // Default
                else -> {
                    primary = ExtendedMode.STANDARD
                    secondary.addAll(listOf(ExtendedMode.MOMENTUM_SWING, ExtendedMode.COPY_TRADE))
                    reason = "Normal conditions - balanced approach"
                    confidence = 60.0
                }
            }
            
            currentPrimaryMode = primary
            
            ModeRecommendation(
                primaryMode = primary,
                secondaryModes = secondary.filter { isActive(it) },
                reason = reason,
                confidence = confidence,
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "evaluateMarket error: ${e.message}")
            ModeRecommendation(
                primaryMode = ExtendedMode.STANDARD,
                secondaryModes = emptyList(),
                reason = "Fallback to standard",
                confidence = 50.0,
            )
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Record a completed trade for a mode.
     */
    fun recordTrade(
        mode: ExtendedMode,
        isWin: Boolean,
        pnlPct: Double,
        holdTimeMs: Long,
    ) {
        ensureInitialized()
        
        try {
            val stats = modeStats.getOrPut(mode) { ModeStats(mode) }
            stats.trades++
            if (isWin) stats.wins++ else stats.losses++
            stats.totalPnlPct += pnlPct
            stats.lastTradeMs = System.currentTimeMillis()
            
            // Update rolling average hold time
            stats.avgHoldTimeMs = if (stats.trades == 1) {
                holdTimeMs
            } else {
                ((stats.avgHoldTimeMs * (stats.trades - 1)) + holdTimeMs) / stats.trades
            }
            
            // Auto-deactivate poorly performing modes
            if (stats.trades >= 10 && stats.winRate < 30.0) {
                stats.isActive = false
                stats.deactivationReason = "Win rate ${stats.winRate.toInt()}% < 30% after ${stats.trades} trades"
                ErrorLogger.warn(TAG, "Deactivated ${mode.label}: ${stats.deactivationReason}")
            }
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "recordTrade error: ${e.message}")
        }
    }
    
    /**
     * Record trade using mode tag from ModeSpecificGates.
     */
    fun recordTradeByTag(
        modeTag: ModeSpecificGates.TradingModeTag,
        isWin: Boolean,
        pnlPct: Double,
        holdTimeMs: Long,
    ) {
        val mode = tagToExtendedMode(modeTag)
        recordTrade(mode, isWin, pnlPct, holdTimeMs)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MODE STATUS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Check if a mode is currently active.
     */
    fun isActive(mode: ExtendedMode): Boolean {
        return try {
            modeStats[mode]?.isActive ?: true
        } catch (e: Exception) {
            true
        }
    }
    
    /**
     * Get stats for a specific mode.
     */
    fun getStats(mode: ExtendedMode): ModeStats? {
        ensureInitialized()
        return modeStats[mode]
    }
    
    /**
     * Get all mode stats sorted by performance.
     */
    fun getAllStatsSorted(): List<ModeStats> {
        ensureInitialized()
        return try {
            modeStats.values.sortedByDescending { it.winRate }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get current primary mode.
     */
    fun getCurrentPrimaryMode(): ExtendedMode = currentPrimaryMode
    
    /**
     * Manually activate a mode.
     */
    fun activateMode(mode: ExtendedMode) {
        try {
            modeStats[mode]?.let {
                it.isActive = true
                it.deactivationReason = ""
            }
            activeModes = activeModes + mode
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "activateMode error: ${e.message}")
        }
    }
    
    /**
     * Manually deactivate a mode.
     */
    fun deactivateMode(mode: ExtendedMode, reason: String) {
        try {
            modeStats[mode]?.let {
                it.isActive = false
                it.deactivationReason = reason
            }
            activeModes = activeModes - mode
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "deactivateMode error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Export state to JSON for persistence.
     */
    fun toJson(): JSONObject {
        return try {
            JSONObject().apply {
                put("primaryMode", currentPrimaryMode.name)
                put("lastEvaluation", lastEvaluationMs)
                
                val statsArray = org.json.JSONArray()
                modeStats.values.forEach { stats ->
                    statsArray.put(JSONObject().apply {
                        put("mode", stats.mode.name)
                        put("trades", stats.trades)
                        put("wins", stats.wins)
                        put("losses", stats.losses)
                        put("totalPnlPct", stats.totalPnlPct)
                        put("avgHoldTimeMs", stats.avgHoldTimeMs)
                        put("isActive", stats.isActive)
                        put("deactivationReason", stats.deactivationReason)
                    })
                }
                put("modeStats", statsArray)
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
    
    /**
     * Restore state from JSON.
     */
    fun fromJson(json: JSONObject) {
        try {
            currentPrimaryMode = try {
                ExtendedMode.valueOf(json.optString("primaryMode", "STANDARD"))
            } catch (e: Exception) {
                ExtendedMode.STANDARD
            }
            lastEvaluationMs = json.optLong("lastEvaluation", 0)
            
            val statsArray = json.optJSONArray("modeStats")
            if (statsArray != null) {
                for (i in 0 until statsArray.length()) {
                    val obj = statsArray.optJSONObject(i) ?: continue
                    val modeName = obj.optString("mode", "") 
                    val mode = try {
                        ExtendedMode.valueOf(modeName)
                    } catch (e: Exception) {
                        continue
                    }
                    
                    modeStats[mode] = ModeStats(
                        mode = mode,
                        trades = obj.optInt("trades", 0),
                        wins = obj.optInt("wins", 0),
                        losses = obj.optInt("losses", 0),
                        totalPnlPct = obj.optDouble("totalPnlPct", 0.0),
                        avgHoldTimeMs = obj.optLong("avgHoldTimeMs", 0),
                        isActive = obj.optBoolean("isActive", true),
                        deactivationReason = obj.optString("deactivationReason", ""),
                    )
                }
            }
            
            initialized = true
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "fromJson error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Convert ModeSpecificGates.TradingModeTag to ExtendedMode.
     */
    private fun tagToExtendedMode(tag: ModeSpecificGates.TradingModeTag): ExtendedMode {
        return when (tag) {
            ModeSpecificGates.TradingModeTag.STANDARD -> ExtendedMode.STANDARD
            ModeSpecificGates.TradingModeTag.MOONSHOT -> ExtendedMode.MOONSHOT
            ModeSpecificGates.TradingModeTag.PUMP_SNIPER -> ExtendedMode.PUMP_SNIPER
            ModeSpecificGates.TradingModeTag.COPY_TRADE -> ExtendedMode.COPY_TRADE
            ModeSpecificGates.TradingModeTag.LONG_HOLD -> ExtendedMode.LONG_HOLD
            ModeSpecificGates.TradingModeTag.BLUE_CHIP -> ExtendedMode.BLUE_CHIP
            ModeSpecificGates.TradingModeTag.DEFENSIVE -> ExtendedMode.STANDARD
            ModeSpecificGates.TradingModeTag.AGGRESSIVE -> ExtendedMode.PUMP_SNIPER
            ModeSpecificGates.TradingModeTag.SNIPE -> ExtendedMode.PRESALE_SNIPE
            ModeSpecificGates.TradingModeTag.RANGE -> ExtendedMode.CYCLIC
            ModeSpecificGates.TradingModeTag.MICRO_CAP -> ExtendedMode.MICRO_CAP
            ModeSpecificGates.TradingModeTag.WHALE_FOLLOW -> ExtendedMode.WHALE_FOLLOW
        }
    }
    
    /**
     * Get mode display string with emoji.
     */
    fun getModeDisplay(mode: ExtendedMode): String {
        return "${mode.emoji} ${mode.label}"
    }
    
    /**
     * Get summary of all modes for dashboard display.
     */
    fun getDashboardSummary(): String {
        ensureInitialized()
        return try {
            val active = modeStats.values.count { it.isActive }
            val topMode = modeStats.values
                .filter { it.trades >= 5 }
                .maxByOrNull { it.winRate }
            
            buildString {
                append("Active Modes: $active/${ExtendedMode.values().size}\n")
                append("Current: ${getModeDisplay(currentPrimaryMode)}\n")
                if (topMode != null) {
                    append("Top Performer: ${getModeDisplay(topMode.mode)} (${topMode.winRate.toInt()}% win)")
                }
            }
        } catch (e: Exception) {
            "Mode data unavailable"
        }
    }
}
