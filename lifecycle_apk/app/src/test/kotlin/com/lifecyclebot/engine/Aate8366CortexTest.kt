package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LaneBrainVotes8366
import com.lifecyclebot.engine.truth.HonestExit8366
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8366 — the Cortex fix (audit 8360 item 5): rank (isotonic) calibration on winsorised log returns with UNCALIBRATED
 * on a flat / non-ranking fit; honest labels (stop at the first mark past it plus exit slippage, held-peak credit only for
 * a level held the sell latency); lane-brain votes that outlive the assessment's lock wait; one walk-forward authority test
 * that realised closes can only revoke.
 */
class Aate8366CortexTest {

    private fun src(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. rank calibration ──

    @Test fun isotonicCalibrationIsMonotoneAndMapsScoresToExpectedLogReturn() {
        val scores = (0 until 200).map { ((it * 37) % 200).toDouble() }
        // Outcome rises with the score, with a deterministic wobble that violates monotonicity locally.
        val outcomes = scores.map { s -> s * 0.5 - 20.0 + (if (s.toInt() % 3 == 0) 6.0 else -3.0) }
        val fit = CortexCalibration7901.fitIso8366(scores, outcomes)
        assertEquals(200, fit.n)
        assertTrue(fit.calibrated)
        assertTrue(fit.spearman > 0.9)
        var prev = Double.NEGATIVE_INFINITY
        var s = -10.0
        while (s <= 210.0) {
            val e = fit.expectedLog8366(s)
            assertTrue("monotone at $s", e >= prev - 1e-12)
            prev = e
            s += 0.5
        }
        // The map lands on the log return of the outcome the score earns (around score 100: +30 % -> ln 1.3).
        val mid = fit.expectedLog8366(100.0)
        assertTrue(kotlin.math.abs(mid - kotlin.math.ln(1.30)) < 0.05)
        assertTrue(kotlin.math.abs(fit.expectedPct8366(100.0) - 30.0) < 6.0)
        assertTrue(fit.expectedPct8366(190.0) > fit.expectedPct8366(10.0))
    }

    @Test fun theFitWinsorisesOnlyInsideTheFit() {
        assertEquals(kotlin.math.ln(4.0), CortexCalibration7901.winsorLog8366(5_000.0), 1e-12)
        assertEquals(kotlin.math.ln(0.01), CortexCalibration7901.winsorLog8366(-100.0), 1e-12)
        assertEquals(kotlin.math.ln(1.5), CortexCalibration7901.winsorLog8366(50.0), 1e-12)
        // The stored pair keeps the raw outcome: a +5,000 % label survives a persistence round trip unclipped.
        val c = CortexCalibration7901()
        c.learn("MOONSHOT", 5.0, 1.0, 5_000.0, 3.0)
        val back = CortexCalibration7901()
        back.decode(c.encode())
        assertTrue(c.encode().toString().contains("5000.0"))
        assertEquals(1, back.isoFit8366("MOONSHOT")?.n)
        assertTrue(c.encode().has(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "MOONSHOT"))
    }

    @Test fun aFlatOrNegativeRankFitIsUncalibratedAndHoldsNoAuthority() {
        val n = CortexCalibration7901.ISO_MIN_N_8366 + 20
        val flat = CortexCalibration7901()
        val neg = CortexCalibration7901()
        val good = CortexCalibration7901()
        for (i in 0 until n) {
            val score = ((i * 13) % n).toDouble()
            flat.learn("CORE", score, 0.0, 4.0, score)
            neg.learn("CORE", score, 0.0, 40.0 - score, score)
            good.learn("CORE", score, 0.0, score - 20.0, score)
        }
        assertTrue(flat.uncalibrated8366("CORE"))
        assertTrue(flat.suspends8366("CORE", 0.5))
        assertTrue(neg.uncalibrated8366("CORE"))
        assertTrue(neg.isoFit8366("CORE")!!.spearman <= 0.0)
        assertFalse(good.uncalibrated8366("CORE"))
        assertFalse(good.suspends8366("CORE", 0.5))
        // UNCALIBRATED -> no authority, whatever the walk-forward says.
        val granted = CortexAuthority8366.test8366(rankingRows(500), 3.0)
        assertTrue(granted.granted)
        assertEquals(0.0, CortexAuthority8366.authority8366(granted, true, 0.0, Double.NaN), 0.0)
        assertEquals("UNCALIBRATED", CortexAuthority8366.code8366(granted, true, 0.0, Double.NaN))
        // Thin fit: the stored slope rule still decides (nothing learned is thrown away).
        val thin = CortexCalibration7901()
        thin.learn("CORE", 1.0, 0.0, 5.0, 1.0)
        assertFalse(thin.uncalibrated8366("CORE"))
        assertTrue(thin.slope("CORE").isFinite())
    }

    // ── 8371 — calibration must learn on every graded decision, including cold-start
    // zero-deviation reads, and preserve runner revision weights over a restart. ──

    @Test fun aColdStartBaselinePredictionRetainsItsRunnerRevisionKey() {
        val c = CortexCalibration7901()
        // With no seated voters, edge == lane prior. Before 8371 this returned
        // NaN and stored an isotonic record that could never receive its later label.
        val stamp = c.learn("MOONSHOT", -10.0, -10.0, -12.0, -10.0)
        assertTrue(stamp.isFinite())
        assertEquals(1, c.isoFit8366("MOONSHOT")!!.n)
        assertTrue(c.revise8322("MOONSHOT", -10.0, -10.0, -12.0, 120.0, stamp))
        assertEquals(120.0, c.isoFit8366("MOONSHOT")!!.expectedPct8366(-10.0), 1e-8)
    }

    @Test fun theCalibrationDecayCoordinateSurvivesRestartAndRevisesTheSlope() {
        val c = CortexCalibration7901()
        var lastStamp = Double.NaN
        repeat(40) { i ->
            val score = if (i % 2 == 0) 2.0 else -2.0
            lastStamp = c.learn("SHITCOIN", score, 0.0, score * 0.5, score)
        }
        assertTrue(lastStamp.isFinite())
        val saved = c.encode()
        assertEquals(6, saved.getString("SHITCOIN").split(',').size)
        val restored = CortexCalibration7901()
        restored.decode(saved)
        // Last grade was -2 -> -1. The late runner label is +50; both the
        // isotonic pair and the weighted slope sums must be revised.
        assertTrue(restored.revise8322("SHITCOIN", -2.0, 0.0, -1.0, 50.0, lastStamp))
        assertEquals(40, restored.isoFit8366("SHITCOIN")!!.n)
        val after = restored.encode().getString("SHITCOIN").split(',').map { it.toDouble() }
        val before = saved.getString("SHITCOIN").split(',').map { it.toDouble() }
        assertTrue(after[3] > before[3])
        assertTrue(after[4] < before[4])
    }

    @Test fun theHistoricalFiveFieldCalibrationStillLoadsWithoutInventedGrades() {
        val c = CortexCalibration7901()
        val old = org.json.JSONObject().put("QUALITY", "5.0,1.0,2.0,1.0,2.0")
        c.decode(old)
        assertTrue(c.slope("QUALITY").isFinite())
        assertEquals(5.0, c.encode().getString("QUALITY").split(',')[0].toDouble(), 0.0)
        assertTrue(c.learn("QUALITY", 3.0, 0.0, 2.0, 3.0).isFinite())
    }

    @Test fun aHotLaneRetainsRankPairsUntilItsRunnerHoldWindow() {
        val c = CortexCalibration7901()
        val firstStamp = c.learn("MOONSHOT", 0.0, 0.0, -15.0, 0.0)
        repeat(1_500) { i ->
            c.learn("MOONSHOT", (i % 100).toDouble(), 0.0, -10.0 + i % 20, (i % 100).toDouble())
        }
        val before = c.encode().getString(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "MOONSHOT")
        assertTrue(before.startsWith("0.0:-15.0:"))
        assertTrue(c.revise8322("MOONSHOT", 0.0, 0.0, -15.0, 87.0, firstStamp))
        val after = c.encode().getString(CortexCalibration7901.ISO_KEY_PREFIX_8366 + "MOONSHOT")
        assertTrue(after.startsWith("0.0:87.0:"))
        assertEquals(1_501, c.isoFit8366("MOONSHOT")!!.n)
    }

    @Test fun twoDecisionsForOneMintKeepSeparateLabelsAndPreciseCalibrationProvenance() {
        val b = com.lifecyclebot.engine.cortex.CortexGradeBook8144()
        fun row(at: Long) = com.lifecyclebot.engine.cortex.CortexGradeRecord8144(
            'D', "mint|MOONSHOT|$at", "mint|MOONSHOT", at, "MOONSHOT", "IGNITION_5M",
            "LAUNCH", "EARLY", "LOW", "MC_LT10K", 1, -4.0, false, true, false,
        )
        val older = row(1_000_000L)
        val newer = row(2_000_000L)
        older.rawEdge8371 = 0.12345678901234567
        older.laneMean8371 = -11.987654321098765
        older.calStamp8371 = -1.2345678901234567
        assertTrue(b.addDecision(older))
        assertTrue(b.addDecision(newer))
        assertTrue(b.label(older.id, -15.0, -12.0, -15.0, true))
        assertTrue(b.label(newer.id, 40.0, 43.0, 40.0, true))
        val restored = com.lifecyclebot.engine.cortex.CortexGradeBook8144()
        restored.decode(b.encode())
        assertEquals(-15.0, restored.decision(older.id)!!.observedNetPct, 0.0)
        assertEquals(40.0, restored.decision(newer.id)!!.observedNetPct, 0.0)
        assertEquals(older.rawEdge8371, restored.decision(older.id)!!.rawEdge8371, 0.0)
        assertEquals(older.laneMean8371, restored.decision(older.id)!!.laneMean8371, 0.0)
        assertEquals(older.calStamp8371, restored.decision(older.id)!!.calStamp8371, 0.0)
        // A revision of the old observation changes only the old id.
        assertTrue(restored.label(older.id, 87.0, 100.0, 87.0, true))
        assertEquals(87.0, restored.decision(older.id)!!.observedNetPct, 0.0)
        assertEquals(40.0, restored.decision(newer.id)!!.observedNetPct, 0.0)
    }

    @Test fun theForwardLabelerBindsEveryCortexCallbackToTheOriginalObservation() {
        val cx = src("engine/cortex/Cortex7885.kt")
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("decisionAtMs8371 = o.atMs"))
        assertTrue(cx.contains("if (decisionAtMs8371 > 0L && p.atMs != decisionAtMs8371)"))
        assertTrue(cx.contains("val id = \"\$key|\$decisionAtMs8371\""))
        assertTrue(cx.contains("val g = gradedTrace8372(id)"))
        assertTrue(cx.contains("r.calStamp8371"))
        assertFalse(cx.contains("val g = graded8006[key] ?: run"))
        assertFalse(cx.contains("gradeBook8144.latestLabelled8324(key) ?: return"))
    }

