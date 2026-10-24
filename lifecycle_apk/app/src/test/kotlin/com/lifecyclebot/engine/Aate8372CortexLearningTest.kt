package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Behavioral tests of actual pure production models, not source-string pins. */
class Aate8372CortexLearningTest {
    private val ids = listOf("FLOW")
    private val edges = listOf(doubleArrayOf(0.5))
    private val raw = doubleArrayOf(1.0)
    private val frozen = CortexLedger7885.PredictionSnapshot8076(
        doubleArrayOf(10.0), doubleArrayOf(0.6), 2.0, 0.5,
    )

    private fun record(mint: String, lane: String, atMs: Long, bucket: CortexScoreboard7885.Bucket,
                       admitted: Boolean, score: Double) = CortexGradeRecord8144(
        kind = 'D', id = "$mint|$lane|$atMs", episode = "$mint|$lane", atMs = atMs,
        lane = lane, setup = "IGNITION", regime = "RANGE", timing = "NOW",
        costBand = "C0_3", cohort = "MC_LT10K", bucket = bucket.ordinal,
        predictedEdgePct = score, admitted = admitted, executable = true, repeat = false,
    )

    @Test fun oneLaneStillProducesDifferentPredictionsForDifferentTokens() {
        val ledger = CortexLedger7885()
        repeat(700) { i ->
            val signal = if (i % 2 == 0) 1.0 else 0.0
            val outcome = if (signal == 1.0) 30.0 else -20.0
            ledger.grade("QUALITY", ids, edges, doubleArrayOf(signal), outcome, outcome)
        }
        fun read(signal: Double) = ledger.fuse("QUALITY", listOf(
            CortexLedger7885.Vote("FLOW", edges.single(), signal, setOf("FLOW")),
        ))
        val yesToken = read(1.0)
        val noToken = read(0.0)
        assertTrue("A lane prior must not replace each token's signal", yesToken.edgePct > noToken.edgePct + 2.0)
        assertEquals("Both reads share only the lane prior", yesToken.laneMean, noToken.laneMean, 0.0)
        assertNotEquals(CortexScoreboard7885.bucketOf(yesToken.edgePct, 0.0, false),
            CortexScoreboard7885.bucketOf(noToken.edgePct, 0.0, false))
    }

    @Test fun acceptedAndRefusedTokensBothTeachWinsAndLossesWithoutCrossLaneOverwrite() {
        val ledger = CortexLedger7885()
        val board = CortexScoreboard7885()
        val grades = CortexGradeBook8144()
        val calibration = CortexCalibration7901()
        for (lane in listOf("QUALITY", "EXPRESS")) {
            for ((index, fixture) in listOf(true to 15.0, true to -12.0, false to 28.0, false to -19.0).withIndex()) {
                val (admitted, outcome) = fixture
                val bucket = if (admitted) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE
                val decision = record("token-$index", lane, 1_000L + index, bucket, admitted, if (admitted) 12.0 else -8.0)
                assertTrue(grades.addDecision(decision))
                ledger.grade(lane, ids, edges, raw, outcome, outcome, prediction8076 = frozen)
                board.record(lane, bucket, admitted, outcome, outcome)
                calibration.learn(lane, decision.predictedEdgePct, 2.0, outcome, decision.predictedEdgePct)
                assertTrue(grades.label(decision.id, outcome, outcome, outcome, true, 301_000L + index))
            }
            assertEquals(4.0, ledger.lanes[lane]!!.n, 0.0)
            assertEquals(4, ledger.seats["FLOW|$lane"]!!.scored)
            assertEquals(4, calibration.isoFit8366(lane)!!.n)
            assertEquals(listOf(15.0, -12.0), grades.executableDecisionSeries8269(lane, true))
            assertEquals(listOf(28.0, -19.0), grades.executableDecisionSeries8269(lane, false))
            val laneBook = board.books[lane]!!
            assertTrue(laneBook.legacyAdmitted.wins > 0.0 && laneBook.legacyAdmitted.wins < laneBook.legacyAdmitted.n)
            assertTrue(laneBook.legacyRefused.wins > 0.0 && laneBook.legacyRefused.wins < laneBook.legacyRefused.n)
        }
        assertEquals(8, grades.size())
        assertTrue("Counterfactual observations are not realised live money", board.realized.isEmpty())
        assertEquals(0, grades.tradeCount())
    }

