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

    /** V5.9.362 — wiring health: total samples across all tracked mints (floor 3). */
    fun getWiringHealth(): Triple<Int, Int, Boolean> {
        val total = history.values.sumOf { dq -> synchronized(dq) { dq.size } }
        return Triple(total, 3, total >= 3)
    }

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
        // V5.9.357 — push the current tick into history at scoring time. The
        // scanner is intentionally untouched; instead, every scoring pass
        // (which happens on every candidate refresh anyway) builds the rolling
        // 30-second buyPressure window in-place. First call yields pulse=0
        // (no prior sample), subsequent calls produce the real delta.
        try { pushTick(candidate.mint, candidate.buyPressurePct) } catch (_: Exception) {}

        val pulse = getRecentPulse(candidate.mint)
        // V5.9.357 — warmup mute: until this mint has at least 3 samples in
        // the 30s window, suppress the vote so a single first-tick spike
        // can't sway entry decisions on cold history.
        val dq = history[candidate.mint]
        val samples = if (dq == null) 0 else synchronized(dq) { dq.size }
        if (samples < 3) {
            return ScoreComponent("OrderbookImbalancePulseAI", 0,
                "📊 warming ($samples/3 samples)")
        }
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
