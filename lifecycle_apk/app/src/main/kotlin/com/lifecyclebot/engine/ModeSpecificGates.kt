package com.lifecyclebot.engine

/**
 * ModeSpecificGates — Trading mode-specific threshold adjustments.
 * 
 * Each trading mode has different risk tolerances and optimal thresholds.
 * This class provides multipliers that FinalDecisionGate applies to its
 * standard thresholds based on the current active trading mode.
 * 
 * DEFENSIVE DESIGN:
 * - All methods are static and stateless (no initialization crashes)
 * - All multipliers have safe defaults (1.0 = no change)
 * - Try/catch wrapping in getMultipliers() for ultimate safety
 */
object ModeSpecificGates {
    
    private const val TAG = "ModeSpecificGates"
    
    /**
     * Trading modes supported by the bot.
     * Maps to AutoModeEngine.BotMode but with extended types.
     */
    enum class TradingModeTag {
        STANDARD,        // Default balanced mode
        MOONSHOT,        // High risk, looking for 10x+
        PUMP_SNIPER,     // Aggressive pump detection
        COPY_TRADE,      // Following whale wallets
        LONG_HOLD,       // Diamond hands mode
        BLUE_CHIP,       // Safe, established tokens
        DEFENSIVE,       // Low risk, tight stops
        AGGRESSIVE,      // High risk, loose stops
        SNIPE,           // Fast entry on new tokens
        RANGE,           // Range-bound trading
        MICRO_CAP,       // Ultra-small mcap tokens
        WHALE_FOLLOW,    // Following smart money
        // V5.9.409 — meme-lane-specific tags so FDG receives the correct
        // per-token context instead of falling back to DEFAULT for every
        // meme trade.
        SHITCOIN,        // Sub-cent launch-pad tokens
        MANIPULATED,     // Intentionally risky pumps (TradeAuthorizer bypass pre-V5.9.409)
        CULT,            // Community-coin lane
        NARRATIVE,       // Narrative-momentum lane
        MEME_GENERIC,    // Any other classified-meme token
    }
    
    /**
     * Threshold multipliers for a specific trading mode.
     * Values > 1.0 = more lenient (easier to pass)
     * Values < 1.0 = stricter (harder to pass)
     */
    data class ModeMultipliers(
        val entryScoreMultiplier: Double = 1.0,      // Multiplier for min entry score
        val exitScoreMultiplier: Double = 1.0,       // Multiplier for exit thresholds
        val confidenceMultiplier: Double = 1.0,      // Multiplier for confidence requirements
        val positionSizeMultiplier: Double = 1.0,    // Multiplier for position sizing
        val stopLossMultiplier: Double = 1.0,        // Multiplier for stop loss (higher = tighter)
        val trailingStopMultiplier: Double = 1.0,    // Multiplier for trailing stop
        val minHoldMultiplier: Double = 1.0,         // Multiplier for minimum hold time
        val maxHoldMultiplier: Double = 1.0,         // Multiplier for maximum hold time
        val rugcheckMultiplier: Double = 1.0,        // Multiplier for rugcheck threshold
        val liquidityMultiplier: Double = 1.0,       // Multiplier for liquidity requirements
    ) {
        companion object {
            val DEFAULT = ModeMultipliers()
        }
    }
    
