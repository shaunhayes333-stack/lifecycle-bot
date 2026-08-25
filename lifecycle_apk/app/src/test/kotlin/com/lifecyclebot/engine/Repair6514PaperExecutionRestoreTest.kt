package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PaperTokenQuantityAuthority6509
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Repair6514PaperExecutionRestoreTest {
    @Before fun reset() {
        CanonicalPositionAuthority6441.resetForTest()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(1.0)
    }

    @After fun cleanup() {
        ExecutionAttemptLease.forceReleaseForMint("PaperUnknownDecimals6514", "test_cleanup")
    }

    @Test
    fun paper_unknown_decimals_opens_canonical_position_debits_ledger_and_records_buy_event() {
        val mint = "PaperUnknownDecimals6514"
        val qtyToken = (0.05 * 150.0) / 0.00075
        val metadataDecimals = PaperQuantityRepresentation6514.metadataDecimals(null)
        val accountingScale = PaperQuantityRepresentation6514.accountingScale(null)
        val qtyRaw = PaperTokenQuantityAuthority6509.encode(qtyToken, accountingScale)
        assertEquals(-1, metadataDecimals)
        assertEquals(PaperQuantityRepresentation6514.DECIMAL_NEUTRAL_STORAGE_SCALE, accountingScale)
        assertTrue(qtyRaw > java.math.BigInteger.ZERO)
        assertEquals(qtyToken, PaperTokenQuantityAuthority6509.decode(qtyRaw, accountingScale), 1e-8)

        val result = CanonicalPaperTransaction6486.open(
            positionId = "PAPER:$mint:6514",
            mint = mint,
            symbol = "P6514",
            lane = "SHITCOIN",
            source = "Repair6514",
            costSol = 0.05,
            feeSol = 0.00025,
            qtyRaw = qtyRaw,
            decimals = metadataDecimals,
            entryScore = 80,
            quantityScale = accountingScale,
        )

        assertTrue(result.reason, result.applied)
        val opened = requireNotNull(CanonicalPositionAuthority6441.getPosition(result.positionId))
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.OPEN, opened.lifecycle)
        assertEquals(-1, opened.tokenDecimals)
        assertEquals(accountingScale, opened.quantityScale)
        assertEquals(qtyRaw, opened.remainingQtyRaw)
        assertEquals(0.94975, PaperAccountLedger6430.cashSol(), 1e-9)
        assertEquals(0.05, PaperAccountLedger6430.openCostBasisSol(), 1e-9)
        assertTrue(EconomicEventSchema6464.snapshot().any {
            it is EconomicEventSchema6464.Buy && it.mint == mint && it.filledQty == qtyRaw
        })

        val cashAfterFirst = PaperAccountLedger6430.cashSol()
        val duplicate = CanonicalPaperTransaction6486.open(
            positionId = "PAPER:$mint:6514:duplicate",
            mint = mint, symbol = "P6514", lane = "MOONSHOT", source = "Repair6514.duplicate",
            costSol = 0.05, feeSol = 0.00025, qtyRaw = qtyRaw,
            decimals = metadataDecimals, quantityScale = accountingScale,
        )
        assertFalse("same-mint second PAPER open must remain suppressed", duplicate.applied)
        assertTrue(duplicate.reason.contains("DUPLICATE") || duplicate.reason.contains("POSITION"))
        assertEquals("duplicate suppression must not double debit cash", cashAfterFirst, PaperAccountLedger6430.cashSol(), 1e-9)
        assertEquals(1, CanonicalPositionAuthority6441.openPositions().count { it.mode == "paper" && it.mint == mint })
    }

    @Test
    fun nonterminal_execution_lease_is_cleared_immediately_not_by_expiry() {
        val mint = "PaperUnknownDecimals6514"
        val lease = ExecutionAttemptLease.acquire("BUY", mint, "P6514", "PAPER_BUY_SHITCOIN", "PAPER", 6514L, 20_000L)
        assertTrue(lease.allowed)
        assertTrue(ExecutionAttemptLease.isActiveKey6514(lease.key))
        ExecutionAttemptLease.releaseNonTerminal(lease.key, "BUY", mint, "P6514", "PAPER_BUY_NONTERMINAL_DEFERRED_TEST_6514")
        assertFalse(ExecutionAttemptLease.isActiveKey6514(lease.key))
    }

    @Test
    fun unknown_decimal_accounting_scale_survives_canonical_event_replay() {
        val mint = "ReplayUnknownDecimals6514"
        val qtyRaw = PaperTokenQuantityAuthority6509.encode(12_345.6789, PaperQuantityRepresentation6514.DECIMAL_NEUTRAL_STORAGE_SCALE)
        val event = EconomicEventSchema6464.Buy(
            atMs = 6514L, mode = "paper", positionId = "PAPER:$mint:6514", mint = mint,
            symbol = "R6514", idempotencyKey = "BUY6514:$mint", executedCostSol = 0.05,
            entryFeesSol = 0.00025, filledQty = qtyRaw, fillPrice = 0.05 / 12_345.6789,
            tokenDecimals = -1, quantityScale = PaperQuantityRepresentation6514.DECIMAL_NEUTRAL_STORAGE_SCALE,
        )
        CanonicalPositionAuthority6441.rebuildPaperFromEvents6486(listOf(event))
        val replayed = requireNotNull(CanonicalPositionAuthority6441.getPosition(event.positionId))
        assertEquals(-1, replayed.tokenDecimals)
        assertEquals(PaperQuantityRepresentation6514.DECIMAL_NEUTRAL_STORAGE_SCALE, replayed.quantityScale)
        assertEquals(12_345.6789, PaperTokenQuantityAuthority6509.decode(replayed.remainingQtyRaw, replayed.quantityScale), 1e-8)
    }

}
