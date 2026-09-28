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
            // V5.9.1266 — CROSS-TALK: plug the autonomous organs (1260-1263) into
            // the education/vote-grading chain. Before this they learned in
            // isolation — consuming committee signals but never broadcasting a
            // gradeable opinion back, so the network's trust/reflection systems
            // were blind to the four smartest layers. Now they vote like every
            // other layer → LayerVoteStore grades their accuracy, Sentience
            // Orchestrator can reflect on them, and their knowledge flows into
            // the shared network instead of dead-ending. Abstain-safe.
            safeVote("ForwardOutcomeModel", ::voteForwardModel, ts, votes)
            safeVote("AutonomousMetaPolicy", ::voteMetaPolicy, ts, votes)
            LayerVoteStore.recordVotes(ts.mint, votes)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "captureAllMemeVotes error on ${ts.symbol}: ${e.message}")
        }
    }

    // ── Per-layer vote predicates ────────────────────────────────────────────
    // Each returns null to abstain, or Pair(bullish, conviction 0..1).

    // V5.9.1266 — ForwardOutcomeModel as a graded voter. Reads its own
    // counterfactual forecast for this token's signature; pWin>0.5 = bullish,
    // conviction scaled by distance from coin-flip AND expectancy sign. Abstains
    // while the signature is still in bootstrap (forecast src="bootstrap").
    private fun voteForwardModel(ts: TokenState): Pair<Boolean, Double>? {
        return try {
            val lane = ts.laneAffinity.firstOrNull() ?: "MEME_GENERIC"
            val score = ts.entryScore.toInt()
            val regime = try { com.lifecyclebot.engine.MarketRegimeAI.getCurrentRegime().label } catch (_: Throwable) { "CHOP" }
            val f = com.lifecyclebot.engine.ForwardOutcomeModel.forecast(lane, score, "C", regime, "UNKNOWN")
            if (f.source == "bootstrap" || f.samples < 8) return null
            val bullish = f.pWin >= 0.5
            // conviction: edge from 0.5 + expectancy reinforcement
            val edge = kotlin.math.abs(f.pWin - 0.5) * 2.0
            val expReinforce = if ((f.expectedPnl > 0) == bullish) 0.2 else -0.1
            Pair(bullish, (edge + expReinforce).coerceIn(0.1, 1.0))
        } catch (_: Throwable) { null }
    }

    // V5.9.1266 — AutonomousMetaPolicy as a graded voter. Its learned conviction
    // multiplier (>1.0 = lean in, <1.0 = damp) maps directly to a directional
    // vote. Neutral conviction (≈1.0, still bootstrapping) → abstain.
    private fun voteMetaPolicy(ts: TokenState): Pair<Boolean, Double>? {
        return try {
            val lane = ts.laneAffinity.firstOrNull() ?: "MEME_GENERIC"
            val score = ts.entryScore.toInt()
            val regime = try { com.lifecyclebot.engine.MarketRegimeAI.getCurrentRegime().label } catch (_: Throwable) { "CHOP" }
            val conv = com.lifecyclebot.engine.AutonomousMetaPolicy.conviction(lane, score, regime)
            if (kotlin.math.abs(conv - 1.0) < 0.04) return null   // bootstrap-neutral → abstain
            val bullish = conv > 1.0
            // conviction strength: distance from neutral, normalised over the [0.55,1.45] band
            val strength = (kotlin.math.abs(conv - 1.0) / 0.45).coerceIn(0.1, 1.0)
            Pair(bullish, strength)
        } catch (_: Throwable) { null }
    }

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
        val buyP = ts.lastBuyPressurePct
        val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        // V5.0.7402 — contrarian fear is valid only after launch discovery has
        // matured into a reclaim/range context. On a launch, low buy pressure is
        // lack of ignition, not a bullish bargain.
        return when {
            launch?.tooLateForSnipe == true -> Pair(false, 0.75)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION && buyP >= 60 ->
                Pair(true, 0.75)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION && buyP < 35 ->
                Pair(false, 0.55)
            launch?.ageMs != null && launch.ageMs <= 180_000L -> null
            buyP < 35 -> Pair(true, 0.6)
            buyP > 85 -> Pair(false, 0.55)
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
        val m = ts.momentum ?: 0.0
        val hg = ts.holderGrowthRate
        val bp = ts.lastBuyPressurePct
        val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        if (launch?.ageMs != null && launch.ageMs <= 180_000L && !launch.tooLateForSnipe) return null

        // V5.0.7402 — negative momentum is not a dip signal by itself. Require
        // a visible reclaim, matching DipHunterAI's production doctrine.
        val px = try { ts.history.toList().takeLast(8).map { it.priceUsd }.filter { it.isFinite() && it > 0.0 } }
            catch (_: Throwable) { emptyList() }
        val bounce = if (px.size >= 4) {
            val lowIdx = px.indices.minByOrNull { px[it] } ?: -1
            val low = px.minOrNull() ?: 0.0
            lowIdx >= 0 && lowIdx < px.lastIndex - 1 && low > 0.0 &&
                px.last() >= low * 1.02 && bp >= 50.0
        } else false
        return when {
            launch?.tooLateForSnipe == true && !bounce -> Pair(false, 0.7)
            m < -1.0 && hg > 0 && bounce -> Pair(true, 0.65)
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
        val mcap = ts.lastMcap
        val hg = ts.holderGrowthRate
        if (mcap <= 0) return null
        val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        return when {
            launch?.tooLateForSnipe == true -> Pair(false, 0.8)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION &&
                mcap < 500_000 && (hg > 0.0 || launch.distinctBuyers60s >= 3) -> Pair(true, 0.82)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING &&
                mcap < 500_000 && hg > 5.0 && launch.buySharePct >= 55.0 -> Pair(true, 0.65)
            launch?.ageMs != null && launch.ageMs <= 180_000L -> null
            mcap < 500_000 && hg > 5.0 -> Pair(true, 0.75)
            mcap > 10_000_000 -> Pair(false, 0.4)
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
        val launch = try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts) } catch (_: Throwable) { null }
        return when {
            launch?.tooLateForSnipe == true -> Pair(false, 0.9)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION ->
                Pair(true, 0.9)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.PRE_IGNITION &&
                (launch.accelerationRising || launch.devBuyTx60s > 0) -> Pair(true, 0.78)
            launch?.phase == com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING &&
                launch.buySharePct >= 60.0 && launch.currentVsRecentPeak >= 0.88 -> Pair(true, 0.55)
            launch?.ageMs != null && launch.ageMs <= 180_000L -> null
            else -> null
        }
    }

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
