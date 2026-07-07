package com.lifecyclebot.perps.crypto

/**
 * V5.0.6150 — route-cost expectancy shaper.
 *
 * Converts spread/slippage/route availability into a bounded expectancy cost so
 * Crypto Universe route choice is not just "route exists". This remains a
 * candidate-quality input; execution finality and hard safety stay elsewhere.
 */
object RouteCostExpectancy6150 {
    data class Verdict(
        val routeCostBps: Double,
        val expectancyMultiplier: Double,
        val quality: String,
        val hardNo: Boolean,
        val reason: String,
    )

    fun evaluate(
        spreadPct: Double,
        slippagePct: Double,
        executable: Boolean,
        venueFamily: String,
    ): Verdict {
        val costBps = ((spreadPct.coerceAtLeast(0.0) + slippagePct.coerceAtLeast(0.0)) * 100.0)
        val venueBonus = when (venueFamily.uppercase()) {
            "DEX_AGGREGATOR" -> 0.95
            "SOLANA_DEX" -> 1.00
            "PERPS" -> 1.05
            "PAPER" -> 1.10
            else -> 1.08
        }
        val effectiveBps = costBps * venueBonus
        val mult = when {
            !executable -> 0.55
            effectiveBps <= 20.0 -> 1.08
            effectiveBps <= 60.0 -> 1.00
            effectiveBps <= 120.0 -> 0.88
            effectiveBps <= 220.0 -> 0.72
            else -> 0.55
        }
        val quality = when {
            !executable -> "NO_EXEC_ROUTE"
            effectiveBps <= 20.0 -> "ELITE_COST"
            effectiveBps <= 60.0 -> "GOOD_COST"
            effectiveBps <= 120.0 -> "ACCEPTABLE_COST"
            effectiveBps <= 220.0 -> "EXPENSIVE_COST"
            else -> "TOXIC_COST"
        }
        return Verdict(
            routeCostBps = effectiveBps,
            expectancyMultiplier = mult,
            quality = quality,
            hardNo = !executable || effectiveBps > 260.0,
            reason = "venue=$venueFamily spread=${"%.2f".format(spreadPct)} slip=${"%.2f".format(slippagePct)} costBps=${"%.1f".format(effectiveBps)} quality=$quality mult=${"%.2f".format(mult)}",
        )
    }
}
