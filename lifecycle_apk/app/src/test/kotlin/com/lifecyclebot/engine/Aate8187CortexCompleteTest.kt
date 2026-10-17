package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexExit7897
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexTiming7900
import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.market.LaunchTape7921
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.RobustLabelRead8185
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8187 — the whole Cortex, finished: every book reads through the label cap and every new label is clamped at
 * ingestion (outlier-poisoned means), the capture -> label -> grade path files every decision (graded / lost / late), the
 * realised closes find their entry verdict, the authority gates say which one blocks each lane, the first-block voter's
 * paid feature is not bought for coins that cannot trade, and the status lines show robust beside raw.
 */
class Aate8187CortexCompleteTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 2_000_000_000_000L
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
    private val neutral = CortexScoreboard7885.Bucket.NEUTRAL.ordinal
    private val refuse = CortexScoreboard7885.Bucket.REFUSE.ordinal

    // ── 1. outlier-poisoned books ──

    /** 5.0.8175 MOONSHOT neutral n80 +788.8%: 30 small winners, 49 small losers and one +60,000% basis-bug runner. */
    private fun poisoned(): CortexLedger7885.Stat = CortexLedger7885.labelStat8187().also { st ->
        repeat(30) { st.add(10.0, false) }
        repeat(49) { st.add(-10.0, false) }
        st.add(60_000.0, true)
    }

    @Test fun aPoisonedBookReadsAtItsRunnerAwareBoundWithRawBeside() {
        val st = poisoned()
        assertEquals(59_810.0 / 80.0, st.rawMean8187(), 1e-9)
        assertTrue(st.suspect8187())
        // At most min(runners, wins) = 1 label up to the 1,000% cap, every other winner at most the +50% runner threshold.
        assertEquals((1_000.0 + 30.0 * 50.0) / 80.0, st.mean(), 1e-9)
        assertEquals(st.mean() * st.n, st.readSum8187(), 1e-9)
        // The winsorised range is the standard error: no proven edge, no manufactured loss.
        val se = kotlin.math.sqrt(st.variance() / st.n)
        assertTrue("mean-se=${st.mean() - se}", st.mean() - se < 2.0)
        assertTrue("mean+se=${st.mean() + se}", st.mean() + se > -2.0)
        val shown = CortexLedger7885.fmt8187(st)
        assertTrue(shown, shown.startsWith("n80/+31.3%") && shown.contains("~raw+747.6%"))
        // The stored sums are untouched.
        assertEquals(59_810.0, st.sum, 1e-9)
    }

    @Test fun aCleanRunnerBookReadsExactlyAsBefore() {
        // The 8072 case: 10% of coins run 10x, 90% lose 20% — the real mean +72% is not a poisoned book.
        val st = CortexLedger7885.labelStat8187()
        repeat(9) { st.add(-20.0, false) }
        st.add(900.0, true)
        assertFalse(st.suspect8187())
        assertEquals(72.0, st.mean(), 1e-9)
        assertEquals(st.rawVariance8187(), st.variance(), 1e-9)
        assertEquals("n10/+72.0%", CortexLedger7885.fmt8187(st))
    }

    @Test fun realisedMoneyIsNeverCappedOrBounded() {
        val st = CortexLedger7885.realisedStat8187()
        st.add(5_000.0, true); st.add(-10.0, false)
        assertFalse(st.suspect8187())
        assertEquals(2_495.0, st.mean(), 1e-9)
    }

    @Test fun robustReadPureHelpers() {
        val r = RobustLabelRead8185
        // A positive sum with no recorded winner means the count was never kept: every label may be a winner.
        assertEquals(10.0, r.winsForBound8187(10.0, 50.0, 0.0), 0.0)
        assertEquals(3.0, r.winsForBound8187(10.0, 50.0, 3.0), 0.0)
        assertEquals(0.0, r.winsForBound8187(10.0, -50.0, 0.0), 0.0)
        // Without a runner floor the bound is 8185's winners x cap.
        assertEquals(r.upperMean8185(10.0, 5_000.0, 2.0, 1_000.0), r.upperMeanRunner8187(10.0, 5_000.0, 2.0, 0.0, 1_000.0, Double.POSITIVE_INFINITY), 1e-12)
        assertEquals((1_000.0 + 50.0) / 10.0, r.upperMeanRunner8187(10.0, 5_000.0, 2.0, 1.0, 1_000.0, 50.0), 1e-12)
        val read = r.robust8187(80.0, 59_810.0, 3.6e9, 31.0, 1.0, 1_000.0, 50.0)
        assertTrue(read.suspect)
        assertEquals(59_810.0 / 80.0, read.rawMean, 1e-9)
        assertTrue(read.stderr.isFinite() && read.stderr > 0.0)
        val clean = r.robust8187(10.0, 720.0, 810_000.0 + 9 * 400.0, 1.0, 1.0, 1_000.0, 50.0)
        assertFalse(clean.suspect); assertEquals(72.0, clean.mean, 1e-9)
        assertEquals(1_000.0, r.clampLabel8187(60_000.0), 0.0)
        assertEquals(300.0, r.clampLabel8187(2_000.0, r.capForKey8185("MC_GT1M")), 0.0)
        assertEquals(-100.0, r.clampLabel8187(-150.0), 0.0)
        assertTrue(r.clampLabel8187(Double.NaN).isNaN())
    }

    @Test fun everyNewLabelIsClampedAtIngestion() {
        val ids = listOf("V"); val edges = listOf(doubleArrayOf(0.5))
        val ledger = CortexLedger7885()
        ledger.grade("SHITCOIN", ids, edges, doubleArrayOf(1.0), 60_000.0, 60_000.0)
        assertEquals(1_000.0, ledger.lanes["SHITCOIN"]!!.sum, 1e-9)
        assertEquals(1_000.0, ledger.seats["V|SHITCOIN"]!!.bins[1].sum, 1e-9)
        assertEquals(1.0, ledger.lanes["SHITCOIN"]!!.runners, 0.0)
        // A realised-close ledger keeps the money it was given.
        val real = CortexLedger7885(Double.POSITIVE_INFINITY)
        real.grade("SHITCOIN", ids, edges, doubleArrayOf(1.0), 60_000.0, 60_000.0)
        assertEquals(60_000.0, real.lanes["SHITCOIN"]!!.sum, 1e-9)
        val board = CortexScoreboard7885()
        board.record("MOONSHOT", CortexScoreboard7885.Bucket.NEUTRAL, false, 60_000.0, 60_000.0)
        assertEquals(1_000.0, board.books["MOONSHOT"]!!.byBucket[neutral].sum, 1e-9)
        board.recordRealized("LIVE", "MOONSHOT", CortexScoreboard7885.Bucket.STRONG, 5_000.0)
        assertEquals(5_000.0, board.realized["LIVE|MOONSHOT"]!![strong].mean(), 1e-9)
    }

    @Test fun aRevisionRemovesTheOldLabelAsBookedAndClampsTheNewOne() {
        val board = CortexScoreboard7885()
        val stamps = board.record("EXPRESS", CortexScoreboard7885.Bucket.STRONG, false, 500.0, 500.0)!!
        // A label booked before 8187 at +5,000% (raw): the revision must take out exactly what went in.
        val st = board.books["EXPRESS"]!!.byBucket[strong]
        st.sum = 5_000.0; st.sumSq = 25_000_000.0
        board.revise8006("EXPRESS", CortexScoreboard7885.Bucket.STRONG, false, 5_000.0, 20.0, 5_000.0, 20.0, stamps)
        assertEquals(20.0, st.sum, 1e-7)
        // A revision upward is a new label: clamped.
        board.revise8006("EXPRESS", CortexScoreboard7885.Bucket.STRONG, false, 20.0, 9_000.0, 20.0, 9_000.0, stamps)
        assertEquals(1_000.0, st.sum, 1e-7)
        val ledger = CortexLedger7885()
        val ids = listOf("V"); val edges = listOf(doubleArrayOf(0.5))
        val trace = ledger.grade("CORE", ids, edges, doubleArrayOf(1.0), 40.0, 40.0)!!
        ledger.revise8006("CORE", ids, edges, doubleArrayOf(1.0), trace, 40.0, 9_000.0, 40.0, 9_000.0)
        assertEquals(1_000.0, ledger.lanes["CORE"]!!.sum, 1e-7)
    }

    @Test fun thePoisonedRestBookCountsItsWinners() {
        val board = CortexScoreboard7885()
        repeat(10) { board.record("QUALITY", CortexScoreboard7885.Bucket.NEUTRAL, false, 5.0, 5.0) }
        repeat(10) { board.record("QUALITY", CortexScoreboard7885.Bucket.STRONG, false, -5.0, -5.0) }
        val b = board.books["QUALITY"]!!
        val rest = b.rest()
        assertEquals(b.byBucket[neutral].wins + b.byBucket[strong].wins, rest.wins, 1e-9)
        assertFalse("a clean rest book is not suspect", rest.suspect8187())
    }

    @Test fun aPoisonedStrongRecordHoldsNoAuthorityAndAPoisonedRivalCannotInvert() {
        val s = poisoned()
        val n = CortexLedger7885.labelStat8187().also { st -> repeat(40) { st.add(if (it % 2 == 0) 2.0 else -2.0, false) } }
        assertEquals(0.0, CortexScoreboard7885.authorityFraction7955(s, n, false), 0.0)
        assertFalse(CortexScoreboard7885.overruleProven(s))
        // 8175: a +788.8% NEUTRAL "beat" every STRONG read; read robustly its error spans the cap, so no inversion is proven.
        val st = CortexLedger7885.labelStat8187().also { x -> repeat(20) { x.add(if (it % 2 == 0) 9.0 else 7.0, false) } }
        assertFalse(CortexScoreboard7885.invertedProven7948(st, s))
    }

    @Test fun aLaunchTapeHeatBinWithOneImplausibleRunnerReadsAtItsBound() {
        // 5.0.8175 byHeat15m h1 n37 / +992.9% / run2%: a label above +100% must be a 2x-peak runner.
        val st = LaunchTape7921.tapeStat8187()
        repeat(14) { st.add(20.0, false) }
        repeat(22) { st.add(-15.0, false) }
        st.add(36_000.0, true)
        assertTrue(st.suspect8187())
        assertEquals((1_000.0 + 14 * 100.0) / 37.0, st.mean(), 1e-9)
    }

    @Test fun anOutcomeTallyIsClampedAndReadRobustly() {
        // A pre-8187 row has no winner count: the bound is every label at the cap, flagged when the raw mean exceeds it.
        val legacy = doubleArrayOf(112.0, 112_067.2, -1.0)
        assertTrue(LanePlaybook7907.outcomeMean8187(legacy).contains("~raw+1000.6%"))
        val fresh = DoubleArray(3)
        LanePlaybook7907.addOutcome8187(fresh, 60_000.0)
        LanePlaybook7907.addOutcome8187(fresh, -10.0)
        assertEquals(2.0, fresh[0], 0.0); assertEquals(990.0, fresh[1], 1e-9); assertEquals(1.0, fresh[2], 0.0)
        assertEquals("+495.0%", LanePlaybook7907.outcomeMean8187(fresh))
        val old = doubleArrayOf(1.0, 5.0, -1.0)
        LanePlaybook7907.addOutcome8187(old, 5.0)
        assertEquals(-1.0, old[2], 0.0)  // unknown winners stay unknown
    }

    // ── grade records and walk-forward ──

    private fun decide(
        book: CortexGradeBook8144, mint: String, lane: String, atMs: Long, bucket: Int, admitted: Boolean, reason: String?,
        net: Double, cohort: String = "MC_10K_100K",
    ): CortexGradeRecord8144 {
        val episode = "$mint|$lane"
        val v = CortexLabelHygiene8144.assess8144(admitted, reason, book.lastGradedAt(episode), atMs)
        val id = "$episode|$atMs"
        val r = CortexGradeRecord8144('D', id, episode, atMs, lane, "S", "BULL", "MID", "C3_6", cohort, bucket, 4.5, admitted, v.executable, v.repeat)
        assertTrue(book.addDecision(r))
        assertTrue(book.label(id, net, net, net, true))
        return r
    }

    @Test fun gradeRecordsReadEveryLabelThroughItsCohortCap() {
        val book = CortexGradeBook8144()
        val big = decide(book, "BIG", "MOONSHOT", t0, strong, true, null, 2_000.0, cohort = "MC_GT1M")
        decide(book, "SMALL", "MOONSHOT", t0 + 1, strong, true, null, 5_000.0)
        assertEquals(2_000.0, big.observedNetPct, 0.0)  // the observed record is kept as booked
        assertEquals(300.0, big.executablePct, 0.0)     // the authority value clamped at ingestion (4x for $1M+)
        assertEquals(listOf(300.0, 1_000.0), book.executableSeries("MOONSHOT", null, strong))
        assertEquals(listOf(300.0, 1_000.0), book.observedSeries("MOONSHOT", strong))
        assertEquals(listOf(2_000.0, 5_000.0), book.observedSeriesRaw8187("MOONSHOT", strong))
        val one = CortexWalkForward8144.stat8144(book.executableSeries("MOONSHOT", null, strong))
        assertEquals(650.0, one.mean, 1e-9)
    }

    @Test fun aPoisonedStrongSeriesCannotValidate() {
        assertEquals(35, CortexWalkForward8144.MIN_SERIES_N_8187)
        val raw = List(34) { -5.0 } + listOf(200_000.0)
        val capped = raw.map { RobustLabelRead8185.clampLabel8187(it) }
        assertFalse(CortexWalkForward8144.promote8144(capped).validated)
        assertTrue(CortexWalkForward8144.stat8144(capped).mean < 30.0)
    }

    @Test fun aFullBookEvictsNonEvidenceFirstThenTheMostCrowdedCohort() {
        val book = CortexGradeBook8144(maxDecisions = 10)
        // Three executable first-of-episode STRONG decisions (evidence) ...
        repeat(3) { i -> decide(book, "S$i", "SHITCOIN", t0 + i, strong, true, null, 3.0) }
        // ... then many repeat refusals of one coin (no evidence): they cannot push the STRONG records out.
        val first = decide(book, "R", "SHITCOIN", t0 + 100, neutral, false, "SCORE_LOW", 1.0)
        assertFalse(first.repeat)
        repeat(20) { i -> assertTrue(decide(book, "R", "SHITCOIN", t0 + 200 + i, neutral, false, "SCORE_LOW", 1.0).repeat) }
        assertEquals(10, book.size())
        assertEquals(3, book.executableSeries("SHITCOIN", null, strong).size)
        // Evidence only: the crowded NEUTRAL cohort gives way before the rare STRONG one.
        repeat(12) { i -> decide(book, "N$i", "SHITCOIN", t0 + 1_000 + i, neutral, false, "SCORE_LOW", 1.0) }
        assertEquals(10, book.size())
        assertEquals(3, book.executableSeries("SHITCOIN", null, strong).size)
        assertEquals(7, book.executableSeries("SHITCOIN", null, neutral).size)
        assertTrue(book.evicted8187 > 0L)
    }

    @Test fun aLateLabelDoesNotOpenTheCoinsEpisode() {
        val book = CortexGradeBook8144()
        val first = decide(book, "M", "MOONSHOT", t0, refuse, false, "SCORE_LOW", -4.0)
        assertEquals(t0, book.lastGradedAt("M|MOONSHOT"))
        // Its first price came late: observed only — and the coin's next decision is not a REPEAT of it.
        assertTrue(book.markLateEntry8153(first.id))
        assertNull(book.lastGradedAt("M|MOONSHOT"))
        assertFalse(CortexLabelHygiene8144.repeatInEpisode8144(false, book.lastGradedAt("M|MOONSHOT"), t0 + 15L * 60_000L))
        val second = decide(book, "M", "MOONSHOT", t0 + 15L * 60_000L, refuse, false, "SCORE_LOW", -6.0)
        assertFalse(second.repeat)
        // A late voter read: observed only, no executable value, and neither resolved nor lost in its cohort.
        val third = decide(book, "Q", "QUALITY", t0, strong, true, null, 8.0)
        assertTrue(book.markLateRead8187(third.id))
        assertTrue(third.executablePct.isNaN())
        assertNull(book.lastGradedAt("Q|QUALITY"))
        assertEquals(1.0, book.resolvedShare("QUALITY", strong), 0.0)
        val counts = book.cohortCounts8187("QUALITY", strong)
        assertEquals(0, counts[0]); assertEquals(1, counts[3])
        // Both flags survive a restart.
        val copy = CortexGradeBook8144().also { it.decode(book.encode()) }
        assertTrue(copy.decision(third.id)!!.lateRead8187)
        assertTrue(copy.decision(third.id)!!.executablePct.isNaN())
    }

    @Test fun aCloseWithNoBoundReadFindsItsAdmittedDecisionRecord() {
        val book = CortexGradeBook8144()
        val admitted = decide(book, "MINT", "SHITCOIN", t0, strong, true, null, 9.0)
        admitted.mode = "LIVE"
        assertNotNull(book.admittedDecision8187("MINT", "SHITCOIN", "LIVE", t0 + 60_000L, 15L * 60_000L))
        assertNull("other mode", book.admittedDecision8187("MINT", "SHITCOIN", "PAPER", t0 + 60_000L, 15L * 60_000L))
        assertNull("decided after the open", book.admittedDecision8187("MINT", "SHITCOIN", "LIVE", t0 - 1L, 15L * 60_000L))
        assertNull("too old", book.admittedDecision8187("MINT", "SHITCOIN", "LIVE", t0 + 16L * 60_000L, 15L * 60_000L))
        val refused = decide(book, "OTHER", "SHITCOIN", t0, strong, false, "SCORE_LOW", 9.0)
        assertFalse(refused.admitted)
        assertNull(book.admittedDecision8187("OTHER", "SHITCOIN", "LIVE", t0 + 60_000L, 15L * 60_000L))
    }

    // ── 2/3. capture -> grade, binding ──

    @Test fun pureBindingAndTimingRules() {
        assertTrue(Cortex7885.readBindable8187(t0, t0 + 60_000L))
        assertFalse("a read decided after the open", Cortex7885.readBindable8187(t0 + 1L, t0))
        assertFalse(Cortex7885.readBindable8187(t0, t0 + 16L * 60_000L))
        assertEquals(0.75, Cortex7885.bindingRate8187(3L, 1L), 1e-12)
        assertTrue(Cortex7885.bindingRate8187(0L, 0L).isNaN())
        assertTrue(Cortex7885.gradedPerHour8187() >= 0.0)
        assertTrue(CortexTiming7900.gradeable8187(t0, t0 + 8L * 60_000L))
        assertFalse(CortexTiming7900.gradeable8187(t0, t0 + 10L * 60_000L))
        assertFalse(CortexTiming7900.gradeable8187(t0 + 1L, t0))
        assertTrue(ForwardReturnLabeler7731.firstMarkDue8187(false, 0L, 20_000L))
        assertFalse("already priced", ForwardReturnLabeler7731.firstMarkDue8187(false, t0, 20_000L))
        assertFalse("past the re-base window", ForwardReturnLabeler7731.firstMarkDue8187(false, 0L, 61_000L))
        assertFalse("already re-based", ForwardReturnLabeler7731.firstMarkDue8187(true, 0L, 20_000L))
        assertEquals(1.5, CortexExit7897.samplePx8187(1.5, t0, t0 + 1_000L)!!, 0.0)
        assertNull("registry-priced position (lastPriceUpdate 0)", CortexExit7897.samplePx8187(1.5, 0L, t0))
        assertNull("stale", CortexExit7897.samplePx8187(1.5, t0, t0 + 10L * 60_000L))
    }

    @Test fun theCapturePathFilesEveryDecisionAndTheCloseFindsItsVerdict() {
        val cx = src("engine/cortex/Cortex7885.kt")
        // Ingestion: every book in onLabel takes the clamped label of the decision's cohort.
        val on = cx.substringAfter("fun onLabel(mint: String, labelLane: String").substringBefore("// ── V5.0.8144 — grade records")
        assertTrue(on.contains("RobustLabelRead8185.clampLabel8187(netPct, cap8187)"))
        assertTrue(on.contains("ledger.grade(p.a.lane, p.a.ids, p.a.edges, p.a.raws, net8187, gross8187"))
        assertTrue(on.contains("board.record(p.a.lane, p.a.bucket, p.legacyAdmitted, net8187, gross8187)"))
        assertTrue(on.contains("gradeBook8144.markLateRead8187("))
        // Lost labels are filed at once; restores past the labeler's reach are not "pending".
        assertTrue(cx.contains("fun onLabelLost8187(mint: String, labelLane: String, reason: String)"))
        assertTrue(cx.contains("if (!persistablePending8144(at, nowMs)) { inc(\"PENDING_RESTORE_PAST_LABEL_8187\"); continue }"))
        val fl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(fl.contains("Cortex7885.onLabelLost8187(o.mint, o.lane, \"GAP\")"))
        assertTrue(fl.contains("Cortex7885.onLabelLost8187(o.mint, o.lane, \"BASIS\")"))
        assertTrue(fl.contains("if (firstMarkDue8187(o.rebased8076, o.lastPxAtMs, age))"))
        // Binding: deferred binds, decision-time staleness, and recovery from the decision record before an unbound filing.
        assertTrue(cx.contains("deferBind8187(positionId, mint, normalized, m, laneKey8082, openedAtMs)"))
        assertTrue(cx.contains("retryDeferredBinds8187(key8187, a)"))
        val close = cx.substringAfter("fun onCanonicalClose(").substringBefore("// ── persistence ──")
        assertTrue(close.indexOf("recoverRead8187(env, closeKey7931, mode, sealed8144)") in 0 until close.indexOf("fileUnboundClose8153(env, mode, sealed8144, snapTag8144)"))
        assertTrue(close.contains("REALIZED_REPLAY_ALREADY_FILED_8187"))
        assertTrue(cx.contains("if (admitted && !late8169) noteEntryRead("))
        // Timing samples survive a restart; the exit cortex prices registry-priced positions.
        val tm = src("engine/cortex/CortexTiming7900.kt")
        assertTrue(tm.contains(".put(\"pending8187\", encodePending8187(System.currentTimeMillis()))"))
        assertTrue(tm.contains("restorePending8187(o.optJSONObject(\"pending8187\"), System.currentTimeMillis())"))
        assertTrue(src("engine/cortex/CortexExit7897.kt").contains("ForwardReturnLabeler7731.markFor7948(ts.mint, { null }, nowMs)"))
    }

    // ── 4/5/6. graduation, economy, status ──

    @Test fun theGatesAreCountedTheEconomyIsPerSessionAndTheScreensShowRobustBesideRaw() {
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("inc(\"AUTHORITY_BLOCKED_8187_\${lane}_\${blockCode8187(lane)}\")"))
        assertTrue(cx.contains("\"      graduation (§8187): \${bindingLine8187()} authority=\${blockLine8187()}\\n\""))
        assertTrue(cx.contains("(day credits / session decisions) \${economyLine8187()}"))
        assertTrue(cx.contains("private fun fmtStat(s: CortexLedger7885.Stat): String = CortexLedger7885.fmt8187(s)"))
        assertTrue(cx.contains("enrichMemo8187[voterId]"))
        assertTrue(src("engine/LoadTelemetry8169.kt").contains("fun sessionCredits8187(): Double"))
        assertTrue(src("engine/TokenSafetyChecker.kt").contains("ageMinutes <= BundleDetector.LAUNCH_VISIBLE_MINUTES_7881 && hard.isEmpty() &&"))
        // Every Cortex label book is a robust label book.
        assertTrue(src("engine/cortex/CortexScoreboard7885.kt").contains("val byBucket = Array(Bucket.values().size) { CortexLedger7885.labelStat8187() }"))
        assertTrue(src("engine/cortex/CortexScoreboard7885.kt").contains("Array(Bucket.values().size) { CortexLedger7885.realisedStat8187() }"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains(".stats.getOrPut(setup) { CortexLedger7885.labelStat8187() }"))
        assertTrue(src("engine/cortex/CortexExit7897.kt").contains("Array(Bucket.values().size) { CortexLedger7885.labelStat8187() }"))
        assertTrue(src("engine/market/LaunchTape7921.kt").contains("binBook.getOrPut(c.bin) { tapeStat8187() }"))
        assertTrue(cx.contains("realLedgers.getOrPut(mode) { CortexLedger7885(Double.POSITIVE_INFINITY) }"))
    }

    // ── 8186 additions: an accountable capture queue; size-ups only on validated after-cost evidence ──

    @Test fun theCaptureQueueCoalescesPerKeyAndRefusesTheNewestWhenFull() {
        assertTrue(Cortex7885.captureAdmits8187(0, false))
        assertTrue(Cortex7885.captureAdmits8187(319, false))
        assertFalse("a refusal is the newest key, never a queued decision", Cortex7885.captureAdmits8187(320, false))
        assertTrue("an admitted decision keeps headroom", Cortex7885.captureAdmits8187(320, true))
        assertFalse(Cortex7885.captureAdmits8187(416, true))
        val cx = src("engine/cortex/Cortex7885.kt")
        val pool = cx.substringAfter("private val capturePool8171").substringBefore("// ── V5.0.8187 §ACCOUNTABLE_CAPTURE_QUEUE")
        assertFalse("no oldest-job eviction", pool.contains("ex.queue.poll()"))
        assertTrue(cx.contains("if (captureLatest8187.put(key, job) != null) { inc(\"CAPTURE_COALESCED_SAME_KEY_8187\"); return }"))
        assertTrue(cx.contains("inc(if (admitted) \"CAPTURE_DROP_FULL_ADMITTED_8187\" else \"CAPTURE_DROP_FULL_NEWEST_8187\")"))
    }

    @Test fun forwardLabelsAloneNeverRaiseSize() {
        // 8186 SHITCOIN: realised STRONG n2 -27.4% — no proof; 20+ positive closes with mean - SE > 0 and a growing wallet — proof.
        assertNull(Cortex7885.realisedProof8187(listOf(-30.0, -24.8)))
        assertNull("too few closes", Cortex7885.realisedProof8187(List(10) { 8.0 }))
        val proven = Cortex7885.realisedProof8187(List(24) { if (it % 2 == 0) 14.0 else 2.0 })
        assertNotNull(proven)
        assertEquals(8.0, proven!!.mean, 1e-9)
        // A realised record carried by one implausible close is read through the cap (a robust mean).
        val spiky = Cortex7885.realisedProof8187(List(23) { -1.0 } + listOf(50_000.0))
        assertTrue(spiky == null || spiky.mean < (1_000.0 - 23.0) / 24.0 + 1e-9)
        // Walk-forward: only a VALIDATED out-of-sample result is proof.
        val inSample = CortexWalkForward8144.promote8144(List(24) { if (it % 2 == 0) 18.0 else 22.0 } + List(16) { if (it % 2 == 0) -8.0 else -12.0 })
        assertNull(Cortex7885.walkForwardProof8187(inSample))
        val cx = src("engine/cortex/Cortex7885.kt")
        val stake = cx.substringAfter("private fun provenEvidenceStake7948(").substringBefore("class Proof8187(")
        assertTrue(stake.contains("?: run { inc(\"SIZE_UP_REFUSED_UNVALIDATED_8187_\$lane\"); return emptyList() }"))
        assertTrue(stake.contains("minOf(setupStake, proofStake) to minOf(setupFraction, proof.fraction)"))
        val conviction = cx.substringAfter("private fun cortexStake7893(").substringBefore("private fun provenEvidenceStake7948(")
        assertTrue(conviction.contains("validatedEvidence8187(a.lane, null) ?: run {"))
    }
}
