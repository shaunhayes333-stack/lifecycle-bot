package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.LiveBootRecovery8074
import com.lifecyclebot.network.WalletTokenReadGate8268
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/** V5.0.8074 — live buys survive a restart (wallet adoption is asked for them); a blind wallet buys nothing. */
class Aate8074LiveRestartRecoveryTest {
    private fun buy(pid: String, mint: String, at: Long, mode: String = "live") = EconomicEventSchema6464.Buy(
        atMs = at, mode = mode, positionId = pid, mint = mint, symbol = "X", idempotencyKey = "b$pid",
        executedCostSol = 0.027, filledQty = BigInteger.valueOf(1000), fillPrice = 0.001,
    )

    @Test fun openLiveBuysAreFoundFromTheDurableLog() {
        val events = listOf(buy("p1", "MINT1", 1_000L), buy("p2", "MINT2", 1_000L), buy("p3", "PAPER3", 1_000L, "paper"), buy("old", "OLD", 10L))
        assertEquals(setOf("MINT1", "MINT2"), LiveBootRecovery8074.openLiveMints8074(events, 500L))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText().contains("LiveBootRecovery8074.tick8074(nowMs)"))
    }

    @Test fun blindWallet() {
        assertTrue(LiveBootRecovery8074.blindPure8074(lastOkMs = 0L, lastFailMs = 200_000L, nowMs = 400_000L))
        assertFalse(LiveBootRecovery8074.blindPure8074(lastOkMs = 300_000L, lastFailMs = 200_000L, nowMs = 400_000L))
        assertFalse(LiveBootRecovery8074.blindPure8074(lastOkMs = 300_000L, lastFailMs = 350_000L, nowMs = 400_000L)) // < 3 min
        assertTrue(File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText().contains("LiveBootRecovery8074.noteTokenRead8074(false)"))
    }
    @Test fun coldStartWalletBlindnessUsesFailureStreakRatherThanUnixEpoch() {
        assertFalse(LiveBootRecovery8074.blindPure8074(0L, 40_000L, 100_000L, firstFailMs = 40_000L))
        assertTrue(LiveBootRecovery8074.blindPure8074(0L, 200_000L, 220_000L, firstFailMs = 40_000L))
        // A successful strict read makes an older failure irrelevant.
        assertFalse(LiveBootRecovery8074.blindPure8074(300_000L, 200_000L, 600_000L, firstFailMs = 40_000L))
        // With a prior success, the last valid proof is the freshness anchor.
        assertTrue(LiveBootRecovery8074.blindPure8074(100_000L, 300_000L, 300_001L))
    }

    @Test fun walletReadsShareOneLeaseAndBoundedFailureBackoff() {
        val owner = "wallet-8268-test-" + System.nanoTime()
        assertNull(WalletTokenReadGate8268.begin(owner, 1_000L))
        assertEquals("IN_FLIGHT", WalletTokenReadGate8268.begin(owner, 1_001L))
        WalletTokenReadGate8268.finish(owner, success = false, nowMs = 1_000L)
        assertEquals("BACKOFF", WalletTokenReadGate8268.begin(owner, 2_999L))
        assertNull(WalletTokenReadGate8268.begin(owner, 3_000L))
        WalletTokenReadGate8268.finish(owner, success = false, nowMs = 3_000L)
        assertEquals("BACKOFF", WalletTokenReadGate8268.begin(owner, 6_999L))
        assertNull(WalletTokenReadGate8268.begin(owner, 7_000L))
        WalletTokenReadGate8268.finish(owner, success = true, nowMs = 7_000L)
        assertNull(WalletTokenReadGate8268.begin(owner, 7_001L))
        WalletTokenReadGate8268.finish(owner, success = true, nowMs = 7_001L)
    }

    @Test fun confirmedTransactionInvalidationDoesNotPunishTheRpcLadder() {
        val owner = "wallet-8268-generation-" + System.nanoTime()
        assertNull(WalletTokenReadGate8268.begin(owner, 1_000L))
        WalletTokenReadGate8268.finish(owner, success = false, nowMs = 1_100L, providerFailure = false)
        assertNull(WalletTokenReadGate8268.begin(owner, 1_101L))
        WalletTokenReadGate8268.finish(owner, success = true, nowMs = 1_101L)
        val source = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(source.contains("WalletTokenReadGate8268.begin(publicKeyB58)"))
        assertTrue(source.contains("WalletTokenReadGate8268.finish(publicKeyB58"))
    }

}
