package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V5.0.8960 — the top-holder fatal is applied to the freshest dated reading, not to a launch-moment memory.
 * Every deferral and authorisation path is unchanged: only the reading the 35 % fatal is applied to moves.
 */
class Aate8960HolderTimeTest {
    private val now = 1_800_000_000_000L
    private fun report(pct: Double, atMs: Long) =
        SafetyReport(topHolderPct = pct, topHolderObservedAtMs8522 = atMs)

    @Test fun aFreshReportGovernsOverAnUndatedLaunchMomentReading() {
        // the 8949 launch rule's coin: 60 % at its opening, 12 % on the fresh readout
        val p = PreTradeHardGate.holderProofDated8960(60.0, 0L, report(12.0, now - 1_000L), now)
        assertEquals(12.0, p.adversePct, 0.0)
        assertEquals(12.0, p.freshPct!!, 0.0)
    }

    @Test fun aFreshAdverseReportStillBlocksWhateverTheStateSays() {
        val p = PreTradeHardGate.holderProofDated8960(5.0, 0L, report(41.0, now - 1_000L), now)
        assertEquals(41.0, p.adversePct, 0.0)
        assertEquals(41.0, p.freshPct!!, 0.0)
    }

    @Test fun noFreshEvidenceKeepsTheOldFailClosedMax() {
        val stale = PreTradeHardGate.holderProofDated8960(60.0, 0L, report(12.0, now - 10 * 60_000L), now)
        assertEquals(60.0, stale.adversePct, 0.0)
        assertNull("a stale report still authorises nothing", stale.freshPct)
        assertEquals(60.0, PreTradeHardGate.holderProofDated8960(60.0, 0L, report(-1.0, 0L), now).adversePct, 0.0)
    }

    @Test fun aFreshDatedStateReadingIsUsedWhenTheReportIsNotFresh() {
        val p = PreTradeHardGate.holderProofDated8960(22.0, now - 5_000L, report(60.0, now - 10 * 60_000L), now)
        assertEquals(22.0, p.adversePct, 0.0)
        assertNull("the report alone authorises", p.freshPct)
    }

    @Test fun twoFreshReadingsKeepTheConservativeMaximum() {
        val p = PreTradeHardGate.holderProofDated8960(48.0, now - 5_000L, report(12.0, now - 1_000L), now)
        assertEquals(48.0, p.adversePct, 0.0)
        assertEquals(12.0, p.freshPct!!, 0.0)
    }

    @Test fun theBuyDoorAndTheBlockReadTheSameDatedProof() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        // superseded by V5.0.9020: one read of the launch facts, judged net of the owner's named traders (holderBlockFor9020)
        org.junit.Assert.assertTrue(gate.contains("holderBlockFor9020(holderProof8960(ts, ts.safety, nowMs).adversePct, ts.mint)"))
        org.junit.Assert.assertTrue(gate.contains("val holder8522 = holderProof8960(ts, safety, System.currentTimeMillis())"))
        org.junit.Assert.assertTrue("undated state can never override fresher dated evidence",
            gate.contains("freshState != null && fresh != null -> maxOf(freshState, fresh)"))
    }
}
