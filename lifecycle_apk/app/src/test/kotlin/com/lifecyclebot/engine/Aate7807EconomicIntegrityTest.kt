package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442
import com.lifecyclebot.engine.truth.LearningQuarantineGate6470
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.7807 — EconomicIntegrity: canonical / lot / journal economic integrity.
 */
class Aate7807EconomicIntegrityTest {

    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    private fun openLive(id: String, mint: String, qty: Long) {
        val r = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "BUY:test7807:$id", positionId = id, mint = mint, symbol = "T7807",
            lane = "MOONSHOT", runId = "t7807", entryCostSol = 1.0,
            openedQtyRaw = BigInteger.valueOf(qty), tokenDecimals = 6, feesSol = 0.0,
            paperMode = false, entryPriceUsd = 0.2, entryPriceSource = "TEST_7807",
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, r)
    }

    // ── #6 lot lineage repaired from canonical truth ──────────────────────
    @Test fun `live sell after restart repairs lot lineage from the same canonical positionId`() {
        CanonicalPositionAuthority6441.resetForTest()
        CanonicalLotQuantity6464.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        val id = "LIVE:MINT_LOT_7807:t7807:1"
        openLive(id, "MINT_LOT_7807", 1_000L)
        // Canonical is mutated before the lot hook on the live sell path.
        CanonicalPositionAuthority6441.partialSell(
            idempotencyKey = "SELL:t7807:1", positionId = id, soldQtyRaw = BigInteger.valueOf(400L),
            proceedsSol = 0.5, soldCostBasisSol = 0.4, feesSol = 0.0, paperMode = false,
        )
        // In-memory lot was lost (restart / wallet adoption): no onBuyFilled.
        CanonicalLotQuantity6464.onSellFilled(id, "MINT_LOT_7807", BigInteger.valueOf(400L))
        assertEquals(BigInteger.valueOf(600L), CanonicalLotQuantity6464.sellable(id))
        assertFalse(LearningQuarantineGate6470.isQuarantined(id, "MINT_LOT_7807"))
    }

    @Test fun `true phantom sell with no canonical lineage stays quarantined and creates no lot`() {
        CanonicalPositionAuthority6441.resetForTest()
        CanonicalLotQuantity6464.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        CanonicalLotQuantity6464.onSellFilled("PID_NONE_7807", "MINT_NONE_7807", BigInteger.valueOf(10L))
        assertTrue(LearningQuarantineGate6470.isQuarantined("PID_NONE_7807", "MINT_NONE_7807"))
        assertEquals(BigInteger.ZERO, CanonicalLotQuantity6464.sellable("PID_NONE_7807"))
    }

    @Test fun `confirmed oversell still reduces lot quantity while learning is quarantined`() {
        CanonicalPositionAuthority6441.resetForTest()
        CanonicalLotQuantity6464.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        CanonicalLotQuantity6464.onBuyFilled("PID_OS_7807", "MINT_OS_7807", BigInteger.valueOf(1_000L))
        CanonicalLotQuantity6464.onSellFilled("PID_OS_7807", "MINT_OS_7807", BigInteger.valueOf(5_000L))
        assertTrue(LearningQuarantineGate6470.isQuarantined("PID_OS_7807", null))
        assertEquals(BigInteger.ZERO, CanonicalLotQuantity6464.sellable("PID_OS_7807"))
    }

    // ── #5 landed live buy is never left uncommitted ─────────────────────
    @Test fun `landed live buy with lost reservation seals a fresh canonical OPEN with exact raw qty and real lane`() {
        CanonicalPositionAuthority6441.resetForTest()
        val mint = "MINT_LOST_RES_7807"
        val ok = ExecutorCanonicalMirror6442.mirrorBuyFill(
            mint = mint, actualQtyRaw = BigInteger.valueOf(123_456_789L), actualCostSol = 0.25,
            actualFeesSol = 0.000005, tokenDecimals = 6, paperMode = false,
            actualEntryPriceUsd = 0.0004, actualEntryPriceSource = "LIVE_PROOF_COST_BASIS",
            recoveryLane = "MOONSHOT", recoverySymbol = "LOST",
        )
        assertTrue(ok)
        val pid = ExecutorCanonicalMirror6442.positionIdOf(mint, false)
        val p = CanonicalPositionAuthority6441.getPosition(pid)
        assertNotNull(p)
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.OPEN, p!!.lifecycle)
        assertEquals(BigInteger.valueOf(123_456_789L), p.remainingQtyRaw)
        assertEquals("MOONSHOT", p.lane)
        assertEquals("live", p.mode)
    }

    @Test fun `landed live buy whose pending reservation was TTL-quarantined is recovered not rejected`() {
        CanonicalPositionAuthority6441.resetForTest()
        CanonicalPositionAuthority6441.setPaperCash(10.0, "test7807")
        val mint = "MINT_TTL_RES_7807"
        val pid = ExecutorCanonicalMirror6442.positionIdOf(mint, false)
        CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "BUY:t7807:$pid", positionId = pid, mint = mint, symbol = "TTL",
            lane = "EXPRESS", runId = "t7807", entryCostSol = 0.3, openedQtyRaw = BigInteger.ZERO,
            tokenDecimals = 6, feesSol = 0.0, paperMode = false,
        )
        Thread.sleep(5)
        val cancelled = CanonicalPositionAuthority6441.cancelStalePendingEntries6461(1L)
        assertTrue(cancelled.contains(pid))
        // A LIVE pending row never debited paper cash, so it must not refund it.
        assertEquals(10.0, CanonicalPositionAuthority6441.paperCashSol(), 1e-12)
        val ok = ExecutorCanonicalMirror6442.mirrorBuyFill(
            mint = mint, actualQtyRaw = BigInteger.valueOf(9_000_000L), actualCostSol = 0.3,
            actualFeesSol = 0.0, tokenDecimals = 6, paperMode = false,
            actualEntryPriceUsd = 0.005, actualEntryPriceSource = "LIVE_PROOF_COST_BASIS",
            recoveryLane = "EXPRESS",
        )
        assertTrue(ok)
        val p = CanonicalPositionAuthority6441.getPosition(pid)!!
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.OPEN, p.lifecycle)
        assertEquals(BigInteger.valueOf(9_000_000L), p.remainingQtyRaw)
    }

    @Test fun `paper buy fill keeps strict refusal when reservation is missing`() {
        CanonicalPositionAuthority6441.resetForTest()
        val ok = ExecutorCanonicalMirror6442.mirrorBuyFill(
            mint = "MINT_PAPER_STRICT_7807", actualQtyRaw = BigInteger.valueOf(1_000L), actualCostSol = 0.1,
            actualFeesSol = 0.0, tokenDecimals = 6, paperMode = true,
        )
        assertFalse(ok)
    }

    @Test fun `live executor resolves positionId only after the canonical commit and passes the real lane`() {
        val ex = src("com/lifecyclebot/engine/Executor.kt")
        val block = ex.substringAfter("val canonicalOpen6486 = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.mirrorBuyFill(")
            .substringBefore("LIVE_BUY_CANONICAL_COMMIT_REJECTED_6486")
        assertTrue(block.contains("recoveryLane = ExecutableOpenGate.activeExecutionIntent6519"))
        assertTrue(block.contains("val pidLive6486 = com.lifecyclebot.engine.truth.ExecutorCanonicalMirror6442.positionIdOf(verifyMint, false)"))
        val mirror = src("com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt")
        assertTrue(mirror.contains("CANONICAL_STALE_ACTIVE_POSITION_ID_REALLOCATED_7807"))
    }

    // ── #7 paper ledger realized replay uses ledger semantics ────────────
    @Test fun `paper ledger realized replay includes partial legs and excludes live rows`() {
        EconomicEventSchema6464.resetForTest()
        EconomicEventSchema6464.recordSell(
            mode = "paper", positionId = "PAPER:M7807:1", mint = "M7807", symbol = "M",
            idempotencyKey = "p_partial_7807", partial = true, soldQty = BigInteger.valueOf(50L),
            preRemainingQty = BigInteger.valueOf(100L), preRemainingCostBasisSol = 1.0,
            grossProceedsSol = 0.8, exitFeesSol = 0.01,
        )
        EconomicEventSchema6464.recordSell(
            mode = "live", positionId = "LIVE:M7807:1", mint = "M7807", symbol = "M",
            idempotencyKey = "l_terminal_7807", partial = false, soldQty = BigInteger.valueOf(100L),
            preRemainingQty = BigInteger.valueOf(100L), preRemainingCostBasisSol = 1.0,
            grossProceedsSol = 3.0, exitFeesSol = 0.01,
        )
        val rows = EconomicEventSchema6464.paperLedgerRealizedEvents7807()
        assertEquals(1, rows.size)
        // gross 0.8 − allocated 0.5 = 0.3 (gross realized; fees booked separately)
        assertEquals(0.3, rows.sumOf { it.realizedPnlSol }, 1e-9)
        val ledger = src("com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt")
        assertTrue(ledger.substringAfter("fun rebuildRealizedFromCanonicalEvents6502").contains("paperLedgerRealizedEvents7807()"))
    }

    // ── #8 hero parity probes compare like with like ─────────────────────
    @Test fun `hero parity probes skip live mode and marked equity`() {
        val crypto = src("com/lifecyclebot/ui/CryptoAltActivity.kt")
        assertTrue(crypto.contains("if (!isLive && displayReady6830) com.lifecyclebot.engine.truth.JournalEconomicAuthority6616"))
        val main = src("com/lifecyclebot/ui/MainActivity.kt")
        assertTrue(main.contains(".probeHeroBinding(\"MEME\", displayedCash7045, -1.0)"))
        assertTrue(main.contains("if (accountRenderable7045) com.lifecyclebot.engine.truth.JournalEconomicAuthority6616"))
    }

    // ── #10 / #11 one identity, exact partial quantity, no sibling invention ──
    @Test fun `live partial journal leg carries canonical positionId and exact sold quantity`() {
        val ex = src("com/lifecyclebot/engine/Executor.kt")
        val leg = ex.substringAfter("val liveTrade = Trade(\"PARTIAL_SELL\", \"live\"")
            .substringBefore("recordTrade(ts, liveTrade)")
        assertTrue(leg.contains("positionId = pos.positionId.ifBlank"))
        assertTrue(leg.contains("soldQtyToken = sellQty"))
        assertTrue(leg.contains("remainingQtyToken = newQty.coerceAtLeast(0.0)"))
        assertTrue(leg.contains("canonicalConsumedRaw = expectedConsumedRawForAudit"))
        assertTrue(ex.contains("val ledgerPositionId = canonicalJournalPositionId7807(ts, trade)"))
        assertTrue(ex.contains("val _fanoutPositionId = tradeWithMint.positionId.ifBlank { ledgerPositionId }"))
    }

    @Test fun `live top-up mirrors the verified add into the same canonical position and lot`() {
        val ex = src("com/lifecyclebot/engine/Executor.kt")
        assertTrue(ex.contains("mirrorLiveTopUpCanonical7807(ts.mint, sig, sol, effectiveNewQty, topUpExplicitDecimals)"))
        val fn = ex.substringAfter("private fun mirrorLiveTopUpCanonical7807").substringBefore("private fun buyPhase")
        assertTrue(fn.contains("addToPosition6486("))
        assertTrue(fn.contains("CanonicalLotQuantity6464.onBuyFilled(pid, mint, addedRaw)"))
    }
}
