package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.123 — DrawdownCircuitAI
 *
 * Layer-level continuous drawdown tracking + graduated aggression dial.
 * The existing LiveSafetyCircuitBreaker is binary (halts trading at -10%
 * session drawdown). This is a gradient — it dials aggression DOWN
 * smoothly as paper drawdown grows within a rolling 4-hour window.
 *
 *   0-3% rolling DD    → aggression 1.0  (normal)
 *   3-6% rolling DD    → aggression 0.75 (trim TP by 25%, score floor +3)
 *   6-10% rolling DD   → aggression 0.50 (trim TP by 50%, score floor +7)
 *   10%+ rolling DD    → aggression 0.25 (nearly defensive only)
 *
 * Consumed by UnifiedScorer as a score floor bump, and by Executor when
 * sizing new trades.
 */
object DrawdownCircuitAI {

    private const val TAG = "DDCircuit"
    private const val WINDOW_MS = 4L * 3600_000L   // 4 hours

    private data class PnlSample(val ts: Long, val balance: Double)

    private val samples = ArrayDeque<PnlSample>()
    private val currentAggression = AtomicReference(1.0)

    @Synchronized
    fun recordBalance(balanceSol: Double) {
        val now = System.currentTimeMillis()
        samples.addLast(PnlSample(now, balanceSol))
        while (samples.isNotEmpty() && (now - samples.first().ts) > WINDOW_MS) {
            samples.removeFirst()
        }
        recompute()
    }

    @Synchronized
    private fun recompute() {
        if (samples.size < 2) return
        val peak = samples.maxOf { it.balance }
        val current = samples.last().balance
        if (peak <= 0.0) return
        val ddPct = ((peak - current) / peak * 100.0).coerceAtLeast(0.0)
        val aggression = when {
            ddPct < 3.0  -> 1.0
            ddPct < 6.0  -> 0.75
            ddPct < 10.0 -> 0.50
            else         -> 0.25
        }
        val prev = currentAggression.getAndSet(aggression)
        if (kotlin.math.abs(prev - aggression) > 0.01) {
            ErrorLogger.info(TAG, "📉 4h DD=${"%.1f".format(ddPct)}%% → aggression=${"%.2f".format(aggression)}")
        }
    }

    fun getAggression(): Double = currentAggression.get()

    fun score(@Suppress("UNUSED_PARAMETER") candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val agg = getAggression()
        val value = when {
            agg >= 0.95 -> 0
            agg >= 0.70 -> -2
            agg >= 0.40 -> -5
            else        -> -10
        }
        return ScoreComponent("DrawdownCircuitAI", value,
            "📉 aggression=${"%.2f".format(agg)} (gate=$value)")
    }
}
