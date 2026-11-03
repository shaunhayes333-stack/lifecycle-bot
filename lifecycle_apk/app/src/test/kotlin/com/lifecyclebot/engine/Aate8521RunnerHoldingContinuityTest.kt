package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.StrategyMeta
import com.lifecyclebot.data.TokenState
import org.junit.Assert.*
import org.junit.Test

class Aate8521RunnerHoldingContinuityTest {
    private fun token(lane: String, paper: Boolean = true, ageMs: Long = 60_000L) = TokenState(
        mint = "holding8521_${lane}_${System.nanoTime()}",
        position = Position(qtyToken = 1000.0, entryPrice = 1.0, costSol = 0.1,
            entryTime = System.currentTimeMillis() - ageMs, tradingMode = lane,
            isPaperPosition = paper, peakGainPct = 30.0, highestPrice = 1.3,
            entryLiquidityUsd = 1_000_000.0, entryPolicySnapshot = "style=QUICK_FLIP;entryTactic=BREAKOUT"),
        meta = StrategyMeta(momScore = 80.0, volScore = 60.0, emafanAlignment = "BULL_FAN"),
        lastPrice = 1.2, lastPriceUpdate = System.currentTimeMillis(), lastLiquidityUsd = 1_000_000.0,
    )

    private fun latchProvenStructure(ts: TokenState) {
        val verdict = RunnerPlan8153.promotion8153(RunnerPlan8153.PromotionInput8153(
            plan = RunnerLive8153.plan8153(ts), laneHostsRunners = true, latched = false,
            verifiedPeakPct = 30.0, grossPct = 30.0, firming = true, structureBroken = false,
            ageMs = 60_000L, standingDown = false,
        ))
        assertEquals(RunnerPlan8153.Promotion8153.PROMOTE_STRUCTURE, verdict)
        assertTrue(RunnerLive8153.latchPromotion8153(ts))
    }

    @Test fun anEarnedStructurePromotionOwnsTheTrailAndClockBelowTheFiftyPercentPeakBar() {
        for (lane in listOf("EXPRESS", "MANIPULATED", "SHITCOIN", "PROJECT_SNIPER", "DIP_HUNTER")) {
            for (paper in listOf(false, true)) {
                val ts = token(lane, paper)
                assertFalse(RunnerTrail8419.runnerPosition8521(ts))
                assertNull(RunnerTrail8419.holdLayerLevelFor8419(ts, lane, lane))
                try {
                    latchProvenStructure(ts)
                    val runner = RunnerTrail8419.runnerPosition8521(ts)
                    assertTrue(runner)
                    assertEquals(RunnerExitContract8136.RunnerBasis8136.PROVEN_PROMOTION, RunnerLive8153.basis8153(ts))
                    assertEquals(45.5, RunnerTrail8419.holdLayerTrailPoints8419(lane, lane, 10.0, 0.65, 30.0,
                        runnerPosition = runner), 1e-9)
                    val level = RunnerTrail8419.holdLayerLevelFor8419(ts, lane, lane)!!
                    assertTrue(level.levelPct in 12.0..15.0)
                    assertFalse(LaneHolds8418.clockBinds8418(lane, lane, 20.0, 30.0, runnerPosition = runner))
                    for (pnl in listOf(-1.0, 0.0, Double.NaN)) {
                        assertTrue(LaneHolds8418.clockBinds8418(lane, lane, pnl, 30.0, runnerPosition = runner))
                    }
                    // Pure callers with only lane/peak evidence retain the existing 8468 contract.
                    assertEquals(6.5, RunnerTrail8419.holdLayerTrailPoints8419(lane, lane, 10.0, 0.65, 30.0), 1e-9)
                    assertTrue(LaneHolds8418.clockBinds8418(lane, lane, 20.0, 30.0))
                } finally { RunnerLive8153.retire8153(ts.mint, ts.position.entryTime) }
            }
        }
    }

    @Test fun theRuntimeHoldingLayerKeepsThePromotionButStillHonoursItsFloorAndHardStop() {
        val ts = token("MANIPULATED", ageMs = 48L * 3_600_000L)
        val before = HoldingLogicLayer.evaluatePosition(ts.position, ts, 20.0, true)
        assertEquals(before.reason, HoldingLogicLayer.HoldAction.EXIT_NOW, before.action)
        assertTrue(before.reason, before.reason.startsWith("Trailing stop") || before.reason.contains("hold time exceeded"))
        try {
            latchProvenStructure(ts)
            val after = HoldingLogicLayer.evaluatePosition(ts.position, ts, 20.0, true)
            assertNotEquals(after.reason, HoldingLogicLayer.HoldAction.EXIT_NOW, after.action)
            assertFalse(after.reason, after.reason.contains("Evaluation error"))
            val floor = HoldingLogicLayer.evaluatePosition(ts.position, ts, 11.0, true)
            assertEquals(floor.reason, HoldingLogicLayer.HoldAction.EXIT_NOW, floor.action)
            assertTrue(floor.reason, floor.reason.startsWith("RUNNER_TRAIL_GREEN_FLOOR"))
            val stop = HoldingLogicLayer.evaluatePosition(ts.position, ts, -40.0, true)
            assertEquals(stop.reason, HoldingLogicLayer.HoldAction.EXIT_NOW, stop.action)
        } finally { RunnerLive8153.retire8153(ts.mint, ts.position.entryTime) }
    }

    @Test fun aSealedStyleCannotPromoteAContractLaneOrGiveANewLotTheOldLatch() {
        for (lane in listOf("EXPRESS", "MANIPULATED", "SHITCOIN", "PROJECT_SNIPER", "DIP_HUNTER")) {
            val ts = token(lane)
            ts.position = ts.position.copy(entryPolicySnapshot = "style=BREAKOUT_RUNNER;entryTactic=BREAKOUT")
            assertFalse(RunnerTrail8419.runnerPosition8521(ts))
            try {
                latchProvenStructure(ts)
                val restored = ts.copy(position = ts.position.copy(peakGainPct = 0.0))
                assertTrue(RunnerTrail8419.runnerPosition8521(restored))
                assertNull(RunnerTrail8419.holdLayerLevelFor8419(restored, lane, lane))
                val newLot = restored.copy(position = restored.position.copy(entryTime = ts.position.entryTime + 1L))
                assertFalse(RunnerTrail8419.runnerPosition8521(newLot))
            } finally { RunnerLive8153.retire8153(ts.mint, ts.position.entryTime) }
        }
    }
}
