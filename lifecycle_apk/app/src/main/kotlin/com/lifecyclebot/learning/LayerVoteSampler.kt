package com.lifecyclebot.learning

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🗳️ LAYER VOTE SAMPLER — V5.9.380
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * At the moment a meme trade opens, this sampler asks each of the 26 meme
 * layers: "given this token's current state, would you vote BULLISH, BEARISH,
 * or ABSTAIN?". The votes are stored in LayerVoteStore and replayed when the
 * trade closes so each layer gets graded on ITS OWN opinion — not the bot's
 * aggregate WR.
 *
 * Each layer's vote is derived from TokenState fields ONLY — no coupling to
 * layer internals (keeps it crash-proof and refactor-proof). The predicates
 * encode each layer's known personality:
 *   • ShitCoinTraderAI — votes bullish on low mcap memes
 *   • BlueChipTraderAI — votes bullish on high mcap quality
 *   • FearGreedAI — contrarian, votes bullish on low buy-pressure
 *   • UltraFastRugDetectorAI — votes bearish when rug flags are tripped
 *   • ...etc
 *
 * If a layer's required field is missing/stale, it abstains gracefully.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LayerVoteSampler {

    private const val TAG = "🗳️Sampler"

    /**
     * Capture votes from all 26 meme layers for this token. Safe to call
     * repeatedly on the same mint — later votes just overwrite earlier ones
     * (useful if we re-sample on position add / increase).
     */
    fun captureAllMemeVotes(ts: TokenState) {
        try {
            val votes = mutableMapOf<String, LayerVoteStore.Vote>()
            safeVote("BehaviorAI", ::voteBehavior, ts, votes)
            safeVote("FluidLearningAI", ::voteFluidLearning, ts, votes)
            safeVote("MomentumPredictorAI", ::voteMomentum, ts, votes)
            safeVote("QualityTraderAI", ::voteQuality, ts, votes)
            safeVote("VolatilityRegimeAI", ::voteVolatility, ts, votes)
            safeVote("LiquidityCycleAI", ::voteLiquidityCycle, ts, votes)
            safeVote("FearGreedAI", ::voteFearGreed, ts, votes)
            safeVote("MarketRegimeAI", ::voteMarketRegime, ts, votes)
            safeVote("SocialVelocityAI", ::voteSocialVelocity, ts, votes)
            safeVote("CashGenerationAI", ::voteCashGen, ts, votes)
            safeVote("EducationSubLayerAI", ::voteEducation, ts, votes)
            safeVote("MetaCognitionAI", ::voteMetaCognition, ts, votes)
            safeVote("RegimeTransitionAI", ::voteRegimeTransition, ts, votes)
            safeVote("SmartMoneyDivergenceAI", ::voteSmartMoney, ts, votes)
            safeVote("DipHunterAI", ::voteDipHunter, ts, votes)
            safeVote("SellOptimizationAI", ::voteSellOpt, ts, votes)
            safeVote("HoldTimeOptimizerAI", ::voteHoldTime, ts, votes)
            safeVote("WhaleTrackerAI", ::voteWhale, ts, votes)
            safeVote("UltraFastRugDetectorAI", ::voteRugDetector, ts, votes)
            safeVote("OrderFlowImbalanceAI", ::voteOrderFlow, ts, votes)
            safeVote("CollectiveIntelligenceAI", ::voteCollective, ts, votes)
            safeVote("MoonshotTraderAI", ::voteMoonshot, ts, votes)
            safeVote("ShitCoinTraderAI", ::voteShitCoin, ts, votes)
            safeVote("ShitCoinExpress", ::voteShitCoinExpress, ts, votes)
            safeVote("BlueChipTraderAI", ::voteBlueChip, ts, votes)
            safeVote("ProjectSniperAI", ::voteProjectSniper, ts, votes)
            LayerVoteStore.recordVotes(ts.mint, votes)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "captureAllMemeVotes error on ${ts.symbol}: ${e.message}")
        }
    }

    // ── Per-layer vote predicates ────────────────────────────────────────────
    // Each returns null to abstain, or Pair(bullish, conviction 0..1).

    private fun voteBehavior(ts: TokenState): Pair<Boolean, Double>? {
        // BehaviorAI: discipline-driven. Votes bullish when entry score is
        // strong AND buy pressure is positive. Abstains in wait phase.
        if (ts.phase == "idle" || ts.phase == "wait") return null
        val score = ts.entryScore
        val buyP = ts.lastBuyPressurePct
        if (score >= 60 && buyP >= 55) return Pair(true, 0.7)
        if (score >= 70) return Pair(true, 0.8)
        if (score < 30 && buyP < 45) return Pair(false, 0.5)
        return null
    }

    private fun voteFluidLearning(ts: TokenState): Pair<Boolean, Double>? {
        // FluidLearningAI: adapts thresholds. Votes on entryScore alone.
        if (ts.entryScore <= 0) return null
        return when {
            ts.entryScore >= 65 -> Pair(true, 0.75)
            ts.entryScore >= 50 -> Pair(true, 0.55)
            ts.entryScore <= 25 -> Pair(false, 0.6)
            else -> null
        }
    }

    private fun voteMomentum(ts: TokenState): Pair<Boolean, Double>? {
        val m = ts.momentum ?: return null
        return when {
            m > 0.5 -> Pair(true, (m.coerceAtMost(3.0) / 3.0))
            m < -0.5 -> Pair(false, (-m.coerceAtLeast(-3.0) / 3.0))
            else -> null
        }
    }

    private fun voteQuality(ts: TokenState): Pair<Boolean, Double>? {
        // Quality prefers tokens with real liquidity AND meaningful mcap.
        val liq = ts.lastLiquidityUsd
        val mcap = ts.lastMcap
        if (liq <= 0 || mcap <= 0) return null
        val liqOk = liq >= 15_000
        val mcapOk = mcap in 50_000.0..5_000_000.0
        return when {
            liqOk && mcapOk -> Pair(true, 0.7)
            liq < 5_000 -> Pair(false, 0.65)
            mcap > 50_000_000 -> Pair(false, 0.5)  // too big for meme edge
            else -> null
        }
    }

    private fun voteVolatility(ts: TokenState): Pair<Boolean, Double>? {
        val v = ts.volatility ?: return null
        return when {
            v > 0.4 -> Pair(false, 0.55)      // extreme vol → abstain/short
            v in 0.1..0.3 -> Pair(true, 0.6)  // goldilocks range
            v < 0.05 -> null                  // dead, abstain
            else -> null
        }
    }

    private fun voteLiquidityCycle(ts: TokenState): Pair<Boolean, Double>? {
        val liq = ts.lastLiquidityUsd
        if (liq <= 0) return null
        return when {
            liq >= 25_000 -> Pair(true, 0.6)
            liq < 3_000 -> Pair(false, 0.7)
            else -> null
        }
    }

    private fun voteFearGreed(ts: TokenState): Pair<Boolean, Double>? {
        // Contrarian. Vote bullish when buy pressure is LOW (fear) and
        // bearish when buy pressure is EXTREME (greed).
        val buyP = ts.lastBuyPressurePct
        return when {
            buyP < 35 -> Pair(true, 0.6)      // contrarian long
            buyP > 85 -> Pair(false, 0.55)    // contrarian short (greed top)
            else -> null
        }
    }

    private fun voteMarketRegime(ts: TokenState): Pair<Boolean, Double>? {
        // Regime via holder growth + momentum combo.
        val hg = ts.holderGrowthRate
        val m = ts.momentum ?: 0.0
        return when {
            hg > 2.0 && m > 0 -> Pair(true, 0.65)       // RISK_ON
            hg < -2.0 && m < 0 -> Pair(false, 0.7)      // RISK_OFF
            else -> null
        }
    }

    private fun voteSocialVelocity(ts: TokenState): Pair<Boolean, Double>? {
        // Proxy: holder growth spike is a social-velocity analogue for memes.
        val hg = ts.holderGrowthRate
        return when {
            hg > 5.0 -> Pair(true, 0.7)
            hg > 2.0 -> Pair(true, 0.5)
            hg < -5.0 -> Pair(false, 0.6)
            else -> null
        }
    }

    private fun voteCashGen(ts: TokenState): Pair<Boolean, Double>? {
        // CashGen is a realize-profits layer — abstains on entry usually,
        // but votes bearish if entry score is weak AND liquidity thin.
        if (ts.entryScore < 30 && ts.lastLiquidityUsd < 5_000) return Pair(false, 0.55)
        return null
    }

    private fun voteEducation(ts: TokenState): Pair<Boolean, Double>? {
        // Education = pattern match. Use entry+exit score spread as proxy.
        val spread = ts.entryScore - ts.exitScore
        return when {
            spread >= 30 -> Pair(true, 0.65)
            spread <= -30 -> Pair(false, 0.65)
            else -> null
        }
    }

    private fun voteMetaCognition(ts: TokenState): Pair<Boolean, Double>? {
        // Agrees only when score AND buy pressure BOTH agree.
        val s = ts.entryScore
        val bp = ts.lastBuyPressurePct
        return when {
            s >= 60 && bp >= 60 -> Pair(true, 0.8)
            s <= 30 && bp <= 40 -> Pair(false, 0.75)
            else -> null
        }
    }

    private fun voteRegimeTransition(ts: TokenState): Pair<Boolean, Double>? {
        // Votes on sharp swings — uses recent momentum shift.
        val m = ts.momentum ?: return null
        return when {
            kotlin.math.abs(m) > 1.5 -> Pair(m > 0, 0.55)
            else -> null
        }
    }

    private fun voteSmartMoney(ts: TokenState): Pair<Boolean, Double>? {
        // SmartMoney proxy: topHolder concentration. Low = distributed (good),
        // Very high = whale dump risk.
        val th = ts.topHolderPct ?: return null
        return when {
            th < 15.0 -> Pair(true, 0.6)       // distributed → smart money OK
            th > 40.0 -> Pair(false, 0.75)     // whale concentration risk
            else -> null
        }
    }

    private fun voteDipHunter(ts: TokenState): Pair<Boolean, Double>? {
        // Votes bullish on oversold (negative momentum + holders still growing).
        val m = ts.momentum ?: 0.0
        val hg = ts.holderGrowthRate
        return when {
            m < -1.0 && hg > 0 -> Pair(true, 0.65)   // dip with support
            m > 2.0 -> null                          // already running, abstain
            else -> null
        }
    }

    private fun voteSellOpt(ts: TokenState): Pair<Boolean, Double>? {
        // Sell optimizer focuses on exit quality; vote bearish only when
        // exit score already exceeds entry score significantly (trade is
        // about to be exited).
        return if (ts.exitScore > ts.entryScore + 20) Pair(false, 0.6) else null
    }

    private fun voteHoldTime(ts: TokenState): Pair<Boolean, Double>? {
        // Abstains on entry. Could contribute more if we had avg hold data
        // per token, but at open time there's no signal yet.
        return null
    }

    private fun voteWhale(ts: TokenState): Pair<Boolean, Double>? {
        // Whale signal proxy: sudden jump in peakHolderCount vs current
        // holders suggests recent whale activity.
        return when {
            ts.holderGrowthRate > 10.0 -> Pair(true, 0.6)
            ts.peakHolderCount > 0 && ts.holderGrowthRate < -10.0 -> Pair(false, 0.65)
            else -> null
        }
    }

    private fun voteRugDetector(ts: TokenState): Pair<Boolean, Double>? {
        // Uses the SafetyReport attached to TokenState. If risk flags present
        // strongly, vote BEARISH.
        val safety = ts.safety
        return try {
            // Safety report is a data class — use any signal we can find.
            val any = safety.toString()
            val risky = any.contains("risk", ignoreCase = true) &&
                (any.contains("high", ignoreCase = true) || any.contains("warn", ignoreCase = true))
            if (risky) Pair(false, 0.75) else null
        } catch (_: Exception) { null }
    }

    private fun voteOrderFlow(ts: TokenState): Pair<Boolean, Double>? {
        // Buy pressure IS order flow imbalance.
        val bp = ts.lastBuyPressurePct
        return when {
            bp >= 70 -> Pair(true, (bp - 70) / 30.0 + 0.4)
            bp <= 30 -> Pair(false, (30 - bp) / 30.0 + 0.4)
            else -> null
        }
    }

    private fun voteCollective(ts: TokenState): Pair<Boolean, Double>? {
        // Collective intelligence — agrees with entryScore direction but only
        // at high conviction thresholds.
        return when {
            ts.entryScore >= 75 -> Pair(true, 0.85)
            ts.entryScore <= 20 -> Pair(false, 0.75)
            else -> null
        }
    }

    private fun voteMoonshot(ts: TokenState): Pair<Boolean, Double>? {
        // Votes bullish on micro-mcap + holder growth (moonshot profile).
        val mcap = ts.lastMcap
        val hg = ts.holderGrowthRate
        if (mcap <= 0) return null
        return when {
            mcap < 500_000 && hg > 5.0 -> Pair(true, 0.75)
            mcap > 10_000_000 -> Pair(false, 0.4)      // too big for moonshot
            else -> null
        }
    }

    private fun voteShitCoin(ts: TokenState): Pair<Boolean, Double>? {
        // Low-mcap memes are ShitCoin's domain.
        val mcap = ts.lastMcap
        if (mcap <= 0) return null
        return when {
            mcap in 30_000.0..1_000_000.0 -> Pair(true, 0.6)
            mcap > 5_000_000 -> Pair(false, 0.5)       // out of its wheelhouse
            else -> null
        }
    }

    private fun voteShitCoinExpress(ts: TokenState): Pair<Boolean, Double>? {
        // Fresh ultra-low-cap only.
        val mcap = ts.lastMcap
        val liq = ts.lastLiquidityUsd
        if (mcap <= 0) return null
        return when {
            mcap < 150_000 && liq > 5_000 -> Pair(true, 0.7)
            mcap > 1_500_000 -> null                   // abstains past ceiling
            else -> null
        }
    }

    private fun voteBlueChip(ts: TokenState): Pair<Boolean, Double>? {
        // Mid/high mcap quality.
        val mcap = ts.lastMcap
        val liq = ts.lastLiquidityUsd
        if (mcap <= 0 || liq <= 0) return null
        return when {
            mcap >= 5_000_000 && liq >= 50_000 -> Pair(true, 0.7)
            mcap < 500_000 -> Pair(false, 0.5)          // too small for blue-chip
            else -> null
        }
    }

    private fun voteProjectSniper(ts: TokenState): Pair<Boolean, Double>? {
        // Source-based: new-launch sources (PUMP_FUN_NEW, RAYDIUM_NEW) bullish
        // if entry score also supports it.
        val src = ts.source.uppercase()
        val isNew = "NEW" in src || "PUMP_FUN" in src || "RAYDIUM" in src
        return when {
            isNew && ts.entryScore >= 55 -> Pair(true, 0.7)
            isNew && ts.entryScore < 30 -> Pair(false, 0.55)
            else -> null
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun safeVote(
        layerName: String,
        voter: (TokenState) -> Pair<Boolean, Double>?,
        ts: TokenState,
        out: MutableMap<String, LayerVoteStore.Vote>,
    ) {
        try {
            val v = voter(ts) ?: return
            out[layerName] = LayerVoteStore.Vote(v.first, v.second.coerceIn(0.1, 1.0))
        } catch (_: Exception) {
            // abstain on error
        }
    }
}
