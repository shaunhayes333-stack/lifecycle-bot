package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.math.BigInteger

class Aate8253WalletEvidenceTest {
    @After fun clear() { WalletAccountCache.bustNow() }

    @Test fun preCloseReadCannotResurrectRowleyAfterConfirmation() {
        WalletAccountCache.bustNow()
        val readGeneration = WalletAccountCache.readGeneration()
        val startedAt = System.currentTimeMillis() - 100L
        val beforeClose = mapOf("Rowley" to CanonicalTokenAmount(BigInteger("63331990605"), 6))
        assertTrue(WalletAccountCache.put(beforeClose, startedAt, true, "owner", readGeneration))
        WalletAccountCache.bustNow() // The real TradeVerifier calls this on confirmed settlement.
        assertFalse(WalletAccountCache.put(beforeClose, startedAt, true, "owner", readGeneration))
        assertNull(WalletAccountCache.snapshot())
        assertTrue(WalletAccountCache.put(emptyMap(), System.currentTimeMillis(), true, "owner"))
        assertTrue(WalletAccountCache.snapshot(owner = "owner")!!.isEmpty())
        assertTrue(WalletAccountCache.evidence()!!.complete)
        assertNull(WalletAccountCache.snapshot(owner = "another-owner"))
    }

    @Test fun observationTimeAndCompletenessBelongToTheSameSnapshot() {
        WalletAccountCache.bustNow()
        val at = System.currentTimeMillis() - 20_000L
        assertTrue(WalletAccountCache.put(emptyMap(), at, false, "owner"))
        assertEquals(at, WalletAccountCache.snapshotAtMs8094())
        assertFalse(WalletAccountCache.evidence()!!.complete)
        assertNull(WalletAccountCache.snapshot(ttlMs = 5_000L))
        assertFalse(WalletAccountCache.put(emptyMap(), at - 1L, true, "owner"))
        assertFalse(WalletAccountCache.evidence()!!.complete)
    }
}
