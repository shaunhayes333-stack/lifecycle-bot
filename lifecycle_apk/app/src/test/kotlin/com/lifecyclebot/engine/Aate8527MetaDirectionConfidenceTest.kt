package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.MetaCognitionAI as Meta
import com.lifecyclebot.v3.scoring.ScoreComponent
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class Aate8527MetaDirectionConfidenceTest {
    @Before fun reset() = Meta.reset()
    @After fun cleanup() = Meta.reset()
    private val layer = Meta.AILayer.MOMENTUM_PREDICTOR
    private fun component(value: Int) = listOf(ScoreComponent("momentum", value, "observed"))
    private fun close(mint: String, pnl: Double) =
        Meta.recordTradeOutcome(mint, "TEST", pnl, 60_000L, "TEST_CONFIRMED")
    private fun outcome(id: String, win: Boolean) = CanonicalTradeOutcome(
        tradeId = id, mint = id, symbol = "TEST", assetClass = AssetClass.MEME,
        mode = TradeMode.MOONSHOT, source = TradeSource.V3, environment = TradeEnvironment.LIVE,
        entryTimeMs = 1_000L, exitTimeMs = 61_000L, entryPrice = 1.0,
        exitPrice = if (win) 1.1 else 0.9, entrySol = 1.0,
        exitSol = if (win) 1.1 else 0.9, realizedPnlSol = if (win) 0.1 else -0.1,
        realizedPnlPct = if (win) 10.0 else -10.0,
        maxGainPct = null, maxDrawdownPct = null, holdSeconds = 60L,
        result = if (win) TradeResult.WIN else TradeResult.LOSS,
        executionResult = ExecutionResult.EXECUTED, closeReason = "TEST",
    )

    @Test fun equalStrengthBullishAndBearishComponentsHaveEqualDirectionalConfidence() {
        val bull = Meta.predictionFromScore8527(layer, 15.0)!!
        val bear = Meta.predictionFromScore8527(layer, -15.0)!!
        assertEquals(Meta.SignalType.BULLISH, bull.signal)
        assertEquals(Meta.SignalType.BEARISH, bear.signal)
        assertEquals(80.0, bull.confidence, 0.0)
        assertEquals(80.0, bear.confidence, 0.0)
        assertEquals(0.8, Meta.upwardProbability8527("BULLISH", bull.confidence), 1e-12)
        assertEquals(0.2, Meta.upwardProbability8527("BEARISH", bear.confidence), 1e-12)
        assertEquals(-15.0, bear.rawScore, 0.0)
        assertEquals(100.0, Meta.predictionFromScore8527(layer, -1000.0)!!.confidence, 0.0)
    }

    @Test fun scorerAndRecorderShareTheSameNeutralBoundaryAndMissingInputContract() {
        for (value in listOf(-5.0, -4.0, 0.0, 4.0, 5.0)) {
            val p = Meta.predictionFromScore8527(layer, value)!!
            assertEquals(Meta.SignalType.NEUTRAL, p.signal)
            assertEquals(50.0, p.confidence, 0.0)
        }
        assertEquals(Meta.SignalType.BULLISH, Meta.predictionFromScore8527(layer, 6.0)!!.signal)
        assertEquals(Meta.SignalType.BEARISH, Meta.predictionFromScore8527(layer, -6.0)!!.signal)
        assertNull(Meta.predictionFromScore8527(layer, Double.NaN))
        assertNull(Meta.predictionFromScore8527(layer, Double.POSITIVE_INFINITY))
        Meta.recordEntryPredictions("bad", "BAD", mapOf(layer to (Meta.SignalType.BULLISH to Double.NaN)))
        close("bad", 10.0)
        assertEquals(0, Meta.saveToJson().getInt("totalTradesAnalyzed"))
    }

    @Test fun realOutcomeLearningDoesNotPunishACorrectBearishWarningForItsNegativeSign() {
        Meta.recordFromScoreCard("bull", "BULL", component(15))
        close("bull", 10.0)
        val bull = Meta.getAllLayerPerformance()[layer]!!
        val bullMeta = Meta.saveToJson().getDouble("metaCalibrationError")
        reset()
        Meta.recordFromScoreCard("bear", "BEAR", component(-15))
        close("bear", -10.0)
        val bear = Meta.getAllLayerPerformance()[layer]!!
        assertEquals(1, bear.correctPredictions)
        assertEquals(1, bear.bearishCalls)
        assertEquals(bull.calibrationError, bear.calibrationError, 1e-12)
        assertEquals(0.25 * 0.92 + 0.04 * 0.08, bear.calibrationError, 1e-12)
        assertEquals(bull.trustMultiplier, bear.trustMultiplier, 1e-12)
        assertEquals(bullMeta, Meta.saveToJson().getDouble("metaCalibrationError"), 1e-12)
        assertEquals(54.0, Meta.saveToJson().getDouble("metaAccuracy"), 1e-12)
    }

    @Test fun aWrongBearishWarningIsPenalizedAndCannotGainAccuracyFromAStrategyWin() {
        Meta.recordFromScoreCard("wrong", "WRONG", component(-15))
        close("wrong", 10.0)
        val bad = Meta.getAllLayerPerformance()[layer]!!
        assertEquals(0, bad.correctPredictions)
        assertEquals(1, bad.incorrectPredictions)
        assertEquals(0.25 * 0.92 + 0.64 * 0.08, bad.calibrationError, 1e-12)
        assertEquals(46.0, Meta.saveToJson().getDouble("metaAccuracy"), 1e-12)
    }

    @Test fun aliasesCannotGiveOneUnderlyingLayerTwoVotesOrDifferentLearningAndDecisionRosters() {
        val votes = Meta.predictionsFromComponents8527(listOf(
            ScoreComponent("volume", 15, "volume"),
            ScoreComponent("orderflow", -15, "flow"),
            ScoreComponent("feargreed", 10, "fear"),
            ScoreComponent("social", 8, "social"),
            ScoreComponent("liquiditycycle", 9, "liq"),
            ScoreComponent("collective_ai", 12, "hive"),
            ScoreComponent("source", 8, "not a metacog layer"),
        ))
        assertEquals(5, votes.size)
        assertEquals(1, votes.count { it.layer == Meta.AILayer.ORDER_FLOW_IMBALANCE })
        assertEquals(Meta.SignalType.BEARISH, votes.first { it.layer == Meta.AILayer.ORDER_FLOW_IMBALANCE }.signal)
        assertEquals(5, votes.map { it.layer }.distinct().size)
    }

    @Test fun calibrationUsesTheEntryForecastNotTheModelAfterItHasSeenLaterOutcomes() {
        val comps = listOf("momentum", "narrative", "liquidity").map { ScoreComponent(it, 15, "observed") }
        val predictions = Meta.predictionsFromComponents8527(comps)
        val atEntry = Meta.calculateMetaConfidence(predictions)
        Meta.recordFromScoreCard("frozen", "FROZEN", comps)
        repeat(30) { i ->
            Meta.recordFromScoreCard("intervening$i", "OTHER", comps)
            close("intervening$i", 10.0)
        }
        val latest = Meta.calculateMetaConfidence(predictions)
        assertTrue(kotlin.math.abs(atEntry.confidence - latest.confidence) > 0.01)
        val before = Meta.saveToJson().getDouble("metaCalibrationError")
        val p = Meta.upwardProbability8527(atEntry.dominantSignal, atEntry.confidence)
        close("frozen", -10.0)
        assertEquals(before * 0.92 + p * p * 0.08, Meta.saveToJson().getDouble("metaCalibrationError"), 1e-12)
    }

    @Test fun mixedOrMissingDirectionDoesNotInventBearishAccuracy() {
        Meta.recordFromScoreCard("mixed", "MIXED", component(0))
        close("mixed", -10.0)
        assertEquals(50.0, Meta.saveToJson().getDouble("metaAccuracy"), 0.0)
        assertEquals(0.5, Meta.upwardProbability8527("MIXED", 99.0), 0.0)
        assertEquals(0.5, Meta.upwardProbability8527("NONE", Double.NaN), 0.0)
    }

    @Test fun canonicalOutcomeWithoutPredictionRosterCountsExposureNotPredictionAccuracy() {
        repeat(20) { Meta.onCanonicalOutcome(outcome("no-roster$it", win = false)) }
        assertEquals(20, Meta.saveToJson().getInt("totalTradesAnalyzed"))
        assertEquals(50.0, Meta.saveToJson().getDouble("metaAccuracy"), 0.0)
        assertEquals(0.25, Meta.saveToJson().getDouble("metaCalibrationError"), 0.0)
        assertTrue(Meta.getAllLayerPerformance().isEmpty())
    }

    @Test fun invalidOutcomeDoesNotConsumeThePendingEntryAndDuplicateSettlementDoesNotRelearn() {
        Meta.recordFromScoreCard("once", "ONCE", component(-15))
        close("once", Double.NaN)
        assertEquals(0, Meta.saveToJson().getInt("totalTradesAnalyzed"))
        close("once", -10.0)
        close("once", -10.0)
        Meta.onCanonicalOutcome(outcome("once", false))
        assertEquals(1, Meta.saveToJson().getInt("totalTradesAnalyzed"))
        assertEquals(1, Meta.getAllLayerPerformance()[layer]!!.totalPredictions)
    }

    @Test fun historicalStateRoundTripIsPreservedWithoutClaimingItWasRetroactivelyRecalibrated() {
        Meta.recordFromScoreCard("history", "H", component(15))
        close("history", 10.0)
        val original = Meta.exportState()
        reset()
        Meta.importState(original)
        val loaded = Meta.saveToJson()
        assertEquals(JSONObject(original).getInt("totalTradesAnalyzed"), loaded.getInt("totalTradesAnalyzed"))
        assertEquals(JSONObject(original).getDouble("metaCalibrationError"), loaded.getDouble("metaCalibrationError"), 0.0)
        assertEquals(1, Meta.getAllLayerPerformance()[layer]!!.totalPredictions)
    }

    @Test fun allThreeProductionScoreModesConsumeTheSharedAdapter() {
        val scorer = File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        assertEquals(3, scorer.split("MetaCognitionAI.predictionsFromComponents8527(").size - 1)
        assertFalse(scorer.contains("confidence = (50.0 + comp.value * 2)"))
        val meta = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MetaCognitionAI.kt").readText()
        // V5.0.8535 §C03 — superseded: the forecast is staged under its account mode as well.
        assertTrue(meta.contains("recordForecast8527(mint, symbol, predictionsFromComponents8527(components), mode8535)"))
        assertTrue(meta.contains("ConcurrentHashMap<String, PendingForecast8527>"))
        assertTrue(meta.contains("updateMetaCalibration(outcome, captured.metaAtEntry)"))
        // V5.0.8535 §C03 — superseded: `alreadyOpen && pendingPredictions.containsKey(mint)`; the staging slot is keyed (mode, mint).
        assertTrue(meta.contains("alreadyOpen && pendingPredictions.containsKey(key)"))
    }
}
