package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.BotService
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeModeAuthority
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.LockedVenueMarks7392
import com.lifecyclebot.network.ParallelMarkFanout7088
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** V5.0.7419 — independent HOT-mark worker for canonical held positions. */
object HeldHotMarkAuthority7419 {
    private const val LOOP_MS = 750L
    private const val HOT_FRESH_MS = 3_000L
    private const val REQUEST_DEADLINE_MS = 450L
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val dex by lazy { DexscreenerApi() }
    private val providerPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "held-hot-mark-7419").apply { isDaemon = true }
    }
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
        val f = providerPool.submit<T?> { block() }
        return try {
            f.get(REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS) to false
        } catch (_: TimeoutException) {
            f.cancel(true); null to true
        } catch (_: Throwable) {
            f.cancel(true); null to false
        }
    }

    private fun refreshPass() {
        val now = System.currentTimeMillis()
        for (p in activeOpen()) {
            if (runtimeTokenAgeMs(p.mint, now) <= HOT_FRESH_MS) continue
            requests.incrementAndGet()
            try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_REQUEST") } catch (_: Throwable) {}
            val beforeRuntime = try { synchronized(BotService.status.tokens) { BotService.status.tokens[p.mint]?.lastPriceUpdate ?: 0L } } catch (_: Throwable) { 0L }
            val beforeTs = maxOf(currentCanonicalTs(p.mint), beforeRuntime)
            var px = 0.0
            var source = ""
            var timedOut = false
            when (p.assetClass) {
                AssetClass.SOLANA_TOKEN -> {
                    val bare = p.mint.removePrefix("solana|").trim()
                    if (bare.isNotBlank() && !bare.contains("|")) {
                        val lockedPair = bounded { LockedVenueMarks7392.resolve(listOf(bare), dex)[bare] }
                        timedOut = timedOut || lockedPair.second
                        val locked = lockedPair.first
                        if (locked != null && locked.priceUsd.isFinite() && locked.priceUsd > 0.0) {
                            px = locked.priceUsd; source = locked.source
                            try { MarkIdentityRepairAuthority7236.recordLockedVenue7392(p.mint, px, source) } catch (_: Throwable) {}
                        } else {
                            val fanPair = bounded { ParallelMarkFanout7088.resolve7088(listOf(bare))[bare] }
                            timedOut = timedOut || fanPair.second
                            val fan = fanPair.first
                            if (fan != null && fan.priceUsd.isFinite() && fan.priceUsd > 0.0 && !(fan.sourceCount >= 2 && !fan.corroborated)) {
                                px = fan.priceUsd
                                source = if (fan.corroborated) "HELD_HOT_FANOUT_CORROBORATED_7419" else "HELD_HOT_SINGLE_SOURCE_7419"
                            }
                        }
                    }
                }
                AssetClass.CRYPTO_ALT -> {
                    val dynPair = bounded { DynamicAltTokenRegistry.refreshHeldMark7251(p.mint) }
                    timedOut = timedOut || dynPair.second
                    val dyn = dynPair.first
                    if (dyn != null && dyn.freshObservation && dyn.canonicalIdentity.equals(p.mint, true) && dyn.price.isFinite() && dyn.price > 0.0) {
                        px = dyn.price; source = "HELD_HOT_CRYPTO_REGISTRY_7419"
                    }
                }
                else -> {}
            }
            if (timedOut) {
                timeouts.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HELD_HOT_MARK_TIMEOUT") } catch (_: Throwable) {}
            }
            if (!(px.isFinite() && px > 0.0)) {
                val cached = try { CanonicalPriceMarkRegistry6522.getFresh6734(p.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC, now) } catch (_: Throwable) { null }
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
            val publishOk = try { CanonicalPriceMarkRegistry6522.publishRepairedExitEconomic7418(p.mint, px, source) } catch (_: Throwable) { false }
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
            val afterRuntime = try { synchronized(BotService.status.tokens) { BotService.status.tokens[p.mint]?.lastPriceUpdate ?: 0L } } catch (_: Throwable) { 0L }
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
