package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexVoters7885.Voter

/**
 * V5.0.8004 — the wide estate: every per-coin read in the stack that had no
 * Cortex seat. The learned books (forward labels, tail hunter, specialists,
 * chart library, structure, expert wallets, meme meta, hive), the Harvard
 * education stack (approval-pattern nudge and layer accuracy / Sharpe /
 * expectancy / trust weighted committees), the behaviour and memory stacks,
 * scanner brains, cross-talk detail, whale / insider / smart money, bonding
 * curve, launch phase, v3 flow models, v4 meta detail, and the raw TokenState
 * fields no voter read (range position, exhaustion, spike, wick, holder bleed,
 * authorities, bundle risk, hype velocity).
 *
 * Same contract as [CortexVoters7885]: cheap reads of what a component already
 * computed or cached, null = abstain, no fetches, no records. Evidence tags are
 * per source module so one module's several numbers count once in fusion.
 */
internal object CortexVotersWide8004 {
    private fun e(vararg v: Double) = v
    private val SCORE = e(30.0, 45.0, 60.0, 75.0)
    private val PROB = e(0.30, 0.45, 0.55, 0.70)
    private val PCT = e(-5.0, -2.0, 0.0, 2.0, 5.0)
    private val WIDE_PCT = e(-20.0, -5.0, 0.0, 10.0, 40.0)
    private val MULT = e(0.80, 0.95, 1.05, 1.20)
    private val FLAG = e(0.5)
    private val ADJ = e(-10.0, -3.0, 0.0, 3.0, 10.0)
    private val COUNT = e(0.5, 1.5, 3.5, 7.5)
    private val UNIT = e(0.2, 0.4, 0.6, 0.8)

    private fun fin(v: Double?): Double? = v?.takeIf { it.isFinite() }
    private fun pos(v: Double?): Double? = v?.takeIf { it.isFinite() && it > 0.0 }
    private fun flag(b: Boolean?): Double? = b?.let { if (it) 1.0 else 0.0 }
    private fun v(id: String, fam: String, edges: DoubleArray, ev: String, read: (TokenState, String, Long) -> Double?) =
        Voter(id, fam, edges, setOf(ev), read)

    // One read per (module, mint, assessment time): several voters share a module's result.
    // V5.0.8015 — one slot per module (an assessment reads one coin at one time), not a map of up to
    // 4,000 stale results for coins long gone (heap read 90% eight minutes into 8014).
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Long, Any?>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> once(name: String, ts: TokenState, now: Long, f: () -> T): T {
        memo[name]?.let { (m, t, v) -> if (m == ts.mint && t == now) return v as T }
        val r = f()
        memo[name] = Triple(ts.mint, now, r)
        return r
    }

    private fun whale(ts: TokenState, now: Long) = once("whale", ts, now) { com.lifecyclebot.engine.WhaleDetector.evaluate(ts.mint, ts) }
    private fun movement(ts: TokenState, now: Long) = once("move", ts, now) { com.lifecyclebot.engine.MovementPatternSignal.from(ts) }
    private fun structure(ts: TokenState, now: Long) = once("mstruct", ts, now) { com.lifecyclebot.v3.modes.MarketStructureRouter.classify(ts) }
    private fun hivePatterns(ts: TokenState, now: Long) = once("hivepat", ts, now) {
        com.lifecyclebot.collective.CollectiveLearning.getPatternEdgesForCandidate(ts.symbol, ts.source, ts.lastLiquidityUsd, ts.lastMcap, ts.lastBuyPressurePct)
    }
    private fun historical(ts: TokenState, now: Long) = once("hist", ts, now) {
        com.lifecyclebot.engine.HistoricalChartScanner.getHistoricalRecommendation(ts.lastLiquidityUsd, ts.tokenMap.volume1hUsd ?: 0.0, 0.0)
    }
    private fun curve(ts: TokenState, now: Long) = once("curve", ts, now) { com.lifecyclebot.engine.BondingCurveTracker.evaluate(ts) }

    private fun ageMs(ts: TokenState, now: Long): Long =
        ts.safety.tokenAgeMinutes.takeIf { it >= 0.0 }?.let { (it * 60_000.0).toLong() } ?: (now - ts.addedToWatchlistAt).coerceAtLeast(0L)

    private fun dev(ts: TokenState): String? =
        try { com.lifecyclebot.engine.OperatorRegistry.getDevWallet(ts.mint)?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }

    private fun committee(ts: TokenState): Map<String, Int>? =
        com.lifecyclebot.v3.scoring.EducationSubLayerAI.peekEntryScores7895(ts.mint)?.takeIf { it.isNotEmpty() }

    /** Σ score × weight(layer) / Σ weight — a committee where each layer counts by what it has proven. */
    private fun weighted(ts: TokenState, w: (String) -> Double): Double? {
        val c = committee(ts) ?: return null
        var num = 0.0; var den = 0.0
        for ((k, s) in c) { val wt = w(k); if (wt.isFinite() && wt > 0.0) { num += s * wt; den += wt } }
        return if (den > 0.0) num / den else null
    }

    private fun hiveMean(key: String, minN: Double = 5.0): Double? =
        com.lifecyclebot.engine.truth.HiveEdge8000.net8000(key)?.takeIf { it.size >= 2 && it[0] >= minN }?.let { it[1] / it[0] }

    private fun cell(ts: TokenState, lane: String, now: Long) =
        once("cell|$lane", ts, now) { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.cellStatFor(ts, lane, now) }

    private fun launchPhase(ts: TokenState, now: Long) =
        try { com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.snapshot(ts, now) } catch (_: Throwable) { null }

    private fun lastCandle(ts: TokenState) = try { ts.history.lastOrNull { !it.synthetic } } catch (_: Throwable) { null }

