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
                    // V5.0.8136 — a hard trim that recovered nothing is re-checked sooner.
                    Thread.sleep(if (escalation8136 > 0) ESCALATED_PERIOD_MS_8136 else PERIOD_MS)
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
        pressure8136 = RetentionPolicy8136.pressure8136(frac, escalation8136)
        if (lvl == 0) { escalation8136 = 0; return }
        trimAll(lvl >= 2)
        if (lvl >= 2) { hardTrims.incrementAndGet(); System.gc() } else softTrims.incrementAndGet()
        if (lvl >= 2) noteHardTrimOutcome8136(frac, rt, max)
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

    // V5.0.8087 — 8086 sat at 500/512 MB: candle histories of rows nobody watches or holds were kept at full depth,
    // the smart-chart read cache never dropped a stale mint, and the mint registry was only capped at restore/save.
    private const val COLD_ROW_CANDLES_8087 = 120
    private val candlesTrimmed8087 = java.util.concurrent.atomic.AtomicLong(0)

    private fun trimColdHistories8087(): Int {
        val watch = try { GlobalTradeRegistry.getWatchlist().toHashSet() } catch (_: Throwable) { return 0 }
        var dropped = 0
        val rows = try { synchronized(BotService.status.tokens) { BotService.status.tokens.entries.toList() } } catch (_: Throwable) { return 0 }
        for ((mint, ts) in rows) {
            if (ts.position.isOpen || mint in watch) continue
            try {
                synchronized(ts.history) { while (ts.history.size > COLD_ROW_CANDLES_8087) { ts.history.removeFirst(); dropped++ } }
            } catch (_: Throwable) {}
        }
        if (dropped > 0) candlesTrimmed8087.addAndGet(dropped.toLong())
        return dropped
    }

    private fun trimAll(hard: Boolean) {
        try { pruneTokens8025() } catch (_: Throwable) {}
        try { prunePressure8136() } catch (_: Throwable) {}
        try { trimBoundedCaches8136(hard) } catch (_: Throwable) {}
        try { SmartChartCache.pruneStale8087() } catch (_: Throwable) {}
        try { MemeMintRegistry.evictStale(); MemeMintRegistry.capToMax8033() } catch (_: Throwable) {}
        if (hard) try { trimColdHistories8087() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.SpecialistMiner7972.trim7977(hard) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.chart.CandleColors7968.trim7977(hard) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.market.MemeMeta7973.trim7977() } catch (_: Throwable) {}
        try { RunnerGrab7967.trim7977() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.TailHunter7996.trim7977() } catch (_: Throwable) {}
        try { PumpCallouts7968.trim7977() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.chart.ChartReader7950.trim7977() } catch (_: Throwable) {}
        // V5.0.7979 — the token archive's resident rows (the rest is on the device).
        // V5.0.8119 — eviction skips unflushed rows; 8117 held 5192 rows with 4732 dirty, so the cap never bit.
        // Flush the backlog here (guard thread, not UI) so the rows become evictable.
        try { TokenMetaCache.instanceOrNull7979()?.let { c -> c.flushNow(); c.evictToMemoryCap7979(if (hard) 600 else 1_000) } } catch (_: Throwable) {}
    }

    // ── V5.0.8136 — pressure-aware retention (RetentionPolicy8136) ──
    private const val ESCALATED_PERIOD_MS_8136 = 5_000L
    @Volatile private var pressure8136 = RetentionPolicy8136.Pressure.NORMAL
    @Volatile private var escalation8136 = 0
    @Volatile private var lastRecoveredMb8136 = 0L
    private val pressurePruned8136 = AtomicLong(0)
    private val ineffectiveHardTrims8136 = AtomicLong(0)
    private val tapesFreed8136 = AtomicLong(0)

    /** Critical evidence a prune must never drop: runner ticket, a balance-proof wait, a wallet-tracked row. */
    private fun critical8136(mint: String, nowMs: Long): Boolean =
        (try { RunnerPlay8018.ticketActive8018(mint, nowMs) } catch (_: Throwable) { true }) ||
            (try { com.lifecyclebot.engine.sell.BalanceProofWaitState.isWaiting(mint) } catch (_: Throwable) { true }) ||
            (try { HostWalletTokenTracker.hasTrackedPosition(mint) } catch (_: Throwable) { true })

    /** Under HARD/CRITICAL pressure, free token rows by relevance regardless of age. */
    private fun prunePressure8136(): Int {
        val p = pressure8136
        if (p != RetentionPolicy8136.Pressure.HARD && p != RetentionPolicy8136.Pressure.CRITICAL) return 0
        val tokens = BotService.status.tokens
        val now = System.currentTimeMillis()
        val watch = try { GlobalTradeRegistry.getWatchlist().toHashSet() } catch (_: Throwable) { return 0 }
        val liveOpen = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.activeMintProjections6490("live").map { it.mint }.toHashSet() } catch (_: Throwable) { return 0 }
        val sealed = try { FdgReEvalThrottle.sealedMints8136(now) } catch (_: Throwable) { emptySet() }
        val snapshot = try { synchronized(tokens) { tokens.entries.toList() } } catch (_: Throwable) { return 0 }
        val rows = snapshot.map { (m, ts) ->
            RetentionPolicy8136.Row(
                mint = m,
                watched = m in watch,
                open = ts.position.isOpen || m in liveOpen,
                admission = m in sealed || com.lifecyclebot.engine.truth.AdmissionProspect8136.isActive8136(m, now),
                critical = critical8136(m, now),
                addedAtMs = ts.addedToWatchlistAt,
                lastTouchMs = maxOf(ts.lastPriceUpdate, ts.addedToWatchlistAt),
            )
        }
        var removed = 0
        for (v in RetentionPolicy8136.victims8136(rows, p, now)) {
            // A watched row leaves the watchlist first; the registry refuses any mint still held anywhere.
            if (v.watched && !(try { GlobalTradeRegistry.removeFromWatchlist(v.mint, "MEMORY_PRESSURE_8136") } catch (_: Throwable) { false })) continue
            if (synchronized(tokens) { tokens.remove(v.mint) } != null) removed++
        }
        if (removed > 0) {
            pressurePruned8136.addAndGet(removed.toLong())
            try { PipelineHealthCollector.labelInc("MEMORY_GUARD_PRESSURE_PRUNED_8136") } catch (_: Throwable) {}
        }
        return removed
    }

    /** Chart tapes, decision seals, authority-proof caches, token archive: bounded harder as pressure rises. */
    private fun trimBoundedCaches8136(hard: Boolean) {
        val p = pressure8136
        try { FdgReEvalThrottle.trim8136() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248.trim8136() } catch (_: Throwable) {}
        if (!hard) return
        val now = System.currentTimeMillis()
        val tapeCap = RetentionPolicy8136.chartTapeCap8136(p)
        if (tapeCap != null) {
            val freed = try {
                com.lifecyclebot.engine.chart.ChartReader7950.trimUnderPressure8136(tapeCap) { m ->
                    (BotService.status.tokens[m]?.position?.isOpen == true) ||
                        com.lifecyclebot.engine.truth.AdmissionProspect8136.isActive8136(m, now) ||
                        HostWalletTokenTracker.hasTrackedPosition(m)
                }
            } catch (_: Throwable) { 0 }
            if (freed > 0) tapesFreed8136.addAndGet(freed.toLong())
        }
        try { com.lifecyclebot.engine.truth.AuthorityProofPrefetch8136.trim8136() } catch (_: Throwable) {}
        if (p == RetentionPolicy8136.Pressure.CRITICAL) {
            try { TokenMetaCache.instanceOrNull7979()?.evictToMemoryCap7979(RetentionPolicy8136.archiveCap8136(p)) } catch (_: Throwable) {}
        }
    }

    /** After a hard trim + GC: did it recover headroom? If not, escalate (CRITICAL retention, faster re-check). */
    private fun noteHardTrimOutcome8136(fracBefore: Double, rt: Runtime, max: Long) {
        val usedAfter = rt.totalMemory() - rt.freeMemory()
        val fracAfter = usedAfter.toDouble() / max
        lastRecoveredMb8136 = ((fracBefore - fracAfter) * max).toLong() / (1 shl 20)
        val ok = RetentionPolicy8136.recovered8136(fracBefore, fracAfter)
        escalation8136 = RetentionPolicy8136.nextEscalation8136(escalation8136, ok)
        if (ok) return
        ineffectiveHardTrims8136.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MEMORY_GUARD_HARD_TRIM_INEFFECTIVE_8136")
            ForensicLogger.lifecycle(
                "MEMORY_GUARD_HARD_TRIM_INEFFECTIVE_8136",
                "before=${"%.0f".format(fracBefore * 100)}% after=${"%.0f".format(fracAfter * 100)}% recoveredMb=$lastRecoveredMb8136 " +
                    "escalation=$escalation8136 action=critical_retention_next_tick ${sizes()}",
            )
        } catch (_: Throwable) {}
    }

    // ── V5.0.8031 — what is actually big ──
    // 8029 died of OutOfMemoryError at 15.4 min inside QueuedWork (SharedPreferences apply), with the token rows already
    // pruned to 147 and every cache this guard trims small. A SharedPreferences file is held in memory whole and
    // re-serialised whole on every apply(): the census lists the largest prefs files and data files on disk, so the
    // next report names the store that fills the heap.
    @Volatile private var censusAt8031 = 0L
    @Volatile private var census8031 = "-"

    /** Pure: "name:KB" for the [n] largest of [sizes] (bytes). */
    fun topSizes8031(sizes: Map<String, Long>, n: Int = 6): String =
        sizes.entries.sortedByDescending { it.value }.take(n).joinToString(",") { "${it.key.take(32)}:${it.value / 1024}KB" }.ifBlank { "-" }

    private fun census8031(): String {
        val now = System.currentTimeMillis()
        if (now - censusAt8031 < 60_000L) return census8031
        censusAt8031 = now
        census8031 = try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return "-"
            val prefsDir = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs")
            val prefs = prefsDir.listFiles()?.associate { it.name.removeSuffix(".xml") to it.length() }.orEmpty()
            val files = ctx.filesDir.walkTopDown().maxDepth(2).filter { it.isFile }.associate { it.name to it.length() }
            "prefsTotal=${prefs.values.sum() / 1024}KB prefsTop=[${topSizes8031(prefs)}] filesTotal=${files.values.sum() / 1024}KB filesTop=[${topSizes8031(files)}] " +
                "labelLoad=${"%.2f".format(try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.pendingLoad8031() } catch (_: Throwable) { -1.0 })}"
        } catch (_: Throwable) { "census_failed" }
        return census8031
    }

    private fun sizes(): String =
        "miner=${try { com.lifecyclebot.engine.truth.SpecialistMiner7972.size7977() } catch (_: Throwable) { -1 }} " +
            "colours=${try { com.lifecyclebot.engine.chart.CandleColors7968.size7977() } catch (_: Throwable) { -1 }} " +
            "chartReads=${try { com.lifecyclebot.engine.chart.ChartReader7950.size7977() } catch (_: Throwable) { -1 }} " +
            "tokenArchiveResident=${try { TokenMetaCache.snapshotIfPresent()?.liveRows ?: -1 } catch (_: Throwable) { -1 }} " +
            "archiveDiskLoads=${try { TokenMetaCache.instanceOrNull7979()?.diskLoads7979() ?: -1L } catch (_: Throwable) { -1L }} " +
            "watchTokens=${try { BotService.status.tokens.size } catch (_: Throwable) { -1 }} tokensPruned8025=${tokensPruned8025.get()} " +
            "smartChart=${try { SmartChartCache.size() } catch (_: Throwable) { -1 }} mintRegistry=${try { MemeMintRegistry.count() } catch (_: Throwable) { -1 }} " +
            "coldCandlesTrimmed8087=${candlesTrimmed8087.get()} | ${census8031()}"

    fun statusLine7977(): String =
        "heap=${lastUsedMb}/${maxMb}MB (${"%.0f".format(lastFrac * 100)}%) peak=${"%.0f".format(peakFrac * 100)}% " +
            "softTrims=${softTrims.get()} hardTrims=${hardTrims.get()} " +
            "pressure8136=$pressure8136 escalation8136=$escalation8136 pressurePruned8136=${pressurePruned8136.get()} " +
            "ineffectiveHardTrims8136=${ineffectiveHardTrims8136.get()} lastRecoveredMb8136=$lastRecoveredMb8136 tapesFreed8136=${tapesFreed8136.get()} ${sizes()}"
}
