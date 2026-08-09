package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6430 §F — SCANNER MINT-LEVEL FAN-IN / CANDIDATE ACCUMULATOR.
 *
 * OPERATOR (V5.0.6424 spec §F):
 *   SCAN_CB=19,493 INTAKE=8,057 LANE_EVAL=1,836 FDG=269 EXEC=121.
 *   'Lizardboy alone was intaked 333 times. Multiple providers are
 *    repeatedly presenting the same mint. Implement mint-level fan-in
 *    BEFORE expensive evaluation.'
 *
 * DESIGN
 * ──────
 * Per-mint accumulator that merges provider evidence and gates
 * expensive evaluation to at most ONE active per (mint,
 * evaluationVersion). Fail-open: if the accumulator hasn't seen the
 * mint or its state is unclear, tryClaimEvaluation returns true so
 * legacy paths still fire.
 *
 * Provider observations are collapsed into a single CandidateEvidence
 * with union of sources, best liquidity, latest volume/mcap/price, and
 * dirtyVersion. Every observation bumps dirtyVersion; evaluation stamps
 * evaluationVersion == dirtyVersion on claim.
 *
 * Re-evaluation TTL: a mint can be re-evaluated after RE_EVAL_TTL_MS
 * OR after a strong evidence change (bumpEvidence with materialChange
 * = true), whichever comes first.
 */
object CandidateAccumulator6430 {

    private const val RE_EVAL_TTL_MS = 3_000L

    data class Evidence(
        val mint: String,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        val sources: MutableSet<String>,
        var bestLiquidityUsd: Double,
        var lastMcapUsd: Double,
        var lastVolumeUsd: Double,
        var lastPriceUsd: Double,
        var dirtyVersion: Long,
        var evaluationVersion: Long,
        var lastEvaluationMs: Long,
        var activeEvaluation: Boolean,
    )

    private val evidence = ConcurrentHashMap<String, Evidence>()
    private val rawHits = AtomicLong(0L)
    private val coalescedCandidates = AtomicLong(0L)
    private val dedupSavedEvaluations = AtomicLong(0L)

    /**
     * Record a provider observation. Called from every scanner ingress
     * (PumpPortal, DexScreener, Raydium, HealingScanner, etc.).
     */
    fun observe(
        mint: String,
        source: String,
        liquidityUsd: Double = -1.0,
        mcapUsd: Double = -1.0,
        volumeUsd: Double = -1.0,
        priceUsd: Double = -1.0,
        materialChange: Boolean = false,
    ) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        rawHits.incrementAndGet()
        val ev = evidence.compute(mint) { _, current ->
            if (current == null) {
                coalescedCandidates.incrementAndGet()
                Evidence(
                    mint = mint,
                    firstSeenMs = now,
                    lastSeenMs = now,
                    sources = mutableSetOf(source),
                    bestLiquidityUsd = liquidityUsd.coerceAtLeast(0.0),
                    lastMcapUsd = mcapUsd,
                    lastVolumeUsd = volumeUsd,
                    lastPriceUsd = priceUsd,
                    dirtyVersion = 1L,
                    evaluationVersion = 0L,
                    lastEvaluationMs = 0L,
                    activeEvaluation = false,
                )
            } else {
                current.lastSeenMs = now
                current.sources.add(source)
                if (liquidityUsd > current.bestLiquidityUsd) current.bestLiquidityUsd = liquidityUsd
                if (mcapUsd > 0.0) current.lastMcapUsd = mcapUsd
                if (volumeUsd > 0.0) current.lastVolumeUsd = volumeUsd
                if (priceUsd > 0.0) current.lastPriceUsd = priceUsd
                if (materialChange) current.dirtyVersion++
                current
            }
        } ?: return
        // Nothing else — evaluation gates happen in tryClaimEvaluation.
    }

    /**
     * Attempt to claim the right to run an expensive evaluation for a
     * mint. Returns true if the caller SHOULD proceed. Returns false
     * and increments dedupSavedEvaluations if another evaluation is
     * active OR the last evaluation was recent enough (TTL) with no
     * material change.
     */
    fun tryClaimEvaluation(mint: String): Boolean {
        if (mint.isBlank()) return true
        val ev = evidence[mint] ?: return true  // fail-open — unknown mint
        val now = System.currentTimeMillis()
        synchronized(ev) {
            if (ev.activeEvaluation) {
                dedupSavedEvaluations.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("CANDIDATE_DEDUP_ACTIVE_6430")
                } catch (_: Throwable) {}
                return false
            }
            val stale = ev.evaluationVersion == ev.dirtyVersion &&
                (now - ev.lastEvaluationMs) < RE_EVAL_TTL_MS
            if (stale) {
                dedupSavedEvaluations.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("CANDIDATE_DEDUP_TTL_6430")
                } catch (_: Throwable) {}
                return false
            }
            ev.activeEvaluation = true
            ev.evaluationVersion = ev.dirtyVersion
            ev.lastEvaluationMs = now
            return true
        }
    }

    /** Called by the evaluator to release the claim (allow re-eval). */
    fun releaseEvaluation(mint: String) {
        val ev = evidence[mint] ?: return
        synchronized(ev) { ev.activeEvaluation = false }
    }

    fun statusLine(): String {
        val ratio = if (rawHits.get() > 0) coalescedCandidates.get().toDouble() / rawHits.get() else 0.0
        return "raw=${rawHits.get()} coalesced=${coalescedCandidates.get()} dedupSaved=${dedupSavedEvaluations.get()} coalesceRatio=${"%.3f".format(ratio)} activeMints=${evidence.count { it.value.activeEvaluation }}"
    }

    /** Pipeline-friendly: how many providers agree on the top mints? */
    fun topProviderConfluence(n: Int = 5): List<Pair<String, Int>> =
        evidence.values.sortedByDescending { it.sources.size }
            .take(n)
            .map { it.mint.take(10) to it.sources.size }

    internal fun resetForTest() {
        evidence.clear(); rawHits.set(0); coalescedCandidates.set(0); dedupSavedEvaluations.set(0)
    }
}
