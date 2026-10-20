package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.GhostCloseFromFinalizedSell8264
import com.lifecyclebot.engine.truth.ReconcileClassifier8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.8264 — a finalized full sell closes its canonical ghost row; nothing without that proof does. */
class Aate8264GhostCloseTest {
    private val lot = ReconcileClassifier8144.Lot8144("pump:35415:6", "9XKzy4", "OPEN", BigInteger("6055668152"), BigInteger("6055668152"), 0.0291)
    private val opened = 1_000_000L
    private fun sell(ts: Long = opened + 48_000L, sig: String = "sigGOMO", proof: String = "LIVE_FINALIZED", raw: String = "6055668152", qty: Double = 0.0, side: String = "SELL") =
        ReconcileClassifier8144.Row8144(side, "9XKzy4", "pump:35415:6", sig, soldQty = qty, consumedRaw = BigInteger(raw), costSol = 0.027, grossProceedsSol = 0.0, reason = "CATASTROPHIC_HARD_BACKSTOP", ts = ts, proofState = proof)

    @Test fun theFinalizedFullSellProvesTheGhost() {
        val s = GhostCloseFromFinalizedSell8264.provenSell8264(lot, opened, 6, listOf(sell()), emptySet(), chainComplete = true, chainRaw = null)
        assertNotNull(s)
        assertEquals(0.027, GhostCloseFromFinalizedSell8264.proceeds8264(s!!), 1e-12)
        // a ui quantity that covers the raw remaining also proves it
        assertNotNull(GhostCloseFromFinalizedSell8264.provenSell8264(lot, opened, 6, listOf(sell(raw = "0", qty = 6055.67)), emptySet(), true, BigInteger.ZERO))
    }

    @Test fun withoutProofNothingCloses() {
        val g = GhostCloseFromFinalizedSell8264
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell()), emptySet(), chainComplete = false, chainRaw = null))          // partial read proves nothing
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell()), emptySet(), true, BigInteger("6055668152")))               // still on chain
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell(sig = "")), emptySet(), true, null))                           // unsigned
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell()), setOf("sigGOMO"), true, null))                             // failed on chain
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell(proof = "PENDING")), emptySet(), true, null))                  // not chain-proven
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell(ts = opened - 1L)), emptySet(), true, null))                   // before this lot opened
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell(raw = "3000000000")), emptySet(), true, null))                 // a partial does not cover it
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell().copy(mint = "OTHER")), emptySet(), true, null))
        assertNull(g.provenSell8264(lot, opened, 6, listOf(sell(side = "BUY")), emptySet(), true, null))
        assertTrue(g.closeProven8264(emptyList(), listOf(lot), listOf(sell()), emptySet(), emptyMap(), true).isEmpty())
    }

    @Test fun theReconcilerRunsItAfterEveryClassification() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/ReconcileRunner8144.kt").readText()
        assertTrue(r.contains("GhostCloseFromFinalizedSell8264.closeProven8264("))
        assertTrue(r.contains("ghostClosed8264="))
    }
}
