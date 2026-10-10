package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7977 — the heap is watched and the learning caches shed load before Android's
 * 512 MiB limit is hit. 5.0.7976 died after ~3 h with OutOfMemoryError (allocation
 * failing on an OkHttp reader thread: the heap was already full). The bot had no heap
 * telemetry, so the next report could not say which store grew.
 *
 * Every 15 s: heap used / max. Above [SOFT] the 79xx caches trim themselves (the
 * specialist miner's thin combinations, candle-colour thin keys, the meme-meta /
 * runner-grab / callouts maps, chart-reader read cache). Above [HARD] a GC is requested
 * after the trim. The diag line shows the heap now, the peak, trims done, and each
 * cache's size, so a future crash names its cause.
 */
object MemoryGuard7977 {

    private const val SOFT = 0.75
    private const val HARD = 0.88
    private const val PERIOD_MS = 15_000L

    private val started = AtomicBoolean(false)
    private val softTrims = AtomicLong(0)
    private val hardTrims = AtomicLong(0)
    @Volatile private var lastFrac = 0.0
    @Volatile private var peakFrac = 0.0
    @Volatile private var lastUsedMb = 0L
    @Volatile private var maxMb = 0L

    /** Pure: what to do at this heap fraction (0 = nothing, 1 = trim, 2 = trim + GC). */
    fun level7977(frac: Double): Int = when {
        !frac.isFinite() -> 0
        frac >= HARD -> 2
        frac >= SOFT -> 1
        else -> 0
    }

    fun start7977() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    Thread.sleep(PERIOD_MS)
                    tick()
                } catch (_: InterruptedException) { return@Thread } catch (_: Throwable) {}
            }
        }, "memory-guard-7977").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun tick() {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val used = rt.totalMemory() - rt.freeMemory()
        if (max <= 0L) return
        val frac = used.toDouble() / max
        lastFrac = frac; lastUsedMb = used / (1 shl 20); maxMb = max / (1 shl 20)
        if (frac > peakFrac) peakFrac = frac
        val lvl = level7977(frac)
        if (lvl == 0) return
        trimAll(lvl >= 2)
        if (lvl >= 2) { hardTrims.incrementAndGet(); System.gc() } else softTrims.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (lvl >= 2) "MEMORY_GUARD_HARD_TRIM_7977" else "MEMORY_GUARD_SOFT_TRIM_7977")
            ForensicLogger.lifecycle("MEMORY_GUARD_TRIM_7977", "level=$lvl used=${used / (1 shl 20)}MB max=${max / (1 shl 20)}MB ${sizes()}")
        } catch (_: Throwable) {}
    }

    private fun trimAll(hard: Boolean) {
        try { com.lifecyclebot.engine.truth.SpecialistMiner7972.trim7977(hard) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.chart.CandleColors7968.trim7977(hard) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.market.MemeMeta7973.trim7977() } catch (_: Throwable) {}
        try { RunnerGrab7967.trim7977() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.TailHunter7996.trim7977() } catch (_: Throwable) {}
        try { PumpCallouts7968.trim7977() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.chart.ChartReader7950.trim7977() } catch (_: Throwable) {}
        // V5.0.7979 — the token archive's resident rows (the rest is on the device).
        try { TokenMetaCache.instanceOrNull7979()?.evictToMemoryCap7979(if (hard) 1_500 else 3_000) } catch (_: Throwable) {}
    }

    private fun sizes(): String =
        "miner=${try { com.lifecyclebot.engine.truth.SpecialistMiner7972.size7977() } catch (_: Throwable) { -1 }} " +
            "colours=${try { com.lifecyclebot.engine.chart.CandleColors7968.size7977() } catch (_: Throwable) { -1 }} " +
            "chartReads=${try { com.lifecyclebot.engine.chart.ChartReader7950.size7977() } catch (_: Throwable) { -1 }} " +
            "tokenArchiveResident=${try { TokenMetaCache.snapshotIfPresent()?.liveRows ?: -1 } catch (_: Throwable) { -1 }} " +
            "archiveDiskLoads=${try { TokenMetaCache.instanceOrNull7979()?.diskLoads7979() ?: -1L } catch (_: Throwable) { -1L }} " +
            "watchTokens=${try { BotService.status.tokens.size } catch (_: Throwable) { -1 }}"

    fun statusLine7977(): String =
        "heap=${lastUsedMb}/${maxMb}MB (${"%.0f".format(lastFrac * 100)}%) peak=${"%.0f".format(peakFrac * 100)}% " +
            "softTrims=${softTrims.get()} hardTrims=${hardTrims.get()} ${sizes()}"
}
