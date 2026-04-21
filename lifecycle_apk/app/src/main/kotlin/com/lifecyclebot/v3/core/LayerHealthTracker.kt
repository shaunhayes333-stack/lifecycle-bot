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

    private const val SUMMARY_EVERY = 50   // log a summary every 50 tokens

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
        val lines = stats.entries
            .sortedBy { it.key }
            .map { (name, s) ->
                val z = s.zeroCount.get()
                val nz = s.nonZeroCount.get()
                val total = (z + nz).coerceAtLeast(1)
                val zeroPct = (z * 100.0 / total).toInt()
                val flag = when {
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
