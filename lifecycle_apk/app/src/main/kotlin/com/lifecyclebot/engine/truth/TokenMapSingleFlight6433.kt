package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6433 — TOKEN MAP SINGLE-FLIGHT HYDRATION.
 *
 * OPERATOR (PIPELINE CHOKE spec):
 *   'Implement mint-keyed SINGLE-FLIGHT hydration. One active
 *    hydration Future/Deferred per mint; all consumers await the
 *    same result. Prevent duplicate TOKEN_MAP_START calls.'
 *
 * DESIGN
 * ──────
 * Per-mint CompletableDeferred registry. First caller for a mint
 * launches the hydration work; subsequent callers within the same
 * flight receive the same Deferred. After completion the entry is
 * evicted so future refreshes can proceed (subject to caller-level
 * TTL). Suppresses duplicate hydration events by construction.
 */
object TokenMapSingleFlight6433 {

    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val hydrationsLaunched = AtomicLong(0L)
    private val dedupCoalesced = AtomicLong(0L)
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Register interest in hydrating a mint. If an active hydration
     * exists, return the shared Deferred and count as coalesced. If
     * not, launch a new one via the supplied hydrateBlock and return
     * its Deferred.
     */
    fun claim(mint: String, hydrateBlock: suspend () -> Boolean): CompletableDeferred<Boolean> {
        if (mint.isBlank()) {
            val d = CompletableDeferred<Boolean>()
            d.complete(false)
            return d
        }
        val existing = inflight[mint]
        if (existing != null) {
            dedupCoalesced.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_MAP_SINGLEFLIGHT_COALESCED_6433") } catch (_: Throwable) {}
            return existing
        }
        val fresh = CompletableDeferred<Boolean>()
        val prev = inflight.putIfAbsent(mint, fresh)
        if (prev != null) {
            // Lost race — return the winner and count as coalesced.
            dedupCoalesced.incrementAndGet()
            return prev
        }
        hydrationsLaunched.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TOKEN_MAP_SINGLEFLIGHT_LAUNCHED_6433") } catch (_: Throwable) {}
        scope.launch {
            try {
                fresh.complete(hydrateBlock())
            } catch (t: Throwable) {
                fresh.complete(false)
            } finally {
                inflight.remove(mint, fresh)
            }
        }
        return fresh
    }

    fun statusLine(): String {
        val h = hydrationsLaunched.get(); val d = dedupCoalesced.get()
        val total = h + d
        val dedupPct = if (total > 0) 100.0 * d / total else 0.0
        return "launched=$h coalesced=$d dedupPct=${"%.1f".format(dedupPct)}% inflight=${inflight.size}"
    }
}
