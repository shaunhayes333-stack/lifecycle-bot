package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** 8835 device forensic: aborted QUALITY BUY was replayed as a phantom second lot. */
class Aate8836PaperSettlementRaceTest {
    private fun pendingOrOpen(lifecycle: CanonicalPositionAuthority6441.Lifecycle, ageMs: Long) =
        CanonicalPositionAuthority6441.Position(
            positionId = "PAPER:SETTLEMENT_TEST", mode = "paper", mint = "mint-8836",
            symbol = "SET", lane = "QUALITY", runId = "unit", openedAtMs = 100_000L,
            entryCostSol = 0.107333, remainingQtyRaw = if (lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN) BigInteger.ONE else BigInteger.ZERO,
            originalQtyRaw = if (lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN) BigInteger.ONE else BigInteger.ZERO,
            soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0,
            feesSol = 0.0, tokenDecimals = 6, lifecycle = lifecycle,
            lastMutationMs = 100_000L - ageMs, quarantineReason = "",
        )

    @Test fun `sweep protects a funded-cost paper reservation throughout settlement`() {
        val now = 100_000L
        for (state in listOf(CanonicalPositionAuthority6441.Lifecycle.PENDING_ENTRY, CanonicalPositionAuthority6441.Lifecycle.OPEN)) {
            assertFalse(CanonicalPositionAuthority6441.eligibleForUnfundedPaperHeal8836(pendingOrOpen(state, 0L), now))
            assertFalse(CanonicalPositionAuthority6441.eligibleForUnfundedPaperHeal8836(pendingOrOpen(state, 20_000L), now))
            assertTrue(CanonicalPositionAuthority6441.eligibleForUnfundedPaperHeal8836(pendingOrOpen(state, 61_000L), now))
        }
    }

    @Test fun `aborted zero-cost or terminal rows never get permanent grace`() {
        val open = pendingOrOpen(CanonicalPositionAuthority6441.Lifecycle.OPEN, 0L)
        assertTrue(CanonicalPositionAuthority6441.eligibleForUnfundedPaperHeal8836(open.copy(entryCostSol = 0.0), 100_000L))
        assertFalse(CanonicalPositionAuthority6441.eligibleForUnfundedPaperHeal8836(open.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED), 100_000L))
        assertFalse(CanonicalPositionAuthority6441.eligibleForUnfundedPaperHeal8836(open.copy(positionId = "LIVE:SETTLEMENT_TEST"), 100_000L))
    }

    @Test fun `durable paper buy is written only after rollback capable proofs`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
            .substringAfter("// V5.0.6485 — ATOMIC PAPER BUY COMMIT.")
            .substringBefore("persistPaperEntrySnapshot8253(ts, tradeId")
        val verify = src.indexOf("if (!positionDidOpen(ts))")
        val eventProof = src.indexOf("CanonicalEconomicEvent6635.openEvent(canonicalEvent6485)")
        val fillProof = src.indexOf("if (fillLotRow6485 <= 0L)")
        val journalBuy = src.indexOf("EconomicEventSchema6464.recordBuy(")
        assertTrue("position proof must precede journal", verify >= 0 && verify < journalBuy)
        assertTrue("canonical event must precede journal", eventProof >= 0 && eventProof < journalBuy)
        assertTrue("fill-lot proof must precede journal", fillProof >= 0 && fillProof < journalBuy)
        assertTrue("commit stamps must precede journal", src.indexOf("PAPER_BUY_ATOMIC_COMMIT_6485") < journalBuy)
        assertTrue("BUY publication must be the final normal-path stage", journalBuy < src.lastIndexOf("} catch (t: Throwable)"))
    }
}