    @Test fun specialistSoftNoMustNotDeleteTheCounterfactualLearningPopulation() {
        for (reason in listOf("NATIVE_THRESHOLD_FAIL_SCORE", "NATIVE_NO_CONTINUATION", "CANDIDATE_ADMISSION_8136_SETUP_RECORD_LOSING")) {
            assertTrue("A judgement NO is learnable: $reason",
                CortexLabelHygiene8144.executableDecision8144(false, reason, nativeEligible = false))
        }
    }

    @Test fun physicalUnsafeOrUnpricedNoNeverManufacturesExecutableEvidence() {
        for (reason in listOf("NATIVE_SELLABILITY_REJECT", "HONEYPOT", "FREEZE", "NATIVE_PRICE_NOT_FRESH", "NATIVE_LANE_LIQ_UNHYDRATED_DEFER")) {
            assertFalse("Physical/data block remains non-executable: $reason",
                CortexLabelHygiene8144.executableDecision8144(false, reason, nativeEligible = false))
        }
    }

    @Test fun counterfactualHelperRequiresGradeableOwnLaneEvidenceWithoutTreatingJudgementAsSafety() {
        assertTrue(CortexLabelHygiene8144.counterfactualExecutable8372(
            false, "FDG_SETUP_RECORD_LOSING", false, true, "THRESHOLD_FAIL_SCORE"))
        assertFalse(CortexLabelHygiene8144.counterfactualExecutable8372(
            false, "FDG_SETUP_RECORD_LOSING", false, false, "THRESHOLD_FAIL_SCORE"))
        assertFalse(CortexLabelHygiene8144.counterfactualExecutable8372(
            false, "FDG_SETUP_RECORD_LOSING", false, true, "SELLABILITY_REJECT"))
        assertFalse(CortexLabelHygiene8144.counterfactualExecutable8372(
            false, "FDG_SETUP_RECORD_LOSING", false, true, "MCAP_TOO_LOW"))
        assertFalse(CortexLabelHygiene8144.counterfactualExecutable8372(
            false, null, false, null, null))
    }

    @Test fun exactOldEpisodeRevisionSurvivesRestartWithoutRewritingNewerTokenOrAnotherLane() {
        val grades = CortexGradeBook8144().also { it.runnerLane8322 = { true } }
        val old = record("same-mint", "MOONSHOT", 1_000L, CortexScoreboard7885.Bucket.REFUSE, false, -6.0)
        val newer = record("same-mint", "MOONSHOT", 30_000_000L, CortexScoreboard7885.Bucket.STRONG, true, 12.0)
        val otherLane = record("same-mint", "QUALITY", 1_000L, CortexScoreboard7885.Bucket.NEUTRAL, false, 0.0)
        for ((r, first) in listOf(old to -9.0, newer to 8.0, otherLane to -2.0)) {
            assertTrue(grades.addDecision(r))
            assertTrue(grades.label(r.id, first, first, first, true, r.atMs + 300_000L))
        }
        val restored = CortexGradeBook8144().also { it.decode(grades.encode()); it.runnerLane8322 = { true } }
        assertTrue(restored.label(old.id, 75.0, 78.0, 75.0, true, old.atMs + 14_400_000L))
        assertEquals(-9.0, restored.decision(old.id)!!.executablePct, 0.0)
        assertEquals(75.0, restored.objectivePct8322(restored.decision(old.id)!!), 0.0)
        assertEquals(8.0, restored.objectivePct8322(restored.decision(newer.id)!!), 0.0)
        assertEquals(-2.0, restored.objectivePct8322(restored.decision(otherLane.id)!!), 0.0)
        assertEquals(CortexScoreboard7885.Bucket.REFUSE.ordinal, restored.decision(old.id)!!.bucket)
        assertEquals(3, restored.size())
    }

