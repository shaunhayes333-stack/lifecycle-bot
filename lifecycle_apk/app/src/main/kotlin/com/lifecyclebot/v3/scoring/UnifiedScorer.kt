package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Unified Scorer
 * Orchestrates all AI scoring modules
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
    private val copyTradeAI: CopyTradeAI = CopyTradeAI()
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
                copyTradeAI.score(candidate, ctx)
            )
        )
    }
    
    /**
     * Get list of all module names
     */
    fun moduleNames(): List<String> = listOf(
        "source", "entry", "momentum", "liquidity", "volume",
        "holders", "narrative", "memory", "regime", "time", "copytrade"
    )
}
