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

    fun acquireOrAttach(mint: String, source: String): CoordinationResult {
        if (mint.isBlank()) return CoordinationResult("", false, emptyList())
        acquires.incrementAndGet()
        val now = System.currentTimeMillis()
        val slot = slots.compute(mint) { _, cur ->
            if (cur != null && (now - cur.createdAtMs) < SLOT_TTL_MS) {
                cur.sources += source
                cur
            } else {
                Slot("Q${now}_${mint.take(6)}", mutableSetOf(source), now)
            }
        }!!
        val isNew = slot.sources.size == 1 && slot.sources.contains(source)
        if (!isNew) {
            coalesces.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MINT_WORK_COALESCED_6450") } catch (_: Throwable) {}
        }
        return CoordinationResult(slot.candidateId, isNew, slot.sources.toList())
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
