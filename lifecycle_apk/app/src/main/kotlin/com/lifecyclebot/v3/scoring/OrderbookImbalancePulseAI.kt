package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — OrderbookImbalancePulseAI
 *
 * Tight-window bid/ask imbalance pulses. DexScreener doesn't expose a full
 * orderbook, but we can proxy via buyPressurePct deltas over 30-second
 * windows. A sudden buy-side spike (+25 pts in 30s) is a tradeable pulse;
 * a sudden sell-side spike is a dump signal.
 *
 * Exit emission: a -25pt pulse on an already-open position triggers a
 * pre-TP partial sell (hooked in Executor via getRecentPulse()).
 */
object OrderbookImbalancePulseAI {

    private const val TAG = "OBPulse"
    private const val WINDOW_MS = 30_000L
    private const val MAX_SAMPLES = 60

    private data class Sample(val ts: Long, val buyPressurePct: Double)

    private val history = ConcurrentHashMap<String, ArrayDeque<Sample>>()

    fun pushTick(mint: String, buyPressurePct: Double) {
        val dq = history.getOrPut(mint) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(Sample(System.currentTimeMillis(), buyPressurePct))
            while (dq.size > MAX_SAMPLES) dq.removeFirst()
        }
    }

    /** Positive = buy-side spike, negative = sell-side spike. pts over WINDOW_MS. */
    fun getRecentPulse(mint: String): Double {
        val dq = history[mint] ?: return 0.0
        synchronized(dq) {
            val now = System.currentTimeMillis()
            val recent = dq.firstOrNull { (now - it.ts) <= WINDOW_MS } ?: return 0.0
            val latest = dq.lastOrNull() ?: return 0.0
            return latest.buyPressurePct - recent.buyPressurePct
        }
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val pulse = getRecentPulse(candidate.mint)
        val value = when {
            pulse >  25.0 -> +5
            pulse >  10.0 -> +2
            pulse < -25.0 -> -6
            pulse < -10.0 -> -2
            else          -> 0
        }
        return ScoreComponent("OrderbookImbalancePulseAI", value,
            "📊 pulse=${"%.1f".format(pulse)}pts/30s")
    }
}
