package com.lifecyclebot.engine

/** V5.0.8772 — code audit (discovery F4): the scanner's cadence (SolanaMarketScanner.scanLoop). */
object ScanCadence8772 {
    /** Pure: the scan interval for a configured [secs], clamped to [10, 15] s (the tuner could set 60 s). */
    fun scanIntervalMs8772(secs: Int): Long = (secs * 1000L).coerceIn(10_000L, 15_000L)

    /** Pure: the wait after a batch that took [batchMs], so batches START every [intervalMs] (at least 1 s apart). */
    fun fixedRateWaitMs8772(intervalMs: Long, batchMs: Long): Long = (intervalMs - batchMs.coerceAtLeast(0L)).coerceAtLeast(1_000L)
}
