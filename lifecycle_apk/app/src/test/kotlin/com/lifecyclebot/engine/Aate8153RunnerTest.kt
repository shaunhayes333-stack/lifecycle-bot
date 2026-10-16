package com.lifecyclebot.engine

import com.lifecyclebot.engine.RunnerPlan8153.Continuation8153
import com.lifecyclebot.engine.RunnerPlan8153.Evidence8153
import com.lifecyclebot.engine.RunnerPlan8153.Plan8153
import com.lifecyclebot.engine.RunnerPlan8153.Promotion8153
import com.lifecyclebot.engine.RunnerPlan8153.PromotionInput8153
import com.lifecyclebot.engine.RunnerReplay8153.Mark8153
import com.lifecyclebot.engine.RunnerRungBook8153.Revert8153
import com.lifecyclebot.engine.RunnerRungBook8153.Stage8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8153 — runner classification, promotion and retention (P3) and the profit-banking / partial-exit lifecycle
 * (P4). Price paths are replayed through the live exit order (RunnerReplay8153: promotion before the ladder, every
 * overlay offers, ExitTruthArbiter8144 decides once, only a landed fill confirms a rung and books proceeds once).
 * Units: the entry cost is 1.0 SOL; sells pay 3% (fees + slippage).
 */
class Aate8153RunnerTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val entry = Mark8153(1.0)
    private fun climb(vararg xs: Double) = xs.map { Mark8153(it, firming = true) }
    private fun replay(path: List<Mark8153>, style: String = "QUICK_FLIP", markMs: Long = 1_000L) =
        RunnerReplay8153.replay8153(path, style, markMs = markMs)
    private fun input(plan: Plan8153 = Plan8153.SCALP, peak: Double = 30.0, gross: Double = 32.0, firming: Boolean = true,
                      broken: Boolean = false, ageMs: Long = 5 * 60_000L, down: Boolean = false, latched: Boolean = false,
                      hosts: Boolean = true) =
        PromotionInput8153(plan, hosts, latched, peak, gross, firming, broken, ageMs, down)

    // ── P3: one plan per position, per sealed strategy (never the lane name alone) ──

    @Test fun onePlanFromTheSealedSetupAnEntryAdmitOrAProvenPromotion() {
        fun plan(style: String, strategy: String = "", grabbed: Boolean = false, latched: Boolean = false, peak: Double = 0.0, hosts: Boolean = true) =
            RunnerPlan8153.plan8153(Evidence8153(hosts, style, strategy, grabbed, latched, peak))
        assertEquals(Plan8153.RUNNER_SEALED, plan("BREAKOUT_RUNNER"))
        assertEquals(Plan8153.SCALP, plan("QUICK_FLIP"))
        // The immutable sealed style decides; the sealed strategy only speaks when the style is unknown.
        assertEquals(Plan8153.SCALP, plan("QUICK_FLIP", strategy = "DIAMOND_HANDS_RUNNER"))
        assertEquals(Plan8153.RUNNER_SEALED, plan("", strategy = "DIAMOND_HANDS_RUNNER"))
        // An entry-time runner admit (grab / ticket / proven cell) counts only when the sealed style is unknown.
        assertEquals(Plan8153.RUNNER_SEALED, plan("", grabbed = true))
        assertEquals(Plan8153.SCALP, plan("QUICK_FLIP", grabbed = true))
        // A promotion of THIS position (latched) or its corroborated +100% peak.
        assertEquals(Plan8153.RUNNER_PROMOTED, plan("QUICK_FLIP", latched = true))
        assertEquals(Plan8153.RUNNER_PROMOTED, plan("QUICK_FLIP", peak = 150.0))
        assertEquals(Plan8153.SCALP, plan("QUICK_FLIP", peak = 250_000.0))                 // a broken basis is not a run
        assertEquals(Plan8153.SCALP, plan("BREAKOUT_RUNNER", hosts = false))               // the lane hosts or it does not
        assertEquals(RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION, RunnerPlan8153.basis8153(Plan8153.RUNNER_PROMOTED))
        assertEquals(RunnerExitContract8136.RunnerBasis8136.NONE, RunnerPlan8153.basis8153(Plan8153.SCALP))
    }

    @Test fun theArbiterReadsTheSamePlanIncludingALatchedPromotion() {
        val scalp = ExitTruthArbiter8144.Inputs8144(sealedStrategy = "", entryStyle = "QUICK_FLIP", laneHostsRunners = true,
            confirmedPeakPct = 30.0, capitalRecoveredConfirmed = false, pnlPct = 30.0, sellCostPct = 3.0, remainingShare = 1.0)
        val milestone = listOf(ExitTruthArbiter8144.Intent8144("RUNNER_MILESTONE_8136_T2_400PCT", 0.35))
        assertEquals(RunnerExitContract8136.RunnerBasis8136.NONE, ExitTruthArbiter8144.runnerBasis8144(scalp))
        assertNull(ExitTruthArbiter8144.decide8144(milestone, scalp).act)
        // A promotion latched at +30% (RunnerLive8153) is the arbiter's answer too: no reader disagrees any more.
        val promoted = scalp.copy(planBasis8153 = RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION)
        assertEquals(RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION, ExitTruthArbiter8144.runnerBasis8144(promoted))
        assertEquals(0.35, ExitTruthArbiter8144.decide8144(milestone, promoted).act!!.fraction, 1e-12)
        assertEquals(RunnerExitContract8136.RunnerBasis8136.NONE,
            ExitTruthArbiter8144.runnerBasis8144(promoted.copy(laneHostsRunners = false)))
    }

    @Test fun promotionIsEarnedOnVerifiedContinuationAndRefusedOnSpikesBadUnitsAndDistribution() {
        // Early: the chain-verified peak and the print at +25% or better, chart firming — before the +40% scalp rung.
        assertEquals(Promotion8153.PROMOTE_STRUCTURE, RunnerPlan8153.promotion8153(input()))
        // Late continuation: 3 hours in (the 8018 rule's 30-minute window refused it).
        assertEquals(Promotion8153.PROMOTE_STRUCTURE, RunnerPlan8153.promotion8153(input(ageMs = 3L * 3_600_000L)))
        assertFalse(RunnerPlay8018.promotes8018(30.0, 3L * 3_600_000L, true, false, false))
        assertEquals(Promotion8153.REFUSE_OUTSIDE_WINDOW, RunnerPlan8153.promotion8153(input(ageMs = 7L * 3_600_000L)))
        // Failed promotions.
        assertEquals(Promotion8153.NOT_YET, RunnerPlan8153.promotion8153(input(firming = false)))
        assertEquals(Promotion8153.NOT_YET, RunnerPlan8153.promotion8153(input(peak = 20.0, gross = 22.0)))
        assertEquals(Promotion8153.REFUSE_UNCORROBORATED, RunnerPlan8153.promotion8153(input(peak = 10.0, gross = 60.0)))
        assertEquals(Promotion8153.REFUSE_BAD_UNITS, RunnerPlan8153.promotion8153(input(gross = 200_000.0)))
        assertEquals(Promotion8153.REFUSE_BAD_UNITS, RunnerPlan8153.promotion8153(input(peak = Double.NaN)))
        assertEquals(Promotion8153.REFUSE_STRUCTURE_BROKEN, RunnerPlan8153.promotion8153(input(broken = true)))
        assertEquals(Promotion8153.REFUSE_DISTRIBUTION, RunnerPlan8153.promotion8153(input(peak = 80.0, gross = 30.0)))
        assertEquals(Promotion8153.STOOD_DOWN, RunnerPlan8153.promotion8153(input(down = true)))
        assertEquals(Promotion8153.NOT_HOSTED, RunnerPlan8153.promotion8153(input(hosts = false)))
        assertEquals(Promotion8153.ALREADY_RUNNER, RunnerPlan8153.promotion8153(input(latched = true)))
        assertEquals(Promotion8153.ALREADY_RUNNER, RunnerPlan8153.promotion8153(input(plan = Plan8153.RUNNER_SEALED)))
        // The corroborated +100% peak already proved it: the latch records it without a fresh structure read.
        assertEquals(Promotion8153.PROMOTE_PROVEN_PEAK,
            RunnerPlan8153.promotion8153(input(plan = Plan8153.RUNNER_PROMOTED, peak = 150.0, gross = 120.0, firming = false)))
        assertTrue(RunnerPlan8153.promotes8153(Promotion8153.PROMOTE_STRUCTURE))
        assertFalse(RunnerPlan8153.promotes8153(Promotion8153.REFUSE_UNCORROBORATED))
    }

    @Test fun continuationGivebackDistributionAndStructureBreakArePerPositionState() {
        val r = Plan8153.RUNNER_SEALED
        assertEquals(Continuation8153.CONTINUING, RunnerPlan8153.continuation8153(r, 100.0, 90.0, true, true, false))
        assertEquals(Continuation8153.STANDING, RunnerPlan8153.continuation8153(r, 100.0, 90.0, false, true, false))
        assertEquals(Continuation8153.GIVING_BACK, RunnerPlan8153.continuation8153(r, 100.0, 70.0, true, true, false))
        assertEquals(Continuation8153.DISTRIBUTING, RunnerPlan8153.continuation8153(r, 100.0, 45.0, true, true, false))
        assertEquals(Continuation8153.DISTRIBUTING, RunnerPlan8153.continuation8153(r, 300.0, 150.0, false, true, false))
        assertEquals(Continuation8153.BROKEN, RunnerPlan8153.continuation8153(r, 100.0, 95.0, true, true, true))
        assertEquals(Continuation8153.NONE, RunnerPlan8153.continuation8153(r, 20.0, -5.0, true, true, false))   // red: no hold
        assertEquals(Continuation8153.NONE, RunnerPlan8153.continuation8153(Plan8153.SCALP, 100.0, 90.0, true, true, false))
        assertTrue(RunnerPlan8153.continuationConfirmed8153(Continuation8153.STANDING))
        assertFalse(RunnerPlan8153.continuationConfirmed8153(Continuation8153.DISTRIBUTING))
        // A runner with a confirmed continuation takes no ordinary scalp partial below its first runner rung.
        assertTrue(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_PROMOTED, true, "RAPID_INSTANT_PROFIT_CAPTURE_4301", 60.0))
        assertFalse(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_PROMOTED, true, "RAPID_INSTANT_PROFIT_CAPTURE_4301", 120.0))
        assertFalse(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_PROMOTED, false, "RAPID_INSTANT_PROFIT_CAPTURE_4301", 60.0))
        assertFalse(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_PROMOTED, true, "SPIKE_CAPTURE_7943_T1_60PCT", 60.0))
        assertFalse(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_PROMOTED, true, "CAPITAL_RECOVERY_8136", 60.0))
        assertFalse(RunnerPlan8153.scalpPartialHeld8153(Plan8153.RUNNER_PROMOTED, true, "STRICT_SL_-20", -20.0))
        assertFalse(RunnerPlan8153.scalpPartialHeld8153(Plan8153.SCALP, true, "RAPID_INSTANT_PROFIT_CAPTURE_4301", 60.0))
    }

    // ── P3 + P4 replays: 2x, 5x, 11x, 51x ──

    @Test fun runnerPathsBankEconomicTakesKeepARealResidualAndBookEveryFillOnce() {
        val p2 = listOf(entry) + climb(1.1, 1.3, 1.5, 1.8, 2.1, 1.9) + listOf(Mark8153(1.6), Mark8153(1.3), Mark8153(1.0))
        val p5 = listOf(entry) + climb(1.1, 1.3, 1.6, 2.2, 3.0, 4.0, 5.2, 4.8) + listOf(Mark8153(4.0), Mark8153(3.3, structureBroken = true))
        val p11 = listOf(entry) + climb(1.1, 1.3, 1.6, 2.2, 3.0, 5.1, 7.0, 9.0, 11.2, 10.0) + listOf(Mark8153(7.0))
        val p51 = listOf(entry) + climb(1.1, 1.3, 1.6, 2.2, 3.0, 5.1, 8.0, 11.5, 16.0, 25.0, 35.0, 51.0, 45.0) + listOf(Mark8153(30.0))
        val runs = listOf(p2, p5, p11, p51).map { replay(it) }
        for (r in runs) {
            println("8153 replay: peak=${r.verifiedPeakPct.toInt()}% net=${"%+.3f".format(r.net)} atPeak=${"%.3f".format(r.shareAtPeak)} exits=${r.exits}")
            // Promoted on the +30% firming print — before the +50% print where the scalp ladder would have sold 60%.
            assertEquals(Plan8153.RUNNER_PROMOTED, r.plan)
            assertEquals(2, r.promotedAtMark)
            assertEquals(Promotion8153.PROMOTE_STRUCTURE, r.promotion)
            assertEquals(0, r.scalpConfirmed)
            // The first take is the capital-recovery tranche, net of costs: principal really comes back.
            assertTrue(r.exits.first().startsWith("CAPITAL_RECOVERY_8136"))
            assertTrue(r.capitalRecovered)
            // Exactly-once proceeds: every fill was reported twice (slice commit + chain callback), booked once.
            assertEquals(r.proceeds, r.bookedProceeds, 1e-12)
            assertTrue(r.exits.none { it.startsWith("UNFILLED") })
            assertTrue(r.net > 0.0)
        }
        assertEquals(listOf(1, 2, 3, 3), runs.map { it.runnerConfirmed })
        assertTrue(runs[0].exits.last().startsWith("RUNNER_FROM_ENTRY_GIVEBACK_8104"))
        for (r in runs.drop(1)) assertTrue(r.exits.last().startsWith("RUNNER_PEAK_CAPTURE_7980"))
        // A real residual rode each run to its peak (the 51x keeps at least the protected 20% of the original).
        assertEquals(1.0 - 1.0 / (2.2 * 0.97), runs[1].shareAtPeak, 1e-9)
        assertTrue(runs[2].shareAtPeak > RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136)
        assertTrue(runs[3].shareAtPeak >= RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136)
        assertTrue(runs.zipWithNext().all { (a, b) -> b.net > a.net })
        assertTrue(runs[3].net > 8.0)
    }

    @Test fun fastSpikeAndLegitimateScalpKeepTheScalpLadder() {
        val spike = replay(listOf(entry, Mark8153(1.05), Mark8153(1.6), Mark8153(1.05), Mark8153(1.02)))
        assertEquals(Plan8153.SCALP, spike.plan)
        assertEquals(-1, spike.promotedAtMark)
        assertTrue(spike.exits.first().startsWith("SPIKE_CAPTURE_7943_T1"))   // sold into the spike on its print
        assertEquals(1, spike.scalpConfirmed)
        assertEquals(spike.proceeds, spike.bookedProceeds, 1e-12)
        assertTrue(spike.net > 0.3)
        val scalp = replay(listOf(entry, Mark8153(1.2), Mark8153(1.45), Mark8153(1.3)))
        assertEquals(Plan8153.SCALP, scalp.plan)
        assertEquals(1, scalp.exits.size)
        assertTrue(scalp.exits.single().startsWith("SPIKE_CAPTURE_7943_T1"))
        assertEquals(0.4, scalp.residualShare, 1e-9)
    }

    @Test fun lateContinuationPromotesAndTheScalpRungNeverFires() {
        // Marks an hour apart: the run confirms 3 hours in; the +45% print then belongs to the runner ladder (+100%).
        val late = replay(listOf(entry, Mark8153(1.1), Mark8153(1.2), Mark8153(1.3, firming = true), Mark8153(1.45, firming = true)),
            markMs = 3_600_000L)
        assertEquals(3, late.promotedAtMark)
        assertEquals(Plan8153.RUNNER_PROMOTED, late.plan)
        assertTrue(late.exits.isEmpty())
        assertEquals(1.0, late.residualShare, 1e-12)
    }

    @Test fun falseSpikesAndBadUnitsNeverPromoteAndNoPhantomProceeds() {
        val phantom = replay(listOf(entry, Mark8153(1.1, firming = true),
            Mark8153(20.0, execPx = 1.1, verifiedPx = 1.1, firming = true), Mark8153(1.1, firming = true)))
        assertEquals(Promotion8153.REFUSE_UNCORROBORATED, phantom.promotion)
        assertEquals(Plan8153.SCALP, phantom.plan)
        assertEquals(10.0, phantom.verifiedPeakPct, 1e-6)                    // the 20x print never ratcheted the peak
        // Whatever sold was priced at the executable 1.1, never at the 20x print; booked once.
        assertTrue(phantom.proceeds <= 1.1 * 0.97 + 1e-9)
        assertEquals(phantom.proceeds, phantom.bookedProceeds, 1e-12)
        val units = replay(listOf(entry, Mark8153(5_000.0, execPx = 1.0, verifiedPx = null, firming = true), Mark8153(1.0)))
        assertEquals(Promotion8153.REFUSE_BAD_UNITS, units.promotion)
        assertTrue(units.exits.isEmpty())
        assertEquals(0.0, units.verifiedPeakPct, 0.0)
    }

    @Test fun postPeakDistributionExitsAndEmergencyStaysAheadOfTheContinuationHold() {
        val dist = replay(listOf(entry) + climb(1.3, 1.6, 2.2, 3.0, 4.0) + listOf(Mark8153(3.5, standing = true), Mark8153(2.5, standing = true)))
        assertTrue(dist.exits.last().startsWith("RUNNER_PEAK_CAPTURE_7980"))
        val emergency = replay(listOf(entry) + climb(1.3, 1.6, 2.2) + listOf(Mark8153(2.1, firming = true, emergency = true)), "BREAKOUT_RUNNER")
        assertTrue(emergency.exits.last().startsWith("LIQUIDITY_DRAIN_EXIT"))
        assertEquals(0.0, emergency.residualShare, 1e-12)
        // A standing runner holds the chart reader's soft full exit twice, then releases it once the structure stops standing.
        val held = replay(listOf(entry) + climb(1.3, 1.6, 2.2) + listOf(Mark8153(2.15, firming = true, chartTop = true),
            Mark8153(2.1, standing = true, chartTop = true), Mark8153(2.0, chartTop = true)), "BREAKOUT_RUNNER")
        assertEquals(2, held.continuationHolds)
        assertTrue(held.exits.last().startsWith("CHART_CAPTURE_TOP_7950"))
    }

    // ── P4: the rung lifecycle ──

    @Test fun overlappingIntentsAndRejectedTranchesNeverConsumeATier() {
        // A sealed runner's capital-recovery rung and a structure break on the same print: the break (rank 3) acts and
        // the rung goes back unconsumed.
        val overlap = replay(listOf(entry, Mark8153(1.3, firming = true), Mark8153(2.1, structureBroken = true)), "BREAKOUT_RUNNER")
        assertEquals(1, overlap.exits.size)
        assertTrue(overlap.exits.single().startsWith("RUNNER_STRUCTURE_BREAK_7967"))
        assertEquals(1, overlap.superseded)
        assertEquals(0, overlap.runnerConfirmed)
        // A tranche whose sale does not land: no fill, no proceeds, the tier retries after its 1 s backoff and confirms.
        val rejected = replay(listOf(entry, Mark8153(1.2), Mark8153(1.45, sellFails = true), Mark8153(1.46), Mark8153(1.47)))
        assertEquals(1, rejected.rejected)
        assertTrue(rejected.exits[0].startsWith("UNFILLED:SPIKE_CAPTURE_7943_T1"))
        assertTrue(rejected.exits[1].startsWith("SPIKE_CAPTURE_7943_T1"))
        assertEquals(1, rejected.scalpConfirmed)
    }

    @Test fun rungStagesConfirmOnlyOnTheFillAndPendingBlocksDuplicateSells() {
        val b = RunnerRungBook8153()
        val k = "MINT8153|1"
        val s = RunnerRungBook8153.LADDER_SCALP_8153
        assertTrue(b.request8153(k, s, 1, "SPIKE_CAPTURE_7943_T1_45PCT", 0L))
        assertFalse(b.request8153(k, s, 2, "SPIKE_CAPTURE_7943_T2_130PCT", 100L))       // one tranche at a time
        assertTrue(b.advance8153(k, Stage8153.TICKET_ACCEPTED, 200L))
        assertFalse(b.advance8153(k, Stage8153.REQUESTED, 300L))                        // never backwards
        assertFalse(b.settle8153(k, inFlight = true, nowMs = 400L))                     // the sell lock: BROADCAST
        assertEquals(Stage8153.BROADCAST, b.pending8153(k)!!.stage)
        assertEquals(0, b.confirmedTier8153(k, s))                                      // nothing confirmed before the fill
        assertFalse(b.settle8153(k, inFlight = false, nowMs = 400L + 74_000L))          // inside the verifier window
        assertTrue(b.confirm8153(k, s, 1, "SIG-A", 0.84, 0.6))
        assertFalse(b.confirm8153(k, s, 1, "SIG-A", 0.84, 0.6))                        // the same fill reported again
        assertEquals(1, b.confirmedTier8153(k, s))
        assertNull(b.pending8153(k))
        assertEquals(0.84, b.bankedProceedsSol8153(k), 1e-12)
        assertEquals(0.24, b.bankedNetSol8153(k), 1e-12)
        assertFalse(b.request8153(k, s, 1, "SPIKE_CAPTURE_7943_T1_46PCT", 500L))       // a confirmed tier is not re-sold
        // Rejected: back off (1 s, 2 s, ...), never consumed; superseded: retry on the next print; expired: retryable.
        assertTrue(b.request8153(k, s, 2, "SPIKE_CAPTURE_7943_T2_130PCT", 1_000L))
        assertEquals(1_000L, RunnerRungBook8153.backoffMs8153(1))
        b.revert8153(k, Revert8153.REJECTED, 1_000L)
        assertFalse(b.request8153(k, s, 2, "SPIKE_CAPTURE_7943_T2_130PCT", 1_500L))
        assertTrue(b.request8153(k, s, 2, "SPIKE_CAPTURE_7943_T2_130PCT", 2_000L))
        b.revert8153(k, Revert8153.SUPERSEDED, 2_000L)
        assertTrue(b.request8153(k, s, 2, "SPIKE_CAPTURE_7943_T2_130PCT", 2_001L))
        assertFalse(b.settle8153(k, inFlight = false, nowMs = 2_001L + 4_000L))
        assertTrue(b.settle8153(k, inFlight = false, nowMs = 2_001L + 5_000L))          // never handed off: expires
        assertEquals(1, b.confirmedTier8153(k, s))
        assertEquals(60_000L, RunnerRungBook8153.backoffMs8153(17))                      // slow, but never dropped
        // A promotion moves the position to the runner ladder: its rungs start at 0, the scalp record stays.
        assertTrue(b.latchPromotion8153(k))
        assertFalse(b.latchPromotion8153(k))
        assertEquals(0, b.confirmedTier8153(k, RunnerRungBook8153.LADDER_RUNNER_8153))
        assertEquals(1, b.confirmedTier8153(k, s))
    }

    @Test fun restartRestoresRungsAndPromotionsIdempotently() {
        val b = RunnerRungBook8153()
        val k = "MINT8153R|42"
        val r = RunnerRungBook8153.LADDER_RUNNER_8153
        b.latchPromotion8153(k)
        b.noteOpportunity8153(k, r, 0.1, 400.0)
        assertTrue(b.request8153(k, r, 2, "RUNNER_MILESTONE_8136_T2_410PCT", 0L))
        b.confirm8153(k, r, 2, "SIG-R", 0.3, 0.05)
        assertTrue(b.request8153(k, r, 3, "RUNNER_MILESTONE_8136_T3_1010PCT", 10L))     // pending: not persisted
        val text = b.encode8153()
        val after = RunnerRungBook8153()
        assertEquals(1, after.restore8153(text))
        assertEquals(1, after.restore8153(text))                                         // twice: still the same state
        assertEquals(2, after.confirmedTier8153(k, r))
        assertTrue(after.promoted8153(k))
        assertNull(after.pending8153(k))
        assertEquals(0.3, after.bankedProceedsSol8153(k), 1e-12)
        assertFalse(after.confirm8153(k, r, 2, "SIG-R", 0.3, 0.05)) // signature dedupe survives a process restart
        assertEquals(0.3, after.bankedProceedsSol8153(k), 1e-12)
        assertFalse(after.request8153(k, r, 2, "RUNNER_MILESTONE_8136_T2_410PCT", 20L)) // the filled rung is not re-sold
        assertTrue(after.request8153(k, r, 3, "RUNNER_MILESTONE_8136_T3_1010PCT", 20L)) // the unconfirmed one retries
        // Gross opportunity vs net banked on the status line; a closed position moves to the closed totals.
        assertTrue(after.statusLine8153().contains("grossOppSol=0.4000"))
        assertTrue(after.statusLine8153().contains("netBankedSol=+0.2500"))
        assertTrue(after.retire8153(k))
        assertTrue(after.statusLine8153().contains("positions=0+1closed"))
        val again = RunnerRungBook8153()
        again.restore8153(after.encode8153())
        assertTrue(again.statusLine8153().contains("netBankedSol=+0.2500"))
    }

    @Test fun recoveryTrancheIsValidatedAgainstHeldQuantityPrincipalAndCost() {
        // 2x, 3% sell cost, everything held: grossed up for costs (more than the 8136-naive 50%).
        assertEquals(1.0 / (2.0 * 0.97), RunnerPlan8153.recoveryTranche8153(1.0, 2.0, 3.0, 1.0).fraction, 1e-12)
        assertEquals("RECOVERED", RunnerPlan8153.recoveryTranche8153(0.0, 2.0, 3.0, 1.0).why)
        assertEquals("NO_CANONICAL_QTY", RunnerPlan8153.recoveryTranche8153(1.0, 2.0, 3.0, 0.0).why)
        // Only half the runtime quantity is really held: recovering principal would leave no residual — no tranche.
        assertEquals("RESIDUAL_UNFUNDED", RunnerPlan8153.recoveryTranche8153(1.0, 2.0, 3.0, 0.5).why)
        // A 1.1x holding cannot recover its principal after 20% costs at all: not a recovery tranche.
        assertEquals(0.0, RunnerPlan8153.recoveryTranche8153(1.0, 1.1, 20.0, 1.0).fraction, 0.0)
        // After a confirmed partial brought 0.8 back, only the unrecovered 0.2 is targeted.
        assertEquals(0.2 / (1.0 * 0.97), RunnerPlan8153.recoveryTranche8153(0.2, 1.0, 3.0, 1.0).fraction, 1e-12)
        // The moonbag remainder is the real one: canonical quantity when known, nothing for a closed position.
        assertEquals(0.0, TailBag8034.tailRemainder8153(open = true, runtimeQty = 1_000.0, canonicalQty = 0.0), 0.0)
        assertEquals(250.0, TailBag8034.tailRemainder8153(open = true, runtimeQty = 1_000.0, canonicalQty = 250.0), 0.0)
        assertEquals(1_000.0, TailBag8034.tailRemainder8153(open = true, runtimeQty = 1_000.0, canonicalQty = null), 0.0)
        assertEquals(0.0, TailBag8034.tailRemainder8153(open = false, runtimeQty = 1_000.0, canonicalQty = 250.0), 0.0)
    }

    // ── live readers agree (TokenState, no Android) ──

    // Entered a minute ago: inside the runner hold window (RunnerGrab7967: 6 h).
    private val entryMs = System.currentTimeMillis() - 60_000L

    private fun token(mint: String, style: String, peakPct: Double = 0.0, lane: String = "MOONSHOT") =
        com.lifecyclebot.data.TokenState(mint = mint, position = com.lifecyclebot.data.Position(qtyToken = 1_000_000.0,
            entryPrice = 1.0, entryTime = entryMs, tradingMode = lane, isPaperPosition = false, peakGainPct = peakPct,
            entryPolicySnapshot = "style=$style;entryTactic=BREAKOUT"))

    @Test fun aLatchedPromotionIsOneAnswerForEveryLiveReader() {
        val ts = token("latch8153", "QUICK_FLIP", peakPct = 30.0)
        assertFalse(TradeExitContract8077.runnerFromEntry8104(ts))
        assertFalse(RunnerGrab7967.holdingRunner8018(ts))
        assertNull(RunnerGrab7967.tiersFor7967(ts))
        assertTrue(RunnerGrab7967.promote8018(ts))                       // the promotion is latched on the position ...
        assertEquals(RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION, TradeExitContract8077.runnerBasis8136(ts))
        assertTrue(TradeExitContract8077.runnerFromEntry8104(ts))        // ... and every reader sees it
        assertTrue(RunnerGrab7967.holdingRunner8018(ts))
        assertEquals(RunnerGrab7967.TIERS_7967, RunnerGrab7967.tiersFor7967(ts))
        assertFalse(RunnerGrab7967.isGrabbed7980("latch8153"))           // not written into the entry-admit map
        // A QUICK_FLIP in the CASHGEN scalp lane is never a runner, latched or not.
        val cash = token("cash8153", "QUICK_FLIP", peakPct = 30.0, lane = "CASHGEN")
        RunnerGrab7967.promote8018(cash)
        assertFalse(TradeExitContract8077.runnerFromEntry8104(cash))
    }

    @Test fun wiringOrderAndDoors() {
        val spike = src("engine/SpikeCapture7943.kt")
        assertTrue(spike.contains("RunnerLive8153.settlePending8153(ts, inFlight8153, now7955)"))
        assertTrue(spike.contains("RunnerLive8153.request8153(ts, ladder8153, reached, reason8153, now7955)"))
        assertTrue(spike.contains("RunnerLive8153.revert8153(ts, RunnerRungBook8153.Revert8153.REJECTED, now7955, MAX_REARMS_7944)"))
        assertFalse(spike.contains("private val firedTiers"))                 // no tier is consumed before its sale
        // The promotion runs before the ladder is chosen and before any rung is requested.
        assertTrue(spike.indexOf("RunnerPlay8018.onMark8018(ts, gross, now7955)") in 1 until spike.indexOf("RunnerLive8153.request8153("))
        assertTrue(src("engine/RunnerPlay8018.kt").contains("RunnerLive8153.promotion8153(ts, gross, down, nowMs)"))
        assertTrue(src("engine/RunnerGrab7967.kt").contains("RunnerLive8153.latchPromotion8153(ts)"))
        assertTrue(src("engine/TradeExitContract8077.kt").contains("= RunnerLive8153.basis8153(ts)"))
        val live = src("engine/ExitTruthArbiterLive8144.kt")
        assertTrue(live.contains("planBasis8153 = try { RunnerLive8153.basis8153(ts) }"))
        assertTrue(live.contains("continuationConfirmed = try { RunnerLive8153.continuationConfirmed8153(ts) }"))
        assertTrue(live.contains("SpikeCapture7943.dispatched8153(ts, act.reason)"))
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("RunnerLive8153.bookSlice8153(ts, reason, proof.sig, result.actualConsumedRaw, result.solReceived, result.realizedPnl.proportionalCostBasisSol)"))
        assertTrue(exec.contains("TailBag8034.markSetAside8034(ts, confirmedSale8153 = !r.terminal)"))
        assertTrue(src("engine/BotService.kt").contains("SpikeCapture7943.onApplied8153(ts, reason)"))
        val exits = src("engine/RunnerExitLive8136.kt")
        assertTrue(exits.contains("RunnerPlan8153.recoveryTranche8153(unrecovered, grossValueSol, sellCostPct8136(ts), sellableShare8153(pos.qtyToken, canon))"))
        assertTrue(exits.contains("RunnerPlan8153.scalpPartialHeld8153("))
        assertTrue(exits.contains("bank8153["))
        assertTrue(src("engine/TailBag8034.kt").contains("RunnerLive8153.retire8153(env.mint"))
    }
}
