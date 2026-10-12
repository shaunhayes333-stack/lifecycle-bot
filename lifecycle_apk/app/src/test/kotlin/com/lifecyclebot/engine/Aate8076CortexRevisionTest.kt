package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.junit.Assert.*
import org.junit.Test

class Aate8076CortexRevisionTest {
    @Test fun delayedOutcomeScoresThePredictionCapturedBeforeInterveningLearning() {
        val ledger = CortexLedger7885()
        val ids = listOf("V"); val edges = listOf(doubleArrayOf(0.5)); val raw = doubleArrayOf(1.0)
        val captured = ledger.predictionSnapshot8076("QUALITY", ids, edges, raw)
        repeat(100) { ledger.grade("QUALITY", ids, edges, raw, 80.0, 80.0) }
        val seat = ledger.seats["V|QUALITY"]!!
        val before = seat.sseModel
        val beforeBrier = seat.brierModel
        assertTrue(ledger.predict("V", "QUALITY", edges[0], 1.0).netPct > 50.0)
        val trace = ledger.grade("QUALITY", ids, edges, raw, -20.0, -20.0, prediction8076 = captured)!!
        assertEquals(0.0, trace.preds[0], 0.0)
        assertEquals(before * CortexLedger7885.DECAY + 400.0, seat.sseModel, 1e-7)
        assertEquals(beforeBrier * CortexLedger7885.DECAY + 0.25, seat.brierModel, 1e-8)
    }

    @Test fun legacyOutcomeWithoutDecisionPredictionTrainsAndRevisesWithoutInventingSkill() {
        val ledger = CortexLedger7885()
        val ids = listOf("V"); val edges = listOf(doubleArrayOf(0.5)); val raw = doubleArrayOf(1.0)
        val unknown = CortexLedger7885.PredictionSnapshot8076(doubleArrayOf(Double.NaN), doubleArrayOf(Double.NaN), Double.NaN, Double.NaN)
        val trace = ledger.grade("QUALITY", ids, edges, raw, 50.0, 50.0, prediction8076 = unknown)!!
        val seat = ledger.seats["V|QUALITY"]!!
        assertEquals(0, seat.scored)
        assertEquals(50.0, seat.bins[1].mean(), 0.0)
        ledger.revise8006("QUALITY", ids, edges, raw, trace, 50.0, -30.0, 50.0, -30.0)
        assertEquals(-30.0, seat.bins[1].mean(), 1e-8)
        assertEquals(0, seat.scored)
        assertEquals(0.0, seat.sseModel, 0.0)
    }

    @Test fun otherLanePriorSurvivesIndependentWindowDecayWithoutSubtractingOwnHistory() {
        val ledger = CortexLedger7885()
        val ids = listOf("V"); val edges = listOf(doubleArrayOf(0.5))
        repeat(400) { i ->
            val high = i % 2 == 0
            ledger.grade("SHITCOIN", ids, edges, doubleArrayOf(if (high) 1.0 else 0.0), if (high) 20.0 else -20.0, 0.0)
        }
        repeat(4000) { ledger.grade("QUALITY", ids, edges, doubleArrayOf(1.0), 1000.0, 1000.0) }
        // QUALITY's full 500-label window cannot erase the separate SHITCOIN signal
        // by being subtracted from a differently aged 500-label global window.
        val prediction = ledger.predict("V", "QUALITY", edges[0], 1.0).netPct
        assertTrue("prediction=$prediction", prediction > 1000.0 && prediction < 1020.0)
        assertEquals(0.0, ledger.predict("V", "CRYPTO_ALT", edges[0], 1.0).netPct, 0.0)
    }

    @Test fun lateRevisionMatchesDirectObservationAfterWindowDecayAndRestore() {
        val revised = CortexLedger7885.Stat()
        val direct = CortexLedger7885.Stat()
        val stamp = revised.add(900.0, true)
        direct.add(-90.0, false)
        repeat(1200) { revised.add(-20.0, false); direct.add(-20.0, false) }
        val restored = CortexLedger7885.Stat().also { it.decode(revised.encode()) }
        restored.revise(900.0, -90.0, true, false, restored.revisionWeight(stamp))
        assertEquals(direct.n, restored.n, 1e-8)
        assertEquals(direct.sum, restored.sum, 1e-7)
        assertEquals(direct.sumSq, restored.sumSq, 1e-6)
        assertEquals(direct.wins, restored.wins, 1e-8)
        assertEquals(direct.runners, restored.runners, 1e-8)
        assertTrue(restored.mean() >= -100.0)
    }

    @Test fun laneLocalSetupNumbersCannotBorrowAnUnrelatedOtherLaneStrategy() {
        val ledger = CortexLedger7885()
        val ids = listOf("PLAYBOOK_SETUP_V3")
        val edges = listOf(doubleArrayOf(0.5))
        repeat(200) { i ->
            val high = i % 2 == 0
            ledger.grade("SHITCOIN", ids, edges, doubleArrayOf(if (high) 1.0 else 0.0),
                if (high) 80.0 else -40.0, 0.0)
        }
        // QUALITY's bin 1 names its own setup, not SHITCOIN's bin 1 setup.
        assertEquals(0.0, ledger.predict(ids[0], "QUALITY", edges[0], 1.0).netPct, 0.0)
        repeat(100) { ledger.grade("QUALITY", ids, edges, doubleArrayOf(1.0), 12.0, 12.0) }
        assertEquals(12.0, ledger.predict(ids[0], "QUALITY", edges[0], 1.0).netPct, 1e-8)
    }

