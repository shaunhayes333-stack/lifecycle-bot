package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P1 — MINT WORK COORDINATOR.
 *
 * OPERATOR MANDATE:
 *   Current dedup is ineffective:
 *     raw=615 accepts=615 coalesces=0 blocks=0
 *   yet Executor later blocks: PAPER_SAME_MINT_ALREADY_OPEN=94.
 *
 *   "Create canonical MintWorkCoordinator. Multiple discovery sources
 *    attach evidence to the same CandidateId. Do not construct an
 *    independent candidate per PUMP_PORTAL/DEX/RAYDIUM/COINGECKO/scanner
 *    heal/scanner direct.
 *
 *    Before lane evaluation: if canonical position already OPEN for mint,
 *    update/watch existing position context — do not generate another
 *    executable BUY candidate."
 *
 * DESIGN
 * ──────
 * Per-mint slot: acquireOrAttach(mint, source) returns a
 * canonicalCandidateId, deduplicating multi-source discovery. Also gates
 * BUY generation via `isOpenBlocked(mint)` which checks canonical open
 * positions before expensive fan-out.
 */
object MintWorkCoordinator6450 {

    data class CoordinationResult(
        val candidateId: String,
        val isNew: Boolean,
        val coalescedFromSources: List<String>,
    )

    private data class Slot(val candidateId: String, val sources: MutableSet<String>, val createdAtMs: Long)

    private const val SLOT_TTL_MS = 30 * 60_000L // 30 min

    private val slots = ConcurrentHashMap<String, Slot>() // mint -> slot
    private val acquires = AtomicLong(0L)
    private val coalesces = AtomicLong(0L)
    private val openMintBuyBlocks = AtomicLong(0L)

    /**
     * ATOMIC acquire-or-attach. V5.0.6453 §P0-#1.
     *
     * Prior (racy) impl used ConcurrentHashMap.compute {} + inspected
     * `slot.sources.size == 1` to determine isNew, which is not
     * consistent under concurrent multi-source callbacks: two callbacks
     * arriving from source A and source B simultaneously could both
     * observe `size == 1` after their own add and both return isNew=true.
     *
     * Fix: use putIfAbsent for the OWNER of the slot, and treat
     * everything else as a coalesce regardless of source. That means
     * "100 simultaneous callbacks for one mint" produces exactly one
     * candidate — regardless of source composition. Only the FIRST
     * putIfAbsent winner is isNew.
     */
    fun acquireOrAttach(mint: String, source: String): CoordinationResult {
        if (mint.isBlank()) return CoordinationResult("", false, emptyList())
        acquires.incrementAndGet()
        val now = System.currentTimeMillis()
        val proposed = Slot("Q${now}_${mint.take(6)}", java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap()), now)
        proposed.sources += source
        val prior = slots.putIfAbsent(mint, proposed)
        if (prior == null) {
            // We won ownership — this is the ONE true candidate.
            return CoordinationResult(proposed.candidateId, isNew = true, coalescedFromSources = listOf(source))
        }
        // Slot expired? Try to swap it out atomically.
        if ((now - prior.createdAtMs) >= SLOT_TTL_MS) {
            if (slots.replace(mint, prior, proposed)) {
                return CoordinationResult(proposed.candidateId, isNew = true, coalescedFromSources = listOf(source))
            }
            // Someone else replaced first — fall through to coalesce.
        }
        // Attach evidence to existing slot; always a coalesce.
        val current = slots[mint] ?: proposed
        current.sources += source
        coalesces.incrementAndGet()
        try { PipelineHealthCollector.labelInc("MINT_WORK_COALESCED_6450") } catch (_: Throwable) {}
        return CoordinationResult(current.candidateId, isNew = false, coalescedFromSources = current.sources.toList())
    }

    /** Called before lane/FDG fan-out. Returns true if a canonical open
     *  position already exists for this mint — caller must NOT generate a
     *  new executable BUY (top-ups reference the existing PositionId). */
    fun isOpenBlocked(mint: String): Boolean {
        val blocked = try {
            val open = CanonicalPositionAuthority6441.openPositions()
            open.any { it.mint.equals(mint, ignoreCase = true) }
        } catch (_: Throwable) { false }
        if (blocked) {
            openMintBuyBlocks.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MINT_WORK_OPEN_BUY_BLOCKED_6450") } catch (_: Throwable) {}
        }
        return blocked
    }

    fun statusLine(): String = "slots=${slots.size} acquires=${acquires.get()} " +
        "coalesces=${coalesces.get()} openMintBuyBlocks=${openMintBuyBlocks.get()}"
}
