package com.lifecyclebot.engine

/**
 * V5.0.8366 §PAPER_NEVER_SIZES_LIVE — SmartSizer's live performance multiplier read FluidLearning's paper-trained win rate
 * while live had fewer than 10 trades (V5.9.737; capped shrink-only by LiveConfidenceSizing8296 in 8296, so a weak paper
 * record still shrank a live order). Paper and shadow results never size live: until [MIN_LIVE_CLOSES_8366] live closes the
 * multiplier is neutral; live's own context (streaks, recent win rate) decides after.
 */
object LivePerf8366 {
    const val MIN_LIVE_CLOSES_8366 = 10
    const val NEUTRAL_8366 = 1.0

    /** Pure: is live still too thin (fewer than [MIN_LIVE_CLOSES_8366] closes) for its own performance multiplier? */
    fun thinLive8366(liveTrades: Int): Boolean = liveTrades < MIN_LIVE_CLOSES_8366

    /** Pure: the live performance multiplier while live is thin — neutral, whatever paper says. */
    fun thinLiveMult8366(@Suppress("UNUSED_PARAMETER") liveTrades: Int): Double = NEUTRAL_8366
}
