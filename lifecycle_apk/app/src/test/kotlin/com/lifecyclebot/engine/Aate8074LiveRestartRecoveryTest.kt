package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EconomicEventSchema6464
import com.lifecyclebot.engine.truth.LiveBootRecovery8074
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
}
