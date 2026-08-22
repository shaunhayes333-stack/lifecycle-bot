package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450
import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class Repair6487AcceptanceTest {
    @Before fun reset() {
        PaperAccountLedger6430.resetForTest()
        EconomicEventSchema6464.resetForTest()
        CanonicalPaperReplay6464.resetForTest()
        ExecutableEntryAuthority6450.resetForTest6487()
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
    }

    @Test fun paper_replay_and_capital_surfaces_match_canonical_ledger() {
        val start = 10.0
        PaperAccountLedger6430.initialize(start)
        assertTrue(PaperAccountLedger6430.onBuy(1.0, 0.01))
        EconomicEventSchema6464.recordBuy(
            mode = "paper", positionId = "P1", mint = "M1", symbol = "ONE",
            idempotencyKey = "B1", executedCostSol = 1.0,
            filledQty = BigInteger.valueOf(1_000), fillPrice = 0.001, entryFeesSol = 0.01,
        )
        assertTrue(PaperAccountLedger6430.onSell(1.2, 1.0, 0.02))
        EconomicEventSchema6464.recordSell(
            mode = "paper", positionId = "P1", mint = "M1", symbol = "ONE",
            idempotencyKey = "S1", partial = false,
            soldQty = BigInteger.valueOf(1_000), preRemainingQty = BigInteger.valueOf(1_000),
            preRemainingCostBasisSol = 1.0, grossProceedsSol = 1.2, exitFeesSol = 0.02,
        )
        val parity = CanonicalPaperReplay6464.compareToLedger(start, 1e-8)
        assertEquals(0.0, parity.cashDelta, 1e-8)
        assertEquals(0.0, parity.realizedDelta, 1e-8)
        assertEquals(0.0, parity.openCostDelta, 1e-8)
        assertNull(PaperAccountLedger6430.assertInvariant(1e-8))
        val capital = CanonicalCapitalAuthority6450.snapshot { 0.0 }
        assertEquals(PaperAccountLedger6430.cashSol(), capital.cashSol, 1e-8)
        assertEquals(PaperAccountLedger6430.realizedPnlSol(), capital.realizedPnlSol, 1e-8)
        assertEquals(PaperAccountLedger6430.openCostBasisSol(), capital.openCostBasisSol, 1e-8)
        assertEquals(PaperAccountLedger6430.feesSol(), capital.feesSol, 1e-8)
        assertEquals(0.0, capital.conservationDeltaSol, 1e-8)
    }

    @Test fun streak_defence_is_mode_lane_scoped_and_never_zero_sizes() {
        ExecutableEntryAuthority6450.recordLossForTest6487(3, lane = "SHITCOIN", mode = "PAPER")
        val toxic = ExecutableEntryAuthority6450.gate("SHITCOIN", "M1", 1.0)
        assertEquals(ExecutableEntryAuthority6450.Verdict.ALLOW, toxic.verdict)
        assertEquals(0.35, toxic.recommendedSizeSol, 1e-9)
        assertEquals(15, ExecutableEntryAuthority6450.scoreFloorDeltaFor6488("SHITCOIN", "PAPER"))
        assertEquals(0.35, ExecutableEntryAuthority6450.sizeMultiplierFor6488("SHITCOIN", "PAPER"), 0.0)

        val profitableLane = ExecutableEntryAuthority6450.gate("BLUECHIP", "M2", 1.0)
        assertEquals(ExecutableEntryAuthority6450.Verdict.ALLOW, profitableLane.verdict)
        assertEquals(1.0, profitableLane.recommendedSizeSol, 1e-9)
        assertEquals(0L, ExecutableEntryAuthority6450.consecutiveLossesFor6488("BLUECHIP", "PAPER"))
        assertEquals(0L, ExecutableEntryAuthority6450.consecutiveLossesFor6488("SHITCOIN", "LIVE"))

        assertEquals(0, ExecutableEntryAuthority6450.scoreFloorDelta6487())
        assertEquals(1.0, ExecutableEntryAuthority6450.sizeMultiplier6487(), 0.0)
    }

    @Test fun shadow_lanes_never_publish_fdg_tickets() {
        listOf("V3_CORE", "STANDARD", "CASHGEN").forEachIndexed { i, lane ->
            val mint = "Shadow${i}11111111111111111111111111111111"
            ExecutableOpenGate.recordFdg(mint, "SHD", lane, true, null, signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 3000.0)
            assertNull(ExecutableOpenGate.recentAllowedAttemptId(mint, lane))
        }
    }

    @Test fun background_watchdog_distinguishes_dead_stalled_healthy_and_manual_stop() {
        assertEquals(
            ServiceWatchdog.RecoveryAction6487.START_SERVICE,
            ServiceWatchdog.recoveryAction6487(true, false, false, false),
        )
        assertEquals(
            ServiceWatchdog.RecoveryAction6487.HEARTBEAT_RESCUE,
            ServiceWatchdog.recoveryAction6487(true, false, true, false),
        )
        assertEquals(
            ServiceWatchdog.RecoveryAction6487.NONE,
            ServiceWatchdog.recoveryAction6487(true, false, true, true),
        )
        assertEquals(
            ServiceWatchdog.RecoveryAction6487.NONE,
            ServiceWatchdog.recoveryAction6487(true, true, false, false),
        )
    }


    @Test fun canonical_registry_projection_is_atomic_and_uses_remaining_partial_basis() {
        val suffix = System.nanoTime().toString()
        val mint = "Registry6488$suffix"
        val positionId = "REG6488:$suffix"
        assertEquals(
            CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition(
                idempotencyKey = "OPEN:$suffix", positionId = positionId,
                mint = mint, symbol = "R6488", lane = "BLUECHIP", runId = suffix,
                entryCostSol = 2.0, openedQtyRaw = BigInteger.valueOf(100L),
                tokenDecimals = 0, feesSol = 0.0, paperMode = false, modeOverride = "paper",
            ),
        )
        EmergentGuardrails.rebuildFromCanonical6475(CanonicalPositionAuthority6441.openPositions())
        assertEquals(BigInteger.valueOf(100L), EmergentGuardrails.snapshot()[mint]?.qtyRaw)
        assertEquals(2.0, EmergentGuardrails.snapshot()[mint]?.entryCostSol ?: -1.0, 1e-9)

        assertEquals(
            CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.partialSell(
                "PARTIAL:$suffix", positionId, BigInteger.valueOf(40L),
                proceedsSol = 1.0, soldCostBasisSol = 0.8, feesSol = 0.0, paperMode = false,
            ),
        )
        EmergentGuardrails.rebuildFromCanonical6475(CanonicalPositionAuthority6441.openPositions())
        assertEquals(BigInteger.valueOf(60L), EmergentGuardrails.snapshot()[mint]?.qtyRaw)
        assertEquals(1.2, EmergentGuardrails.snapshot()[mint]?.entryCostSol ?: -1.0, 1e-9)

        assertEquals(
            CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.partialSell(
                "CLOSE:$suffix", positionId, BigInteger.valueOf(60L),
                proceedsSol = 1.4, soldCostBasisSol = 1.2, feesSol = 0.0, paperMode = false,
            ),
        )
        EmergentGuardrails.rebuildFromCanonical6475(CanonicalPositionAuthority6441.openPositions())
        assertFalse(EmergentGuardrails.snapshot().containsKey(mint))
    }


    @Test fun legacy_losing_streak_reflex_is_cohort_telemetry_not_global_veto() {
        com.lifecyclebot.engine.truth.LosingStreakReflex6439.reset()
        repeat(3) { i ->
            com.lifecyclebot.engine.truth.LosingStreakReflex6439.onTradeClosed(
                realizedSolDelta = -0.1, mint = "LOSS$i", mode = "paper", lane = "SHITCOIN",
            )
        }
        assertTrue(com.lifecyclebot.engine.truth.LosingStreakReflex6439.cooldownRemainingSec("SHITCOIN", "paper") > 0L)
        assertEquals(0L, com.lifecyclebot.engine.truth.LosingStreakReflex6439.cooldownRemainingSec("BLUECHIP", "paper"))
        @Suppress("DEPRECATION")
        assertFalse(com.lifecyclebot.engine.truth.LosingStreakReflex6439.shouldBlockNewBuys())
    }

}
