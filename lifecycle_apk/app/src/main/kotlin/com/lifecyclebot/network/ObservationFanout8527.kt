package com.lifecyclebot.network

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded observation workers. Separate source/leaf pools prevent parents
 * waiting for child jobs queued behind themselves. Held and background pools
 * cannot consume each other's capacity. No execution/finality job uses them.
 */
internal object ObservationFanout8527 {
    internal class Pools(
        sourceThreads: Int = 8, leafThreads: Int = 8,
        sourceQueue: Int = 16, leafQueue: Int = 24, prefix: String,
    ) {
        val source = executor(sourceThreads, sourceQueue, "${prefix}-source")
        val leaf = executor(leafThreads, leafQueue, "${prefix}-leaf")
        private fun executor(threads: Int, capacity: Int, name: String): ThreadPoolExecutor =
            ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                ArrayBlockingQueue<Runnable>(capacity),
                { r -> Thread(r, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()
            ).apply { allowCoreThreadTimeOut(true) }
        fun purge() { source.purge(); leaf.purge() }
        fun shutdown() { source.shutdownNow(); leaf.shutdownNow() }
    }

    // Eight source slots: one per source in the existing fan-out. Leaf slots
    // are separate; queues hold at most two source passes / two 12-mint batches.
    // V5.0.8618 — the background pool is 12 source / 12 leaf workers with 24 / 36 queue slots (was 8 / 8, 16 / 24): 03:50 dump
    // MARK_FANOUT_RETRYABLE_SATURATION_8527=228 (price passes rejected outright, the coins left on stale marks) and
    // _DEADLINE_8527=260. Every job is a network wait, not CPU. The held-position pool keeps its own capacity.
    private val background = Pools(sourceThreads = 12, leafThreads = 12, sourceQueue = 24, leafQueue = 36, prefix = "mark-background-8527")
    private val protective = Pools(sourceQueue = 8, leafQueue = 12, prefix = "mark-held-8527")

    internal class Batch(
        total: Int, timeoutMs: Long, held: Boolean = ObservationHttpScope8527.current()?.held == true,
        leaf: Boolean = false, poolsForTest: Pools? = null,
        deadlineNanos: Long = System.nanoTime() + timeoutMs * 1_000_000L,
    ) {
        private val parent = ObservationHttpScope8527.current()
        private val scope = parent?.child(deadlineNanos)
            ?: ObservationHttpScope8527.Scope(deadlineNanos, held)
        private val pools = poolsForTest ?: if (scope.held) protective else background
        private val executor = if (leaf) pools.leaf else pools.source
        private val latch = CountDownLatch(total)
        private class Task(val scope: ObservationHttpScope8527.Scope) {
            @Volatile var future: Future<*>? = null
            @Volatile var completed = false
        }
        private val tasks = ArrayList<Task>()
        private var rejected = 0

        fun submit(block: () -> Unit) {
            val task = Task(scope.child())
            tasks.add(task)
            try {
                task.future = executor.submit {
                    try {
                        ObservationHttpScope8527.withScope(task.scope) {
                            task.scope.remainingMs() // a queued-expired task never reaches a provider
                            block()
                            task.scope.remainingMs() // body/parse work also belongs to the original deadline
                            task.completed = true
                        }
                    } catch (_: Throwable) {
                        // Unknown observation, never a fabricated zero/no-route.
                    } finally {
                        task.scope.cancel()
                        latch.countDown()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                rejected++
                task.scope.cancel()
                latch.countDown()
                note("MARK_FANOUT_RETRYABLE_SATURATION_8527")
            }
        }

        /** Returns false for saturation/expiry/cancel; callers must not count it as provider absence. */
        fun await(): Boolean {
            try {
                val left = scope.remainingMs()
                latch.await(left, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: java.io.InterruptedIOException) {
                // Parent/source deadline, not the provider's answer.
            }
            val complete = rejected == 0 && latch.count == 0L && tasks.all { it.completed } && !scope.localFailureObserved
            if (!complete) note("MARK_FANOUT_RETRYABLE_DEADLINE_8527")
            for (task in tasks) {
                if (!task.completed) {
                    task.scope.cancel() // cancels the actual HTTP call, including body reads
                    task.future?.cancel(true)
                }
            }
            scope.cancel()
            pools.purge() // cancelled queued work cannot occupy a later pass's slots
            return complete
        }
    }

    private fun note(code: String) {
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(code) } catch (_: Throwable) {}
    }
}
