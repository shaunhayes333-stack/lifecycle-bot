package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.123 — NewsShockAI
 *
 * LLM-summarized news/twitter sentiment delta over 15-minute windows.
 * Uses the existing Gemini/OpenAI text path (GeminiCopilot) when a news
 * feed tick arrives. A spike (sentiment slope > +0.4 over 15m) biases
 * entries positively; a crash (slope < -0.4) biases negatively AND triggers
 * exit consideration in Executor (via a hook in the exit path).
 *
 * This file only holds the shared state + scoring. The poller lives in
 * BotService and feeds updateFromPoll() with a float summary score from
 * the LLM.
 */
object NewsShockAI {

    private const val TAG = "NewsShock"

    data class SentimentSnapshot(val score: Double, val slope15m: Double, val capturedAtMs: Long)

    private val snap = AtomicReference<SentimentSnapshot?>(null)

    /** V5.9.362 — wiring health: 1 if a sentiment snapshot has been captured. */
    fun getWiringHealth(): Triple<Int, Int, Boolean> {
        val ready = snap.get() != null
        return Triple(if (ready) 1 else 0, 1, ready)
    }

    fun updateFromPoll(score: Double, slope15m: Double) {
        snap.set(SentimentSnapshot(score.coerceIn(-1.0, 1.0), slope15m.coerceIn(-2.0, 2.0), System.currentTimeMillis()))
        ErrorLogger.info(TAG, "📰 sentiment=${"%.2f".format(score)} slope15m=${"%.2f".format(slope15m)}")
    }

    fun getSlope(): Double = snap.get()?.slope15m ?: 0.0

    fun score(@Suppress("UNUSED_PARAMETER") candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val s = snap.get() ?: return ScoreComponent("NewsShockAI", 0, "📰 no news data")
        val slope = s.slope15m
        val value = when {
            slope >  0.6 -> +6
            slope >  0.2 -> +2
            slope < -0.6 -> -10
            slope < -0.2 -> -4
            else         -> 0
        }
        return ScoreComponent("NewsShockAI", value,
            "📰 sent=${"%.2f".format(s.score)} slope=${"%.2f".format(slope)}")
    }
}
