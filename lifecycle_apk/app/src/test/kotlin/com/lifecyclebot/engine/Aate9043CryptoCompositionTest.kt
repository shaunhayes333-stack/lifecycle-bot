package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136 as Admission
import com.lifecyclebot.engine.truth.CryptoSignalEngine8857 as Crypto
import com.lifecyclebot.engine.truth.CanonicalEntryAuthority6540 as Funnel
import com.lifecyclebot.engine.truth.AssetClass
import org.junit.Assert.*
import org.junit.Test

class Aate9043CryptoCompositionTest {
    private val reason = "DATA_INCOMPLETE_DEFER_8136"
    private fun wait(vararg trace: String, cost: Double = 2.0) = Admission.Decision8136(
        Admission.Terminal8136.DEFER_DATA, reason, false, null, Double.NaN, trace.toList(), costPct = cost)

    @Test fun backedNativeCryptoHistoryWaitReachesTheRemainingContract() {
        val d = wait("missing=shape,chg,bp")
        var backingReads = 0
        assertFalse(Crypto.deferBeforeEconomics9043(true, reason, d) { backingReads++; true })
        assertEquals(1, backingReads)
        assertTrue(Crypto.deferBeforeEconomics9043(true, reason, d) { false })
        assertTrue(Crypto.deferBeforeEconomics9043(true, reason, d) { throw IllegalStateException("unreadable backing") })
        assertTrue(Crypto.deferBeforeEconomics9043(false, reason, d) { fail("other modes/assets do not borrow crypto backing"); true })
    }

    @Test fun priceLiquidityCostsAndPendingBuyDoorProofRemainBindingEvenWithBacking() {
        val decisions = listOf(wait("missing=price"), wait("missing=liq", "criticalUnobserved8170=LIQUIDITY:UNKNOWN"),
            wait("missing=shape", cost = Double.NaN),
            wait("buyDoorProof8703=LIVE_SAFETY_REPORT_NOT_FRESH_8269 wouldAdmit=NATIVE", "missing=proof:LIVE_SAFETY_REPORT_NOT_FRESH_8269"),
            wait("missing=proof:LIVE_CRITICAL_PROOF_PENDING_8373"))
        for (d in decisions) assertTrue(d.trace.toString(), Crypto.deferBeforeEconomics9043(true, reason, d) {
            fail("critical facts cannot consult a learned exemption"); true
        })
        assertTrue(Crypto.binds8857("REPEAT_LOSER_8022") { null })
        assertTrue(Crypto.binds8857("LIVE_ROUTE_UNAVAILABLE") { null })
    }

    @Test fun waitAndTerminalRefusalCloseDifferentFunnelOutcomes() {
        Funnel.clearAllForTest()
        try {
            repeat(2) { Funnel.markCandidateFor6551(AssetClass.CRYPTO_ALT, "fixture", "test"); Funnel.markSubmitFor6551(AssetClass.CRYPTO_ALT, "fixture", "test") }
            Funnel.markAuthWaitFor9043(AssetClass.CRYPTO_ALT, "fixture", reason)
            Funnel.markAuthBlockFor6551(AssetClass.CRYPTO_ALT, "fixture", "REPEAT_LOSER_8022")
            val row = Funnel.assetClassStats6567().single { it.assetClass == AssetClass.CRYPTO_ALT }
            assertEquals(2L, row.submits); assertEquals(1L, row.waits); assertEquals(1L, row.blocks); assertEquals(0L, row.allows)
            assertEquals(row.submits, row.waits + row.blocks + row.allows)
            assertEquals(1L, Funnel.snapshot(Funnel.Venue.CRYPTO).authWait)
            assertEquals(1L, Funnel.snapshot(Funnel.Venue.CRYPTO).authBlock)
        } finally { Funnel.clearAllForTest() }
    }
}
