package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.v3.scoring.ScoreComponent
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.3807 — Read-only AI stack snapshot ring.
 *
 * Purpose:
 * - Capture the specialist/component vector that already exists at scoring time.
 * - Give future MoE/counterfactual work a stable observation substrate.
 * - Preserve throughput: bounded ring, no disk writes, no network/LLM/API calls,
 *   no influence on score, FDG, sizing, executor, or learning labels.
 *
 * Mux doctrine:
 * - Snapshot carries source/lane/mode when available.
 * - Unknown values stay explicit instead of borrowing current UI/runtime state.
 */
object AIStackSnapshot {
    data class ComponentView(
        val name: String,
        val value: Int,
        val fatal: Boolean,
        val reason: String,
    )

    data class Snapshot(
        val tsMs: Long,
        val mint: String,
        val symbol: String,
        val source: String,
        val lane: String,
        val mode: String,
        val regime: String,
        val scoreTotal: Int,
        val fatalCount: Int,
        val positiveCount: Int,
        val negativeCount: Int,
        val components: List<ComponentView>,
    )

    private const val RING_CAP = 96
    private val ring = ConcurrentLinkedDeque<Snapshot>()
    private val ringSize = AtomicInteger(0)

    fun recordScoreCard(
        mint: String,
        symbol: String,
        source: String = "UNKNOWN",
        lane: String = "UNKNOWN",
        mode: String = "UNKNOWN",
        regime: String = "UNKNOWN",
        card: ScoreCard,
        extraComponents: List<ScoreComponent> = emptyList(),
    ) {
        try {
            val all = (card.components + extraComponents)
                .asSequence()
                .filter { it.name.isNotBlank() }
                .take(64)
                .map {
                    ComponentView(
                        name = it.name,
                        value = it.value,
                        fatal = it.fatal,
                        reason = it.reason.take(96),
                    )
                }
                .toList()
            val snap = Snapshot(
                tsMs = System.currentTimeMillis(),
                mint = mint,
                symbol = symbol,
                source = source.ifBlank { "UNKNOWN" },
                lane = lane.ifBlank { "UNKNOWN" },
                mode = mode.ifBlank { "UNKNOWN" },
                regime = regime.ifBlank { "UNKNOWN" },
                scoreTotal = card.total,
                fatalCount = all.count { it.fatal },
                positiveCount = all.count { it.value > 0 },
                negativeCount = all.count { it.value < 0 },
                components = all,
            )
            ring.addLast(snap)
            if (ringSize.incrementAndGet() > RING_CAP) {
                ring.pollFirst()
                ringSize.decrementAndGet()
            }
            try { PipelineHealthCollector.labelInc("AI_STACK_SNAPSHOT") } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // Telemetry must never affect scoring/entry.
        }
    }

    fun recent(limit: Int = 12): List<Snapshot> = try {
        ring.toList().takeLast(limit.coerceIn(1, RING_CAP))
    } catch (_: Throwable) { emptyList() }

    fun formatForPipelineDump(limit: Int = 8): String {
        return try {
            val rows = recent(limit)
            if (rows.isEmpty()) return ""
            val sb = StringBuilder("\n===== AI Stack Snapshot (V5.0.3807, read-only) =====\n")
            rows.asReversed().forEach { s ->
                val top = s.components.sortedByDescending { kotlin.math.abs(it.value) }.take(5)
                    .joinToString(" ") { "${it.name}=${it.value}${if (it.fatal) "!" else ""}" }
                sb.append("  ")
                    .append(s.mode).append(' ')
                    .append(s.lane).append(' ')
                    .append(s.symbol.take(10)).append(' ')
                    .append("src=").append(s.source).append(' ')
                    .append("total=").append(s.scoreTotal)
                    .append(" +/-=").append(s.positiveCount).append('/').append(s.negativeCount)
                    .append(" fatal=").append(s.fatalCount)
                    .append(" top[").append(top).append("]\n")
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }

    fun reset() {
        ring.clear()
        ringSize.set(0)
    }
}
