package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738
import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738.Stage
import com.lifecyclebot.engine.truth.ProvenanceAuthority6737
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Aate6743SourceAuthorityCompletionTest {
    @Before
    fun reset() {
        ProvenanceAuthority6737.resetForTest6737()
        CanonicalRoundTripReconciler6738.resetForTest6738()
    }

    @Test
    fun `USD entry basis is never fabricated from SOL cost divided by quantity`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertFalse(src.contains("entryCostSol / qtyToken6631"))
        assertFalse(src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"))
        assertTrue(src.contains("OPEN_POSITION_USD_BASIS_UNKNOWN_6743"))
        assertTrue(src.contains("REPLAY_CARRY_NO_USD_BASIS_6541"))
    }

    @Test
    fun `positive USD basis is never exempted from economic invariant by source name`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/QuantityInvariantAuthority6500.kt").readText()
        assertFalse(src.contains("carry_or_derived_basis_skipped"))
        assertFalse(src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"))
    }

    @Test
    fun `parity compare is read-only and uses one atomic ledger snapshot`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        val compare = src.substringAfter("fun compareToLedger(").substringBefore("fun lastSnapshot()")
        assertTrue(compare.contains("PaperCapitalAuthority6577.snapshot()"))
        assertFalse(compare.contains("establishReplayCarry6489("))
        assertFalse(compare.contains("reconcileReplayCarry6498("))
        assertTrue(compare.contains("eventRevisionAtStart"))
        assertTrue(compare.contains("eventRevisionAtEnd"))
    }

    @Test
    fun `inconclusive parity cannot authorize new admission`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperLedgerDivergenceGuard6731.kt").readText()
        assertTrue(src.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_NO_SNAPSHOT_6743"))
        assertTrue(src.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_REVISION_RACE_6743"))
        assertTrue(src.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_STALE_6743"))
        assertFalse(src.contains("OK_REVISION_RACE_6742"))
        assertFalse(src.contains("OK_STALE_PARITY_6732"))
    }

    @Test
    fun `round trip requires all four stages and divergence is sticky`() {
        val missingExit = "missing-exit"
        assertTrue(CanonicalRoundTripReconciler6738.record(missingExit, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(missingExit, Stage.SELL_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(missingExit, Stage.LEARNING_DELIVERED))
        assertFalse(CanonicalRoundTripReconciler6738.tripOf(missingExit)!!.reconciled)
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(missingExit)!!.divergenceReason.isNotBlank())

        val good = "good-four-stage"
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.EXIT_DECIDED))
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.SELL_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.LEARNING_DELIVERED))
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(good)!!.reconciled)

        val sticky = "sticky-divergence"
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.EXIT_DECIDED))
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.SELL_COMMITTED))
        CanonicalRoundTripReconciler6738.observeAccountingDivergence(sticky, "evt-sticky", "CASH_DELTA")
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.LEARNING_DELIVERED))
        assertFalse(CanonicalRoundTripReconciler6738.tripOf(sticky)!!.reconciled)
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(sticky)!!.divergenceReason == "CASH_DELTA")
    }
}
