package com.lifecyclebot.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

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
    private val sideEffectExecutor =
        Executors.newFixedThreadPool(8, ThreadFactory { r ->
            Thread(r, "AATE-SideEffect-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true                       // never keeps the JVM alive
                priority = Thread.NORM_PRIORITY - 1   // yields to trade-critical work
            }
        })

    /** Use for ALL non-trade-critical fire-and-forget background coroutines. */
    val sideEffect: CoroutineDispatcher = sideEffectExecutor.asCoroutineDispatcher()
}