    /**
     * Get multipliers for a specific trading mode.
     * Returns safe defaults if anything goes wrong.
     */
    fun getMultipliers(modeTag: TradingModeTag?): ModeMultipliers {
        return try {
            when (modeTag) {
                TradingModeTag.MOONSHOT -> ModeMultipliers(
                    entryScoreMultiplier = 0.85,      // Lower bar for entry (seeking moonshots)
                    exitScoreMultiplier = 1.3,        // Higher bar for exit (let winners run)
                    confidenceMultiplier = 0.9,       // Slightly lower confidence ok
                    positionSizeMultiplier = 0.7,     // Smaller positions (higher risk)
                    stopLossMultiplier = 0.8,         // Wider stops (expect volatility)
                    trailingStopMultiplier = 0.7,     // Wider trailing (let it run)
                    minHoldMultiplier = 0.5,          // Quick exits ok
                    maxHoldMultiplier = 3.0,          // Hold longer for moon
                    rugcheckMultiplier = 0.9,         // Slightly more lenient
                    liquidityMultiplier = 0.8,        // Accept lower liquidity
                )
                
                TradingModeTag.PUMP_SNIPER -> ModeMultipliers(
                    entryScoreMultiplier = 0.7,       // Very low bar (speed matters)
                    exitScoreMultiplier = 0.9,        // Quick exits
                    confidenceMultiplier = 0.8,       // Lower confidence ok
                    positionSizeMultiplier = 0.5,     // Small positions (very high risk)
                    stopLossMultiplier = 0.7,         // Wide stops initially
                    trailingStopMultiplier = 1.2,     // Tight trailing once profitable
                    minHoldMultiplier = 0.3,          // Very quick exits ok
                    maxHoldMultiplier = 0.5,          // Don't hold long
                    rugcheckMultiplier = 0.7,         // More lenient (risky tokens)
                    liquidityMultiplier = 0.6,        // Accept lower liquidity
                )
                
                TradingModeTag.BLUE_CHIP -> ModeMultipliers(
                    entryScoreMultiplier = 1.3,       // Higher bar for entry
                    exitScoreMultiplier = 0.8,        // Lower bar for exit (preserve gains)
                    confidenceMultiplier = 1.2,       // Higher confidence required
                    positionSizeMultiplier = 1.5,     // Larger positions (lower risk)
                    stopLossMultiplier = 1.3,         // Tighter stops
                    trailingStopMultiplier = 1.2,     // Tighter trailing
                    minHoldMultiplier = 2.0,          // Hold longer
                    maxHoldMultiplier = 5.0,          // Much longer holds
                    rugcheckMultiplier = 1.3,         // Stricter rugcheck
                    liquidityMultiplier = 1.5,        // Higher liquidity required
                )
                
                TradingModeTag.DEFENSIVE -> ModeMultipliers(
                    entryScoreMultiplier = 1.2,       // Higher bar for entry
                    exitScoreMultiplier = 0.85,       // Lower bar for exit
                    confidenceMultiplier = 1.15,      // Higher confidence
                    positionSizeMultiplier = 0.8,     // Smaller positions
                    stopLossMultiplier = 1.3,         // Tight stops
                    trailingStopMultiplier = 1.3,     // Tight trailing
                    minHoldMultiplier = 1.0,
                    maxHoldMultiplier = 1.0,
                    rugcheckMultiplier = 1.2,         // Stricter rugcheck
                    liquidityMultiplier = 1.2,        // Higher liquidity
                )
                
                TradingModeTag.AGGRESSIVE -> ModeMultipliers(
                    entryScoreMultiplier = 0.8,       // Lower bar
                    exitScoreMultiplier = 1.2,        // Higher bar (let run)
                    confidenceMultiplier = 0.85,      // Lower confidence ok
                    positionSizeMultiplier = 1.2,     // Larger positions
                    stopLossMultiplier = 0.8,         // Wider stops
                    trailingStopMultiplier = 0.85,    // Wider trailing
                    minHoldMultiplier = 0.7,
                    maxHoldMultiplier = 1.5,
                    rugcheckMultiplier = 0.9,
                    liquidityMultiplier = 0.9,
                )
                
                TradingModeTag.SNIPE -> ModeMultipliers(
                    entryScoreMultiplier = 0.75,      // Low bar (speed matters)
                    exitScoreMultiplier = 1.0,
                    confidenceMultiplier = 0.8,       // Lower confidence ok
                    positionSizeMultiplier = 0.6,     // Smaller positions
                    stopLossMultiplier = 0.9,
                    trailingStopMultiplier = 1.1,     // Slightly tight trailing
                    minHoldMultiplier = 0.5,          // Quick exits
                    maxHoldMultiplier = 0.7,
                    rugcheckMultiplier = 0.8,
                    liquidityMultiplier = 0.7,
                )
                
                TradingModeTag.COPY_TRADE -> ModeMultipliers(
                    entryScoreMultiplier = 0.9,       // Trust the whale
                    exitScoreMultiplier = 1.1,
                    confidenceMultiplier = 0.9,
                    positionSizeMultiplier = 0.8,     // Match whale proportionally
                    stopLossMultiplier = 1.0,
                    trailingStopMultiplier = 1.0,
                    minHoldMultiplier = 1.0,
                    maxHoldMultiplier = 1.5,
                    rugcheckMultiplier = 0.95,
                    liquidityMultiplier = 0.9,
                )
                
                TradingModeTag.LONG_HOLD -> ModeMultipliers(
                    entryScoreMultiplier = 1.2,       // Higher bar for entry
                    exitScoreMultiplier = 1.4,        // Much higher bar for exit
                    confidenceMultiplier = 1.1,
                    positionSizeMultiplier = 1.0,
                    stopLossMultiplier = 0.7,         // Wide stops
                    trailingStopMultiplier = 0.6,     // Very wide trailing
                    minHoldMultiplier = 3.0,          // Long minimum hold
                    maxHoldMultiplier = 10.0,         // Very long max hold
                    rugcheckMultiplier = 1.1,
                    liquidityMultiplier = 1.0,
                )
                
                TradingModeTag.MICRO_CAP -> ModeMultipliers(
                    entryScoreMultiplier = 0.8,
                    exitScoreMultiplier = 1.1,
                    confidenceMultiplier = 0.85,
                    positionSizeMultiplier = 0.4,     // Very small positions
                    stopLossMultiplier = 0.7,         // Wide stops (volatile)
                    trailingStopMultiplier = 0.8,
                    minHoldMultiplier = 0.5,
                    maxHoldMultiplier = 1.0,
                    rugcheckMultiplier = 0.7,         // More lenient (risky)
                    liquidityMultiplier = 0.5,        // Accept low liquidity
                )
                
                TradingModeTag.WHALE_FOLLOW -> ModeMultipliers(
                    entryScoreMultiplier = 0.85,
                    exitScoreMultiplier = 1.15,
                    confidenceMultiplier = 0.9,
                    positionSizeMultiplier = 0.9,
                    stopLossMultiplier = 1.0,
                    trailingStopMultiplier = 1.0,
                    minHoldMultiplier = 1.0,
                    maxHoldMultiplier = 2.0,
                    rugcheckMultiplier = 0.95,
                    liquidityMultiplier = 1.0,
                )

                // ═══════════════════════════════════════════════════════
                // V5.9.409 — Meme-lane multipliers.
                // Calibrated toward the user's pre-markets proven-meme
                // setup (loose entry bar, wide stops, narrow trailing
                // once profitable, low liquidity tolerance). Each meme
                // sub-lane has slightly different personality so FDG's
                // 24 channels can re-tune per sub-phase.
                // ═══════════════════════════════════════════════════════
                TradingModeTag.SHITCOIN -> ModeMultipliers(
                    entryScoreMultiplier = 0.65,      // very low bar — take the shot
                    exitScoreMultiplier = 0.90,       // quick exits
                    confidenceMultiplier = 0.70,      // low conf ok
                    positionSizeMultiplier = 0.55,    // small sizes, high freq
                    stopLossMultiplier = 0.70,        // wide stops (vol)
                    // V5.9.417 — LOOSENED. Was 1.25 (TIGHT trail) which was
                    // killing trades at -1.9% in 1-2m before they could
                    // breathe. Memes pump-and-pull-back aggressively in
                    // their first few minutes; a tight trail front-runs
                    // the very moves we want to capture.
                    trailingStopMultiplier = 0.70,
                    minHoldMultiplier = 0.30,
                    maxHoldMultiplier = 0.60,
                    rugcheckMultiplier = 0.65,        // lenient — shitcoins ARE risky
                    liquidityMultiplier = 0.50,       // accept thin liquidity
                )

                TradingModeTag.MANIPULATED -> ModeMultipliers(
                    entryScoreMultiplier = 0.70,
                    exitScoreMultiplier = 0.85,
                    confidenceMultiplier = 0.75,
                    positionSizeMultiplier = 0.50,
                    stopLossMultiplier = 0.60,
                    // V5.9.417 — LOOSENED 1.30 → 0.70.
                    trailingStopMultiplier = 0.70,
                    minHoldMultiplier = 0.30,
                    maxHoldMultiplier = 0.50,
                    rugcheckMultiplier = 0.50,        // MANIPULATED book bypasses rugcheck
                    liquidityMultiplier = 0.45,
                )

                TradingModeTag.CULT -> ModeMultipliers(
                    entryScoreMultiplier = 0.78,
                    exitScoreMultiplier = 1.15,       // let community runs breathe
                    confidenceMultiplier = 0.85,
                    positionSizeMultiplier = 0.70,
                    stopLossMultiplier = 0.75,
                    // V5.9.417 — LOOSENED 0.90 → 0.75.
                    trailingStopMultiplier = 0.75,
                    minHoldMultiplier = 1.00,
                    maxHoldMultiplier = 2.00,
                    rugcheckMultiplier = 0.85,
                    liquidityMultiplier = 0.70,
                )

                TradingModeTag.NARRATIVE -> ModeMultipliers(
                    entryScoreMultiplier = 0.80,
                    exitScoreMultiplier = 1.10,
                    confidenceMultiplier = 0.85,
                    positionSizeMultiplier = 0.75,
                    stopLossMultiplier = 0.80,
                    // V5.9.417 — LOOSENED 0.95 → 0.80.
                    trailingStopMultiplier = 0.80,
                    minHoldMultiplier = 0.80,
                    maxHoldMultiplier = 1.50,
                    rugcheckMultiplier = 0.85,
                    liquidityMultiplier = 0.75,
                )

                TradingModeTag.MEME_GENERIC -> ModeMultipliers(
                    entryScoreMultiplier = 0.75,
                    exitScoreMultiplier = 1.00,
                    confidenceMultiplier = 0.80,
                    positionSizeMultiplier = 0.65,
                    stopLossMultiplier = 0.75,
                    // V5.9.417 — LOOSENED 1.10 → 0.75.
                    trailingStopMultiplier = 0.75,
                    minHoldMultiplier = 0.50,
                    maxHoldMultiplier = 1.00,
                    rugcheckMultiplier = 0.80,
                    liquidityMultiplier = 0.65,
                )

                TradingModeTag.RANGE, TradingModeTag.STANDARD, null -> ModeMultipliers.DEFAULT
            }
        } catch (e: Exception) {
            // Ultimate safety - never crash
            ErrorLogger.warn(TAG, "getMultipliers failed for $modeTag: ${e.message}")
            ModeMultipliers.DEFAULT
        }
    }
    