    // ── 2. honest labels ──

    @Test fun aStopIsGradedAtTheFirstMarkBelowItWithExitSlippage() {
        val lows = ArrayList<DoubleArray>()
        assertTrue(HonestExit8366.noteLow8366(lows, 10_000L, -4.0, 5_000L, -1.0))
        assertTrue(HonestExit8366.noteLow8366(lows, 20_000L, -12.0, 15_000L, -6.0))
        assertTrue(HonestExit8366.noteLow8366(lows, 30_000L, -40.0, 25_000L, -20.0))
        val fill = HonestExit8366.stopFill8366(lows, 8.0, HonestExit8366.DEFAULT_SELL_LATENCY_MS_8366)
        // First mark at or below -8 is -12 (not -8, not the -40 low), then the velocity into it over the 10 s latency.
        val drift = (0.88 / 0.94 - 1.0) * 100.0 * (10_000.0 / 5_000.0)
        val expect = (0.88 * (1.0 + drift / 100.0) - 1.0) * 100.0
        assertEquals(expect, fill, 1e-9)
        assertTrue(fill < -12.0)
        // Stopped before the peak: the label IS the fill (the old play graded exactly -8).
        val ladder: (Double, Double) -> Double = { _, rest -> rest }
        assertEquals(fill, HonestExit8366.playGross8366(8.0, 30.0, -12.0, -40.0, 25.0, fill, ladder), 1e-12)
        assertEquals(-8.0, com.lifecyclebot.engine.LaneParticipation8029.playGross8029(8.0, 30.0, -12.0, -40.0, 25.0, ladder), 1e-12)
        // No recorded mark reached the stop: the play keeps -stop.
        assertTrue(HonestExit8366.stopFill8366(lows, 50.0, 10_000L).isNaN())
        assertEquals(-50.0, HonestExit8366.playGross8366(50.0, 0.0, -51.0, -51.0, -30.0, Double.NaN, ladder), 1e-12)
        assertTrue(HonestExit8366.MAX_LOWS_8366 >= 32)
    }

