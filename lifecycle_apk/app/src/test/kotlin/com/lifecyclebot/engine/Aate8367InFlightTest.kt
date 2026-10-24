package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.Side
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.Store
import com.lifecyclebot.engine.truth.ExecutionSpineAcceptance6647
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8367 §OPEN_IN_FLIGHT_RECEIPTS — 5.0.8360 smoke: every delta 0.000000, two STOCK paper BUY receipts
 * (PAPER6486:OPEN:STOCK_…; pos/led/flt/term committed, jrn=false, ages 14.8 s / 11.7 s) and the window printed
 * CASH_DELTA|BASIS_DELTA|REALIZED_DELTA|QUANTITY_DELTA. The window still FAILS; it now names what is unfinished.
 */
class Aate8367InFlightTest {

    @Before fun reset() { CanonicalEconomicEvent6635.resetForTest() }
    @After fun tearDown() { CanonicalEconomicEvent6635.resetForTest() }

    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun inFlight(
        n: Int = 2, cash: Double = 0.0, basis: Double = 0.0, realized: Double = 0.0,
        qty: BigInteger = BigInteger.ZERO, cashSide: Boolean = true,
    ) = ExecutionSpineAcceptance6647.InFlightOnly8367(
        n = n, kinds = "STOCK:BUY:missing=jrn", oldestMs = 14_780L, cashSideReconciled = cashSide,
        rawCashDeltaSol = cash, rawBasisDeltaSol = basis, rawRealizedDeltaSol = realized, rawQuantityDeltaRaw = qty,
    )

    // The window's unreconciled sentinel: NaN deltas and quantity ONE.
    private fun sentinel(f: ExecutionSpineAcceptance6647.InFlightOnly8367?) =
        ExecutionSpineAcceptance6647.conservationFailures8367(Double.NaN, Double.NaN, Double.NaN, BigInteger.ONE, f)

    @Test fun zero_deltas_with_only_open_receipts_are_named_open_in_flight_and_still_fail() {
        val f = sentinel(inFlight())
        assertEquals(1, f.size)
        assertEquals("OPEN_IN_FLIGHT_RECEIPTS_8367 n=2 kinds=STOCK:BUY:missing=jrn oldestMs=14780", f[0])
        assertFalse(f.any { it.endsWith("_DELTA") })
        assertTrue(ExecutionSpineAcceptance6647.isOpenInFlightFailure8367(f[0]))
    }

    @Test fun a_real_delta_is_still_named_by_its_delta() {
        val cash = sentinel(inFlight(cash = 0.129750))
        assertTrue(cash.contains("CASH_DELTA"))
        assertFalse(cash.any { ExecutionSpineAcceptance6647.isOpenInFlightFailure8367(it) })
        val qty = sentinel(inFlight(qty = BigInteger.valueOf(84_930_968L)))
        assertTrue(qty.contains("QUANTITY_DELTA"))
        assertFalse(qty.any { ExecutionSpineAcceptance6647.isOpenInFlightFailure8367(it) })
        // The cash side of the verdict itself failed (e.g. replay totals incomplete): keep the four names.
        assertEquals(4, sentinel(inFlight(cashSide = false)).size)
        // No open receipt: the old naming.
        assertEquals(listOf("CASH_DELTA", "BASIS_DELTA", "REALIZED_DELTA", "QUANTITY_DELTA"), sentinel(null))
        assertEquals(4, sentinel(inFlight(n = 0)).size)
        // Reconciled zero deltas: nothing to name.
        assertTrue(ExecutionSpineAcceptance6647.conservationFailures8367(0.0, 0.0, 0.0, BigInteger.ZERO, inFlight()).isEmpty())
        // A reconciled real delta keeps exactly its own name.
        assertEquals(listOf("BASIS_DELTA"),
            ExecutionSpineAcceptance6647.conservationFailures8367(0.0, 0.5, 0.0, BigInteger.ZERO, inFlight(basis = 0.5)))
    }

    @Test fun evaluate_fails_with_the_honest_name() {
        val o = ExecutionSpineAcceptance6647.Observation(
            durationMs = 125_000L, safety = 82L, v3 = 91L, bgSplitRuntimeIntakeZombie = 0L,
            configuredWorkers = 12, currentWorkerHeartbeats = 12, phantomSizedOnly = 0L, sizePending = 0L,
            fdgAllowWithoutIntent = 0L, dispatches = 15L, immutableIntentsForDispatches = 15L,
            terminalResultsForDispatches = 15L, cryptoDispatches = 3L, cryptoOpenConfirmed = 5L,
            maxExitStartDelayCycles = 0L, exitStart = 4L, exitDone = 4L, canonicalOpen = 37L, exitEvaluations = 80L,
            supervisorForcedLeaseReleases = 0L, cashDeltaSol = Double.NaN, basisDeltaSol = Double.NaN,
            realizedDeltaSol = Double.NaN, quantityDeltaRaw = BigInteger.ONE, heroJournalParityFail = 0L,
            invalidGrowthOrLearningUpdates = 0L, inFlightOnly8367 = inFlight(),
        )
        val r = ExecutionSpineAcceptance6647.evaluate(o)
        assertFalse(r.passed)
        assertEquals(listOf("OPEN_IN_FLIGHT_RECEIPTS_8367 n=2 kinds=STOCK:BUY:missing=jrn oldestMs=14780"), r.failures)
        assertEquals(4, ExecutionSpineAcceptance6647.evaluate(o.copy(inFlightOnly8367 = null)).failures.size)
    }