    /**
     * V5.9.409 — per-token mapper. Priority over fromBotMode() when the
     * token has a specific lane / sub-phase set. Returns null if the
     * string doesn't match a known tag, letting callers fall back to
     * the global bot-mode mapper.
     */
    fun fromTradingMode(tradingMode: String?): TradingModeTag? {
        if (tradingMode.isNullOrBlank()) return null
        val m = tradingMode.trim().uppercase()
        return when {
            m.startsWith("MOONSHOT")         -> TradingModeTag.MOONSHOT
            m == "SHITCOIN"                   -> TradingModeTag.SHITCOIN
            m == "MANIPULATED"                -> TradingModeTag.MANIPULATED
            m == "CULT"                       -> TradingModeTag.CULT
            m == "NARRATIVE"                  -> TradingModeTag.NARRATIVE
            m == "MEME" || m == "MEME_GENERIC" -> TradingModeTag.MEME_GENERIC
            m == "MICRO_CAP"                  -> TradingModeTag.MICRO_CAP
            m == "PUMP_SNIPER"                -> TradingModeTag.PUMP_SNIPER
            m == "BLUE_CHIP" || m == "BLUECHIP" -> TradingModeTag.BLUE_CHIP
            m == "LONG_HOLD"                  -> TradingModeTag.LONG_HOLD
            m == "DEFENSIVE"                  -> TradingModeTag.DEFENSIVE
            m == "AGGRESSIVE"                 -> TradingModeTag.AGGRESSIVE
            m == "SNIPE"                      -> TradingModeTag.SNIPE
            m == "RANGE"                      -> TradingModeTag.RANGE
            m == "WHALE_FOLLOW"               -> TradingModeTag.WHALE_FOLLOW
            m == "COPY_TRADE" || m == "COPY"  -> TradingModeTag.COPY_TRADE
            else -> null
        }
    }

