package com.lifecyclebot.engine

import com.lifecyclebot.engine.RunnerPlan8153.Evidence8153
import com.lifecyclebot.engine.RunnerPlan8153.Plan8153
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LaneTrigger8468
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8468 — the strategy audit's four fixes (owner: "all 4. but be smart").
 *  1. each fast lane exits on its own designed contract until the position proves it runs (+50 % verified peak or a proven
 *     runner-grab entry); then the runner contract owns it, uncapped;
 *  2. the non-runner desks run their own sub-trader's clock and are graded on it;
 *  3. a coin the lane's own brain qualifies is admitted on that trigger's record at the lane's horizon;
 *  4. a runner lane past its launch window refuses a tighter chase; wallet-follow lanes and proven launches are exempt.
 */
class Aate8468LaneDesignTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val minute = 60_000L

    // ── 1. lane exit contracts ──

    @Test fun aFastLaneIsARunnerOnlyOnceItIsPromoted() {
        for (lane in listOf("EXPRESS", "MANIPULATED", "SHITCOIN", "PROJECT_SNIPER", "DIP_HUNTER", "SHITCOIN_EXPRESS")) {
            assertTrue(lane, RunnerExitProfile7277.contractLane8468(lane))
            assertFalse("$lane unpromoted", RunnerExitProfile7277.runnerExits8468(lane, 49.9))
            assertFalse("$lane no peak", RunnerExitProfile7277.runnerExits8468(lane, Double.NaN))
            assertTrue("$lane promoted", RunnerExitProfile7277.runnerExits8468(lane, 50.0))
        }
        // MOONSHOT and the ensemble / copy lanes keep the runner contract from entry; desks never have it
        for (lane in listOf("MOONSHOT", "MOONSHOT_ORBITAL", "CORE", "WHALE_FOLLOW")) {
            assertFalse(lane, RunnerExitProfile7277.contractLane8468(lane))
            assertTrue(lane, RunnerExitProfile7277.runnerExits8468(lane, 0.0))
        }
        assertFalse(RunnerExitProfile7277.runnerExits8468("TREASURY", 500.0))
        assertFalse(RunnerExitProfile7277.runnerExits8468("BLUECHIP", 500.0))
    }

    @Test fun anUnpromotedFastLaneKeepsItsOwnGiveBackLockAndTrail() {
        // +40 peak, now +21: the lane's own 40 % give-back fires; MOONSHOT's runner floor (<= +20 there) holds
        assertTrue(PeakDrawdownLock.shouldLock(40.0, 21.0, "EXPRESS"))
        assertFalse(PeakDrawdownLock.shouldLock(40.0, 21.0, "MOONSHOT"))
        // promoted (+500 peak) the fast lane rides the runner trail exactly as before
        assertFalse(PeakDrawdownLock.shouldLock(500.0, 410.0, "EXPRESS"))
        assertTrue(PeakDrawdownLock.shouldLock(500.0, 289.0, "EXPRESS"))
        // the hold layer: own table trail x patience until promoted, the trail's own points after
        assertEquals(10.0, RunnerTrail8419.holdLayerTrailPoints8419("EXPRESS", "EXPRESS", 10.0, 1.0, 30.0), 1e-9)
        assertEquals(70.0, RunnerTrail8419.holdLayerTrailPoints8419("EXPRESS", "EXPRESS", 10.0, 1.0, 100.0), 1e-9)
        assertEquals(26.0, RunnerTrail8419.tickLockLevel8419("SHITCOIN", 30.0, 26.0), 1e-9)
    }

    @Test fun anUnpromotedFastLaneRunsItsDesignedClock() {
        assertEquals(30L * minute, HoldingLogicLayer.getHoldParams("EXPRESS").maxHoldTimeMs)
        assertEquals(15L * minute, HoldingLogicLayer.getHoldParams("MANIPULATED").maxHoldTimeMs)
        assertEquals(6L * 60L * minute, HoldingLogicLayer.getHoldParams("DIP_HUNTER").maxHoldTimeMs)
        assertTrue("green, unpromoted: its clock binds", LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", 12.0, 30.0))
        assertFalse("green, promoted: the trail owns it", LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", 12.0, 80.0))
        assertTrue("red, promoted: its clock binds", LaneHolds8418.clockBinds8418("EXPRESS", "EXPRESS", -2.0, 80.0))
        assertFalse("MOONSHOT green from entry", LaneHolds8418.clockBinds8418("MOONSHOT", "MOONSHOT", 3.0))
        assertEquals(35.0, LaneHolds8418.trailFor8418("CORE", "CORE", 10.0), 0.0)
        assertEquals(25.0, LaneHolds8418.trailFor8418("SHITCOIN", "SHITCOIN", 25.0, 20.0), 0.0)
    }

    @Test fun aSealedStyleAloneNoLongerMakesAFastLaneBuyARunner() {
        fun plan(contract: Boolean, style: String = "BREAKOUT_RUNNER", peak: Double = 0.0, admitted: Boolean = false, grabbed: Boolean = false,
                 latched: Boolean = false) = RunnerPlan8153.plan8153(Evidence8153(true, style, "", grabbedAtEntry = grabbed, promotedLatched = latched,
            verifiedPeakPct = peak, runnerEntryAdmitted = admitted, contractLane8468 = contract))
        assertEquals("unchanged for MOONSHOT", Plan8153.RUNNER_SEALED, plan(false))
        assertEquals(Plan8153.SCALP, plan(true))
        assertEquals(Plan8153.SCALP, plan(true, peak = 49.0))
        assertEquals(Plan8153.RUNNER_PROMOTED, plan(true, peak = 50.0))
        assertEquals(Plan8153.RUNNER_PROMOTED, plan(true, latched = true))
        assertEquals("a proven runner setup at entry", Plan8153.RUNNER_SEALED, plan(true, admitted = true))
        assertEquals(Plan8153.RUNNER_SEALED, plan(true, style = "CHART_BREAKOUT", grabbed = true))
        assertEquals("a basis in the wrong units is not a promotion", Plan8153.SCALP, plan(true, peak = 200_000.0))
        assertTrue(src("engine/RunnerLive8153.kt").contains("contractLane8468 = RunnerExitProfile7277.contractLane8468(pos.tradingMode)"))
    }

    @Test fun aFastLanesOwnExitsActUnheldBeforePromotion() {
        assertTrue(LaneExitContract8468.designedExit8468("EXPRESS", "EXPRESS_MOMENTUM_DEATH"))
        assertTrue(LaneExitContract8468.designedExit8468("SHITCOIN_EXPRESS", "EXPRESS_TIME_EXIT"))
        assertTrue(LaneExitContract8468.designedExit8468("MANIPULATED", "MANIPULATED_TIME_EXIT"))
        assertTrue(LaneExitContract8468.designedExit8468("SHITCOIN", "SHITCOIN_TAKE_PROFIT"))
        assertTrue(LaneExitContract8468.designedExit8468("PROJECT_SNIPER", "SNIPER_TRAIL_FADE"))
        assertTrue(LaneExitContract8468.designedExit8468("DIP_HUNTER", "DIP_RECOVERY_TARGET"))
        assertFalse("another lane's exit", LaneExitContract8468.designedExit8468("EXPRESS", "SHITCOIN_TIME_EXIT"))
        assertFalse("not a contract lane", LaneExitContract8468.designedExit8468("MOONSHOT", "MOONSHOT_TAKE_PROFIT"))
        assertFalse("a generic overlay is not the lane's design", LaneExitContract8468.designedExit8468("EXPRESS", "Fluid hold time exceeded"))
        assertTrue(LaneExitContract8468.acts8468("EXPRESS", "EXPRESS_MOMENTUM_DEATH", Plan8153.SCALP))
        assertFalse(LaneExitContract8468.acts8468("EXPRESS", "EXPRESS_MOMENTUM_DEATH", Plan8153.RUNNER_PROMOTED))
        assertFalse(LaneExitContract8468.acts8468("EXPRESS", "EXPRESS_MOMENTUM_DEATH", Plan8153.RUNNER_SEALED))

        // the gates that held them: cost floor, moonbag, tiny-profit deferral, style min-hold
        assertTrue(src("engine/truth/NetEdge8030.kt").substringAfter("fun exitRefusal8030(").substringBefore("val structural = structural8030(reason)")
            .contains("com.lifecyclebot.engine.LaneExitContract8468.acts8468(ts, reason)"))
        val ex = src("engine/Executor.kt")
        for (head in listOf("private fun moonbagGate7322(", "private fun liveProfitDustExitShouldDefer(", "private fun liveHoldDelayIfNeeded(")) {
            assertTrue(head, ex.substringAfter(head).take(900).contains("LaneExitContract8468.acts8468(ts, "))
        }
        // MANIPULATED's own exit runs until promotion, whatever style was sealed
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("ManipulatedTraderAI.hasPosition(ts.mint, ts.position.isPaperPosition) &&\n            !RunnerExitProfile7277.runnerExits8468(\"MANIPULATED\", ts.position.peakGainPct)) {"))
    }

    // ── 2. desks on their own clocks ──

    @Test fun desksRunTheirOwnClocksAndAreGradedOnThem() {
        assertEquals(45L * minute, HoldingLogicLayer.getHoldParams("CASHGEN").maxHoldTimeMs)
        assertEquals(45L * minute, HoldingLogicLayer.getHoldParams("TREASURY").maxHoldTimeMs)
        assertEquals(60L * minute, HoldingLogicLayer.getHoldParams("QUALITY").maxHoldTimeMs)
        assertEquals(8L * 60L * minute, HoldingLogicLayer.getHoldParams("BLUECHIP").maxHoldTimeMs)
        assertEquals(45L * minute, LaneHolds8418.deskHorizonMs8468("cashgen"))
        assertNull(LaneHolds8418.deskHorizonMs8468("EXPRESS"))
        assertEquals(45L * minute, CortexLabelHygiene8144.horizonMsFor8468("CASHGEN", false))
        assertEquals("hour-graded / runner lanes unchanged", CortexLabelHygiene8144.executableHorizonMs8392(true), CortexLabelHygiene8144.horizonMsFor8468("QUALITY", true))
        assertEquals(60L * minute, CortexLabelHygiene8144.horizonMsFor8468("STANDARD", false))
        assertFalse(CortexLabelHygiene8144.revisionCountsFor8468("CASHGEN", false, 50L * minute))
        assertTrue(CortexLabelHygiene8144.revisionCountsFor8468("STANDARD", false, 50L * minute))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertEquals(3, Regex("CortexLabelHygiene8144\\.revisionCountsFor8468\\(").findAll(cx).count())
        assertTrue(src("engine/HoldingLogicLayer.kt").contains("val hold8392 = base.maxHoldTimeMs"))
    }

    // ── 3. the lane's own trigger ──

    private fun stat(n60: Int, mean: Double, se: Double, n240: Int = 0, mean240: Double = 0.0, suspect: Boolean = false) =
        ForwardReturnLabeler7731.CellStat("TRIGGER|X|NATIVE", n60, mean, 0.5, 0.1, se, 0, n240, mean240, outlierSuspect8185 = suspect)

    @Test fun aLanesOwnTriggerAdmitsOnItsOwnRecordAtItsOwnHorizon() {
        // five-minute lanes: 30+ labels, mean - SE > 0
        assertTrue(LaneTrigger8468.proven8468(false, stat(30, 2.0, 1.0)))
        assertFalse(LaneTrigger8468.proven8468(false, stat(29, 2.0, 1.0)))
        assertFalse(LaneTrigger8468.proven8468(false, stat(80, 1.0, 1.0)))
        // hour-graded lanes: 40+ hour labels, mean > 0
        assertTrue(LaneTrigger8468.proven8468(true, stat(10, -5.0, 1.0, n240 = 40, mean240 = 3.0)))
        assertFalse(LaneTrigger8468.proven8468(true, stat(10, 5.0, 1.0, n240 = 39, mean240 = 3.0)))
        assertFalse(LaneTrigger8468.proven8468(true, stat(10, 5.0, 1.0, n240 = 80, mean240 = -0.1)))
        assertFalse("an implausible basis proves nothing", LaneTrigger8468.proven8468(false, stat(60, 9.0, 1.0, suspect = true)))
        assertFalse(LaneTrigger8468.proven8468(false, null))
        // the lane's brain must qualify the coin NOW
        assertTrue(LaneTrigger8468.admits8468(true, false, stat(30, 2.0, 1.0)))
        assertFalse(LaneTrigger8468.admits8468(false, false, stat(30, 2.0, 1.0)))
        assertFalse(LaneTrigger8468.admits8468(null, false, stat(30, 2.0, 1.0)))

        assertEquals("TRIGGER|EXPRESS|NATIVE", ForwardReturnLabeler7731.triggerKey8468(" express "))
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("it.nativeTrigger8468 = try { com.lifecyclebot.engine.OwnershipChain8364.nativeEligible8364(ts.mint, l) == true }"))
        assertTrue(frl.contains("val withStage = if (o.nativeTrigger8468 && !pseudo) staged + triggerKey8468(o.lane) else staged"))
        assertTrue("never pruned", frl.contains("!it.key.startsWith(\"TRIGGER|\")"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.substringAfter("private fun measuredEdge8060(").take(600).contains("LaneTrigger8468.admits8468(ts, lane)"))
        assertTrue("it clears watch-first's unmeasured thin-cell wait only", gate.contains("if (trigger8468) auditedAdmission8076 = true"))
        // the realised-loss tactic refusal still binds a proven trigger
        assertTrue(gate.contains("if (proven8059 == null && !admittedJudged8201 && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, l8059)"))
    }

    // ── 4. entry timing ──

    @Test fun aRunnerLanePastItsLaunchWindowRefusesATighterChase() {
        assertTrue(LiveEdgeGate7877.chasingRunner8468(45.0, 0.95, 0.0))
        assertFalse("pulled back off the high", LiveEdgeGate7877.chasingRunner8468(45.0, 0.85, 0.0))
        assertFalse(LiveEdgeGate7877.chasingRunner8468(30.0, 0.99, 0.0))
        assertTrue(LiveEdgeGate7877.chasingRunner8468(0.0, 0.5, 65.0))
        assertFalse(LiveEdgeGate7877.chasingRunner8468(Double.NaN, Double.NaN, Double.NaN))
        // the 8065 spike rule is unchanged for every lane
        assertFalse(LiveEdgeGate7877.chasing8065(45.0, 0.95, 0.0))
        assertTrue(LiveEdgeGate7877.chasing8065(120.0, 0.95, 10.0))

        assertFalse("a proven launch is bought on its own tape bound", LiveEdgeGate7877.runnerChaseApplies8468(true, 60_000L))
        assertTrue("an unknown age is not assumed a launch", LiveEdgeGate7877.runnerChaseApplies8468(true, null))
        assertTrue(LiveEdgeGate7877.runnerChaseApplies8468(true, 10L * minute))
        assertFalse(LiveEdgeGate7877.runnerChaseApplies8468(false, null))
        assertTrue(LiveEdgeGate7877.followsWallet8468("WHALE_FOLLOW"))
        assertTrue(LiveEdgeGate7877.followsWallet8468("COPY_TRADE"))
        assertFalse(LiveEdgeGate7877.followsWallet8468("EXPRESS"))

        val chase = src("engine/truth/LiveEdgeGate7877.kt").substringAfter("internal fun chaseRefusal8397(").substringBefore("    /**")
        assertTrue(chase.contains("&& !followsWallet8468(lane)"))
        assertTrue(chase.contains("if (!spike8065 && !runner8468) return null"))
        assertTrue("one chase terminal, counted apart", chase.contains("if (runner8468) \"CHASING_RUNUP_8468_${'$'}lane\" else \"CHASING_SPIKE_8065_${'$'}lane\""))
    }
}
