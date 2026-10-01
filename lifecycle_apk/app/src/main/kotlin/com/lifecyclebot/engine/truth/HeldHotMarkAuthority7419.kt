package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeModeAuthority
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.LockedVenueMarks7392
import com.lifecyclebot.network.ParallelMarkFanout7088
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** V5.0.7419 — independent HOT-mark worker for canonical held positions. */
object HeldHotMarkAuthority7419 {
    private const val LOOP_MS = 750L
    private const val HOT_FRESH_MS = 3_000L
    private const val REQUEST_DEADLINE_MS = 450L
    private const val BATCH_FANOUT_DEADLINE_MS_7510 = 2_500L
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val dex by lazy { DexscreenerApi() }
    // V5.0.7606 — never queue held-position provider work behind timed-out calls.
    // A fixed two-thread pool allowed one slow locked-venue + fanout pass to occupy
    // both workers even after Future.cancel(true), leaving every later refresh
    // queued behind blocked network I/O. Use a bounded, zero-queue executor:
    // new work either starts immediately on an available worker or is rejected
    // and retried on the next 750ms pass. This preserves forward progress.
    private val providerPool = java.util.concurrent.ThreadPoolExecutor(
        0,
        6,
        15L,
        TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue<Runnable>(),
        { r -> Thread(r, "held-hot-mark-7419").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )
    private val requests = AtomicLong(0L)
    private val advanced = AtomicLong(0L)
    private val unchanged = AtomicLong(0L)
    private val timeouts = AtomicLong(0L)
    private val fallbacks = AtomicLong(0L)
    private val waitedOnEnrichment = AtomicLong(0L)
    private val waitedOnUi = AtomicLong(0L)
    private val waitedOnKeyless = AtomicLong(0L)

    data class Summary(val requests: Long, val advanced: Long, val unchanged: Long, val timeouts: Long, val fallbacks: Long, val waitedOnEnrichment: Long, val waitedOnUi: Long, val waitedOnKeyless: Long)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            while (isActive && running.get()) {
                try { refreshPass() } catch (_: Throwable) {}
                delay(LOOP_MS)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { job?.cancel() } catch (_: Throwable) {}
        job = null
    }

    private fun activeOpen(): List<CanonicalPositionAuthority6441.Position> {
        val mode = try { if (RuntimeModeAuthority.isPaper()) "paper" else "live" } catch (_: Throwable) { "live" }
        return try { CanonicalPositionAuthority6441.openPositions().filter { it.mode.equals(mode, true) } } catch (_: Throwable) { emptyList() }
    }

    private fun runtimeTokenAgeMs(mint: String, now: Long): Long {
        val ts = try { synchronized(BotService.status.tokens) { BotService.status.tokens[mint] } } catch (_: Throwable) { null }
        val at = ts?.lastPriceUpdate ?: 0L
        return if (at > 0L) (now - at).coerceAtLeast(0L) else Long.MAX_VALUE
    }

    private fun currentCanonicalTs(mint: String): Long = try {
        CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)?.timestampMs ?: 0L
    } catch (_: Throwable) { 0L }

