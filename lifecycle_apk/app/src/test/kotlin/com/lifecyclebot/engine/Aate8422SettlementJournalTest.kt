package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger as BI

class Aate8422SettlementJournalTest {
    private fun pos() = CanonicalPositionAuthority6441.Position(
        positionId="position-8422", mode="live", mint="mint8422", symbol="TEST", lane="MOONSHOT", runId="test",
        openedAtMs=1000, entryCostSol=0.1, remainingQtyRaw=BI.ZERO, originalQtyRaw=BI.valueOf(1000000),
        soldCostBasisSol=0.1, realizedPnlSol=0.025, realizedProceedsSol=0.13, feesSol=0.005,
        tokenDecimals=6, lifecycle=CanonicalPositionAuthority6441.Lifecycle.CLOSED,
        lastMutationMs=2000, quarantineReason="", entryPriceUsd=10.0)
    private fun receipt() = EconomicEventSchema6464.Sell(
        atMs=2000, mode="live", positionId="position-8422", mint="mint8422", symbol="TEST", idempotencyKey="receipt8422",
        partial=false, soldQty=BI.valueOf(1000000), allocatedCostBasisSol=0.1, grossProceedsSol=0.13,
        exitFeesSol=0.005, netProceedsSol=0.125, realizedPnlSol=0.03, realizedReturnPct=30.0,
        remainingQty=BI.ZERO, remainingCostBasisSol=0.0, solUsdAtExit=100.0,
        confirmedSignature8422="signature8422", exitReason8422="TAKE_PROFIT")

    @Test fun verifiedProjectionRetainsChainEconomicsAndEntryOwner() {
        val t=JournalEconomicReplay6619.confirmedSellTrade8422(receipt(),pos())!!
        assertEquals(0.13,t.sol,1e-12); assertEquals(0.03,t.pnlSol,1e-12); assertEquals(0.025,t.netPnlSol,1e-12)
        assertEquals(0.005,t.feeSol,1e-12); assertEquals(13.0,t.price,1e-12)
        assertEquals("MOONSHOT",t.tradingMode); assertEquals("LIVE_FINALIZED",t.proofState)
        assertEquals("signature8422",t.sig); assertEquals("position-8422",t.positionId)
        assertEquals(BI.valueOf(1000000),t.canonicalConsumedRaw)
        assertTrue(TradeHistoryStore.isValidAccountingTrade(t))
    }
    @Test fun noSignatureWrongOwnerPaperAndInvalidQuantitiesCannotBecomeLiveFacts() {
        val e=receipt(); val p=pos()
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(e.copy(confirmedSignature8422=""),p))
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(e.copy(mode="paper"),p))
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(e.copy(mint="another"),p))
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(e.copy(positionId="other"),p))
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(e.copy(soldQty=BI.valueOf(1000001)),p))
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(e.copy(grossProceedsSol=Double.NaN),p))
    }
    @Test fun partialsKeepSliceCostAndRemainderAndExactZeroNet() {
        val e=receipt().copy(partial=true,soldQty=BI.valueOf(400000),remainingQty=BI.valueOf(600000),
            allocatedCostBasisSol=0.04,grossProceedsSol=0.045,exitFeesSol=0.005)
        val t=JournalEconomicReplay6619.confirmedSellTrade8422(e,pos())!!
        assertEquals("PARTIAL_SELL",t.side); assertEquals(0.04,t.soldCostBasisSol,1e-12)
        assertEquals(0.0,t.netPnlSol,1e-12); assertEquals(0.6,t.remainingQtyToken,1e-12)
    }
    @Test fun identityIsTransactionAndMintNotCallerLaneOrSideLabel() {
        val t=JournalEconomicReplay6619.confirmedSellTrade8422(receipt(),pos())!!
        val k=TradeHistoryStore.liveSellKey8422(t)
        assertEquals(k,TradeHistoryStore.liveSellKey8422(t.copy(side="PARTIAL_SELL",tradingMode="CORE",ts=9000)))
        assertNotEquals(k,TradeHistoryStore.liveSellKey8422(t.copy(sig="secondSignature")))
        assertNull(TradeHistoryStore.liveSellKey8422(t.copy(mode="paper")))
    }
    @Test fun receiptCodecPreservesVerificationAndLegacyDoesNotAcquireIt() {
        val e=receipt(); val decoded=EconomicEventSchema6464.decode6486(EconomicEventSchema6464.encode6486(e)) as EconomicEventSchema6464.Sell
        assertEquals(e,decoded)
        val old=org.json.JSONObject(EconomicEventSchema6464.encode6486(e)).apply { remove("confirmedSignature8422"); remove("exitReason8422") }
        val legacy=EconomicEventSchema6464.decode6486(old.toString()) as EconomicEventSchema6464.Sell
        assertEquals("",legacy.confirmedSignature8422)
        assertNull(JournalEconomicReplay6619.confirmedSellTrade8422(legacy,pos()))
    }
}