    @Test fun lateRevisionAfter1600GradesAndRestartMatchesDirectOutcomeInRealModels() {
        val revisedLedger = CortexLedger7885(); val directLedger = CortexLedger7885()
        val revisedBoard = CortexScoreboard7885(); val directBoard = CortexScoreboard7885()
        val revisedCalibration = CortexCalibration7901(); val directCalibration = CortexCalibration7901()
        val lane = "EXPRESS"; val initial = -14.0; val final = 90.0
        val bucket = CortexScoreboard7885.Bucket.STRONG
        val trace = revisedLedger.grade(lane, ids, edges, raw, initial, initial, prediction8076 = frozen)!!
        directLedger.grade(lane, ids, edges, raw, final, final, prediction8076 = frozen)
        val boardStamps = revisedBoard.record(lane, bucket, false, initial, initial)!!
        directBoard.record(lane, bucket, false, final, final)
        val calibrationStamp = revisedCalibration.learn(lane, 14.0, 2.0, initial, 12.0)
        directCalibration.learn(lane, 14.0, 2.0, final, 12.0)
        repeat(1_600) { i ->
            val signal = if (i % 2 == 0) 1.0 else 0.0
            val outcome = if (signal == 1.0) 20.0 else -11.0
            val signalRaw = doubleArrayOf(signal)
            revisedLedger.grade(lane, ids, edges, signalRaw, outcome, outcome, prediction8076 = frozen)
            directLedger.grade(lane, ids, edges, signalRaw, outcome, outcome, prediction8076 = frozen)
            // Sparse STRONG owns its decay; other tokens must not erase it.
            revisedBoard.record(lane, CortexScoreboard7885.Bucket.NEUTRAL, true, outcome, outcome)
            directBoard.record(lane, CortexScoreboard7885.Bucket.NEUTRAL, true, outcome, outcome)
            revisedCalibration.learn(lane, signal * 16.0 - 6.0, 2.0, outcome, signal * 16.0 - 8.0)
            directCalibration.learn(lane, signal * 16.0 - 6.0, 2.0, outcome, signal * 16.0 - 8.0)
        }
        // Production codecs, not shared object references, recreate the models.
        val ledger = CortexLedger7885().also { it.decode(JSONObject(revisedLedger.encode().toString())) }
        val board = CortexScoreboard7885().also { it.decode(JSONObject(revisedBoard.encode().toString())) }
        val calibration = CortexCalibration7901().also { it.decode(JSONObject(revisedCalibration.encode().toString())) }
        ledger.revise8006(lane, ids, edges, raw, trace, initial, final, initial, final)
        board.revise8006(lane, bucket, false, initial, final, initial, final, boardStamps)
        assertTrue(calibration.revise8322(lane, 14.0, 2.0, initial, final, calibrationStamp))
        for (key in directLedger.lanes.keys) assertStatSame(directLedger.lanes[key]!!, ledger.lanes[key]!!)
        for (key in directLedger.seats.keys) {
            val expected = directLedger.seats[key]!!; val actual = ledger.seats[key]!!
            expected.bins.indices.forEach { assertStatSame(expected.bins[it], actual.bins[it]) }
            assertStatSame(expected.above, actual.above); assertStatSame(expected.below, actual.below)
            assertEquals(expected.scored, actual.scored)
            assertEquals(expected.sseModel, actual.sseModel, 1e-5)
            assertEquals(expected.sseBase, actual.sseBase, 1e-5)
            assertEquals(expected.brierModel, actual.brierModel, 1e-7)
        }
        directBoard.books[lane]!!.all().zip(board.books[lane]!!.all()).forEach { (expected, actual) -> assertStatSame(expected, actual) }
        assertEquals(1.0, board.books[lane]!!.strongLifetime8025, 0.0)
        assertEquals(1_601, calibration.isoFit8366(lane)!!.n)
        assertEquals(directCalibration.slope(lane), calibration.slope(lane), 1e-9)
        for (score in listOf(-8.0, 0.0, 8.0, 12.0)) {
            assertEquals(directCalibration.isoFit8366(lane)!!.expectedPct8366(score),
                calibration.isoFit8366(lane)!!.expectedPct8366(score), 1e-8)
        }
    }

