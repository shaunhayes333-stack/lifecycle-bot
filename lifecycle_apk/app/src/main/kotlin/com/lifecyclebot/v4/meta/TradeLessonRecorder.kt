package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ===============================================================================
 * TRADE LESSON RECORDER — V4 Causal Chain Learning
 * ===============================================================================
 *
 * Records the FULL causal chain for every trade, not just "won/lost".
 * EducationAI should update separate memory lanes:
 *   - strategy-quality memory
 *   - regime-fit memory
 *   - execution-quality memory
 *   - leverage-survival memory
 *   - narrative-persistence memory
 *   - cross-asset rotation memory
 *
 * This avoids poisoning the whole system from one bad area.
 *
 * ===============================================================================
 */
object TradeLessonRecorder {

    private const val TAG = "TradeLessonRecorder"
    private const val MAX_LESSONS_PER_LANE = 500

    // Turso client reference for persistence
    var tursoClient: com.lifecyclebot.collective.TursoClient? = null

    // Separate memory lanes
    private val strategyLane = ConcurrentHashMap<String, MutableList<TradeLesson>>()
    private val regimeLane = ConcurrentHashMap<String, MutableList<TradeLesson>>()       // By regime
    private val executionLane = ConcurrentHashMap<String, MutableList<TradeLesson>>()     // By venue
    private val leverageLane = ConcurrentHashMap<String, MutableList<TradeLesson>>()      // By leverage level
    private val narrativeLane = ConcurrentHashMap<String, MutableList<TradeLesson>>()     // By narrative
    private val rotationLane = ConcurrentHashMap<String, MutableList<TradeLesson>>()      // By lead source

    // All lessons (master list)
    private val allLessons = mutableListOf<TradeLesson>()

    /** Both prices must describe the same sell order. Entry-to-exit return is not slippage. */
    internal fun observedSlippage8491(expectedExit: Double?, actualExit: Double): Double? =
        expectedExit?.takeIf { it.isFinite() && it > 0.0 && actualExit.isFinite() && actualExit > 0.0 }
            ?.let { kotlin.math.abs(actualExit - it) / it * 100.0 }

    /** Called at entry, before an asynchronous execution can change the token/context. */
    fun captureMemeEntry8491(mint: String, symbol: String, lane: String, mode: String, price: Double): String {
        val c = liveContext6859(lane, "MEME", symbol, mint, price, System.currentTimeMillis(),
            executionRoute = if (mode.equals("PAPER", true)) "PAPER" else "LIVE")
        return org.json.JSONObject().apply {
            put("symbol", symbol); put("regime", c.entryRegime.name); put("session", c.entrySession.name)
            put("trust", c.trustScore); put("fragility", c.fragilityScore); put("narrative", c.narrativeHeat)
            put("heat", c.portfolioHeat); put("execution", c.executionConfidence)
            put("capturedAt", c.captureTime)
        }.toString()
    }

    /** Existing canonical bus owns startup registration, proof gates, retry and durable ACKs. */
    fun onCanonicalMeme8492(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        fun exclude(reason: String): Boolean {
            com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.exclude("V4TradeLesson8492", env.tradeId, reason)
            return false
        }
        if (env.assetClassTag != "SOLANA_TOKEN") return exclude("V4_NOT_MEME_8492")
        if (!com.lifecyclebot.engine.LearningPersistence.restoreComplete8086()) return false
        val e = com.lifecyclebot.engine.truth.CanonicalFinalityPersistence6486.durableEventForPosition7459(env.positionId) ?: return false
        if (e.mint != env.mint || !e.mode.equals(env.mode, true)) return exclude("V4_TERMINAL_IDENTITY_MISMATCH_8492")
        if (!com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.isCleanForLearning7807(e)) return exclude("V4_UNCLEAN_TERMINAL_8492")
        val entry = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(e.positionId) ?: return false
        if (canonicalMemeLesson8491(e, entry) == null) return exclude("V4_UNSUPPORTED_TERMINAL_8492")
        return acceptCanonicalMeme8492(e, entry, com.lifecyclebot.engine.LearningPersistence::checkpointTradeLesson8492)
    }

