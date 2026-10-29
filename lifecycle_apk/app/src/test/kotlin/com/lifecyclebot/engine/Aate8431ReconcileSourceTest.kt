package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.8431 — one reconcile source: the proof line's recon bucket is the chain reconciler's, the signed sell stays in route=. */
class Aate8431ReconcileSourceTest {
    @Test fun theProofLineReadsTheReconcilersPendingSetNotItsOwnSellFlag() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/PositionProof8189.kt").readText()
        assertTrue(p.contains("val pendingTx8431 = try { p.mint in ReconcileRunner8144.pendingMints8431() } catch (_: Throwable) { false }"))
        assertTrue(p.contains("val bucket = bucket8189(p.remainingQtyRaw, chainRaw, lastComplete8189, p.mint in frozen, pendingTx8431)"))
        assertTrue("the signed sell still reaches the route readiness", p.contains("signedSellPending8189(p.mint)"))
        val r = File("src/main/kotlin/com/lifecyclebot/engine/ReconcileRunner8144.kt").readText()
        assertTrue(r.indexOf("lastPending8431 = pending.toSet()") < r.indexOf("return ReconcileClassifier8144.Inputs8144("))
        assertEquals(emptySet<String>(), ReconcileRunner8144.pendingMints8431())
        // the pure bucket is unchanged: a matched quantity with no pending transaction is MATCHED
        assertEquals(PositionProof8189.Bucket8189.MATCHED, PositionProof8189.bucket8189(BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L), true, false, false))
        assertEquals(PositionProof8189.Bucket8189.PENDING_TX, PositionProof8189.bucket8189(BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L), true, false, true))
    }
}
