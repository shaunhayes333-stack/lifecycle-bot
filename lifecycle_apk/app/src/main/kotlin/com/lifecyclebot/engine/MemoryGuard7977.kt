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
 *
 * V5.0.8144 — every resident store is registered in [MemoryRegistry8144]: one trim dispatch reaches all of them
 * (the Cortex, the hive book, the structure tapes were never reached) and the status line carries a census of
 * entries and estimated bytes per store, queue depths and evictions (computed on this thread, cached for the UI).
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
                    // V5.0.8144 — and every hard trim is judged on the next, sooner tick.
                    Thread.sleep(if (escalation8136 > 0 || pendingHardTrimFrac8144 > 0.0) ESCALATED_PERIOD_MS_8136 else PERIOD_MS)
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
        // V5.0.8144 — the previous hard trim is judged now, a tick later. It used to be judged right after
        // System.gc(), which Android honours only every other call, so a trim that had freed its stores could read
        // as "ineffective" because nothing had been collected yet.
        val prior8144 = pendingHardTrimFrac8144
        if (prior8144 > 0.0) { pendingHardTrimFrac8144 = 0.0; noteHardTrimOutcome8144(prior8144, frac, max) }
        val lvl = level7977(frac)
        pressure8136 = RetentionPolicy8136.pressure8136(frac, escalation8136)
        try { refreshCensus8144() } catch (_: Throwable) {}
        if (lvl == 0) { escalation8136 = 0; try { periodicResidue8309() } catch (_: Throwable) {}; return }  // V5.0.8309
        trimAll(lvl >= 2)
        if (lvl >= 2) { hardTrims.incrementAndGet(); System.gc() } else softTrims.incrementAndGet()
        if (lvl >= 2) pendingHardTrimFrac8144 = frac
        try {
            PipelineHealthCollector.labelInc(if (lvl >= 2) "MEMORY_GUARD_HARD_TRIM_7977" else "MEMORY_GUARD_SOFT_TRIM_7977")
            ForensicLogger.lifecycle("MEMORY_GUARD_TRIM_7977", "level=$lvl used=${used / (1 shl 20)}MB max=${max / (1 shl 20)}MB ${sizes()} census8144=[$censusLine8144]")
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
                    (try { RunnerPlay8018.ticketActive8018(m, now) } catch (_: Throwable) { true }) || HandoffRetention8185.protects8185(m, now), now - last)  // V5.0.8185
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

    // V5.0.8305 — the guard tick and an emergency trim (NetParseGuard8305, from a network thread) never trim at once.
    private val trimLock8305 = Any()

    private fun trimAll(hard: Boolean) = synchronized(trimLock8305) { trimAllLocked8305(hard) }

    private fun trimAllLocked8305(hard: Boolean) {
        val guardBefore8309 = try { guardPassSnapshot8309() } catch (_: Throwable) { null }  // V5.0.8309
        try { pruneTokens8025() } catch (_: Throwable) {}
        try { prunePressure8136() } catch (_: Throwable) {}
        try { trimBoundedCaches8136(hard) } catch (_: Throwable) {}
        if (hard) try { trimColdHistories8087() } catch (_: Throwable) {}
        // V5.0.8144 — every other store through the registry: one dispatch that reaches each registered store (the
        // 7977 list never reached the Cortex, the hive book, the structure tapes or the policy heads) and reports what
        // each freed, so an ineffective hard trim names the stores that did not shrink.
        try { lastTrim8144 = registry8144.trimAll8144(trimLevel8144(hard, pressure8136)) } catch (_: Throwable) {}
        // V5.0.7979 — the token archive's resident rows (the rest is on the device).
        // V5.0.8119 — eviction skips unflushed rows; 8117 held 5192 rows with 4732 dirty, so the cap never bit.
        // Flush the backlog here (guard thread, not UI) so the rows become evictable.
        try { TokenMetaCache.instanceOrNull7979()?.let { c -> c.flushNow(); c.evictToMemoryCap7979(if (hard) 600 else 1_000) } } catch (_: Throwable) {}
        // V5.0.8309 — the guard's own passes (token rows, cold candles, archive) ran outside the registry: book what they freed.
        if (guardBefore8309 != null) try { lastTrim8144 = lastTrim8144 + guardPassResults8309(guardBefore8309) } catch (_: Throwable) {}
    }

    // ── V5.0.8305 — emergency trim after an OutOfMemoryError was caught on an expendable parse ──
    private const val EMERGENCY_MIN_GAP_MS_8305 = 10_000L
    private val emergencyAt8305 = AtomicLong(0L)
    private val emergencyTrims8305 = AtomicLong(0L)
    private val emergencyCoalesced8305 = AtomicLong(0L)
    @Volatile private var emergencyLastReason8305 = "-"

    /** Pure: may an emergency trim run at [nowMs] when the last ran at [lastMs]? */
    fun emergencyDue8305(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= EMERGENCY_MIN_GAP_MS_8305

    /**
     * V5.0.8305 — a network thread dropped a frame / body because the heap was exhausted: run a hard trim now (at CRITICAL
     * retention) instead of waiting up to 15 s for the next tick, then request a GC. At most once per 10 s (a burst of
     * failing frames coalesces); the next tick judges whether it recovered headroom. Returns true when it ran.
     */
    fun emergencyTrim8305(reason: String): Boolean {
        val now = System.currentTimeMillis()
        val last = emergencyAt8305.get()
        if (!emergencyDue8305(last, now) || !emergencyAt8305.compareAndSet(last, now)) { emergencyCoalesced8305.incrementAndGet(); return false }
        emergencyTrims8305.incrementAndGet()
        emergencyLastReason8305 = reason.take(48)
        val prior8305 = pressure8136
        try {
            pressure8136 = RetentionPolicy8136.Pressure.CRITICAL
            try { trimAll(true) } finally { pressure8136 = prior8305 }   // the next tick re-reads the heap
            hardTrims.incrementAndGet()
            System.gc()
            val rt = Runtime.getRuntime()
            if (rt.maxMemory() > 0L) pendingHardTrimFrac8144 = (rt.totalMemory() - rt.freeMemory()).toDouble() / rt.maxMemory()
        } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("MEMORY_GUARD_EMERGENCY_TRIM_8305")
            ForensicLogger.lifecycle("MEMORY_GUARD_EMERGENCY_TRIM_8305", "reason=$emergencyLastReason8305 ${sizes()}")
        } catch (_: Throwable) {}
        return true
    }

    fun emergencyTrims8305(): Long = emergencyTrims8305.get()

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
                admission = m in sealed || com.lifecyclebot.engine.truth.AdmissionProspect8136.isActive8136(m, now) ||
                    HandoffRetention8185.protects8185(m, now),  // V5.0.8185 — an open hand-off target
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

    /**
     * After a hard trim (judged on the next tick, V5.0.8144): did it recover headroom? If not, escalate (CRITICAL
     * retention, faster re-check) and name the stores the trim left large.
     */
    private fun noteHardTrimOutcome8144(fracBefore: Double, fracAfter: Double, max: Long) {
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
                    "escalation=$escalation8136 action=critical_retention_next_tick " +
                    "unfreed8144=[${MemoryRegistry8144.unfreed8144(lastTrim8144, censusRows8144).joinToString(",")}] ${sizes()}",
            )
        } catch (_: Throwable) {}
    }

    // ── V5.0.8144 — the store registry and the census ──
    @Volatile private var pendingHardTrimFrac8144 = 0.0
    @Volatile private var lastTrim8144: List<MemoryRegistry8144.TrimResult8144> = emptyList()
    @Volatile private var censusRows8144: List<MemoryRegistry8144.Row8144> = emptyList()
    @Volatile private var censusLine8144 = "-"
    private val registry8144: MemoryRegistry8144 = MemoryRegistry8144().also { registerStores8144(it) }

    /** Heap pressure as of the last guard tick (NORMAL before the first). */
    fun pressure8144(): RetentionPolicy8136.Pressure = pressure8136

    /** Pure: the registry trim level for a soft / hard trim at pressure [p]. */
    fun trimLevel8144(hard: Boolean, p: RetentionPolicy8136.Pressure): RetentionPolicy8136.Pressure = when {
        !hard -> RetentionPolicy8136.Pressure.SOFT
        p == RetentionPolicy8136.Pressure.CRITICAL -> RetentionPolicy8136.Pressure.CRITICAL
        else -> RetentionPolicy8136.Pressure.HARD
    }

    /** Store names the guard trims (or counts); exposed for the wiring test. */
    fun registeredStores8144(): List<String> = registry8144.names8144()

    /** Open positions, active admissions and wallet-tracked mints keep their chart / structure tapes. */
    private fun tapeProtected8144(m: String, now: Long): Boolean =
        (BotService.status.tokens[m]?.position?.isOpen == true) ||
            com.lifecyclebot.engine.truth.AdmissionProspect8136.isActive8136(m, now) ||
            HostWalletTokenTracker.hasTrackedPosition(m)

    private fun tokenRowBytes8144(): Long {
        var candles = 0L
        var rows = 0L
        for (ts in BotService.status.tokens.values) { rows++; candles += ts.history.size + ts.history5m.size + ts.history15m.size }
        return candles * 104L + rows * 6_144L
    }

    private fun registerStores8144(r: MemoryRegistry8144) {
        val hard = { p: RetentionPolicy8136.Pressure -> p == RetentionPolicy8136.Pressure.HARD || p == RetentionPolicy8136.Pressure.CRITICAL }
        // Trimmed by the guard's own passes (pruneTokens8025 / prunePressure8136 / trimColdHistories8087 / archive cap).
        r.register8144("tokenRows", 6_144L, { BotService.status.tokens.size }, null) { tokenRowBytes8144() }
        r.register8144("tokenArchive", 2_048L, { TokenMetaCache.snapshotIfPresent()?.liveRows ?: -1 }, null)
        r.register8144("miner", com.lifecyclebot.engine.truth.SpecialistMiner7972.KEY_BYTES_8144, {
            com.lifecyclebot.engine.truth.SpecialistMiner7972.size7977() + com.lifecyclebot.engine.truth.SpecialistMiner7972.devCount8144()
        }, { p -> com.lifecyclebot.engine.truth.SpecialistMiner7972.trim7977(hard(p)) }) {
            com.lifecyclebot.engine.truth.SpecialistMiner7972.size7977() * com.lifecyclebot.engine.truth.SpecialistMiner7972.KEY_BYTES_8144 +
                com.lifecyclebot.engine.truth.SpecialistMiner7972.promotedCount8144() * 220L + com.lifecyclebot.engine.truth.SpecialistMiner7972.devCount8144() * 150L
        }
        // Fixed, preallocated at capacity: never grows, nothing to trim.
        r.register8144("chartLibrary", 0L, { com.lifecyclebot.engine.chart.ChartLibrary7950.size() }, null) { com.lifecyclebot.engine.chart.ChartLibrary7950.residentBytes8144() }
        r.register8144("chartTapes", com.lifecyclebot.engine.chart.ChartReader7950.BUCKET_BYTES_8144, { com.lifecyclebot.engine.chart.ChartReader7950.tapeCount8144() },
            { _ -> com.lifecyclebot.engine.chart.ChartReader7950.trim7977() }) {
            com.lifecyclebot.engine.chart.ChartReader7950.tapeBytes8144() + com.lifecyclebot.engine.chart.ChartReader7950.readCacheCount8144() * 240L
        }
        r.register8144("structureTapes", com.lifecyclebot.engine.chart.ChartReader7950.BUCKET_BYTES_8144, { com.lifecyclebot.engine.chart.StructureTracker7962.tapeCount8144() }, { p ->
            val now = System.currentTimeMillis()
            com.lifecyclebot.engine.chart.StructureTracker7962.trimQuiet8144(if (hard(p)) 5L * 60_000L else 15L * 60_000L, now) { m -> tapeProtected8144(m, now) }
        }) { com.lifecyclebot.engine.chart.StructureTracker7962.tapeBytes8144() }
        r.register8144("candleColours", 96L, { com.lifecyclebot.engine.chart.CandleColors7968.size7977() }, { p -> com.lifecyclebot.engine.chart.CandleColors7968.trim7977(hard(p)) })
        r.register8144("memeMeta", 0L, { -1 }, { _ -> com.lifecyclebot.engine.market.MemeMeta7973.trim7977() })
        r.register8144("runnerGrab", 0L, { -1 }, { _ -> RunnerGrab7967.trim7977() })
        r.register8144("tailHunter", 0L, { -1 }, { _ -> com.lifecyclebot.engine.truth.TailHunter7996.trim7977() })
        r.register8144("pumpCallouts", 0L, { -1 }, { _ -> PumpCallouts7968.trim7977() })
        r.register8144("smartChart", 2_048L, { SmartChartCache.size() }, { _ -> SmartChartCache.pruneStale8087() })
        r.register8144("mintRegistry", 320L, { MemeMintRegistry.count() }, { _ -> MemeMintRegistry.evictStale(); MemeMintRegistry.capToMax8033() })
        r.register8144("cortex", com.lifecyclebot.engine.cortex.Cortex7885.ASSESSMENT_BASE_BYTES_8144, {
            com.lifecyclebot.engine.cortex.Cortex7885.assessCacheCount8144() + com.lifecyclebot.engine.cortex.Cortex7885.pendingCount() +
                com.lifecyclebot.engine.cortex.Cortex7885.gradedCount8144() + com.lifecyclebot.engine.cortex.Cortex7885.entryReadCount8144()
        }, { p -> com.lifecyclebot.engine.cortex.Cortex7885.trim8144(p) }) {
            val per = com.lifecyclebot.engine.cortex.Cortex7885.assessmentBytes8144()
            (com.lifecyclebot.engine.cortex.Cortex7885.assessCacheCount8144() + com.lifecyclebot.engine.cortex.Cortex7885.pendingCount() +
                com.lifecyclebot.engine.cortex.Cortex7885.gradedCount8144() + com.lifecyclebot.engine.cortex.Cortex7885.entryReadCount8144()) * per
        }
        // V5.0.8309 — under HARD the network book goes to its CRITICAL cap (own keys kept; evicted keys page back in at the next
        // boot): at the HARD cap it sat under the cap and every hard trim freed nothing. Estimated at ~290 B a key (key string,
        // map node, DoubleArray(6)) for both the network book and this install's uploaded baseline.
        r.register8144("hiveBook", com.lifecyclebot.engine.truth.HiveEdge8000.AGGREGATE_KEY_BYTES_8144, {
            com.lifecyclebot.engine.truth.HiveEdge8000.aggregateCount8144() + com.lifecyclebot.engine.truth.HiveEdge8000.uploadedCount8144()
        }, { p -> com.lifecyclebot.engine.truth.HiveEdge8000.trim8144(if (hard(p)) RetentionPolicy8136.Pressure.CRITICAL else p) }) {
            (com.lifecyclebot.engine.truth.HiveEdge8000.aggregateCount8144() + com.lifecyclebot.engine.truth.HiveEdge8000.uploadedCount8144()).toLong() * 290L
        }
        // Evidence with its own insert-time caps (6,000 observations / 1,000 cells; 3,000 / 4,000 policy stamps).
        r.register8144("labeler", 1_536L, { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.pendingCount8144() }, null) {
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.pendingCount8144() * 1_536L +
                com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.cellCount8144() * 210L + com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.seenCount8144() * 96L
        }
        r.register8144("policyHeadStamps", 320L, { UnifiedPolicyHead.pendingCount8144() }, null)
        r.register8144("metaPolicyStamps", 128L, { AutonomousMetaPolicy.pendingCount8144() }, null)
        r.register8144("pipelineLabels", 150L, { PipelineHealthCollector.labelKeyCount8144() }, null)
        r.register8144("fdgSeals", 320L, { FdgReEvalThrottle.size() }, null)
        // Queues: a queued coroutine / job holds its continuation and captured state.
        r.register8144("sideEffectQueue", 1_024L, { com.lifecyclebot.util.AppDispatchers.sideEffectQueueDepth8144() }, null)
        r.register8144("cortexQueue", 2_048L, { com.lifecyclebot.engine.cortex.Cortex7885.queuedTasks() }, null)
        // V5.0.8169 — one entry per mint ever seen, never pruned and never counted: the native specialist snapshots
        // (unreadable past 5 s), the desk sheets, the bundle analyses (unreadable once stale). Each trim drops only
        // entries no reader can be served (see the owners' pruners).
        r.register8144("nativeSnapshots", 3_072L, { SpecialistBrainBridge7542.snapshotCount8169() }, { _ -> SpecialistBrainBridge7542.pruneUnreadable8169() })
        r.register8144("deskSheets", 4_096L, { ToolkitSignalSheet.sheetCount8169() }, { _ -> ToolkitSignalSheet.pruneIdleSheets8169() })
        r.register8144("bundleAnalyses", 768L, { BundleDetector.cacheCount8169() }, { _ -> BundleDetector.pruneStale8169() })
        registerStores8289(r)  // V5.0.8289
        registerStores8305(r)  // V5.0.8305
        registerStores8309(r)  // V5.0.8309
    }

    /**
     * V5.0.8305 — stores the census did not know: the insider tracker's holdings / price caches (a whale's dust tokens),
     * the crypto universe registry (fed by the Jupiter verified list), the specialist contributor lists (one per mint
     * ever qualified) and the response bodies being read right now through the shared client.
     */
    private fun registerStores8305(r: MemoryRegistry8144) {
        // V5.0.8309 — trimmed: expired holdings / tx caches always, price and symbol caches under HARD (snapshot kept).
        r.register8144("insiderTracker8305", 160L, { com.lifecyclebot.perps.InsiderWalletTracker.memCount8305() }, { p ->
            com.lifecyclebot.perps.InsiderWalletTracker.trim8309(p == RetentionPolicy8136.Pressure.HARD || p == RetentionPolicy8136.Pressure.CRITICAL)
        }) { com.lifecyclebot.perps.InsiderWalletTracker.estBytes8309() }
        r.register8144("dynAltRegistry8305", 760L, { com.lifecyclebot.perps.DynamicAltTokenRegistry.getTokenCount() }, null)
        r.register8144("contribMerge8305", 360L, { com.lifecyclebot.engine.truth.SpecialistContributorMerge6612.trackedMints() }, { _ ->
            com.lifecyclebot.engine.truth.SpecialistContributorMerge6612.boundMints8305(System.currentTimeMillis())
        })
        r.register8144("mergeQueue8305", 640L, { TokenMergeQueue.size() }, { p ->
            TokenMergeQueue.pruneStale8305(System.currentTimeMillis(), if (p == RetentionPolicy8136.Pressure.SOFT) 30L * 60_000L else 10L * 60_000L)
        })
        r.register8144("httpBodiesInFlight8305", 1_024L, { (com.lifecyclebot.network.HttpBodyCap8305.inFlightBytes8305() / 1024L).toInt() }, null) {
            com.lifecyclebot.network.HttpBodyCap8305.inFlightBytes8305()
        }
    }

    /**
     * V5.0.8309 — 5.0.8306 sat at 473/512 MB with 406 MB unattributed and hard trims freeing nothing. What the census never
     * saw: the per-mint residue a token leaves after its row is pruned (MintResidue8309: a 200-candle chart copy, flow /
     * volatility / smart-money / regime histories, classification and hydration rows — each written for every evaluated
     * mint and removed by nothing), and the learning books (Cortex seat ledgers, lane playbook, cell allocator, trade-shape
     * and behaviour learners), the launch tape's buyer maps and the SharedPreferences mirrors. Residue is swept (non-live
     * mints only); the learning books are counted, never trimmed (memory pressure never resets learning).
     */
    private fun registerStores8309(r: MemoryRegistry8144) {
        val res = MintResidue8309
        res.register8309("smartChartCandles", 14_000L, { SmartChartScanner.residueMap8309() }) { SmartChartScanner.residueBytes8309() }
        res.register8309("regimeHistory", 3_000L, { com.lifecyclebot.v3.scoring.RegimeTransitionAI.residueHistory8309() })
        res.register8309("regimeTransitions", 220L, { com.lifecyclebot.v3.scoring.RegimeTransitionAI.residueTransitions8309() })
        res.register8309("volatilityRegime", 1_100L, { com.lifecyclebot.v3.scoring.VolatilityRegimeAI.residueMap8309() })
        res.register8309("orderFlow", 5_500L, { com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.residueMap8309() })
        res.register8309("smartMoneyDivergence", 1_800L, { com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.residueMap8309() })
        res.register8309("exitScoreHistory", 700L, { DistributionDetector.residueMap8309() })
        res.register8309("structureClass", 260L, { com.lifecyclebot.v3.modes.MarketStructureRouter.residueMap8309() })
        res.register8309("commonSense", 420L, { CommonSenseTradePlaybook.residueMap8309() })
        res.register8309("alphaCache", 120L, { DataPipeline.residueAlpha8309() })
        res.register8309("candleSynthBuckets", 160L, { com.lifecyclebot.engine.truth.LocalCandleSynthesis7055.residueMap8309() })
        res.register8309("pairHydration", 320L, { com.lifecyclebot.engine.truth.PairHydrationState6398.residueMap8309() })
        for (name in res.names8309()) {
            r.register8144("res8309:$name", 0L, { res.countOf8309(name) }, { _ -> sweepResidue8309(name) }) { res.bytesOf8309(name).coerceAtLeast(0L) }
        }
        // Learning books: counted, never trimmed.
        r.register8144("cortexLedgers8309", 0L, { -1 }, null) {
            com.lifecyclebot.engine.cortex.Cortex7885.ledgerBytes8309() + com.lifecyclebot.engine.cortex.CortexExit7897.ledgerBytes8309() +
                com.lifecyclebot.engine.cortex.CortexTiming7900.ledgerBytes8309()
        }
        r.register8144("lanePlaybook8309", 0L, { com.lifecyclebot.engine.cortex.LanePlaybook7907.memCount8309() }, null) {
            com.lifecyclebot.engine.cortex.LanePlaybook7907.estBytes8309()
        }
        r.register8144("cellAllocator8309", 0L, { CellAllocator7962.memCount8309() }, null) { CellAllocator7962.estBytes8309() }
        r.register8144("tradeShape8309", 0L, { com.lifecyclebot.engine.truth.TradeShapeLearner7883.memCount8309() }, null) {
            com.lifecyclebot.engine.truth.TradeShapeLearner7883.estBytes8309()
        }
        r.register8144("behaviorLearning8309", 0L, { BehaviorLearning.memCount8309() }, null) { BehaviorLearning.estBytes8309() }
        // Capped at write by their owners (counted here).
        r.register8144("playbookCache8309", 0L, { -1 }, null) { com.lifecyclebot.engine.cortex.LanePlaybook7907.cacheBytes8309() }
        r.register8144("launchTape8309", 0L, { com.lifecyclebot.engine.market.LaunchTape7921.memCount8309() }, null) {
            com.lifecyclebot.engine.market.LaunchTape7921.estBytes8309()
        }
        r.register8144("pairFlow8309", 170L, { com.lifecyclebot.engine.truth.PairFlow8169.count8309() }, null)
        // The service executors' unbounded queues: each queued launch holds its continuation and captured state (~1 KB+).
        r.register8144("serviceQueues8309", 1_024L, { executorQueued8309() }, null)
        // Android holds every opened SharedPreferences file as a parsed map of Strings (UTF-16: ~2x the XML on disk).
        r.register8144("prefsMirror8309", 0L, { -1 }, null) { prefsBytes8309 * 2L }
        for ((name, kind) in listOf(
            "tokenRows" to "guardPass", "tokenArchive" to "guardPass", "fdgSeals" to "guardPass", "chartLibrary" to "fixed",
            "labeler" to "ledger", "policyHeadStamps" to "ledger", "metaPolicyStamps" to "ledger", "pipelineLabels" to "capped",
            "sideEffectQueue" to "queue", "cortexQueue" to "queue", "dynAltRegistry8305" to "universe", "httpBodiesInFlight8305" to "transient",
            "cortexLedgers8309" to "ledger", "lanePlaybook8309" to "ledger", "cellAllocator8309" to "ledger", "tradeShape8309" to "ledger",
            "behaviorLearning8309" to "ledger", "playbookCache8309" to "capped", "launchTape8309" to "capped", "pairFlow8309" to "capped",
            "prefsMirror8309" to "prefs", "serviceQueues8309" to "queue",
        )) r.markKind8309(name, kind)
    }

    /** V5.0.8309 — queued tasks across the bot service's executors (0 when the service is not running). */
    private fun executorQueued8309(): Int = (BotService.instance?.executorQueues8309() ?: IntArray(0)).filter { it > 0 }.sum()

    /** V5.0.8309 — stores with no trim and no exempt kind (should be empty), and the exempt stores as "name(kind)". */
    fun untrimmedStores8309(): List<String> = registry8144.untrimmed8144()
    fun exemptStores8309(): List<String> = registry8144.exempt8309()

    /** V5.0.8309 — bytes of SharedPreferences XML on disk (census8031, every 60 s). */
    @Volatile private var prefsBytes8309 = 0L
    @Volatile private var residueFirstTickMs8309 = 0L

    /**
     * V5.0.8309 — is [m] live: a token row, watched, open (runtime or canonical live), ticketed, sealed by the FDG, an active
     * admission / hand-off, a balance-proof wait or a wallet-tracked mint. A key shorter than a Solana mint (a crypto-lane
     * symbol shares some of these trackers) is always live. Null when the watchlist or the live projection
     * cannot be read (then nothing is swept).
     */
    private fun liveMints8309(now: Long): ((String) -> Boolean)? {
        val tokens = BotService.status.tokens
        val watch = try { GlobalTradeRegistry.getWatchlist().toHashSet() } catch (_: Throwable) { return null }
        val liveOpen = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.activeMintProjections6490("live").map { it.mint }.toHashSet() } catch (_: Throwable) { return null }
        val sealed = try { FdgReEvalThrottle.sealedMints8136(now) } catch (_: Throwable) { return null }
        return { m ->
            m.length < 32 || tokens.containsKey(m) || m in watch || m in liveOpen || m in sealed || critical8136(m, now) ||
                (try { com.lifecyclebot.engine.truth.AdmissionProspect8136.isActive8136(m, now) } catch (_: Throwable) { true }) ||
                (try { HandoffRetention8185.protects8185(m, now) } catch (_: Throwable) { true })
        }
    }

    /** Registry trim of one residue cache: drop the keys of mints that are no longer live. */
    private fun sweepResidue8309(name: String) {
        val live = liveMints8309(System.currentTimeMillis()) ?: return
        MintResidue8309.sweepOne8309(name, live)
    }

    /** Time-based cleanups their owners define but nothing ever called (per-mint trackers past 30 min / their TTL). */
    private fun staleCleanups8309() {
        try { com.lifecyclebot.v3.scoring.VolatilityRegimeAI.cleanup() } catch (_: Throwable) {}
        try { com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.cleanup() } catch (_: Throwable) {}
        try { com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.cleanup() } catch (_: Throwable) {}
        try { DistributionFadeAvoider.cleanup() } catch (_: Throwable) {}
        try { DataPipeline.cleanup() } catch (_: Throwable) {}
    }

    /** Guard tick at NORMAL pressure: the residue is swept every 2 min (not only once the heap reaches 75%). */
    private fun periodicResidue8309() {
        val now = System.currentTimeMillis()
        if (residueFirstTickMs8309 <= 0L) { residueFirstTickMs8309 = now; return }
        if (!MintResidue8309.sweepDue8309(maxOf(MintResidue8309.lastSweepMs8309(), residueFirstTickMs8309), now)) return
        staleCleanups8309()
        val live = liveMints8309(now) ?: return
        synchronized(trimLock8305) { MintResidue8309.sweep8309(live, now) }
    }

    /** What the guard's own passes see before a trim: token rows, their estimated bytes, resident archive rows. */
    private class GuardPass8309(val rows: Int, val rowBytes: Long, val archive: Int)

    private fun guardPassSnapshot8309(): GuardPass8309 =
        GuardPass8309(BotService.status.tokens.size, tokenRowBytes8144(), TokenMetaCache.snapshotIfPresent()?.liveRows ?: -1)

    private fun guardPassResults8309(b: GuardPass8309): List<MemoryRegistry8144.TrimResult8144> {
        staleCleanups8309()
        val rows = BotService.status.tokens.size
        val archive = TokenMetaCache.snapshotIfPresent()?.liveRows ?: -1
        return listOf(
            MemoryRegistry8144.TrimResult8144("tokenRows(guard)", b.rows, rows, true, b.rowBytes, tokenRowBytes8144()),
            MemoryRegistry8144.TrimResult8144("tokenArchive(guard)", b.archive, archive, true,
                if (b.archive >= 0) b.archive * 2_048L else -1L, if (archive >= 0) archive * 2_048L else -1L),
        )
    }

    /** V5.0.8309 — the residue sweep, what the last trim released per store (KB before -> after) and the exempt stores. */
    fun memLine8309(): String =
        "mem8309[${try { MintResidue8309.line8309() } catch (_: Throwable) { "residue8309[-]" }} " +
            "lastTrimMb8309=[${MemoryRegistry8144.trimMbLine8309(lastTrim8144)}] prefsMirrorKB=${prefsBytes8309 * 2L / 1024L} " +
            "execQ8309=[${try { BotService.instance?.executorQueues8309()?.joinToString(",") ?: "-" } catch (_: Throwable) { "-" }}] " +
            "exempt8309=[${registry8144.exempt8309().joinToString(",")}]]"

    /**
     * V5.0.8289 — the 8283-8286 data stores the census did not know (8286: censusMb=63 unattributedMb=448 at 511/512 MB):
     * the provenance trade tape and its signature window, the candle-quality tags and compatible 1 m store, the feature
     * revision / funnel books, the admission decision caches, the provider lane queue, the DexScreener pool book, the
     * launch-flow tapes and the voter caches. Each counts, estimates its retained bytes, and trims under pressure what no
     * reader can be served (open positions, active admissions and wallet-tracked mints keep their tapes).
     */
    private fun registerStores8289(r: MemoryRegistry8144) {
        val hard = { p: RetentionPolicy8136.Pressure -> p == RetentionPolicy8136.Pressure.HARD || p == RetentionPolicy8136.Pressure.CRITICAL }
        val critical = { p: RetentionPolicy8136.Pressure -> p == RetentionPolicy8136.Pressure.CRITICAL }
        r.register8144("tradeTape8289", com.lifecyclebot.engine.truth.TradeTape8100.TRADE_BYTES_8289, { com.lifecyclebot.engine.truth.TradeTape8100.tradeCount8289() }, { p ->
            val now = System.currentTimeMillis()
            com.lifecyclebot.engine.truth.TradeTape8100.trim8289(hard(p), critical(p), now) { m -> tapeProtected8144(m, now) }
        }) { com.lifecyclebot.engine.truth.TradeTape8100.estBytes8289() }
        r.register8144("candleCoverage8289", com.lifecyclebot.engine.truth.CandleCoverage8284.BAR_BYTES_8289, { com.lifecyclebot.engine.truth.CandleCoverage8284.storeBarCount8289() }, { p ->
            val now = System.currentTimeMillis()
            com.lifecyclebot.engine.truth.CandleCoverage8284.trim8289(hard(p), critical(p), now) { m -> tapeProtected8144(m, now) }
        }) { com.lifecyclebot.engine.truth.CandleCoverage8284.estBytes8289() }
        r.register8144("featureRevision8289", 170L, { com.lifecyclebot.engine.truth.FeatureRevision8283.storeCount8289() }, { p ->
            com.lifecyclebot.engine.truth.FeatureRevision8283.trim8289(hard(p), System.currentTimeMillis())
        }) { com.lifecyclebot.engine.truth.FeatureRevision8283.estBytes8289() }
        r.register8144("featureFunnel8289", 190L, { com.lifecyclebot.engine.truth.FeatureFunnel8283.seenCount8289() }, { p ->
            com.lifecyclebot.engine.truth.FeatureFunnel8283.trim8289(critical(p))
        }) { com.lifecyclebot.engine.truth.FeatureFunnel8283.estBytes8289() }
        r.register8144("admissionDecisions8289", com.lifecyclebot.engine.truth.CandidateAdmission8136.DECISION_BYTES_8289,
            { com.lifecyclebot.engine.truth.CandidateAdmission8136.decisionCacheCount8289() },
            { _ -> com.lifecyclebot.engine.truth.CandidateAdmission8136.trim8289(System.currentTimeMillis()) }) {
            com.lifecyclebot.engine.truth.CandidateAdmission8136.estBytes8289()
        }
        r.register8144("providerLanes8289", 160L, { ProviderLanes8285.pendingTotal8289() }, { _ -> ProviderLanes8285.trim8289(System.currentTimeMillis()) })
        r.register8144("dexPools8289", 420L, { com.lifecyclebot.network.DexPools8285.mintCount8289() }, { p ->
            com.lifecyclebot.network.DexPools8285.trim8289(if (hard(p)) 30L * 60_000L else 2L * 3_600_000L, System.currentTimeMillis())
        }) { com.lifecyclebot.network.DexPools8285.estBytes8289() }
        r.register8144("launchTapes8289", WhaleDetector.LAUNCH_TRADE_BYTES_8289, { WhaleDetector.launchTapeMints8289() }, { p ->
            WhaleDetector.boundLaunchTapes8289(System.currentTimeMillis(), if (hard(p)) WhaleDetector.LAUNCH_TAPE_CAP_8289 / 4 else WhaleDetector.LAUNCH_TAPE_CAP_8289)
        }) { WhaleDetector.estBytes8289() }
        r.register8144("voterCaches8289", 150L, { com.lifecyclebot.engine.cortex.CortexVoters7885.cacheCount8289() }, { _ ->
            com.lifecyclebot.engine.cortex.CortexVoters7885.trim8289(System.currentTimeMillis())
        }) { com.lifecyclebot.engine.cortex.CortexVoters7885.estBytes8289() }
    }

    /**
     * Pure (V5.0.8169): heap used minus what the census can attribute to a store. Large while a hard trim recovers a lot
     * = collectable allocation churn, not a retained holder; large while trims recover nothing = an uncounted holder.
     */
    fun unattributedMb8169(usedMb: Long, censusBytes: Long): Long = (usedMb - censusBytes.coerceAtLeast(0L) / (1L shl 20)).coerceAtLeast(0L)

    /** Guard thread: the census for the status line (counts O(1); tape / token estimates walk at most a few thousand rows). */
    private fun refreshCensus8144() {
        try { census8031() } catch (_: Throwable) {}   // V5.0.8309 — keeps prefsBytes8309 current (cached 60 s)
        bookInsertEvictions8144()
        val rows = registry8144.census8144()
        censusRows8144 = rows
        censusLine8144 = MemoryRegistry8144.line8144(rows)
        censusStatus8144 = buildCensusStatus8144()
        storesLine8305 = storesLine8305(rows)
    }

    /** V5.0.8305 — every registered store (not only the top 8), largest first: "name:count/KB". Built on the guard thread. */
    @Volatile private var storesLine8305 = "-"

    /** Pure: all census rows as "name:count/KB", largest estimate first. */
    fun storesLine8305(rows: List<MemoryRegistry8144.Row8144>): String =
        rows.sortedWith(compareByDescending<MemoryRegistry8144.Row8144> { it.estBytes }.thenBy { it.name })
            .joinToString(",") { "${it.name}:${if (it.count >= 0) it.count.toString() else "?"}/${it.estBytes.coerceAtLeast(0L) / 1024}KB" }
            .ifEmpty { "-" }

    /** Stores that evict at insert time book their evictions into the census. */
    private fun bookInsertEvictions8144() {
        val miner = try { com.lifecyclebot.engine.truth.SpecialistMiner7972.evicted8144() } catch (_: Throwable) { 0L }
        val cortex = try { com.lifecyclebot.engine.cortex.Cortex7885.evicted8144() } catch (_: Throwable) { 0L }
        val hive = try { com.lifecyclebot.engine.truth.HiveEdge8000.evicted8144() } catch (_: Throwable) { 0L }
        val uph = try { UnifiedPolicyHead.pendingEvicted8144() } catch (_: Throwable) { 0L }
        for ((name, total) in listOf("miner" to miner, "cortex" to cortex, "hiveBook" to hive, "policyHeadStamps" to uph)) {
            val booked = insertEvictionsBooked8144[name] ?: 0L
            if (total > booked) { registry8144.noteEvictions8144(name, total - booked); insertEvictionsBooked8144[name] = total }
        }
    }
    private val insertEvictionsBooked8144 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Built on the guard thread each tick; the status line only reads it (never touches the stores). */
    @Volatile private var censusStatus8144 = "census8144=[-]"

    private fun buildCensusStatus8144(): String =
        "census8144=[$censusLine8144] trims8144=${registry8144.trimsRun8144()} untrimmed8144=[${registry8144.untrimmed8144().joinToString(",")}] " +
            "lastTrimUnfreed8144=[${MemoryRegistry8144.unfreed8144(lastTrim8144, censusRows8144).joinToString(",")}] " +
            "sideEffect8144[q=${try { com.lifecyclebot.util.AppDispatchers.sideEffectQueueDepth8144() } catch (_: Throwable) { -1 }} " +
            "active=${try { com.lifecyclebot.util.AppDispatchers.sideEffectActive8144() } catch (_: Throwable) { -1 }} " +
            "rejected=${try { com.lifecyclebot.util.AppDispatchers.sideEffectRejected8144() } catch (_: Throwable) { -1L }}] " +
            "saves8144[uph ${try { UnifiedPolicyHead.saveLine8144() } catch (_: Throwable) { "-" }} · amp ${try { AutonomousMetaPolicy.saveLine8144() } catch (_: Throwable) { "-" }} · " +
            "labelerDeferred=${try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.persistDeferred8144() } catch (_: Throwable) { -1L }}]"

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
            prefsBytes8309 = prefs.values.sum()   // V5.0.8309
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
            "ineffectiveHardTrims8136=${ineffectiveHardTrims8136.get()} lastRecoveredMb8136=$lastRecoveredMb8136 tapesFreed8136=${tapesFreed8136.get()} " +
            "attributed8169=[censusMb=${MemoryRegistry8144.totalBytes8144(censusRows8144) / (1L shl 20)} unattributedMb=${unattributedMb8169(lastUsedMb, MemoryRegistry8144.totalBytes8144(censusRows8144))} " +
            "bundleRestored=${try { BundleDetector.restoredCount8169() } catch (_: Throwable) { -1L }}] ${sizes()} " +
            censusStatus8144 + " " + netLine8305()

    /**
     * V5.0.8305 — the network side of the heap: the shared OkHttp dispatcher's queued / running calls, body-cap refusals and
     * the largest bodies read per endpoint, bytes of bodies being read right now, frames dropped (oversize / parse OOM)
     * and emergency trims. Every store's size is already in census8144 (MemoryRegistry8144.line8144, per store).
     */
    fun netLine8305(): String =
        "net8305[${try { com.lifecyclebot.network.SharedHttpClient.dispatcherLine8305() } catch (_: Throwable) { "dispatcher8305[-]" }} " +
            "${try { com.lifecyclebot.network.HttpBodyCap8305.line8305() } catch (_: Throwable) { "bodyCap8305[-]" }} " +
            "${try { com.lifecyclebot.network.NetParseGuard8305.line8305() } catch (_: Throwable) { "frames8305[-]" }} " +
            "${try { com.lifecyclebot.perps.InsiderWalletTracker.scanLine8305() } catch (_: Throwable) { "insiderScan8305[-]" }} " +
            "emergencyTrims8305=${emergencyTrims8305.get()} coalesced=${emergencyCoalesced8305.get()} last=$emergencyLastReason8305] " +
            "stores8305=[$storesLine8305] " + memLine8309()
}
