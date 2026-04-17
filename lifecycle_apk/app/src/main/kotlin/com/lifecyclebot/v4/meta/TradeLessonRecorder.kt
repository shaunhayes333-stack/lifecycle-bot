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

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD — Full causal chain capture
    // ═══════════════════════════════════════════════════════════════════════

    fun record(lesson: TradeLesson) {
        synchronized(allLessons) {
            allLessons.add(lesson)
            if (allLessons.size > MAX_LESSONS_PER_LANE * 6) allLessons.removeAt(0)
        }

        // Route to strategy lane
        addToLane(strategyLane, lesson.strategy, lesson)

        // Route to regime lane
        addToLane(regimeLane, lesson.entryRegime.name, lesson)

        // Route to execution lane
        addToLane(executionLane, lesson.executionRoute, lesson)

        // Route to leverage lane
        val levKey = when {
            lesson.leverageUsed <= 1.0 -> "SPOT"
            lesson.leverageUsed <= 2.0 -> "LOW_LEV"
            lesson.leverageUsed <= 5.0 -> "MED_LEV"
            else -> "HIGH_LEV"
        }
        addToLane(leverageLane, levKey, lesson)

        // Route to narrative lane
        val narrativeTheme = NarrativeFlowAI.getNarrativeForSymbol(lesson.symbol)?.theme
        if (narrativeTheme != null) {
            addToLane(narrativeLane, narrativeTheme, lesson)
        }

        // Route to rotation lane
        if (lesson.leadSource != null) {
            addToLane(rotationLane, lesson.leadSource, lesson)
        }

        // Feed to StrategyTrustAI
        StrategyTrustAI.recordTrade(lesson)

        // Feed to QuantMind V2
        try { com.lifecyclebot.engine.quant.QuantMindV2.recordTrade(lesson) } catch (_: Exception) {}

        // Feed leveraged trades to LeverageSurvivalAI
        if (lesson.leverageUsed > 1.0) {
            LeverageSurvivalAI.recordLeveragedTrade(
                leverage = lesson.leverageUsed,
                outcomePct = lesson.outcomePct,
                holdSec = lesson.holdSec,
                wasLiquidated = lesson.exitReason == "LIQUIDATED",
                maePct = lesson.maePct
            )
        }

        // V5.7.8: Persist to Turso (fire and forget)
        tursoClient?.let { client ->
            GlobalScope.launch(Dispatchers.IO) {
                try { client.saveTradeLesson(lesson) } catch (_: Exception) {}
            }
        }

        ErrorLogger.debug(TAG, "Recorded lesson: ${lesson.strategy}/${lesson.symbol} " +
            "outcome=${String.format("%.2f", lesson.outcomePct)}% " +
            "regime=${lesson.entryRegime} exit=${lesson.exitReason}")
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
            trustScore = StrategyTrustAI.getTrustScore(strategy),
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
        actualFillPrice: Double
    ) {
        val lesson = TradeLesson(
            id = "LESSON_${System.currentTimeMillis()}",
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
            slippagePct = if (context.expectedFillPrice > 0) {
                kotlin.math.abs(actualFillPrice - context.expectedFillPrice) / context.expectedFillPrice * 100
            } else 0.0,
            executionRoute = context.executionRoute
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
        val captureTime: Long
    )

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
            lessons.forEach { lesson ->
                synchronized(allLessons) { allLessons.add(lesson) }
                addToLane(strategyLane, lesson.strategy, lesson)
                addToLane(regimeLane, lesson.entryRegime.name, lesson)
                addToLane(executionLane, lesson.executionRoute, lesson)
                // V5.9.8: Also feed into StrategyTrustAI so trust scores update from historical data
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
}
