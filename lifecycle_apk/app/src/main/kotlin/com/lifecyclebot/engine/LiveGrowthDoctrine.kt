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
    const val VERSION = "V5.0.4021_ADAPTIVE_LEARNED_GROWTH_CORE"

    val growthLaneUniverse: Set<String> = linkedSetOf(
        "STANDARD", "QUALITY", "BLUECHIP", "BLUE_CHIP", "TREASURY",
        "MOONSHOT", "SHITCOIN", "MANIPULATED", "MANIP", "DIP_HUNTER",
        "PROJECT_SNIPER", "SNIPER", "EXPRESS", "CYCLIC", "PRESALE",
        "PUMP_SNIPER", "WHALE", "WHALE_FOLLOW", "INSIDER_SHARK", "COPY", "COPY_TRADE",
        "MICRO_CAP", "REVIVAL", "ARBITRAGE", "MARKET_MAKER", "LIQUIDATION_HUNTER"
    )

    val growthToolUniverse: Set<String> = linkedSetOf(
        "PUMP_FUN", "PUMP_GRADUATION", "RAYDIUM", "JUPITER", "METIS",
        "DEX", "SMART_CHART", "MFE_TRAIL", "DIAMOND_HANDS", "DEGEN_ENTRY",
        "MICRO_SNIPE", "SNIPE_AGE_GATE", "PULLBACK_RECLAIM", "DIP_RECLAIM",
        "REENTRY_RECOVERY", "WHALE", "WHALE_WALLET", "INSIDER_WALLET", "SOCIAL_ALPHA", "COPY_TRADE",
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
            l.contains("INSIDER") || l.contains("SHARK") -> "INSIDER_SHARK"
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
            "INSIDER_SHARK" -> 1.24
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
            "PROJECT_SNIPER", "WHALE_FOLLOW", "INSIDER_SHARK" -> 0.0060
            "DIP_HUNTER", "CYCLIC", "COPY_TRADE" -> 0.0044
            "SHITCOIN", "MANIPULATED", "EXPRESS", "ARBITRAGE" -> 0.0035
            else -> 0.0045
        }
        val maxWalletPct = when (c) {
            "MOONSHOT" -> 0.35
            "QUALITY", "BLUECHIP" -> 0.28
            "PROJECT_SNIPER", "WHALE_FOLLOW", "INSIDER_SHARK" -> 0.26
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
        // V5.0.4021 — adaptive learned growth dust floor. Previous hidden floors
        // (0.012/0.026/0.040) fought BotConfig.minLiveBuySol=0.005 and kept
        // forcing ≥0.10-ish tickets once growth sizing/wallet caps interacted.
        // These floors are only executable dust bounds; primary fluid sizing authorities
        // remain wallet %, lane feedback, movement, confidence, liquidity impact, and proof.
        val minExec = when {
            spendableSol >= 1.0 -> 0.020
            spendableSol >= 0.5 -> 0.010
            else -> 0.005
        }
        val moveSize = movement?.sizeMult ?: 1.0
        val moveHold = movement?.holdMult ?: 1.0
        // V5.0.4023 — LiveStrategyTuner is cached/live-terminal-only and
        // soft-shapes the growth envelope. It cannot veto, zero-size, or call
        // LLM/API; it just biases real-wallet capital toward lanes that have
        // actually closed profitably live and gives runners more room.
        val strategyTune = try { LiveStrategyTuner.adjustment(c) } catch (_: Throwable) { LiveStrategyTuner.adjustment("STANDARD") }
        val tunedMaxWalletPct = (maxWalletPct * strategyTune.maxWalletMult).coerceIn(0.055, 0.42)
        val adjustedWalletPct = (baseWalletPct * moveSize * strategyTune.sizeMult).coerceIn(0.012, tunedMaxWalletPct)
        val adjustedLaneMult = (laneMult * moveSize * strategyTune.sizeMult).coerceIn(0.25, 1.95)
        val adjustedLiqPct = (liqPct * moveSize.coerceIn(0.55, 1.35) * strategyTune.liquidityImpactMult).coerceIn(0.0020, 0.0090)
        return SizePolicy(
            walletPct = adjustedWalletPct,
            laneMult = adjustedLaneMult,
            liquidityImpactPct = adjustedLiqPct,
            maxWalletPct = tunedMaxWalletPct,
            absoluteCapSol = absoluteCap,
            minExecutableSol = minExec,
            reason = "$VERSION AGGRESSIVE_2X_5X_LIVE_WALLET_GROWTH lane=$c score=${score.toInt()} walletPct=${"%.3f".format(adjustedWalletPct)} laneMult=${"%.2f".format(adjustedLaneMult)} liqPct=${"%.4f".format(adjustedLiqPct)} maxWalletPct=${"%.3f".format(tunedMaxWalletPct)} cap=${"%.3f".format(absoluteCap)} movement=${movement?.pattern ?: "none"} moveConf=${movement?.confidence?.toInt() ?: 0} hold×=${"%.2f".format(moveHold)} timing=${movement?.timing ?: "n/a"} tune=${strategyTune.compact}",
        )
    }

    // V5.0.4557 — contribution tuning for real dispatch lanes.
    // growthLaneUniverse intentionally documents aliases/families (WHALE/COPY/
    // PRESALE/etc.), but AgenticStyleRouter can only wake lanes that BotService
    // actually dispatches. Runtime 4535 showed enabled lanes such as CASHGEN,
    // TREASURY and BLUECHIP contributing little/none while the fallback lottery
    // could spend its one alternate on non-dispatch aliases. Keep the universe for
    // docs/sizing, but lane fallback itself is restricted to executable lanes and
    // biased toward historically under-contributing families first.
    val dispatchableContributionLanes: List<String> = listOf(
        // V5.0.4580 — contribution fallback must wake capital-efficient lanes first.
        // Runtime 4578: BLUECHIP/MOONSHOT were the only net-positive lanes while
        // SHITCOIN/MANIPULATED/EXPRESS were bleeding. Do not rotate bleeders before
        // the lanes currently paying the wallet.
        "BLUECHIP", "MOONSHOT", "QUALITY", "PROJECT_SNIPER", "DIP_HUNTER",
        "CASHGEN", "TREASURY", "STANDARD", "EXPRESS", "MANIPULATED", "SHITCOIN"
    )

    fun growthLaneFallback(seed: String, existing: Set<String>): String? {
        val normalizedExisting = existing.map { canonicalLane(it) }.toSet() + existing.map { it.uppercase() }.toSet()
        val missing = dispatchableContributionLanes.filterNot { it in normalizedExisting }
        if (missing.isEmpty()) return null
        val rotation = try { (System.currentTimeMillis() / 30_000L).toInt() } catch (_: Throwable) { 0 }
        return missing[((seed.hashCode() xor rotation) and 0x7fffffff) % missing.size]
    }

    fun growthToolFallback(seed: String, existing: Set<String>): String? {
        val missing = growthToolUniverse.filterNot { it in existing }
        if (missing.isEmpty()) return null
        return missing[((seed.hashCode() and 0x7fffffff) % missing.size)]
    }
}
