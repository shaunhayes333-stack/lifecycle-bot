package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.engine.truth.ReconcileClassifier8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Cause8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Inputs8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.Lot8144
import com.lifecyclebot.engine.truth.ReconcileClassifier8144.State8144
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/** Replays the report's complete sell followed by an empty wallet; no lifecycle writes. */
class Aate8237WalletObservationTest {
    private val mint = "ArpPse8237"
    private val raw = BigInteger("35982061846")
    private val held = mapOf(mint to CanonicalTokenAmount(raw, 6))
    private val at = 1_000_000L

    @Before fun setUp() { WalletAccountCache.bustNow() }
    @After fun tearDown() { WalletAccountCache.bustNow() }

    private fun audit(nowMs: Long, lots: List<Lot8144> = emptyList()): ReconcileClassifier8144.Report8144 {
        val observation = ReconcileRunner8144.walletObservationForAudit8237(nowMs)
        return ReconcileClassifier8144.classify8144(Inputs8144(
            lots = lots, chain = observation?.tokens?.mapValues { it.value.raw },
            chainComplete = observation?.complete == true, txs = emptyList(), journal = emptyList(),
            botEvidenceMints = setOf(mint), hostOpenMints = emptySet(),
        ))
    }

    private fun openLot() = Lot8144("position8237", mint, "OPEN", raw, raw, 0.0294, "LIVE_PROOF")

    @Test fun completeEmptyAfterSellReplacesPreviousFullBalance() {
        WalletAccountCache.put(held, complete = true, source = "OWNER_TOKEN_PROGRAMS", observedAtMs = at)
        assertEquals(1, audit(at + 1).count(Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL))
        WalletAccountCache.put(emptyMap(), complete = true, source = "OWNER_TOKEN_PROGRAMS", observedAtMs = at + 2)
        val observed = ReconcileRunner8144.walletObservationForAudit8237(at + 3)!!
        assertTrue(observed.complete)
        assertTrue(observed.tokens.isEmpty())
        assertEquals(at + 2, observed.observedAtMs)
        val afterSell = audit(at + 3)
        assertTrue(afterSell.ok)
        assertEquals(0, afterSell.count(Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL))
        assertEquals(0, afterSell.chainMints)
    }

    @Test fun partialEmptyCannotProveAnOpenPositionGone() {
        WalletAccountCache.put(held, complete = true, observedAtMs = at)
        WalletAccountCache.put(emptyMap(), complete = false, source = "KNOWN_MINTS_FALLBACK_7374", observedAtMs = at + 2)
        val result = audit(at + 3, listOf(openLot()))
        assertEquals(0, result.count(Cause8144.CANONICAL_NOT_ON_CHAIN))
        assertEquals(1, result.states[State8144.PARTIAL_CHAIN_OBSERVED])
        assertFalse(ReconcileRunner8144.walletObservationForAudit8237(at + 3)!!.complete)
    }

    @Test fun completeEmptyStillDetectsARealCanonicalDiscrepancy() {
        WalletAccountCache.put(emptyMap(), complete = true, observedAtMs = at)
        val result = audit(at + 1, listOf(openLot()))
        assertEquals(1, result.count(Cause8144.CANONICAL_NOT_ON_CHAIN))
        assertFalse(result.ok)
    }

    @Test fun failedReadDoesNotRenewOldEvidenceAndStaleOrFutureEvidenceIsUnknown() {
        WalletAccountCache.put(held, complete = true, observedAtMs = at)
        // On a failed request the producer does not call put. Repeated audit reads retain the original time.
        assertEquals(at, ReconcileRunner8144.walletObservationForAudit8237(at + 10)!!.observedAtMs)
        assertEquals(at, ReconcileRunner8144.walletObservationForAudit8237(at + 100)!!.observedAtMs)
        assertNull(ReconcileRunner8144.walletObservationForAudit8237(at - 1))
        assertNull(ReconcileRunner8144.walletObservationForAudit8237(at + 6 * 60_000L))
        assertEquals("UNVERIFIED_NO_CHAIN", audit(at + 6 * 60_000L).inventory)
    }

