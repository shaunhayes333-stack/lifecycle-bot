package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6344 — Immutable FillLotLedger invariant test suite.
 * Verifies the append-only guarantees required by the operator's P0-2 spec.
 */
class FillLotLedger6344Test {

    @Before
    fun setup() {
        FillLotLedger6344.resetForTest()
    }

    @Test
    fun primary_key_is_wallet_plus_mint_plus_buyTxSig() {
        val k = FillLotLedger6344.key("Wall", "MintA", "SigX")
        assertEquals("Wall|MintA|SigX", k)
    }

    @Test
    fun appendBuy_first_write_wins_second_is_idempotent() {
        val first = FillLotLedger6344.appendBuy(
            walletAddress = "W1",
            mintAddress = "M1",
            buyTxSig = "S1",
            entryCostSol = SolAmount.of(0.010),
            entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6,
            laneCanonical = "STANDARD",
            entryTsMs = 1000L,
        )!!
        val second = FillLotLedger6344.appendBuy(
            walletAddress = "W1",
            mintAddress = "M1",
            buyTxSig = "S1",
            entryCostSol = SolAmount.of(0.999),           // attempt to mutate
            entryQty = TokenQuantity.of(9999.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.9),
            entryPriceUsdPerToken = PriceUsdPerToken.of(1.0),
            decimals = 6,
            laneCanonical = "MOONSHOT",
            entryTsMs = 2000L,
        )!!
        // Both calls return the ORIGINAL row — no mutation.
        assertEquals(0.010, first.entryCostSol, 1e-12)
        assertEquals(0.010, second.entryCostSol, 1e-12)
        assertEquals(100.0, second.entryQty, 1e-12)
        assertEquals("STANDARD", second.laneCanonical)
        assertEquals(1000L, second.entryTsMs)
    }

    @Test
    fun appendBuy_rejects_blank_key() {
        assertNull(FillLotLedger6344.appendBuy(
            walletAddress = "", mintAddress = "M", buyTxSig = "S",
            entryCostSol = SolAmount.of(1.0), entryQty = TokenQuantity.of(1.0),
            entryPriceSolPerToken = PriceSolPerToken.of(1.0),
            entryPriceUsdPerToken = PriceUsdPerToken.of(1.0),
            decimals = 6, laneCanonical = "X", entryTsMs = 0L,
        ))
        assertNull(FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "", buyTxSig = "S",
            entryCostSol = SolAmount.of(1.0), entryQty = TokenQuantity.of(1.0),
            entryPriceSolPerToken = PriceSolPerToken.of(1.0),
            entryPriceUsdPerToken = PriceUsdPerToken.of(1.0),
            decimals = 6, laneCanonical = "X", entryTsMs = 0L,
        ))
        assertNull(FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "",
            entryCostSol = SolAmount.of(1.0), entryQty = TokenQuantity.of(1.0),
            entryPriceSolPerToken = PriceSolPerToken.of(1.0),
            entryPriceUsdPerToken = PriceUsdPerToken.of(1.0),
            decimals = 6, laneCanonical = "X", entryTsMs = 0L,
        ))
    }

    @Test
    fun appendSell_accumulates_and_flags_terminal_when_fully_sold() {
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            entryCostSol = SolAmount.of(0.010), entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 0L,
        )
        val afterFirst = FillLotLedger6344.appendSell(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "S1", soldQty = TokenQuantity.of(40.0),
            proceedsSol = SolAmount.of(0.006), feeSol = SolAmount.of(0.0001),
            sellTsMs = 100L, proofSource = "LIVE_FINALIZED",
        )!!
        assertEquals(40.0, afterFirst.cumulativeSoldQty, 1e-9)
        assertEquals(60.0, afterFirst.remainingQty, 1e-9)
        assertFalse(afterFirst.isTerminal(1e-6))
        val afterSecond = FillLotLedger6344.appendSell(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "S2", soldQty = TokenQuantity.of(60.0),
            proceedsSol = SolAmount.of(0.009), feeSol = SolAmount.of(0.0001),
            sellTsMs = 200L, proofSource = "LIVE_FINALIZED",
        )!!
        assertEquals(100.0, afterSecond.cumulativeSoldQty, 1e-9)
        assertEquals(0.0, afterSecond.remainingQty, 1e-9)
        assertTrue(afterSecond.isTerminal(1e-6))
    }

    @Test
    fun appendSell_is_idempotent_by_sellTxSig() {
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            entryCostSol = SolAmount.of(0.010), entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 0L,
        )
        FillLotLedger6344.appendSell(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "SIG_A", soldQty = TokenQuantity.of(40.0),
            proceedsSol = SolAmount.of(0.006), feeSol = SolAmount.ZERO,
            sellTsMs = 100L, proofSource = "LIVE_FINALIZED",
        )
        val second = FillLotLedger6344.appendSell(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "SIG_A", soldQty = TokenQuantity.of(999.0),   // re-attribution attempt
            proceedsSol = SolAmount.of(9.9), feeSol = SolAmount.ZERO,
            sellTsMs = 200L, proofSource = "LIVE_FINALIZED",
        )!!
        // Second call must not double-count the same sell signature.
        assertEquals(40.0, second.cumulativeSoldQty, 1e-9)
        assertEquals(1, second.sells.size)
    }

    @Test
    fun latestOpenLot_returns_most_recent_non_terminal() {
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B1",
            entryCostSol = SolAmount.of(0.010), entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 100L,
        )
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B2",
            entryCostSol = SolAmount.of(0.020), entryQty = TokenQuantity.of(200.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 200L,
        )
        assertEquals("B2", FillLotLedger6344.latestOpenLot("W", "M")!!.buyTxSig)
    }
}
