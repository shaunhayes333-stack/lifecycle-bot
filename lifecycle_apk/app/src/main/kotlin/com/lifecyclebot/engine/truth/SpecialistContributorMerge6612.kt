package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6612 — Bounded Contributor Merge (operator directive Feb 2026):
 *   "many active specialists -> one canonical execution owner per
 *   candidate -> every relevant specialist continues contributing to
 *   sizing/hold/exit/learning. Contributor influence must never be
 *   zero merely because the specialist was not elected owner."
 *
 * V5.0.6607b restored the paper-mode `allLaneContribution` telemetry
 * emit so contributors are now COUNTED, but their opinions were not
 * yet merged into the elected owner's decision. This registry closes
 * that loop with a bounded merge:
 *
 *   1. During per-lane evaluation, when a specialist is NOT the
 *      elected owner but still qualifies the candidate, it calls
 *      `recordContributor(mint, lane, laneScore, aiConfidence,
 *      buyIntent)`. The registry keeps a rolling per-mint aggregate.
 *   2. When the owner is about to size the order, it queries
 *      `boundedSizeMultiplier6612(mint)`. The return is clamped to
 *      [0.75, 1.25] — contributors CAN nudge the owner's size, but
 *      never break its sealed authority.
 *
 * Bounded math (per-mint):
 *   contribCount    = number of non-owner contributors that qualified
 *   buyAlignment    = fraction of contributors also proposing BUY
 *   confidenceMean  = mean of contributors' aiConfidence in [0,100]
 *
 * Multiplier:
 *   base = 1.0
 *   base += 0.05 * (buyAlignment - 0.5) * min(contribCount, 6) / 6.0
 *   base += 0.20 * (confidenceMean/100.0 - 0.5) * min(contribCount, 6) / 6.0
 *   return base.coerceIn(0.75, 1.25)
 *
 * At contribCount=0 the multiplier is exactly 1.0 so nothing changes
 * for un-observed mints. TTL bounded at CONTRIB_TTL_MS so stale desks
 * don't influence future candidates on the same mint.
 */
object SpecialistContributorMerge6612 {

    private const val CONTRIB_TTL_MS = 5_000L
    private const val LO = 0.75
    private const val HI = 1.25

    private data class Contrib(
        val lane: String,
        val laneScore: Double,
        val aiConfidence: Double,
        val buyIntent: Boolean,
        val recordedAtMs: Long,
    )

    private val byMint = ConcurrentHashMap<String, MutableList<Contrib>>()
    private val merges = AtomicLong(0)

    /**
     * Called from the lane-eval fanout when a non-owner specialist
     * qualifies the candidate. Idempotent per (mint, lane) — a fresh
     * entry replaces stale entries for the same lane on the same mint.
     */
    fun recordContributor(
        mint: String,
        lane: String,
        laneScore: Double,
        aiConfidence: Double,
        buyIntent: Boolean,
    ) {
        if (mint.isBlank() || lane.isBlank()) return
        val laneUp = lane.uppercase()
        val entry = Contrib(laneUp, laneScore, aiConfidence, buyIntent, System.currentTimeMillis())
        val list = byMint.computeIfAbsent(mint) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.removeAll { it.lane == laneUp }
            list.add(entry)
            // Evict stale entries opportunistically.
            val now = System.currentTimeMillis()
            list.removeAll { now - it.recordedAtMs > CONTRIB_TTL_MS }
        }
        try { PipelineHealthCollector.labelInc("CONTRIB_MERGE_RECORDED_6612_$laneUp") } catch (_: Throwable) {}
    }

    /**
     * Owner-side query. Returns 1.0 when there are no valid
     * contributors so the owner's original sizing is preserved.
     */
    fun boundedSizeMultiplier6612(mint: String): Double {
        if (mint.isBlank()) return 1.0
        val list = byMint[mint] ?: return 1.0
        val now = System.currentTimeMillis()
        val fresh = synchronized(list) { list.filter { now - it.recordedAtMs <= CONTRIB_TTL_MS } }
        if (fresh.isEmpty()) return 1.0
        val n = fresh.size
        val buyAlignment = fresh.count { it.buyIntent }.toDouble() / n
        val confidenceMean = fresh.map { it.aiConfidence }.average() / 100.0
        val evidenceWeight = kotlin.math.min(n, 6).toDouble() / 6.0
        var mult = 1.0
        mult += 0.05 * (buyAlignment - 0.5) * evidenceWeight
        mult += 0.20 * (confidenceMean - 0.5) * evidenceWeight
        val bounded = mult.coerceIn(LO, HI)
        try {
            merges.incrementAndGet()
            PipelineHealthCollector.labelInc("CONTRIB_MERGE_SIZE_MULT_6612_APPLIED")
            if (bounded != 1.0) {
                PipelineHealthCollector.labelInc(
                    "CONTRIB_MERGE_SIZE_MULT_6612_${when {
                        bounded < 0.90 -> "LO"
                        bounded < 1.10 -> "NEUTRAL"
                        else -> "HI"
                    }}"
                )
            }
        } catch (_: Throwable) {}
        return bounded
    }

    /** V5.0.6612 — telemetry: how many merges have fired since start. */
    fun mergeCount(): Long = merges.get()

    /** V5.0.6612 — telemetry: current unique mints tracking contributors. */
    fun trackedMints(): Int = byMint.size

    /** Testing / reset helper (never called in production). */
    fun resetForTest() {
        byMint.clear()
        merges.set(0)
    }
}
