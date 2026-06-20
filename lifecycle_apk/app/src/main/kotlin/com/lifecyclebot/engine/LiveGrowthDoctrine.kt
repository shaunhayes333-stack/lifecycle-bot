package com.lifecyclebot.engine

/**
 * V5.0.3947 — LIVE GROWTH DOCTRINE CORE.
 *
 * Source authority for the operator's north-star: aggressively grow the LIVE
 * wallet every day (2x–5x when opportunity + true safety permit). This object is
 * deliberately consumed at the central execution/style chokepoints instead of
 * patching individual traders downstream.
 *
 * Coverage target: all meme/live trader families, lane archetypes, and trading
 * tools share one growth policy surface:
 *   STANDARD, QUALITY, BLUECHIP, TREASURY, MOONSHOT, SHITCOIN, MANIPULATED,
 *   DIP_HUNTER, PROJECT_SNIPER, EXPRESS, CYCLIC, PRESALE/PUMP_SNIPER,
 *   WHALE/COPY, MICRO/REVIVAL/ARBITRAGE/MARKET_MAKER/LIQUIDATION.
 *
 * This never bypasses route, wallet, true blacklist, zero-liquidity, no-route,
 * confirmed-rug, or raw hard-floor safety. It only controls sizing/style breadth
 * after a candidate has survived source-level safety and execution gates.
 */
object LiveGrowthDoctrine {
    const val VERSION = "V5.0.3947_LIVE_GROWTH_CORE"

    val growthLaneUniverse: Set<String> = linkedSetOf(
        "STANDARD", "QUALITY", "BLUECHIP", "BLUE_CHIP", "TREASURY",
        "MOONSHOT", "SHITCOIN", "MANIPULATED", "MANIP", "DIP_HUNTER",
        "PROJECT_SNIPER", "SNIPER", "EXPRESS", "CYCLIC", "PRESALE",
        "PUMP_SNIPER", "WHALE", "WHALE_FOLLOW", "COPY", "COPY_TRADE",
        "MICRO_CAP", "REVIVAL", "ARBITRAGE", "MARKET_MAKER", "LIQUIDATION_HUNTER"
    )

    val growthToolUniverse: Set<String> = linkedSetOf(
        "PUMP_FUN", "PUMP_GRADUATION", "RAYDIUM", "JUPITER", "METIS",
        "DEX", "SMART_CHART", "MFE_TRAIL", "DIAMOND_HANDS", "DEGEN_ENTRY",
        "MICRO_SNIPE", "SNIPE_AGE_GATE", "PULLBACK_RECLAIM", "DIP_RECLAIM",
        "REENTRY_RECOVERY", "WHALE", "WHALE_WALLET", "COPY_TRADE",
        "SMART_MONEY", "NARRATIVE", "SOCIAL", "SENTIMENT", "ORDER_FLOW",
        "SCALP", "ARB", "FLOW_IMBALANCE", "VENUE_LAG", "MEV_PROTECTION",
        "JITO", "DEFENSIVE_PROBE", "PATTERN_CLASSIFIER", "PATTERN_BACKTESTER"
    )

    data class SizePolicy(
        val walletPct: Double,
        val laneMult: Double,
        val liquidityImpactPct: Double,
        val maxWalletPct: Double,
        val absoluteCapSol: Double,
        val minExecutableSol: Double,
        val reason: String,
    )

