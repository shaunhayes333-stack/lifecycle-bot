package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.MintDecimalsAuthority6392
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import com.lifecyclebot.engine.truth.PaperTokenQuantityAuthority6509
import com.lifecyclebot.engine.truth.PositionStateLedger6454
import com.lifecyclebot.engine.truth.SellQtyBoundaryClamp6427
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class Repair6509AuthorityAcceptanceTest {
    private fun reset(cash: Double = 10.0) {
        PaperAccountLedger6430.resetForTest(); PaperAccountLedger6430.initialize(cash)
        CanonicalPositionAuthority6441.resetForTest(); CanonicalLotQuantity6464.resetForTest()
        EconomicEventSchema6464.resetForTest(); MintDecimalsAuthority6392.clearForTest()
        SellQtyBoundaryClamp6427.resetForTest(); PositionStateLedger6454.resetForTest()
    }

    @Test fun decimals_5_6_9_round_trip_buy_lot_terminal_sell_without_skew() {
        for (decimals in listOf(5, 6, 9)) {
            reset()
            val mint = "MINT6509D${decimals}_${System.nanoTime()}"
            val pid = "PAPER:6509:$decimals:${System.nanoTime()}"
            val costSol = 0.10; val solUsd = 150.0; val tokenPriceUsd = 0.003
            val expectedQty = (costSol * solUsd) / tokenPriceUsd
            val d = requireNotNull(PaperTokenQuantityAuthority6509.resolveDecimals(mint, decimals))
            val raw = PaperTokenQuantityAuthority6509.encode(expectedQty, d)
            val check = PaperTokenQuantityAuthority6509.independentCheck(costSol, solUsd, tokenPriceUsd, raw, d)
            assertTrue(check.reason, check.ok)
            assertEquals(expectedQty, PaperTokenQuantityAuthority6509.decode(raw, d), 1e-8)
            assertTrue(CanonicalPaperTransaction6486.open(pid, mint, "D$decimals", "TEST", "6509", costSol, 0.0, raw, d).applied)
            val open = requireNotNull(CanonicalPositionAuthority6441.getPosition(pid))
            assertEquals(raw, open.remainingQtyRaw)
            assertEquals(d, open.tokenDecimals)
            assertTrue(CanonicalLotQuantity6464.hasFundedOpenLot6485(pid))
            val journalQty = PaperTokenQuantityAuthority6509.resolveJournalSoldQty(expectedQty, expectedQty, terminal = true)
            assertEquals(raw, PaperTokenQuantityAuthority6509.journalSoldRaw(journalQty, d))
            assertTrue(CanonicalPaperTransaction6486.close(pid, mint, "D$decimals", grossProceedsSol = 0.075,
                soldQtyRaw = raw, soldCostBasisSol = costSol, sellFeeSol = 0.0,
                exitReason = "LOSS_25_PERCENT_6509", terminalSequence = 1L).applied)
            val closed = requireNotNull(CanonicalPositionAuthority6441.getPosition(pid))
            assertEquals(CanonicalPositionAuthority6441.Lifecycle.CLOSED, closed.lifecycle)
            assertEquals(BigInteger.ZERO, closed.remainingQtyRaw)
            assertEquals("loss changes proceeds, never token qty", raw, PaperTokenQuantityAuthority6509.journalSoldRaw(journalQty, d))
            assertTrue(PaperTerminalProjectionConvergence6509.canonicalClosedNoActive(mint))
        }
    }

    @Test fun source_locked_sell_qty_is_never_scaled_by_negative_return() {
        val qty = 1_234.56789
        val resolved = PaperTokenQuantityAuthority6509.resolveJournalSoldQty(qty, qty, terminal = true, explicitLegacyInferenceQty = qty * 0.75)
        assertEquals(qty, resolved, 0.0)
    }

    @Test fun every_projection_failure_is_isolated_and_canonical_economics_stay_once() {
        reset()
        val mint = "CLEANUP6509_${System.nanoTime()}"; val pid = "PAPER:CLEANUP6509:${System.nanoTime()}"
        val raw = BigInteger.valueOf(1_000_000L)
        assertTrue(CanonicalPaperTransaction6486.open(pid, mint, "CLN", "TEST", "6509", 0.10, 0.0, raw, 6).applied)
        assertTrue(CanonicalPaperTransaction6486.close(pid, mint, "CLN", 0.08, raw, 0.10, 0.0, "CLOSE_6509", 1L).applied)
        val cashAfter = PaperAccountLedger6430.cashSol(); val pnlAfter = PaperAccountLedger6430.realizedPnlSol()
        val names = listOf("LEDGER", "PAPER_AUTH", "GUARDRAIL", "GLOBAL_REGISTRY", "PORTFOLIO")
        names.forEach { fail ->
            val called = linkedSetOf<String>()
            fun op(name: String) { called += name; if (name == fail) error("injected-$name") }
            val r = PaperTerminalProjectionConvergence6509.converge(mint, "CLN", "REPAIR_6509", -20,
                PaperTerminalProjectionConvergence6509.Ops(
                    closeLedger = { op("LEDGER"); "C6509" }, paperAuthority = { op("PAPER_AUTH") },
                    guardrail = { op("GUARDRAIL") }, globalRegistry = { op("GLOBAL_REGISTRY") }, portfolio = { op("PORTFOLIO") },
                ))
            assertTrue(fail in r.failed); assertEquals(names.toSet(), called)
            assertEquals(CanonicalPositionAuthority6441.Lifecycle.CLOSED, CanonicalPositionAuthority6441.getPosition(pid)?.lifecycle)
            assertEquals(cashAfter, PaperAccountLedger6430.cashSol(), 0.0); assertEquals(pnlAfter, PaperAccountLedger6430.realizedPnlSol(), 0.0)
        }
        val duplicate = CanonicalPaperTransaction6486.close(pid, mint, "CLN", 0.08, raw, 0.10, 0.0, "DUPLICATE_6509", 2L)
        assertFalse(duplicate.applied)
        assertEquals(cashAfter, PaperAccountLedger6430.cashSol(), 0.0)
    }

    @Test fun canonical_fdg_intent_ignores_unknown_raw_signal_but_preserves_hard_no_and_ticket_guards() {
        assertTrue(ExecutableOpenGate.canonicalExecutableIntent6509(true, "BUY", emptyList(), true))
        assertTrue(ExecutableOpenGate.canonicalExecutableIntent6509(true, "PROBE_ONLY", emptyList(), true))
        assertFalse(ExecutableOpenGate.canonicalExecutableIntent6509(true, "BUY", listOf("CONFIRMED_RUG"), true))
        assertFalse(ExecutableOpenGate.canonicalExecutableIntent6509(true, "BUY", emptyList(), false))
        assertFalse(ExecutableOpenGate.canonicalExecutableIntent6509(false, "BUY", emptyList(), true))
    }
}
