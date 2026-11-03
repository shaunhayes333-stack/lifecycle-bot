package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.RunnerPlan8153.Plan8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises actual runtime readers: the pure plan already allowed these lanes but its callers excluded them. */
class Aate8521RunnerCapabilityTest {
    private val entryMs = System.currentTimeMillis() - 60_000L

    private fun token(lane: String, suffix: String, peak: Double, paper: Boolean = false) = TokenState(
        mint = "runner8521_${lane}_${suffix}_${System.nanoTime()}",
        position = Position(qtyToken = 1_000.0, entryPrice = 1.0, entryTime = entryMs,
            tradingMode = lane, isPaperPosition = paper, peakGainPct = peak,
            entryPolicySnapshot = "style=QUICK_FLIP;entryTactic=BREAKOUT"),
    )

    private fun assertReaders(ts: TokenState, expected: Plan8153) {
        val basis = RunnerPlan8153.basis8153(expected)
        assertEquals(ts.position.tradingMode, expected, RunnerLive8153.plan8153(ts))
        assertEquals(basis, TradeExitContract8077.runnerBasis8136(ts))
        assertEquals(basis, ExitTruthArbiter8144.runnerBasis8144(ExitTruthArbiterLive8144.inputsFor8144(ts, 1.3)))
    }

    @Test fun everyNativeLaneNeedsPositionEvidenceAndKeepsItsEntryContract() {
        val born = setOf("MOONSHOT", "SHITCOIN", "EXPRESS", "PROJECT_SNIPER")
        val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
            "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
        for (lane in lanes) {
            assertEquals(lane, lane in born, TradeExitContract8077.runnerFromEntryLane8104(lane, false))
            assertFalse(TradeExitContract8077.runnerFromEntryLane8104(lane, true))
            for (paper in listOf(false, true)) {
                val ts = token(lane, "unproven", 0.0, paper)
                assertReaders(ts, Plan8153.SCALP)
                assertFalse(TradeExitContract8077.runnerFromEntry8104(ts))
                assertFalse(RunnerGrab7967.holdingRunner8018(ts))
            }
        }
    }

    @Test fun contractLanesAndCorePromoteAtTheirExistingVerifiedPeakOnly() {
        for ((lane, threshold) in listOf("MANIPULATED" to 50.0, "DIP_HUNTER" to 50.0, "CORE" to 100.0)) {
            for (paper in listOf(false, true)) {
                for (unproven in listOf(threshold - 0.01, Double.NaN, Double.POSITIVE_INFINITY, 100_000.0)) {
                    assertReaders(token(lane, "below_or_invalid", unproven, paper), Plan8153.SCALP)
                }
                val ts = token(lane, "proven", threshold, paper)
                assertReaders(ts, Plan8153.RUNNER_PROMOTED)
                assertEquals(!paper, TradeExitContract8077.runnerFromEntry8104(ts))
                assertTrue(RunnerGrab7967.holdingRunner8018(ts))
                assertNotNull(RunnerGrab7967.tiersFor7967(ts))
                assertEquals(RunnerPlan8153.Promotion8153.PROMOTE_PROVEN_PEAK,
                    RunnerLive8153.promotion8153(ts, threshold, standingDown = false, nowMs = entryMs + 60_000L))
            }
        }
    }

    @Test fun provenPromotionLatchSurvivesRestoredPeakWithoutPromotingANewLot() {
        for ((lane, threshold) in listOf("MANIPULATED" to 50.0, "DIP_HUNTER" to 50.0, "CORE" to 100.0)) {
            val ts = token(lane, "latched", threshold)
            val verdict = RunnerLive8153.promotion8153(ts, threshold, standingDown = false, nowMs = entryMs + 60_000L)
            assertTrue(RunnerPlan8153.promotes8153(verdict))
            try {
                assertTrue(RunnerLive8153.latchPromotion8153(ts))
                val restored = ts.copy(position = ts.position.copy(peakGainPct = 0.0))
                assertReaders(restored, Plan8153.RUNNER_PROMOTED)
                assertTrue(TradeExitContract8077.runnerFromEntry8104(restored))
                assertTrue(RunnerGrab7967.holdingRunner8018(restored))
                val nextLot = restored.copy(position = restored.position.copy(entryTime = entryMs + 1_000L))
                assertReaders(nextLot, Plan8153.SCALP)
            } finally {
                RunnerLive8153.retire8153(ts.mint, ts.position.entryTime)
            }
        }
    }

    @Test fun aPrintWithoutVerifiedPeakCannotPromoteAndBornRunnerLanesKeepTheirThresholds() {
        for (lane in listOf("MANIPULATED", "DIP_HUNTER", "CORE")) {
            val ts = token(lane, "uncorroborated", 0.0)
            val verdict = RunnerLive8153.promotion8153(ts, 150.0, standingDown = false, nowMs = entryMs + 60_000L)
            assertEquals(RunnerPlan8153.Promotion8153.REFUSE_UNCORROBORATED, verdict)
            assertFalse(RunnerPlan8153.promotes8153(verdict))
            assertReaders(ts, Plan8153.SCALP)
        }
        for ((lane, threshold) in listOf("MOONSHOT" to 100.0, "SHITCOIN" to 50.0,
            "EXPRESS" to 50.0, "PROJECT_SNIPER" to 50.0)) {
            assertReaders(token(lane, "born_below", threshold - 0.01), Plan8153.SCALP)
            val ts = token(lane, "born_proven", threshold)
            assertReaders(ts, Plan8153.RUNNER_PROMOTED)
            assertTrue(TradeExitContract8077.runnerFromEntry8104(ts))
        }
        for (lane in listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "TREASURY")) {
            assertReaders(token(lane, "not_capable", 200.0), Plan8153.SCALP)
        }
    }
}
