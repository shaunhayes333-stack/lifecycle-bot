package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.PeakLedger8188
import com.lifecyclebot.engine.truth.WalletCashLegs8237
import com.lifecyclebot.engine.truth.WalletCashLegs8237.Flow8237
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Gross signed cash movements, including partial receipts, must not manufacture historical deposits. */
class Aate8237CashSettlementTest {
    private val at = 1_000_000L

    @Before fun setUp() { PeakLedger8188.resetForTests8188() }
    @After fun tearDown() { PeakLedger8188.resetForTests8188() }

    private fun trade(side: String, sol: Double, sig: String, gross: Double = 0.0,
                      mode: String = "live", proof: String = "LIVE_FINALIZED", ts: Long = at) =
        Trade(side = side, mode = mode, sol = sol, price = 0.000086, ts = ts,
            sig = sig, mint = "ArpPse8237", proofState = proof, grossProceedsSol = gross)

    private fun evidence(vararg trades: Trade) = WalletCashLegs8237.evidence8237(trades.toList(), at - 1, at + 100)

    @Test fun completedLosingRoundTripCannotNetAwayItsLaterSaleCredit() {
        val e = evidence(trade("BUY", 0.0294, "buy8237"), trade("SELL", 0.028, "sell8237"))
        assertEquals(0.028, e.grossInSol, 1e-12)
        assertEquals(0.0294, e.grossOutSol, 1e-12)
        assertEquals(Flow8237.POSSIBLE_TRADE_SETTLEMENT, WalletCashLegs8237.classify8237(0.028, e))
        assertEquals(Flow8237.POSSIBLE_TRADE_SETTLEMENT, WalletCashLegs8237.classify8237(-0.0294, e))
        assertEquals(0, e.unresolvedLegs)
    }

    @Test fun partialReceiptUsesActualProceedsAndNeverItsCostField() {
        val e = evidence(trade("PARTIAL_SELL", 0.010, "partial8237", gross = 0.015))
        assertEquals(0.015, e.grossInSol, 1e-12)
        assertEquals(Flow8237.POSSIBLE_TRADE_SETTLEMENT, WalletCashLegs8237.classify8237(0.015, e))
        val missingProceeds = evidence(trade("PARTIAL_SELL", 0.010, "partialMissing8237"))
        assertEquals(0.0, missingProceeds.grossInSol, 0.0)
        assertEquals(1, missingProceeds.unresolvedLegs)
        assertEquals(Flow8237.MIXED_TRADE_FLOW_UNRESOLVED, WalletCashLegs8237.classify8237(0.010, missingProceeds))
    }

    @Test fun repeatedAndPartialTerminalProjectionsOfOneSignatureAreOneCashLeg() {
        val terminal = trade("SELL", 0.015, "sameSell8237", gross = 0.015)
        val partial = trade("PARTIAL_SELL", 0.010, "sameSell8237", gross = 0.015)
        val e = evidence(terminal, terminal, partial)
        assertEquals(1, e.legs.size)
        assertEquals(0.015, e.grossInSol, 1e-12)
        assertEquals(0, e.unresolvedLegs)
        val conflicting = evidence(terminal, partial.copy(grossProceedsSol = 0.025))
        assertTrue(conflicting.legs.isEmpty())
        assertEquals(1, conflicting.unresolvedLegs)
    }

    @Test fun anUnpricedProvedDuplicateIsNotSilentlyDiscarded() {
        val e = evidence(trade("SELL", 0.015, "sameSell8237"), trade("PARTIAL_SELL", 0.010, "sameSell8237"))
        assertTrue(e.legs.isEmpty())
        assertEquals(1, e.unresolvedLegs)
    }

    @Test fun paperUnsignedAndUnfinalizedRowsCannotSupplyProvedCashAmounts() {
        val e = evidence(
            trade("SELL", 8.0, "paper8237", mode = "paper"),
            trade("SELL", 0.03, ""),
            trade("SELL", 0.04, "broadcast8237", proof = "LIVE_BROADCAST"),
            trade("SELL", 0.05, "reconciled8237", proof = "LIVE_RECONCILED"),
        )
        assertEquals(0.05, e.grossInSol, 1e-12)
        assertEquals(1, e.legs.size)
        assertEquals(2, e.unresolvedLegs)
        assertEquals(Flow8237.MIXED_TRADE_FLOW_UNRESOLVED, WalletCashLegs8237.classify8237(0.10, e))
    }