    fun canonicalLane(raw: String): String {
        val l = raw.uppercase()
        return when {
            l.contains("BLUE") -> "BLUECHIP"
            l.contains("QUALITY") -> "QUALITY"
            l.contains("TREASURY") -> "TREASURY"
            l.contains("MOON") || l.contains("GRADUATION") -> "MOONSHOT"
            l.contains("SHIT") -> "SHITCOIN"
            l.contains("MANIP") || l.contains("NARRATIVE") -> "MANIPULATED"
            l.contains("DIP") || l.contains("REVERSAL") || l.contains("REVIVAL") -> "DIP_HUNTER"
            l.contains("SNIP") || l.contains("PRESALE") || l.contains("PUMP") || l.contains("FRESH") -> "PROJECT_SNIPER"
            l.contains("EXPRESS") || l.contains("SCALP") -> "EXPRESS"
            l.contains("CYCLIC") -> "CYCLIC"
            l.contains("WHALE") -> "WHALE_FOLLOW"
            l.contains("COPY") -> "COPY_TRADE"
            l.contains("ARB") || l.contains("FLOW") || l.contains("VENUE") -> "ARBITRAGE"
            l.contains("MICRO") -> "MICRO_CAP"
            else -> "STANDARD"
        }
    }

    fun sizePolicy(lane: String, score: Double, walletSol: Double, spendableSol: Double): SizePolicy {
        val c = canonicalLane(lane)
        val baseWalletPct = when {
            score >= 85.0 -> 0.115
            score >= 70.0 -> 0.095
            score >= 50.0 -> 0.075
            score >= 25.0 -> 0.055
            else -> 0.035
        }
        val laneMult = when (c) {
            "MOONSHOT" -> 1.35
            "QUALITY" -> 1.30
            "BLUECHIP" -> 1.25
            "PROJECT_SNIPER" -> 1.18
            "WHALE_FOLLOW" -> 1.16
            "COPY_TRADE" -> 1.12
            "DIP_HUNTER" -> 1.08
            "CYCLIC" -> 1.05
            "TREASURY" -> 1.02
            "SHITCOIN" -> 0.98
            "MANIPULATED" -> 0.92
            "EXPRESS" -> 0.90
            "ARBITRAGE" -> 0.88
            else -> 1.00
        }
        val liqPct = when (c) {
            "QUALITY", "BLUECHIP", "MOONSHOT", "WHALE_FOLLOW", "COPY_TRADE" -> 0.0060
            "PROJECT_SNIPER", "DIP_HUNTER", "CYCLIC" -> 0.0048
            "SHITCOIN", "MANIPULATED", "EXPRESS", "ARBITRAGE" -> 0.0040
            else -> 0.0045
        }
        val maxWalletPct = when (c) {
            "MOONSHOT", "QUALITY", "BLUECHIP" -> 0.14
            "PROJECT_SNIPER", "WHALE_FOLLOW", "COPY_TRADE" -> 0.12
            "DIP_HUNTER", "CYCLIC", "TREASURY" -> 0.10
            else -> 0.085
        }
        val absoluteCap = when {
            walletSol < 0.25 -> 0.030
            walletSol < 1.0 -> 0.085
            walletSol < 2.0 -> 0.160
            walletSol < 10.0 -> 0.420
            else -> 0.900
        }
        val minExec = when {
            spendableSol >= 1.0 -> 0.035
            spendableSol >= 0.5 -> 0.024
            else -> 0.012
        }
        return SizePolicy(
            walletPct = baseWalletPct,
            laneMult = laneMult,
            liquidityImpactPct = liqPct,
            maxWalletPct = maxWalletPct,
            absoluteCapSol = absoluteCap,
            minExecutableSol = minExec,
            reason = "$VERSION lane=$c score=${score.toInt()} walletPct=${"%.3f".format(baseWalletPct)} laneMult=${"%.2f".format(laneMult)} liqPct=${"%.4f".format(liqPct)} maxWalletPct=${"%.3f".format(maxWalletPct)} cap=${"%.3f".format(absoluteCap)}",
        )
    }

    fun growthLaneFallback(seed: String, existing: Set<String>): String? {
        val missing = growthLaneUniverse.filterNot { it in existing }
        if (missing.isEmpty()) return null
        return missing[((seed.hashCode() and 0x7fffffff) % missing.size)]
    }

    fun growthToolFallback(seed: String, existing: Set<String>): String? {
        val missing = growthToolUniverse.filterNot { it in existing }
        if (missing.isEmpty()) return null
        return missing[((seed.hashCode() and 0x7fffffff) % missing.size)]
    }
}
