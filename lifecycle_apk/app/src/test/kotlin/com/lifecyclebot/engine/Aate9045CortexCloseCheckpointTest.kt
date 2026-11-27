package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCloseCheckpoint9045
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production scheduler without a market tick, wall-clock sleeps, or a stubbed coordinator. */
class Aate9045CortexCloseCheckpointTest {
    private class ClockExecutor : AbstractExecutorService(), ScheduledExecutorService {
        var now = 0L
        var reject = false
        var maxQueued = 0
        private var stopped = false
        private var serial = 0L
        private val tasks = PriorityQueue<Task<*>>()
        val queued: Int get() = tasks.count { !it.isCancelled }
        val nextAt: Long? get() = tasks.filterNot { it.isCancelled }.minOfOrNull { it.due }

        private inner class Task<V>(val callable: Callable<V>, val due: Long, val order: Long) :
            FutureTask<V>(callable), RunnableScheduledFuture<V> {
            override fun isPeriodic() = false
            override fun getDelay(unit: TimeUnit): Long = unit.convert(due - now, TimeUnit.MILLISECONDS)
            override fun compareTo(other: Delayed): Int = if (other is ClockExecutor.Task<*>) {
                val first = due.compareTo(other.due)
                if (first != 0) first else order.compareTo(other.order)
            } else getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }

        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            if (reject || stopped) throw RejectedExecutionException("fixture executor unavailable")
            val task = Task(callable, now + unit.toMillis(delay).coerceAtLeast(0L), serial++)
            tasks.add(task); maxQueued = maxOf(maxQueued, queued)
            return task
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run() }, delay, unit)
        override fun execute(command: Runnable) { schedule(command, 0L, TimeUnit.MILLISECONDS) }
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            val remaining = tasks.toMutableList<Runnable>()
            tasks.clear()
            return remaining
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun next() {
            val task = tasks.poll() ?: error("no scheduled work")
            now = maxOf(now, task.due)
            task.run()
            if (!task.isCancelled) task.get() // expose uncaught scheduler failures
        }
        fun advanceTo(at: Long) {
            require(at >= now)
            while (nextAt?.let { it <= at } == true) next()
            now = at
        }
        fun callbackAlreadyDispatched(): () -> Unit {
            val callable = tasks.peek().callable
            return { callable.call(); Unit }
        }
    }

    @Test fun manyRequestsProduceOneQueuedDrainAndNeverCommitOnTheCaller() {
        val executor = ClockExecutor()
        var pending = true
        var commits = 0
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { 0L }, { commits++; pending = false; true },
            clockNanos = { executor.now * 1_000_000L })
        repeat(1_000) { assertTrue(drain.request()) }
        assertEquals(0, commits)
        assertEquals(1, executor.queued)
        executor.next()
        assertEquals(1, commits)
        assertEquals(0, executor.queued)
        assertEquals(1, executor.maxQueued)
    }

    @Test fun pacedReceiptWakesAtItsDeadlineWithoutAnotherMarketOrBusTick() {
        val executor = ClockExecutor()
        var pending = true
        var commits = 0
        val deadline = 120_000L
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { deadline - executor.now },
            { commits++; pending = false; true }, clockNanos = { executor.now * 1_000_000L })
        drain.request(); executor.next()
        assertEquals(deadline, executor.nextAt)
        assertEquals(0, commits)
        executor.advanceTo(deadline - 1L)
        assertEquals(0, commits)
        executor.advanceTo(deadline)
        assertEquals(1, commits)
        assertEquals(0, executor.queued)
    }

    @Test fun refusedAndThrowingCommitsRetryAutonomouslyAndSuccessResetsBackoff() {
        val executor = ClockExecutor()
        var pending = true
        var attempts = 0
        val failures = ArrayList<Throwable>()
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { 0L }, {
            attempts++
            when (attempts) {
                1, 2, 5 -> false
                3 -> throw IllegalStateException("transient store failure")
                else -> { pending = false; true }
            }
        }, failures::add, clockNanos = { executor.now * 1_000_000L })
        drain.request(); executor.next()
        assertTrue(pending); assertEquals(1_000L, executor.nextAt)
        repeat(1_000) { drain.request() }
        assertEquals("hot redelivery must not bypass a failed-store retry deadline", 1_000L, executor.nextAt)
        assertEquals(1, executor.queued)
        executor.next()
        assertTrue(pending); assertEquals(3_000L, executor.nextAt)
        executor.next()
        assertTrue(pending); assertEquals(7_000L, executor.nextAt)
        executor.next()
        assertFalse(pending); assertEquals(4, attempts); assertEquals(1, failures.size)
        assertEquals(0, executor.queued)
        pending = true; drain.request(); executor.next()
        assertEquals("successful durability resets the next retry to one second", 8_000L, executor.nextAt)
        executor.next(); assertFalse(pending)
    }

    @Test fun aForcedFlushCannotLeaveANewCloseBehindAnObsoletePaceTimer() {
        val executor = ClockExecutor()
        var pending = true
        var remaining = 120_000L
        var commits = 0
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { remaining }, { commits++; pending = false; true },
            clockNanos = { executor.now * 1_000_000L })
        drain.request(); executor.next()
        assertEquals(120_000L, executor.nextAt)
        // The separate forced flush commits the old close and updates the actual encode cost/deadline.
        pending = false; remaining = 0L
        // A new close is now eligible; the already queued old deadline must not strand it.
        pending = true; drain.request()
        executor.advanceTo(0L)
        assertEquals("new eligible close must run without advancing to the obsolete deadline", 1, commits)
        assertFalse(pending)
        assertEquals(0, executor.queued)
    }

    @Test fun closeArrivingAfterCaptureRemainsPendingForTheNextBatch() {
        val executor = ClockExecutor()
        val pending = linkedSetOf("first")
        val durable = linkedSetOf<String>()
        val captures = ArrayList<Set<String>>()
        lateinit var drain: CortexCloseCheckpoint9045
        drain = CortexCloseCheckpoint9045(executor, { pending.isNotEmpty() }, { 0L }, {
            val captured = pending.toSet(); captures.add(captured)
            if (captures.size == 1) { pending.add("later"); drain.request() }
            durable.addAll(captured); pending.removeAll(captured)
            true
        }, clockNanos = { executor.now * 1_000_000L })
        drain.request(); executor.next()
        assertEquals(setOf("first"), durable)
        assertEquals(setOf("later"), pending)
        assertEquals(1, executor.queued)
        executor.next()
        assertEquals(listOf(setOf("first"), setOf("later")), captures)
        assertEquals(setOf("first", "later"), durable)
        assertTrue(pending.isEmpty())
        assertEquals(1, executor.maxQueued)
    }

    @Test fun requestRacingTheLastPendingCheckCannotLoseItsWakeOrOverlapEncoders() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val pending = AtomicBoolean(true)
        val checkedEmpty = CountDownLatch(1); val releaseCheck = CountDownLatch(1); val completed = CountDownLatch(2)
        val checks = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger(); val commits = AtomicInteger()
        val drain = CortexCloseCheckpoint9045(executor, {
            val result = pending.get()
            if (checks.incrementAndGet() == 2) {
                assertFalse(result); checkedEmpty.countDown(); assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
            }
            result
        }, { 0L }, {
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try { commits.incrementAndGet(); pending.set(false); completed.countDown(); true }
            finally { active.decrementAndGet() }
        })
        try {
            drain.request(); assertTrue(checkedEmpty.await(5, TimeUnit.SECONDS))
            pending.set(true); repeat(100) { drain.request() }
            releaseCheck.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(2, commits.get()); assertEquals(1, maximum.get()); assertFalse(pending.get())
        } finally { releaseCheck.countDown(); executor.shutdownNow() }
    }

    @Test fun interruptedCheckpointKeepsTheReceiptPendingAndReleasesSchedulerOwnership() {
        val executor = ClockExecutor()
        var pending = true
        var attempts = 0
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { 0L }, {
            if (++attempts == 1) throw InterruptedException("interrupted encoder")
            pending = false; true
        }, clockNanos = { executor.now * 1_000_000L })
        try {
            drain.request(); executor.next()
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(pending); assertEquals(1, executor.queued)
            Thread.interrupted()
            executor.next()
            assertFalse(pending); assertEquals(2, attempts)
        } finally { Thread.interrupted() }
    }

    @Test fun rejectedSubmissionNeverRunsInlineOrPretendsTheReceiptWasCommitted() {
        val executor = ClockExecutor().also { it.reject = true }
        var pending = true
        var commits = 0
        val errors = ArrayList<Throwable>()
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { 0L }, { commits++; pending = false; true }, errors::add,
            clockNanos = { executor.now * 1_000_000L })
        assertFalse(drain.request())
        assertTrue(pending); assertEquals(0, commits); assertEquals(0, executor.queued); assertEquals(1, errors.size)
        executor.reject = false
        assertTrue(drain.request()); executor.next()
        assertFalse(pending); assertEquals(1, commits)
    }

    @Test fun canceledTimerCallbackCannotStealTheReplacementTimerOrCommitTwice() {
        val executor = ClockExecutor()
        var pending = true; var pace = 120_000L; var commits = 0
        val drain = CortexCloseCheckpoint9045(executor, { pending }, { pace }, { commits++; pending = false; true },
            clockNanos = { executor.now * 1_000_000L })
        drain.request(); executor.next()
        val staleCallback = executor.callbackAlreadyDispatched()
        pace = 0L; drain.request()
        staleCallback() // cancellation may race an executor that has already dispatched the old callback
        assertEquals(0, commits); assertEquals(1, executor.queued)
        executor.advanceTo(0L)
        assertEquals(1, commits); assertFalse(pending); assertEquals(0, executor.queued)
    }

    @Test fun advancingPaceTimersDoesNotAccumulateCanceledTasksInTheWorkerQueue() {
        val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val pending = AtomicBoolean(true); val pace = java.util.concurrent.atomic.AtomicLong(120_000L)
        val commits = AtomicInteger()
        val drain = CortexCloseCheckpoint9045(executor, pending::get, pace::get,
            { commits.incrementAndGet(); pending.set(false); true })
        try {
            drain.request(); executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(1, executor.queue.size)
            repeat(100) {
                pace.addAndGet(-1_000L); drain.request()
                assertTrue("canceled timed work must be physically removed", executor.queue.size <= 1)
            }
            assertEquals(0, commits.get())
            pace.set(0L); drain.request(); executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(1, commits.get()); assertFalse(pending.get()); assertEquals(0, executor.queue.size)
        } finally { executor.shutdownNow() }
    }
}
