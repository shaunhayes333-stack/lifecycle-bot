package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Entry AI
 * Buy pressure, RSI, momentum signals
 */
class EntryAI : ScoringModule {
    override val name = "entry"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        // Buy pressure
        when {
            candidate.buyPressurePct >= 70 -> { score += 8; reasons += "Strong buy pressure" }
            candidate.buyPressurePct >= 60 -> { score += 4; reasons += "Good buy pressure" }
            candidate.buyPressurePct < 35 -> { score -= 8; reasons += "Weak buy pressure" }
        }
        
        // Technical signals from extra
        if (candidate.extraBoolean("rsiOversold")) { score += 5; reasons += "RSI bounce setup" }
        if (candidate.extraBoolean("momentumUp")) { score += 4; reasons += "Momentum rising" }
        if (candidate.extraBoolean("higherLows")) { score += 3; reasons += "Higher lows forming" }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-20, 20),
            reason = reasons.ifEmpty { listOf("Neutral entry") }.joinToString(", ")
        )
    }
}

/**
 * V3 Momentum AI
 * Pump detection and momentum strength
 */
class MomentumAI : ScoringModule {
    override val name = "momentum"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        if (candidate.extraBoolean("pumpBuilding")) { score += 10; reasons += "Pump building" }
        if (candidate.extraBoolean("momentumUp")) { score += 4; reasons += "Momentum up" }
        if (candidate.extraBoolean("momentumWeak")) { score -= 8; reasons += "Momentum weak" }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-15, 15),
            reason = reasons.ifEmpty { listOf("Neutral momentum") }.joinToString(", ")
        )
    }
}

/**
 * V3 Liquidity AI
 * LP health and draining detection
 */
class LiquidityAI : ScoringModule {
    override val name = "liquidity"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        val draining = candidate.extraBoolean("liquidityDraining")
        val phase = candidate.extraString("phase")
        val volumeExpanding = candidate.extraBoolean("volumeExpanding")
        
        // Liquidity level scoring
        when {
            candidate.liquidityUsd >= 40_000 -> { score += 8; reasons += "Strong liquidity base" }
            candidate.liquidityUsd >= 15_000 -> { score += 5; reasons += "Good liquidity" }
            candidate.liquidityUsd < 3_000 -> { score -= 8; reasons += "Thin liquidity" }
        }
        
        // Draining penalty (contextual)
        if (draining) {
            val penalty = when {
                phase.contains("pre", true) && volumeExpanding -> 2  // Early + volume = OK
                phase.contains("early", true) && volumeExpanding -> 3
                else -> 8  // Late stage draining = bad
            }
            score -= penalty
            reasons += "Liquidity draining"
        }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-15, 10),
            reason = reasons.ifEmpty { listOf("Neutral liquidity") }.joinToString(", ")
        )
    }
}

/**
 * V3 Volume Profile AI
 * Accumulation and distribution detection
 */
class VolumeProfileAI : ScoringModule {
    override val name = "volume"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        if (candidate.extraBoolean("accumulationAtVal")) { score += 8; reasons += "Accumulation at value" }
        if (candidate.extraBoolean("volumeExpanding")) { score += 5; reasons += "Volume expanding" }
        if (candidate.extraBoolean("sellCluster")) { score -= 8; reasons += "Sell cluster detected" }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-10, 15),
            reason = reasons.ifEmpty { listOf("Neutral volume") }.joinToString(", ")
        )
    }
}

/**
 * V3 Holder Safety AI
 * Concentration and bundle detection
 */
class HolderSafetyAI : ScoringModule {
    override val name = "holders"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        val topHolder = candidate.topHolderPct ?: 0.0
        val bundled = candidate.bundledPct ?: 0.0
        val holderCount = candidate.holders ?: 0
        
        // Holder count - V3.2: Less punishing for missing data on fresh tokens
        when {
            holderCount == 0 -> { 
                // Don't punish harshly for missing data - it's often just not loaded yet
                score -= 2  // Was -10, now -2
                reasons += "Holder data pending" 
            }
            holderCount > 80 -> { score += 4; reasons += "Healthy holder spread" }
            holderCount > 30 -> { score += 2; reasons += "Growing holder base" }
        }
        
        // Top holder concentration
        when {
            topHolder >= 20 -> { score -= 12; reasons += "Top holder concentration high" }
            topHolder >= 10 -> { score -= 6; reasons += "Top holder moderately high" }
            topHolder in 0.1..5.0 -> { score += 3; reasons += "Top holder acceptable" }
        }
        
