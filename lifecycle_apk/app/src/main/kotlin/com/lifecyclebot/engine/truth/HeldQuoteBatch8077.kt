package com.lifecyclebot.engine.truth

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One deadline for the held book, with no additional executor queue. */
internal object HeldQuoteBatch8077 {
    data class Result(val price: Double?, val timedOut: Boolean)

    fun resolve(requests: Map<String, () -> Double?>, executor: ExecutorService, budgetMs: Long): Map<String, Result> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceAtLeast(0))
        val futures = LinkedHashMap<String, Future<Double?>>()
        val results = LinkedHashMap<String, Result>()
        try {
            for ((key, request) in requests) {
                try { futures[key] = executor.submit<Double?> { request() } }
                catch (_: RejectedExecutionException) { results[key] = Result(null, true) }
            }
            for ((key, future) in futures) {
                results[key] = try {
                    val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
                    val price = future.get(remaining, TimeUnit.NANOSECONDS)
                    Result(price?.takeIf { it.isFinite() && it > 0.0 }, false)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    Result(null, true)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (_: java.util.concurrent.ExecutionException) {
                    Result(null, false)
                } catch (_: java.util.concurrent.CancellationException) {
                    Result(null, false)
                }
            }
        } finally {
            futures.values.filter { !it.isDone }.forEach { it.cancel(true) }
        }
        return results
    }
}
