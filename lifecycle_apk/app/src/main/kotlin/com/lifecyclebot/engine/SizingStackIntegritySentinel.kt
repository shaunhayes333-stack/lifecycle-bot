package com.lifecyclebot.engine

/** V5.0.4285 — report-only guardrail for cumulative sizing multiplier contradictions. */
object SizingStackIntegritySentinel {
    data class Finding(val severity: String, val reason: String)

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
            val compact = components.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.fmtLocal(3)}" }.take(420)
            ForensicLogger.lifecycle("SIZING_STACK_INTEGRITY_SENTINEL_4285", "severity=$severity reason=${reason.take(140)} mode=$mode lane=$lane source=$source mint=${mint.take(10)} symbol=$symbol product=${rawProduct.fmtLocal(3)} components=$compact")
            PipelineHealthCollector.labelInc("SIZING_STACK_INTEGRITY_SENTINEL_4285")
        } catch (_: Throwable) {}
        return Finding(severity, reason)
    }
}

private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "%.${decimals}f", this) } catch (_: Throwable) { this.toString() }
