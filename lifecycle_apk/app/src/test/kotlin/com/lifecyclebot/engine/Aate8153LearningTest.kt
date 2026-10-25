package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexOutcomeRoute8144
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexWalkForward8144
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153.SetupSource8153
import com.lifecyclebot.engine.cortex.LearningExitTruth8153
import com.lifecyclebot.engine.cortex.LearningObjective8153
import com.lifecyclebot.engine.cortex.LearningPolicyCompare8153
import com.lifecyclebot.engine.cortex.LearningPolicyCompare8153.ExitPolicy8153
import com.lifecyclebot.engine.cortex.LearningPolicyCompare8153.Verdict8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8153 — Cortex, realised learning and strategy selection aligned with banked profit: forward / executable / realised
 * kept apart on the device path; late-first-price labels never executable; exit learning from actual partial proceeds;
 * the displayed verdict stats read the tradeable book; a relabel never misroutes a trade's outcome; the objective
 * penalises a policy that cuts right-tail winners.
 */
class Aate8153LearningTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 2_000_000_000_000L
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
    private val refuse = CortexScoreboard7885.Bucket.REFUSE.ordinal

    /** Replays Cortex7885.recordLabel8144 (+ the 8153 late-first-price flag) exactly as onLabel runs it on device. */
    private fun decide(
        book: CortexGradeBook8144, mint: String, lane: String, atMs: Long, bucket: Int, admitted: Boolean, reason: String?,
        net: Double, late: Boolean = false, setup: String = "S",
    ): CortexGradeRecord8144 {
        val episode = "$mint|$lane"
        val v = CortexLabelHygiene8144.assess8144(admitted, reason, book.lastGradedAt(episode), atMs)
        val id = "$episode|$atMs"
        val r = CortexGradeRecord8144('D', id, episode, atMs, lane, setup, "BULL", "MID", "C3_6", "MC_10K_100K", bucket, 4.5, admitted, v.executable, v.repeat)
        assertTrue(book.addDecision(r))
        assertTrue(book.label(id, net, net, CortexLabelHygiene8144.executablePct8144(net, true), true))
        if (late) assertTrue(book.markLateEntry8153(id))
        return r
    }

    // ── 1. forward vs executable vs realised, device-path inputs ──

    @Test fun forwardExecutableAndRealisedStaySeparateOnDevicePathInputs() {
        val book = CortexGradeBook8144()
        val lane = "MOONSHOT"
        val bought = decide(book, "B1", lane, t0, strong, true, null, 12.0)
        assertEquals(12.0, bought.executablePct, 1e-9)
        // A refused coin first priced off-watch at its 5-minute horizon: the label kept the decision price.
        val late = decide(book, "R1", lane, t0 + 1, refuse, false, "EDGE_GATE_SOFT_SCORE", 200.0, late = true)
        assertEquals(200.0, late.observedNetPct, 1e-9)
        assertTrue(late.executablePct.isNaN())
        assertTrue(late.lateEntry8153)
        // A later revision inside the hold horizon moves the observed record and never restores an executable value.
        assertTrue(book.label(late.id, 310.0, 310.0, 310.0, true))
        assertEquals(310.0, late.observedNetPct, 1e-9)
        assertTrue(late.executablePct.isNaN())
        assertTrue(book.executableSeries(lane, null, refuse).isEmpty())
        assertEquals(listOf(310.0), book.observedSeries(lane, refuse))
        // A late-priced label resolves nothing: the REFUSE cohort reads unresolved, so it neither earns nor blocks authority.
        assertEquals(0.0, book.resolvedShare(lane, refuse), 0.0)
        assertEquals(1.0, book.resolvedShare(lane, strong), 0.0)
        // The trade's realised close lives on its trade record; no decision record ever receives it.
        val (trade, owner) = LearningEntryTruth8153.unboundTrade8153("pos-1", "B1", "LIVE", t0, lane, "LIVE", "B1", lane, "S", null)!!
        assertTrue(book.openTrade(trade))
        assertNotNull(book.closeTrade("pos-1", owner, -5.0))
        assertEquals(-5.0, trade.realisedPct, 1e-9)
        assertTrue(bought.realisedPct.isNaN() && late.realisedPct.isNaN())
        assertTrue(trade.observedNetPct.isNaN() && trade.executablePct.isNaN())
        // The flag survives a restart; a 24-field (8146) row still reads, as on time.
        val copy = CortexGradeBook8144().also { it.decode(book.encode()) }
        assertTrue(copy.decision(late.id)!!.lateEntry8153)
        assertTrue(copy.decision(late.id)!!.executablePct.isNaN())
        assertEquals(0.0, copy.resolvedShare(lane, refuse), 0.0)
        val single = CortexGradeBook8144().also { decide(it, "B2", lane, t0, strong, true, null, 7.0) }
        val legacy = CortexGradeBook8144().also { it.decode(single.encode().substringBeforeLast('\u001F')) }
        assertEquals(1, legacy.size())
        assertEquals(listOf(7.0), legacy.executableSeries(lane, null, strong))
    }

    // ── 2. late first price ──

    @Test fun lateFirstPriceIsFlaggedAndConsumedByTheCortex() {
        // Re-base window 5-60 s: a first mark at 45 s re-bases; one at 5 minutes (off-watch batch) is late.
        assertFalse(LearningEntryTruth8153.lateFirstPrice8153(45_000L, 60_000L))
        assertFalse(LearningEntryTruth8153.lateFirstPrice8153(60_000L, 60_000L))
        assertTrue(LearningEntryTruth8153.lateFirstPrice8153(61_000L, 60_000L))
        assertTrue(LearningEntryTruth8153.lateFirstPrice8153(5L * 60_000L, 60_000L))
        assertFalse(LearningEntryTruth8153.lateFirstPrice8153(5L * 60_000L, 0L))
        // Wiring: the labeler flags it where the re-base window is decided; the Cortex consumes it at the 5-minute label.
        val fl = src("engine/truth/ForwardReturnLabeler7731.kt")
        val rebase = fl.substringAfter("o.rebased8076 = true").substringBefore("o.basis8386 = o.basis8386.rebase(mark8386)")
        assertTrue(rebase.contains("LearningEntryTruth8153.lateFirstPrice8153(age, REBASE_MAX_MS_8076)) noteLateFirstPrice8153(o.mint, o.lane, o.atMs, nowMs)"))
        assertTrue(rebase.indexOf("noteLateFirstPrice8153") < rebase.indexOf("if (mark8386.observedAtMs - o.atMs <= REBASE_MAX_MS_8076"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("ForwardReturnLabeler7731.lateFirstPrice8153(mint, labelLane, atMs)"))
        assertTrue(cx.contains("gradeBook8144.markLateEntry8153(\"\$key|\$atMs\")"))
        val on = cx.substringAfter("fun onLabel(mint: String, labelLane: String").substringBefore("// ── V5.0.8144 — grade records")
        // superseded by V5.0.8187: the playbook takes the label clamped to its cohort's cap (net8187 / gross8187 / fam8187).
        // superseded by V5.0.8322: a flat five-minute mark is observed only too.
        assertTrue(on.indexOf("if (!hyg8144.authority || late8153 || lateRead8169 || flat) {") in 0 until on.indexOf(
            "LanePlaybook7907.onLabel(mint, labelLane, net8187, gross8187, fam8187, p.setup7961, decisionAtMs8372 = p.atMs)"))
        // A hygiene-excluded decision's revisions move its own observed record, not the older graded decision's books.
        // V5.0.8372 resolves the immutable episode directly; it no longer relies on a lane's latest observed-only marker.
        val rv = cx.substringAfter("fun reviseLabel8006(").substringBefore("private fun reviseModelsLocked8372(")
        assertTrue(rv.contains("val id = \"\$key|\$decisionAtMs8371\""))
        val lookup = rv.indexOf("val record = gradeBook8144.decision(id)")
        val guard = rv.indexOf("if (record?.lateEntry8153 == true || record?.lateRead8187 == true ||")
        val observed = rv.indexOf("gradeBook8144.label(id, newNet, newGross, Double.NaN, false)")
        val stop = rv.indexOf("return", observed.coerceAtLeast(0))
        val trace = rv.indexOf("val g = gradedTrace8372(id)")
        val mutate = rv.indexOf("reviseModelsLocked8372(id, g, newNet, newGross, witness8324)")
        assertTrue(lookup >= 0 && lookup < guard && guard < observed && observed < stop && stop < trace && trace < mutate)
    }

    // ── 3. exit learning from actual partial proceeds ──

    @Test fun exitLearningReadsActualPartialProceedsAndPostExitMovement() {
        // Canonical legs (out of order): half sold at 0.0018, the rest at 0.0012; 0.1 SOL of confirmed fallback proceeds.
        val legs = listOf(
            LearningExitTruth8153.SellLeg8153(t0 + 2, 500.0, 500.0, 0.6, 0.5, 0.0, 0.0012),
            LearningExitTruth8153.SellLeg8153(t0 + 1, 500.0, 1_000.0, 0.9, 0.5, 0.5, 0.0018),
        )
        val (cost, slices) = LearningExitTruth8153.fromLegs8153(legs, 0.1)!!
        assertEquals(1.0, cost, 1e-12)
        assertEquals(listOf(0.5, 0.5, 0.0), slices.map { it.soldFraction })
        // One exit horizon later the token trades at 0.0024.
        val g = LearningExitTruth8153.grade8153(cost, slices, 0.0, 0.0024)!!
        assertEquals(1.6, g.proceedsSol, 1e-12)
        assertEquals(60.0, g.bankedPct, 1e-9)
        assertEquals(60.0, g.totalPct, 1e-9)
        assertEquals(0.9, g.regretSol, 1e-9)                 // 0.9 x (0.0024/0.0018) - 0.9 + 0.6 x 2 - 0.6
        assertEquals(90.0, g.regretPct, 1e-9)
        assertEquals(2, g.pricedSlices)                      // the fallback fill has no exit price: proceeds only
        assertTrue(g.tailCut)
        // The remaining position's value counts in the total of a partial grade.
        val open = LearningExitTruth8153.grade8153(cost, slices.take(1), 0.7, 0.0024)!!
        assertEquals(-10.0, open.bankedPct, 1e-9)
        assertEquals(60.0, open.totalPct, 1e-9)
        // No later mark: banked stays true, regret unknown.
        val unpriced = LearningExitTruth8153.grade8153(cost, slices, 0.0, Double.NaN)!!
        assertTrue(unpriced.regretPct.isNaN()); assertFalse(unpriced.tailCut)
        assertNull(LearningExitTruth8153.grade8153(0.0, slices, 0.0, 0.0024))
        assertNull(LearningExitTruth8153.fromLegs8153(emptyList(), 0.5))
        // A lane whose exits keep cutting moves that ran is proven tail-cutting; one whose exits saved money is not.
        val cutting = LearningExitTruth8153.Book8153()
        repeat(8) { cutting.add(g) }
        assertTrue(cutting.tailCutProven8153())
        assertEquals(8, cutting.tailCuts)
        val saving = LearningExitTruth8153.Book8153()
        val saved = LearningExitTruth8153.grade8153(cost, slices, 0.0, 0.0009)!!
        assertTrue(saved.regretPct < 0.0)
        repeat(8) { saving.add(saved) }
        assertFalse(saving.tailCutProven8153())
        val back = LearningExitTruth8153.Book8153().also { assertTrue(it.decode(cutting.encode())) }
        assertTrue(back.tailCutProven8153())
        assertTrue(LearningExitTruth8153.TAIL_CUT_REGRET_PCT_8153 > LearningExitTruth8153.TAIL_CUT_PROOF_PCT_8153)
        // Wiring: frozen at the publish BEFORE TailBag retires the fallback rows; the Cortex SELL answers to the record.
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertTrue(bus.indexOf("CortexExit7897.onClosePartials8153(env)") in 0 until bus.indexOf("TailBag8034.onClose8034(env)"))
        val ex = src("engine/cortex/CortexExit7897.kt")
        assertTrue(ex.contains("RecoveryFallbackLedger8141.confirmedFallbackSol8141(env.positionId)"))
        assertTrue(ex.contains("if (tailCutting8153(r.lane)) { inc(\"SUSPENDED_TAIL_CUT_8153_\${r.lane}\"); return null }"))
    }

    // ── 4. the displayed verdict stats read the tradeable book; authority stays denied without evidence ──

    @Test fun displayedVerdictStatsComeFromTheTradeableBook() {
        val book = CortexGradeBook8144()
        val lane = "MOONSHOT"
        var t = t0
        // 12 bought STRONG reads, +12 / +16.
        for (i in 0 until 12) { decide(book, "S$i", lane, t, strong, true, null, if (i % 2 == 0) 12.0 else 16.0); t += 60_000L }
        // One runner refused every 15 minutes while it ran: each label +213%.
        for (j in 0 until 10) decide(book, "RUN", lane, t + j * 15L * 60_000L, refuse, false, "LATE_CHASE_SCORE", 213.0)
        t += 10L * 15L * 60_000L
        // Six soft refusals at -10%, and four refusals first priced late at +300%.
        for (i in 0 until 6) { decide(book, "R$i", lane, t, refuse, false, "EDGE_GATE_SOFT_SCORE", -10.0); t += 60_000L }
        for (i in 0 until 4) { decide(book, "L$i", lane, t, refuse, false, "EDGE_GATE_SOFT_SCORE", 300.0, late = true); t += 60_000L }

        // What a raw (un-hygienic) book shows: REFUSE far above STRONG.
        val raw = CortexWalkForward8144.stat8144(book.observedSeries(lane, refuse))
        assertEquals(20, raw.n)
        assertEquals((10 * 213.0 + 6 * -10.0 + 4 * 300.0) / 20.0, raw.mean, 1e-9)
        // What the screens now show: the tradeable records only.
        val row = LearningEntryTruth8153.verdictRow8153(book, lane)
        assertEquals(12, row.strongN); assertEquals(14.0, row.strongMean, 1e-9)
        assertEquals(7, row.refuseN); assertEquals((213.0 - 60.0) / 7.0, row.refuseMean, 1e-9)
        assertEquals(0, row.neutralN); assertTrue(row.neutralMean.isNaN())
        assertEquals(7.0 / 11.0, row.refuseResolved, 1e-12)
        assertEquals(1.0, row.strongResolved, 0.0)
        // Wiring: the Learning panel, the scoreboard card and the status line read the row; the authority shown is the held one.
        val cx = src("engine/cortex/Cortex7885.kt")
        val ui = cx.substringAfter("fun ui8069(): Ui8069?").substringBefore("fun statusLine(): String")
        // Current UI uses only frozen v1 verdicts; the default helper above still reads preserved historical data.
        assertTrue(ui.contains("LearningEntryTruth8153.verdictRow8153(gradeBook8144, lane, scoreVersion = 1)"))
        assertTrue(ui.contains("val auth = heldFraction8153(lane)"))
        assertFalse(ui.contains("s.n.toInt(), s.mean(), nu.n.toInt(), nu.mean()"))
        val card = cx.substringAfter("fun scoreboardCard(): String").substringBefore("data class UiLane8069(")
        assertTrue(card.contains("LearningEntryTruth8153.verdictRow8153(gradeBook8144, lane, scoreVersion = 1)"))
        assertTrue(card.contains("fmtRow8153(row8153.strongN, row8153.strongMean)"))
        assertFalse(card.contains("\${fmtStat(b.byBucket[2]).padEnd(9)}"))
        assertTrue(cx.contains("currentFrozenV1_8372[refuse=\${fmtRow8153(v8153.refuseN, v8153.refuseMean)}"))

        // Authority: no owner-routed realised closes -> partial at most; losing or wallet-shrinking closes -> none.
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955, LearningEntryTruth8153.realisedEvidenceCap8153(emptyList()), 1e-12)
        assertEquals(0.0, LearningEntryTruth8153.realisedEvidenceCap8153(listOf(-14.0, -20.0, 5.0, -8.0, -30.0, 10.0)), 0.0)
        val drag = listOf(400.0, -95.0, -95.0, -95.0, -95.0, 400.0, -95.0, -95.0, -95.0, -95.0)
        assertEquals(4.0, CortexWalkForward8144.stat8144(drag).mean, 1e-9)                     // positive mean...
        assertTrue(LearningObjective8153.score8153(drag).growthPerTradePct < 0.0)               // ...that shrinks the wallet
        assertEquals(0.0, LearningEntryTruth8153.realisedEvidenceCap8153(drag), 0.0)
        assertEquals(1.0, LearningEntryTruth8153.realisedEvidenceCap8153(List(25) { if (it % 2 == 0) 8.0 else 12.0 }), 0.0)
        assertTrue(cx.contains("val cellRealised = ownerRealisedCap8153(lane, setup)"))
    }

    // ── 5. a relabel never misroutes the outcome ──

    @Test fun relabelAfterEntryDoesNotMisrouteTheWholePositionOutcome() {
        val sealedTag = "ACCELERATING_TAPE;LAUNCH_CONTINUATION#O=MATCHED#V=7"
        assertEquals("ACCELERATING_TAPE" to SetupSource8153.SEALED_IDENTITY,
            LearningEntryTruth8153.entrySetup8153(sealedTag, "PRE_IGNITION_BASE") { "LAUNCH_CONTINUATION" })
        assertEquals("PRE_IGNITION_BASE" to SetupSource8153.DECISION_SELECTED,
            LearningEntryTruth8153.entrySetup8153(null, "PRE_IGNITION_BASE") { "LAUNCH_CONTINUATION" })
        assertEquals("LAUNCH_CONTINUATION" to SetupSource8153.CLASSIFIER_AT_BIND,
            LearningEntryTruth8153.entrySetup8153("", null) { "LAUNCH_CONTINUATION" })
        assertEquals("" to SetupSource8153.NONE, LearningEntryTruth8153.entrySetup8153(null, null) { throw IllegalStateException("classifier") })

        // The trade is opened under its sealed setup; afterwards the classifier relabels the token.
        val book = CortexGradeBook8144()
        val (setup, _) = LearningEntryTruth8153.entrySetup8153(sealedTag, "PRE_IGNITION_BASE") { "LAUNCH_CONTINUATION" }
        val trade = CortexGradeRecord8144('T', "pos-7", "M7|MOONSHOT", t0, "MOONSHOT", setup, "BULL", "MID", "C3_6", "MC_10K_100K", strong, 5.0, true, true, false)
        trade.mode = "LIVE"
        assertTrue(book.openTrade(trade))
        val relabelled = "LAUNCH_CONTINUATION"
        val owner = CortexOutcomeRoute8144.owner8144("LIVE", "M7", "LIVE", "M7", "MOONSHOT", sealedTag, "$relabelled#O=MATCHED", "MOONSHOT", trade.setup)
        assertNotNull(book.closeTrade("pos-7", owner, 140.0))
        assertEquals(listOf(140.0), book.realisedSeries("MOONSHOT", "ACCELERATING_TAPE", strong, "LIVE"))
        assertTrue(book.realisedSeries("MOONSHOT", relabelled, null, "LIVE").isEmpty())

        // A close no Cortex read was bound to still reaches its sealed owner, under no verdict bucket.
        val (u, uo) = LearningEntryTruth8153.unboundTrade8153("pos-u", "MU", "LIVE", t0, "moonshot", "LIVE", "MU", "moonshot", sealedTag, null)!!
        assertEquals("MOONSHOT", u.lane); assertEquals("ACCELERATING_TAPE", u.setup)
        assertEquals(LearningEntryTruth8153.NO_READ_BUCKET_8153, u.bucket)
        assertTrue(book.openTrade(u))
        assertNotNull(book.closeTrade("pos-u", uo, 60.0))
        assertEquals(listOf(140.0, 60.0), book.realisedSeries("MOONSHOT", "ACCELERATING_TAPE", null, "LIVE"))
        assertEquals(listOf(140.0), book.realisedSeries("MOONSHOT", "ACCELERATING_TAPE", strong, "LIVE"))  // never a STRONG sample
        assertTrue(book.realisedSeries("MOONSHOT", null, null, "PAPER").isEmpty())                      // no paper/live mixing
        // A paper seal on a live close is not this trade's: lane-level economics only, no strategy sample.
        val (p, po) = LearningEntryTruth8153.unboundTrade8153("pos-p", "MU", "LIVE", t0, "MOONSHOT", "PAPER", "MU", "MOONSHOT", sealedTag, null)!!
        assertEquals(CortexOutcomeRoute8144.Attribution8144.UNVERIFIABLE, po.attribution)
        assertFalse(po.trainsStrategy)
        assertTrue(book.openTrade(p)); assertNotNull(book.closeTrade("pos-p", po, -40.0))
        assertEquals(listOf(140.0, 60.0), book.realisedSeries("MOONSHOT", "ACCELERATING_TAPE", null, "LIVE"))
        assertNull(LearningEntryTruth8153.unboundTrade8153("", "MU", "LIVE", t0, "MOONSHOT", null, null, null, null, null))
        // Wiring.
        val cx = src("engine/cortex/Cortex7885.kt")
        // 8198 — the unbound filing is the delivery's gate inside onCanonicalClose: nothing filed is an explicit ACK,
        // a filed unbound grade crosses the same durable-commit barrier as a bound one.
        assertTrue(cx.contains("if (!fileUnboundClose8153(env, mode, sealed8144, snapTag8144)) {"))
        assertTrue(cx.contains("val (setup, src8153) = LearningEntryTruth8153.entrySetup8153("))
        assertTrue(cx.contains("p.selected8153.ifBlank { p.setup7961 }.ifBlank { \"-\" }"))
    }

    // ── 6. walk-forward counterfactual with costs; the objective penalises tail cutting ──

    private val runnerPath = doubleArrayOf(5.0, 25.0, 60.0, 150.0, 400.0, 320.0, 300.0)
    private val fadePath = doubleArrayOf(5.0, 25.0, 10.0, -5.0, -10.0)

    @Test fun exitReplayBanksAtTheTriggeringMarkWithCosts() {
        val trail = ExitPolicy8153(stopPct = 30.0, trailFromPeakPct = 25.0, trailArmPct = 100.0)
        assertEquals(320.0, LearningPolicyCompare8153.exitGross8153(runnerPath, trail), 1e-9)
        assertEquals(-10.0, LearningPolicyCompare8153.exitGross8153(fadePath, trail), 1e-9)
        assertEquals(-45.0, LearningPolicyCompare8153.exitGross8153(doubleArrayOf(-10.0, -45.0, 20.0), trail), 1e-9)  // a gap banks the gap
        val take = ExitPolicy8153(stopPct = 30.0, takePct = 20.0)
        assertEquals(25.0, LearningPolicyCompare8153.exitGross8153(runnerPath, take), 1e-9)
        // One partial: half at the +150 mark, the rest on the trailing exit at +320; one extra sell leg is charged.
        val partial = trail.copy(partialAtPct = 100.0, partialFraction = 0.5, extraLegCostPct = 1.5)
        assertEquals(235.0, LearningPolicyCompare8153.exitGross8153(runnerPath, partial), 1e-9)
        assertEquals(1, LearningPolicyCompare8153.partialLegs8153(runnerPath, partial))
        assertEquals(0, LearningPolicyCompare8153.partialLegs8153(fadePath, partial))
        assertEquals(229.5, LearningPolicyCompare8153.counterfactualNet8153(runnerPath, partial, 4.0), 1e-9)
        assertEquals(-14.0, LearningPolicyCompare8153.counterfactualNet8153(fadePath, partial, 4.0), 1e-9)
        assertTrue(LearningPolicyCompare8153.exitGross8153(DoubleArray(0), trail).isNaN())
    }

    @Test fun objectivePenalisesATailCuttingPolicyThatWinsOnHitRate() {
        val paths = List(40) { if (it % 5 == 0) runnerPath else fadePath }
        val costs = List(40) { 4.0 }
        val letRun = ExitPolicy8153(stopPct = 30.0, trailFromPeakPct = 25.0, trailArmPct = 100.0)
        val cutAt20 = ExitPolicy8153(stopPct = 30.0, takePct = 20.0)
        val runNets = paths.indices.map { LearningPolicyCompare8153.counterfactualNet8153(paths[it], letRun, costs[it]) }
        val cutNets = paths.indices.map { LearningPolicyCompare8153.counterfactualNet8153(paths[it], cutAt20, costs[it]) }
        val run = LearningObjective8153.score8153(runNets)
        val cut = LearningObjective8153.score8153(cutNets)
        // Hit rate alone picks the tail-cutting policy...
        assertEquals(1.0, cut.hitRate, 0.0)
        assertEquals(0.2, run.hitRate, 1e-12)
        // ...the objective does not: expectancy, growth, captured runner return.
        assertEquals(52.0, run.expectancyPct, 1e-9)
        assertEquals(21.0, cut.expectancyPct, 1e-9)
        assertEquals(63.2, run.runnerPerTradePct, 1e-9)
        assertEquals(0.0, cut.runnerPerTradePct, 0.0)
        assertEquals(2528.0 / 448.0, run.profitFactor, 1e-9)
        assertTrue(run.growthPerTradePct > cut.growthPerTradePct)
        assertTrue(run.maxDrawdownPct > 0.0 && cut.maxDrawdownPct == 0.0)
        assertTrue(run.score > cut.score)
        assertTrue(run.score - cut.score > 25.0)
        assertEquals(40, run.n)
        assertTrue(LearningObjective8153.score8153(emptyList()).score.isNaN())
        assertTrue(LearningObjective8153.RUNNER_PCT_8153 > 0.0 && LearningObjective8153.STAKE_FRACTION_8153 in 0.0..1.0)
        // Switching to the tail-cutting exit is never adopted out of sample.
        val r = LearningPolicyCompare8153.compareExits8153(paths, costs, letRun, cutAt20)
        assertEquals(Verdict8153.NO_EDGE, r.verdict)
        assertFalse(r.adopt)
        assertEquals(0, r.foldsTested)
        assertTrue(run.compact().contains("hit=20%"))
    }

    @Test fun aBetterExitIsAdoptedOnlyOutOfSampleAfterCosts() {
        val dip = doubleArrayOf(-10.0, -35.0, -20.0, 0.0, 20.0)
        val paths = List(40) { if (it % 2 == 0) dip else fadePath }
        val costs = List(40) { 4.0 }
        val r = LearningPolicyCompare8153.compareExits8153(paths, costs, ExitPolicy8153(stopPct = 30.0), ExitPolicy8153(stopPct = 40.0))
        assertEquals(Verdict8153.ADOPT, r.verdict)
        assertTrue(r.adopt)
        assertEquals(3, r.foldsTested); assertEquals(3, r.foldsPassed)
        assertEquals(24, r.oos.n); assertEquals(27.5, r.oos.mean, 1e-9)
        assertTrue(r.candidateOos!!.score > r.baselineOos!!.score)
        // Too few paired trades never adopt; a candidate that only matches the baseline has no edge.
        assertEquals(Verdict8153.INSUFFICIENT_DATA, LearningPolicyCompare8153.compareReturns8153(List(10) { 1.0 }, List(10) { 9.0 }).verdict)
        assertEquals(Verdict8153.NO_EDGE, LearningPolicyCompare8153.compareReturns8153(List(40) { 5.0 }, List(40) { 5.0 }).verdict)
        assertTrue(LearningPolicyCompare8153.MARGIN_PCT_8153 > 0.0)
    }
}
