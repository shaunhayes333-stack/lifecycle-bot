package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6402 §H — SAME-MINT CANDIDATE EPOCH (queue conflation).
 *
 * OBSERVED FAILURE (6401 snapshot)
 * ─────────────────────────────────
 * `PAPER_SAME_MINT_ALREADY_OPEN_6371:68 · V3_SAME_MINT_ALREADY_OPEN_6373:11`
 * — 81 duplicate entry evaluations on mints that were already open.
 * Scanner callback storm produced 2640 callbacks over 37 loop
 * iterations (~71 raw callbacks per tick) while the bot only had
 * ~4-10 open positions.
 *
 * DIRECTIVE
 * ──────────
 * "When a mint is already open, remove redundant queued entry
 *  candidates for that mint; suppress further entry evaluation
 *  until a meaningful state change; keep price and exit monitoring
 *  active; do not repeatedly fan the same mint through every lane
 *  and executor gate."
 *
 * "Implement a per-mint candidate epoch. New data replaces the
 *  previous queued candidate for the same mint instead of appending
 *  another work item."
 *
 * DESIGN
 * ──────
 * Callers on the entry-gate hot path call [shouldSuppress] BEFORE
 * scoring/lane-eval. When a position for the same mint is already
 * open (or was suppressed within the cooldown window), returns
 * true and increments a bounded suppression counter. On any state
 * change that could unblock entry (position closed, sell finality,
 * new lane authority), callers call [onStateChange] to bump the
 * mint's epoch so the next candidate is admitted.
 */
object SameMintCandidateEpoch6402 {

    /** Cooldown after a suppression before we allow another eval pass. */
    const val SUPPRESSION_COOLDOWN_MS: Long = 2_000L

    private data class Entry(
        val epoch: Long,
        val lastSuppressedAt: Long,
        val suppressCount: Long,
    )

    private val nextEpoch = AtomicLong(0L)
    private val perMint = ConcurrentHashMap<String, Entry>()
    private val totalSuppressed = AtomicLong(0L)

    /**
     * Returns true iff the caller should SKIP evaluating this
     * candidate (e.g. same-mint already open, or suppression is
     * still within the cooldown window).
     *
     * @param mint token mint address
     * @param sameMintAlreadyOpen provided by caller; the canonical
     *   position registry says a live/paper position exists for this
     *   mint right now.
     */
    fun shouldSuppress(
        mint: String,
        sameMintAlreadyOpen: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!sameMintAlreadyOpen) {
            // Not open — check cooldown from a previous suppression.
            val e = perMint[mint] ?: return false
            val age = nowMs - e.lastSuppressedAt
            return age < SUPPRESSION_COOLDOWN_MS
        }
        // Same-mint already open → suppress and record.
        val prior = perMint[mint]
        val newEntry = Entry(
            epoch = prior?.epoch ?: nextEpoch.incrementAndGet(),
            lastSuppressedAt = nowMs,
            suppressCount = (prior?.suppressCount ?: 0L) + 1L,
        )
        perMint[mint] = newEntry
        totalSuppressed.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("SAME_MINT_CANDIDATE_SUPPRESSED_6402")
            if (newEntry.suppressCount <= 3L || newEntry.suppressCount % 10L == 0L) {
                ForensicLogger.lifecycle(
                    "SAME_MINT_CANDIDATE_SUPPRESSED_6402",
                    "mint=${mint.take(10)} epoch=${newEntry.epoch} suppressCount=${newEntry.suppressCount}",
                )
            }
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Meaningful state change — bump the mint's epoch so the next
     * candidate is admitted. Call from position-close finality,
     * external state change, or lane authority upgrade.
     */
    fun onStateChange(mint: String, reason: String) {
        val prior = perMint[mint] ?: return
        perMint.remove(mint)
        try {
            PipelineHealthCollector.labelInc("SAME_MINT_EPOCH_BUMPED_6402")
            ForensicLogger.lifecycle(
                "SAME_MINT_EPOCH_BUMPED_6402",
                "mint=${mint.take(10)} oldEpoch=${prior.epoch} suppressCount=${prior.suppressCount} reason=$reason",
            )
        } catch (_: Throwable) {}
    }

    fun totalSuppressed(): Long = totalSuppressed.get()
    fun trackedMintCount(): Int = perMint.size

    internal fun clearAllForTest() {
        perMint.clear()
        totalSuppressed.set(0L)
        nextEpoch.set(0L)
    }
}
