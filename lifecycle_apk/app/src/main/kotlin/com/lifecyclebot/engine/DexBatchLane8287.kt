package com.lifecyclebot.engine

import com.lifecyclebot.network.DexPools8285
import com.lifecyclebot.network.DexscreenerApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8287 §THIRTY_MINTS_A_CALL_ON_A_STEADY_CADENCE — the one DexScreener pair batch lane.
 *
 * 5.0.8286: bp/chg/liq dexBatch a=1069 r=76 t=4754. The 8285 lane drained the moment a mint was enqueued, so almost
 * every "batch" carried one mint; it shared a 25 ms limiter slot with every other DexScreener caller and returned
 * LIMITER_REFUSED whenever any call landed in the same instant; a refused batch was re-queued and counted throttled
 * once per mint per retry, up to 8 times; and while the provider's own cooldown ran, the lane kept trying (and
 * counting) instead of waiting it out. CandidateMarkFresh8094 ran a second 30-mint reader on the same host.
 *
 * Now: one lane for every pair read (setup features, candidate marks), at most [MAX_CALLS_PER_MIN_8287] calls a minute
 * ([MIN_SPACING_MS_8287] apart), each collecting up to 30 pending mints ([waitMs8287]: a lone mint waits up to
 * [COLLECT_MS_8287] for company, a held position's mint never waits). The provider's own cooldown is waited out, not
 * counted per mint. The HTTP call carries the near-decision class for the shared host budget (DexHostBudget8287).
 * Nothing is inferred for a mint the answer did not list.
 */
object DexBatchLane8287 {
    // V5.0.8382 — 60 a minute left 119 mints pending and chg / bp unfetched on 1,314 setup reads (dexPair:NOT_FETCHED) in
    // 5.0.8381 while DexScreener's host bucket (DexHostBudget8287, 180-270/min) had room: the pair lane is the one reader of
    // chg / bp / liq for every coin, so it takes two thirds of the host's base rate. Held exit marks keep their priority.
    const val MAX_CALLS_PER_MIN_8287 = 120
    const val MIN_SPACING_MS_8287 = 60_000L / MAX_CALLS_PER_MIN_8287
    const val COLLECT_MS_8287 = 750L
    private const val MAX_COOLDOWN_WAIT_MS_8287 = 30_000L
    private const val MAX_REFUSALS_8287 = 4

    private val running = AtomicBoolean(false)
    @Volatile private var lastBatchAtMs = 0L
    private val api by lazy { DexscreenerApi() }
    private val batches = AtomicLong(0L)
    private val mintsSent = AtomicLong(0L)
    private val answered = AtomicLong(0L)
    private val refusedBatches = AtomicLong(0L)
    private val cooldownWaits = AtomicLong(0L)

    /**
     * Pure: ms to wait before the next batch at this moment; 0 = send now, -1 = nothing pending. Spacing keeps the lane
     * under [MAX_CALLS_PER_MIN_8287]; a batch that is not yet full and holds no held-position mint waits for its oldest
     * mint to have waited [COLLECT_MS_8287], so mints arriving together share one call.
     */
    fun waitMs8287(pending: Int, oldestAgeMs: Long, sinceLastMs: Long, heldPending: Boolean): Long {
        if (pending <= 0) return -1L
        val spacing = (MIN_SPACING_MS_8287 - sinceLastMs).coerceAtLeast(0L)
        val collect = if (pending >= DexPools8285.BATCH_MAX_8285 || heldPending) 0L else (COLLECT_MS_8287 - oldestAgeMs).coerceAtLeast(0L)
        return maxOf(spacing, collect)
    }

    /** Queue [mint] for the lane at its priority (one pending request per mint across every asker) and start the lane. */
    fun enqueue8287(mint: String, handoff: Boolean = false): Boolean {
        if (mint.trim().length !in 32..44) return false
        val queued = ProviderLanes8285.enqueue8285(ProviderLanes8285.DEX_8285, ProviderLanes8285.KIND_PAIR_8285, mint.trim(),
            ProviderLanes8285.prioFor8285(mint.trim(), handoff))
        kick8287()
        return queued
    }

    /** Start the lane's worker unless it is running. */
    fun kick8287() {
        if (!running.compareAndSet(false, true)) return
        try {
            Thread({
                var drained = false
                try { drained = loop8287() } catch (_: Throwable) {
                } finally {
                    running.set(false)
                    // a mint queued while the worker found the lane empty would otherwise wait for the next asker
                    // (never after a cooldown / refusal exit: the next asker restarts the lane, no spin)
                    if (drained && pending8287() > 0) kick8287()
                }
            }, "dex-batch-8287").apply { isDaemon = true }.start()
        } catch (_: Throwable) { running.set(false) }
    }

    private fun pending8287(): Int = ProviderLanes8285.pendingCount8285(ProviderLanes8285.DEX_8285, ProviderLanes8285.KIND_PAIR_8285)

    private fun sleep8287(ms: Long): Boolean = try { Thread.sleep(ms.coerceAtLeast(1L)); true } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    /** The worker: true when it stopped because nothing was pending, false after a cooldown / refusal exit. */
    private fun loop8287(): Boolean {
        val lanes = ProviderLanes8285
        var refusals = 0
        while (refusals < MAX_REFUSALS_8287) {
            val now = System.currentTimeMillis()
            val cooling = lanes.coolingLeftMs8287(lanes.DEX_8285, now)
            if (cooling > 0L) {
                // the provider's own cooldown is waited out (bounded), never counted against each pending mint
                if (cooling > MAX_COOLDOWN_WAIT_MS_8287) return false
                cooldownWaits.incrementAndGet()
                if (!sleep8287(cooling)) return false
                continue
            }
            val w = waitMs8287(pending8287(), lanes.oldestPendingAgeMs8287(lanes.DEX_8285, lanes.KIND_PAIR_8285, now),
                now - lastBatchAtMs, lanes.heldPending8287(lanes.DEX_8285, lanes.KIND_PAIR_8285))
            if (w < 0L) return true
            if (w > 0L) { if (!sleep8287(w)) return false; continue }
            val batch = lanes.drain8285(lanes.DEX_8285, lanes.KIND_PAIR_8285, DexPools8285.BATCH_MAX_8285)
            if (batch.isEmpty()) return true
            lastBatchAtMs = now
            batches.incrementAndGet()
            mintsSent.addAndGet(batch.size.toLong())
            val r = try { api.batchPairs8285(batch) } catch (_: Throwable) { null }
            try { PipelineHealthCollector.labelInc("SETUP_PAIR_FETCH_8191") } catch (_: Throwable) {}
            noteBatch8287(batch, r)
            if (r == null || r.cause == DexscreenerApi.BatchCause8087.LIMITER_REFUSED || r.cause == DexscreenerApi.BatchCause8087.PROVIDER_FAILED) {
                refusals++
                refusedBatches.incrementAndGet()
                for (m in batch) lanes.enqueue8285(lanes.DEX_8285, lanes.KIND_PAIR_8285, m, lanes.prioFor8285(m))
                if (!sleep8287(MIN_SPACING_MS_8287 * refusals)) return false
            } else refusals = 0
        }
        return false
    }

    /** Per-mint counters (one attempt per mint per call sent), arrivals wake the held defers, prices go to the mark refresh. */
    private fun noteBatch8287(batch: List<String>, r: DexscreenerApi.BatchPairs8285?) {
        val throttled = r == null || r.cause == DexscreenerApi.BatchCause8087.LIMITER_REFUSED
        val outcome = if (throttled) FeatureSourceCounters8285.THROTTLED else FeatureSourceCounters8285.ATTEMPT
        repeat(batch.size) { for (f in PAIR_FEATURES_8287) FeatureSourceCounters8285.note8285(f, "dexBatch", outcome) }
        for (m in r?.answered.orEmpty()) {
            answered.incrementAndGet()
            for (f in PAIR_FEATURES_8287) FeatureSourceCounters8285.note8285(f, "dexBatch", FeatureSourceCounters8285.RETURNED)
            try {
                PipelineHealthCollector.labelInc("SETUP_PAIR_FETCH_ANSWERED_8252")
                com.lifecyclebot.engine.truth.CandidateAdmission8136.evidenceArrived8252(m, com.lifecyclebot.engine.truth.CandidateAdmission8136.PAIR_FEATURES_8252)
            } catch (_: Throwable) {}
        }
        if (r != null && !throttled) try {
            com.lifecyclebot.engine.truth.CandidateMarkFresh8094.onLaneAnswer8287(batch, r.prices8287)
        } catch (_: Throwable) {}
    }

    private val PAIR_FEATURES_8287 = listOf("bp", "chg", "liq")

    /** dexLane8287[calls=.. mints=.. perCall=.. answered=.. refusedCalls=.. cooldownWaits=.. pending=..] */
    fun statusLine8287(): String {
        val b = batches.get()
        val per = if (b > 0L) "%.1f".format(mintsSent.get().toDouble() / b) else "-"
        return "dexLane8287[calls=$b mints=${mintsSent.get()} perCall=$per answered=${answered.get()} " +
            "refusedCalls=${refusedBatches.get()} cooldownWaits=${cooldownWaits.get()} pending=${pending8287()} maxPerMin=$MAX_CALLS_PER_MIN_8287]"
    }
}
