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
    
    // V5.2: Paper mode flag - set by BotService
    @Volatile
    var isPaperMode: Boolean = false
    
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
        MOONSHOT("🚀", "Moonshot", 4, "Early high-potential detection"),
        PUMP_SNIPER("🔫", "Pump Sniper", 5, "Viral pump detection"),
        COPY_TRADE("🦊", "Copy Trade", 3, "Whale wallet following"),
        LONG_HOLD("💎", "Diamond Hands", 2, "Long-term conviction holds"),
        BLUE_CHIP("🔵", "Blue Chip", 1, "Safe treasury growth"),
        CYCLIC("♻️", "Cyclic", 2, "Pattern-based trading"),
        SLEEPER("💤", "Sleeper", 4, "Dormant token revivals"),
        NICHE("🧬", "Niche", 4, "Low supply opportunities"),
        PRESALE_SNIPE("🎯", "Presale Snipe", 5, "First-block entry"),
        ARBITRAGE("⚡", "Arbitrage", 2, "Cross-DEX spreads"),
        MOMENTUM_SWING("🌊", "Momentum Wave", 3, "Strong trend following"),
        MICRO_CAP("🔬", "Micro Cap", 5, "Ultra-small mcap plays"),
        REVIVAL("🔥", "Phoenix", 4, "Crashed token recovery"),
        WHALE_FOLLOW("🐋", "Whale Follow", 3, "Smart money tracking"),
        PUMP_DUMP("💣", "Pump & Dump", 5, "Aggressive accumulation"),
        MARKET_MAKER("🏛️", "Market Maker", 2, "Spread profit capture"),
        LIQUIDATION_HUNTER("🦅", "Liquidation Hunter", 4, "Distressed selling"),
        TREASURY("💰", "Treasury", 1, "Conservative cash generation"),
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
        val winRate: Double get() = if (wins + losses > 0) wins.toDouble() / (wins + losses) * 100 else 0.0
        val avgPnlPct: Double get() = if (trades > 0) totalPnlPct / trades else 0.0
        val isHealthy: Boolean get() = winRate >= 40.0 || (wins + losses) < 10  // Need 10+ decisive trades to judge
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
                    confidence = 55.0
                }
                
                // Low volatility + bull = steady growth
                volatilityPct < 10 && marketRegime == "BULL" -> {
                    primary = ExtendedMode.BLUE_CHIP
                    secondary.addAll(listOf(ExtendedMode.LONG_HOLD, ExtendedMode.STANDARD))
                    reason = "Low volatility bull - steady accumulation"
                    confidence = 70.0
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
        
        // V5.9.220+: Skip scratches — only decisive trades count for mode stats/WR
        val isScratch = pnlPct in -1.0..1.0 && !isWin
        if (isScratch) return
        
        try {
            val stats = modeStats.getOrPut(mode) { ModeStats(mode) }
            val decisiveBefore = stats.wins + stats.losses
            stats.trades++
            if (isWin) stats.wins++ else stats.losses++
            stats.totalPnlPct += pnlPct
            stats.lastTradeMs = System.currentTimeMillis()
            
            // Update rolling average hold time (use decisive count)
            val decisiveCount = stats.wins + stats.losses
            stats.avgHoldTimeMs = if (decisiveCount == 1) {
                holdTimeMs
            } else {
                ((stats.avgHoldTimeMs * (decisiveCount - 1)) + holdTimeMs) / decisiveCount
            }
            
            // Auto-deactivate poorly performing modes.
            // V5.9: Never deactivate during bootstrap phase — low win rate is expected
            // while the model is gathering data. Only evaluate after bootstrap ends.
            val learningProgress = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
            } catch (_: Exception) { 0.0 }
            val isBootstrapPhase = learningProgress < 0.40  // V5.9.165: aligned

            val minTrades = when {
                isPaperMode && isBootstrapPhase -> Int.MAX_VALUE  // Never deactivate in bootstrap
                isPaperMode -> 150                                 // Post-bootstrap paper: need more data
                else -> 10                                         // Live: judge quickly
            }
            val minWinRate = if (isPaperMode) 8.0 else 30.0

            // Guard uses decisive trades (wins+losses), not raw trade count
            if (decisiveCount >= minTrades && stats.winRate < minWinRate) {
                stats.isActive = false
                stats.deactivationReason = "Win rate ${stats.winRate.toInt()}% < ${minWinRate.toInt()}% after $decisiveCount decisive trades"
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
    
    // ═══════════════════════════════════════════════════════════════════
    // TOKEN-BASED MODE RECOMMENDATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Recommend the best trading mode based on token characteristics.
     * This ensures all 18 modes have opportunities to trigger.
     * 
     * @param liquidity Token liquidity in USD
     * @param mcap Market cap
     * @param ageMs Token age in milliseconds
     * @param volScore Volume score (0-100)
     * @param momScore Momentum score (0-100)
     * @param source Discovery source (e.g., "PUMP_FUN", "DEX_BOOSTED")
     * @param emafanAlignment EMA fan alignment (BULL_FAN, FLAT, etc)
     * @param holderConcentration Top holder percentage
     * @param isRevival Whether token is showing revival signals
     * @param hasWhaleActivity Whether whales are active
     * @return Recommended ExtendedMode
     */
    fun recommendModeForToken(
        liquidity: Double,
        mcap: Double,
        ageMs: Long,
        volScore: Double,
        momScore: Double,
        source: String,
        emafanAlignment: String = "FLAT",
        holderConcentration: Double = 0.0,
        isRevival: Boolean = false,
        hasWhaleActivity: Boolean = false,
    ): ExtendedMode {
        ensureInitialized()
        
        val ageMinutes = ageMs / (60 * 1000)
        
        return try {
            when {
                // ═══════════════════════════════════════════════════════════════
                // PRESALE_SNIPE: Ultra-new tokens (< 2 minutes)
                // ═══════════════════════════════════════════════════════════════
                ageMinutes < 2 && source.contains("PUMP", ignoreCase = true) -> {
                    if (isActive(ExtendedMode.PRESALE_SNIPE)) ExtendedMode.PRESALE_SNIPE
                    else ExtendedMode.PUMP_SNIPER
                }
                
                // ═══════════════════════════════════════════════════════════════
                // PUMP_SNIPER: New tokens with high volume (< 10 min, vol > 70)
                // ═══════════════════════════════════════════════════════════════
                ageMinutes < 10 && volScore > 70 -> {
                    if (isActive(ExtendedMode.PUMP_SNIPER)) ExtendedMode.PUMP_SNIPER
                    else ExtendedMode.MOMENTUM_SWING
                }
                
                // ═══════════════════════════════════════════════════════════════
                // MICRO_CAP: Tiny market cap tokens
                // ═══════════════════════════════════════════════════════════════
                mcap < 15000 && liquidity < 8000 -> {
                    if (isActive(ExtendedMode.MICRO_CAP)) ExtendedMode.MICRO_CAP
                    else ExtendedMode.MOONSHOT
                }
                
                // ═══════════════════════════════════════════════════════════════
                // MOONSHOT: Low mcap with strong momentum
                // ═══════════════════════════════════════════════════════════════
                mcap < 100000 && momScore > 60 && emafanAlignment.contains("BULL") -> {
                    if (isActive(ExtendedMode.MOONSHOT)) ExtendedMode.MOONSHOT
                    else ExtendedMode.MOMENTUM_SWING
                }
                
                // ═══════════════════════════════════════════════════════════════
                // WHALE_FOLLOW: When whales are active
                // ═══════════════════════════════════════════════════════════════
                hasWhaleActivity && liquidity > 20000 -> {
                    if (isActive(ExtendedMode.WHALE_FOLLOW)) ExtendedMode.WHALE_FOLLOW
                    else ExtendedMode.COPY_TRADE
                }
                
                // ═══════════════════════════════════════════════════════════════
                // REVIVAL: Crashed tokens showing recovery
                // ═══════════════════════════════════════════════════════════════
                isRevival && momScore > 50 -> {
                    if (isActive(ExtendedMode.REVIVAL)) ExtendedMode.REVIVAL
                    else ExtendedMode.MOMENTUM_SWING
                }
                
                // ═══════════════════════════════════════════════════════════════
                // LIQUIDATION_HUNTER: Distressed tokens with volume spike
                // ═══════════════════════════════════════════════════════════════
                mcap < 50000 && volScore > 80 && liquidity < 10000 -> {
                    if (isActive(ExtendedMode.LIQUIDATION_HUNTER)) ExtendedMode.LIQUIDATION_HUNTER
                    else ExtendedMode.PUMP_SNIPER
                }
                
                // ═══════════════════════════════════════════════════════════════
                // NICHE: Low supply opportunities (high holder concentration)
                // ═══════════════════════════════════════════════════════════════
                holderConcentration > 30 && mcap < 200000 && momScore > 40 -> {
                    if (isActive(ExtendedMode.NICHE)) ExtendedMode.NICHE
                    else ExtendedMode.STANDARD
                }
                
                // ═══════════════════════════════════════════════════════════════
                // SLEEPER: Dormant token with sudden activity
                // ═══════════════════════════════════════════════════════════════
                ageMinutes > 60 * 24 && volScore > 60 && source.contains("SLEEPER", ignoreCase = true) -> {
                    if (isActive(ExtendedMode.SLEEPER)) ExtendedMode.SLEEPER
                    else ExtendedMode.REVIVAL
                }
                
                // ═══════════════════════════════════════════════════════════════
                // CYCLIC: Range-bound tokens
                // ═══════════════════════════════════════════════════════════════
                emafanAlignment == "FLAT" && volScore in 30.0..60.0 && ageMinutes > 30 -> {
                    if (isActive(ExtendedMode.CYCLIC)) ExtendedMode.CYCLIC
                    else ExtendedMode.STANDARD
                }
                
                // ═══════════════════════════════════════════════════════════════
                // MOMENTUM_SWING: Strong trend following
                // ═══════════════════════════════════════════════════════════════
                emafanAlignment.contains("BULL") && momScore > 55 && volScore > 50 -> {
                    if (isActive(ExtendedMode.MOMENTUM_SWING)) ExtendedMode.MOMENTUM_SWING
                    else ExtendedMode.STANDARD
                }
                
                // ═══════════════════════════════════════════════════════════════
                // BLUE_CHIP: Established high-liquidity tokens
                // ═══════════════════════════════════════════════════════════════
                liquidity > 10000 && mcap > 100000 -> {
                    if (isActive(ExtendedMode.BLUE_CHIP)) ExtendedMode.BLUE_CHIP
                    else ExtendedMode.LONG_HOLD
                }
                
                // ═══════════════════════════════════════════════════════════════
                // LONG_HOLD: High liquidity with stable fundamentals
                // ═══════════════════════════════════════════════════════════════
                liquidity > 50000 && mcap > 200000 && volScore in 30.0..70.0 -> {
                    if (isActive(ExtendedMode.LONG_HOLD)) ExtendedMode.LONG_HOLD
                    else ExtendedMode.BLUE_CHIP
                }
                
                // ═══════════════════════════════════════════════════════════════
                // PUMP_DUMP: Aggressive accumulation (high vol, new-ish)
                // ═══════════════════════════════════════════════════════════════
                ageMinutes < 30 && volScore > 75 && liquidity > 10000 -> {
                    if (isActive(ExtendedMode.PUMP_DUMP)) ExtendedMode.PUMP_DUMP
                    else ExtendedMode.PUMP_SNIPER
                }
                
                // ═══════════════════════════════════════════════════════════════
                // MARKET_MAKER: Spread capture on stable tokens
                // ═══════════════════════════════════════════════════════════════
                liquidity > 100000 && emafanAlignment == "FLAT" && volScore < 40 -> {
                    if (isActive(ExtendedMode.MARKET_MAKER)) ExtendedMode.MARKET_MAKER
                    else ExtendedMode.CYCLIC
                }
                
                // ═══════════════════════════════════════════════════════════════
                // ARBITRAGE: Cross-DEX opportunities (high liquidity, stable)
                // ═══════════════════════════════════════════════════════════════
                liquidity > 150000 && mcap > 500000 && volScore > 40 -> {
                    if (isActive(ExtendedMode.ARBITRAGE)) ExtendedMode.ARBITRAGE
                    else ExtendedMode.STANDARD
                }
                
                // ═══════════════════════════════════════════════════════════════
                // COPY_TRADE: Follow smart money
                // ═══════════════════════════════════════════════════════════════
                hasWhaleActivity -> {
                    if (isActive(ExtendedMode.COPY_TRADE)) ExtendedMode.COPY_TRADE
                    else ExtendedMode.WHALE_FOLLOW
                }
                
                // ═══════════════════════════════════════════════════════════════
                // DEFAULT: Standard mode
                // ═══════════════════════════════════════════════════════════════
                else -> ExtendedMode.STANDARD
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "recommendModeForToken error: ${e.message}")
            ExtendedMode.STANDARD
        }
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
