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

    // ── V5.0.8025 — the token map is the largest thing the trims never touched ──
    // 5.0.8023: heap 92% (peak 100%), 22 hard trims, worst loop 38-55 s, with 569 token rows each carrying
    // up to 460 candles while the watchlist is capped at 220. On a HARD trim, rows that are not on the
    // watchlist, not open (runtime or canonical live), not ticketed and not priced for 30 minutes go, oldest
    // first, down to [KEEP_TOKENS_8025]. A dropped coin comes back through intake like any new one.
    const val KEEP_TOKENS_8025 = 300
    // V5.0.8027 — 30 min of "no price" never came: background pricing keeps refreshing rows the watchlist has
    // already dropped, so 8026 pruned 0 rows and died of OutOfMemoryError at 22.7 min (564-672 rows vs a
    // 220 watchlist). The clock is now the row's age since intake, 10 min, and every trim (soft or hard) prunes.
    private const val STALE_TOKEN_MS_8025 = 10L * 60_000L
    private val tokensPruned8025 = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure: may a token row be pruned? */
    fun prunable8025(watched: Boolean, open: Boolean, ticketed: Boolean, staleMs: Long): Boolean =
        !watched && !open && !ticketed && staleMs >= STALE_TOKEN_MS_8025

    private fun pruneTokens8025(): Int {
        val tokens = BotService.status.tokens
        if (tokens.size <= KEEP_TOKENS_8025) return 0
        val now = System.currentTimeMillis()
        val watch = try { GlobalTradeRegistry.getWatchlist().toHashSet() } catch (_: Throwable) { return 0 }
        val liveOpen = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.activeMintProjections6490("live").map { it.mint }.toHashSet() } catch (_: Throwable) { return 0 }
        val victims = tokens.entries.asSequence()
            .filter { (m, ts) ->
                val last = ts.addedToWatchlistAt
                prunable8025(m in watch, ts.position.isOpen || m in liveOpen,
                    try { RunnerPlay8018.ticketActive8018(m, now) } catch (_: Throwable) { true }, now - last)
            }
            .sortedBy { maxOf(it.value.lastPriceUpdate, it.value.addedToWatchlistAt) }
            .map { it.key }
            .take(tokens.size - KEEP_TOKENS_8025)
            .toList()
        if (victims.isEmpty()) return 0
        synchronized(tokens) { victims.forEach { tokens.remove(it) } }
        tokensPruned8025.addAndGet(victims.size.toLong())
        try { PipelineHealthCollector.labelInc("MEMORY_GUARD_TOKENS_PRUNED_8025") } catch (_: Throwable) {}
        return victims.size
    }

    private fun trimAll(hard: Boolean) {
        try { pruneTokens8025() } catch (_: Throwable) {}
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
            "watchTokens=${try { BotService.status.tokens.size } catch (_: Throwable) { -1 }} tokensPruned8025=${tokensPruned8025.get()}"

    fun statusLine7977(): String =
        "heap=${lastUsedMb}/${maxMb}MB (${"%.0f".format(lastFrac * 100)}%) peak=${"%.0f".format(peakFrac * 100)}% " +
            "softTrims=${softTrims.get()} hardTrims=${hardTrims.get()} ${sizes()}"
}