    @Test fun heldPeakCreditNeedsTheHoldTime() {
        val hold = HonestExit8366.DEFAULT_SELL_LATENCY_MS_8366
        // A 50 % print that reverts inside the latency is never a level a sell could land at.
        val w = ArrayDeque<DoubleArray>()
        var h = 0.0
        for ((t, g) in listOf(0L to 0.0, 1_000L to 50.0, 7_000L to 50.0, 8_000L to 0.0, 12_000L to 0.0)) h = HonestExit8366.heldPeakStep8366(w, t, g, h, hold)
        assertEquals(0.0, h, 0.0)
        // Held for the full latency: credited.
        val w2 = ArrayDeque<DoubleArray>()
        var h2 = 0.0
        for ((t, g) in listOf(0L to 0.0, 1_000L to 50.0, 6_000L to 50.0, 11_000L to 50.0, 12_000L to 50.0)) h2 = HonestExit8366.heldPeakStep8366(w2, t, g, h2, hold)
        assertEquals(50.0, h2, 0.0)
        assertEquals(10_000L, HonestExit8366.DEFAULT_SELL_LATENCY_MS_8366)
    }

    // ── 3. lane-brain votes ──

    @Test fun theVoteTtlCoversTheLockWait() {
        val ttl = LaneBrainVotes8366.ttlMs8366(95_000L, 3_000L)
        assertTrue(ttl >= 95_000L + 3_000L)
        assertTrue(ttl >= LaneBrainVotes8366.TTL_FLOOR_MS_8366)
        assertTrue(ttl <= LaneBrainVotes8366.TTL_CAP_MS_8366)
        assertEquals(LaneBrainVotes8366.TTL_CAP_MS_8366, LaneBrainVotes8366.ttlMs8366(3_600_000L, 0L))
        assertEquals(LaneBrainVotes8366.TTL_FLOOR_MS_8366, LaneBrainVotes8366.ttlMs8366(0L, 0L))
        assertTrue(LaneBrainVotes8366.PAD_MS_8366 > 0L)
        val now = 1_800_000_000_000L
        // A vote read 95 s before the assessment (the reported lock wait): the 5 s snapshot read refused it; the stamp accepts it.
        assertFalse(SpecialistBrainBridge7542.snapshotReadable8169(now - 95_000L, now))
        assertTrue(LaneBrainVotes8366.accepts8366(now - 95_000L, now, ttl))
        assertFalse(LaneBrainVotes8366.accepts8366(now - ttl - 1L, now, ttl))
        assertFalse(LaneBrainVotes8366.accepts8366(now + 1_000L, now, ttl))   // a future stamp is never used
        // Stamped at the decision, resolved after the snapshot expired.
        val mint = "MINT8366_${System.nanoTime()}"
        val votes = listOf(CortexLedger7885.Vote("LB_MOONSHOT_S", doubleArrayOf(0.0, 50.0), 70.0, setOf("lane_brain_LB_MOONSHOT")))
        val t0 = System.currentTimeMillis()
        LaneBrainVotes8366.stamp8366(mint, votes, t0)
        assertEquals(votes, LaneBrainVotes8366.resolve8366(mint, emptyList(), t0 + 90_000L))
        assertTrue(LaneBrainVotes8366.resolve8366(mint, emptyList(), t0 + LaneBrainVotes8366.ttlNow8366() + 1_000L).isEmpty())
        assertTrue(LaneBrainVotes8366.resolve8366("COLD_$mint", emptyList(), t0).isEmpty())
        assertTrue(LaneBrainVotes8366.line8366().contains("expired="))
    }

