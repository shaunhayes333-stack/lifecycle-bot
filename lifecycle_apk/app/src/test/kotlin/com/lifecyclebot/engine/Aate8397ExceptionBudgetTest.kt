package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.ExceptionalEntryBudget8397
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Aate8397ExceptionBudgetTest {
    private val now = 10_000_000L
    private fun c(mint: String = "mint", lane: String = "MOONSHOT", version: Long = 1) =
        ExceptionalEntryBudget8397.Candidate(mint, lane, version)

    @After fun clean() { EvidenceHold8336.resetForTests8337(); EvidenceHold8336.enabled8336 = true }

    @Test fun preliminaryReadsAndPreSubmitRejectionsDoNotSpendSlots() {
        val b = ExceptionalEntryBudget8397()
        repeat(10) { assertTrue(b.qualify(c(), "verified", true, now + it)) }
        assertEquals(0L to 0L, b.counts())
        assertTrue(b.rateAllows("MOONSHOT", now + 10))
        assertNull(b.reserve("attempt1", c(), now + 20))
        assertFalse("in-flight candidate occupies the lane", b.rateAllows("MOONSHOT", now + 21))
        assertTrue(b.release("attempt1"))
        assertTrue(b.rateAllows("MOONSHOT", now + 22))
        assertEquals(0L to 0L, b.counts())
        assertNull(b.reserve("attempt2", c(), now + 23))
        assertNotNull(b.submitted("attempt2", "sent-but-unconfirmed-signature", now + 24))
        assertNull(b.submitted("attempt2", "sent-but-unconfirmed-signature", now + 25))
        assertFalse(b.release("attempt2"))
        assertNotNull("an idempotent receipt does not authorize a second send", b.reserve("attempt2", c(), now + 26))
        assertEquals(1L to 1L, b.counts())
        assertFalse(b.rateAllows("MOONSHOT", now + 60_001))
        assertFalse("a clock rollback cannot erase the lane cooldown", b.rateAllows("MOONSHOT", now - 1))
    }

    @Test fun evidenceAndReservationAreExactMintLaneGenerationAndAttempt() {
        val b = ExceptionalEntryBudget8397()
        assertTrue(b.qualify(c(), "verified", false, now))
        assertNotNull(b.reserve("wrong-version", c(version = 2), now + 1))
        assertNotNull(b.reserve("wrong-lane", c(lane = "SHITCOIN"), now + 1))
        assertNotNull(b.reserve("wrong-mint", c(mint = "other"), now + 1))
        assertNull(b.reserve("same", c(), now + 2))
        assertNull(b.reserve("same", c(), now + 3))
        assertNotNull(b.reserve("same", c(mint = "other"), now + 4))
        assertNotNull("same candidate cannot acquire two concurrent attempts", b.reserve("second", c(), now + 5))
        assertEquals(1, b.reservations())
        b.release("same")
        assertNotNull("a stale qualification must be evaluated afresh", b.reserve("stale", c(), now + 60_001))
        assertEquals(0, b.reservations())
    }

    @Test fun concurrentReservationsEnforceTheSharedHourlyCapAtomically() {
        val b = ExceptionalEntryBudget8397()
        val candidates = (0..7).map { c("m$it", "LANE$it") }
        candidates.forEach { assertTrue(b.qualify(it, "verified", false, now)) }
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val jobs = candidates.mapIndexed { i, candidate -> workers.submit<Boolean> {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                b.reserve("a$i", candidate, now + 1) == null
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            assertEquals(3, jobs.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(3, b.reservations())
            assertEquals(0L, b.counts().first)
        } finally { workers.shutdownNow() }
    }

    @Test fun actualEvidenceHoldExecutorAdapterReleasesAndCommitsOnce() {
        EvidenceHold8336.resetForTests8337()
        EvidenceHold8336.enabled8336 = true
        val ts = TokenState(mint = "adapter8397", symbol = "TEST")
        val ticket = ExecutableOpenGate.ExecutionIntent("attempt8397", "candidate8397", 1L, ts.mint, "LIVE", "MOONSHOT",
            "BUY", true, 1L, 0.03, now, "TEST")
        assertTrue(EvidenceHold8336.qualifyForTest8397(ts.mint, "MOONSHOT", 1L, now))
        assertNull(EvidenceHold8336.reserveEntry8397(ts, ticket, now + 1))
        EvidenceHold8336.finishEntry8397(ticket.attemptId, null, now + 2)
        assertEquals(0L, EvidenceHold8336.exceptionCount8337())
        assertNull(EvidenceHold8336.reserveEntry8397(ts, ticket, now + 3))
        EvidenceHold8336.finishEntry8397(ticket.attemptId, "signature8397", now + 4)
        EvidenceHold8336.finishEntry8397(ticket.attemptId, "signature8397", now + 5)
        assertEquals(1L, EvidenceHold8336.exceptionCount8337())
        assertFalse(EvidenceHold8336.rateAllows8337("MOONSHOT", now + 60_001))
        assertNotNull(EvidenceHold8336.reserveEntry8397(ts, ticket.copy(candidateVersion = 2, attemptId = "wrong"), now + 6))
    }

    @Test fun executorReservesBeforeEveryEntryRouteAndFinishesTheExactRecoveredIntent() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val identity = source.indexOf("val recoveredLiveAttemptId = requireNotNull(sealedIntent7835).attemptId")
        val reserve = source.indexOf("EvidenceHold8336.reserveEntry8397(ts, sealedIntent7835)", identity)
        val pump = source.indexOf("val pumpFirstResult: Pair<String, Double>?", identity)
        val preparation = source.indexOf("prepareJupiterBuy8090(ts, wallet, sol, effectiveSol, tradeKey, c, ::buyTerminalFail, sealedIntent7835, submission8397)", identity)
        val finish = source.indexOf("EvidenceHold8336.finishEntry8397(recoveredLiveAttemptId, submission8397.signature ?: broadcastSig7733)", identity)
        assertTrue("the transaction uses the sealed intent's exact ID", identity >= 0)
        assertTrue("PumpPortal cannot submit before reservation", reserve in (identity + 1) until pump)
        assertTrue("preparation's direct Raydium send cannot precede reservation", reserve in (identity + 1) until preparation)
        assertTrue("every exit releases or commits through outer finally", finish > preparation &&
            source.substring(source.lastIndexOf("} finally {", finish), finish).contains("} finally {"))
        val unknown = source.indexOf("broadcastSig7733 = sentSig7733", preparation)
        val unknownReturn = source.indexOf("onLiveBuyThrownAfterSend7733(ts, sentSig7733", preparation)
        assertTrue("unknown submitted liability is retained before its early return", unknown in (preparation + 1) until unknownReturn)
        val plan = File("src/main/kotlin/com/lifecyclebot/engine/truth/TradePlan7739.kt").readText()
        assertTrue("the downstream plan recheck retains the same candidate generation",
            plan.contains("EvidenceHold8336.refusal8336(ts, lane, paper, nowMs, candidateVersion8397 = candidateVersion)"))
    }
}
