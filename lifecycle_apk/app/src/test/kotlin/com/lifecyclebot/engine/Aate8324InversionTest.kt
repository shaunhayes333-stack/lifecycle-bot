package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexTailEv8324
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Record8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Verdict8323
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8324 — "these look inverted again. check all": column wiring end to end, the runner-lane tail EV bucket, a STRONG
 * bucket that fires when the evidence supports it, anti-runner voters demoted, and admission's loss tests read tail-aware.
 */
class Aate8324InversionTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val refuse = CortexScoreboard7885.Bucket.REFUSE.ordinal
    private val neutral = CortexScoreboard7885.Bucket.NEUTRAL.ordinal
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal

    private fun rec(book: CortexGradeBook8144, id: String, lane: String, at: Long, bucket: Int, take: Boolean, net: Double, gross: Double, pred: Double = 0.0) {
        assertTrue(book.addDecision(CortexGradeRecord8144('D', id, "m$id|$lane", at, lane, "S", "-", "T", "C3_6", "MC_10K_100K", bucket, pred, take, true, false)))
        assertTrue(book.label(id, net, gross, net, true))
    }

    // ── 1. column wiring ──

    @Test fun everyPanelColumnReadsItsOwnPopulationAndStatistic() {
        val book = CortexGradeBook8144()
        rec(book, "s1", "CORE", 1L, strong, take = false, net = 20.0, gross = 25.0)
        rec(book, "s2", "CORE", 2L, strong, take = true, net = 40.0, gross = 45.0)
        rec(book, "r1", "CORE", 3L, refuse, take = false, net = -30.0, gross = -25.0)
        rec(book, "n1", "CORE", 4L, neutral, take = false, net = -10.0, gross = -5.0)
        val v = LearningEntryTruth8153.verdictRow8153(book, "CORE")
        // Cortex columns: STRONG = bucket 2, REFUSE = bucket 0, NEUTRAL = bucket 1 — net MEANS of the objective.
        assertEquals(2, v.strongN); assertEquals(30.0, v.strongMean, 1e-9)
        assertEquals(1, v.refuseN); assertEquals(-30.0, v.refuseMean, 1e-9)
        assertEquals(1, v.neutralN); assertEquals(-10.0, v.neutralMean, 1e-9)
        assertEquals(35.0, v.strongGrossMedian8198, 1e-9)
        // AATE columns: TAKE = admitted, REFUSE = refused, whatever the Cortex bucket.
        val d = LearningEntryTruth8153.decisionRow8269(book, "CORE")
        assertEquals(1, d.takeN); assertEquals(3, d.refuseN)
        assertEquals(40.0, d.takeNetMean, 1e-9)
        assertEquals((20.0 - 30.0 - 10.0) / 3.0, d.refuseNetMean, 1e-9)
        // Enum ordinals the books index by.
        assertEquals(0, refuse); assertEquals(1, neutral); assertEquals(2, strong)
        val ui = src("ui/LearningCounterActivity.kt")
        assertTrue(ui.contains("\"\${pct8069(l.aateTakeNetMean8269)} / \${pct8069(l.aateRefuseNetMean8269)}\""))
        assertTrue(ui.contains("Cortex predicted STRONG n\${l.strongN} net mean \${pct8069(l.strongMean)} vs REFUSE n\${l.refuseN} \${pct8069(l.refuseMean)}"))
        assertTrue(ui.contains("l.inversion8324"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("UiLane8069(lane, b.byBucket.sumOf { it.n }.toInt(), v.strongN, v.strongMean, v.neutralN, v.neutralMean,"))
        assertTrue(cx.contains("aateTakeNetMean8269 = d8269.takeNetMean, aateRefuseNetMean8269 = d8269.refuseNetMean,"))
    }

    @Test fun theGrossMedianFollowsTheObjectivesHorizonNotTheLatestObservedMark() {
        val book = CortexGradeBook8144()
        book.runnerLane8322 = { it == "MOONSHOT" }
        // first label +10 net / +12 gross; an in-hold revision +200 / +202; then an observed-only mark past the hold.
        assertTrue(book.addDecision(CortexGradeRecord8144('D', "a", "ma|MOONSHOT", 1L, "MOONSHOT", "S", "-", "T", "C", "MC_10K_100K", strong, 1.0, false, true, false)))
        assertTrue(book.label("a", 10.0, 12.0, 10.0, true))
        assertTrue(book.label("a", 200.0, 202.0, 200.0, true, availableAtMs = 3_600_000L))
        assertTrue(book.label("a", -60.0, -58.0, -60.0, false))
        val r = book.decision("a")!!
        assertEquals(202.0, book.objectiveGross8324(r), 1e-9)
        assertEquals(202.0, r.revisedGross8324, 1e-9)
        assertEquals(listOf(202.0), book.executableGrossSeries8198("MOONSHOT", null, strong))
        assertEquals(listOf(200.0), book.executableSeries("MOONSHOT", null, strong))
        // the new fields survive a restart
        r.tailEv8324 = 17.5
        val back = CortexGradeBook8144().also { it.decode(book.encode()); it.runnerLane8322 = { l -> l == "MOONSHOT" } }
        val b = back.decision("a")!!
        assertEquals(12.0, b.firstGross8324, 1e-3); assertEquals(202.0, b.revisedGross8324, 1e-3); assertEquals(17.5, b.tailEv8324, 1e-3)
        val row: CortexGradeBook8144.RankRow8324 = back.rankRows8324("MOONSHOT").single()
        assertEquals(200.0, row.objective, 1e-3); assertEquals(17.5, row.tailEv, 1e-3)
    }

    // ── 2. runner-lane bucket on a tail pond ──

    /** 95 coins at -20%, 5 runners at +760% (the 8321 pond shape): mean +19%, median -20%. */
    private fun tailPond(): CortexTailEv8324.Pond8324 {
        val xs = List(95) { -20.0 } + List(5) { 760.0 }
        return CortexTailEv8324.pond8324(xs, List(xs.size) { CortexLedger7885.LABEL_CAP_8187 })!!
    }

    @Test fun aTailPondIsReadAsAMixtureNotAsItsTypicalCoin() {
        val p = tailPond()
        assertEquals(100, p.n); assertEquals(5, p.runners)
        assertEquals(0.05, p.pBook, 1e-12)
        assertTrue(p.plausible)
        assertEquals(760.0, p.eRun, 1e-9); assertEquals(-20.0, p.eNon, 1e-9)
        assertEquals(0.05 * 760.0 + 0.95 * -20.0, p.mean, 1e-9)    // +19.0
        assertEquals(780.0, p.premium, 1e-9)
        assertTrue(p.evidence)
        assertEquals(CortexTailEv8324.MIN_RECORDS_8324, 20)
        assertEquals(CortexLedger7885.RUNNER_GROSS_PCT, CortexTailEv8324.RUNNER_PCT_8324, 0.0)
        // a coin at the lane's baseline runner rate reads the pond mean; the 8321 bucket filed it REFUSE (edge ~ -20)
        val ev = CortexTailEv8324.ev8324(p, 0.08, 0.08)
        assertEquals(19.0, ev, 1e-9)
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(-21.0, 0.08, true))
        assertNotEquals(CortexScoreboard7885.Bucket.REFUSE, CortexTailEv8324.runnerBucket8324(-21.0, ev, p, CortexTailEv8324.strongBar8324(emptyList(), p.mean)))
    }

    @Test fun aRunnerLaneNeverRefusesWithoutEvidenceAndRefusesAClearlyNegativeEv() {
        // no pond / thin pond -> never REFUSE
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexTailEv8324.runnerBucket8324(-40.0, -40.0, null, 3.0))
        val thin = CortexTailEv8324.pond8324(List(10) { -30.0 }, List(10) { 1_000.0 })!!
        assertFalse(thin.evidence)
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexTailEv8324.runnerBucket8324(-40.0, thin.mean, thin, 3.0))
        // a pond that truly loses (no runner in 60) refuses on its evidence
        val dead = CortexTailEv8324.pond8324(List(60) { -25.0 + (it % 3) }, List(60) { 1_000.0 })!!
        assertTrue(dead.evidence)
        val ev = CortexTailEv8324.ev8324(dead, 0.0, 0.0)
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexTailEv8324.runnerBucket8324(-30.0, ev, dead, 3.0))
    }

    @Test fun anImplausibleTailIsReadAtItsCapNotFabricatedNorDropped() {
        // 3 labels at +60,000% (the 8175 basis-error shape): sum per runner far over 3 x cap
        val xs = List(57) { -20.0 } + List(3) { 60_000.0 }
        val p = CortexTailEv8324.pond8324(xs, List(xs.size) { 1_000.0 })!!
        assertFalse(p.plausible)
        assertEquals(1_000.0, p.eRun, 1e-9)
    }

    // ── 3. STRONG fires when the evidence supports it ──

    @Test fun strongFiresForACoinWhoseRunnerOddsBeatTheLane() {
        val p = tailPond()
        val bar = CortexTailEv8324.strongBar8324(emptyList(), p.mean)
        assertEquals(p.mean + CortexScoreboard7885.PROOF_MARGIN_PCT, bar, 1e-9)
        // voters lift this coin's runner rate 3 points over the lane baseline: EV = 0.08 x 760 + 0.92 x -20 = +42.4
        val ev = CortexTailEv8324.ev8324(p, 0.11, 0.08)
        assertEquals(0.08 * 760.0 + 0.92 * -20.0, ev, 1e-9)
        assertEquals(CortexScoreboard7885.Bucket.STRONG, CortexTailEv8324.runnerBucket8324(-15.0, ev, p, bar))
        // once 50+ decision-time EVs are known the bar follows the lane's own top fifth
        val window = List(100) { it.toDouble() }
        assertEquals(CortexTailEv8324.MIN_WINDOW_8324, 50)
        assertEquals(CortexTailEv8324.STRONG_QUANTILE_8324, 0.80, 0.0)
        assertEquals(CortexTailEv8324.quantile8324(window, 0.8), CortexTailEv8324.strongBar8324(window, p.mean), 1e-9)
        assertEquals(79.2, CortexTailEv8324.quantile8324(window, 0.8), 1e-9)
    }

    @Test fun theCortexWiresTheTailBucketAndTheRankingLine() {
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("val bucket = if (runner) runnerBucketNow8324(lane, calibrated, ev8324) else CortexScoreboard7885.bucketOf(calibrated, fused.runnerRate, runner)"))
        assertTrue(cx.contains("try { refreshPonds8324(lanes) }"))
        assertTrue(cx.contains("ranking (§8324 does the Cortex rank-order forward returns?): \${inversionLine8324()}"))
        assertTrue(cx.contains("inversion8324["))
        assertTrue(cx.contains("it.tailEv8324 = p.a.tailEv8324"))
        // a revision whose voter trace aged out still revises its grade record; past-horizon traces go first
        assertTrue(cx.contains("val g = graded8006[key] ?: run { reviseGradeOnly8324(key, newNet, newGross, lat8144); return }"))
        assertTrue(cx.contains("if (graded8006.size >= MAX_GRADED_8006) evictPastHorizon8324(System.currentTimeMillis())"))
    }

    @Test fun rankIcAndQuintilesMeasureTheOrdering() {
        val pred = (1..20).map { it.toDouble() }
        val right = pred.map { it * 2.0 }
        val wrong = pred.map { -it }
        assertEquals(1.0, CortexTailEv8324.rankIc8324(pred, right), 1e-9)
        assertEquals(-1.0, CortexTailEv8324.rankIc8324(pred, wrong), 1e-9)
        val q = CortexTailEv8324.quintiles8324(pred, wrong)
        assertTrue(q[0] < q[1])
        assertEquals("INVERTED", CortexTailEv8324.verdict8324(-1.0, q[0], q[1]))
        val q2 = CortexTailEv8324.quintiles8324(pred, right)
        assertEquals("RANKS", CortexTailEv8324.verdict8324(1.0, q2[0], q2[1]))
        assertEquals("THIN", CortexTailEv8324.verdict8324(Double.NaN, Double.NaN, Double.NaN))
        assertTrue(CortexTailEv8324.rankIc8324(listOf(1.0, 2.0), listOf(1.0, 2.0)).isNaN())
    }

    // ── 4. a negative-skill (anti-runner) voter is demoted ──

    @Test fun anAntiRunnerSeatLosesItsAuthorityOnARunnerLaneOnly() {
        // above the lane mean: 40 decisions, 0 runners; below: 40 decisions, 8 runners
        assertEquals(0.0, CortexTailEv8324.runnerDirection8324(40.0, 0.0, 40.0, 8.0), 0.0)
        assertEquals(1.0, CortexTailEv8324.runnerDirection8324(40.0, 8.0, 40.0, 0.0), 0.0)
        assertEquals(1.0, CortexTailEv8324.runnerDirection8324(10.0, 0.0, 40.0, 8.0), 0.0)   // thin side: not judged
        val led = CortexLedger7885()
        val seat = led.seat("CHASE_VOTER", "SHITCOIN", 3)
        repeat(40) { seat.above.add(-20.0, false) }
        repeat(32) { seat.below.add(-20.0, false) }
        repeat(8) { seat.below.add(700.0, true) }
        assertEquals("a bare ledger seats as before", 1.0, led.runnerFactor8324(seat, "SHITCOIN"), 0.0)
        led.runnerLane8324 = { it == "SHITCOIN" }
        assertEquals(0.0, led.runnerFactor8324(seat, "SHITCOIN"), 0.0)
        assertEquals(1.0, led.runnerFactor8324(seat, "TREASURY"), 0.0)
        assertTrue(src("engine/cortex/CortexLedger7885.kt").contains("cleanAuthority8007(it, seats.size) * runnerFactor8324(it, lane)"))
        assertTrue(led.cleanLine8007().contains("antiRunnerDemoted8324="))
    }

    // ── 5. admission reads the tail-aware statistic ──

    @Test fun missedRunnerCreditIsArithmeticNotHope() {
        // 12 labels without a runner on a 5% / +780-point lane: the Laplace bound (1/13) is above the lane's 5%, so +39 points
        assertEquals(0.05 * 780.0, CortexTailEv8324.missedRunnerPts8324(12.0, 0.0, 0.05, 780.0), 1e-9)
        // 100 such labels: (1/101) x 780 = +7.7 points; a record with its runners: nothing
        assertEquals(780.0 / 101.0, CortexTailEv8324.missedRunnerPts8324(100.0, 0.0, 0.05, 780.0), 1e-9)
        assertEquals(0.0, CortexTailEv8324.missedRunnerPts8324(40.0, 0.05, 0.05, 780.0), 0.0)
        // no measured premium (fewer than 3 plausible runners in the lane): nothing
        assertEquals(0.0, CortexTailEv8324.missedRunnerPts8324(12.0, 0.0, 0.05, Double.NaN), 0.0)
    }

    private fun ev(id: String, n: Int, mean: Double, se: Double, run: Double = 0.0) =
        Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se, run)

    private fun cand(sel: Evidence8136, rate: Double, prem: Double) = Candidate8136(
        mint = "MINT8324pump", lane = "SHITCOIN", selectedSetup = sel.id, regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 5_000L, expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 15.0,
        expectedGrossBySetup = mapOf(sel.id to 30.0), setups = listOf(sel), genericScore = 40.0, genericFloor = 30.0,
        tailLaneRate8324 = rate, tailPremium8324 = prem,
    )

    @Test fun aThinRunnerlessRecordOnARunnerLaneIsNotAClearLoss() {
        // 14 labels at -20% +/- 2, no runner: mean + 2 SE < 0, so 8136 refused it SETUP_RECORD_LOSING
        val thin = ev("LAUNCH", 14, -20.0, 2.0, run = 0.0)
        val before = CandidateAdmission8136.decide8136(cand(thin, Double.NaN, Double.NaN))
        assertEquals("SETUP_RECORD_LOSING_8136", before.reason)
        // on the tail pond its missing runners are worth +39 points: no longer a clear loss (the generic prior decides)
        val after = CandidateAdmission8136.decide8136(cand(thin, 0.05, 780.0))
        assertNotEquals("SETUP_RECORD_LOSING_8136", after.reason)
        assertEquals(Terminal8136.ADMIT, after.terminal)
        // a large runnerless record is still a proven loss
        val big = ev("LAUNCH", 400, -20.0, 1.0, run = 0.0)
        assertEquals("SETUP_RECORD_LOSING_8136", CandidateAdmission8136.decide8136(cand(big, 0.05, 780.0)).reason)
        // unknown runner share: unchanged
        val unknown = ev("LAUNCH", 14, -20.0, 2.0, run = Double.NaN)
        assertEquals(unknown, CandidateAdmission8136.tailCredited8324(unknown, 0.05, 780.0))
    }

    @Test fun theCheatSheetAvoidsOnlyAClearTailAwareLoss() {
        val r = Record8323("LAUNCH", 0.5, 14.0, -20.0, 2.0, 0.0)
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.judge8323(r, Verdict8323.NEUTRAL))
        assertEquals(Verdict8323.NEUTRAL, LaneCheatSheet8323.judge8323(r, Verdict8323.NEUTRAL, 0.0, 0.05, 780.0))
        // and an avoided setup leaves AVOID on the same tail-aware net
        assertEquals(Verdict8323.NEUTRAL, LaneCheatSheet8323.judge8323(r, Verdict8323.AVOID, 0.0, 0.05, 780.0))
        val big = Record8323("LAUNCH", 0.5, 300.0, -20.0, 1.0, 0.0)
        assertEquals(Verdict8323.AVOID, LaneCheatSheet8323.judge8323(big, Verdict8323.NEUTRAL, 0.0, 0.05, 780.0))
        val sheet = src("engine/cortex/LaneCheatSheet8323.kt")
        assertTrue(sheet.contains("applyRecords8323(lane, recordsFor8323(lane, live), nowMs, pond8324 = pond8324)"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("tailPremium8324 = pond8324?.premium ?: Double.NaN,"))
    }

    @Test fun theLiveSetupAndTimingLossDoorsReadTheTailCredit() {
        val st = CortexLedger7885.labelStat8187().also { x -> repeat(14) { x.add(-20.0, false) } }
        // a scalp lane never gets tail credit; a runner lane without a measured pond neither
        assertEquals(0.0, LanePlaybook7907.tailCredit8324("TREASURY", st), 0.0)
        assertEquals(0.0, LanePlaybook7907.tailCredit8324("UNPONDED_RUNNER_MOONSHOT_8324", st), 0.0)
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("setupRecordNegative8094(st.n, st.mean() + tailCredit8324(lane, st), st.variance())) setup else null"))
        assertTrue(pb.contains("setupRecordNegative8094(st.n, st.mean() + tailCredit8324(lane, st), st.variance())) \"\$setup@\$band\" else null"))
    }
}
