package com.lifecyclebot.network

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * V5.0.8418 §DAISY_CHAIN — one council turn as a chain that ADDS providers instead of letting them block each other.
 *
 *  1. Members are ordered by [LlmCapacityPool8418.order8418]: spare capacity x health / latency, distinct providers
 *     first. A member whose bucket has no capacity is not asked (no slot spent on a known refusal) — it is not a
 *     failure either.
 *  2. The first [width] members with capacity are asked in parallel.
 *  3. Each failure (429, 5xx, timeout, empty) releases only its own bucket and launches the NEXT member down the
 *     chain, so a dead provider costs the turn one member, never the whole wall clock and never a sibling's budget.
 *  4. The turn returns as soon as [wantAnswers] good answers are in. Members still running are NOT cancelled: they
 *     finish on their own thread, record their real outcome (health, latency, bucket) and land in the shared
 *     [LlmResponseCache8418] through [onSettled].
 *
 * The executor never queues: a full pool refuses the launch, which is counted as no capacity and moves on, so a hung
 * provider can hold at most its bucket's in-flight places and can never stall the next turn behind it.
 */
object LlmDaisyChain8418 {

    class Member8418(val name: String, val call: () -> Any?)

    data class Result8418(
        val answers: List<Pair<String, String>>,
        val asked: Int,
        val ownBackoff: Int,
        val empty: Int,
        val errored: Int,
        val noCapacity: Int,
        val stillRunning: Int,
    )

    private const val MAX_THREADS_8418 = 10

    /** Bounded and queue-less: a launch the pool cannot start now is refused, never parked behind a hung member. */
    private val executor8418: ExecutorService = ThreadPoolExecutor(
        0, MAX_THREADS_8418, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        ThreadFactory { r -> Thread(r, "aate-llm-chain-8418").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
    )

    /**
     * Pure: what a member's return value says about its provider. [benched] is true when the member's own refusal
     * handling just put its provider behind a stated wait (a 429 / quota), which is how a rate limit surfaces here.
     */
    fun classify8418(out: Any?, benched: Boolean): CapacityBucket8418.Outcome8418 = when {
        out is String && out.isNotBlank() -> CapacityBucket8418.Outcome8418.OK
        benched -> CapacityBucket8418.Outcome8418.RATE
        out is java.net.SocketTimeoutException -> CapacityBucket8418.Outcome8418.TIMEOUT
        out is java.io.InterruptedIOException -> CapacityBucket8418.Outcome8418.TIMEOUT
        out is Throwable -> CapacityBucket8418.Outcome8418.ERROR
        else -> CapacityBucket8418.Outcome8418.EMPTY
    }

    private class Run8418(
        val ordered: List<Member8418>,
        val isOwnRefusal: (Any?) -> Boolean,
        val isBenched: (String) -> Boolean,
        val onSettled: (String, Any?, CapacityBucket8418.Outcome8418, Boolean) -> Unit,
        val executor: ExecutorService,
        val clock: () -> Long,
    ) {
        val results = LinkedBlockingQueue<Pair<String, Any?>>()
        @Volatile var returned = false
        var next = 0
        var inFlight = 0
        var asked = 0
        var noCapacity = 0
        var poolFull = false

        fun task(m: Member8418): Runnable = Runnable {
            val t0 = clock()
            val out: Any? = try { m.call() } catch (t: Throwable) { t }
            val lat = clock() - t0
            val oc = if (isOwnRefusal(out)) CapacityBucket8418.Outcome8418.OWN_REFUSAL else classify8418(out, isBenched(m.name))
            try { LlmCapacityPool8418.release8418(m.name, clock(), oc, lat) } catch (_: Throwable) {}
            try { onSettled(m.name, out, oc, returned) } catch (_: Throwable) {}
            results.put(m.name to out)
        }

        /** Starts the next member down the chain that has capacity; false when the chain (or the pool) is spent. */
        fun launchNext(): Boolean {
            if (poolFull) return false
            while (next < ordered.size) {
                val m = ordered[next++]
                if (!LlmCapacityPool8418.tryAcquire8418(m.name, clock())) { noCapacity++; continue }
                try {
                    executor.execute(task(m))
                } catch (_: RejectedExecutionException) {
                    LlmCapacityPool8418.release8418(m.name, clock(), CapacityBucket8418.Outcome8418.OWN_REFUSAL, 0L)
                    noCapacity++
                    poolFull = true
                    return false
                }
                inFlight++
                asked++
                return true
            }
            return false
        }
    }

    /**
     * Runs one turn. [onSettled] is called on the member's own thread with (name, value, outcome, lateAfterReturn)
     * for EVERY member that was started, including the ones that finish after this function has returned.
     */
    fun run8418(
        members: List<Member8418>,
        wantAnswers: Int,
        width: Int,
        wallMs: Long,
        isOwnRefusal: (Any?) -> Boolean,
        isBenched: (String) -> Boolean,
        onSettled: (String, Any?, CapacityBucket8418.Outcome8418, Boolean) -> Unit,
        executor: ExecutorService = executor8418,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Result8418 {
        val ordered = LlmCapacityPool8418.order8418(members, { it.name }, clock())
        val run = Run8418(ordered, isOwnRefusal, isBenched, onSettled, executor, clock)
        for (i in 0 until width.coerceAtLeast(1)) {
            if (!run.launchNext()) break
        }
        val answers = ArrayList<Pair<String, String>>()
        var ownBackoff = 0
        var empty = 0
        var errored = 0
        val deadline = clock() + wallMs
        try {
            while (answers.size < wantAnswers.coerceAtLeast(1) && run.inFlight > 0) {
                val remaining = deadline - clock()
                if (remaining <= 0L) break
                val done = run.results.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                run.inFlight--
                val (name, out) = done
                when {
                    isOwnRefusal(out) -> { ownBackoff++; run.asked--; run.launchNext() }
                    out is String && out.isNotBlank() -> answers.add(name to out)
                    out is Throwable -> { errored++; run.launchNext() }
                    else -> { empty++; run.launchNext() }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            run.returned = true
        }
        return Result8418(answers, run.asked, ownBackoff, empty, errored, run.noCapacity, run.inFlight)
    }
}
