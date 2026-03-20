package com.lifecyclebot.data

/**
 * Sentiment result for a single token from all sources combined.
 *
 * score:     -100 to +100  (negative = bearish, positive = bullish)
 * confidence: 0 to 100     (how much data we have to back the score)
 * blocked:   true if hard block signal detected (rug/scam keywords)
 */
data class SentimentResult(
    val score: Double = 0.0,
    val confidence: Double = 0.0,
    val blocked: Boolean = false,
    val blockReason: String = "",
    // breakdown by source
    val xScore: Double = 0.0,
    val xMentions: Int = 0,
    val xVelocity: Double = 0.0,   // mentions per minute (recent)
    val telegramScore: Double = 0.0,
    val telegramMentions: Int = 0,
    val telegramVelocity: Double = 0.0,
    // wallet concentration (0-100, 100 = completely concentrated)
    val walletConcentration: Double = 0.0,
    val concentrationBlocked: Boolean = false,
    // price/mention divergence flag
    val divergenceSignal: Boolean = false,
    val summary: String = "",
    val updatedAt: Long = 0L,
) {
    /** How stale this data is in minutes */
    val ageMins: Double get() = (System.currentTimeMillis() - updatedAt) / 60_000.0

    /**
     * Decayed score — positive sentiment older than 15 min loses value quickly.
     * Negative sentiment persists longer (bad news travels slowly).
     */
    val decayedScore: Double get() {
        if (updatedAt == 0L) return 0.0
        return when {
            score > 0 -> {
                val decay = (1.0 - (ageMins / 15.0)).coerceIn(0.0, 1.0)
                score * decay
            }
            score < 0 -> {
                // Negative sentiment decays over 30 min
                val decay = (1.0 - (ageMins / 30.0)).coerceIn(0.3, 1.0)
                score * decay
            }
            else -> 0.0
        }
    }

    val isStale get() = ageMins > 20.0
}

/** Rolling mention history for velocity calculation */
data class MentionEvent(
    val source: String,    // "x" | "telegram"
    val ts: Long,
    val sentiment: Double, // -1 to 1
    val text: String,
)
