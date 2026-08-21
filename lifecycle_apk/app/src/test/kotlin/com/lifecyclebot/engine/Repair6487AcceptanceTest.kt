package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalCapitalAuthority6450
import com.lifecyclebot.engine.truth.CanonicalPaperReplay6464
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

    @Test fun streak_defence_tightens_then_denies_without_funded_probe() {
        ExecutableEntryAuthority6450.recordLossForTest6487(1)
        val one = ExecutableEntryAuthority6450.gate("TEST", "M1", 1.0)
        assertEquals(ExecutableEntryAuthority6450.Verdict.ALLOW, one.verdict)
        assertEquals(0.65, one.recommendedSizeSol, 1e-9)
        assertEquals(8, ExecutableEntryAuthority6450.scoreFloorDelta6487())
        ExecutableEntryAuthority6450.resetForTest6487()
        ExecutableEntryAuthority6450.recordLossForTest6487(2)
        val two = ExecutableEntryAuthority6450.gate("TEST", "M2", 1.0)
        assertEquals(0.35, two.recommendedSizeSol, 1e-9)
        assertEquals(15, ExecutableEntryAuthority6450.scoreFloorDelta6487())
        ExecutableEntryAuthority6450.resetForTest6487()
        ExecutableEntryAuthority6450.recordLossForTest6487(3)
        val three = ExecutableEntryAuthority6450.gate("TEST", "M3", 1.0)
        assertEquals(ExecutableEntryAuthority6450.Verdict.DENY_LOSING_STREAK, three.verdict)
        assertEquals(0.0, three.recommendedSizeSol, 0.0)
        assertEquals(0.0, ExecutableEntryAuthority6450.sizeMultiplier6487(), 0.0)
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

}