    @Test fun zeroDeviationRevisionCorrectsItsPairAndCenteredRegressionSumsAfterRestart() {
        val revised = CortexCalibration7901(); val direct = CortexCalibration7901()
        val firstStamp = revised.learn("MOONSHOT", 2.0, 2.0, -8.0, 0.0)
        direct.learn("MOONSHOT", 2.0, 2.0, 85.0, 0.0)
        repeat(300) { i ->
            val edge = if (i % 2 == 0) 7.0 else -1.0
            revised.learn("MOONSHOT", edge, 2.0, (i % 7).toDouble(), edge - 2.0)
            direct.learn("MOONSHOT", edge, 2.0, (i % 7).toDouble(), edge - 2.0)
        }
        val restored = CortexCalibration7901().also { it.decode(JSONObject(revised.encode().toString())) }
        assertTrue(firstStamp.isFinite())
        assertTrue(restored.revise8322("MOONSHOT", 2.0, 2.0, -8.0, 85.0, firstStamp))
        assertEquals(301, restored.isoFit8366("MOONSHOT")!!.n)
        val oldPair = restored.encode().getString("ISO8366|MOONSHOT").split(';').first().split(':')
        assertEquals("Exact old pair, not the latest token, changes", 85.0, oldPair[1].toDouble(), 0.0)
        val actualSums = restored.encode().getString("MOONSHOT").split(',').map { it.toDouble() }
        val expectedSums = direct.encode().getString("MOONSHOT").split(',').map { it.toDouble() }
        expectedSums.zip(actualSums).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-8) }
    }

    @Test fun profitableRankDiagnosticMustNotReplaceActualRecordedVerdictProof() {
        val grades = CortexGradeBook8144(); val board = CortexScoreboard7885()
        repeat(500) { i ->
            val score = ((i * 37) % 100).toDouble()
            val outcome = score * 0.5 - 10.0
            // Rank top winners were actually REFUSE: reproduce the user log mismatch.
            val bucket = if (score < 20.0) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE
            val r = record("rank-$i", "EXPRESS", 1_000L + i, bucket, false, score)
            grades.addDecision(r); grades.label(r.id, outcome, outcome, outcome, true)
            board.record("EXPRESS", bucket, false, outcome, outcome)
        }
        val ranking = CortexAuthority8366.test8366(grades.scoredRows8366("EXPRESS", false), 1.5)
        assertTrue("Diagnostic ranking is deliberately favorable", ranking.granted)
        assertTrue(grades.executableSeries("EXPRESS", null, CortexScoreboard7885.Bucket.STRONG.ordinal).average() < 0.0)
        assertTrue(grades.executableSeries("EXPRESS", null, CortexScoreboard7885.Bucket.REFUSE.ordinal).average() > 0.0)
        assertEquals("Actual STRONG decisions have not earned authority", 0.0, board.fractionFor7955("EXPRESS"), 0.0)
    }

    @Test fun legacyEvidenceSurvivesButCannotImpersonateTheRestoredPerTokenScoreVersion() {
        val grades = CortexGradeBook8144()
        val old = record("legacy", "MOONSHOT", 1_000L, CortexScoreboard7885.Bucket.REFUSE, false, -6.0)
            .also { it.tailEv8324 = 48.0 }
        val fresh = record("current", "MOONSHOT", 2_000L, CortexScoreboard7885.Bucket.STRONG, false, 14.0)
            .also { it.tailEv8324 = 99.0; it.scoreVersion8372 = 1 }
        for (r in listOf(old, fresh)) {
            assertTrue(grades.addDecision(r))
            assertTrue(grades.label(r.id, 20.0, 23.0, 20.0, true, r.atMs + 300_000L))
        }
        val restored = CortexGradeBook8144().also { it.decode(grades.encode()) }
        assertEquals(2, restored.size())
        assertEquals(listOf(48.0, 14.0), restored.scoredRows8366("MOONSHOT", true).map { it.score })
        val authoritative = restored.scoredRows8366("MOONSHOT", true, scoreVersion = 1)
        assertEquals(1, authoritative.size)
        assertEquals(14.0, authoritative.single().score, 0.0)
        assertEquals(CortexScoreboard7885.Bucket.STRONG.ordinal, authoritative.single().frozenBucket)
        assertEquals(302_000L, authoritative.single().outcomeAvailableAtMs)
        assertEquals(0, restored.decision(old.id)!!.scoreVersion8372)
        assertEquals(-6.0, restored.decision(old.id)!!.predictedEdgePct, 0.0)
    }

    @Test fun selectedCostedShadowExitOwnsTheObjectiveEvenForAScalpAndIsNotChargedTwice() {
        val grades = CortexGradeBook8144().also { it.execOffset8187 = { 3.0 } }
        val r = record("scalp", "QUALITY", 1_000L, CortexScoreboard7885.Bucket.REFUSE, false, -6.0)
            .also { it.scoreVersion8372 = 1 }
        grades.addDecision(r)
        assertTrue(grades.label(r.id, -4.0, -1.0, -4.0, true, 301_000L))
        assertEquals(-7.0, grades.scoredRows8366("QUALITY", false, 1).single().netPct, 0.0)
        assertTrue(grades.shadowOutcome8372(r.id, 12.0, 16.0, 421_000L))
        assertEquals(-4.0, r.executablePct, 0.0)
        assertEquals(12.0, grades.objectivePct8322(r), 0.0)
        assertEquals(16.0, grades.objectiveGross8324(r), 0.0)
        assertEquals(421_000L, grades.objectiveAvailableAt8322(r))
        val restored = CortexGradeBook8144().also {
            it.decode(grades.encode()); it.execOffset8187 = { 3.0 }
        }
        val row = restored.scoredRows8366("QUALITY", false, 1).single()
        assertEquals("Already-costed shadow exits must not lose the haircut twice", 12.0, row.netPct, 0.0)
        assertEquals(421_000L, row.outcomeAvailableAtMs)
        assertEquals(CortexScoreboard7885.Bucket.REFUSE.ordinal, row.frozenBucket)
        assertEquals("SHADOW_EXIT", restored.decision(r.id)!!.outcomeSource8372)
        assertEquals(301_000L, restored.decision(r.id)!!.firstAvailableAtMs8372)
        assertEquals('D', restored.decision(r.id)!!.kind)
        assertEquals("A simulated exit is not a realised transaction", 0, restored.tradeCount())
    }

    @Test fun missingOrTimeTravellingShadowFillsCannotReplaceAnExecutableRecord() {
        val grades = CortexGradeBook8144()
        val r = record("unknown-fill", "EXPRESS", 10_000L, CortexScoreboard7885.Bucket.STRONG, true, 12.0)
        grades.addDecision(r)
        assertTrue(grades.label(r.id, 5.0, 8.0, 5.0, true, 310_000L))
        assertFalse(grades.shadowOutcome8372(r.id, Double.NaN, 20.0, 400_000L))
        assertFalse(grades.shadowOutcome8372(r.id, 20.0, Double.NaN, 400_000L))
        assertFalse(grades.shadowOutcome8372(r.id, 20.0, 23.0, 9_999L))
        assertEquals("FORWARD", r.outcomeSource8372)
        assertEquals(5.0, grades.objectivePct8322(r), 0.0)
        assertTrue(r.executableRevisedPct.isNaN())
    }

    private fun assertStatSame(expected: CortexLedger7885.Stat, actual: CortexLedger7885.Stat) {
        assertEquals(expected.n, actual.n, 1e-8)
        assertEquals(expected.sum, actual.sum, 1e-6)
        assertEquals(expected.sumSq, actual.sumSq, 1e-5)
        assertEquals(expected.runners, actual.runners, 1e-8)
        assertEquals(expected.wins, actual.wins, 1e-8)
    }
}
