package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveRefreshQueue8385
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.LaunchCohort8173
import com.lifecyclebot.engine.truth.OnChainDepth8038
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class Aate8385ReserveWaitTest {
    private class Clock {
        var now = 100_000L
        data class Job(val at: Long, val seq: Long, val task: Runnable)
        val tasks = PriorityQueue<Job>(compareBy<Job> { it.at }.thenBy { it.seq })
        var seq = 0L
        fun post(delay: Long, task: Runnable): Boolean { tasks.add(Job(now + delay, seq++, task)); return true }
        fun step() { val j = tasks.remove(); now = j.at; j.task.run() }
    }
    @Test fun onlyEnumeratedDataRefusalsWaitAndEveryConsumerAgrees() {
        for (why in listOf(OnChainDepth8038.STALE_RESERVES_8385, OnChainDepth8038.UNVERIFIED_RESERVES_8385)) {
            val p = OnChainDepth8038.classifyPreflight8385("MintA", why, 100L)
            assertTrue(p is OnChainDepth8038.Preflight8385.DataDefer)
            assertEquals(why, TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:$why"))
            assertEquals(HandoffReceiverTasks8153.Block8153.DATA, HandoffReceiverTasks8153.classify8153(why, false, true))
            assertTrue(ContinuationRead8348.dataDefer8348(why))
            assertTrue(HandoffReceiverTasks8153.hydrationAnswers8345(why))
        }
        assertSame(OnChainDepth8038.Preflight8385.Ready, OnChainDepth8038.classifyPreflight8385("MintA", null, 100L))
        assertTrue(OnChainDepth8038.classifyPreflight8385("MintA", "CURVE_NOBODY_BOUGHT_8046", 100L) is OnChainDepth8038.Preflight8385.HardRefusal)
        assertNull(TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:CURVE_NOBODY_BOUGHT_8046"))
    }
    @Test fun rejectedWorkerAndTransientRpcProgressWithoutAnotherCaller() {
        val c = Clock(); var workers = 0; var calls = 0
        val q = CurveRefreshQueue8385({c.now}, c::post, { r -> if (workers++ == 0) false else {r.run(); true} },
            { batch -> calls++; if (calls == 1) throw java.io.IOException("transient") else batch.associateWith {c.now} })
        q.request("MintA", true)
        assertEquals(1, q.size)
        c.step() // rejected, not consumed or removed
        assertEquals(0, calls)
        c.step() // actual first RPC attempt fails, remains queued
        assertEquals(1, calls); assertEquals(1, q.size)
        c.step() // no caller: the scheduled drain succeeds
        assertEquals(2, calls); assertEquals(0, q.size)
        assertTrue(c.tasks.isEmpty())
    }
    @Test fun fourFailedAttemptsEndByNameWithoutInfiniteDrain() {
        val c = Clock(); var attempts = 0; val terminals = mutableListOf<String>()
        val q = CurveRefreshQueue8385({c.now}, c::post, {it.run(); true}, { attempts++; emptyMap() },
            { mint, why -> terminals.add("$mint:$why") })
        q.request("MintA", true)
        while (c.tasks.isNotEmpty()) c.step()
        assertEquals(4, attempts); assertEquals(0, q.size)
        assertEquals(listOf("MintA:CURVE_REFRESH_RETRY_BUDGET_8385"), terminals)
    }
    @Test fun discoveryCooldownDoesNotSuppressApprovedFreshProofAndNeverExpandsWirePace() {
        val c = Clock(); val batches = mutableListOf<Pair<Long, List<String>>>()
        val q = CurveRefreshQueue8385({c.now}, c::post, {it.run(); true}, { batch -> batches.add(c.now to batch); batch.associateWith {c.now} })
        q.request("MintA", false); c.step()
        q.request("MintA", false); assertEquals(0, q.size)
        q.request("MintA", true); q.request("MintA", true); assertEquals(1, q.size)
        while (c.tasks.isNotEmpty()) c.step()
        assertEquals(2, batches.size)
        assertTrue(batches[1].first - batches[0].first >= 10_000L)
        assertEquals(listOf("MintA"), batches[1].second)
    }
    @Test fun oldDuplicateUnknownWrongMintAndWrongSourceCannotWakeWait() {
        val w = TicketRetry8327.ReserveWait8385("MintA", "LIVE", "attemptA", 7L, 100_000L, 20L, true)
        fun accepts(mint: String, at: Long, source: String, version: Long, now: Long) =
            TicketRetry8327.reserveMatches8385(w, mint, at, source, version, now)
        assertFalse(accepts("MintB", 100_001, "ACCOUNT", 21, 100_001))
        assertFalse(accepts("MintA", 99_999, "ACCOUNT", 21, 100_001))
        assertFalse(accepts("MintA", 100_001, "ACCOUNT", 20, 100_001))
        assertFalse(accepts("MintA", 100_001, "FRAME", 21, 100_001))
        assertTrue(accepts("MintA", 100_001, "ACCOUNT", 21, 130_001))
        assertFalse(accepts("MintA", 100_001, "ACCOUNT", 21, 130_002))
        assertFalse(accepts("MintA", 100_001, "ACCOUNT", 21, 100_000))
        assertFalse(TicketRetry8327.triggerMatches8330(OnChainDepth8038.STALE_RESERVES_8385, TicketRetry8327.TRIGGER_GUARD_8330))
    }
    @Test fun onlyAcceptedCurveObservationUpdatesStoredVersionAndFreshness() {
        val mint = "test8385-${java.util.UUID.randomUUID()}"
        CurveReserves7951.noteAccount7951(mint, 40.0, 1_000.0, 10.0, 100_000)
        val first = CurveReserves7951.acceptedObservation8385(mint)!!
        CurveReserves7951.noteAccount7951(mint, 80.0, 2_000.0, 50.0, 99_000)
        CurveReserves7951.noteFrame7951(mint, 90.0, 2_000.0, 100_000)
        assertEquals(first, CurveReserves7951.acceptedObservation8385(mint))
        CurveReserves7951.noteAccount7951(mint, 41.0, 1_000.0, 11.0, 100_001)
        val next = CurveReserves7951.acceptedObservation8385(mint)!!
        assertTrue(next.version > first.version); assertEquals("ACCOUNT", next.source)
        assertTrue(LaunchCohort8173.reserveFresh8174(next.atMs, 130_001))
        assertFalse(LaunchCohort8173.reserveFresh8174(next.atMs, 130_002))
    }
}
