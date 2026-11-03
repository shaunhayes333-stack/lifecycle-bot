package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexForwardEpoch8521
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regression witnesses for the actual forecast/authority separation and the source-time model migration. */
class Aate8521CortexCalibrationRecoveryTest {
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
    private val runners = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER")

    @Test fun pooledCalibrationBlockKeepsItsFittedValueAtEveryObservedScore() {
        val fit = CortexCalibration7901.fitNet8372(listOf(0.0, 1.0, 2.0, 3.0), listOf(-20.0, 15.0, 5.0, 60.0))
        assertTrue(fit.valid)
        assertEquals(10.0, fit.expectedNetPct8372(1.0), 1e-12)
        assertEquals(10.0, fit.expectedNetPct8372(1.5), 1e-12)
        assertEquals(10.0, fit.expectedNetPct8372(2.0), 1e-12)
        // Interpolate only in the unobserved interval [2,3], not from the pooled centre 1.5.
        assertEquals(22.5, fit.expectedNetPct8372(2.25), 1e-12)
    }

    @Test fun interpolationCannotConvertAPooledObservedLossIntoAFalseProfit() {
        val fit = CortexCalibration7901.fitNet8372(listOf(0.0, 1.0, 2.0), listOf(15.0, -25.0, 20.0))
        assertEquals(-5.0, fit.expectedNetPct8372(0.0), 1e-12)
        assertEquals(-5.0, fit.expectedNetPct8372(1.0), 1e-12)
        assertEquals(20.0, fit.expectedNetPct8372(2.0), 1e-12)
    }

