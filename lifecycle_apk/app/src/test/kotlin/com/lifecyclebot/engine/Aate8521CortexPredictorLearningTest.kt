package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.v3.scoring.CandidateComponentBook8521
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8521CortexPredictorLearningTest {
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
    private val edges = doubleArrayOf(0.0)
    private val voter = "CAUSAL_FEATURE_8521"

    private fun fill(s: CortexLedger7885.Stat, n: Int, mean: Double) {
        repeat(n) { s.add(mean, false) }
    }

    @Test fun crossLaneBinPrevalenceCannotManufacturePredictiveEdge() {
        for (target in lanes) {
            val others = lanes.filter { it != target }.take(2)
            for (reverse in listOf(false, true)) {
                val model = CortexLedger7885()
                fill(model.lane(others[0]), 100, 20.0)
                fill(model.lane(others[1]), 100, -20.0)
                val positiveHigh = if (reverse) 10 else 90
                val negativeHigh = 100 - positiveHigh
                val positive = model.seat(voter, others[0], 2)
                val negative = model.seat(voter, others[1], 2)
                fill(positive.bins[1], positiveHigh, 20.0)
                fill(positive.bins[0], 100 - positiveHigh, 20.0)
                fill(negative.bins[1], negativeHigh, -20.0)
                fill(negative.bins[0], 100 - negativeHigh, -20.0)
                assertEquals(target, 0.0, model.predict(voter, target, edges, 1.0).netPct, 1e-12)
                assertEquals(target, 0.0, model.predict(voter, target, edges, -1.0).netPct, 1e-12)
            }
        }
    }

    @Test fun realWithinLaneFeatureLiftStillTransfersToAnUnseenLane() {
        val model = CortexLedger7885()
        fill(model.lane("QUALITY"), 100, 20.0)
        fill(model.lane("SHITCOIN"), 100, -20.0)
        fill(model.seat(voter, "QUALITY", 2).bins[1], 90, 30.0)
        fill(model.seat(voter, "SHITCOIN", 2).bins[1], 10, -10.0)
        assertEquals(1_000.0 / 120.0, model.predict(voter, "TREASURY", edges, 1.0).netPct, 1e-12)
    }

    @Test fun everyLaneLearnsItsOwnDirectionFromFrozenOutOfSamplePredictionsAndSurvivesRestart() {
        var model = CortexLedger7885()
        for ((laneIndex, lane) in lanes.withIndex()) {
            val sign = if (laneIndex % 2 == 0) 1.0 else -1.0
            repeat(400) { i ->
                val raw = if (i % 2 == 0) 1.0 else -1.0
                val outcome = if (raw * sign > 0.0) 20.0 else -10.0
                val frozen = model.predictionSnapshot8076(lane, listOf(voter), listOf(edges), doubleArrayOf(raw))
                model.grade(lane, listOf(voter), listOf(edges), doubleArrayOf(raw), outcome, outcome,
                    prediction8076 = frozen)
            }
            val good = model.predict(voter, lane, edges, sign)
            val bad = model.predict(voter, lane, edges, -sign)
            assertTrue("$lane high-quality feature must rank above poor feature", good.netPct > bad.netPct)
            assertTrue("$lane positive outcome learned", good.netPct > 0.0)
            assertTrue("$lane negative outcome learned", bad.netPct < 0.0)
            assertTrue("$lane earned skill", good.authority > 0.0)
        }
        val before = lanes.associateWith { lane -> model.predict(voter, lane, edges, 1.0).netPct }
        model = CortexLedger7885().also { it.decode(JSONObject(model.encode().toString())) }
        for (lane in lanes) assertEquals(lane, before.getValue(lane), model.predict(voter, lane, edges, 1.0).netPct, 1e-12)
    }

    @Test fun delayedLabelUsesDecisionTimePredictionsEvenAfterTheModelChanges() {
        for (lane in lanes) {
            val model = CortexLedger7885()
            val ids = listOf(voter)
            val bins = listOf(edges)
            val raw = doubleArrayOf(1.0)
            val frozen = model.predictionSnapshot8076(lane, ids, bins, raw)
            repeat(80) { model.grade(lane, ids, bins, raw, 25.0, 25.0) }
            assertTrue(model.predict(voter, lane, edges, 1.0).netPct > frozen.preds[0])
            val trace = model.grade(lane, ids, bins, raw, -10.0, -10.0, prediction8076 = frozen)!!
            assertEquals(lane, frozen.preds[0], trace.preds[0], 0.0)
            assertEquals(lane, frozen.base, trace.base, 0.0)
        }
    }

    @Test fun currentCandidateComponentsDoNotFreezeWithTheEntrySnapshot() {
        val book = CandidateComponentBook8521()
        val sealedEntry = mapOf("MomentumPredictorAI" to 9)
        book.record("live:mint", sealedEntry, 1_000L, false)
        book.record("live:mint", mapOf("MomentumPredictorAI" to -9), 2_000L, false)
        assertEquals(9, sealedEntry.getValue("MomentumPredictorAI"))
        assertEquals(-9, book.read("live:mint", 2_000L)!!.getValue("MomentumPredictorAI"))
    }

    @Test fun partialSpecialistRefreshPreservesEachComponentsOwnObservationTime() {
        val book = CandidateComponentBook8521()
        book.record("live:mint", mapOf("MomentumPredictorAI" to 9, "LiquidityDepthAI" to -4), 1_000L, false)
        book.record("live:mint", mapOf("QualityTraderAI" to 7), 40_000L, true)
        assertEquals(mapOf("QualityTraderAI" to 7), book.read("live:mint", 46_001L))
        assertNull(book.read("live:mint", 85_001L))
    }

    @Test fun outOfOrderFutureAndCrossModeComponentsCannotContaminateCurrentVotes() {
        val book = CandidateComponentBook8521()
        book.record("live:mint", mapOf("MomentumPredictorAI" to -9), 2_000L, false)
        book.record("live:mint", mapOf("MomentumPredictorAI" to 9), 1_000L, false)
        book.record("paper:mint", mapOf("MomentumPredictorAI" to 8), 2_000L, false)
        assertNull(book.read("live:mint", 1_999L))
        assertEquals(-9, book.read("live:mint", 2_000L)!!.getValue("MomentumPredictorAI"))
        assertEquals(8, book.read("paper:mint", 2_000L)!!.getValue("MomentumPredictorAI"))
    }

    @Test fun candidateFeatureMemoryIsBoundedWithoutRetainingOldTokensForever() {
        val book = CandidateComponentBook8521(maxCandidates = 3)
        repeat(20) { book.record("live:mint$it", mapOf("MomentumPredictorAI" to it), 1_000L + it, false) }
        assertEquals(3, book.size())
        assertNull(book.read("live:mint0", 1_020L))
        assertEquals(19, book.read("live:mint19", 1_020L)!!.getValue("MomentumPredictorAI"))
    }
}
