package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SignedTxLedger8085 as Ledger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/** The production dispatch adapter, signed ledger, rung owner and finality booking wired together. */
class Aate9043RunnerDeliveryTest {
    private val ladder = RunnerRungBook8153.LADDER_RUNNER_8153
    private val reason = "SPIKE_CAPTURE_7943_T1"
    private val restoreRunnerState = ArrayList<() -> Unit>()
    private var previousWriter: ((List<Ledger.Entry>) -> Unit)? = null

    /** The delivery fixture uses singletons; preserve their complete prior state, including telemetry counters. */
    @Before fun isolateGlobals() {
        previousWriter = Ledger.persister
        Ledger.persister = { }
        Ledger.resetForTest()
        RunnerFunnel8331.resetForTest8331()
        // RunnerLive intentionally has no production reset. Match the existing runner execution fixture's
        // private-map access, isolating the entire book so neither retired totals nor signatures leak to later tests.
        val book = field(RunnerLive8153, "book").get(RunnerLive8153) as RunnerRungBook8153
        for (name in listOf("continuation", "lastVerdict", "counts", "planActive8254")) isolateMap(RunnerLive8153, name)
        for (name in listOf("book", "modeEvents9043")) isolateMap(book, name)
        @Suppress("UNCHECKED_CAST")
        val signatures = field(book, "seenSignatures").get(book) as MutableSet<String>
        val savedSignatures = signatures.toSet()
        restoreRunnerState += { signatures.clear(); signatures.addAll(savedSignatures) }
        signatures.clear()
        for (name in listOf("closedOpportunitySol", "closedNetSol", "closedPositions", "requested", "accepted",
            "broadcast", "confirmed", "booked", "rejected", "superseded", "expired", "duplicateFills")) {
            val member = field(book, name)
            val value = member.get(book)
            restoreRunnerState += { member.set(book, value) }
            if (value is Double) member.setDouble(book, 0.0) else member.setLong(book, 0L)
        }
    }

    @After fun restoreGlobals() {
        try {
            Ledger.resetForTest()
            RunnerFunnel8331.resetForTest8331()
            restoreRunnerState.asReversed().forEach { it() }
            restoreRunnerState.clear()
        } finally { Ledger.persister = previousWriter }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun isolateMap(owner: Any, name: String) {
        val map = field(owner, name).get(owner) as MutableMap<Any, Any>
        val saved = LinkedHashMap(map)
        restoreRunnerState += { map.clear(); map.putAll(saved) }
        map.clear()
    }

    private fun token() = TokenState(mint = "delivery9043-${System.nanoTime()}", position = Position(
        qtyToken = 1_000.0, entryPrice = 1.0, entryTime = System.currentTimeMillis() - 60_000L,
        costSol = .1, tradingMode = "MOONSHOT", isPaperPosition = false, positionId = "LIVE:delivery:${System.nanoTime()}"))

    @Test fun ticketThenUnknownSubmissionThenDelayedDuplicateFillIsOneEconomicEffect() {
        val ts = token(); val now = System.currentTimeMillis()
        assertTrue(RunnerLive8153.request8153(ts, ladder, 1, reason, now))
        val sentBefore = RunnerFunnel8331.count8331(RunnerFunnel8331.Stage8331.SELL_SENT)
        val sig = "sig-${ts.mint}"
        val persisted = ArrayList<String>()
        val previousWriter = Ledger.persister
        Ledger.persister = { entries -> persisted.clear(); persisted += entries.map { Ledger.encode(it) } }
        try {
            runBlocking {
                assertFalse(RungDispatch8397.dispatch8397(ts, reason) {
                    assertEquals(sentBefore, RunnerFunnel8331.count8331(RunnerFunnel8331.Stage8331.SELL_SENT))
                    val intent = RunnerLive8153.sellIntent9043(ts, reason, 100L, "JUPITER")
                    Ledger.record(sig, "signed-bytes", "blockhash", intent, now + 1L, requireDurable = true)
                    assertTrue(persisted.any { it.contains(sig) && it.contains("RUNG9043:") })
                    assertEquals(sentBefore, RunnerFunnel8331.count8331(RunnerFunnel8331.Stage8331.SELL_SENT))
                    Ledger.transition(sig, Ledger.State.SUBMIT_ATTEMPTED, nowMs = now + 2L)
                    Ledger.transition(sig, Ledger.State.OUTCOME_UNKNOWN, nowMs = now + 3L)
                    false // lost acknowledgment is neither fill nor permission to rearm
                })
            }
            assertEquals(sentBefore + 1L, RunnerFunnel8331.count8331(RunnerFunnel8331.Stage8331.SELL_SENT))
            assertNotNull(RunnerLive8153.pendingRequest8397(ts, reason))
            assertFalse(RunnerLive8153.settlePending8153(ts, false, now + 1_000_000L))
            Ledger.transition(sig, Ledger.State.CONFIRMED, nowMs = now + 4L)
            assertEquals(0, RunnerLive8153.confirmedTier8153(ts, ladder)) // chain status alone does not book proceeds
            RunnerLive8153.bookSlice8153(ts, reason, sig, BigInteger.valueOf(100), .2, .1)
            RunnerLive8153.bookSlice8153(ts, reason, sig, BigInteger.valueOf(100), .2, .1)
            assertEquals(1, RunnerLive8153.confirmedTier8153(ts, ladder))
            assertNull(RunnerLive8153.pendingRequest8397(ts, reason))
        } finally { Ledger.persister = previousWriter }
    }

    @Test fun sameClockModeOrPositionReplacementCannotExecuteAnOldOffer() {
        for (paperReplacement in listOf(true, false)) {
            val ts = token(); val now = System.currentTimeMillis()
            assertTrue(RunnerLive8153.request8153(ts, ladder, 1, reason, now))
            val offer = RungDispatch8397.capture8397(ts, reason)
            ts.position = ts.position.copy(isPaperPosition = paperReplacement, positionId = "replacement-position")
            runBlocking {
                assertFalse(RungDispatch8397.dispatch8397(ts, reason, offer) { fail("stale owner reached executor"); true })
            }
            assertEquals(0, RunnerLive8153.confirmedTier8153(ts, ladder))
        }
    }

    @Test fun paperTicketNeverIncrementsTheLiveSentFunnel() {
        val ts = token(); ts.position = ts.position.copy(isPaperPosition = true)
        val before = RunnerFunnel8331.count8331(RunnerFunnel8331.Stage8331.SELL_SENT)
        assertTrue(RunnerLive8153.request8153(ts, ladder, 1, reason, System.currentTimeMillis()))
        runBlocking { assertTrue(RungDispatch8397.dispatch8397(ts, reason) { true }) }
        assertEquals(before, RunnerFunnel8331.count8331(RunnerFunnel8331.Stage8331.SELL_SENT))
        assertEquals(1, RunnerLive8153.confirmedTier8153(ts, ladder))
    }
}