        // Bundle concentration
        when {
            bundled >= 25 -> { score -= 10; reasons += "Heavy bundle concentration" }
            bundled >= 10 -> { score -= 5; reasons += "Moderate bundle concentration" }
        }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-20, 10),
            reason = reasons.ifEmpty { listOf("Neutral holder safety") }.joinToString(", ")
        )
    }
}

/**
 * V3 Narrative AI
 * Identity signals and naming patterns
 */
class NarrativeAI : ScoringModule {
    override val name = "narrative"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        if (candidate.hasIdentitySignals) {
            score += 6
            reasons += "Has identity signals"
        } else {
            score -= 2
            reasons += "No identity"
        }
        
        if (candidate.extraBoolean("suspiciousName")) {
            score -= 3
            reasons += "Suspicious naming pattern"
        }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-5, 10),
            reason = reasons.joinToString(", ")
        )
    }
}

/**
 * V3 Memory AI
 * Historical pattern matching
 */
class MemoryAI : ScoringModule {
    override val name = "memory"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val memoryScore = candidate.extraInt("memoryScore").coerceIn(-15, 10)
        
        val reason = when {
            memoryScore >= 6 -> "Strong positive analogs"
            memoryScore > 0 -> "Positive analogs"
            memoryScore < -8 -> "Strong negative analogs"
            memoryScore < 0 -> "Negative analogs"
            else -> "No memory edge"
        }
        
        return ScoreComponent(name = name, value = memoryScore, reason = reason)
    }
}

/**
 * V3 Market Regime AI
 * Bull/Bear/Crab context
 */
class MarketRegimeAI : ScoringModule {
    override val name = "regime"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val phase = candidate.extraString("phase")
        
        val value = when {
            ctx.marketRegime.equals("BULL", true) && phase.contains("pre", true) -> 8
            ctx.marketRegime.equals("BULL", true) -> 4
            ctx.marketRegime.equals("BEAR", true) -> -6
            else -> 1
        }
        
        return ScoreComponent(
            name = name,
            value = value.coerceIn(-10, 10),
            reason = "Regime ${ctx.marketRegime}"
        )
    }
}

/**
 * V3 Time AI
 * Time-of-day edge
 */
class TimeAI : ScoringModule {
    override val name = "time"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        val hour = ((ctx.clockMs / 1000 / 3600) % 24).toInt()
        
        val value = when (hour) {
            in 0..5 -> -2   // Dead hours
            in 6..11 -> 2   // Asia/Europe
            in 12..18 -> 3  // US hours
            else -> 1       // Evening
        }
        
        return ScoreComponent(
            name = name,
            value = value.coerceIn(-5, 5),
            reason = "Hour=$hour"
        )
    }
}

/**
 * V3 Copy Trade AI
 * Stale/crowded pattern penalties
 */
class CopyTradeAI : ScoringModule {
    override val name = "copytrade"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        var score = 0
        val reasons = mutableListOf<String>()
        
        if (candidate.extraBoolean("copyTradeStale")) {
            score -= 8
            reasons += "Stale copy-trade pattern"
        }
        
        if (candidate.extraBoolean("copyTradeCrowded")) {
            score -= 4
            reasons += "Crowded setup"
        }
        
        return ScoreComponent(
            name = name,
            value = score.coerceIn(-10, 5),
            reason = reasons.ifEmpty { listOf("No copy-trade penalty") }.joinToString(", ")
        )
    }
}

/**
 * V3 Suppression AI
 * 
 * Converts legacy suppressions (COPY_TRADE_INVALIDATION, WHALE_ACCUMULATION_INVALIDATION,
 * STOP_LOSS, DISTRIBUTION) into score penalties instead of hard blocks.
 * 
 * This is the key component of the V3 migration - what was once a kill switch
 * is now a weighted penalty that V3 can evaluate alongside other signals.
 */
class SuppressionAI : ScoringModule {
    override val name = "suppression"
    
    override fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        // Get penalty from legacy suppression system
        val penalty = try {
            com.lifecyclebot.engine.DistributionFadeAvoider.getSuppressionPenalty(candidate.mint)
        } catch (e: Exception) {
            0
        }
        
        if (penalty == 0) {
            return ScoreComponent(
                name = name,
                value = 0,
                reason = "No suppression"
            )
        }
        
        val reason = when {
            penalty >= 25 -> "Recent stop-loss/distribution"
            penalty >= 20 -> "Whale invalidation penalty"
            penalty >= 15 -> "Copy-trade invalidation penalty"
            else -> "Minor suppression cooldown"
        }
        
        return ScoreComponent(
            name = name,
            value = -penalty,  // Negative = penalty
            reason = "$reason (-$penalty)"
        )
    }
}
