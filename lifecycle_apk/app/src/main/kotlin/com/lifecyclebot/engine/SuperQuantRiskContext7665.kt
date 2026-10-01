package com.lifecyclebot.engine

/**
 * V5.0.7665 - mature read-only bridge from canonical QuantMetrics into Super critic.
 *
 * QuantMetrics is now fed exclusively from canonical finalized outcomes.
 * This bridge returns neutral until the report has enough samples.
 */
object SuperQuantRiskContext7665 {
    data class Snapshot(
        val usable: Boolean,
        val sampleAdequacy: String,
        val totalTrades: Int,
        val var95Pct: Double,
        val cvar95Pct: Double,
        val sortino: Double,
        val maxDrawdownPct: Double,
        val riskPressure: Double,
    ) {
        fun tag(): String = String.format(
            java.util.Locale.US,
            "quant7665(use=%s,n=%d,VaR=%.1f,CVaR=%.1f,sort=%.2f,dd=%.1f,p=%.2f)",
            usable, totalTrades, var95Pct, cvar95Pct, sortino, maxDrawdownPct, riskPressure,
        )
    }

    fun snapshot(): Snapshot {
        return try {
            val r = com.lifecyclebot.engine.quant.QuantMetrics.generateReport(30)
            val usable = r.sampleAdequacy == "USABLE" || r.sampleAdequacy == "ROBUST"
            val tail = (kotlin.math.abs(r.riskStats.cvar95Pct) / 30.0).coerceIn(0.0, 1.0)
            val dd = (r.maxDrawdownPct / 40.0).coerceIn(0.0, 1.0)
            val downside = when {
                r.sortinoRatio >= 1.5 -> 0.0
                r.sortinoRatio >= 0.5 -> 0.25
                r.sortinoRatio >= 0.0 -> 0.50
                else -> 0.75
            }
            val pressure = if (!usable) 0.0
            else (tail * 0.45 + dd * 0.35 + downside * 0.20).coerceIn(0.0, 1.0)
            Snapshot(
                usable = usable,
                sampleAdequacy = r.sampleAdequacy,
                totalTrades = r.winRateStats.totalTrades,
                var95Pct = r.riskStats.var95Pct,
                cvar95Pct = r.riskStats.cvar95Pct,
                sortino = r.sortinoRatio,
                maxDrawdownPct = r.maxDrawdownPct,
                riskPressure = pressure,
            )
        } catch (_: Throwable) {
            Snapshot(false, "UNAVAILABLE", 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }
}
