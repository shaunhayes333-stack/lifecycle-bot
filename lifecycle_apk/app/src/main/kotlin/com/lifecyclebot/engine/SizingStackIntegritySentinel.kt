package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.4285 — report-only guardrail for cumulative sizing multiplier contradictions. */
object SizingStackIntegritySentinel {
    data class Finding(val severity: String, val reason: String)
    private val findingCounts = ConcurrentHashMap<String, AtomicLong>()

    fun inspect(
        mode: String,
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        components: Map<String, Double>,
        rawProduct: Double,
    ): Finding? {
        if (!rawProduct.isFinite()) return emit("CRITICAL", "non_finite_product", mode, lane, source, mint, symbol, rawProduct, components)
        val tiny = components.filterValues { it.isFinite() && it > 0.0 && it < 0.55 }.keys.sorted()
        val boost = components.filterValues { it.isFinite() && it > 1.18 }.keys.sorted()
        val finding = when {
            rawProduct < 0.18 -> "dust_stack product=${rawProduct.fmtLocal(3)} tiny=${tiny.joinToString("+").ifBlank { "none" }}"
            rawProduct > 4.50 -> "runaway_stack product=${rawProduct.fmtLocal(3)} boost=${boost.joinToString("+").ifBlank { "none" }}"
            tiny.size >= 3 -> "triple_downsize tiny=${tiny.joinToString("+")} product=${rawProduct.fmtLocal(3)}"
            boost.size >= 4 -> "quad_boost boost=${boost.joinToString("+")} product=${rawProduct.fmtLocal(3)}"
            else -> null
        } ?: return null
        return emit("WARN", finding, mode, lane, source, mint, symbol, rawProduct, components)
    }

    private fun emit(severity: String, reason: String, mode: String, lane: String, source: String, mint: String, symbol: String, rawProduct: Double, components: Map<String, Double>): Finding {
        try {
            findingCounts.computeIfAbsent("$severity|${reason.take(48)}") { AtomicLong(0L) }.incrementAndGet()
            val compact = components.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.fmtLocal(3)}" }.take(420)
            ForensicLogger.lifecycle("SIZING_STACK_INTEGRITY_SENTINEL_4285", "severity=$severity reason=${reason.take(140)} mode=$mode lane=$lane source=$source mint=${mint.take(10)} symbol=$symbol product=${rawProduct.fmtLocal(3)} components=$compact")
            PipelineHealthCollector.labelInc("SIZING_STACK_INTEGRITY_SENTINEL_4285")
        } catch (_: Throwable) {}
        return Finding(severity, reason)
    }

    fun status(limit: Int = 6): String {
        val rows = findingCounts.mapValues { it.value.get() }.entries.sortedByDescending { it.value }.take(limit.coerceAtLeast(1))
        return "SIZING_STACK_INTEGRITY_STATUS_4362 findings=${findingCounts.values.sumOf { it.get() }} top=${rows.joinToString(";") { it.key + ":" + it.value }} report_only=true no_size_mutation=true"
    }
}

private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "%.${decimals}f", this) } catch (_: Throwable) { this.toString() }
