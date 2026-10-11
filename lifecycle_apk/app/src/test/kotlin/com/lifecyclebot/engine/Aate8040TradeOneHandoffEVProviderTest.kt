package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import com.lifecyclebot.engine.truth.OracleTradeHistory7287
import java.math.BigInteger
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** One refused FDG primary, one READY rescue, no invented green EV. */
class Aate8040TradeOneHandoffEVProviderTest {
    @Test fun refusedPrimaryElectsOnlyStrongestReadyRescue() {
        val mint = "rescue8040" + System.nanoTime()
        val v = LaneExecutionCoordinator.candidateVersionFor(mint)
        SpecialistCandidateBooks7803.markReady("MOONSHOT", mint, "M", v, 45, 60.0, "test", 80.0)
        SpecialistCandidateBooks7803.markReady("QUALITY", mint, "M", v, 70, 70.0, "test", 60.0)
        SpecialistOwnership7951.noteLaneRefused7960(mint, "CASHGEN")
        assertTrue(SpecialistOwnership7951.handOffAllowed7960("MOONSHOT", mint, "CASHGEN"))
        assertFalse(SpecialistOwnership7951.handOffAllowed7960("QUALITY", mint, "CASHGEN"))
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("SpecialistOwnership7951.onFdgVerdict7951("))
    }

    @Test fun aGreenLiveEVRequiresMatchingSignedWalletReceiptEconomics() {
        fun t(sig:String, proof:String, amount:Double, actualPnl:Double) =
            Trade(side="SELL", mode="live", sol=amount, price=1.0, ts=1L,
                mint="ProofMint8040", sig=sig, proofState=proof,
                soldCostBasisSol=0.1, grossProceedsSol=amount, feeSol=0.001,
                netPnlSol=actualPnl, canonicalConsumedRaw=BigInteger.valueOf(500L))
        assertEquals(9.0, OracleTradeHistory7287.netPct(t("chainSig", "LIVE_FINALIZED",0.11,0.009))!!,1e-8)
        assertNull(OracleTradeHistory7287.netPct(t("", "LIVE_FINALIZED",0.11,0.009)))
        assertNull(OracleTradeHistory7287.netPct(t("broadcastSig","LIVE_BROADCAST",0.11,0.009)))
        assertNull(OracleTradeHistory7287.netPct(t("chainSig","LIVE_FINALIZED",0.11,0.04)))
    }

    @Test fun priceProviderCannotClaimExecutableSwapRoute() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/RouteTruthHydrator.kt").readText()
        assertTrue(s.contains("ROUTE_TRUTH_PRICE_ONLY_PENDING_EXEC_ROUTE_8040"))
        assertTrue(s.contains("Result(false, \"PROVIDER_PRICE_ONLY\""))
        assertFalse(s.contains("ROUTE_TRUTH_FROM_HELIUS"))
    }
}
