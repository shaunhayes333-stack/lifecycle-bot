package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.3807 — Read-only effective size-shape telemetry.
 *
 * This is the guardrail before adding more autonomy/MoE layers. It does not
 * participate in admission, score, sizing, execution, or learning. It has no influence on score.
 * It only records the
 * already-computed final FDG size and the shaping tags/checks that led there.
 *
 * Doctrine protected:
 * - no learned-strategy zero sizing without visibility
 * - live/paper split is event-local from FDG mode, not UI snapshot mode
 * - no synchronous I/O, LLM, API, or persistence in the hot path
 */
object EffectiveSizeShapeTrace {
    data class Trace(
        val tsMs: Long,
        val mint: String,
        val symbol: String,
        val mode: String,
        val lane: String,
        val source: String,
        val baseSizeSol: Double,
        val finalSizeSol: Double,
        val effectiveMult: Double,
        val shouldTrade: Boolean,
        val blockReason: String,
        val shapeTags: List<String>,
        val shapeChecks: List<String>,
    ) {
        val dusted: Boolean get() = shouldTrade && finalSizeSol > 0.0 && finalSizeSol < 0.01
        val zeroed: Boolean get() = shouldTrade && finalSizeSol <= 0.0
        val live: Boolean get() = mode.equals("LIVE", true)
        val paper: Boolean get() = mode.equals("PAPER", true)
    }

    private const val RING_CAP = 120
    private val ring = ConcurrentLinkedDeque<Trace>()
    private val ringSize = AtomicInteger(0)

    private fun isShapeTag(tag: String): Boolean {
        val t = tag.lowercase()
        return t.contains("size") || t.contains("mult") || t.contains("×") ||
            t.contains("metapolicy") || t.contains("starve") || t.contains("fwdsim") ||
            t.contains("policyhead") || t.contains("hypo") || t.contains("lane_policy") ||
            t.contains("personality") || t.contains("probe") || t.contains("dust") ||
            t.contains("penalty") || t.contains("reduced")
    }

    private fun isShapeCheck(name: String, reason: String?): Boolean {
        val s = (name + " " + (reason ?: "")).lowercase()
        return s.contains("size") || s.contains("mult") || s.contains("×") ||
            s.contains("metapolicy") || s.contains("starve") || s.contains("forward") ||
            s.contains("policy") || s.contains("hypothesis") || s.contains("personality") ||
            s.contains("dust") || s.contains("penalty") || s.contains("probe")
    }

    fun recordDecision(
        mint: String,
        symbol: String,
        mode: String,
        lane: String,
        source: String,
        baseSizeSol: Double,
        finalSizeSol: Double,
        shouldTrade: Boolean,
        blockReason: String?,
        tags: List<String>,
        gateChecks: List<FinalDecisionGate.GateCheck>,
    ) {
        try {
            val safeBase = baseSizeSol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val safeFinal = finalSizeSol.takeIf { it.isFinite() } ?: 0.0
            val trace = Trace(
                tsMs = System.currentTimeMillis(),
                mint = mint,
                symbol = symbol,
                mode = mode.ifBlank { "UNKNOWN" },
                lane = lane.ifBlank { "UNKNOWN" },
                source = source.ifBlank { "UNKNOWN" },
                baseSizeSol = safeBase,
                finalSizeSol = safeFinal,
                effectiveMult = if (safeBase > 0.0) (safeFinal / safeBase) else 1.0,
                shouldTrade = shouldTrade,
                blockReason = blockReason ?: "",
                shapeTags = tags.filter(::isShapeTag).takeLast(20),
                shapeChecks = gateChecks.filter { isShapeCheck(it.name, it.reason) }
                    .takeLast(20)
                    .map { "${it.name}:${it.reason ?: "OK"}".take(140) },
            )
            ring.addLast(trace)
            if (ringSize.incrementAndGet() > RING_CAP) {
                ring.pollFirst()
                ringSize.decrementAndGet()
            }
            try {
                PipelineHealthCollector.labelInc("SIZE_SHAPE_TRACE_${trace.mode.uppercase()}")
                if (trace.dusted) PipelineHealthCollector.labelInc("AI_SIZE_STACK_DUSTED_${trace.mode.uppercase()}")
                if (trace.zeroed) PipelineHealthCollector.labelInc("AI_SIZE_STACK_ZEROED_${trace.mode.uppercase()}")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // Telemetry must never affect FDG.
        }
    }

    fun recent(limit: Int = 16): List<Trace> = try {
        ring.toList().takeLast(limit.coerceIn(1, RING_CAP))
    } catch (_: Throwable) { emptyList() }

    fun summary(mode: String): Triple<Int, Int, Int> {
        val rows = recent(RING_CAP).filter { it.mode.equals(mode, true) }
        return Triple(rows.size, rows.count { it.dusted }, rows.count { it.zeroed })
    }

    fun formatForPipelineDump(limit: Int = 12): String {
        return try {
            val rows = recent(limit)
            if (rows.isEmpty()) return ""
            val live = summary("LIVE")
            val paper = summary("PAPER")
            val sb = StringBuilder("\n===== Effective Size Shape Trace (V5.0.3807, read-only) =====\n")
            sb.append("  LIVE rows=${live.first} dusted=${live.second} zeroed=${live.third}\n")
            sb.append("  PAPER rows=${paper.first} dusted=${paper.second} zeroed=${paper.third}\n")
            rows.asReversed().forEach { t ->
                val tagText = (t.shapeTags + t.shapeChecks).take(6).joinToString(" | ").ifBlank { "no_shape_tags" }
                sb.append("  ")
                    .append(t.mode).append(' ')
                    .append(t.lane).append(' ')
                    .append(t.symbol.take(10)).append(' ')
                    .append(String.format("%.4f→%.4f ×%.3f", t.baseSizeSol, t.finalSizeSol, t.effectiveMult))
                if (t.blockReason.isNotBlank()) sb.append(" block=").append(t.blockReason.take(28))
                sb.append(" :: ").append(tagText.take(220)).append('\n')
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }

    fun reset() {
        ring.clear()
        ringSize.set(0)
    }
}
