package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8169 — exits that destroyed the edge: stops inside noise and cost, backstops shallower than the stop, a runner's
 * protective floor discarded while its give-back lock was deferred, and loss refusals resting on pre-epoch labels.
 */
class Aate8169ExitsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. stop width: lane doctrine, round trip + measured noise ──

    @Test fun theLaneDoctrineIsTheFloorOfALearnedStop() {
        assertEquals(10.0, ExitDoctrine8169.doctrinePct8169("BLUECHIP"), 1e-9)
        assertEquals(10.0, ExitDoctrine8169.doctrinePct8169("QUALITY"), 1e-9)
        assertEquals(15.0, ExitDoctrine8169.doctrinePct8169("MOONSHOT"), 1e-9)
        assertEquals(5.0, ExitDoctrine8169.doctrinePct8169("CASHGEN"), 1e-9)
        // 5.0.8168: StopLearner8040 learned 5% for BLUECHIP / QUALITY — under the 10% Field Manual invalidation.
        assertEquals(10.0, ExitDoctrine8169.floorStopMag8169(5.0, 10.0, Double.NaN, Double.NaN), 1e-9)
        assertEquals("DOCTRINE", ExitDoctrine8169.floorSource8169(5.0, 10.0, Double.NaN, Double.NaN))
        // A wider stop is never narrowed (CASHGEN learned 25%).
        assertEquals(25.0, ExitDoctrine8169.floorStopMag8169(25.0, 5.0, 1.0, 2.0), 1e-9)
        assertEquals("", ExitDoctrine8169.floorSource8169(25.0, 5.0, 1.0, 2.0))
    }

    @Test fun aStopIsNeverInsideItsRoundTripPlusTwoMeasuredRanges() {
        // A pump coin ranging 6%/minute with a 4% round trip: a 15% stop is inside 4 + 2 x 6 = 16.
        assertEquals(16.0, ExitDoctrine8169.floorStopMag8169(15.0, Double.NaN, 6.0, 4.0), 1e-9)
        assertEquals("NOISE", ExitDoctrine8169.floorSource8169(15.0, 15.0, 6.0, 4.0))
        // Never past StopAuthority7887's 25% ceiling.
        assertEquals(25.0, ExitDoctrine8169.floorStopMag8169(15.0, Double.NaN, 20.0, 10.0), 1e-9)
        // Unknown noise is not invented: only the doctrine applies.
        assertEquals(12.0, ExitDoctrine8169.floorStopMag8169(12.0, 10.0, Double.NaN, 30.0), 1e-9)
        // A calm bluechip (1.5%/minute, 2.5% cost) keeps its 10% stop.
        assertEquals(10.0, ExitDoctrine8169.floorStopMag8169(10.0, 10.0, 1.5, 2.5), 1e-9)
    }

    @Test fun noiseIsMeasuredFromRealOneMinuteRangesOnly() {
        assertEquals(10.0, ExitDoctrine8169.candleRangePct8169(1.0, 1.05, 0.95), 1e-9)
        assertTrue(ExitDoctrine8169.candleRangePct8169(1.0, 0.0, 0.0).isNaN())   // high / low never observed
        assertTrue(ExitDoctrine8169.noisePct8169(listOf(5.0, Double.NaN)).isNaN()) // under three measured candles
        assertEquals(6.0, ExitDoctrine8169.noisePct8169(listOf(4.0, 6.0, 8.0)), 1e-9)
        // The newest 12 only.
        assertEquals(1.0, ExitDoctrine8169.noisePct8169(List(5) { 50.0 } + List(12) { 1.0 }), 1e-9)
    }

    @Test fun learnedTighteningNeverGoesInsideTheLaneInvalidation() {
        assertEquals(-15.0, ExitDoctrine8169.predictiveSlPct8169(-3.0, 15.0)!!, 1e-9)
        assertEquals(-20.0, ExitDoctrine8169.predictiveSlPct8169(-20.0, 15.0)!!, 1e-9)
        assertNull(ExitDoctrine8169.predictiveSlPct8169(null, 15.0))
        assertEquals(-5.0, ExitDoctrine8169.predictiveSlPct8169(-5.0, Double.NaN)!!, 1e-9)
    }

    // ── 2. backstops sit under the ordinary stop; a lifecycle floor is its own ──

    @Test fun theHardFloorAndCatastropheAreDeeperThanTheOrdinaryStop() {
        assertEquals(15.0, ExitDoctrine8169.hardFloorMag8169(6.0), 1e-9)      // tight lanes: unchanged 15%
        assertEquals(15.0, ExitDoctrine8169.hardFloorMag8169(Double.NaN), 1e-9)
        assertEquals(20.0, ExitDoctrine8169.hardFloorMag8169(15.0), 1e-9)     // runner lanes: under their 15% stop
        assertEquals(30.0, ExitDoctrine8169.hardFloorMag8169(25.0), 1e-9)
        assertEquals(30.0, ExitDoctrine8169.hardFloorMag8169(40.0), 1e-9)     // capped: a real hard floor remains
        // 5.0.8168 48Xs7Y: the bootstrap catastrophe (-15) sat at the hard floor and sold a 9-second-old MOONSHOT.
        assertEquals(20.0, ExitDoctrine8169.catastropheMag8169(-15.0, 20.0, Double.NaN), 1e-9)
        assertEquals(25.0, ExitDoctrine8169.catastropheMag8169(-25.0, 15.0, Double.NaN), 1e-9)
        // A lifecycle hold's -40% floor is never pre-empted by a shallower "catastrophe" (it bypasses every hold).
        assertEquals(40.0, ExitDoctrine8169.catastropheMag8169(-15.0, 30.0, 40.0), 1e-9)
    }

    // ── 3. a deferred runner keeps its protective floor ──

    @Test fun aDeferredRunnerIsFlooredAtBreakEvenThenAtHalfItsPeak() {
        assertTrue(ExitDoctrine8169.runnerProtectFloor8169(8.0, 3.0).isNaN())          // room to run
        assertEquals(3.5, ExitDoctrine8169.runnerProtectFloor8169(21.0, 3.0), 1e-9)      // break-even (7935)
        assertEquals(22.5, ExitDoctrine8169.runnerProtectFloor8169(45.0, 3.0), 1e-9)     // half the peak (8104 band)
        assertEquals(ExitDoctrine8169.RunnerLock8169.UNDER_BREAKEVEN, ExitDoctrine8169.runnerLockState8169(true, 8.0, 3.0))
        assertEquals(ExitDoctrine8169.RunnerLock8169.BREAKEVEN_FLOOR, ExitDoctrine8169.runnerLockState8169(true, 21.0, 3.0))
        assertEquals(ExitDoctrine8169.RunnerLock8169.HALF_PEAK_FLOOR, ExitDoctrine8169.runnerLockState8169(true, 45.0, 3.0))
        assertEquals(ExitDoctrine8169.RunnerLock8169.ARMED, ExitDoctrine8169.runnerLockState8169(false, 45.0, 3.0))
    }

    @Test fun theProtectiveFloorFiresWhileASlidingLockStillWaits() {
        assertTrue(ExitDoctrine8169.positiveStopFires8169(-15.0, true, Double.NaN))     // a loss stop always fires
        assertTrue(ExitDoctrine8169.positiveStopFires8169(12.0, false, Double.NaN))     // not deferred: as before
        assertTrue(ExitDoctrine8169.positiveStopFires8169(22.5, true, 22.5))            // the protective floor fires
        assertFalse(ExitDoctrine8169.positiveStopFires8169(30.0, true, 22.5))           // a lock above it waits for the arm
        assertFalse(ExitDoctrine8169.positiveStopFires8169(3.0, true, Double.NaN))      // no floor earned: waits (7322)
    }

    // ── 4. stale loss refusals decay to neutral, never to allow ──

    private val staleLoser = Evidence8136(CandidateAdmission8136.KIND_CELL, "C", 80, -9.0, 2.0)

    private fun candidate(epochAware: Boolean, cellLoss: Evidence8136?) = Candidate8136(
        mint = "MINT8169pump", lane = "SHITCOIN", selectedSetup = "PRE_IGNITION_BASE", regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 5_000L, expectedGrossPct = 30.0, costPct = 6.0, downsidePct = 15.0,
        cell = staleLoser, genericScore = 12.0, genericFloor = 30.0,
        lossEpochAware8169 = epochAware, cellLoss8169 = cellLoss,
    )

    @Test fun aLossPatternRefusesOnlyOnLabelsGradedSinceTheEpoch() {
        // Pure fixtures (not epoch-aware) keep the all-time record as the loss record.
        assertEquals("CELL_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(candidate(false, null)).reason)
        // Live: the losing record predates the epoch and nothing since proves it -> it decays...
        val decayed = CandidateAdmission8136.decide8136(candidate(true, null))
        assertFalse(decayed.reason == "CELL_PROVEN_LOSING_8136")
        assertTrue(decayed.trace.any { it.startsWith(CandidateAdmission8136.STALE_CELL_TRACE_8169) })
        // ...to neutral, not to allow: the generic prior still decides (12 < 30 refuses).
        assertEquals(Terminal8136.REFUSE, decayed.terminal)
        assertEquals("GENERIC_SCORE_FLOOR_8136", decayed.reason)
        // Fresh labels that prove the loss again refuse it again.
        assertEquals("CELL_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(candidate(true, staleLoser)).reason)
        assertTrue(CandidateAdmission8136.staleLoss8169(true, staleLoser, null))
        assertFalse(CandidateAdmission8136.staleLoss8169(false, staleLoser, null))
        assertFalse(CandidateAdmission8136.staleLoss8169(true, staleLoser, staleLoser))
    }

    @Test fun theGradingEpochNeedsNoManualReset() {
        // The build's grading start is an epoch on its own (LearningEpoch8164 = 0, never reset).
        assertEquals(1_000L, ForwardReturnLabeler7731.gradingEpoch8169(1_000L, 0L))
        assertEquals(5_000L, ForwardReturnLabeler7731.gradingEpoch8169(1_000L, 5_000L))
        assertTrue(ForwardReturnLabeler7731.freshObservation8169(1_000L, 1_000L))
        assertFalse(ForwardReturnLabeler7731.freshObservation8169(999L, 1_000L))
        // No epoch known: nothing may teach a loss pattern (never gate on history a fresh run cannot have).
        assertFalse(ForwardReturnLabeler7731.freshObservation8169(5_000L, 0L))
    }

    // ── 5. an exit is charged the sell leg, not the buy leg it already paid ──

    @Test fun aSmallRemaindersProfitExitIsNotChargedTheSunkBuyLeg() {
        val ne = com.lifecyclebot.engine.truth.NetEdge8030
        // ~0.001 SOL remainder, 0.000295 SOL per fixed leg: the round trip reads ~61%, the exit's own share ~31.5%.
        assertEquals(31.5, ne.exitCostPct8169(61.0, 0.000295, 0.001), 1e-9)
        // Never under the sell leg's own fixed share (a measured round trip smaller than the model's legs).
        assertEquals(29.5, ne.exitCostPct8169(35.0, 0.000295, 0.001), 1e-9)
        // Never over the round trip; unknown legs or size leave the round trip as it was.
        assertEquals(2.0, ne.exitCostPct8169(2.0, 0.01, 0.001), 1e-9)
        assertEquals(5.0, ne.exitCostPct8169(5.0, Double.NaN, 0.03), 1e-9)
        assertEquals(5.0, ne.exitCostPct8169(5.0, 0.0003, 0.0), 1e-9)
        // A normal 0.03 SOL ticket: one ~1% leg comes off.
        assertEquals(4.0, ne.exitCostPct8169(5.0, 0.0003, 0.03), 1e-9)
        val neSrc = src("engine/truth/NetEdge8030.kt")
        assertTrue(neSrc.contains("val cost = exitCostPct8169(roundTrip, try { com.lifecyclebot.engine.CostMeter8151.roundTripFixedSol8151() / 2.0 }"))
        // Structural exits (rug, dev dump, liquidity) are still never held: they return before any cost is read.
        val body = neSrc.substringAfter("fun exitRefusal8030(").substringBefore("val roundTrip = ")
        assertTrue(body.contains("if (structural) return false"))
    }

    // ── wiring ──

    @Test fun wired() {
        val sa = src("engine/cortex/StopAuthority7887.kt")
        assertTrue(sa.contains("ExitDoctrine8169.doctrineFloored8169(lane, composed0)"))
        assertTrue(sa.contains("ExitDoctrine8169.noiseCostFloored8169(ts, lane, chosen)"))
        assertTrue(sa.contains("ExitDoctrine8169.doctrineFloored8169(l, compose(null, base, mult, runner || l == \"MOONSHOT\"))"))
        assertTrue(src("engine/cortex/StopLearner8040.kt").contains("ExitDoctrine8169.doctrinePct8169(lane)"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val HARD_FLOOR_STOP_PCT_CONST = ExitDoctrine8169.rapidHardFloorMag8169(ts)"))
        assertTrue(bot.contains("val catastropheThreshold = ExitDoctrine8169.rapidCatastrophePct8169(ts, getCatastropheThreshold(cfg.paperMode))"))
        assertTrue(bot.contains("ExitDoctrine8169.runnerAwareStop8169(ts, raw, peakPnlPct, costPct, runnerDeferred) {"))
        assertTrue(bot.contains("if (pnlPct <= dynamicStopPct7696 && ExitDoctrine8169.fires8169(ts, dynamicStopPct7696, runnerDefer7322) &&"))
        assertFalse(bot.contains("!(dynamicStopPct7696 > 0.0 && runnerDefer7322)"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("val HARD_FLOOR_STOP_PCT = ExitDoctrine8169.riskHardFloorMag8169(ts, isFreshMeme)"))
        assertFalse(ex.contains("if (isFreshMeme) 9.0 else 15.0"))
        assertTrue(ex.contains("ExitDoctrine8169.recommendedSlPct8169("))
        val moon = src("v3/scoring/MoonshotTraderAI.kt")
        assertEquals(2, Regex(Regex.escape("ExitDoctrine8169.recommendedSlPct8169(")).findAll(moon).count())
        assertTrue(moon.contains("ExitDoctrine8169.earlyRunnerStopMag8169(mint)"))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(adm.contains("lossEpochAware8169 = true,"))
        assertTrue(adm.contains("ForwardReturnLabeler7731.freshCellStatFor8169(ts, lane, nowMs)"))
        assertTrue(adm.contains("ForwardReturnLabeler7731.freshStageStatFor8169(fit.lane, fit.stage.name)"))
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        // superseded by V5.0.8324: the fresh tally also counts chain-witnessed runners.
        assertTrue(lab.contains("if (horizon == 60) bookFresh8169(o, keys, net, gross, witnessed8324)"))
        assertTrue(lab.contains("ExitDoctrine8169.noteGraded8169(o.lane, stop8029, o.peakPct, dipFirst8029)"))
        assertTrue(src("engine/truth/LearningResetSweep7781.kt").contains("\"stop_learner_8042.txt\""))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Exit doctrine (§8169): "))
        // Integrity exits are other reasons and never pass through these helpers.
        val doc = src("engine/ExitDoctrine8169.kt")
        for (r in listOf("RUG", "HONEYPOT", "FREEZE", "DEV_", "LIQUIDITY_")) assertFalse(doc.contains("\"$r"))
    }
}
