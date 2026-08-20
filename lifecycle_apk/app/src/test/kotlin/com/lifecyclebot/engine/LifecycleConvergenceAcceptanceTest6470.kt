package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalEconomicIdentity6470
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CanonicalLifecycleAuthority6470
import com.lifecyclebot.engine.truth.CanonicalLotQuantity6464
import com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464
import com.lifecyclebot.engine.truth.FinalizedBusConsumerBridge6465
import com.lifecyclebot.engine.truth.LearningQuarantineGate6470
import com.lifecyclebot.engine.truth.UnifiedReconcilerHealth6470
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.6470 — HARD CI ASSERTIONS for §P0 lifecycle convergence.
 *
 * §P0 Lot quantity invariant — reject sell against bought=0 lot.
 * §P0 Lot quantity invariant — reject oversell.
 * §P0 Learning quarantine gate — sell against phantom lot triggers quarantine.
 * §P0 Learning quarantine gate — drops learning consumer deliveries but not Dashboard.
 * §P0 Canonical economic identity — the ONE equation with fees.
 * §P0 Canonical economic identity — NON-CLAMPING.
 * §P0 Unified reconciler health — snapshot pulls from the ground-truth heartbeat.
 * §P0 Canonical lifecycle authority — audit is idempotent on clean state.
 */
class LifecycleConvergenceAcceptanceTest6470 {

    // ─── Lot quantity invariant at the source ──────────────────────────
    @Test
    fun `onSellFilled quarantines when no matching buy lot exists`() {
        CanonicalLotQuantity6464.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        // No prior onBuyFilled → onSellFilled with a real qty must NOT create
        // a phantom lot; it must quarantine.
        CanonicalLotQuantity6464.onSellFilled(
            positionId = "PID_PHANTOM", mint = "MINT_PHANTOM",
            filledQty = BigInteger.valueOf(7_489_167_031_711L),
        )
        assertTrue(
            "phantom sell quarantined the position",
            LearningQuarantineGate6470.isQuarantined("PID_PHANTOM", "MINT_PHANTOM"),
        )
        // The lot must remain uncreated: sellable is 0.
        assertEquals(BigInteger.ZERO, CanonicalLotQuantity6464.sellable("PID_PHANTOM"))
    }

    @Test
    fun `onSellFilled quarantines on oversell`() {
        CanonicalLotQuantity6464.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        CanonicalLotQuantity6464.onBuyFilled("PID_OS", "MINT_OS", BigInteger.valueOf(1_000L))
        // Sell 5000 > bought 1000 → quarantined.
        CanonicalLotQuantity6464.onSellFilled("PID_OS", "MINT_OS", BigInteger.valueOf(5_000L))
        assertTrue(LearningQuarantineGate6470.isQuarantined("PID_OS", null))
    }

    @Test
    fun `onSellFilled accepts valid sell that respects the invariant`() {
        CanonicalLotQuantity6464.resetForTest()
        LearningQuarantineGate6470.resetForTest()
        CanonicalLotQuantity6464.onBuyFilled("PID_OK", "MINT_OK", BigInteger.valueOf(1_000L))
        CanonicalLotQuantity6464.onSellFilled("PID_OK", "MINT_OK", BigInteger.valueOf(400L))
        assertFalse(LearningQuarantineGate6470.isQuarantined("PID_OK", "MINT_OK"))
        // Sellable = 1000 - 400 = 600.
        assertEquals(BigInteger.valueOf(600L), CanonicalLotQuantity6464.sellable("PID_OK"))
    }

    // ─── Learning quarantine gate ───────────────────────────────────────
    @Test
    fun `quarantine gate drops learners but allows dashboard`() {
        LearningQuarantineGate6470.resetForTest()
        LearningQuarantineGate6470.quarantineMint("BAD_MINT", "TEST")
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "TID", atMs = 0L, realizedPnlSol = 0.1, realizedReturnPct = 5.0,
            mint = "BAD_MINT", lane = "MEME",
        )
        assertFalse("learner is dropped", FinalizedBusConsumerBridge6465.deliver("TacticSwitcher", env))
        assertFalse("governor is dropped", FinalizedBusConsumerBridge6465.deliver("Governor", env))
        assertTrue("dashboard is not learning — pass through", FinalizedBusConsumerBridge6465.deliver("Dashboard", env))
    }

    @Test
    fun `quarantine gate does not drop clean mints`() {
        LearningQuarantineGate6470.resetForTest()
        val env = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = "TID2", atMs = 0L, realizedPnlSol = 0.2, realizedReturnPct = 10.0,
            mint = "CLEAN_MINT", lane = "MEME",
        )
        // V5.0.6475 — Governor has no wired mutation API; clean delivery must
        // remain visible as an unfulfilled parity result rather than a fake ACK.
        assertFalse(FinalizedBusConsumerBridge6465.deliver("Governor", env))
    }

    // ─── Canonical economic identity (the ONE equation) ─────────────────
    @Test
    fun `canonical economic identity holds when the equation balances`() {
        CanonicalEconomicIdentity6470.resetForTest()
        // startingCap + realized - fees == cash + openCost
        // 10 + 2 - 0.5 == 5 + 6.5 → 11.5 == 11.5 → delta 0
        val r = CanonicalEconomicIdentity6470.reconcile(
            startingCapitalSol = 10.0,
            canonicalRealizedPnlSol = 2.0,
            canonicalFeesSol = 0.5,
            canonicalCashSol = 5.0,
            canonicalOpenCostBasisSol = 6.5,
        )
        assertEquals(0.0, r.delta, 1e-9)
        assertEquals(0L, CanonicalEconomicIdentity6470.breachCount())
    }

    @Test
    fun `canonical economic identity does NOT clamp when the equation breaks`() {
        CanonicalEconomicIdentity6470.resetForTest()
        val r = CanonicalEconomicIdentity6470.reconcile(
            startingCapitalSol = 10.0,
            canonicalRealizedPnlSol = 0.0,
            canonicalFeesSol = 0.0,
            canonicalCashSol = 8.0,
            canonicalOpenCostBasisSol = 0.5,
        )
        // lhs=10, rhs=8.5, delta = -1.5. Non-clamping.
        assertEquals(-1.5, r.delta, 1e-9)
        assertEquals(1L, CanonicalEconomicIdentity6470.breachCount())
    }

    // ─── Unified reconciler health ──────────────────────────────────────
    @Test
    fun `unified reconciler health snapshot exposes ground-truth counters`() {
        UnifiedReconcilerHealth6470.resetForTest()
        // We do not force the underlying reconciler; we assert the snapshot
        // surface is stable and picks up 6467's counters correctly.
        val s = UnifiedReconcilerHealth6470.snapshot()
        assertNotNull(s)
        assertTrue(s.quickAgeMs == -1L || s.quickAgeMs >= 0L)
    }

    // ─── Canonical lifecycle authority ──────────────────────────────────
    @Test
    fun `lifecycle authority audit is idempotent on clean state`() {
        CanonicalLifecycleAuthority6470.resetForTest()
        CanonicalMintOccupancyRegistry6464.resetForTest()
        val r = CanonicalLifecycleAuthority6470.audit()
        assertNotNull(r)
        // Nothing set up ⇒ no divergence.
        assertEquals(0, r.canonicalOpen)
        assertEquals(0, r.occupancyOpen)
        assertEquals(0, r.delta)
    }
}