    private fun stockBuy(pid: String, ageMs: Long) = CanonicalEconomicEvent6635.Event(
        economicEventId = "PAPER6486:OPEN:$pid", positionId = pid, mint = "STOCK:AAPL", canonicalMint = "STOCK:AAPL",
        symbol = "AAPL", mode = "paper", lane = "STOCK", side = Side.BUY,
        timestampMs = System.currentTimeMillis() - ageMs,
        qtyRaw = BigInteger.valueOf(1_000_000L), decimals = 6, executionPriceUsd = 200.0, executionPriceSol = 1.0,
        notionalSol = 0.1, feeSol = 0.0, cashDeltaSol = -0.1, positionQtyDeltaRaw = BigInteger.valueOf(1_000_000L),
        realizedPnlDeltaSol = 0.0, terminalFillIndex = 0,
    )

    /**
     * Reproduction of the 8360 receipts: ensureOpenProjection6659 commits POSITION, LEDGER, TERMINAL_EXEC and FILL_LOT
     * synchronously; JOURNAL is stamped only when the async TradeHistoryIO SQLite insert lands. Until then the receipt
     * is OPEN (not leaked); the journal receipt is the one branch that terminates it.
     */
    @Test fun cross_asset_open_waits_only_for_its_durable_journal_then_commits() {
        val a = stockBuy("STOCK_1792775241487", 14_780L)
        val b = stockBuy("STOCK_1792775241488", 11_701L)
        for (e in listOf(a, b)) {
            assertTrue(CanonicalEconomicEvent6635.openEvent(e))
            for (s in listOf(Store.POSITION, Store.LEDGER, Store.TERMINAL_EXEC, Store.FILL_LOT)) {
                assertTrue(CanonicalEconomicEvent6635.markCommitted(e.economicEventId, s, "test.open6659"))
            }
        }
        val open = CanonicalEconomicEvent6635.openInFlightOnly8367()
        assertNotNull(open)
        assertEquals(2, open!!.n)
        assertEquals("STOCK:BUY:missing=jrn", open.kinds)
        assertTrue(open.oldestMs >= 14_780L)
        assertTrue(CanonicalEconomicEvent6635.forensicReconciliationLine6635().contains("openInFlight=2"))

        // The durable journal row lands: every opened receipt reaches COMMITTED.
        for (e in listOf(a, b)) {
            assertTrue(CanonicalEconomicEvent6635.markCommitted(e.economicEventId, Store.JOURNAL, "TradeHistoryStore.SQLite.durable6641"))
            assertTrue(CanonicalEconomicEvent6635.isCommitted(e.economicEventId))
        }
        assertNull(CanonicalEconomicEvent6635.openInFlightOnly8367())
        assertTrue(CanonicalEconomicEvent6635.forensicReconciliationLine6635().contains("status=RECONCILED"))
    }

    @Test fun a_pending_receipt_is_never_renamed_open_in_flight() {
        val old = stockBuy("STOCK_1", 200_000L)
        assertTrue(CanonicalEconomicEvent6635.openEvent(old))
        assertTrue(CanonicalEconomicEvent6635.markCommitted(old.economicEventId, Store.LEDGER, "test"))
        CanonicalEconomicEvent6635.sweepPending6635(60_000L)
        assertNull(CanonicalEconomicEvent6635.openInFlightOnly8367())
    }

    @Test fun pure_kind_helpers() {
        assertEquals("jrn", CanonicalEconomicEvent6635.missingStores8367(true, true, false, true, true))
        assertEquals("led+jrn", CanonicalEconomicEvent6635.missingStores8367(true, false, false, true, true))
        assertEquals("none", CanonicalEconomicEvent6635.missingStores8367(true, true, true, true, true))
        assertEquals("SPOT:BUY:missing=jrn", CanonicalEconomicEvent6635.inFlightKind8367("SPOT_HOGS_1792612169526", "BUY", "jrn"))
        assertEquals("?:SELL:missing=flt", CanonicalEconomicEvent6635.inFlightKind8367("", "SELL", "flt"))
    }

    @Test fun source_pins_8367() {
        val spine = src("engine/truth/ExecutionSpineAcceptance6647.kt")
        assertTrue(spine.contains("f += conservationFailures8367(o.cashDeltaSol, o.basisDeltaSol, o.realizedDeltaSol, o.quantityDeltaRaw, o.inFlightOnly8367)"))
        assertTrue(spine.contains("// superseded by V5.0.8367 — the four delta checks moved verbatim into conservationFailures8367"))
        assertTrue(spine.contains("inFlightOnly8367 = captureInFlightOnly8367(forensic, nowMs),   // V5.0.8367"))
        assertTrue(spine.contains("if (forensic == null || forensic.reconciled) return null"))
        assertTrue(spine.contains("failures=${'$'}{failures.joinToString(\"|\").replace(' ', ',')}"))
        // The unreconciled sentinel is unchanged: nothing is healed to pass.
        assertTrue(spine.contains("if (forensic?.reconciled == true && value != null) value else Double.NaN"))
        assertTrue(spine.contains("quantityDeltaRaw = if (forensic?.reconciled == true) forensic.quantityRaw else java.math.BigInteger.ONE"))
        val audit = src("engine/truth/AcceptanceInvariantAudit6441.kt")
        assertTrue(audit.contains("filterNot { it in paperEconomic7399 || ExecutionSpineAcceptance6647.isOpenInFlightFailure8367(it) }"))
        val fr = src("engine/truth/ForensicReconciliation6635.kt")
        assertTrue(fr.contains("fun cashSideReconciled8367(): Boolean = lastReconciledStatus.get() == \"RECONCILED\""))
        val ev = src("engine/truth/CanonicalEconomicEvent6635.kt")
        assertTrue(ev.contains("if (t != Terminal.OPEN) return null"))
    }
}
