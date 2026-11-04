package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.RunnerExitContract8136.Mark8136
import com.lifecyclebot.engine.RunnerExitContract8136.Policy8136
import com.lifecyclebot.engine.RunnerReplay8153.Mark8153
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Live runner actuator, chronological replay and forward grading must not use contradictory exits. */
class Aate8527RunnerFloorLearningAlignmentTest {
    private val style = "DIAMOND_HANDS_RUNNER"
    private fun runner(now: Long, ageMs: Long = 120_000L, peak: Double = 100.0): TokenState {
        val mint = "Floor8527" + System.nanoTime()
        return TokenState(mint, symbol = "RUNNER").apply {
            lastPrice = 1.0
            lastMcap = 1_000_000.0
            lastLiquidityUsd = 1_000_000.0
            position = Position(qtyToken = 100.0, entryPrice = 1.0, entryTime = now - ageMs,
                costSol = 1.0, highestPrice = 1.0 + peak / 100.0, peakGainPct = peak,
                isPaperPosition = false, tradingMode = "MOONSHOT",
                entryPolicySnapshot = "style=$style", positionId = "LIVE:$mint:8527")
        }
    }

    @Test fun actualHeldExitDoesNotSellInsideTheSharedFloor() {
        val now = System.currentTimeMillis()
        val ts = runner(now)
        val exits = mutableListOf<Pair<Double, String>>()
        assertEquals(40.0, GreenFloor8363.floorFor8363(ts), 1e-9)
        // The same corroborated peak reaches the real live actuator before the retreat.
        RunnerGrab7967.heldExit7967(ts, 2.0, now) { _, f, why -> exits += f to why }
        RunnerGrab7967.heldExit7967(ts, 1.45, now + 1_000L) { _, f, why -> exits += f to why }
        assertTrue("Old +50% half-peak branch must not pre-empt the +40% shared floor: $exits", exits.isEmpty())
        RunnerGrab7967.heldExit7967(ts, 1.39, now + 2_000L) { _, f, why -> exits += f to why }
        assertEquals(1, exits.size)
        assertEquals(1.0, exits.single().first, 0.0)
        assertTrue(exits.single().second.startsWith("RUNNER_GREEN_FLOOR_8363"))
    }

    @Test fun actualHardCollapseStillExitsAndFirstMinuteConfirmationRuleIsUnchanged() {
        val now = System.currentTimeMillis()
        val mature = runner(now)
        val exits = mutableListOf<String>()
        RunnerGrab7967.heldExit7967(mature, 0.5, now) { _, f, why ->
            assertEquals(1.0, f, 0.0); exits += why
        }
        assertTrue(exits.single().startsWith("RUNNER_HARD_STOP_7967"))
        assertTrue(RunnerGrab7967.hardStopWaits8337(-40.0, 10_000L, false))
        assertFalse(RunnerGrab7967.hardStopWaits8337(-81.0, 10_000L, false))
        assertFalse(RunnerGrab7967.hardStopWaits8337(-40.0, 10_000L, true))
    }

    @Test fun currentChronologicalReplaysHold45ThenSellOnlyAtAnObservedCrossing() {
        val held = listOf(Mark8153(1.0), Mark8153(2.0), Mark8153(1.45))
        val before = RunnerReplay8153.replay8153(held, style, sellCostPct = 0.0)
        assertTrue(before.residualShare > 0.0)
        assertTrue(before.exits.none { it.contains("GIVEBACK_8104") || it.contains("GREEN_FLOOR") })
        val crossed = RunnerReplay8153.replay8153(
            held + Mark8153(1.39, execPx = 1.35, verifiedPx = 1.39), style, sellCostPct = 0.0)
        assertTrue(crossed.exits.last().startsWith("RUNNER_GREEN_FLOOR_8363"))
        assertEquals(0.0, crossed.residualShare, 1e-12)
        // Actual executable mark, not the theoretical +40% floor, prices the remaining fill.
        assertEquals(before.proceeds + before.residualShare * 1.35, crossed.proceeds, 1e-12)
        assertEquals(crossed.proceeds, crossed.bookedProceeds, 1e-12)

        val path = listOf(Mark8136(1.0), Mark8136(2.0), Mark8136(1.45))
        val current = RunnerExitContract8136.replay8136(path, Policy8136.CONTRACT_8136, style, 0.0)
        assertTrue(current.exits.none { it.contains("GIVEBACK_8104") || it.contains("GREEN_FLOOR") })
        val after = RunnerExitContract8136.replay8136(path + Mark8136(1.39), Policy8136.CONTRACT_8136, style, 0.0)
        assertTrue(after.exits.any { it.contains("RUNNER_GREEN_FLOOR_8363") })
        // Intentionally historical comparison stays historical, not an active exit authority.
        val legacy = RunnerExitContract8136.replay8136(path, Policy8136.LEGACY_LANE_RUNNER, style, 0.0)
        assertTrue(legacy.exits.any { it.contains("RUNNER_FROM_ENTRY_GIVEBACK_8104") })
    }

    @Test fun failedFloorSaleCannotInventProceedsAndEmergencyStillOverrides() {
        val held = listOf(Mark8153(1.0), Mark8153(2.0), Mark8153(1.45))
        val before = RunnerReplay8153.replay8153(held, style, sellCostPct = 0.0)
        val failed = RunnerReplay8153.replay8153(held + Mark8153(1.39, sellFails = true),
            style, sellCostPct = 0.0)
        assertEquals(before.proceeds, failed.proceeds, 1e-12)
        assertEquals(before.residualShare, failed.residualShare, 1e-12)
        assertTrue(failed.exits.last().startsWith("UNFILLED:RUNNER_GREEN_FLOOR"))
        val emergency = RunnerReplay8153.replay8153(held + Mark8153(0.5, emergency = true),
            style, sellCostPct = 0.0)
        assertEquals(0.0, emergency.residualShare, 1e-12)
        assertTrue(emergency.exits.last().startsWith("LIQUIDITY_DRAIN_EXIT"))
    }

    @Test fun summaryLabelsNeverInventHalfPeakOrSharedFloorFills() {
        for (peak in listOf(40.0, 100.0, 120.0, 199.0, 500.0)) {
            for (observed in listOf(-50.0, -5.0, 39.0, 45.0, 90.0)) {
                assertEquals(observed, ForwardReturnLabeler7731.runnerRest8106(true, peak, observed), 0.0)
                assertEquals(observed, ForwardReturnLabeler7731.runnerRest8106(false, peak, observed), 0.0)
            }
        }
        assertTrue(ForwardReturnLabeler7731.runnerRest8106(true, 120.0, Double.NaN).isNaN())
    }

    @Test fun repairedLabelContractStartsFreshAuthorityAndIsWiredToTheActiveProducer() {
        fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
        val live = src("engine/RunnerGrab7967.kt").substringAfter("fun heldExit7967(").substringBefore("// ── missed-runner audit")
        assertFalse(live.contains("fromEntryGiveback8104("))
        assertTrue(live.contains("GreenFloor8363.breachedFor8363(ts, g)"))
        assertFalse(src("engine/RunnerReplay8153.kt").contains("fromEntryGiveback8104("))
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("GRADING_VERSION_8169 = 8527"))
        assertTrue(f.contains("FRESH_KEY_8169 = \"fresh_cells_8527\""))
        assertTrue(f.contains("runnerRest8106(true, minOf(pk, held8366), rest)"))
        assertFalse(f.contains("fromEntryGiveback8104("))
    }
}
