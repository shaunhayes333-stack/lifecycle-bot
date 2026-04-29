package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.365 — singleton telemetry holder for cross-component counters
 * that are awkward to expose from per-instance objects.
 *
 * Scope is intentionally tiny — just the counters the main UI needs to
 * render the Funnel Stages tile. If this grows beyond ~5 counters,
 * promote into a proper telemetry bus.
 */
object MarketsTelemetry {
    /** Number of times BotService.onTokenFound bypassed the paper liquidity
     *  floor because the same mint had already been discovered by ≥1 prior
     *  scanner inside the merge window (V5.9.364 multi-scanner bypass). */
    val multiScannerBypasses: AtomicInteger = AtomicInteger(0)

    /** Last-known SolanaMarketScanner throughput snapshot. Updated by the
     *  scanner on every cycle so MainActivity can read without holding a
     *  reference to the per-run scanner instance. */
    @Volatile
    var latestThroughput: SolanaMarketScanner.ThroughputSnapshot =
        SolanaMarketScanner.ThroughputSnapshot(0, 0, 0, 0, 0, 0, 0)

    fun reset() {
        multiScannerBypasses.set(0)
        latestThroughput = SolanaMarketScanner.ThroughputSnapshot(0, 0, 0, 0, 0, 0, 0)
    }
}