    // ── 4. one walk-forward authority test ──

    private fun rankingRows(n: Int, slope: Double = 0.5): List<CortexAuthority8366.Row8366> = (0 until n).map { i ->
        val score = ((i * 37) % 100).toDouble()
        CortexAuthority8366.Row8366(1_000_000L + i * 60_000L, score, score * slope - 10.0)
    }

    @Test fun theSingleWalkForwardGrantsOnOutOfSampleRankAndSpread() {
        val r = CortexAuthority8366.test8366(rankingRows(500), 3.0)
        assertEquals(CortexAuthority8366.Verdict8366.GRANTED, r.verdict)
        assertTrue(r.oosN >= CortexAuthority8366.MIN_OOS_N_8366)
        assertTrue(r.ic > 0.0)
        assertTrue(r.spread > 3.0)
        assertEquals(1.0, r.fraction, 1e-12)
        assertTrue(r.topN >= CortexAuthority8366.MIN_BUCKET_N_8366 && r.bottomN >= CortexAuthority8366.MIN_BUCKET_N_8366)
        // Cut-offs come from the folds BEFORE each tested fold (time-ordered, five folds, top / bottom fifth).
        assertEquals(5, CortexAuthority8366.FOLDS_8366)
        assertEquals(0.8, CortexAuthority8366.TOP_Q_8366, 0.0)
        assertEquals(0.2, CortexAuthority8366.BOTTOM_Q_8366, 0.0)
        assertEquals(8.0, CortexAuthority8366.quantile8366((0..10).map { it.toDouble() }, 0.8), 0.0)
        assertTrue(CortexAuthority8366.quantile8366(emptyList(), 0.5).isNaN())
        // Graduated by the margin over cost.
        assertEquals(0.5, CortexAuthority8366.graduated8366(4.5, 3.0), 1e-12)
        assertEquals(0.0, CortexAuthority8366.graduated8366(2.9, 3.0), 0.0)
        assertEquals(0.25, CortexAuthority8366.graduated8366(1.5, 1.0), 1e-12)   // the 2-point floor on the scale
        // Too few out-of-sample rows: nothing yet (but no realised close is needed either).
        assertEquals(CortexAuthority8366.Verdict8366.INSUFFICIENT_OOS, CortexAuthority8366.test8366(rankingRows(15), 3.0).verdict)
        // V5.0.8399 — from 15 OOS a sample whose edge clears its own noise earns a PARTIAL (ramp) authority, below the full grant.
        val ramp = CortexAuthority8366.test8366(rankingRows(100), 3.0)
        assertEquals(CortexAuthority8366.Verdict8366.RAMP_8399, ramp.verdict)
        assertTrue(ramp.granted)
        assertTrue(ramp.fraction > 0.0 && ramp.fraction < CortexAuthority8366.test8366(rankingRows(500), 3.0).fraction)
        assertEquals(CortexAuthority8366.graduated8366(ramp.spread, 3.0) * CortexAuthority8366.rampWeight8399(ramp.oosN), ramp.fraction, 1e-12)
        // The ramp's shape: 15 OOS / 5 per bucket is the first rung, the noise multiple falls 2.0 -> 1.0, the weight rises 0.15 -> 1.0.
        assertEquals(15, CortexAuthority8366.MIN_RAMP_OOS_N_8399); assertEquals(5, CortexAuthority8366.MIN_RAMP_BUCKET_N_8399)
        assertEquals(2.0, CortexAuthority8366.rampK8399(CortexAuthority8366.MIN_RAMP_OOS_N_8399), 1e-12)
        assertEquals(1.0, CortexAuthority8366.rampK8399(CortexAuthority8366.MIN_OOS_N_8366), 1e-12)
        assertEquals(CortexAuthority8366.RAMP_FLOOR_WEIGHT_8399, CortexAuthority8366.rampWeight8399(CortexAuthority8366.MIN_RAMP_OOS_N_8399), 1e-12)
        assertEquals(1.0, CortexAuthority8366.rampWeight8399(CortexAuthority8366.MIN_OOS_N_8366), 1e-12)
        assertTrue(CortexAuthority8366.rampWeight8399(50) > CortexAuthority8366.rampWeight8399(20))
        // Coverage scaling: 1 at the 0.7 bar, proportional below it, nothing under MIN_COVERAGE_FOR_ANY_8399.
        assertEquals(1.0, CortexAuthority8366.coverageScale8399(0.7), 1e-12)
        assertEquals(0.5 / 0.7, CortexAuthority8366.coverageScale8399(0.5), 1e-12)
        assertEquals(0.0, CortexAuthority8366.coverageScale8399(CortexAuthority8366.MIN_COVERAGE_FOR_ANY_8399 - 0.01), 0.0)
        // A score that ranks backwards, or a spread that does not pay the cost, earns nothing.
        val inv = rankingRows(500).map { it.copy(netPct = 40.0 - it.score * 0.5) }
        assertEquals(CortexAuthority8366.Verdict8366.NO_RANK, CortexAuthority8366.test8366(inv, 3.0).verdict)
        assertEquals(CortexAuthority8366.Verdict8366.SPREAD_BELOW_COST, CortexAuthority8366.test8366(rankingRows(500, 0.01), 3.0).verdict)
        // A ranking whose top bucket still loses net earns no STRONG authority.
        val losing = rankingRows(500).map { it.copy(netPct = it.netPct - 60.0) }
        assertEquals(CortexAuthority8366.Verdict8366.TOP_NOT_POSITIVE, CortexAuthority8366.test8366(losing, 3.0).verdict)
    }

