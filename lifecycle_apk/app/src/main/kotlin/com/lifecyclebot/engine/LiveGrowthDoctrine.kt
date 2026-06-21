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
    const val VERSION = "V5.0.4020_MICRO_BOOTSTRAP_GROWTH_CORE"

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

    fun sizePolicy(lane: String, score: Double, walletSol: Double, spendableSol: Double, movement: MovementPatternSignal.Signal? = null): SizePolicy {
        val c = canonicalLane(lane)
        val baseWalletPct = when {
            score >= 85.0 -> 0.240
            score >= 70.0 -> 0.190
            score >= 50.0 -> 0.130
            score >= 25.0 -> 0.080
            else -> 0.040
        }
        val laneMult = when (c) {
            "MOONSHOT" -> 1.55
            "QUALITY" -> 1.42
            "BLUECHIP" -> 1.40
            "PROJECT_SNIPER" -> 1.34
            "WHALE_FOLLOW" -> 1.16
            "COPY_TRADE" -> 0.96
            "DIP_HUNTER" -> 1.08
            "CYCLIC" -> 0.82
            "TREASURY" -> 1.02
            "SHITCOIN" -> 0.78
            "MANIPULATED" -> 0.82
            "EXPRESS" -> 0.72
            "ARBITRAGE" -> 0.88
            else -> 1.00
        }
        val liqPct = when (c) {
            "QUALITY", "BLUECHIP", "MOONSHOT" -> 0.0080
            "PROJECT_SNIPER", "WHALE_FOLLOW" -> 0.0060
            "DIP_HUNTER", "CYCLIC", "COPY_TRADE" -> 0.0044
            "SHITCOIN", "MANIPULATED", "EXPRESS", "ARBITRAGE" -> 0.0035
            else -> 0.0045
        }
        val maxWalletPct = when (c) {
            "MOONSHOT" -> 0.35
            "QUALITY", "BLUECHIP" -> 0.28
            "PROJECT_SNIPER", "WHALE_FOLLOW" -> 0.26
            "TREASURY" -> 0.16
            "COPY_TRADE", "DIP_HUNTER", "CYCLIC" -> 0.14
            else -> 0.10
        }
        val absoluteCap = when {
            walletSol < 0.25 -> 0.050
            walletSol < 1.0 -> 0.180
            walletSol < 2.0 -> 0.360
            walletSol < 10.0 -> 1.250
            else -> 3.000
        }
        // V5.0.4020 — micro-bootstrap wallet floor. Previous hidden floors
        // (0.012/0.026/0.040) fought BotConfig.minLiveBuySol=0.005 and kept
        // forcing ≥0.10-ish tickets once growth sizing/wallet caps interacted.
        // Let small wallets sample below 0.10; wallet %, impact, and pending-
        // proof micro caps still bound risk.
        val minExec = when {
            spendableSol >= 1.0 -> 0.020
            spendableSol >= 0.5 -> 0.010
            else -> 0.005
        }
        val moveSize = movement?.sizeMult ?: 1.0
        val moveHold = movement?.holdMult ?: 1.0
        val adjustedWalletPct = (baseWalletPct * moveSize).coerceIn(0.015, maxWalletPct)
        val adjustedLaneMult = (laneMult * moveSize).coerceIn(0.35, 1.75)
        val adjustedLiqPct = (liqPct * moveSize.coerceIn(0.55, 1.35)).coerceIn(0.0025, 0.0080)
        return SizePolicy(
            walletPct = adjustedWalletPct,
            laneMult = adjustedLaneMult,
            liquidityImpactPct = adjustedLiqPct,
            maxWalletPct = maxWalletPct,
            absoluteCapSol = absoluteCap,
            minExecutableSol = minExec,
            reason = "$VERSION AGGRESSIVE_2X_5X_LIVE_WALLET_GROWTH lane=$c score=${score.toInt()} walletPct=${"%.3f".format(adjustedWalletPct)} laneMult=${"%.2f".format(adjustedLaneMult)} liqPct=${"%.4f".format(adjustedLiqPct)} maxWalletPct=${"%.3f".format(maxWalletPct)} cap=${"%.3f".format(absoluteCap)} movement=${movement?.pattern ?: "none"} moveConf=${movement?.confidence?.toInt() ?: 0} hold×=${"%.2f".format(moveHold)} timing=${movement?.timing ?: "n/a"}",
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
