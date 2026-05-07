package com.lifecyclebot.v3.core

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.ScoreComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.93 — rolling per-layer health tracker for V3 scoring.
 *
 * Problem we're diagnosing: the V3 total score is capped around 30 on almost
 * every token because 12+ of 22 scoring layers consistently return 0. Without
 * a per-layer breakdown we can't tell whether a layer is (a) data-starved,
 * (b) intentionally neutral on this token type, or (c) broken.
 *
 * This tracker keeps rolling counters of "zero vs non-zero emits" per layer,
 * plus the most recent reason string for each zero. Every N scored tokens it
 * dumps a summary at INFO so the health stats are visible in the log without
 * spamming per-token.
 */
object LayerHealthTracker {

    private const val SUMMARY_EVERY = 20   // log a summary every 20 scored tokens

    private data class Stats(
        val zeroCount: AtomicLong = AtomicLong(0),
        val nonZeroCount: AtomicLong = AtomicLong(0),
        @Volatile var lastZeroReason: String = "",
        @Volatile var lastNonZeroValue: Int = 0,
        @Volatile var lastNonZeroReason: String = ""
    )

    private val stats = ConcurrentHashMap<String, Stats>()
    private val evalCount = AtomicLong(0)

    fun record(components: List<ScoreComponent>) {
        components.forEach { c ->
            val s = stats.getOrPut(c.name) { Stats() }
            if (c.value == 0) {
                s.zeroCount.incrementAndGet()
                if (c.reason.isNotBlank()) s.lastZeroReason = c.reason
            } else {
                s.nonZeroCount.incrementAndGet()
                s.lastNonZeroValue = c.value
                if (c.reason.isNotBlank()) s.lastNonZeroReason = c.reason
            }
        }

        val n = evalCount.incrementAndGet()
        if (n % SUMMARY_EVERY == 0L) {
            logSummary(n)
        }
    }

    private fun logSummary(n: Long) {
        // V5.9.495z34 — bridge each summary into DeadAILayerFilter.
        // Layers tagged DISABLED_NOT_APPLICABLE (FundingRateAwarenessAI
        // on spot memes, NewsShockAI when no macro feed configured,
        // OperatorFingerprintAI when no creator data, etc.) are
        // surfaced with that label instead of "DEAD" so the FDG
        // normalisation can ignore them rather than be poisoned by
        // them. Operator brief item 3.
        val notApplicable = setOf(
            "FundingRateAwarenessAI",       // spot memes have no funding rate
            "NewsShockAI",                  // poll returns flat sentiment most of the time
            "OperatorFingerprintAI",        // many tokens have no resolvable creator
            "ExecutionCostPredictorAI",     // emits 0 once liquidity tier is uniform
            "OrderbookImbalancePulseAI",    // emits 0 when book is too thin
            "CapitalEfficiencyAI",          // emits 0 with no PnL/SOL·h history
        )
        for (layer in notApplicable) {
            try { com.lifecyclebot.engine.DeadAILayerFilter.markNotApplicable(layer) }
            catch (_: Throwable) { /* best-effort */ }
        }

        val lines = stats.entries
            .sortedBy { it.key }
            .map { (name, s) ->
                val z = s.zeroCount.get()
                val nz = s.nonZeroCount.get()
                val total = (z + nz).coerceAtLeast(1)
                val zeroPct = (z * 100.0 / total).toInt()
                // V5.9.495z34 — feed contributions into DeadAILayerFilter
                // so its rolling stats stay in sync with the summary.
                try {
                    val zerosToRecord = (z - (z / 2)).coerceAtLeast(0)
                    val nonzerosToRecord = (nz - (nz / 2)).coerceAtLeast(0)
                    repeat(zerosToRecord.toInt().coerceAtMost(5)) {
                        com.lifecyclebot.engine.DeadAILayerFilter.recordContribution(name, 0.0)
                    }
                    repeat(nonzerosToRecord.toInt().coerceAtMost(5)) {
                        com.lifecyclebot.engine.DeadAILayerFilter.recordContribution(name, 1.0)
                    }
                } catch (_: Throwable) { /* best-effort */ }

                val health = try {
                    com.lifecyclebot.engine.DeadAILayerFilter.health(name)
                } catch (_: Throwable) { null }
                val flag = when {
                    health == com.lifecyclebot.engine.DeadAILayerFilter.LayerHealth.DISABLED_NOT_APPLICABLE
                                  -> "🟦 DISABLED_NOT_APPLICABLE"
                    zeroPct >= 95 -> "🚨 DEAD"
                    zeroPct >= 80 -> "⚠️ STARVED"
                    zeroPct >= 50 -> "🟡 SPARSE"
                    else          -> "✅ HEALTHY"
                }
                val hint = if (zeroPct >= 80 && s.lastZeroReason.isNotBlank()) {
                    " · zeroReason=\"${s.lastZeroReason.take(60)}\""
                } else if (zeroPct < 80 && s.lastNonZeroReason.isNotBlank()) {
                    " · lastVal=${s.lastNonZeroValue} \"${s.lastNonZeroReason.take(50)}\""
                } else ""
                "  $flag $name  z=$z nz=$nz (${zeroPct}% zero)$hint"
            }
        ErrorLogger.info(
            "V3|LAYERHEALTH",
            "after $n evals\n" + lines.joinToString("\n")
        )
    }

    fun reset() {
        stats.clear()
        evalCount.set(0)
    }
}