    @Test fun realisedClosesOnlyRevokeAndOnlyFromTwenty() {
        val r = CortexAuthority8366.test8366(rankingRows(500), 3.0)
        assertTrue(r.granted)
        // No realised close at all: authority is granted (the 8136 deadlock is gone).
        assertEquals(1.0, CortexAuthority8366.authority8366(r, false, 0.0, Double.NaN), 1e-12)
        // Nineteen losing closes cannot revoke; twenty can.
        assertEquals(1.0, CortexAuthority8366.authority8366(r, false, 19.0, -30.0), 1e-12)
        assertEquals(0.0, CortexAuthority8366.authority8366(r, false, 20.0, -0.1), 0.0)
        assertEquals("REVOKED_REALISED", CortexAuthority8366.code8366(r, false, 25.0, -3.0))
        // Winning closes never raise an ungranted lane (V5.0.8399: under 15 OOS is ungranted; 100 rows is a ramp grant).
        val none = CortexAuthority8366.test8366(rankingRows(15), 3.0)
        assertFalse(none.granted)
        assertEquals(0.0, CortexAuthority8366.authority8366(none, false, 200.0, 50.0), 0.0)
        val ramp = CortexAuthority8366.test8366(rankingRows(100), 3.0)
        assertEquals(ramp.fraction, CortexAuthority8366.authority8366(ramp, false, 200.0, 50.0), 1e-12)   // closes never raise above the proof
        assertEquals(0.0, CortexAuthority8366.authority8366(ramp, false, 20.0, -0.1), 0.0)              // and still revoke a ramp
        assertEquals(CortexAuthority8366.REVOKE_MIN_N_8366, 20)
        assertEquals("OPEN", CortexAuthority8366.code8366(r, false, 0.0, Double.NaN))
    }

