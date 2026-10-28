package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * V5.0.8418 — adopting an upstream ticket compares the SPOT multiple too: a ticket sealed without it is re-sealed with
 * the canonical multiple (same decision, same margin) instead of the leveraged trade being refused at the executor.
 */
class Aate8418SpotScaleAdoptionTest {
    private val mint = "Aate8418SpotScaleMintAAAAAAAAAAAAAAAAAAAAA"

    @Before fun reset() { ExecutableOpenGate.resetForTests() }

    private fun intent(attempt: String, version: Long, size: Double, spot: Double) = ExecutableOpenGate.ExecutionIntent(
        attemptId = attempt, candidateId = "$mint:$version", candidateVersion = version,
        mint = mint, mode = "LIVE", canonicalLane = "MARKETS",
        fdgVerdict = "BUY", fdgAllowed = true, authorityVersion = version,
        resolvedSize = size, createdAt = System.currentTimeMillis(), symbol = "XAU",
        finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY,
        decisionAuthorityId6613 = "FDG:$version", fdgDecisionId6613 = "LIVE:$mint:$version:MARKETS",
        fdgEvidence6613 = "hardNo=0", requiresSolanaTokenMap = false,
        spotScaleLeverage8418 = spot,
    )

    @Test fun anUpstreamTicketWithoutTheMultipleIsResealedWithIt() {
        val v = System.nanoTime()
        val upstream = intent("UP8418:$v", v, 0.2, 1.0)
        assertNotNull(ExecutableOpenGate.registerCanonicalIntent6554(upstream))
        val canonical = intent("CAN8418:$v", v, 0.2, 3.0)
        val adopted = ExecutableOpenGate.registerCanonicalIntent6554(canonical)!!
        assertEquals(upstream.attemptId, adopted.attemptId)          // the upstream ticket is adopted, not replaced
        assertEquals(0.2, adopted.resolvedSize, 0.0)                  // margin unchanged
        assertEquals(3.0, adopted.spotScaleLeverage8418, 0.0)         // multiple sealed
        assertEquals(0.6, adopted.sealedSpotNotionalSol8418, 1e-12)   // ticket notional = submitted = recorded
    }

    @Test fun differentMultiplesOrSizesAreNeverMerged() {
        val a = intent("A8418", 5L, 0.2, 2.0)
        assertSame(a, ExecutableOpenGate.spotScaleAdopt8418(a, intent("B8418", 5L, 0.2, 3.0)))   // two real multiples: conflict
        val noMult = intent("C8418", 5L, 0.2, 1.0)
        assertSame(noMult, ExecutableOpenGate.spotScaleAdopt8418(noMult, intent("D8418", 5L, 0.25, 3.0)))   // other size
        assertSame(noMult, ExecutableOpenGate.spotScaleAdopt8418(noMult, intent("E8418", 6L, 0.2, 3.0)))    // other version
        assertSame(noMult, ExecutableOpenGate.spotScaleAdopt8418(noMult, intent("F8418", 5L, 0.2, 1.0)))    // nothing to seal
        assertEquals(4.0, ExecutableOpenGate.spotScaleAdopt8418(noMult, intent("G8418", 5L, 0.2, 4.0)).spotScaleLeverage8418, 0.0)
    }
}