    @Test fun sparseStrongBucketCannotFallBelowItsLabelFloorOnRevision() {
        val revised = CortexScoreboard7885()
        val direct = CortexScoreboard7885()
        val strong = CortexScoreboard7885.Bucket.STRONG
        val neutral = CortexScoreboard7885.Bucket.NEUTRAL
        val stamps = revised.record("EXPRESS", strong, false, 500.0, 500.0)!!
        direct.record("EXPRESS", strong, false, -100.0, -100.0)
        repeat(2000) {
            revised.record("EXPRESS", neutral, false, -10.0, -10.0)
            direct.record("EXPRESS", neutral, false, -10.0, -10.0)
        }
        revised.revise8006("EXPRESS", strong, false, 500.0, -100.0, 500.0, -100.0, stamps)
        val actual = revised.books["EXPRESS"]!!.byBucket[strong.ordinal]
        assertEquals(-100.0, actual.mean(), 1e-7)
        assertEquals(direct.books["EXPRESS"]!!.missedStrong.sum, revised.books["EXPRESS"]!!.missedStrong.sum, 1e-7)
    }

    @Test fun delayedScoreCorrectionUsesRemainingErrorWeightAndDoesNotInventRegimeGrades() {
        val l = CortexLedger7885()
        val ids = listOf("V")
        val edges = listOf(doubleArrayOf(0.5))
        val raw = doubleArrayOf(1.0)
        val trace = l.grade("CORE", ids, edges, raw, 40.0, 40.0, "NORMAL")!!
        repeat(100) { l.grade("CORE", ids, edges, raw, -10.0, -10.0, "NORMAL") }
        val seat = l.seats["V|CORE"]!!
        val before = seat.sseModel
        val regimeSum = l.seats["V|CORE@NORMAL"]!!.bins[1].sum
        val weight = Math.pow(CortexLedger7885.DECAY, 100.0)
        val p = trace.preds[0]
        l.revise8006("CORE", ids, edges, raw, trace, 40.0, -50.0, 40.0, -50.0, "NORMAL")
        assertEquals(before + weight * ((-50.0 - p) * (-50.0 - p) - (40.0 - p) * (40.0 - p)), seat.sseModel, 1e-7)
        // The first vote was too early to belong to the later regime-specific voter cell.
        assertEquals(regimeSum, l.seats["V|CORE@NORMAL"]!!.bins[1].sum, 1e-9)
        l.revise8006("CORE", ids, edges, raw, trace, -50.0, 40.0, -50.0, 40.0, "NORMAL")
        assertEquals(before, seat.sseModel, 1e-7)
    }

    @Test fun refuseComparisonFindsInversionWithoutNeutralSamples() {
        val board = CortexScoreboard7885()
        repeat(50) {
            board.record("PROJECT_SNIPER", CortexScoreboard7885.Bucket.STRONG, false, -20.0, -20.0)
            board.record("PROJECT_SNIPER", CortexScoreboard7885.Bucket.REFUSE, false, 60.0, 60.0)
        }
        assertTrue(board.inverted7948("PROJECT_SNIPER"))
        repeat(50) { board.record("SHITCOIN", CortexScoreboard7885.Bucket.STRONG, false, 40.0, 40.0) }
        repeat(2) { board.record("SHITCOIN", CortexScoreboard7885.Bucket.REFUSE, false, 490.0, 490.0) }
        assertFalse(board.inverted7948("SHITCOIN"))
    }

    @Test fun hiveScoresAndBinsMustBeMathematicallyPossible() {
        assertTrue(CortexLedger7885.validHiveSeat8076(doubleArrayOf(100.0, 25.0, 100.0, 20.0, 25.0, 2.0), 2))
        assertFalse(CortexLedger7885.validHiveSeat8076(doubleArrayOf(100.0, -2500.0, 1.0, 20.0, 25.0, 2.0), 2))
        assertFalse(CortexLedger7885.validHiveSeat8076(doubleArrayOf(100.0, 1.0, 1.0, 20.0, 25.0, 4.0), 2))
        assertTrue(CortexLedger7885.validHiveBin8076(doubleArrayOf(2.0, -20.0, 200.0, 0.0, 0.0)))
        assertFalse(CortexLedger7885.validHiveBin8076(doubleArrayOf(2.0, -1200.0, 200.0, 0.0, 0.0)))
        assertFalse(CortexLedger7885.validHiveBin8076(doubleArrayOf(2.0, 20.0, 100.0, 2.0, 0.0)))
        assertFalse(CortexLedger7885.validHiveBin8076(doubleArrayOf(2.0, 20.0, Double.NaN, 2.0, 0.0)))
    }
}