    // ── 5. source pins ──

    @Test fun sourcePins8366() {
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("private fun laneFractionHeld8187(lane: String): Double = authorityFraction8366(lane)"))
        assertTrue(cx.contains("private fun heldFraction8153(lane: String): Double = authorityFraction8366(lane)"))
        // 8372 restores one arithmetic token forecast. The old log fit remains a diagnostic API,
        // not the decision path; an uncalibrated lane must not rewrite every token to NEUTRAL.
        assertTrue(cx.contains("calibratedRead8372(runner, fused, locked8325.slope, locked8325.net8372, executableOffset8187(lane))"))
        assertTrue(cx.contains("calibration.netFit8372(lane, executableOffset8187(lane))"))
        assertFalse(cx.contains("uncalibratedNeutral8366(lane, locked8325.iso8366,"))
        assertTrue(cx.contains("CortexAuthority8366.test8372("))
        assertTrue(cx.contains("scoreVersion = 1"))
        assertTrue(cx.contains("calibrationScore8372(p.a),"))
        assertTrue(cx.contains("targetBasis8372 = if (shadowExit8372) CortexCalibration7901.TARGET_SHADOW_NET_8372 else CortexCalibration7901.TARGET_FORWARD_NET_8372"))
        assertTrue(cx.contains("CortexAssessmentProvenance8372.decode("))
        assertTrue(cx.contains("CortexVoters7885.stampLaneBrains8366(ts, nowMs)"))
        assertTrue(cx.contains("LaneBrainVotes8366.noteWaitMs8366("))
        assertTrue(cx.contains("nullWhy8201<CortexWalkForward8144.Stat8144>(\"AUTHORITY_8366_"))
        assertFalse(cx.contains("calibration.suspends8322(lane, MIN_CONSISTENT_SLOPE)) return 0.0"))
        val voters = src("engine/cortex/CortexVoters7885.kt")
        assertTrue(voters.contains("LaneBrainVotes8366.resolve8366(ts.mint, laneBrainVotes8003(ts), System.currentTimeMillis())"))
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        // V5.0.8379 — a launch-window entry grades the window play (still honest fills: HonestExit8366.stopFill8366); others the lane play.
        assertTrue(lab.contains("else playGross8366(o, stop8029, gross, ladder8040)"))
        assertTrue(lab.contains("HonestExit8366.stopFill8366(o.lows8366.filter"))
        assertTrue(lab.contains("playGross8366(o, s, gross, ladder8040)"))
        assertTrue(lab.contains("val held8366 = if (o.honest8366) o.heldPeak8366 else o.heldPeak8071"))
        assertTrue(lab.contains("it.honest8366 = true"))
        assertTrue(lab.contains("\${honestLine8366()}"))
        val cal = src("engine/cortex/CortexCalibration7901.kt")
        assertTrue(cal.contains("const val MAX_SLOPE = 1.5"))   // the old slope is still learned and shown
        assertTrue(cal.contains("fun fitIso8366("))
        assertTrue(src("engine/cortex/CortexGradeBook8144.kt").contains("fun scoredRows8366(lane: String, runner: Boolean, scoreVersion: Int? = null, setup: String? = null)"))
    }
}
