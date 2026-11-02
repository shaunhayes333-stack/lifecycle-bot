package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellReconciler
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicReference

/** A paused dispatcher reproduces a loaded IO pool; no wallet or network operation runs. */
class Aate8513SellLifecycleTest {
    private class Paused : CoroutineDispatcher() {
        val queue = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drainCancelled() { while(queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private fun job(): Job? {
        val f=SellReconciler.javaClass.getDeclaredField("jobRef").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") return (f.get(SellReconciler) as AtomicReference<Job?>).get()
    }
    @Test fun queuedWorkerIsOwnedBeforeDispatchAndStartIsIdempotent() {
        val d=Paused(); val scope=CoroutineScope(SupervisorJob()+d)
        val ticks=SellReconciler.totalTicks
        try {
            SellReconciler.start(scope,true,null,dispatcher=d)
            val first=job()
            assertNotNull(first)
            assertTrue("a scheduled worker must not look absent to the watchdog",SellReconciler.isStarted)
            assertEquals("ownership is not a fake heartbeat",ticks,SellReconciler.totalTicks)
            SellReconciler.start(scope,true,null,dispatcher=d)
            assertSame("repeated start must not cancel queued work",first,job())
        } finally { SellReconciler.stop(); scope.cancel(); d.drainCancelled() }
    }
    @Test fun cancelledScopeCannotLeaveAStartedZombie() {
        val d=Paused(); val scope=CoroutineScope(SupervisorJob()+d)
        try {
            SellReconciler.start(scope,true,null,dispatcher=d)
            scope.cancel(); d.drainCancelled()
            assertFalse(SellReconciler.isStarted)
            assertNull("completion clears only its own job",job())
        } finally { SellReconciler.stop(); scope.cancel(); d.drainCancelled() }
    }
    @Test fun oldCompletionCannotEraseAReplacementGeneration() {
        val d=Paused(); val old=CoroutineScope(SupervisorJob()+d); val next=CoroutineScope(SupervisorJob()+d)
        try {
            SellReconciler.start(old,true,null,dispatcher=d)
            val first=job()
            SellReconciler.start(next,true,null,dispatcher=d)
            val second=job()
            assertNotSame(first,second)
            assertTrue("replacement owns lifecycle before dispatch",SellReconciler.isStarted)
            old.cancel()
            d.queue.removeFirst().run() // run the cancelled old generation only
            assertSame(second,job())
        } finally { SellReconciler.stop(); old.cancel(); next.cancel(); d.drainCancelled() }
    }

    @Test fun firstTickGetsGraceButHistoricTicksCannotHideAStall() {
        val d=Paused(); val scope=CoroutineScope(SupervisorJob()+d)
        val oldTicks=SellReconciler.totalTicks; val oldAt=SellReconciler.lastTickAtMs
        try {
            val wallet=org.mockito.Mockito.mock(com.lifecyclebot.network.SolanaWallet::class.java)
            SellReconciler.start(scope,false,wallet,dispatcher=d)
            val at=SellReconciler.javaClass.getDeclaredField("loopStartedAtMs8513").apply{isAccessible=true}.getLong(SellReconciler)
            SellReconciler.totalTicks=1234; SellReconciler.lastTickAtMs=at-60_000
            assertTrue(SellReconciler.isLiveAlive(at+1))
            assertFalse(SellReconciler.isLiveZombie(1,at+1))
            assertFalse(SellReconciler.isLiveAlive(at+30_001))
            assertTrue(SellReconciler.isLiveZombie(1,at+30_001))
            SellReconciler.lastTickAtMs=at+30_000
            assertTrue(SellReconciler.isLiveAlive(at+30_001))
        } finally {
            SellReconciler.stop();scope.cancel();d.drainCancelled()
            SellReconciler.totalTicks=oldTicks;SellReconciler.lastTickAtMs=oldAt
        }
    }
}
