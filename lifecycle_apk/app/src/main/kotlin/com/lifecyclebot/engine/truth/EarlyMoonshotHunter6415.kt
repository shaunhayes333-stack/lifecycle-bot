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

        // Apply learned weights.
        var composite = 0.0
        for (s in fired) {
            val learned = try { MoonshotSignalLearner6415.signalWeight(s.name) } catch (_: Throwable) { 1.0 }
            composite += s.weight * learned
        }

        val tier = when {
            composite >= 70.0 -> Tier.ELITE
            composite >= 55.0 -> Tier.STRONG
            else -> Tier.NORMAL
        }
        val verdict = Verdict(tier, composite, mcapUsd, signals, "sub25k_moonshot_hunter")

        try {
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
