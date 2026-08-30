package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class Repair6519ExecutionAuthorityAcceptanceTest {
    @After fun cleanup() {
        ExecutableOpenGate.resetForTests()
        CanonicalPositionAuthority6441.resetForTest()
        PositionStateLedger6454.resetForTest()
        SellQtyBoundaryClamp6427.resetForTest()
        PaperLearningEligibility6519.resetForTest()
    }

    private fun intent(version: Long = 7L, authority: Long = 11L) = ExecutableOpenGate.ExecutionIntent(
        attemptId = "A6519", candidateId = "M6519:$version", candidateVersion = version,
        mint = "M6519", mode = "PAPER", canonicalLane = "WARRIOR", fdgVerdict = "BUY",
        fdgAllowed = true, authorityVersion = authority, resolvedSize = 0.05,
        createdAt = System.currentTimeMillis(), symbol = "WARRIOR", hardNoReasons = emptyList(),
        finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
        decisionAuthorityId6613 = "TEST_FDG:11", fdgDecisionId6613 = "TEST:M6519:7",
        fdgEvidence6613 = "verdict=BUY lane=WARRIOR",
    )

    @Test fun `FDG BUY immutable intent makes UNKNOWN diagnostic not veto`() {
        val i = intent()
        assertFalse(ExecutableOpenGate.mutableSignalCanVeto6519(i, "UNKNOWN"))
        assertFalse(ExecutableOpenGate.mutableSignalCanVeto6519(i, "WAIT"))
        assertTrue(ExecutableOpenGate.mutableSignalCanVeto6519(null, "UNKNOWN"))
    }

    @Test fun `older frozen candidate or authority cannot restore over active BUY`() {
        val i = intent(version = 9L, authority = 20L)
        assertFalse(ExecutableOpenGate.restoreExecStateFromFrozenSnapshot(i, 8L, 20L))
        assertFalse(ExecutableOpenGate.restoreExecStateFromFrozenSnapshot(i, 9L, 19L))
        assertTrue(ExecutableOpenGate.restoreExecStateFromFrozenSnapshot(i, 9L, 20L))
        assertEquals("BUY", i.fdgVerdict)
        assertTrue(i.fdgAllowed)
    }

    @Test fun `position state and sell boundary rebuild from identical canonical open set`() {
        val p = CanonicalPositionAuthority6441.Position(
            positionId="P6519", mode="paper", mint="M6519", symbol="W", lane="WARRIOR",
            runId="R", openedAtMs=1L, entryCostSol=0.05, remainingQtyRaw=BigInteger.TEN,
            originalQtyRaw=BigInteger.TEN, soldCostBasisSol=0.0, realizedPnlSol=0.0,
            realizedProceedsSol=0.0, feesSol=0.0, tokenDecimals=0, quantityScale=0,
            lifecycle=CanonicalPositionAuthority6441.Lifecycle.OPEN, lastMutationMs=1L,
            quarantineReason="", entryPriceUsd=0.005, entryPriceSource="TEST",
        )
        PositionStateLedger6454.syncFromCanonical6519(listOf(p))
        SellQtyBoundaryClamp6427.syncFromCanonical6519(listOf(p))
        assertEquals(1, PositionStateLedger6454.openOrPartialCount6519())
        assertEquals(1, SellQtyBoundaryClamp6427.trackedOpenCount6519())
    }

    @Test fun `unrecoverable zero entry price is quarantined never OPEN`() {
        val bad = EconomicEventSchema6464.Buy(
            atMs=1L, mode="paper", positionId="BAD6519", mint="M6519", symbol="BAD",
            idempotencyKey="B", executedCostSol=0.0, filledQty=BigInteger.ZERO,
            fillPrice=0.0, tokenDecimals=9, quantityScale=9,
        )
        CanonicalPositionAuthority6441.rebuildPaperFromEvents6486(listOf(bad))
        assertEquals(0, CanonicalPositionAuthority6441.openCount())
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.QUARANTINED,
            CanonicalPositionAuthority6441.getPosition("BAD6519")?.lifecycle)
    }
}