    @Test fun everyLaneUsesTheDisplayedNetForecastEvenWhenItsSeparateTailDiagnosticDisagrees() {
        for (lane in lanes) {
            assertEquals("$lane negative net forecast remains a testable refusal", CortexScoreboard7885.Bucket.REFUSE,
                Cortex7885.bucketNow8418(lane, lane in runners, -6.0, 0.9, 100.0, null))
            assertEquals("$lane positive net forecast remains testable without authority", CortexScoreboard7885.Bucket.STRONG,
                Cortex7885.bucketNow8418(lane, lane in runners, 6.0, 0.0, -100.0, null))
            assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, Cortex7885.forecastBucket8521(Double.NaN))
        }
    }

    @Test fun everyLaneCanCollectRecoveryCohortsWhileItsInvertedModelHasNoAuthority() {
        for (lane in lanes) {
            val cal = CortexCalibration7901()
            repeat(80) { i ->
                val score = if (i % 2 == 0) 10.0 else -10.0
                cal.learn(lane, score, 0.0, -score * 4.0, score)
            }
            val failedFit = cal.netFit8372(lane)!!
            assertTrue("$lane is measured but inverted", cal.uncalibrated8372(lane))
            val observations = ArrayList<CortexAuthority8366.Row8366>()
            val horizon = CortexForwardEpoch8521.targetHorizon(CortexForwardEpoch8521.targetKind(lane))
            repeat(300) { i ->
                // Freeze the prediction before its later outcome. A failed lane's old authority may not relabel it.
                val score = if (i % 2 == 0) 10.0 else -10.0
                val bucket = Cortex7885.bucketNow8418(lane, lane in runners, score, 0.0, Double.NaN, failedFit)
                val expected = if (score > 0) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE
                assertEquals("$lane retains the candidate forecast while suspended", expected, bucket)
                val at = 1_000_000L + i * (horizon + 60_000L)
                val net = if (score > 0) 30.0 else -20.0
                observations.add(CortexAuthority8366.Row8366(at, score, net, bucket.ordinal, at + horizon, at + horizon))
                cal.learn(lane, score, 0.0, net, score)
            }
            val proof = CortexAuthority8366.test8372(observations, 2.0, observations.last().outcomeAvailableAtMs + 1)
            // Retaining a STRONG prediction did not grant the old failed model permission to trade.
            assertEquals(0.0, CortexAuthority8366.authority8366(proof, true, 0.0, Double.NaN), 0.0)
            // New source-correct evidence can recover both the fit and the exact frozen STRONG-vs-rest proof.
            assertFalse("$lane has learned the repaired ordering", cal.uncalibrated8372(lane))
            assertTrue("$lane recovers only after the earned proof", CortexAuthority8366.authority8366(
                proof, cal.uncalibrated8372(lane), 0.0, Double.NaN) > 0.0)
        }
    }

    @Test fun finiteOutcomesWithoutAvailableTimestampsRemainMissingInCoverage() {
        val rows = (0 until 200).map { i ->
            val strong = i % 2 == 0
            val at = 1_000_000L + i * 1_000L
            CortexAuthority8366.Row8366(at, if (strong) 10.0 else -10.0, if (strong) 30.0 else -20.0,
                (if (strong) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE).ordinal,
                if (i < 40) at + 300_000L else 0L, at + 300_000L)
        }
        val result = CortexAuthority8366.test8372(rows, 2.0, 2_000_000L)
        assertEquals(160, result.missing8392)
        assertEquals(0.2, result.strongCoverage8392, 1e-12)
        assertEquals(0.2, result.restCoverage8392, 1e-12)
        assertEquals(CortexAuthority8366.Verdict8366.INCOMPLETE_OUTCOMES, result.verdict)
        assertFalse(result.granted)
    }

    @Test fun migrationCarriesActualTradingTruthAndPreservesTheArchivedSource() {
        val old = JSONObject().put("ledger", JSONObject().put("badForward", -100))
            .put("calibration", JSONObject().put("badFit", -1))
            .put("joint7961", JSONObject().put("forwardSetup", -100))
            .put("vetoes", JSONObject().put("forwardVeto", -100))
            .put("real", JSONObject().put("LIVE", JSONObject().put("actualProfit", 12.5)))
            .put("board", JSONObject().put("CORE", "forwardBucket")
                .put("_realized", JSONObject().put("LIVE|CORE", "canonicalCloses")))
        val archive = old.toString()
        val carried = CortexForwardEpoch8521.realisedBooks(old)
        assertEquals(archive, old.toString())
        assertTrue(CortexForwardEpoch8521.current(carried))
        for (key in listOf("ledger", "calibration", "joint7961", "vetoes")) assertFalse(carried.has(key))
        assertEquals(12.5, carried.getJSONObject("real").getJSONObject("LIVE").getDouble("actualProfit"), 0.0)
        assertFalse(carried.getJSONObject("board").has("CORE"))
        assertEquals("canonicalCloses", carried.getJSONObject("board").getJSONObject("_realized").getString("LIVE|CORE"))
    }

    @Test fun migrationRetainsPositionBindingsAndTradeRowsWithoutOldRevisionCoordinates() {
        val old = JSONObject().put("p", JSONArray().put(JSONObject().put("ep", CortexForwardEpoch8521.OLD_BOOKS)))
            .put("dict", JSONArray().put("oldVoter"))
            .put("g8013", JSONArray().put(JSONObject().put("cal", -0.5)))
            .put("cold8372", JSONObject().put("oldTrace", true))
            .put("bound8076", JSONArray().put(JSONObject().put("pid", "real-position").put("sv8372", 1)))
            .put("seal8395", JSONArray().put(JSONObject().put("rk", "real-entry")))
        val carried = CortexForwardEpoch8521.positionPending(old)
        assertTrue(CortexForwardEpoch8521.current(carried))
        assertEquals(0, carried.getJSONArray("p").length())
        assertEquals(0, carried.getJSONArray("g8013").length())
        assertFalse(carried.has("cold8372"))
        assertEquals("real-position", carried.getJSONArray("bound8076").getJSONObject(0).getString("pid"))
        assertEquals(1, carried.getJSONArray("bound8076").getJSONObject(0).getInt("sv8372"))
        assertEquals(1, old.getJSONArray("p").length())
        val trade = "T\u001Freal-position\u001F12.5"
        val raw = "D\u001Fold-forward\u001F-100.0\u001E$trade\u001ED\u001Fanother-forward"
        assertEquals(trade, CortexForwardEpoch8521.realisedGrades(raw))
        assertFalse(CortexForwardEpoch8521.current(old))
        assertNotEquals(CortexForwardEpoch8521.OLD_BOOKS, CortexForwardEpoch8521.BOOKS)
        assertNotEquals(CortexForwardEpoch8521.OLD_PENDING, CortexForwardEpoch8521.PENDING)
        assertNotEquals(CortexForwardEpoch8521.OLD_GRADES, CortexForwardEpoch8521.GRADES)
    }
}
