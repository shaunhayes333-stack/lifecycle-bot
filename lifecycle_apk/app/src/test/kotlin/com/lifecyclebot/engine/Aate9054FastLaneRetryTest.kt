package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FastLaneRetry9054
import java.util.concurrent.Semaphore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class Aate9054FastLaneRetryTest {
    private fun until(ms:Long)=System.nanoTime()+ms*1_000_000L
    @Test fun bothPermitsReleaseBeforeNextWait() = runBlocking {
        val chain=Semaphore(2); val slots=Semaphore(1); val r=FastLaneRetry9054(chain)
        assertTrue(r.acquire("CHAIN_ANSWER_8957",slots,until(1000)){true})
        assertEquals(1,chain.availablePermits())
        val pending=async(start=CoroutineStart.UNDISPATCHED){r.acquire("EVIDENCE_WAIT_8948",slots,until(1500)){true}}
        assertFalse(pending.isCompleted)
        assertFalse(r.acquire("STAGE_READ_8950",slots,until(1000)){true})
        r.release("CHAIN_ANSWER_8957",slots)
        assertTrue(pending.await())
        assertEquals(2,chain.availablePermits()); assertEquals(0,slots.availablePermits())
        r.release("EVIDENCE_WAIT_8948",slots)
        assertEquals(1,slots.availablePermits())
    }
    @Test fun invalidOrCancelledWakeNeverOwnsALease() = runBlocking {
        val chain=Semaphore(2); val slots=Semaphore(0); val r=FastLaneRetry9054(chain)
        var valid=true
        val pending=async(start=CoroutineStart.UNDISPATCHED){r.acquire("LAUNCH_RULE_8949",slots,until(1000)){valid}}
        valid=false; slots.release()
        assertFalse(pending.await())
        assertEquals(1,slots.availablePermits())
        slots.acquire()
        val c=async(start=CoroutineStart.UNDISPATCHED){r.acquire("CHAIN_ANSWER_8957",slots,until(1000)){true}}
        c.cancelAndJoin()
        assertTrue(r.line().contains("waiting=0")); assertEquals(2,chain.availablePermits())
        slots.release()
        assertFalse(r.acquire("BASE_BREAKOUT_8995",slots,until(-1)){true})
        assertEquals(1,slots.availablePermits())
    }
    @Test fun ordinaryWaitersCannotConsumeArmedSignalReserve() = runBlocking {
        val chain=Semaphore(2); val slots=Semaphore(0); val r=FastLaneRetry9054(chain)
        val ordinary=(1..16).map { async(start=CoroutineStart.UNDISPATCHED){r.acquire("EVIDENCE_WAIT_8948",slots,until(5000)){true}} }
        val launch=async(start=CoroutineStart.UNDISPATCHED){r.acquire("LAUNCH_RULE_8949",slots,until(5000)){true}}
        try {
            assertFalse(r.acquire("EVIDENCE_WAIT_8948",slots,until(1000)){true})
            assertFalse(launch.isCompleted); assertTrue(r.line().contains("waiting=17"))
        } finally { ordinary.forEach{it.cancelAndJoin()}; launch.cancelAndJoin() }
        assertTrue(r.line().contains("waiting=0")); assertEquals(2,chain.availablePermits())
    }
}