    /**
     * Convert AutoModeEngine.BotMode to TradingModeTag.
     * Safe conversion with default fallback.
     */
    fun fromBotMode(botMode: AutoModeEngine.BotMode?): TradingModeTag {
        return try {
            when (botMode) {
                AutoModeEngine.BotMode.SNIPE -> TradingModeTag.SNIPE
                AutoModeEngine.BotMode.RANGE -> TradingModeTag.RANGE
                AutoModeEngine.BotMode.AGGRESSIVE -> TradingModeTag.AGGRESSIVE
                AutoModeEngine.BotMode.DEFENSIVE -> TradingModeTag.DEFENSIVE
                AutoModeEngine.BotMode.COPY -> TradingModeTag.COPY_TRADE
                AutoModeEngine.BotMode.PAUSED -> TradingModeTag.STANDARD
                null -> TradingModeTag.STANDARD
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "fromBotMode failed: ${e.message}")
            TradingModeTag.STANDARD
        }
    }
    
    /**
     * Apply multipliers to adjust a threshold value.
     * 
     * @param baseValue The original threshold value
     * @param multiplier The multiplier to apply
     * @param isMinThreshold If true, lower values are stricter. If false, higher values are stricter.
     * @return Adjusted threshold value
     */
    fun applyMultiplier(baseValue: Double, multiplier: Double, isMinThreshold: Boolean = true): Double {
        return try {
            if (isMinThreshold) {
                // For min thresholds: higher multiplier = higher threshold = stricter
                baseValue * multiplier
            } else {
                // For max thresholds: higher multiplier = higher threshold = more lenient
                baseValue * multiplier
            }
        } catch (e: Exception) {
            baseValue
        }
    }
}
