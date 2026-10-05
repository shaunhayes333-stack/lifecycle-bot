package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6415 §C — EARLY MOONSHOT HUNTER.
 *
 * OPERATOR DIRECTIVE (Feb 2026):
 * "several tokens have 26x or better this week we need to find them
 *  before 25k buy a good sized chunk and hold for huge profits. that
 *  should be easy for aate as it looks at every metric in a live state.
 *  it needs to be SMART, LEARN and INTEGRATE ACROSS THE STACK. wire it thru."
 *
 * DESIGN
 * ──────
 * Score every sub-$25k mcap candidate on 8 signals AATE already
 * captures live. Each signal is a boolean fire + a raw weight
 * multiplied by the learned weight from [MoonshotSignalLearner6415].
 *
 *   MCAP_SUB_25K              (weight 20)  entry mcap USD < 25000
 *   MCAP_SUB_10K              (weight 15)  entry mcap USD < 10000  (stacked)
 *   LP_DEPTH_HEALTHY          (weight 10)  entry liquidity USD  >=  1500
 *   VOLUME_ACCEL              (weight 10)  vol1h > lp * 0.5
 *   MULTI_SOURCE_CONFIDENCE   (weight 12)  sourceCount >= 2
 *   RUG_SAFETY_PROOF          (weight 15)  mint authority renounced / LP burned
 *   LOW_SELL_PRESSURE         (weight 10)  buys > sells * 1.5 in the last window
 *   CULTURE_RESONANCE_NAME    (weight  8)  symbol matches a curated keyword hit list
 *
 * Composite total possible ≈ 100 (before learning weights).
 *
 *   composite >= 70  ELITE   sizeMult 2.0×  + PATIENT_HOLD profile
 *   composite 55-69  STRONG  sizeMult 1.5×  + STRONG profile
 *   below 55         NORMAL  sizeMult 1.0×  + no override
 *
 * scoreCandidate() emits EXEC_MOONSHOT_ELITE_6415 / STRONG_6415 /
 * PROBED_6415 with the full signal set so operators see WHY the
 * boost fired (or didn't). Every closed trade feeds the learner via
 * `onTradeClosed(mint, tier, signalsFired, pnlPct)`.
 */
object EarlyMoonshotHunter6415 {

    // Culture-resonance curated keyword list. Kept short & broad —
    // learner will demote noise over time via signalWeight() adaptation.
    private val CULTURE_KEYWORDS = setOf(
        // dogs / cats
        "DOG", "PUPPY", "SHIB", "BONK", "WIF", "PUP", "CAT", "MEOW", "KITTY", "PEPE", "FROG",
        // AI / tech
        "AI", "GPT", "CLAUDE", "GEMINI", "GROK", "BOT", "AGENT", "NANO", "TECH", "META",
        // politics / mainstream
        "TRUMP", "BIDEN", "MAGA", "USA", "AMERICA", "ELON", "MUSK",
        // culture memes
        "MOON", "ROCKET", "PUMP", "DEGEN", "CHAD", "WOJAK", "HODL", "APE", "APES",
        "MEME", "COOKIE", "COIN", "KING",
    )

    data class Signal(val name: String, val weight: Double)

    enum class Tier(val sizeMult: Double) {
        NORMAL(1.0),
        STRONG(1.5),
        ELITE(2.0),
    }

    data class Verdict(
        val tier: Tier,
        val composite: Double,
        val mcapUsd: Double,
        val signalsFired: Set<String>,
        val reason: String,
    ) {
        val sizeMult: Double get() = tier.sizeMult
        fun toLog(mint: String, symbol: String): String =
            "mint=${mint.take(10)} sym=$symbol tier=$tier composite=${"%.1f".format(composite)} " +
                "sizeMult=${"%.2f".format(sizeMult)} mcapUsd=${"%.0f".format(mcapUsd)} " +
                "signals=[${signalsFired.joinToString(",")}] reason=${reason.take(60)}"
    }

    /**
     * Compute a moonshot verdict for a candidate. Report-only —
     * caller decides whether/how to apply the sizing lift.
     */
    fun scoreCandidate(
        mint: String,
        symbol: String,
        mcapUsd: Double,
        liquidityUsd: Double,
        vol1hUsd: Double,
        sourceCount: Int,
        buysLastWindow: Int,
        sellsLastWindow: Int,
        rugSafetyConfirmed: Boolean,
        holderCount: Int = 0,
        holderGrowthPct: Double = 0.0,
        topHolderPct: Double = -1.0,
        smartMoneyBuys60s: Int = 0,
        distinctBuyers60s: Int = 0,
        largestBuyerSharePct60s: Double = -1.0,
        top3BuyerSharePct60s: Double = -1.0,
        momentumScore: Double = 50.0,
        bundleRisk: String = "UNKNOWN",
        firstBlockSupplyPct: Double = -1.0,
        devSelling: Boolean = false,
        socialVelocityScore: Double = 0.0,
        emitTelemetry: Boolean = true,
    ): Verdict {
        // Fast rejection: no mcap OR mcap way above 25k → NORMAL.
        if (!mcapUsd.isFinite() || mcapUsd <= 0.0 || mcapUsd > 25_000.0) {
            return Verdict(Tier.NORMAL, 0.0, mcapUsd, emptySet(), "mcap_out_of_moonshot_band")
        }

        val signals = mutableSetOf<String>()
        val fired = mutableListOf<Signal>()

        // MCAP_SUB_25K — always fires (we've already gated above).
        fired += Signal("MCAP_SUB_25K", 20.0); signals.add("MCAP_SUB_25K")
        if (mcapUsd < 10_000.0) { fired += Signal("MCAP_SUB_10K", 15.0); signals.add("MCAP_SUB_10K") }

        if (liquidityUsd.isFinite() && liquidityUsd >= 1500.0) {
            fired += Signal("LP_DEPTH_HEALTHY", 10.0); signals.add("LP_DEPTH_HEALTHY")
        }
        if (vol1hUsd.isFinite() && vol1hUsd > liquidityUsd * 0.5) {
            fired += Signal("VOLUME_ACCEL", 10.0); signals.add("VOLUME_ACCEL")
        }
        if (sourceCount >= 2) {
            fired += Signal("MULTI_SOURCE_CONFIDENCE", 12.0); signals.add("MULTI_SOURCE_CONFIDENCE")
        }
        if (rugSafetyConfirmed) {
            fired += Signal("RUG_SAFETY_PROOF", 15.0); signals.add("RUG_SAFETY_PROOF")
        }
        val totalTrades = buysLastWindow + sellsLastWindow
        if (totalTrades >= 4 && buysLastWindow > sellsLastWindow * 1.5) {
            fired += Signal("LOW_SELL_PRESSURE", 10.0); signals.add("LOW_SELL_PRESSURE")
        }
        val upperSym = symbol.uppercase()
        if (CULTURE_KEYWORDS.any { upperSym.contains(it) }) {
            fired += Signal("CULTURE_RESONANCE_NAME", 8.0); signals.add("CULTURE_RESONANCE_NAME")
        }

        // V5.0.7798 — EXPANSION ENGINE evidence.
        // A runner is not merely green: independent demand broadens faster than
        // valuation while distribution stays survivable. Unknown evidence is neutral.
        if (holderGrowthPct.isFinite()) {
            when {
                holderGrowthPct >= 10.0 -> { fired += Signal("HOLDER_GROWTH_VIRAL", 14.0); signals.add("HOLDER_GROWTH_VIRAL") }
                holderGrowthPct >= 2.0 -> { fired += Signal("HOLDER_GROWTH_POSITIVE", 7.0); signals.add("HOLDER_GROWTH_POSITIVE") }
            }
        }
        if (holderCount >= 100) { fired += Signal("HOLDER_BREADTH", 8.0); signals.add("HOLDER_BREADTH") }
        if (topHolderPct in 0.0..20.0) { fired += Signal("TOP_HOLDER_HEALTHY", 8.0); signals.add("TOP_HOLDER_HEALTHY") }

        when {
            smartMoneyBuys60s >= 3 -> { fired += Signal("SMART_MONEY_CONVERGENCE", 16.0); signals.add("SMART_MONEY_CONVERGENCE") }
            smartMoneyBuys60s >= 2 -> { fired += Signal("SMART_MONEY_CLUSTER", 11.0); signals.add("SMART_MONEY_CLUSTER") }
            smartMoneyBuys60s == 1 -> { fired += Signal("SMART_MONEY_TOUCH", 4.0); signals.add("SMART_MONEY_TOUCH") }
        }

        when {
            distinctBuyers60s >= 8 -> { fired += Signal("INDEPENDENT_BUYER_BREADTH_STRONG", 16.0); signals.add("INDEPENDENT_BUYER_BREADTH_STRONG") }
            distinctBuyers60s >= 5 -> { fired += Signal("INDEPENDENT_BUYER_BREADTH", 11.0); signals.add("INDEPENDENT_BUYER_BREADTH") }
            distinctBuyers60s >= 3 -> { fired += Signal("INDEPENDENT_BUYERS_BUILDING", 6.0); signals.add("INDEPENDENT_BUYERS_BUILDING") }
        }

        when {
            momentumScore >= 70.0 -> { fired += Signal("FLOW_ACCELERATION_STRONG", 10.0); signals.add("FLOW_ACCELERATION_STRONG") }
            momentumScore >= 55.0 -> { fired += Signal("FLOW_ACCELERATION_BUILDING", 5.0); signals.add("FLOW_ACCELERATION_BUILDING") }
        }

        if (socialVelocityScore >= 6.0) {
            fired += Signal("SOCIAL_VELOCITY", 7.0); signals.add("SOCIAL_VELOCITY")
        }

        val bundle = bundleRisk.uppercase()
        if (bundle in setOf("LOW", "CLEAN", "NONE") && (firstBlockSupplyPct < 0.0 || firstBlockSupplyPct <= 20.0)) {
            fired += Signal("DISTRIBUTION_CLEAN", 8.0); signals.add("DISTRIBUTION_CLEAN")
        }

        val negative = mutableListOf<Signal>()
        if (devSelling) negative += Signal("DEV_SELLING", -25.0)
        if (topHolderPct >= 45.0) negative += Signal("TOP_HOLDER_DANGEROUS", -18.0)
        if (bundle in setOf("HIGH", "CRITICAL", "SEVERE")) negative += Signal("BUNDLE_CONCENTRATION", -18.0)
        if (firstBlockSupplyPct >= 40.0) negative += Signal("FIRST_BLOCK_CONCENTRATION", -15.0)
        if (largestBuyerSharePct60s >= 65.0) negative += Signal("ONE_BUYER_DOMINATES_FLOW", -16.0)
        if (top3BuyerSharePct60s >= 85.0) negative += Signal("TOP3_BUYERS_DOMINATE_FLOW", -14.0)
        if (holderGrowthPct.isFinite() && holderGrowthPct <= -5.0) negative += Signal("HOLDER_GROWTH_SHRINKING", -12.0)

        // Apply learned weights.
        var composite = 0.0
        for (s in fired + negative) {
            val learned = try { MoonshotSignalLearner6415.signalWeight(s.name) } catch (_: Throwable) { 1.0 }
            composite += s.weight * learned
            if (s.weight < 0.0) signals.add(s.name)
        }

        val tier = when {
            composite >= 70.0 -> Tier.ELITE
            composite >= 55.0 -> Tier.STRONG
            else -> Tier.NORMAL
        }
        val verdict = Verdict(
            tier, composite, mcapUsd, signals,
            "sub25k_expansion_engine holders=$holderCount hg=${"%.1f".format(holderGrowthPct)} top=${"%.1f".format(topHolderPct)} smart60=$smartMoneyBuys60s buyers60=$distinctBuyers60s largest=${"%.0f".format(largestBuyerSharePct60s)} top3=${"%.0f".format(top3BuyerSharePct60s)} mom=${"%.0f".format(momentumScore)} bundle=$bundle devSell=$devSelling"
        )

        if (emitTelemetry) try {
            val tag = when (tier) {
                Tier.ELITE -> "EXEC_MOONSHOT_ELITE_6415"
                Tier.STRONG -> "EXEC_MOONSHOT_STRONG_6415"
                Tier.NORMAL -> "EXEC_MOONSHOT_PROBED_6415"
            }
            ForensicLogger.lifecycle(tag, verdict.toLog(mint, symbol))
            PipelineHealthCollector.labelInc(tag)
        } catch (_: Throwable) {}
        return verdict
    }

    /**
     * Convenience wiring: register the hold profile associated with
     * a verdict. Called by Executor.liveBuy right before it sends
     * the buy, so exit code sees the profile before the first tick.
     */
    fun registerHoldProfile(mint: String, symbol: String, verdict: Verdict) {
        // V5.0.7764 — keep the signals that fired at entry for the close.
        signalsAtEntry7764[mint] = verdict.signalsFired
        if (signalsAtEntry7764.size > 2_000) signalsAtEntry7764.clear()
        when (verdict.tier) {
            Tier.ELITE -> MoonshotHoldProfileRegistry6415.registerElite(mint, symbol,
                "composite=${"%.1f".format(verdict.composite)} signals=[${verdict.signalsFired.joinToString(",")}]")
            Tier.STRONG -> MoonshotHoldProfileRegistry6415.registerStrong(mint, symbol,
                "composite=${"%.1f".format(verdict.composite)} signals=[${verdict.signalsFired.joinToString(",")}]")
            Tier.NORMAL -> {} // no-op
        }
    }

    /**
     * Feed a closed-trade outcome to the learner. Called from the
     * sell terminal path alongside the existing EV loop.
     */
    private val signalsAtEntry7764 = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** V5.0.7764 — the signals recorded at entry, consumed once at the close. */
    fun takeSignalsAtEntry7764(mint: String): Set<String>? = signalsAtEntry7764.remove(mint)

    fun onTradeClosed(mint: String, symbol: String, tier: String, signalsFired: Set<String>, pnlPct: Double) {
        try {
            MoonshotSignalLearner6415.recordOutcome(mint, symbol, tier, signalsFired, pnlPct)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String = try {
        val learner = MoonshotSignalLearner6415.statusLine()
        val profiles = MoonshotHoldProfileRegistry6415.statusLine()
        "learner=[$learner] profiles=[$profiles]"
    } catch (_: Throwable) { "unavailable" }
}
