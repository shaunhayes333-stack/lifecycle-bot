package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.arb.ArbScannerAI
import com.lifecyclebot.v3.arb.ArbEvaluation

/**
 * V3 Unified Scorer
 * Orchestrates all AI scoring modules
 * 
 * V3 MIGRATION: Added SuppressionAI to convert legacy invalidations
 * (COPY_TRADE_INVALIDATION, WHALE_ACCUMULATION_INVALIDATION, etc.)
 * into score penalties instead of hard blocks.
 * 
 * V3.1 EXPANSION: Added FearGreedAI and SocialVelocityAI
 * - FearGreedAI: Uses Alternative.me free API for market sentiment
 * - SocialVelocityAI: Uses DexScreener boosted tokens for social velocity
 * 
 * V3.2 EXPANSION: Added ArbScannerAI integration
 * - Parallel arb lane for short-horizon mispricing capture
 * - Three arb types: VENUE_LAG, FLOW_IMBALANCE, PANIC_REVERSION
 */
class UnifiedScorer(
    private val entryAI: EntryAI = EntryAI(),
    private val momentumAI: MomentumAI = MomentumAI(),
    private val liquidityAI: LiquidityAI = LiquidityAI(),
    private val volumeAI: VolumeProfileAI = VolumeProfileAI(),
    private val holderAI: HolderSafetyAI = HolderSafetyAI(),
    private val narrativeAI: NarrativeAI = NarrativeAI(),
    private val memoryAI: MemoryAI = MemoryAI(),
    private val regimeAI: MarketRegimeAI = MarketRegimeAI(),
    private val timeAI: TimeAI = TimeAI(),
    private val copyTradeAI: CopyTradeAI = CopyTradeAI(),
    private val suppressionAI: SuppressionAI = SuppressionAI(),  // V3 MIGRATION: Legacy suppression as penalty
    private val fearGreedAI: FearGreedAI = FearGreedAI(),        // V3.1: Market sentiment from Alternative.me
    private val socialVelocityAI: SocialVelocityAI = SocialVelocityAI()  // V3.1: Social momentum detection
) {
    /**
     * Score a candidate through all AI modules
     * Returns aggregated ScoreCard
     */
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        return ScoreCard(
            listOf(
                sourceScore(candidate.source),
                entryAI.score(candidate, ctx),
                momentumAI.score(candidate, ctx),
                liquidityAI.score(candidate, ctx),
                volumeAI.score(candidate, ctx),
                holderAI.score(candidate, ctx),
                narrativeAI.score(candidate, ctx),
                memoryAI.score(candidate, ctx),
                regimeAI.score(candidate, ctx),
                timeAI.score(candidate, ctx),
                copyTradeAI.score(candidate, ctx),
                suppressionAI.score(candidate, ctx),  // V3 MIGRATION: Converts legacy blocks to penalties
                fearGreedAI.score(candidate, ctx),    // V3.1: Fear & Greed Index
                socialVelocityAI.score(candidate, ctx)  // V3.1: Social velocity detection
            )
        )
    }
    
    /**
     * Score a candidate AND evaluate for arb opportunities.
     * Returns pair of (ScoreCard, ArbEvaluation?)
     * 
     * Use this for parallel arb lane processing.
     */
    fun scoreWithArb(candidate: CandidateSnapshot, ctx: TradingContext): Pair<ScoreCard, ArbEvaluation?> {
        // Record source timing for arb tracking
        ArbScannerAI.recordSourceSeen(candidate)
        
        // Run normal scoring
        val scoreCard = score(candidate, ctx)
        
        // Run arb evaluation in parallel
        val arbEval = try {
            ArbScannerAI.evaluate(candidate, ctx)
        } catch (e: Exception) {
            null  // Silently ignore arb errors
        }
        
        return Pair(scoreCard, arbEval)
    }
    
    /**
     * Get list of all module names
     */
    fun moduleNames(): List<String> = listOf(
        "source", "entry", "momentum", "liquidity", "volume",
        "holders", "narrative", "memory", "regime", "time", 
        "copytrade", "suppression", "feargreed", "social"
    )
}