    internal fun acceptCanonicalMeme8492(
        e: com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.Event,
        entry: com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot,
        persist: (String) -> Boolean,
    ): Boolean {
        val lesson = canonicalMemeLesson8491(e, entry) ?: return false
        record(lesson)
        return persist(lesson.id)
    }

    /** One immutable position, one mode, one terminal net result. No mutable watchlist reads. */
    internal fun canonicalMemeLesson8491(
        e: com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.Event,
        entry: com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot,
    ): TradeLesson? {
        if (entry.assetClassTag != "SOLANA_TOKEN" || entry.positionId != e.positionId || entry.mint != e.mint) return null
        val mode = e.mode.uppercase()
        if (mode !in setOf("LIVE", "PAPER")) return null
        if (entry.mode8090.uppercase() in setOf("LIVE", "PAPER") && !entry.mode8090.equals(mode, true)) return null
        if (e.assetClassTag.isNotBlank() && e.assetClassTag != "SOLANA_TOKEN") return null
        if (e.economicFinality !in if (mode == "LIVE") setOf("LIVE_FINALIZED", "LIVE_RECONCILED") else setOf("PAPER_SIMULATED")) return null
        if (!e.netReturnPct.isFinite()) return null
        val c = try { org.json.JSONObject(entry.v4EntryContext8491) } catch (_: Exception) { null }
        val observed = c != null && c.optLong("capturedAt") > 0L
        fun value(key: String): Double = c?.optDouble(key, 0.5)?.takeIf { it.isFinite() } ?: 0.5
        return TradeLesson(
            id = "MEME:$mode:${e.positionId}", strategy = entry.entryLane, market = "MEME",
            symbol = c?.optString("symbol")?.takeIf { it.isNotBlank() } ?: e.mint,
            entryRegime = try { GlobalRiskMode.valueOf(c?.optString("regime").orEmpty()) } catch (_: Exception) { GlobalRiskMode.RISK_ON },
            entrySession = try { SessionContext.valueOf(c?.optString("session").orEmpty()) } catch (_: Exception) { SessionContext.OFF_HOURS },
            trustScore = value("trust"), fragilityScore = value("fragility"), narrativeHeat = value("narrative"),
            portfolioHeat = value("heat"), leverageUsed = 1.0, executionConfidence = value("execution"),
            leadSource = null, expectedDelaySec = null, outcomePct = e.netReturnPct,
            mfePct = e.mfePct.takeIf { it.isFinite() } ?: 0.0, maePct = e.maePct.takeIf { it.isFinite() } ?: 0.0,
            mfeObserved8491 = e.mfePct.isFinite(), maeObserved8491 = e.maePct.isFinite(),
            holdSec = (e.holdingTimeMs.coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            exitReason = e.exitReason, expectedFillPrice = entry.entryPriceUsd,
            // This event has net economics and excursions, not a sell quote/fill pair.
            actualFillPrice = 0.0, slippagePct = 0.0, executionRoute = mode,
            timestamp = e.settledAtMs, entryContextObserved8491 = observed, slippageObserved8491 = false,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD — Full causal chain capture
    // ═══════════════════════════════════════════════════════════════════════

    private fun paperEvidence7803(lesson: TradeLesson): Boolean =
        lesson.strategy.startsWith("PAPER::") || lesson.executionRoute.uppercase().contains("PAPER")

    private fun lessonLaneKey7803(lesson: TradeLesson, raw: String): String =
        if (paperEvidence7803(lesson) && !raw.startsWith("PAPER::")) "PAPER::$raw" else raw

    private fun normalizeLessonMode7803(lesson: TradeLesson): TradeLesson {
        if (!paperEvidence7803(lesson)) return lesson
        val strategy = if (lesson.strategy.startsWith("PAPER::")) lesson.strategy else "PAPER::" + lesson.strategy
        val route = if (lesson.executionRoute.startsWith("PAPER::")) lesson.executionRoute else "PAPER::" + lesson.executionRoute
        return if (strategy == lesson.strategy && route == lesson.executionRoute) lesson
        else lesson.copy(strategy = strategy, executionRoute = route)
    }

    fun record(lesson: TradeLesson) {
        // V5.0.7803 audit — PAPER lessons are real learning evidence, but they
        // are not LIVE proof. Namespace their strategy identity before any
        // trust write and keep them out of mode-agnostic live-risk learners.
        val isPaperEvidence7803 = paperEvidence7803(lesson)
        val routedLesson7803 = normalizeLessonMode7803(lesson)
        synchronized(allLessons) {
            if (allLessons.any { it.id == routedLesson7803.id }) return
            allLessons.add(routedLesson7803)
            if (allLessons.size > MAX_LESSONS_PER_LANE * 6) allLessons.removeAt(0)
        }

        // Route to strategy lane
        addToLane(strategyLane, routedLesson7803.strategy, routedLesson7803)

        // Route to regime lane
        addToLane(regimeLane, lessonLaneKey7803(routedLesson7803, routedLesson7803.entryRegime.name), routedLesson7803)

        // Route to execution lane
        addToLane(executionLane, routedLesson7803.executionRoute, routedLesson7803)

        // Route to leverage lane
        val levKey = when {
            routedLesson7803.leverageUsed <= 1.0 -> "SPOT"
            routedLesson7803.leverageUsed <= 2.0 -> "LOW_LEV"
            routedLesson7803.leverageUsed <= 5.0 -> "MED_LEV"
            else -> "HIGH_LEV"
        }
        addToLane(leverageLane, lessonLaneKey7803(routedLesson7803, levKey), routedLesson7803)

        // Route to narrative lane
        val narrativeTheme = NarrativeFlowAI.getNarrativeForSymbol(routedLesson7803.symbol)?.theme
        if (narrativeTheme != null) {
            addToLane(narrativeLane, lessonLaneKey7803(routedLesson7803, narrativeTheme), routedLesson7803)
        }

        // Route to rotation lane
        if (routedLesson7803.leadSource != null) {
            addToLane(rotationLane, lessonLaneKey7803(routedLesson7803, routedLesson7803.leadSource), routedLesson7803)
        }

        // Feed to StrategyTrustAI
        StrategyTrustAI.recordTrade(routedLesson7803)

        // Feed to QuantMind V2
        if (!isPaperEvidence7803) try { com.lifecyclebot.engine.quant.QuantMindV2.recordTrade(routedLesson7803) } catch (_: Exception) {}

        // Feed leveraged trades to LeverageSurvivalAI
        if (!isPaperEvidence7803 && routedLesson7803.leverageUsed > 1.0) {
            LeverageSurvivalAI.recordLeveragedTrade(
                leverage = routedLesson7803.leverageUsed,
                outcomePct = routedLesson7803.outcomePct,
                holdSec = routedLesson7803.holdSec,
                wasLiquidated = routedLesson7803.exitReason == "LIQUIDATED",
                maePct = routedLesson7803.maePct
            )
        }

        // V5.7.8: Persist to Turso (fire and forget)
        tursoClient?.let { client ->
            GlobalScope.launch(Dispatchers.IO) {
                try { client.saveTradeLesson(routedLesson7803) } catch (_: Exception) {}
            }
        }

        ErrorLogger.debug(TAG, "Recorded lesson: ${routedLesson7803.strategy}/${routedLesson7803.symbol} " +
            "outcome=${String.format("%.2f", routedLesson7803.outcomePct)}% " +
            "regime=${routedLesson7803.entryRegime} exit=${routedLesson7803.exitReason}")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAPTURE — Build a TradeLesson from current context
    // ═══════════════════════════════════════════════════════════════════════

    fun captureContext(
        strategy: String,
        market: String,
        symbol: String,
        leverageUsed: Double,
        executionRoute: String,
        expectedFillPrice: Double
    ): TradeLessonContext {
        val snapshot = CrossTalkFusionEngine.getSnapshot()
        val leadLag = CrossAssetLeadLagAI.getLeadSignalFor(symbol)

        return TradeLessonContext(
            strategy = strategy,
            market = market,
            symbol = symbol,
            entryRegime = snapshot?.globalRiskMode ?: GlobalRiskMode.RISK_ON,
            entrySession = snapshot?.sessionContext ?: SessionContext.OFF_HOURS,
            trustScore = StrategyTrustAI.getTrustScore(if (executionRoute.uppercase().contains("PAPER")) "PAPER::$strategy" else strategy),
            fragilityScore = LiquidityFragilityAI.getFragilityScore(symbol),
            narrativeHeat = NarrativeFlowAI.getNarrativeHeat(symbol),
            portfolioHeat = PortfolioHeatAI.getPortfolioHeat(),
            leverageUsed = leverageUsed,
            executionConfidence = ExecutionPathAI.getExecutionConfidenceMultiplier(),
            leadSource = leadLag?.leader,
            expectedDelaySec = leadLag?.expectedDelaySec,
            executionRoute = executionRoute,
            expectedFillPrice = expectedFillPrice,
            captureTime = System.currentTimeMillis()
        )
    }

    /**
     * Complete a lesson after trade closes. Call this with the context
     * captured at entry time plus the actual outcome.
     */
    fun completeLesson(
        context: TradeLessonContext,
        outcomePct: Double,
        mfePct: Double,
        maePct: Double,
        holdSec: Int,
        exitReason: String,
        actualFillPrice: Double,
        expectedExitFillPrice8491: Double? = null,
    ) {
        val lesson = TradeLesson(
            id = "LESSON:${context.executionRoute}:${context.market}:${context.strategy}:${context.symbol}:${context.captureTime}",
            strategy = context.strategy,
            market = context.market,
            symbol = context.symbol,
            entryRegime = context.entryRegime,
            entrySession = context.entrySession,
            trustScore = context.trustScore,
            fragilityScore = context.fragilityScore,
            narrativeHeat = context.narrativeHeat,
            portfolioHeat = context.portfolioHeat,
            leverageUsed = context.leverageUsed,
            executionConfidence = context.executionConfidence,
            leadSource = context.leadSource,
            expectedDelaySec = context.expectedDelaySec,
            outcomePct = outcomePct,
            mfePct = mfePct,
            maePct = maePct,
            holdSec = holdSec,
            exitReason = exitReason,
            expectedFillPrice = context.expectedFillPrice,
            actualFillPrice = actualFillPrice,
            slippagePct = observedSlippage8491(expectedExitFillPrice8491, actualFillPrice) ?: 0.0,
            executionRoute = context.executionRoute,
            slippageObserved8491 = observedSlippage8491(expectedExitFillPrice8491, actualFillPrice) != null,
            entryContextObserved8491 = context.entryContextObserved8491
        )
        record(lesson)
    }

    // Context captured at trade entry
    data class TradeLessonContext(
        val strategy: String,
        val market: String,
        val symbol: String,
        val entryRegime: GlobalRiskMode,
        val entrySession: SessionContext,
        val trustScore: Double,
        val fragilityScore: Double,
        val narrativeHeat: Double,
        val portfolioHeat: Double,
        val leverageUsed: Double,
        val executionConfidence: Double,
        val leadSource: String?,
        val expectedDelaySec: Int?,
        val executionRoute: String,
        val expectedFillPrice: Double,
        val captureTime: Long,
        val entryContextObserved8491: Boolean = false,
    )

    /**
     * V5.0.6859 §THE_CAUSAL_CHAIN_RECORDER_WAS_FED_CONSTANTS — this class exists to
     * record "the FULL causal chain for every trade, not just won/lost", and its
     * three meme call sites all built the context out of hardcoded literals:
     *
     *   entryRegime = GlobalRiskMode.RISK_ON, entrySession = SessionContext.OFF_HOURS,
     *   trustScore = 0.5, fragilityScore = 0.3, narrativeHeat = 0.5,
     *   portfolioHeat = 0.3, leverageUsed = 1.0, executionConfidence = 0.6
     *
     * (Executor:21383 and :24213 vary only executionConfidence and narrativeHeat;
     * MoonshotTraderAI:1330 hardcodes all eight.) Every lesson in the corpus was
     * therefore stamped with the same context, so the separate memory lanes this
     * class was built to keep apart — regime-fit, execution-quality,
     * narrative-persistence — had nothing to separate. StrategyTrustAI reads that
     * corpus, and the collective/Turso hive syncs it, so the constants propagated
     * into the network hive mind as well.
     *
     * Every one of those values has a real live source, and after V5.0.6853 the two
     * that used to be genuinely unavailable (fragility and portfolio heat) are fed.
     * This factory resolves them; each read is independently fail-soft so a single
     * unavailable subsystem degrades one field instead of the whole lesson.
     */
    fun liveContext6859(
        strategy: String,
        market: String,
        symbol: String,
        mint: String = "",
        expectedFillPrice: Double,
        captureTime: Long,
        executionRoute: String = "JUPITER_V6",
        leverageUsed: Double = 1.0,
        leadSource: String? = null,
        expectedDelaySec: Int? = null,
    ): TradeLessonContext {
        val regime = try {
            CrossMarketRegimeAI.getCurrentRegime()
        } catch (_: Throwable) { GlobalRiskMode.RISK_ON }
        val session = try {
            CrossTalkFusionEngine.currentSession6859()
        } catch (_: Throwable) { SessionContext.OFF_HOURS }
        val trust = try {
            StrategyTrustAI.getTrustScore(if (executionRoute.uppercase().contains("PAPER")) "PAPER::$strategy" else strategy)
        } catch (_: Throwable) { 0.5 }
        val fragility = try {
            LiquidityFragilityAI.getFragilityScoreFor(mint, symbol)
        } catch (_: Throwable) { 0.3 }
        val narrative = try {
            NarrativeFlowAI.getNarrativeHeat(symbol)
        } catch (_: Throwable) { 0.5 }
        val heat = try {
            PortfolioHeatAI.getPortfolioHeat()
        } catch (_: Throwable) { 0.3 }
        val execConf = try {
            (ExecutionPathAI.getExecutionConfidenceMultiplier() / 2.0).coerceIn(0.0, 1.0)
        } catch (_: Throwable) { 0.6 }
        return TradeLessonContext(
            strategy = strategy,
            market = market,
            symbol = symbol,
            entryRegime = regime,
            entrySession = session,
            trustScore = trust,
            fragilityScore = fragility,
            narrativeHeat = narrative,
            portfolioHeat = heat,
            leverageUsed = leverageUsed,
            executionConfidence = execConf,
            leadSource = leadSource,
            expectedDelaySec = expectedDelaySec,
            executionRoute = executionRoute,
            expectedFillPrice = expectedFillPrice,
            captureTime = captureTime,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY — Lane-specific analysis
    // ═══════════════════════════════════════════════════════════════════════

    fun getStrategyLessons(strategy: String): List<TradeLesson> =
        strategyLane[strategy]?.toList() ?: emptyList()

    fun getRegimeLessons(regime: GlobalRiskMode): List<TradeLesson> =
        regimeLane[regime.name]?.toList() ?: emptyList()

    fun getLeverageLessons(leverageKey: String): List<TradeLesson> =
        leverageLane[leverageKey]?.toList() ?: emptyList()

    fun getNarrativeLessons(theme: String): List<TradeLesson> =
        narrativeLane[theme]?.toList() ?: emptyList()

    fun getWinRateForLane(lane: Map<String, MutableList<TradeLesson>>, key: String): Double {
        val lessons = lane[key] ?: return 0.5
        if (lessons.isEmpty()) return 0.5
        return lessons.count { it.outcomePct > 0 }.toDouble() / lessons.size
    }

    fun getTotalLessons(): Int = synchronized(allLessons) { allLessons.size }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun addToLane(lane: ConcurrentHashMap<String, MutableList<TradeLesson>>, key: String, lesson: TradeLesson) {
        val list = lane.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            list.add(lesson)
            if (list.size > MAX_LESSONS_PER_LANE) list.removeAt(0)
        }
    }

    fun clear() {
        synchronized(allLessons) { allLessons.clear() }
        strategyLane.clear()
        regimeLane.clear()
        executionLane.clear()
        leverageLane.clear()
        narrativeLane.clear()
        rotationLane.clear()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // V5.7.8: TURSO PERSISTENCE — Load/Save
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun loadFromTurso() {
        val client = tursoClient ?: return
        try {
            // Load trade lessons
            val lessons = client.loadRecentTradeLessons(limit = 500)
            lessons.forEach { rawLesson ->
                val lesson = normalizeLessonMode7803(rawLesson)
                val added = synchronized(allLessons) {
                    if (allLessons.any { it.id == lesson.id }) false else { allLessons.add(lesson); true }
                }
                if (!added) return@forEach
                addToLane(strategyLane, lesson.strategy, lesson)
                addToLane(regimeLane, lessonLaneKey7803(lesson, lesson.entryRegime.name), lesson)
                addToLane(executionLane, lessonLaneKey7803(lesson, lesson.executionRoute), lesson)
                // V5.0.7803 — restored PAPER evidence stays in PAPER trust namespace.
                try { StrategyTrustAI.recordTrade(lesson) } catch (_: Exception) {}
            }
            ErrorLogger.info(TAG, "Loaded ${lessons.size} trade lessons from Turso")

            // Load strategy trust records → feed to StrategyTrustAI
            val trustRecords = client.loadAllStrategyTrust()
            trustRecords.forEach { record ->
                StrategyTrustAI.restoreTrustRecord(record)
            }
            ErrorLogger.info(TAG, "Restored ${trustRecords.size} strategy trust records")

            // Load lead-lag pairs
            val pairs = client.loadLeadLagPairs()
            pairs.forEach { pair ->
                CrossAssetLeadLagAI.restorePair(pair)
            }
            ErrorLogger.info(TAG, "Restored ${pairs.size} lead-lag pairs")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Load from Turso failed: ${e.message}")
        }
    }

    suspend fun saveAllTrustToTurso() {
        val client = tursoClient ?: return
        try {
            StrategyTrustAI.getAllTrustScores().values.forEach { record ->
                client.saveStrategyTrust(record)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Save trust to Turso failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // V5.9.991 — LOCAL PERSISTENCE (Doctrine #25 amnesia close)
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Pre-V5.9.991 the only persistence was Turso (cloud). On startup
    // restoreFromTurso() loads the last 500 lessons IF the device is online
    // AND the Turso client is configured. If either fails, the entire
    // causal-chain learning corpus starts cold every restart — exactly the
    // amnesia pattern the doctrine forbids.
    //
    // This commit adds a local blob persistence path so the lessons survive
    // restarts even with no network / no Turso. Turso remains the canonical
    // cross-device sync layer; LearningPersistence is the local fallback.
    //
    // Strategy: serialize allLessons (the master list); per-lane indexes
    // are derived state, rebuilt on import via the same addToLane() helper
    // used by record(). This avoids duplicating the same lesson 6× across
    // 6 lane blobs.

    fun exportState(): String {
        return try {
            val snapshot = synchronized(allLessons) { allLessons.toList() }
            val arr = org.json.JSONArray()
            for (lesson in snapshot) {
                val o = org.json.JSONObject()
                o.put("id", lesson.id)
                o.put("strategy", lesson.strategy)
                o.put("market", lesson.market)
                o.put("symbol", lesson.symbol)
                o.put("entryRegime", lesson.entryRegime.name)
                o.put("entrySession", lesson.entrySession.name)
                o.put("trustScore", lesson.trustScore)
                o.put("fragilityScore", lesson.fragilityScore)
                o.put("narrativeHeat", lesson.narrativeHeat)
                o.put("portfolioHeat", lesson.portfolioHeat)
                o.put("leverageUsed", lesson.leverageUsed)
                o.put("executionConfidence", lesson.executionConfidence)
                if (lesson.leadSource != null) o.put("leadSource", lesson.leadSource)
                if (lesson.expectedDelaySec != null) o.put("expectedDelaySec", lesson.expectedDelaySec)
                o.put("outcomePct", lesson.outcomePct)
                o.put("mfePct", lesson.mfePct)
                o.put("maePct", lesson.maePct)
                o.put("holdSec", lesson.holdSec)
                o.put("exitReason", lesson.exitReason)
                o.put("expectedFillPrice", lesson.expectedFillPrice)
                o.put("actualFillPrice", lesson.actualFillPrice)
                o.put("slippagePct", lesson.slippagePct)
                o.put("executionRoute", lesson.executionRoute)
                o.put("timestamp", lesson.timestamp)
                o.put("entryContextObserved8491", lesson.entryContextObserved8491)
                o.put("slippageObserved8491", lesson.slippageObserved8491)
                o.put("maeObserved8491", lesson.maeObserved8491)
                o.put("mfeObserved8491", lesson.mfeObserved8491)
                arr.put(o)
            }
            val root = org.json.JSONObject()
            root.put("v", 1)
            root.put("lessons", arr)
            root.toString()
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "exportState failed: ${e.message}")
            "{}"
        }
    }

    fun importState(json: String) {
        try {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("lessons") ?: return
            var restored = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lesson = try {
                    TradeLesson(
                        id = o.optString("id", ""),
                        strategy = o.optString("strategy", "UNKNOWN"),
                        market = o.optString("market", "MEME"),
                        symbol = o.optString("symbol", "?"),
                        entryRegime = try { GlobalRiskMode.valueOf(o.optString("entryRegime", "CRUISE")) }
                                      catch (_: Throwable) { GlobalRiskMode.values().first() },
                        entrySession = try { SessionContext.valueOf(o.optString("entrySession", "UNKNOWN")) }
                                        catch (_: Throwable) { SessionContext.values().first() },
                        trustScore = o.optDouble("trustScore", 0.5),
                        fragilityScore = o.optDouble("fragilityScore", 0.5),
                        narrativeHeat = o.optDouble("narrativeHeat", 0.5),
                        portfolioHeat = o.optDouble("portfolioHeat", 0.0),
                        leverageUsed = o.optDouble("leverageUsed", 1.0),
                        executionConfidence = o.optDouble("executionConfidence", 0.5),
                        leadSource = o.optString("leadSource", "").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
                        expectedDelaySec = if (o.has("expectedDelaySec")) o.optInt("expectedDelaySec") else null,
                        outcomePct = o.optDouble("outcomePct", 0.0),
                        mfePct = o.optDouble("mfePct", 0.0),
                        maePct = o.optDouble("maePct", 0.0),
                        holdSec = o.optInt("holdSec", 0),
                        exitReason = o.optString("exitReason", "UNKNOWN"),
                        expectedFillPrice = o.optDouble("expectedFillPrice", 0.0),
                        actualFillPrice = o.optDouble("actualFillPrice", 0.0),
                        slippagePct = o.optDouble("slippagePct", 0.0),
                        executionRoute = o.optString("executionRoute", "UNKNOWN"),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        entryContextObserved8491 = o.optBoolean("entryContextObserved8491", false),
                        slippageObserved8491 = o.optBoolean("slippageObserved8491", false),
                        maeObserved8491 = o.optBoolean("maeObserved8491", false),
                        mfeObserved8491 = o.optBoolean("mfeObserved8491", false),
                    )
                } catch (_: Throwable) { continue }
                val normalizedLesson7803 = normalizeLessonMode7803(lesson)

                val added8491 = synchronized(allLessons) {
                    if (allLessons.any { it.id == normalizedLesson7803.id }) false else {
                        allLessons.add(normalizedLesson7803)
                        if (allLessons.size > MAX_LESSONS_PER_LANE * 6) allLessons.removeAt(0)
                        true
                    }
                }
                if (!added8491) continue
                // Rebuild per-lane indexes
                addToLane(strategyLane, normalizedLesson7803.strategy, normalizedLesson7803)
                addToLane(regimeLane, lessonLaneKey7803(normalizedLesson7803, normalizedLesson7803.entryRegime.name), normalizedLesson7803)
                addToLane(executionLane, lessonLaneKey7803(normalizedLesson7803, normalizedLesson7803.executionRoute), normalizedLesson7803)
                val levKey = when {
                    normalizedLesson7803.leverageUsed <= 1.0 -> "SPOT"
                    normalizedLesson7803.leverageUsed <= 2.0 -> "LOW_LEV"
                    normalizedLesson7803.leverageUsed <= 5.0 -> "MED_LEV"
                    else -> "HIGH_LEV"
                }
                addToLane(leverageLane, lessonLaneKey7803(normalizedLesson7803, levKey), normalizedLesson7803)
                if (normalizedLesson7803.leadSource != null) addToLane(rotationLane, lessonLaneKey7803(normalizedLesson7803, normalizedLesson7803.leadSource), normalizedLesson7803)
                // Local persistence must rebuild the same trust history without a network/Turso dependency.
                StrategyTrustAI.recordTrade(normalizedLesson7803)
                restored++
            }
            ErrorLogger.info(TAG, "importState: restored $restored lessons from blob")
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "importState failed: ${e.message}")
        }
    }
}
