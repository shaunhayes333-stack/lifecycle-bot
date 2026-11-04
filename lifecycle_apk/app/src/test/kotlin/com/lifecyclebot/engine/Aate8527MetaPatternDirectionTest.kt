package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.MetaCognitionAI as Meta
import com.lifecyclebot.v3.scoring.ScoreComponent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8527MetaPatternDirectionTest {
    private var oldMood = "NEUTRAL"
    private val layer = Meta.AILayer.MOMENTUM_PREDICTOR

    @Before fun setup() {
        Meta.reset()
        oldMood = SymbolicContext.emotionalState
        SymbolicContext.emotionalState = "NEUTRAL"
    }
    @After fun cleanup() { Meta.reset(); SymbolicContext.emotionalState = oldMood }

    private fun votes(score: Int) = Meta.predictionsFromComponents8527(
        listOf(ScoreComponent("momentum", score, "observed")))
    private fun row(wins: Int, losses: Int, up: Int = 0, down: Int = 0, neutral: Int = 0) =
        Meta.ConsensusPattern("fixture", emptySet(), emptySet(), emptySet(),
            occurrences = wins + losses, wins = wins, losses = losses,
            directionUp8527 = up, directionDown8527 = down, directionNeutral8527 = neutral)

    private fun jsonRow(signature: String, wins: Int, losses: Int, up: Int = 0, down: Int = 0,
                        neutral: Int = 0, knownDirection: Boolean = true) = JSONObject().apply {
        put("signature", signature); put("occurrences", wins + losses)
        put("wins", wins); put("losses", losses); put("avgPnl", if (wins > 0) 10.0 else -10.0)
        if (knownDirection) {
            put("directionUp8527", up); put("directionDown8527", down); put("directionNeutral8527", neutral)
        }
    }

    private fun install(predictions: List<Meta.Prediction>, wins: Int, losses: Int,
                        knownDirection: Boolean = true, neutralLosses: Int = 0, readiness: Int = 0) {
        val signature = Meta.ConsensusPattern.buildSignature(predictions)
        val state = Meta.saveToJson().put("totalTradesAnalyzed", readiness)
        state.put("winningPatterns", JSONArray().apply {
            if (wins > 0) put(jsonRow(signature, wins, 0, up = wins, knownDirection = knownDirection))
        })
        state.put("losingPatterns", JSONArray().apply {
            if (losses > 0) put(jsonRow(signature, 0, losses, down = losses - neutralLosses,
                neutral = neutralLosses, knownDirection = knownDirection))
        })
        Meta.loadFromJson(state)
    }

    @Test fun confidencePoolsBothBooksInsteadOfTreatingEachSplitAsPerfectEvidence() {
        val win = row(5, 0, up = 5)
        val loss = row(0, 100, down = 100)
        assertEquals(-12.0, Meta.patternConfidenceAdjustment8527("BULLISH", win, loss), 0.0)
        assertEquals(12.0, Meta.patternConfidenceAdjustment8527("BEARISH", win, loss), 0.0)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("MIXED", win, loss), 0.0)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("NONE", win, loss), 0.0)
    }

    @Test fun theExistingSampleAndRateBoundsStillApplyToActualKnownDirectionCounts() {
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("BEARISH", null, row(0, 2, down = 2)), 0.0)
        assertEquals(12.0, Meta.patternConfidenceAdjustment8527("BEARISH", null, row(0, 3, down = 3)), 0.0)
        assertEquals(12.0, Meta.patternConfidenceAdjustment8527("BULLISH", row(66, 0, up = 66), row(0, 34, down = 34)), 0.0)
        assertEquals(-12.0, Meta.patternConfidenceAdjustment8527("BULLISH", row(34, 0, up = 34), row(0, 66, down = 66)), 0.0)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("BULLISH", row(50, 0, up = 50), row(0, 50, down = 50)), 0.0)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("BULLISH", null, null), 0.0)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("BULLISH", row(1, 0, up = 100), null), 0.0)
    }

    @Test fun legacyLossesCannotBeReinterpretedAsCorrectBearishCalls() {
        val legacy = row(0, 100)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("BEARISH", null, legacy), 0.0)
        val predictions = votes(-15)
        val before = Meta.calculateMetaConfidence(predictions).confidence
        install(predictions, 0, 100, knownDirection = false)
        assertEquals(before, Meta.calculateMetaConfidence(predictions).confidence, 0.0)
        val saved = Meta.saveToJson().getJSONArray("losingPatterns").getJSONObject(0)
        assertEquals(100, saved.getInt("losses"))
        assertEquals(100, saved.getInt("occurrences"))
        assertEquals(0, saved.getInt("directionDown8527"))
    }

    @Test fun flatAndSmallPositiveFinancialLossesNeverBecomeBearishProof() {
        for ((i, pnl) in listOf(0.0, 0.5, -0.5).withIndex()) {
            Meta.recordFromScoreCard("flat$i", "FLAT", listOf(ScoreComponent("momentum", -15, "observed")))
            Meta.recordTradeOutcome("flat$i", "FLAT", pnl, 60_000L, "CONFIRMED")
        }
        val saved = Meta.saveToJson().getJSONArray("losingPatterns").getJSONObject(0)
        assertEquals(3, saved.getInt("losses"))
        assertEquals(0, saved.getInt("directionDown8527"))
        assertEquals(3, saved.getInt("directionNeutral8527"))
        assertEquals(-12.0, Meta.patternConfidenceAdjustment8527("BEARISH", null, row(0, 3, neutral = 3)), 0.0)
        assertEquals(0.0, Meta.patternConfidenceAdjustment8527("MIXED", null, row(0, 100, neutral = 100)), 0.0)
    }

    @Test fun prospectiveDirectionCountsSurviveHistoryRoundTripAndUseExactOnePercentBoundaries() {
        for ((i, pnl) in listOf(1.0, -1.0, 0.0).withIndex()) {
            Meta.recordFromScoreCard("boundary$i", "B", listOf(ScoreComponent("momentum", 15, "observed")))
            Meta.recordTradeOutcome("boundary$i", "B", pnl, 60_000L, "CONFIRMED")
        }
        val original = Meta.exportState()
        Meta.reset(); Meta.importState(original)
        val saved = Meta.saveToJson()
        val win = saved.getJSONArray("winningPatterns").getJSONObject(0)
        val loss = saved.getJSONArray("losingPatterns").getJSONObject(0)
        assertEquals(1, win.getInt("wins"))
        assertEquals(1, win.getInt("directionUp8527"))
        assertEquals(2, loss.getInt("losses"))
        assertEquals(1, loss.getInt("directionDown8527"))
        assertEquals(1, loss.getInt("directionNeutral8527"))
    }

    @Test fun productionConfidenceUsesThePooledDirectionRead() {
        for (score in listOf(15, -15)) {
            Meta.reset()
            val predictions = votes(score)
            val baseline = Meta.calculateMetaConfidence(predictions).confidence
            install(predictions, 5, 100)
            val expected = baseline + if (score > 0) -12.0 else 12.0
            assertEquals(expected, Meta.calculateMetaConfidence(predictions).confidence, 1e-12)
        }
    }

    @Test fun knownLoserVetoUsesAllFinancialOutcomesWithoutErasingLegacyProfitability() {
        assertFalse(Meta.knownLosingPattern8527(row(100, 0), row(0, 5)))
        assertTrue(Meta.knownLosingPattern8527(row(5, 0), row(0, 100)))
        assertFalse(Meta.knownLosingPattern8527(null, row(0, 4)))
        assertTrue(Meta.knownLosingPattern8527(null, row(0, 5)))
        val predictions = votes(15)
        install(predictions, 100, 5, knownDirection = false, readiness = 30)
        assertNull(Meta.checkVeto(predictions, 50.0))
        install(predictions, 5, 100, knownDirection = false, readiness = 30)
        assertEquals("META_VETO_KNOWN_LOSER", Meta.checkVeto(predictions, 50.0))
    }

    private fun meta(signal: String, confidence: Double) = Meta.MetaConfidenceResult(
        confidence, signal, emptyList(), 1.0, if (signal == "BEARISH") -80.0 else 80.0)

    @Test fun confidenceMultiplierSupportsTheCurrentRawScoreDirectionAndRemainsBounded() {
        assertEquals(1.15, Meta.scoreConfidenceMultiplier8527(50, meta("BULLISH", 80.0)), 0.0)
        assertEquals(0.72, Meta.scoreConfidenceMultiplier8527(50, meta("BEARISH", 80.0)), 0.0)
        assertEquals(1.15, Meta.scoreConfidenceMultiplier8527(-50, meta("BEARISH", 80.0)), 0.0)
        assertEquals(0.72, Meta.scoreConfidenceMultiplier8527(-50, meta("BULLISH", 80.0)), 0.0)
        for (raw in listOf(-100, -1, 0, 1, 100)) {
            for (signal in listOf("BULLISH", "BEARISH", "MIXED", "NONE")) {
                for (confidence in listOf(0.0, 25.0, 50.0, 80.0, 100.0, Double.NaN)) {
                    val mult = Meta.scoreConfidenceMultiplier8527(raw, meta(signal, confidence))
                    assertTrue(mult in 0.72..1.15)
                    if (raw != 0) assertTrue(raw.toDouble() * (raw * mult) > 0.0)
                    if (signal == "MIXED" || signal == "NONE" || confidence.isNaN() || raw == 0) {
                        assertEquals(1.0, mult, 0.0)
                    }
                }
            }
        }
    }

    @Test fun actualScoreDoesNotRewardAConfidentBearOrAmplifyANegativeScoreForAConfidentBull() {
        Meta.loadFromJson(Meta.saveToJson().put("totalTradesAnalyzed", 30))
        val bear = votes(-25)
        val bull = votes(25)
        assertTrue(Meta.adjustScore(50, bear) < 50)
        assertTrue(Meta.adjustScore(-50, bull) > -50)
        assertTrue(Meta.adjustScore(50, bull) > 50)
        assertTrue(Meta.adjustScore(-50, bear) < -50)
        assertEquals(50, Meta.adjustScore(50, votes(0)))
        assertEquals(-50, Meta.adjustScore(-50, votes(0)))
        assertEquals(50, Meta.adjustScore(50, emptyList()))
        // V5.0.8535 §C02 — superseded: `adjustScore(1_000, bull) <= 100` / `adjustScore(-1_000, bear) >= -100`. The raw vote
        // aggregate is never clamped; the correction is a bounded delta (±META_DELTA_CAP_8535), so 1,000 stays near 1,000.
        assertTrue(Meta.adjustScore(1_000, bull) in 1_000..(1_000 + Meta.META_DELTA_CAP_8535))
        assertTrue(Meta.adjustScore(-1_000, bear) in (-1_000 - Meta.META_DELTA_CAP_8535)..-1_000)
    }

    private fun installPerformance(bulls: List<Meta.AILayer>, bears: List<Meta.AILayer>) {
        val perf = JSONObject()
        for (l in bulls + bears) {
            val strong = l in bears
            perf.put(l.name, JSONObject().apply {
                put("totalPredictions", 100); put("correctPredictions", if (strong) 100 else 0)
                put("recentAccuracyEwma", if (strong) 100.0 else 0.0)
                put("calibrationError", if (strong) 0.01 else 0.5)
                put("overconfidenceScore", if (strong) -0.2 else 0.5)
            })
        }
        Meta.loadFromJson(Meta.saveToJson().put("layerPerformance", perf))
    }

    @Test fun trustWeightedOppositionCannotBoostTheCountMajoritysConfidence() {
        val bulls = listOf(Meta.AILayer.MOMENTUM_PREDICTOR, Meta.AILayer.NARRATIVE_DETECTOR, Meta.AILayer.LIQUIDITY_DEPTH)
        val bears = listOf(Meta.AILayer.ORDER_FLOW_IMBALANCE, Meta.AILayer.WHALE_TRACKER)
        installPerformance(bulls, bears)
        val predictions = bulls.map { Meta.predictionFromScore8527(it, 15.0)!! } +
            bears.map { Meta.predictionFromScore8527(it, -15.0)!! }
        val result = Meta.calculateMetaConfidence(predictions)
        assertEquals("BULLISH", result.dominantSignal)
        assertTrue(result.weightedBias < 0.0)
        // 50 base +4 agreement +5 category coverage; opposition must subtract from59, not add.
        assertTrue(result.confidence < 59.0)
    }

    @Test fun threeAccurateOpposingLayersDoNotAddConfidenceToTheOppositeMajority() {
        val bulls = listOf(Meta.AILayer.MOMENTUM_PREDICTOR, Meta.AILayer.NARRATIVE_DETECTOR,
            Meta.AILayer.LIQUIDITY_DEPTH, Meta.AILayer.ENTRY_INTELLIGENCE)
        val bears = listOf(Meta.AILayer.ORDER_FLOW_IMBALANCE, Meta.AILayer.WHALE_TRACKER, Meta.AILayer.VOLATILITY_REGIME)
        installPerformance(bulls, bears)
        val predictions = bulls.map { Meta.predictionFromScore8527(it, 15.0)!! } +
            bears.map { Meta.predictionFromScore8527(it, -15.0)!! }
        val result = Meta.calculateMetaConfidence(predictions)
        assertEquals("BULLISH", result.dominantSignal)
        assertTrue(result.weightedBias < 0.0)
        assertTrue(result.factors.any { it.startsWith("trusted=") && it.endsWith("-8") })
    }
}
