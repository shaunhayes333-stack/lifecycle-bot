package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Repair6522CanonicalIntegrityAcceptanceTest {
    @Before fun reset() {
        CanonicalPositionAuthority6441.resetForTest()
        TerminalMutationAuthority6466.resetForTest()
        TerminalSellIdempotency6464.resetForTest()
        EconomicEventSchema6464.resetForTest()
        CanonicalPositionAuthority6441.setPaperCash(10.0, "6522-test")
    }

    private fun open(raw: Long = 334_200L): CanonicalPositionAuthority6441.Position {
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.openPosition("buy-1", "pid-1", "mint-1", "T", "SHITCOIN", "run", 0.010,
                BigInteger.valueOf(raw), 6, 0.0, true, entryPriceUsd = 0.00001, quantityScale = 6))
        return CanonicalPositionAuthority6441.getPosition("pid-1")!!
    }

    @Test fun A_buy_raw_is_the_only_full_sell_quantity() {
        val p = open()
        assertEquals(BigInteger.valueOf(334_200L), p.remainingQtyRaw)
        val amount = CanonicalTokenAmount(p.remainingQtyRaw, p.quantityScale)
        assertEquals(BigInteger.valueOf(334_200L), amount.rawRoundTrip())
        assertNotEquals(BigInteger.valueOf(3_456L), amount.raw)
        assertNotEquals(BigInteger("334200000000000000"), amount.raw)
    }

    @Test fun B_simultaneous_full_close_intents_claim_once() {
        val p = open()
        val event = TerminalMutationAuthority6466.TerminalEvent(p.positionId, p.mint, p.symbol, "paper", p.openedAtMs,
            TerminalMutationAuthority6466.FULL_CLOSE_SEQUENCE_6522, "ignored", "STOP")
        assertEquals(TerminalMutationAuthority6466.ClaimResult.GRANTED, TerminalMutationAuthority6466.claim(event))
        assertEquals(TerminalMutationAuthority6466.ClaimResult.ALREADY_FINALIZED, TerminalMutationAuthority6466.claim(event.copy(exitReason = "DEX_REFRESH")))
        assertEquals("paper|pid-1|${p.openedAtMs}|FULL_CLOSE", TerminalMutationAuthority6466.buildKey("paper", p.positionId, p.openedAtMs, TerminalMutationAuthority6466.FULL_CLOSE_SEQUENCE_6522))
    }

    @Test fun C_partial_then_full_counts_one_completed_generation() {
        val p = open()
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.partialSell("partial-1", p.positionId, BigInteger.valueOf(83_550L), 0.003, 0.0025, 0.0, true))
        assertEquals(0, CanonicalPositionAuthority6441.closedPositions().size)
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.partialSell("full-1", p.positionId, BigInteger.valueOf(250_650L), 0.009, 0.0075, 0.0, true))
        assertEquals(1, CanonicalPositionAuthority6441.closedPositions().distinctBy { "${it.positionId}|${it.openedAtMs}" }.size)
    }

    @Test fun D_report_snapshot_has_one_frozen_revision() {
        val s = PipelineHealthCollector.snapshot()
        assertEquals(s.reportRevision, s.canonicalTradeCounts.revision)
    }

    @Test fun E_usd_mark_requires_sol_usd_conversion() {
        val sol = CanonicalPriceDomains6522.priceSol(PriceUsd(BigDecimal("2.00")), SolUsd(BigDecimal("200.00")))
        assertEquals(0, BigDecimal("0.01").compareTo(sol.value))
        val value = CanonicalPriceDomains6522.valueSol(CanonicalTokenAmount(BigInteger.valueOf(334_200L), 6), sol)
        assertEquals(0, BigDecimal("0.003342").compareTo(value))
    }

    @Test fun F_decimal_skew_blocks_before_any_position_mutation() {
        val p = open()
        val before = CanonicalPositionAuthority6441.getPosition(p.positionId)!!
        val verdict = CanonicalSellQuantityGuard6522.validate("paper", p.positionId, p.openedAtMs, p.mint,
            BigInteger.valueOf(334_200L), 9, p.remainingQtyRaw, true)
        assertFalse(verdict.allowed)
        assertEquals("QTY_DECIMAL_SKEW", verdict.reason)
        assertEquals(before, CanonicalPositionAuthority6441.getPosition(p.positionId))
        assertEquals(0, CanonicalPositionAuthority6441.closedPositions().size)
    }

    @Test fun mark_price_validity_is_independent_from_missing_liquidity_and_orientation_is_mint_bound() {
        val gate = MarkAuthorityIntegrityGate6496.evaluate("mint-1", 0.00001, 0.0, 0.0, "DEXSCREENER_PAIR_POLL", "pair-real", true)
        assertTrue(gate.priceAuthoritative)
        assertFalse(gate.routeExecutable)
        assertFalse(MarkAuthorityIntegrityGate6496.evaluate("mint-1", 0.00001, 0.0, 0.0, "DEXSCREENER_PAIR_POLL", "MINT_ROUTE:mint-1", true).priceAuthoritative)
        assertTrue(CanonicalPriceMarkRegistry6522.publish(CanonicalPriceMark6522("mint-1", "pair-real", "mint-1", "So111", "DEXSCREENER_PAIR_POLL", 1L, PriceUsd(BigDecimal("0.00001")), null)))
        assertFalse(CanonicalPriceMarkRegistry6522.publish(CanonicalPriceMark6522("mint-1", "pair-wrong", "other-mint", "So111", "DEXSCREENER_PAIR_POLL", 2L, PriceUsd(BigDecimal("9")), null)))
        assertEquals(BigDecimal("0.00001"), CanonicalPriceMarkRegistry6522.get("mint-1")!!.priceUsd.value)
    }

    @Test fun corrupted_terminal_sell_is_quarantined_from_replay() {
        EconomicEventSchema6464.recordBuy("paper", "pid-x", "mint-x", "X", "buy-x", 0.010, BigInteger.valueOf(334_200L), 0.0, tokenDecimals = 6, quantityScale = 6)
        EconomicEventSchema6464.recordSell("paper", "pid-x", "mint-x", "X", "bad-terminal", false, BigInteger.valueOf(3_456L), BigInteger.valueOf(334_200L), 0.010, 0.011, 0.0)
        val replay = CanonicalPaperReplay6464.replay(1.0)
        assertEquals(1, replay.invalidRowsQuarantined)
        assertEquals(0, replay.fullSells)
        assertEquals(BigInteger.valueOf(334_200L), replay.perMintRemainingQty["mint-x"])
    }

}
