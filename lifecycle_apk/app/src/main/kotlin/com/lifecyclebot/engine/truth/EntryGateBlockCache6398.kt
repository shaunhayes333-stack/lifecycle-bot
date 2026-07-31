package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * V5.0.6398 — ENTRY GATE BLOCK CACHE (deterministic rejection dedupe).
 *
 * A score-below-floor decision is a GATE decision, not a failed buy.
 * The same fingerprint must NOT be reconsidered until at least one
 * material condition changes.
 *
 * Fingerprint keys:
 *   mint + lane + trader + tactic + scoreModelVersion + floorModelVersion
 *   + roundedEffectiveScore + roundedEffectiveFloor + dataVersion
 *
 * Reevaluate only when:
 *   - dataVersion changes
 *   - effectiveScore changes by MATERIAL_SCORE_DELTA (2.0)
 *   - effectiveFloor changes
 *   - lane / trader / tactic changes
 *   - governor / regime changes (dataVersion is the proxy)
 *   - reeval TTL (30..60s) expires
 */
object EntryGateBlockCache6398 {

    const val REEVAL_TTL_MS: Long = 30_000L
    const val MATERIAL_SCORE_DELTA: Double = 2.0

    data class Fingerprint(
        val mint: String, val lane: TraderLane, val trader: TraderId,
        val tactic: EntryTactic, val scoreModelVersion: String,
        val floorModelVersion: String,
        val roundedFloor: Int, val dataVersion: Long,
    )

    data class BlockEntry(
        val fp: Fingerprint,
        val effectiveScore: Double,
        val effectiveFloor: Double,
        val blockedAtMs: Long,
        var suppressCount: Long = 0L,
    )

    private val cache = ConcurrentHashMap<Fingerprint, BlockEntry>()

    val canonicalBlocks = AtomicLong(0L)
    val duplicateSuppressions = AtomicLong(0L)

    fun buildFingerprint(decision: EntryAuthorityDecision6398): Fingerprint =
        Fingerprint(
            mint = decision.score.mint,
            lane = decision.score.lane,
            trader = decision.score.trader,
            tactic = decision.score.tactic,
            scoreModelVersion = decision.score.scoreModelVersion,
            floorModelVersion = decision.floor.floorModelVersion,
            roundedFloor = decision.floor.effectiveFloor.roundToInt(),
            dataVersion = decision.score.dataVersion,
        )

    /**
     * Returns true if the caller should emit ONE canonical ENTRY_GATE_BLOCK
     * event; false if the block should be suppressed as a duplicate.
     */
    @Synchronized
    fun shouldEmitCanonicalBlock(
        decision: EntryAuthorityDecision6398, nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val fp = buildFingerprint(decision)
        val prev = cache[fp]
        if (prev == null) {
            cache[fp] = BlockEntry(fp, decision.score.effectiveScore, decision.floor.effectiveFloor, nowMs)
            canonicalBlocks.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_GATE_BLOCK_6398") } catch (_: Throwable) {}
            return true
        }
        // Material score change against the same fingerprint reopens.
        if (abs(decision.score.effectiveScore - prev.effectiveScore) >= MATERIAL_SCORE_DELTA) {
            cache[fp] = BlockEntry(fp, decision.score.effectiveScore, decision.floor.effectiveFloor, nowMs)
            canonicalBlocks.incrementAndGet()
            return true
        }
        // TTL expiry reopens.
        if (nowMs - prev.blockedAtMs >= REEVAL_TTL_MS) {
            cache[fp] = BlockEntry(fp, decision.score.effectiveScore, decision.floor.effectiveFloor, nowMs)
            canonicalBlocks.incrementAndGet()
            return true
        }
        prev.suppressCount += 1L
        duplicateSuppressions.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_GATE_BLOCK_DUPLICATE_SUPPRESSED_6398") } catch (_: Throwable) {}
        return false
    }

    fun clearForMint(mint: String) { cache.keys.removeIf { it.mint == mint } }

    internal fun clearAllForTest() {
        cache.clear(); canonicalBlocks.set(0L); duplicateSuppressions.set(0L)
    }
}
