package com.lifecyclebot.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1349 — ROOT-CAUSE FIX for the "always stalls at ~100 trades" wedge.
 *
 * The bot fires dozens of fire-and-forget GlobalScope.launch(Dispatchers.IO)
 * coroutines per session for SIDE-EFFECT work: CollectiveLearning cloud uploads
 * (Turso, 30s timeout), broadcastHotToken, recordTradeForML, wallet RPC
 * reconciliation, Telegram/Discord notify. Dispatchers.IO is hard-capped at 64
 * threads. Each slow/blocked network call holds an IO thread for up to its
 * timeout. After ~64-100 trades' worth of backed-up uploads, EVERY IO thread is
 * wedged in a JNI socket read, so trade-critical IO (price fetch, exit execution,
 * journal) can no longer get a thread — the bot "stalls" at a roughly constant
 * count while the dedicated botLoop thread keeps ticking. That's why a basic
 * early-era bot (almost no background IO) ran for thousands of trades and the
 * current one dies near 100.
 *
 * Fix: a SEPARATE, bounded dispatcher for non-critical side effects. It has its
 * OWN small thread pool, completely isolated from Dispatchers.IO, so a slow
 * Turso/RPC endpoint can saturate THIS pool harmlessly without ever starving the
 * trade execution path. Telemetry is fire-and-forget — if this pool is busy the
 * work simply waits; the bot keeps trading.
 *
 * Trade-CRITICAL IO (the actual buy/sell/quote/price RPC) intentionally stays on
 * Dispatchers.IO, which is now protected because the side-effect flood no longer
 * shares it.
 */
object AppDispatchers {

    private val threadCounter = AtomicInteger(0)

    // Bounded pool for fire-and-forget side effects (cloud upload, ML record,
    // broadcast, notify, wallet reconcile). 8 threads is plenty for telemetry and
    // guarantees that even if all 8 are stuck in 30s socket reads, the 64
    // Dispatchers.IO threads remain free for trade execution.
    //
    // V5.0.8144 — the queue is bounded. Executors.newFixedThreadPool queues without limit: with all 8 threads in
    // slow socket reads, every GlobalScope.launch(sideEffect) (one per FDG stamp in two learners, one per alert,
    // one per hook) waited in an unbounded LinkedBlockingQueue holding its coroutine and captured state, and the
    // heap filled (5.0.8142: OutOfMemoryError on DefaultExecutor at 512/512 MB). Past [SIDE_EFFECT_QUEUE_CAP_8144]
    // queued tasks a dispatch is refused and counted; kotlinx cancels that coroutine (its job completes with the
    // cancellation), so a refused side effect is dropped visibly instead of queued forever.
    const val SIDE_EFFECT_QUEUE_CAP_8144 = 4_096
    private val rejectedCount8144 = AtomicLong(0)
    private val sideEffectExecutor: ThreadPoolExecutor =
        ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(SIDE_EFFECT_QUEUE_CAP_8144), ThreadFactory { r ->
            Thread(r, "AATE-SideEffect-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true                       // never keeps the JVM alive
                priority = Thread.NORM_PRIORITY - 1   // yields to trade-critical work
            }
        }, RejectedExecutionHandler { _, _ ->
            rejectedCount8144.incrementAndGet()
            throw RejectedExecutionException("side-effect queue full (${SIDE_EFFECT_QUEUE_CAP_8144})")
        })

    /** Use for ALL non-trade-critical fire-and-forget background coroutines. */
    val sideEffect: CoroutineDispatcher = sideEffectExecutor.asCoroutineDispatcher()

    /** V5.0.8144 — census: side-effect tasks waiting for a thread, and dispatches refused at the cap. */
    fun sideEffectQueueDepth8144(): Int = sideEffectExecutor.queue.size
    fun sideEffectActive8144(): Int = sideEffectExecutor.activeCount
    fun sideEffectRejected8144(): Long = rejectedCount8144.get()
}