    private fun trend(c: Collection<com.lifecyclebot.data.Candle>): Double? {
        val l = c.toList().filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 }
        if (l.size < 3) return null
        return (l.last().priceUsd / l.first().priceUsd - 1.0) * 100.0
    }

    private fun setupQuality(ts: TokenState) = when {
        ts.entryScore >= 90 -> "A+"; ts.entryScore >= 80 -> "A"; ts.entryScore >= 70 -> "B"; else -> "C"
    }

    private fun volumeSignal(ts: TokenState) = ts.meta.volScore.let {
        when { it > 80 -> "SURGE"; it > 60 -> "INCREASING"; it > 40 -> "NORMAL"; it > 20 -> "DECREASING"; else -> "LOW" }
    }

    val VOTERS: List<Voter> = listOf(
        // ── learned books (§7731 … §8000) ──
        v("FRL_CELL_MEAN", "BOOKS", WIDE_PCT, "frl_cell") { ts, lane, now -> cell(ts, lane, now)?.takeIf { it.n60 >= 5 }?.meanNet60Pct },
        v("FRL_CELL_WINRATE", "BOOKS", PROB, "frl_cell") { ts, lane, now -> cell(ts, lane, now)?.takeIf { it.n60 >= 5 }?.winRate60 },
        v("FRL_CELL_RUNNER_RATE", "BOOKS", e(0.02, 0.05, 0.10, 0.20), "frl_cell") { ts, lane, now -> cell(ts, lane, now)?.takeIf { it.n60 >= 5 }?.runnerRate60 },
        v("FRL_CELL_PROOF_TIER", "BOOKS", e(0.5, 1.5), "frl_cell") { ts, lane, now ->
            com.lifecyclebot.engine.truth.CellProofLadder7731.tierFor(cell(ts, lane, now)).ordinal.toDouble()
        },
        v("TAIL_TICKET", "BOOKS", FLAG, "tail_hunter") { ts, _, now -> flag(com.lifecyclebot.engine.truth.TailHunter7996.ticketActive7996(ts.mint, now)) },
        v("SPECIALIST_MATCH", "BOOKS", FLAG, "specialist_miner") { ts, lane, now -> flag(com.lifecyclebot.engine.truth.SpecialistMiner7972.peek7978(ts, lane, now)) },
        v("HIVE_DEV_MEAN", "BOOKS", WIDE_PCT, "hive_dev") { ts, _, _ -> dev(ts)?.let { hiveMean("DEV|$it") } },
        v("RUNNER_GRABBED", "BOOKS", FLAG, "runner_grab") { ts, _, _ -> flag(com.lifecyclebot.engine.RunnerGrab7967.isGrabbed7980(ts.mint)) },
        v("EXIT_PROFILE_P75_PEAK", "BOOKS", e(10.0, 25.0, 50.0, 100.0), "exit_profile") { ts, _, now ->
            com.lifecyclebot.engine.ExitProfile7955.keyProfileFor7962(ts, now)?.takeIf { it.n >= 5 }?.p75Peak
        },
        // ── chart library and structure ──
        v("CHART_MOTIF_LIFT", "CHART", e(-0.10, -0.03, 0.03, 0.10), "chart_motif") { ts, _, _ ->
            com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.motif?.takeIf { it.n >= 5 }?.lift
        },
        v("CHART_MOTIF_PUP", "CHART", PROB, "chart_motif") { ts, _, _ ->
            com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.motif?.takeIf { it.n >= 5 }?.pUp
        },
        v("CHART_MOTIF_END_PCT", "CHART", PCT, "chart_motif") { ts, _, _ ->
            com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.motif?.takeIf { it.n >= 5 }?.meanEndPct
        },
        v("CHART_TAPE_BUY_SHARE", "CHART", e(0.40, 0.50, 0.60, 0.70), "chart_tape") { ts, _, _ ->
            com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.buyShare?.let { fin(it) }
        },
        v("CHART_BUY_SIGNAL", "CHART", FLAG, "chart_motif") { ts, _, _ ->
            com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.let { flag(com.lifecyclebot.engine.chart.ChartReader7950.buySignal(it)) }
        },
        v("CHART_DEV_SOLD", "CHART", FLAG, "chart_tape") { ts, _, _ -> com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.devSold?.let { flag(it) } },
        v("STRUCT_HH_HL", "CHART", COUNT, "structure") { ts, _, now ->
            com.lifecyclebot.engine.chart.StructureTracker7962.reads7967(ts.mint, now).second?.hhHl?.toDouble()
        },
        v("STRUCT_RECLAIM", "CHART", FLAG, "structure") { ts, _, now ->
            com.lifecyclebot.engine.chart.StructureTracker7962.reads7967(ts.mint, now).first?.reclaimFresh?.let { flag(it) }
        },
        v("STRUCT_BROKEN", "CHART", FLAG, "structure") { ts, _, now ->
            com.lifecyclebot.engine.chart.StructureTracker7962.reads7967(ts.mint, now).let { (a, b) -> (a ?: b)?.brokeStructure?.let { flag(it) } }
        },
        // ── expert wallets, callers, meme meta ──
        v("EXPERT_ENTRY_LIVE", "SMART_MONEY", FLAG, "experts") { ts, _, now -> flag(com.lifecyclebot.engine.ExpertWallets7962.expertEntryLive7962(ts.mint, now)) },
        v("EXPERT_CELL_LIFT", "SMART_MONEY", PCT, "experts_cell") { ts, _, now ->
            fin(com.lifecyclebot.engine.ExpertWallets7962.expertCellLift7962(ts.lastMcap, ageMs(ts, now)))?.takeIf { it != 0.0 }
        },
        v("MEME_META_BETA", "META", FLAG, "meme_meta") { ts, _, now -> flag(com.lifecyclebot.engine.market.MemeMeta7973.beta7973(ts.mint, ts.symbol, ts.name, now)) },
        v("MEME_META_LIVE", "META", FLAG, "meme_meta_live") { ts, _, now -> flag(com.lifecyclebot.engine.market.MemeMeta7973.live7973(ts.mint, now)) },
        v("MEME_META_KOTH", "META", FLAG, "meme_meta_live") { ts, _, now -> flag(com.lifecyclebot.engine.market.MemeMeta7973.koth7973(ts.mint, now)) },
        v("ORACLE_LAST_VERDICT", "FORECASTER", FLAG, "oracle") { ts, _, now ->
            com.lifecyclebot.engine.truth.OracleEdgeProof7263.latestVerdict7740(ts.mint, 10 * 60_000L, now)?.let {
                if (it == com.lifecyclebot.engine.truth.PredictiveEntryOracle6915.Verdict.ADMIT) 1.0 else 0.0
            }
        },
        // ── Harvard / education stack ──
        v("HARVARD_APPROVAL_NUDGE", "HARVARD", e(-2.0, 0.5, 3.5, 6.5), "harvard_approval") { ts, _, _ ->
            committee(ts)?.let { com.lifecyclebot.v3.scoring.EducationSubLayerAI.approvalBoostFor(it) }?.takeIf { it.second != "NO_SIG" }?.first?.toDouble()
        },
        v("HARVARD_ACCURACY_COMMITTEE", "HARVARD", ADJ, "harvard_weighted") { ts, _, _ ->
            weighted(ts) { k -> com.lifecyclebot.v3.scoring.EducationSubLayerAI.getLayerAccuracy(k) }
        },
        v("HARVARD_SHARPE_COMMITTEE", "HARVARD", ADJ, "harvard_weighted") { ts, _, _ ->
            weighted(ts) { k -> com.lifecyclebot.v3.scoring.EducationSubLayerAI.getLayerSharpe(k).coerceAtLeast(0.0) }
        },
        v("HARVARD_BULL_EXPECTANCY", "HARVARD", PCT, "harvard_weighted") { ts, _, _ ->
            committee(ts)?.filterValues { it > 5 }?.keys?.map { com.lifecyclebot.v3.scoring.EducationSubLayerAI.getLayerExpectancyPct(it) }
                ?.filter { it.isFinite() && it != 0.0 }?.takeIf { it.isNotEmpty() }?.average()
        },
        v("HARVARD_BULL_LAYERS", "HARVARD", e(2.5, 5.5, 9.5, 14.5), "harvard_count") { ts, _, _ -> committee(ts)?.count { it.value > 5 }?.toDouble() },
        v("HARVARD_BEAR_LAYERS", "HARVARD", e(0.5, 2.5, 5.5, 9.5), "harvard_count") { ts, _, _ -> committee(ts)?.count { it.value < -5 }?.toDouble() },
        v("HARVARD_LANE_EXPECTANCY", "HARVARD", PCT, "harvard_lane") { _, lane, _ ->
            fin(com.lifecyclebot.v3.scoring.EducationSubLayerAI.getLayerExpectancyPct(lane))?.takeIf { it != 0.0 }
        },
        v("TRUSTNET_COMMITTEE", "HARVARD", ADJ, "trust_network") { ts, _, _ ->
            weighted(ts) { k -> com.lifecyclebot.v3.scoring.AITrustNetworkAI.getTrustWeight(k) }
        },
        v("BOOTSTRAP_COMMITTEE", "HARVARD", ADJ, "bootstrap_weights") { ts, _, _ ->
            weighted(ts) { k -> com.lifecyclebot.v3.scoring.BootstrapAdaptiveEngine.getMultiplier(k) }
        },
        // ── behaviour stack ──
        v("BEHAVIOR_LEARNING_ADJ", "BEHAVIOR", ADJ, "behavior_learning") { ts, lane, _ ->
            com.lifecyclebot.engine.BehaviorLearning.evaluate(ts.phase, setupQuality(ts), lane, ts.lastLiquidityUsd, volumeSignal(ts))
                .takeIf { it.confidence > 0.0 }?.scoreAdjustment?.toDouble()
        },
        v("BEHAVIOR_AI_CONF_MOD", "BEHAVIOR", ADJ, "behavior_ai") { _, _, _ -> com.lifecyclebot.v3.scoring.BehaviorAI.getConfidenceModifier().toDouble() },
        v("BEHAVIOR_AI_SENTIMENT", "BEHAVIOR", SCORE, "behavior_ai") { _, _, _ -> com.lifecyclebot.v3.scoring.BehaviorAI.getInternalSentiment().toDouble() },
        v("BEHAVIOR_AI_TILT", "BEHAVIOR", e(10.0, 30.0, 50.0, 70.0), "behavior_ai") { _, _, _ -> com.lifecyclebot.v3.scoring.BehaviorAI.getState().tiltLevel.toDouble() },
        v("BEHAVIOR_AI_DISCIPLINE", "BEHAVIOR", SCORE, "behavior_ai") { _, _, _ -> com.lifecyclebot.v3.scoring.BehaviorAI.getState().disciplineScore.toDouble() },
        v("FLUID_LEARNING_PROGRESS", "BEHAVIOR", UNIT, "fluid") { _, _, _ -> fin(com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()) },
        // ── memory stack ──
        v("LOSING_PATTERN_MEAN", "MEMORY", PCT, "losing_pattern") { ts, lane, _ ->
            com.lifecyclebot.engine.LosingPatternMemory.stats(lane, ts.entryScore.toInt()).takeIf { it.sample >= 5 }?.meanPnl
        },
        v("LOSING_PATTERN_LOSS_RATE", "MEMORY", e(40.0, 55.0, 70.0, 85.0), "losing_pattern") { ts, lane, _ ->
            com.lifecyclebot.engine.LosingPatternMemory.stats(lane, ts.entryScore.toInt()).takeIf { it.sample >= 5 }?.lossRatePct
        },
        v("TRADING_MEMORY_PATTERN_WR", "MEMORY", PROB, "trading_memory") { ts, _, _ ->
            fin(com.lifecyclebot.engine.TradingMemory.getPatternWinRate(ts.phase, ts.meta.emafanAlignment, ts.source))
        },
        v("CREATOR_RUG_COUNT", "MEMORY", e(0.5, 1.5, 3.5), "creator") { ts, _, _ ->
            dev(ts)?.let { com.lifecyclebot.engine.TradingMemory.getCreatorRugCount(it).toDouble() }
        },
        v("PATTERN_GOLDEN_GOOSE", "MEMORY", e(-20.0, -5.0, 5.0, 20.0), "token_patterns") { ts, _, _ ->
            com.lifecyclebot.engine.PatternGoldenGoose.scoreBias(ts.name, ts.symbol).takeIf { it != 0 }?.toDouble()
        },
        v("TOKEN_WIN_PATTERN_WR", "MEMORY", PROB, "token_patterns") { ts, _, _ ->
            com.lifecyclebot.engine.TokenWinMemory.patternEdgeForToken(ts.name, ts.symbol).takeIf { it.bestN >= 5 }?.bestWr
        },
        v("HISTORICAL_CHART_CONF", "MEMORY", PROB, "historical_chart") { ts, _, now ->
            historical(ts, now)
                .takeIf { it.sampleSize >= 5 }?.confidence
        },
        v("HISTORICAL_CHART_EXP_GAIN", "MEMORY", e(5.0, 15.0, 30.0, 60.0), "historical_chart") { ts, _, now ->
            historical(ts, now)
                .takeIf { it.sampleSize >= 5 }?.expectedGainPct
        },
        v("COLLECTIVE_PATTERN_ADJ", "COLLECTIVE", ADJ, "hive_patterns") { ts, _, now ->
            hivePatterns(ts, now)
                .takeIf { it.isNotEmpty() }?.map { it.scoreAdj.toDouble() }?.average()
        },
        v("COLLECTIVE_PATTERN_PNL", "COLLECTIVE", PCT, "hive_patterns") { ts, _, now ->
            hivePatterns(ts, now)
                .filter { it.totalTrades >= 5 }.takeIf { it.isNotEmpty() }?.map { it.avgPnlPct }?.average()
        },
        v("COLLECTIVE_BLACKLIST", "COLLECTIVE", FLAG, "hive_blacklist") { ts, _, _ -> flag(com.lifecyclebot.collective.CollectiveLearning.isBlacklisted(ts.mint)) },
        v("COLLECTIVE_NETWORK_BOOST", "COLLECTIVE", ADJ, "collective_ai") { ts, _, _ ->
            com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.networkBoostForMint(ts.mint).takeIf { it != 0 }?.toDouble()
        },
        v("COLLECTIVE_AVOID", "COLLECTIVE", FLAG, "collective_ai") { ts, _, _ -> flag(com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.shouldAvoid(ts.mint)) },
        v("COLLECTIVE_CONSENSUS", "COLLECTIVE", FLAG, "collective_ai") { ts, _, _ -> flag(com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.hasConsensus(ts.mint)) },
        v("CREATOR_HIVE_PNL", "COLLECTIVE", WIDE_PCT, "creator") { ts, _, _ ->
            dev(ts)?.let { com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getCreatorReputation(it) }?.takeIf { it.totalOutcomes >= 3 }?.avgPnlPct
        },
        v("SOURCE_HIVE_PNL", "COLLECTIVE", PCT, "source_hive") { ts, _, _ ->
            com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.getSourceReliability(ts.source)?.takeIf { it.totalOutcomes >= 10 }?.avgPnlPct
        },
        v("MOONSHOT_COLLECTIVE_WINNER", "COLLECTIVE", FLAG, "collective_winner") { ts, _, _ ->
            flag(com.lifecyclebot.v3.scoring.MoonshotTraderAI.isCollectiveWinner(ts.mint))
        },
        // ── scanner brains ──
        v("SCANNER_DISCOVERY_BONUS", "SCANNER", ADJ, "scanner_learning") { ts, _, now ->
            fin(com.lifecyclebot.engine.ScannerLearning.getDiscoveryBonus(ts.source, ts.lastLiquidityUsd, ageMs(ts, now) / 3_600_000.0))
        },
        v("SCANNER_SOURCE_WR", "SCANNER", PROB, "scanner_learning") { ts, _, _ -> fin(com.lifecyclebot.engine.ScannerLearning.getSourceWinRate(ts.source)) },
        v("MODE_LEARNING_BONUS", "SCANNER", ADJ, "mode_learning") { ts, lane, _ ->
            com.lifecyclebot.engine.ModeLearning.getScoreBonus(lane, ts.phase, ts.lastLiquidityUsd, ts.source,
                java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)).toDouble()
        },
        v("SOURCE_COHORT_WR", "SCANNER", e(30.0, 45.0, 55.0, 70.0), "source_cohort_adv") { ts, _, _ ->
            com.lifecyclebot.engine.truth.SourceCohortAdvisory6727.advisory(ts.source).takeIf { it.decidedCount >= 10 }?.winRatePct
        },
        v("MODE_SCANNER_SCORE", "SCANNER", SCORE, "mode_scanners") { ts, _, _ -> com.lifecyclebot.engine.ModeSpecificScanners.getCached(ts.mint)?.score?.let { fin(it) } },
        v("SMART_CHART_BIAS", "SCANNER", e(-50.0, -10.0, 10.0, 50.0), "smart_chart") { ts, _, _ ->
            com.lifecyclebot.engine.SmartChartScanner.quickScan(ts)?.let {
                when (it.overallBias) { "BULLISH" -> it.confidence; "BEARISH" -> -it.confidence; else -> 0.0 }
            }
        },
        v("SMART_CHART_BEARISH", "SCANNER", SCORE, "smart_chart_cache") { ts, _, _ -> fin(com.lifecyclebot.engine.SmartChartCache.getBearishConfidence(ts.mint)) },
        v("MOVEMENT_PATTERN_CONF", "SCANNER", SCORE, "movement") { ts, _, now -> fin(movement(ts, now).confidence) },
        v("MOVEMENT_PATTERN_SIZE", "SCANNER", MULT, "movement") { ts, _, now -> fin(movement(ts, now).sizeMult) },
        v("MARKET_STRUCTURE_RISK", "SCANNER", e(1.5, 2.5, 3.5, 4.5), "structure_router") { ts, _, now ->
            structure(ts, now).mode.riskTier.toDouble()
        },
        v("MARKET_STRUCTURE_CONF", "SCANNER", PROB, "structure_router") { ts, _, now ->
            fin(structure(ts, now).confidence)?.let { if (it > 1.0) it / 100.0 else it }
        },
        v("MEME_TECH_SCORE", "SCANNER", SCORE, "meme_tech") { ts, _, _ -> com.lifecyclebot.v3.MemeUnifiedScorerBridge.scoreForEntry(ts, null).techScore.toDouble() },
        v("SECOND_SCORER", "SCANNER", SCORE, "second_scorer") { ts, _, _ -> com.lifecyclebot.engine.SecondScorer.score(ts).score.toDouble() },
        v("AGE_PATTERN_SCORE", "SCANNER", SCORE, "age_pattern") { ts, _, now ->
            fin(com.lifecyclebot.engine.OrthogonalSignals.calculateAgePatternScore((ageMs(ts, now) / 60_000L).toInt(), ts.tokenMap.migratedOrGraduated))
        },
        v("TOXIC_PATTERN", "SCANNER", FLAG, "toxic_mode") { ts, lane, _ ->
            val bucket = ts.lastLiquidityUsd.let { when { it < 2_000 -> "MICRO"; it < 10_000 -> "TINY"; it < 50_000 -> "LOW"; else -> "HEALTHY" } }
            flag(com.lifecyclebot.engine.ToxicModeCircuitBreaker.isToxicPattern(ts.source, bucket, lane, false))
        },
        v("SOURCE_TIMING_PENALTY", "SCANNER", e(-17.5, -10.0, -4.0, -1.0), "source_timing") { ts, _, _ ->
            com.lifecyclebot.v3.arb.SourceTimingRegistry.getSourceTimingPenalty(ts.mint).first.toDouble()
        },
        v("SOURCE_COUNT", "SCANNER", COUNT, "source_timing") { ts, _, _ -> com.lifecyclebot.v3.arb.SourceTimingRegistry.getSourceCount(ts.mint).takeIf { it > 0 }?.toDouble() },
        v("VENUE_LAG_S", "SCANNER", e(5.0, 15.0, 45.0, 90.0), "source_timing") { ts, _, _ -> com.lifecyclebot.v3.arb.SourceTimingRegistry.getVenueLagMs(ts.mint)?.let { it / 1000.0 } },
        v("CHANGE_SINCE_FIRST_SEEN", "SCANNER", WIDE_PCT, "source_timing") { ts, _, _ ->
            ts.lastPrice.takeIf { it > 0.0 }?.let { fin(com.lifecyclebot.v3.arb.SourceTimingRegistry.getPriceChangeSinceFirstSeen(ts.mint, it)) }
        },
        v("ARB_OPPORTUNITY_SCORE", "SCANNER", SCORE, "arb") { ts, _, _ -> com.lifecyclebot.v3.arb.ArbScannerAI.cachedOpportunity(ts.mint)?.score?.toDouble() },
        v("ARB_EXPECTED_MOVE", "SCANNER", PCT, "arb") { ts, _, _ -> com.lifecyclebot.v3.arb.ArbScannerAI.cachedOpportunity(ts.mint)?.expectedMovePct?.let { fin(it) } },
        v("ARB_RECENT_LOSSES", "SCANNER", COUNT, "arb_losses") { ts, _, _ -> com.lifecyclebot.v3.arb.ArbLearning.getRecentLossCount(ts.mint).toDouble() },
        v("WAVE_ENTRY_LATE", "SCANNER", FLAG, "wave_entry") { ts, _, _ ->
            flag(com.lifecyclebot.engine.WaveEntryQualityGate6382.evaluate(ts, ts.entryScore.toInt()) != null)
        },
        // ── cross-talk detail and super-brain aggregate ──
        v("CROSSTALK_CONF_BOOST", "CROSSTALK", ADJ, "crosstalk") { ts, lane, _ -> com.lifecyclebot.engine.AICrossTalk.cachedSignal7654(ts.mint, lane)?.confidenceBoost?.let { fin(it) } },
        v("CROSSTALK_SIZE", "CROSSTALK", MULT, "crosstalk") { ts, lane, _ -> com.lifecyclebot.engine.AICrossTalk.cachedSignal7654(ts.mint, lane)?.sizeMultiplier?.let { fin(it) } },
        v("CROSSTALK_CORRELATION", "CROSSTALK", UNIT, "crosstalk") { ts, lane, _ -> com.lifecyclebot.engine.AICrossTalk.cachedSignal7654(ts.mint, lane)?.correlationStrength?.let { fin(it) } },
        v("CROSSTALK_EXIT_URGENCY", "CROSSTALK", UNIT, "crosstalk") { ts, lane, _ -> com.lifecyclebot.engine.AICrossTalk.cachedSignal7654(ts.mint, lane)?.exitUrgency?.let { fin(it) } },
        v("CROSSTALK_PARTICIPANTS", "CROSSTALK", COUNT, "crosstalk") { ts, lane, _ -> com.lifecyclebot.engine.AICrossTalk.cachedSignal7654(ts.mint, lane)?.participatingAIs?.size?.toDouble() },
        v("CROSSTALK_MEME_SHAPE", "CROSSTALK", MULT, "v4_crosstalk_shape") { ts, lane, _ ->
            fin(com.lifecyclebot.v4.meta.CrossTalkFusionEngine.memeShapeMultiplier6878(ts.symbol, lane))
        },
        v("SUPERBRAIN_BULL_MINUS_BEAR", "CROSSTALK", e(-2.5, -0.5, 0.5, 2.5), "superbrain_agg") { ts, _, _ ->
            com.lifecyclebot.engine.SuperBrainEnhancements.getAggregatedSignal(ts.mint)?.let { (it.bullishSignals - it.bearishSignals).toDouble() }
        },
        v("SUPERBRAIN_AGG_CONF", "CROSSTALK", SCORE, "superbrain_agg") { ts, _, _ -> com.lifecyclebot.engine.SuperBrainEnhancements.getAggregatedSignal(ts.mint)?.confidence?.let { fin(it) } },
        v("SYMBOLIC_VOTE", "CROSSTALK", e(-0.5, -0.1, 0.1, 0.5), "symbolic") { ts, _, _ -> com.lifecyclebot.engine.SymbolicVerdictRegistry.peekVote8004(ts.mint) },
        v("SENTIENCE_LLM_VOTE", "CROSSTALK", e(-6.0, 1.0), "sentience_llm") { ts, _, _ ->
            com.lifecyclebot.engine.SentienceHooks.entryQualityScoreBias6678(ts.symbol).takeIf { it != 0 }?.toDouble()
        },
        v("ULTIMATE_EDGE_PWIN", "CROSSTALK", PROB, "ultimate_edge") { ts, lane, _ -> com.lifecyclebot.engine.UltimateEdgeEngine.cached(ts.mint, lane)?.modelPWin?.let { fin(it) } },
        v("ULTIMATE_EDGE_EXP_PNL", "CROSSTALK", PCT, "ultimate_edge") { ts, lane, _ -> com.lifecyclebot.engine.UltimateEdgeEngine.cached(ts.mint, lane)?.modelExpectedPnlPct?.let { fin(it) } },
        v("ULTIMATE_EDGE_OPPORTUNITY", "CROSSTALK", SCORE, "ultimate_edge") { ts, lane, _ -> com.lifecyclebot.engine.UltimateEdgeEngine.cached(ts.mint, lane)?.opportunityScore?.let { fin(it) } },
        v("ULTIMATE_EDGE_REL_STRENGTH", "CROSSTALK", PCT, "ultimate_edge") { ts, lane, _ -> com.lifecyclebot.engine.UltimateEdgeEngine.cached(ts.mint, lane)?.relativeStrengthPct?.let { fin(it) } },
        v("MOMENTUM_PREDICTOR", "CROSSTALK", SCORE, "momentum_predictor") { ts, _, _ -> pos(com.lifecyclebot.engine.MomentumPredictorAI.getMomentumScore(ts.mint)) },
        v("NARRATIVE_DETECTOR_ADJ", "CROSSTALK", ADJ, "narrative_detector") { ts, _, _ ->
            fin(com.lifecyclebot.engine.NarrativeDetectorAI.getEntryScoreAdjustment(ts.symbol, ts.name))?.takeIf { it != 0.0 }
        },
        v("NARRATIVE_CLUSTER_WR", "CROSSTALK", e(30.0, 45.0, 55.0, 70.0), "meme_cluster_wr") { ts, _, _ ->
            fin(com.lifecyclebot.v3.scoring.MemeNarrativeAI.winRatePct(com.lifecyclebot.v3.scoring.MemeNarrativeAI.detect(ts.symbol, ts.name).cluster))?.takeIf { it > 0.0 }
        },
        // ── whale / insider / smart money ──
        v("WHALE_SCORE", "SMART_MONEY", SCORE, "whale_detector") { ts, _, now -> whale(ts, now).takeIf { it.hasWhaleActivity }?.whaleScore },
        v("WHALE_VELOCITY", "SMART_MONEY", SCORE, "whale_detector") { ts, _, now -> fin(whale(ts, now).velocityScore) },
        v("WHALE_CONCENTRATION", "SMART_MONEY", SCORE, "whale_detector") { ts, _, now -> fin(whale(ts, now).concentration) },
        v("WHALE_SMART_MONEY", "SMART_MONEY", FLAG, "whale_detector") { ts, _, now -> flag(whale(ts, now).smartMoneyPresent) },
        v("WHALE_TRACKER_SIGNED", "SMART_MONEY", e(-50.0, -10.0, 10.0, 50.0), "whale_tracker") { ts, _, _ ->
            com.lifecyclebot.engine.WhaleTrackerAI.getWhaleSignal(ts.mint, ts.symbol).takeIf { it.whaleCount > 0 }?.let {
                when (it.signal) {
                    com.lifecyclebot.engine.WhaleTrackerAI.SignalType.WHALE_ACCUMULATION -> it.strength
                    com.lifecyclebot.engine.WhaleTrackerAI.SignalType.WHALE_DISTRIBUTION -> -it.strength
                    else -> 0.0
                }
            }
        },
        v("SMART_MONEY_BUYS_60S", "SMART_MONEY", COUNT, "smart_money_feed") { ts, _, now -> com.lifecyclebot.engine.truth.SmartMoneyFeed6394.smartMoneyBuysLast60s(ts.mint, now).toDouble() },
        v("GMGN_SMART_MONEY", "SMART_MONEY", FLAG, "gmgn") { ts, _, _ ->
            com.lifecyclebot.v4.meta.ExternalAlphaFeeds.smartMoneyRows7300().takeIf { it.isNotEmpty() }?.let { rows -> flag(rows.any { it.mint == ts.mint }) }
        },
        v("INSIDER_WALLET_SCORE", "SMART_MONEY", e(10.0, 30.0, 60.0), "insider_wallets") { ts, _, _ ->
            com.lifecyclebot.perps.InsiderWalletTracker.getInsiderScore(ts.mint).takeIf { it > 0 }?.toDouble()
        },
        v("INSIDER_ACCUMULATION", "SMART_MONEY", e(10.0, 30.0, 60.0), "insiders") { ts, _, _ ->
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.accumulationScore7925(ts.mint).takeIf { it > 0 }?.toDouble()
        },
        v("INSIDER_ALPHA", "SMART_MONEY", FLAG, "insiders") { ts, _, _ -> flag(com.lifecyclebot.v3.scoring.InsiderTrackerAI.hasRecentAlphaSignal(ts.mint)) },
        v("INSIDER_AVOID", "SMART_MONEY", FLAG, "insiders") { ts, _, _ -> flag(com.lifecyclebot.v3.scoring.InsiderTrackerAI.shouldAvoid(ts.mint)) },
        v("SMART_MONEY_DIVERGENCE", "SMART_MONEY", e(-1.5, -0.5, 0.5, 1.5), "sm_divergence") { ts, _, _ ->
            when (com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.getDivergence(ts.mint)) {
                com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.STRONG_BULLISH -> 2.0
                com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.BULLISH -> 1.0
                com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.CONFIRMATION_BULL -> 1.0
                com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.BEARISH -> -1.0
                com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.CONFIRMATION_BEAR -> -1.0
                com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.DivergenceType.STRONG_BEARISH -> -2.0
                else -> null
            }
        },
        v("HOT_CONVICTION", "SMART_MONEY", SCORE, "hot_conviction") { ts, _, _ ->
            com.lifecyclebot.engine.HotConvictionWarmup.getAll().firstOrNull { it.mint == ts.mint }?.convictionScore?.toDouble()
        },
        // ── flow / volatility models (v3) ──
        v("ORDERBOOK_PULSE", "FLOW", e(-20.0, -5.0, 5.0, 20.0), "ob_pulse") { ts, _, _ -> fin(com.lifecyclebot.v3.scoring.OrderbookImbalancePulseAI.getRecentPulse(ts.mint))?.takeIf { it != 0.0 } },
        v("ORDERFLOW_DELTA", "FLOW", e(-100.0, -20.0, 20.0, 100.0), "ofi") { ts, _, _ -> fin(com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.getCumulativeDelta(ts.mint))?.takeIf { it != 0.0 } },
        v("ORDERFLOW_STATE", "FLOW", e(0.5, 1.5, 2.5, 3.5), "ofi") { ts, _, _ ->
            com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.getFlowState(ts.mint).ordinal.toDouble()
        },
        v("ORDERFLOW_ABSORBING", "FLOW", FLAG, "ofi") { ts, _, _ -> flag(com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.isAbsorbing(ts.mint)) },
        v("VOL_REGIME", "FLOW", e(0.5, 1.5, 2.5), "vol_regime") { ts, _, _ -> com.lifecyclebot.v3.scoring.VolatilityRegimeAI.getRegime(ts.mint).ordinal.toDouble() },
        v("VOL_SQUEEZE", "FLOW", FLAG, "vol_regime") { ts, _, _ -> flag(com.lifecyclebot.v3.scoring.VolatilityRegimeAI.isInSqueeze(ts.mint)) },
        v("EXEC_EXTRA_SLIP", "FLOW", e(0.5, 1.5, 3.0, 6.0), "exec_cost") { ts, _, _ -> fin(com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.expectedExtraSlipPct(ts.lastLiquidityUsd)) },
        // ── market regime (global; seats learn which regimes each lane earns in) ──
        v("LIQ_CYCLE_HEALTH", "REGIME", SCORE, "liq_cycle") { _, _, _ -> fin(com.lifecyclebot.v3.scoring.LiquidityCycleAI.getCurrentState().healthScore) },
        v("LIQ_CYCLE_RISK", "REGIME", e(1.5, 2.5, 3.5, 4.5), "liq_cycle") { _, _, _ -> com.lifecyclebot.v3.scoring.LiquidityCycleAI.getCurrentState().riskLevel.toDouble() },
        v("STABLECOIN_REGIME", "REGIME", e(-0.5, -0.1, 0.1, 0.5), "stable_flow") { _, _, _ -> fin(com.lifecyclebot.v3.scoring.StablecoinFlowAI.getRegimeBias()) },
        v("NEWS_SHOCK_SLOPE", "REGIME", e(-0.2, -0.05, 0.05, 0.2), "news_shock") { _, _, _ -> fin(com.lifecyclebot.v3.scoring.NewsShockAI.getSlope())?.takeIf { it != 0.0 } },
        v("REGIME_FIT_LANE", "REGIME", MULT, "regime_fit") { _, lane, _ -> fin(com.lifecyclebot.v4.meta.CrossMarketRegimeAI.getRegimeFitMultiplier(lane)) },
        v("PORTFOLIO_ENTRY_PENALTY", "REGIME", UNIT, "v4_portfolio_pen") { _, _, _ -> fin(com.lifecyclebot.v4.meta.PortfolioHeatAI.getNewEntryPenalty()) },
        // ── bonding curve, launch phase, admission records ──
        v("CURVE_PROGRESS_TRACKER", "LAUNCH", e(20.0, 50.0, 80.0, 95.0), "bonding_curve") { ts, _, now ->
            curve(ts, now).takeIf { !it.isGraduated && it.progressPct > 0.0 }?.progressPct
        },
        v("CURVE_SOL_TO_GRAD", "LAUNCH", e(5.0, 20.0, 50.0, 80.0), "bonding_curve") { ts, _, now ->
            curve(ts, now).takeIf { !it.isGraduated && it.solToGraduation > 0.0 }?.solToGraduation
        },
        v("LAUNCH_PHASE", "LAUNCH", e(0.5, 1.5, 2.5, 3.5, 4.5), "launch_phase") { ts, _, now -> launchPhase(ts, now)?.phase?.ordinal?.toDouble() },
        v("LAUNCH_CREATE_MULTIPLE", "LAUNCH", e(1.5, 3.0, 6.0, 12.0), "launch_phase") { ts, _, now -> launchPhase(ts, now)?.createMultiple?.let { pos(it) } },
        v("LAUNCH_VS_RECENT_PEAK", "LAUNCH", e(0.5, 0.75, 0.9, 0.98), "launch_phase") { ts, _, now -> launchPhase(ts, now)?.currentVsRecentPeak?.let { pos(it) } },
        v("LAUNCH_SMART_BUYERS", "LAUNCH", COUNT, "launch_phase") { ts, _, now -> launchPhase(ts, now)?.smartMoneyBuyers60s?.toDouble() },
        v("LAUNCH_REPEAT_BUYERS", "LAUNCH", COUNT, "launch_phase") { ts, _, now -> launchPhase(ts, now)?.repeatBuyerWallets60s?.toDouble() },
        v("LAUNCH_TOP3_SHARE", "LAUNCH", e(20.0, 35.0, 50.0, 70.0), "launch_phase") { ts, _, now -> launchPhase(ts, now)?.top3BuyerSharePct60s?.let { pos(it) } },
        v("MAYHEM", "LAUNCH", FLAG, "mayhem") { ts, _, _ -> flag(com.lifecyclebot.engine.MayhemMode7943.isMayhem7979(ts)) },
        v("MOONSHOT_HOLD_PROFILE", "LAUNCH", e(0.5, 1.5), "moonshot_profile") { ts, _, _ ->
            com.lifecyclebot.engine.truth.MoonshotHoldProfileRegistry6415.profile(ts.mint).ordinal.toDouble()
        },
        v("LANE_ADMISSION_SCORE", "LAUNCH", SCORE, "lane_admission") { ts, _, now -> com.lifecyclebot.engine.truth.LaneScoreAdmission7308.forMint(ts.mint, now)?.score?.let { fin(it) } },
        v("DIST_FADE_PENALTY", "LAUNCH", e(2.5, 10.0, 20.0), "dist_fade") { ts, _, _ -> com.lifecyclebot.engine.DistributionFadeAvoider.getSuppressionPenalty(ts.mint).toDouble() },
        v("EDGE_VETO_ACTIVE", "LAUNCH", FLAG, "fdg_memory") { ts, _, _ -> flag(com.lifecyclebot.engine.FinalDecisionGate.hasActiveEdgeVeto(ts.mint) != null) },
        v("DIST_COOLDOWN", "LAUNCH", FLAG, "fdg_memory") { ts, _, _ -> flag(com.lifecyclebot.engine.FinalDecisionGate.isInDistributionCooldown(ts.mint)) },
        v("SECOND_MOON_HOT", "LAUNCH", FLAG, "reentry") { ts, _, _ -> flag(com.lifecyclebot.engine.ReentryGuard.isSecondMoonHot(ts.mint)) },
        v("DEAD_QUARANTINE", "LAUNCH", FLAG, "dead_token") { ts, _, _ -> flag(com.lifecyclebot.engine.DeadTokenQuarantine.isDead(ts.mint)) },
        v("SOCIAL_TRUST", "LAUNCH", e(0.8, 0.95, 1.05, 1.15), "social_trust") { ts, _, _ -> fin(com.lifecyclebot.engine.TokenSocialScorer.getTrustForMint(ts.mint)) },
        v("ML_ENTRY_CONFIDENCE", "FORECASTER", PROB, "on_device_ml") { ts, _, now ->
            val h = ts.history.toList().takeLast(30)
            if (h.size < 5) null else com.lifecyclebot.ml.OnDeviceMLEngine.predict(
                h, ts.lastLiquidityUsd, ts.lastMcap, h.last().holderCount, ts.holderGrowthRate, ts.safety.rugcheckScore,
                ts.safety.mintAuthorityDisabled == true, ts.safety.freezeAuthorityDisabled == true, ts.safety.topHolderPct,
                ts.meta.rsi, ts.meta.emafanAlignment, ageMs(ts, now) / 60_000L,
            ).takeIf { it.trajectoryClass != "BOOTSTRAP" && it.trajectoryClass != "UNAVAILABLE" }?.entryConfidence?.toDouble()
        },
        // ── raw TokenState fields no voter read ──
        v("META_VELOCITY", "TOKEN", e(10.0, 30.0, 60.0, 100.0), "meta_velocity") { ts, _, _ -> fin(ts.meta.velocityScore) },
        v("META_CURVE_PROGRESS", "TOKEN", e(20.0, 50.0, 80.0, 95.0), "meta_curve") { ts, _, _ -> pos(ts.meta.curveProgress) },
        v("META_POS_IN_RANGE", "TOKEN", e(20.0, 40.0, 60.0, 80.0), "meta_range") { ts, _, _ -> fin(ts.meta.posInRange) },
        v("META_RANGE_PCT", "TOKEN", e(5.0, 15.0, 30.0, 60.0), "meta_range") { ts, _, _ -> pos(ts.meta.rangePct) },
        v("META_LOWER_HIGHS", "TOKEN", FLAG, "meta_structure") { ts, _, _ -> flag(ts.meta.lowerHighs) },
        v("META_BREAKDOWN", "TOKEN", FLAG, "meta_structure") { ts, _, _ -> flag(ts.meta.breakdown) },
        v("META_EXHAUSTION", "TOKEN", FLAG, "meta_exhaustion") { ts, _, _ -> flag(ts.meta.exhaustion) },
        v("META_SPIKE_TOP", "TOKEN", FLAG, "meta_exhaustion") { ts, _, _ -> flag(ts.meta.spikeDetected) },
        v("META_EMA_FAN", "TOKEN", e(-1.5, -0.5, 0.5, 1.5), "meta_ema") { ts, _, _ ->
            when (ts.meta.emafanAlignment) { "BULL_FAN" -> 2.0; "BULL_FLAT" -> 1.0; "FLAT" -> 0.0; "BEAR_FLAT" -> -1.0; "BEAR_FAN" -> -2.0; else -> null }
        },
        v("META_CHART_PATTERN_CONF", "TOKEN", SCORE, "meta_pattern") { ts, _, _ -> pos(ts.meta.chartPatternConf) },
        v("SELL_PRESSURE", "TOKEN", e(30.0, 45.0, 55.0, 70.0), "sell_pressure") { ts, _, _ -> fin(ts.lastSellPressurePct) },
        v("FDV_TO_MCAP", "TOKEN", e(1.01, 1.2, 2.0, 5.0), "fdv") { ts, _, _ -> if (ts.lastFdv > 0.0 && ts.lastMcap > 0.0) ts.lastFdv / ts.lastMcap else null },
        v("HOLDER_BLEED", "TOKEN", e(0.7, 0.85, 0.95, 0.99), "holder_bleed") { ts, _, _ ->
            val now = ts.history.lastOrNull()?.holderCount ?: 0
            if (ts.peakHolderCount >= 10 && now > 0) now.toDouble() / ts.peakHolderCount else null
        },
        v("EXIT_SCORE_AT_ENTRY", "TOKEN", SCORE, "exit_score") { ts, _, _ -> pos(ts.exitScore) },
        v("PRICE_MOMENTUM", "TOKEN", PCT, "momentum_raw") { ts, _, _ -> fin(ts.momentum) },
        v("PRICE_VS_LAST_EXIT", "TOKEN", e(0.7, 0.9, 1.1, 1.5), "reentry_price") { ts, _, _ ->
            if (ts.lastExitPrice > 0.0 && ts.lastPrice > 0.0) ts.lastPrice / ts.lastExitPrice else null
        },
        v("LAST_EXIT_WAS_WIN", "TOKEN", FLAG, "reentry_price") { ts, _, _ -> if (ts.lastExitPrice > 0.0) flag(ts.lastExitWasWin) else null },
        v("CANDLE_UPPER_WICK", "TOKEN", e(0.1, 0.25, 0.4, 0.6), "candle") { ts, _, _ -> lastCandle(ts)?.upperWickRatio?.let { fin(it) } },
        v("CANDLE_BUY_RATIO", "TOKEN", e(0.40, 0.50, 0.60, 0.70), "candle") { ts, _, _ -> lastCandle(ts)?.takeIf { it.buysH1 + it.sellsH1 > 0 }?.buyRatio?.let { fin(it) } },
        v("TREND_5M_CANDLES", "TOKEN", WIDE_PCT, "htf_trend") { ts, _, _ -> trend(ts.history5m) },
        v("TREND_15M_CANDLES", "TOKEN", WIDE_PCT, "htf_trend") { ts, _, _ -> trend(ts.history15m) },
        v("CONSENSUS_OBJECTIONS", "TOKEN", COUNT, "consensus_obj") { ts, _, _ -> ts.lastConsensusObjections.size.toDouble() },
        v("WATCHLIST_AGE_MIN", "TOKEN", e(2.0, 10.0, 30.0, 120.0), "watch_age") { ts, _, now -> ((now - ts.addedToWatchlistAt).coerceAtLeast(0L) / 60_000.0) },
        v("VOL_ACCEL_5M_VS_1H", "TOKEN", e(0.5, 1.0, 2.0, 4.0), "vol_accel") { ts, _, _ ->
            val v5 = ts.tokenMap.volume5mUsd ?: return@v null; val v1h = ts.tokenMap.volume1hUsd ?: return@v null
            if (v5 > 0.0 && v1h > 0.0) v5 * 12.0 / v1h else null
        },
        v("VOL_1H_TO_LIQ", "TOKEN", e(0.5, 1.0, 3.0, 10.0), "vol_accel") { ts, _, _ ->
            val v1h = ts.tokenMap.volume1hUsd ?: return@v null
            if (v1h > 0.0 && ts.lastLiquidityUsd > 0.0) v1h / ts.lastLiquidityUsd else null
        },
        v("CURVE_REAL_SOL", "TOKEN", e(5.0, 20.0, 50.0, 80.0), "meta_curve") { ts, _, _ -> pos(ts.tokenMap.realSolReserves) },
        v("GRADUATED", "TOKEN", FLAG, "graduated") { ts, _, _ -> flag(ts.tokenMap.migratedOrGraduated) },
        v("TOKEN_2022", "TOKEN", FLAG, "token2022") { ts, _, _ -> flag(ts.tokenMap.token2022) },
        v("MINT_AUTHORITY_LIVE", "SAFETY", FLAG, "authorities") { ts, _, _ -> ts.safety.mintAuthorityDisabled?.let { flag(!it) } },
        v("FREEZE_AUTHORITY_LIVE", "SAFETY", FLAG, "authorities") { ts, _, _ -> ts.safety.freezeAuthorityDisabled?.let { flag(!it) } },
        v("SAFETY_TIER", "SAFETY", e(0.5, 1.5, 2.5), "safety_tier") { ts, _, _ -> ts.safety.tier.ordinal.toDouble() },
        v("BUNDLE_RISK", "SAFETY", e(0.5, 1.5), "bundle_risk") { ts, _, _ ->
            when (ts.safety.bundleRisk.uppercase()) { "LOW" -> 0.0; "MEDIUM" -> 1.0; "HIGH" -> 2.0; else -> null }
        },
        v("SOFT_PENALTY_COUNT", "SAFETY", COUNT, "safety_soft") { ts, _, _ -> ts.safety.softPenalties.size.toDouble() },
        v("NAME_FLAGGED", "SAFETY", FLAG, "safety_name") { ts, _, _ -> flag(ts.safety.nameFlag.isNotBlank()) },
        v("DATA_CONFLICT", "SAFETY", FLAG, "safety_conflict") { ts, _, _ -> flag(ts.safety.dataConflictFlag) },
        v("X_VELOCITY", "SOCIAL", e(0.1, 0.5, 2.0, 5.0), "hype") { ts, _, _ -> pos(ts.sentiment.xVelocity) },
        v("TELEGRAM_VELOCITY", "SOCIAL", e(0.1, 0.5, 2.0, 5.0), "hype") { ts, _, _ -> pos(ts.sentiment.telegramVelocity) },
        v("WALLET_CONCENTRATION", "SOCIAL", e(20.0, 40.0, 60.0, 80.0), "sent_concentration") { ts, _, _ -> pos(ts.sentiment.walletConcentration) },
        v("SENTIMENT_DIVERGENCE", "SOCIAL", FLAG, "sent_divergence") { ts, _, _ -> flag(ts.sentiment.divergenceSignal) },
        v("SENTIMENT_DECAYED", "SOCIAL", e(-0.3, -0.05, 0.05, 0.3), "sentiment") { ts, _, _ -> fin(ts.sentiment.decayedScore)?.takeIf { it != 0.0 } },
    )
}
