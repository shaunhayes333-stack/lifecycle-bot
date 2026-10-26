package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexFlatMark8322
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexTailDoor8322
import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8322 — the Cortex's measured edge restored on the owner's 8072 directive (no win caps on chain-observed data):
 * raw labels at ingestion, runner lanes graded on their hold-window label, a tail-aware STRONG door, flat marks kept out of
 * the books, and no calibration suspension on a thin or spread-less fit.
 */
class Aate8322CortexSignalTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal

    private fun decision(book: CortexGradeBook8144, mint: String, lane: String, atMs: Long, bucket: Int = strong): String {
        val id = "$mint|$lane|$atMs"
        assertTrue(book.addDecision(CortexGradeRecord8144('D', id, "$mint|$lane", atMs, lane, "S", "BULL", "MID", "C3_6", "MC_10K_100K", bucket, 4.5, true, true, false)))
        return id
    }

    // ── tail-aware STRONG door ──

    @Test fun aTailDependentCohortWithANegativeMedianIsStrongEligibleOnTheTailRead() {
        // The 8303 pond: most coins lose a little, a few run. Median < 0, mean > 0.
        val pattern = List(5) { -10.0 } + listOf(150.0)
        val values = List(10) { pattern }.flatten()
        assertTrue(CortexWalkForward8144.median8238(values) < 0.0)            // 8238's door could never open here
        val tail = CortexTailDoor8322.read8322(values)
        assertTrue(tail.mean > 0.0)
        assertTrue(tail.leaveTopOutMean > 0.0)
        assertEquals(10, tail.runners)
        assertTrue(tail.why, tail.open)
        assertEquals(3, CortexTailDoor8322.MIN_RUNNERS_8322)
        // The same series, time-ordered, validates out of sample on its mean (walk-forward mean - SE past the margin).
        val timed = values.mapIndexed { i, v -> CortexWalkForward8144.Timed8144(t0 + i * 10L * minute, v) }
        assertTrue(CortexWalkForward8144.promoteTimed8196(timed).validated)
    }

    @Test fun oneCoinOrNoTailNeverOpensTheDoor() {
        val oneCoin = List(35) { -5.0 } + listOf(2_000.0)
        val r = CortexTailDoor8322.read8322(oneCoin)
        assertTrue(r.mean > 0.0)
        assertFalse(r.open); assertEquals("ONE_COIN", r.why)
        val noTail = List(20) { if (it % 2 == 0) 12.0 else -4.0 }
        assertEquals("FEW_RUNNERS", CortexTailDoor8322.read8322(noTail).why)
        assertEquals("MEAN_NOT_POSITIVE", CortexTailDoor8322.read8322(List(10) { -3.0 }).why)
        assertEquals("EMPTY", CortexTailDoor8322.read8322(emptyList()).why)
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("val tail8322 = if (runnerHorizon8144(a.lane)) CortexTailDoor8322.read8322(series) else null"))
        // superseded by V5.0.8366 — the STRONG door is the one walk-forward test (OOS top-bucket record); the tail / median doors are
        // still read and counted for display (STRONG_COHORT_LEGACY_8366_<why>).
        assertTrue(cx.contains("return if (!(CortexWalkForward8144.median8238(series) > 0.0)) \"MEDIAN_NOT_POSITIVE\" else null"))
        assertTrue(cx.contains("strongDoorLegacy8366(a)?.let { inc(\"STRONG_COHORT_LEGACY_8366_\$it\") }"))
    }

    // ── no clamp at ingestion; OUTLIER_SUSPECT flags, never vetoes ──

    @Test fun labelsAreStoredRawAtIngestion() {
        val ids = listOf("V"); val edges = listOf(doubleArrayOf(0.5))
        val ledger = CortexLedger7885()
        repeat(3) { ledger.grade("SHITCOIN", ids, edges, doubleArrayOf(1.0), 3_000.0, 3_050.0) }
        ledger.grade("SHITCOIN", ids, edges, doubleArrayOf(1.0), -10.0, -8.0)
        val lane = ledger.lanes["SHITCOIN"]!!
        assertEquals(8_990.0, lane.sum, 1e-9)                               // 8187 stored 2,990
        assertEquals(8_990.0, ledger.seats["V|SHITCOIN"]!!.bins[1].sum, 1e-9)
        // Flagged, not vetoed: three runners make the tail real, so the decision read is the raw mean; only the
        // prediction regulariser keeps the 8187 bound.
        assertTrue(lane.suspect8187())
        assertTrue(lane.tailReal8322())
        assertEquals(8_990.0 / 4.0, lane.mean(), 1e-9)
        assertEquals(3_000.0 / 4.0, lane.predictMean8322(), 1e-9)
        assertTrue(CortexLedger7885.fmt8187(lane).startsWith("n4/+2247.5%~bound+750.0%"))
        // One implausible label alone is stored raw but read at its bound (the 8175 single-label case).
        val board = CortexScoreboard7885()
        board.record("MOONSHOT", CortexScoreboard7885.Bucket.STRONG, false, 3_000.0, 3_050.0)
        val st = board.books["MOONSHOT"]!!.byBucket[strong]
        assertEquals(3_000.0, st.sum, 1e-9)
        assertFalse(st.tailReal8322())
        assertEquals(1_000.0, st.mean(), 1e-9)
        assertEquals(2.0, CortexLedger7885.MIN_TAIL_RUNNERS_8322 - 1.0, 0.0)
        // The grade book keeps the raw executable value (8187 stored the cap).
        val book = CortexGradeBook8144()
        val id = decision(book, "RUN", "MOONSHOT", t0)
        assertTrue(book.label(id, 3_000.0, 3_050.0, 3_000.0, true))
        assertEquals(3_000.0, book.decision(id)!!.executablePct, 0.0)
        assertEquals(listOf(3_000.0), book.executableSeries("MOONSHOT", null, strong))
        val on = src("engine/cortex/Cortex7885.kt").substringAfter("fun onLabel(mint: String, labelLane: String").substringBefore("// ── V5.0.8144 — grade records")
        assertFalse(on.contains("clampLabel8187(netPct, cap8187)"))
        assertTrue(on.contains("val net8187 = netPct"))
    }

    @Test fun aTailBookWithSeveralRunnersCarriesAuthorityOnItsRawMean() {
        val st = CortexLedger7885.labelStat8187()
        repeat(40) { i -> st.add(if (i % 8 == 0) 1_400.0 else -12.0, i % 8 == 0) }
        assertTrue(st.suspect8187())                         // runners above the 1,000% cap
        assertTrue(st.mean() - kotlin.math.sqrt(st.variance() / st.n) > 2.0)
        assertTrue(CortexScoreboard7885.authorityFraction7955(st, CortexLedger7885.labelStat8187(), false) > 0.0)
    }

    // ── zero-label (flat mark) poisoning ──

    @Test fun aFlatMarkIsObservedOnlyAndNeverACohortLoss() {
        assertTrue(CortexFlatMark8322.flatMark8322(0.0, 0.0, 0.0))
        assertFalse(CortexFlatMark8322.flatMark8322(0.0, 0.4, 0.0))      // it moved and came back: a real 0
        assertFalse(CortexFlatMark8322.flatMark8322(-0.2, 0.0, -0.2))
        assertTrue(CortexFlatMark8322.observedOnly8322(true, true, false, false))
        assertFalse(CortexFlatMark8322.observedOnly8322(true, false, false, false))
        assertEquals(0, CortexFlatMark8322.rebaseDirection8322(1.0, 1.0))
        assertEquals(-1, CortexFlatMark8322.rebaseDirection8322(1.0, 0.9))
        assertEquals(1, CortexFlatMark8322.rebaseDirection8322(1.0, 1.1))
        val book = CortexGradeBook8144()
        val moved = decision(book, "A", "TREASURY", t0)
        assertTrue(book.label(moved, -4.0, -1.0, -4.0, true))
        val flat = decision(book, "B", "TREASURY", t0 + minute)
        assertTrue(book.label(flat, -3.0, 0.0, -3.0, true))               // gross 0, net = -cost
        assertTrue(book.markFlat8322(flat))
        assertEquals(listOf(-4.0), book.executableSeries("TREASURY", null, strong))
        assertEquals(1.0, book.resolvedShare("TREASURY", strong), 0.0)    // neither resolves nor loses its cohort
        assertEquals(1, book.flat8322())
        assertEquals(null, book.lastGradedAt("B|TREASURY"))               // the coin's episode stays open
        // A later horizon cannot give a flat decision an executable value.
        assertTrue(book.label(flat, 40.0, 43.0, 40.0, true))
        assertTrue(book.decision(flat)!!.executableRevisedPct.isNaN())
        val back = CortexGradeBook8144().also { it.decode(book.encode()) }
        assertTrue(back.decision(flat)!!.flat8322)
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        // The five-minute label carries chain-witness provenance and the original token-episode identity (8371).
        assertTrue(frl.contains("Cortex7885.onLabel(o.mint, o.lane, 60, net, gross, fam8080, flat8322 = flat8322, witness8324 = w8324, decisionAtMs8371 = o.atMs, proof8395 = outcomeProof8395(o, net, gross, H60_MS_7731))") /* superseded by V5.0.8395 (R3): label and its proof travel together */)
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("gradeBook8144.markFlat8322(\"\$key|\${p.atMs}\")"))
    }

    // ── calibration grade floor ──

    @Test fun calibrationDoesNotSuspendBelowTheGradeFloorOrOnASpreadlessFit() {
        val c = CortexCalibration7901()
        // An anti-calibrated lane: the measured slope falls under 0.5 from ~60 grades (8291) ...
        repeat(80) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; c.learn("MOONSHOT", d, 0.0, -d) }
        assertTrue(c.slope("MOONSHOT") < 0.5)
        assertFalse(c.suspendable8322("MOONSHOT"))
        assertFalse(c.suspends8322("MOONSHOT", 0.5))                       // ... but cannot switch the lane off yet
        repeat(80) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; c.learn("MOONSHOT", d, 0.0, -d) }
        assertTrue(c.suspends8322("MOONSHOT", 0.5))                        // 100+ grades with real spread: it can
        // Deviations that barely differ: a slope over near-identical d is noise.
        val q = CortexCalibration7901()
        repeat(300) { i -> val d = if (i % 2 == 0) 0.10 else 0.20; q.learn("TREASURY", d, 0.0, if (i % 2 == 0) 5.0 else -5.0) }
        assertTrue(q.slope("TREASURY") < 0.5)
        assertFalse(q.suspends8322("TREASURY", 0.5))
        assertFalse(CortexCalibration7901.suspendable8322(99.0, 0.0, 1_000.0))
        assertTrue(CortexCalibration7901.suspendable8322(150.0, 0.0, 150.0))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertFalse(cx.contains("calibration.slope(lane) < MIN_CONSISTENT_SLOPE"))
        // 8372 uses arithmetic calibration for authority, with the historical slope retained for thin fits.
        assertTrue(cx.contains("val ok = !calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))"))
        assertTrue(cx.contains("calibration.uncalibrated8372(lane, executableOffset8187(lane))"))
    }

    @Test fun aRunnerLaneRecalibratesOnItsHoldWindowLabel() {
        val c = CortexCalibration7901()
        val stamps = ArrayList<Double>()
        // Five-minute reads say the Cortex is backwards ...
        repeat(150) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; stamps.add(c.learn("MOONSHOT", d, 0.0, -d)) }
        assertTrue(c.slope("MOONSHOT") < 0.5)
        // ... the hold-window labels say it was right: each grade is revised at the weight it still owns.
        stamps.forEachIndexed { i, s -> val d = if (i % 2 == 0) 10.0 else -10.0; assertTrue(c.revise8322("MOONSHOT", d, 0.0, -d, d, s)) }
        assertEquals(1.0, c.slope("MOONSHOT"), 1e-6)
        // A zero-deviation observation still owns its outcome mean and exact raw pair (8371/8372).
        val zeroStamp = c.learn("MOONSHOT", 0.0, 0.0, 1.0, 0.0)
        assertTrue(c.revise8322("MOONSHOT", 0.0, 0.0, 1.0, 2.0, zeroStamp))
    }

    // ── runner lanes grade on the long horizon ──

    @Test fun aRunnerLaneIsGradedOnItsHoldWindowRevisionWithoutLeakingIntoEarlierFolds() {
        val book = CortexGradeBook8144()
        book.runnerLane8322 = { it == "MOONSHOT" }
        val run = decision(book, "RUN", "MOONSHOT", t0)
        assertTrue(book.label(run, -6.0, -4.0, -6.0, true, availableAtMs = t0 + 5 * minute))
        assertTrue(book.label(run, 80.0, 90.0, 80.0, true, availableAtMs = t0 + 240 * minute))
        val r = book.decision(run)!!
        assertEquals(-6.0, r.executablePct, 0.0)                              // 8272: the first label is never rewritten
        assertEquals(80.0, book.objectivePct8322(r), 0.0)                     // the runner lane is graded on its hold
        assertEquals(t0 + 240 * minute, book.objectiveAvailableAt8322(r))
        assertEquals(listOf(80.0), book.executableSeries("MOONSHOT", null, strong))
        val timed = book.executableTimed8196("MOONSHOT", null, strong).single()
        assertEquals(-6.0, timed.knownAt8322(t0 + 30 * minute)!!, 0.0)        // a fold at t0+30 min sees the 5-minute label
        assertEquals(80.0, timed.knownAt8322(t0 + 300 * minute)!!, 0.0)
        assertEquals(null, timed.knownAt8322(t0 + minute))
        // A scalp lane keeps its first label as its objective.
        val scalp = decision(book, "SC", "CORE", t0)
        assertTrue(book.label(scalp, -2.0, -1.0, -2.0, true))
        assertTrue(book.label(scalp, 30.0, 31.0, 30.0, true))
        assertEquals(listOf(-2.0), book.executableSeries("CORE", null, strong))
        // The revision survives a restart.
        val back = CortexGradeBook8144().also { it.decode(book.encode()); it.runnerLane8322 = { l -> l == "MOONSHOT" } }
        assertEquals(listOf(80.0), back.executableSeries("MOONSHOT", null, strong))
        assertEquals(t0 + 240 * minute, back.decision(run)!!.revisedAvailableAtMs)
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("b.runnerLane8322 = { l -> runnerHorizon8144(l) }"))
        // The shared revision path replaces the exact original calibration contribution at its original weight.
        val revision = cx.substringAfter("private fun reviseModelsLocked8372(").substringBefore("fun progressiveLine8006()")
        assertTrue(revision.contains("val a = g.p.a; val oldNet = g.net; val oldGross = g.gross; val wasWitnessed = g.witnessed8324"))
        assertTrue(revision.contains("calibration.revise8322(a.lane, a.fused.edgePct, a.fused.laneMean, oldNet, newNet, g.calStamp8322,"))
        assertTrue(revision.contains("targetBasis8372 = if (shadowExit8372) CortexCalibration7901.TARGET_SHADOW_NET_8372 else null"))
        val forward = cx.substringAfter("fun reviseLabel8006(").substringBefore("private fun reviseModelsLocked8372(")
        assertTrue(forward.contains("val id = \"\$key|\$decisionAtMs8371\""))
        assertTrue(forward.contains("val g = gradedTrace8372(id) ?: run {"))
        // superseded by V5.0.8395: the revision mutates models only with its validated proof (R3).
        assertTrue(forward.contains("reviseModelsLocked8372(id, g, newNet, newGross, proof8395?.witnessed8395() == true)"))
    }

    @Test fun aLegacyClampedRecordIsMarkedNotRewritten() {
        val book = CortexGradeBook8144()
        val id = decision(book, "OLD", "SHITCOIN", t0)
        assertTrue(book.label(id, 4_000.0, 4_050.0, 1_000.0, true))           // 8187 stored the cap as the executable value
        assertEquals(1, book.clampedLegacy8322())
        assertEquals(1_000.0, book.decision(id)!!.executablePct, 0.0)
    }
}