    private fun <T> bounded(block: () -> T?): Pair<T?, Boolean> {
        val f = try {
            providerPool.submit<T?> { block() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { PipelineHealthCollector.labelInc("HELD_HOT_PROVIDER_POOL_SATURATED_7606") } catch (_: Throwable) {}
            return null to true
        }
        return try {
            f.get(REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS) to false
        } catch (_: TimeoutException) {
            f.cancel(true); null to true
        } catch (_: Throwable) {
            f.cancel(true); null to false
        }
    }

    private fun <T> boundedBatch7510(block: () -> T?): Pair<T?, Boolean> {
        val f = try {
            providerPool.submit<T?> { block() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { PipelineHealthCollector.labelInc("HELD_HOT_PROVIDER_POOL_SATURATED_7606") } catch (_: Throwable) {}
            return null to true
        }
        return try {
            f.get(BATCH_FANOUT_DEADLINE_MS_7510, TimeUnit.MILLISECONDS) to false
        } catch (_: TimeoutException) {
            f.cancel(true); null to true
        } catch (_: Throwable) {
            f.cancel(true); null to false
        }
    }

    private fun refreshPass() {
        val now = System.currentTimeMillis()
        val stale = activeOpen().filter { runtimeTokenAgeMs(it.mint, now) > HOT_FRESH_MS }
        if (stale.isEmpty()) return

        // V5.0.7510 — held Solana positions are a book, not N independent
        // discovery candidates. Resolve the book once per pass.
        val solanaByBare7510 = LinkedHashMap<String, CanonicalPositionAuthority6441.Position>()
        for (p in stale) {
            if (p.assetClass != AssetClass.SOLANA_TOKEN) continue
            val bare = p.mint.removePrefix("solana|").trim()
            if (bare.isNotBlank() && !bare.contains("|")) solanaByBare7510[bare] = p
        }

        // V5.0.7546 — price the held Solana book from locked venue and the
        // corroborated general fanout CONCURRENTLY under one whole-book deadline.
        // 7510 resolved locked venue first and only then started the fanout for
        // unresolved mints, so the pass latency was roughly
        //   lockedVenueChunks + 2.5s fanout
        // and a 750ms worker routinely took several seconds. The risk clock is
        // now O(1) (§7545), so stale marks are the remaining latency source.
        //
        // Trust semantics do not change:
        //   locked venue wins when present;
        //   otherwise a corroborated fanout may publish;
        //   single-source fanout stays non-authoritative.
        val heldBare7546 = solanaByBare7510.keys.toList()
        val lockedFuture7546 = if (heldBare7546.isNotEmpty()) {
            try { providerPool.submit<Map<String, LockedVenueMarks7392.Mark>> {
                LockedVenueMarks7392.resolve(heldBare7546, dex)
            } } catch (_: Throwable) { null }
        } else null
        val fanFuture7546 = if (heldBare7546.isNotEmpty()) {
            try { providerPool.submit<Map<String, ParallelMarkFanout7088.Mark7088>> {
                ParallelMarkFanout7088.resolve7088(heldBare7546)
            } } catch (_: Throwable) { null }
        } else null

        val passStart7546 = System.currentTimeMillis()
        fun remaining7546(): Long =
            (BATCH_FANOUT_DEADLINE_MS_7510 - (System.currentTimeMillis() - passStart7546))
                .coerceAtLeast(1L)

        var lockedTimedOut7546 = false
        var fanTimedOut7546 = false
        val locked7510 = if (lockedFuture7546 != null) {
            try {
                lockedFuture7546.get(remaining7546(), TimeUnit.MILLISECONDS) ?: emptyMap()
            } catch (_: TimeoutException) {
                lockedTimedOut7546 = true
                lockedFuture7546.cancel(true)
                emptyMap()
            } catch (_: Throwable) {
                lockedFuture7546.cancel(true)
                emptyMap()
            }
        } else emptyMap()
        val fan7510 = if (fanFuture7546 != null) {
            try {
                fanFuture7546.get(remaining7546(), TimeUnit.MILLISECONDS) ?: emptyMap()
            } catch (_: TimeoutException) {
                fanTimedOut7546 = true
                fanFuture7546.cancel(true)
                emptyMap()
            } catch (_: Throwable) {
                fanFuture7546.cancel(true)
                emptyMap()
            }
        } else emptyMap()

        if (lockedTimedOut7546 || fanTimedOut7546) {
            timeouts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("HELD_HOT_BOOK_DEADLINE_TIMEOUT_7546")
                if (lockedTimedOut7546) PipelineHealthCollector.labelInc("HELD_HOT_LOCKED_VENUE_TIMEOUT_7546")
                if (fanTimedOut7546) PipelineHealthCollector.labelInc("HELD_HOT_BATCH_FANOUT_TIMEOUT_7546")
            } catch (_: Throwable) {}
        }
        try {
            PipelineHealthCollector.labelInc("HELD_HOT_BOOK_PARALLEL_PASS_7546")
        } catch (_: Throwable) {}

        try {
            PipelineHealthCollector.labelInc("HELD_HOT_BATCH_PASS_7510")
            if (locked7510.isNotEmpty()) PipelineHealthCollector.labelInc("HELD_HOT_BATCH_LOCKED_PRICED_7510")
            if (fan7510.isNotEmpty()) PipelineHealthCollector.labelInc("HELD_HOT_BATCH_FANOUT_PRICED_7510")
        } catch (_: Throwable) {}

        for (p in stale) {
            requests.incrementAndGet()
            try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_REQUEST") } catch (_: Throwable) {}
            val beforeRuntime = try {
                synchronized(BotService.status.tokens) {
                    BotService.status.tokens[p.mint]?.lastPriceUpdate ?: 0L
                }
            } catch (_: Throwable) { 0L }
            val beforeTs = maxOf(currentCanonicalTs(p.mint), beforeRuntime)
            var px = 0.0
            var source = ""
            var timedOut = false

            when (p.assetClass) {
                AssetClass.SOLANA_TOKEN -> {
                    val bare = p.mint.removePrefix("solana|").trim()
                    val locked = locked7510[bare]
                    if (locked != null && locked.priceUsd.isFinite() && locked.priceUsd > 0.0) {
                        px = locked.priceUsd
                        source = locked.source
                        try {
                            MarkIdentityRepairAuthority7236.recordLockedVenue7392(p.mint, px, source)
                        } catch (_: Throwable) {}
                    } else {
                        val fan = fan7510[bare]
                        if (fan != null && fan.priceUsd.isFinite() && fan.priceUsd > 0.0 &&
                            !(fan.sourceCount >= 2 && !fan.corroborated)
                        ) {
                            px = fan.priceUsd
                            source = if (fan.corroborated) {
                                "HELD_HOT_FANOUT_CORROBORATED_7419"
                            } else {
                                "HELD_HOT_SINGLE_SOURCE_7419"
                            }
                        }
                    }
                }
                AssetClass.CRYPTO_ALT -> {
                    // Dynamic cross-asset marks remain exact-identity and
                    // independent of the Solana batch.
                    val dynPair = bounded { DynamicAltTokenRegistry.refreshHeldMark7251(p.mint) }
                    timedOut = timedOut || dynPair.second
                    val dyn = dynPair.first
                    if (dyn != null && dyn.freshObservation &&
                        dyn.canonicalIdentity.equals(p.mint, true) &&
                        dyn.price.isFinite() && dyn.price > 0.0
                    ) {
                        px = dyn.price
                        source = "HELD_HOT_CRYPTO_REGISTRY_7419"
                    }
                }
                else -> {}
            }

            if (timedOut) {
                timeouts.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_TIMEOUT") } catch (_: Throwable) {}
            }

            if (!(px.isFinite() && px > 0.0)) {
                val cached = try {
                    CanonicalPriceMarkRegistry6522.getFresh6734(
                        p.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC, now
                    )
                } catch (_: Throwable) { null }
                if (cached != null && cached.baseMint.equals(p.mint, true)) {
                    px = try { cached.priceUsd.value.toDouble() } catch (_: Throwable) { 0.0 }
                    source = cached.source.ifBlank { "HELD_HOT_FALLBACK_7419" }
                    if (px.isFinite() && px > 0.0) {
                        fallbacks.incrementAndGet()
                        try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_FALLBACK") } catch (_: Throwable) {}
                    }
                }
            }

            if (!(px.isFinite() && px > 0.0)) {
                unchanged.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_UNCHANGED") } catch (_: Throwable) {}
                continue
            }

            // V5.0.7539 — preserve the proof produced by ParallelMarkFanout7088.
            // A corroborated fanout is >=2 independent feeds agreeing (or
            // authoritative curve state). 7419 previously renamed that result
            // and then discarded the proof, so the exit registry rejected it.
            // Single-source fanout stays non-authoritative.
            val verified7424 = source.startsWith("LOCKED_VENUE_") ||
                source == "HELD_HOT_CRYPTO_REGISTRY_7419" ||
                source == "HELD_HOT_FANOUT_CORROBORATED_7419"
            val publishOk = try {
                CanonicalPriceMarkRegistry6522.publishRepairedExitEconomic7418(
                    p.mint, px, source, verifiedIdentity7424 = verified7424,
                )
            } catch (_: Throwable) { false }
            try {
                when {
                    publishOk && source == "HELD_HOT_FANOUT_CORROBORATED_7419" ->
                        PipelineHealthCollector.labelInc("HELD_HOT_CORROBORATED_FANOUT_PUBLISHED_7539")
                    !publishOk && source == "HELD_HOT_SINGLE_SOURCE_7419" ->
                        PipelineHealthCollector.labelInc("HELD_HOT_SINGLE_SOURCE_NOT_PROMOTED_7539")
                    !publishOk && px > 0.0 ->
                        PipelineHealthCollector.labelInc("HELD_HOT_VALID_PRICE_PUBLISH_REFUSED_7539")
                }
            } catch (_: Throwable) {}

            if (publishOk) {
                try {
                    synchronized(BotService.status.tokens) {
                        BotService.status.tokens[p.mint]?.let { ts ->
                            ts.lastPrice = px
                            ts.lastPriceSource = source
                            ts.lastPriceUpdate = System.currentTimeMillis()
                        }
                    }
                } catch (_: Throwable) {}
            }

            val afterRuntime = try {
                synchronized(BotService.status.tokens) {
                    BotService.status.tokens[p.mint]?.lastPriceUpdate ?: 0L
                }
            } catch (_: Throwable) { 0L }
            val afterTs = maxOf(currentCanonicalTs(p.mint), afterRuntime)
            if (publishOk && afterTs > beforeTs) {
                advanced.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_ADVANCED") } catch (_: Throwable) {}
            } else {
                unchanged.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_UNCHANGED") } catch (_: Throwable) {}
            }
        }
    }
    fun summary(): Summary = Summary(requests.get(), advanced.get(), unchanged.get(), timeouts.get(), fallbacks.get(), waitedOnEnrichment.get(), waitedOnUi.get(), waitedOnKeyless.get())
    fun statusLine(): String {
        val s = summary()
        return "req=${s.requests} advanced=${s.advanced} unchanged=${s.unchanged} timeout=${s.timeouts} fallback=${s.fallbacks} waitEnrich=${s.waitedOnEnrichment} waitUi=${s.waitedOnUi} waitKeyless=${s.waitedOnKeyless}"
    }
}