    @Test fun lateFinalityReplacesTheBroadcastProjectionWithoutDoubleCounting() {
        val broadcast = trade("SELL", 0.05, "late8237", proof = "LIVE_BROADCAST")
        val e = evidence(broadcast, broadcast.copy(proofState = "LIVE_FINALIZED"))
        assertEquals(0.05, e.grossInSol, 1e-12)
        assertEquals(1, e.legs.size)
        assertEquals(0, e.unresolvedLegs)
    }

    @Test fun mixedSellAndPossibleDepositIsUnresolvedInsteadOfRebasingTheWholeCredit() {
        val e = evidence(trade("BUY", 0.0294, "buy8237"), trade("SELL", 0.028, "sell8237"))
        assertEquals(Flow8237.MIXED_TRADE_FLOW_UNRESOLVED, WalletCashLegs8237.classify8237(0.048, e))
        PeakLedger8188.refuseUnresolvedCashFlow8237(0.048, 0.048, 0.4078, at + 2, "mixed")
        val decision = PeakLedger8188.rebases8188().last()
        assertEquals(0.0, decision.appliedSol, 0.0)
        assertEquals(decision.fromSol, decision.toSol, 0.0)
        assertEquals(0.4078, decision.toSol, 0.0)
    }

    @Test fun independentWalletInflowStillNeedsTheExistingConfirmationAndComponentProof() {
        val noBotCash = evidence()
        assertEquals(Flow8237.EXTERNAL_FLOW_REQUIRES_PROOF, WalletCashLegs8237.classify8237(0.10, noBotCash))
        assertNull(CapitalDrawdown7948.confirmFlow7986(7, 7, at, at + 44_999))
        assertEquals(false, CapitalDrawdown7948.confirmFlow7986(7, 8, at, at + 50_000))
        assertEquals(true, CapitalDrawdown7948.confirmFlow7986(7, 7, at, at + 45_000))
        assertEquals(0.0, PeakLedger8188.liquidCorroboratedFlow8188(0.10, 0.0, 0.0), 0.0)
        assertEquals(0.0, PeakLedger8188.liquidCorroboratedFlow8188(0.10, 0.10, 0.05), 0.0)
        assertEquals(0.10, PeakLedger8188.liquidCorroboratedFlow8188(0.10, 0.10, 0.0), 1e-12)
        assertTrue(PeakLedger8188.rebases8188().isEmpty())
    }

    @Test fun evidenceOutsideTheCashWindowAndInvalidFlowDoNotAcquireAuthority() {
        val e = evidence(trade("SELL", 0.05, "old8237", ts = at - 2), trade("BUY", 0.04, "future8237", ts = at + 101))
        assertTrue(e.legs.isEmpty())
        assertEquals(Flow8237.NO_FLOW, WalletCashLegs8237.classify8237(Double.NaN, e))
        assertEquals(Flow8237.NO_FLOW, WalletCashLegs8237.classify8237(0.0, e))
    }

    @Test fun restoredUnprovenRebasesStayVisibleWithoutErasingAnyHistoricalLoss() {
        val event = PeakLedger8188.PeakEvent8188(0.3184, at, 0.2238, 0.0955, -0.0009, 0.0,
            PeakLedger8188.ORIGIN_NEW_HIGH_8188, "hero:FAILED")
        val old = listOf(
            PeakLedger8188.Rebase8188(at + 1, 0.0300, 0.0300, 0.0300, 0.3184, 0.3484, "APPLIED"),
            PeakLedger8188.Rebase8188(at + 2, 0.0314, 0.0314, 0.0314, 0.3484, 0.3798, "APPLIED"),
            PeakLedger8188.Rebase8188(at + 3, 0.0280, 0.0280, 0.0280, 0.3798, 0.4078, "APPLIED"),
        )
        PeakLedger8188.restore8188(PeakLedger8188.encodeEvent8188(event), PeakLedger8188.encodeRebases8188(old))
        PeakLedger8188.noteSeed8188(0.4078, at, at + 120_000)
        val peak = CapitalDrawdown7948.Peak7948(0.4078, at)
        val line = PeakLedger8188.line8188(peak, at + 120_000)
        assertEquals(event, PeakLedger8188.currentEvent8188())
        assertEquals(old, PeakLedger8188.rebases8188())
        assertTrue(line.contains("legacyRebasesUnverified8237=3"))
        assertTrue(line.contains("legacyNetUnverifiedSol8237=+0.0894"))
        assertTrue(line.contains("legacyCorrection8237=UNRESOLVED_EXTERNAL_TRANSFER_PROOF"))
        assertTrue(line.contains("effective=0.4078"))
        val dd = CapitalDrawdown7948.drawdownPct7948(peak, 0.3099, at + 120_000)
        assertTrue(dd > 24.0 && dd < 24.1)
        assertFalse(dd == 0.0)
    }
}