    @Test fun tradeInvalidationCannotFallBackToPrivatePreSellCache() {
        WalletAccountCache.put(held, complete = true, observedAtMs = at)
        assertNotNull(ReconcileRunner8144.walletObservationForAudit8237(at + 1))
        WalletAccountCache.bustNow()
        assertNull(ReconcileRunner8144.walletObservationForAudit8237(at + 2))
        assertEquals(0, audit(at + 2).count(Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL))
    }

    @Test fun snapshotMapCannotChangeAfterPublication() {
        val mutable = held.toMutableMap()
        WalletAccountCache.put(mutable, complete = true, observedAtMs = at)
        mutable.clear()
        assertEquals(raw, ReconcileRunner8144.walletObservationForAudit8237(at + 1)!!.tokens[mint]!!.raw)
    }

    @Test fun preSellInFlightReadCannotRepopulateTheCacheAfterInvalidation() {
        val preSellGeneration = WalletAccountCache.readGeneration8237()
        WalletAccountCache.put(held, complete = true, observedAtMs = at, readGeneration = preSellGeneration)
        WalletAccountCache.bustNow()
        assertFalse(WalletAccountCache.put(held, complete = true, observedAtMs = at + 2, readGeneration = preSellGeneration))
        assertNull(ReconcileRunner8144.walletObservationForAudit8237(at + 3))
        assertTrue(WalletAccountCache.put(emptyMap(), complete = true, observedAtMs = at + 4,
            readGeneration = WalletAccountCache.readGeneration8237()))
        assertTrue(audit(at + 5).ok)
    }

    @Test fun olderSlowReadCannotOverwriteANewerCompleteEmptyObservation() {
        WalletAccountCache.put(emptyMap(), complete = true, observedAtMs = at + 2)
        assertFalse(WalletAccountCache.put(held, complete = true, observedAtMs = at))
        assertTrue(ReconcileRunner8144.walletObservationForAudit8237(at + 3)!!.tokens.isEmpty())
        assertEquals(at + 2, ReconcileRunner8144.walletObservationForAudit8237(at + 3)!!.observedAtMs)
    }

    @Test fun completeEmptyCannotBeRefilledByStalePositiveExitProof() {
        val stale = mapOf(mint to raw)
        assertEquals(emptyMap<String, BigInteger>(), PositionProof8189.coherentChain8237(emptyMap(), true, setOf(mint), stale))
        assertEquals(stale, PositionProof8189.coherentChain8237(emptyMap(), false, setOf(mint), stale))
        assertNull(PositionProof8189.coherentChain8237(null, false, setOf(mint), emptyMap()))
    }

    @Test fun proofTableKeepsCompleteAbsenceAtZeroAndPreservesPartialOrUnknownFallback() {
        val absent = PositionProof8189.walletRawForProof8237(mint, emptyMap(), true, raw)
        assertEquals(BigInteger.ZERO, absent)
        assertEquals(PositionProof8189.Bucket8189.NOT_ON_CHAIN,
            PositionProof8189.bucket8189(raw, absent, true, false, false))
        assertEquals(raw, PositionProof8189.walletRawForProof8237(mint, mapOf(mint to raw), true, BigInteger.ONE))
        assertEquals(raw, PositionProof8189.walletRawForProof8237(mint, emptyMap(), false, raw))
        assertEquals(raw, PositionProof8189.walletRawForProof8237(mint, null, false, raw))
        assertEquals(BigInteger.ZERO,
            PositionProof8189.walletRawForProof8237(mint, mapOf(mint to BigInteger.ZERO), false, raw))
        assertNull(PositionProof8189.walletRawForProof8237(mint, emptyMap(), false, null))
        assertNull(PositionProof8189.walletRawForProof8237(mint, null, true, null))
    }
}
