package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P0-#1 — CANONICAL MINT OCCUPANCY REGISTRY.
 *
 * OPERATOR MANDATE:
 *   "97.3% of EXEC_GATE blocks are duplicates/open-position cases.
 *    Meanwhile Scanner dedup raw=0 coalesced=0 dedupSaved=0. Early
 *    dedup is not operating. Implement one CanonicalMintOccupancyRegistry.
 *    Key: mode + canonicalMint. Value: NONE/CANDIDATE/PENDING_ENTRY/
 *    IN_FLIGHT_ENTRY/OPEN/PENDING_EXIT."
 *
 * SUPERSEDES: CanonicalMintOccupancyRegistry6459 (unwired, telemetry-only).
 *
 * DESIGN
 * ──────
 * Admission-order guardrail sitting at the top of the pipeline:
 *
 *   raw observation → normalize mint → occupancy lookup → source coalesce
 *                     → validation → hydration → V3 → lane → FDG → executor
 *
 * `admit(mode, mint, symbol, source)` returns an Admission verdict:
 *   PASS_NONE     — first sighting; move to CANDIDATE
 *   PASS_CANDIDATE — already CANDIDATE; coalesce into existing observation
 *   BLOCK_OPEN     — position already OPEN; caller must merge context
 *   BLOCK_PENDING  — PENDING_ENTRY or IN_FLIGHT_ENTRY; coalesce attempt
 *   BLOCK_EXITING  — PENDING_EXIT; obey explicit re-entry policy
 *
 * Live and paper are keyed independently so a paper open never blocks
 * a live entry and vice versa. Live-mode entries flow through the same
 * admission path — no bypass, no split brain.
 *
 * Executor stays as the final defensive guard; this registry cuts the
 * cost of 90%+ of duplicate observations BEFORE hydration.
 */
object CanonicalMintOccupancyRegistry6464 {

    enum class Occupancy { NONE, CANDIDATE, PENDING_ENTRY, IN_FLIGHT_ENTRY, OPEN, PENDING_EXIT }

    enum class Admission { PASS_NONE, PASS_CANDIDATE, BLOCK_OPEN, BLOCK_PENDING, BLOCK_EXITING }

    private data class Entry(
        val mode: String,       // "paper" | "live"
        val mint: String,       // canonical (normalized)
        val symbol: String,
        val source: String,
        val occupancy: Occupancy,
        val firstSeenMs: Long,
        val lastMutationMs: Long,
    )

    private const val PENDING_TTL_MS = 90_000L   // 90s; PendingEntryProjectionGuard6461 semantics

    private val entries = ConcurrentHashMap<String, Entry>()

    // Telemetry
    private val admissions = AtomicLong(0L)
    private val coalesces = AtomicLong(0L)
    private val blocksOpen = AtomicLong(0L)
    private val blocksPending = AtomicLong(0L)
    private val blocksExiting = AtomicLong(0L)
    private val transitions = AtomicLong(0L)
    private val ttlSweeps = AtomicLong(0L)

    private fun key(mode: String, mint: String): String = "${mode.lowercase()}|$mint"

    // ─── Public admission API ───────────────────────────────────────────

    fun admit(mode: String, mint: String, symbol: String, source: String): Admission {
        if (mint.isBlank()) return Admission.PASS_NONE
        admissions.incrementAndGet()
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        val cur = entries[k]
        return when (cur?.occupancy) {
            null, Occupancy.NONE -> {
                entries[k] = Entry(mode.lowercase(), mint, symbol, source, Occupancy.CANDIDATE, now, now)
                Admission.PASS_NONE
            }
            Occupancy.CANDIDATE -> {
                coalesces.incrementAndGet()
                entries[k] = cur.copy(symbol = symbol.ifBlank { cur.symbol }, source = source, lastMutationMs = now)
                try { PipelineHealthCollector.labelInc("MINT_OCCUPANCY_COALESCED_6464") } catch (_: Throwable) {}
                Admission.PASS_CANDIDATE
            }
            Occupancy.OPEN -> {
                blocksOpen.incrementAndGet()
                logBlock("BLOCK_OPEN", mint, symbol, source, cur.occupancy)
                Admission.BLOCK_OPEN
            }
            Occupancy.PENDING_ENTRY, Occupancy.IN_FLIGHT_ENTRY -> {
                // TTL check — PENDING_ENTRY older than 90s is treated as stale;
                // release so a fresh attempt can proceed.
                if (now - cur.lastMutationMs > PENDING_TTL_MS) {
                    ttlSweeps.incrementAndGet()
                    entries[k] = Entry(mode.lowercase(), mint, symbol, source, Occupancy.CANDIDATE, now, now)
                    try {
                        ForensicLogger.lifecycle(
                            "MINT_OCCUPANCY_PENDING_TTL_RELEASE_6464",
                            "mint=${mint.take(10)} ageMs=${now - cur.lastMutationMs}",
                        )
                        PipelineHealthCollector.labelInc("MINT_OCCUPANCY_PENDING_TTL_RELEASE_6464")
                    } catch (_: Throwable) {}
                    Admission.PASS_NONE
                } else {
                    blocksPending.incrementAndGet()
                    logBlock("BLOCK_PENDING", mint, symbol, source, cur.occupancy)
                    Admission.BLOCK_PENDING
                }
            }
            Occupancy.PENDING_EXIT -> {
                blocksExiting.incrementAndGet()
                logBlock("BLOCK_EXITING", mint, symbol, source, cur.occupancy)
                Admission.BLOCK_EXITING
            }
        }
    }

    // ─── Explicit state transitions (called by executor + fill handlers) ─

    fun markPendingEntry(mode: String, mint: String, symbol: String = "", source: String = "executor") =
        transition(mode, mint, symbol, source, Occupancy.PENDING_ENTRY)
    fun markInFlightEntry(mode: String, mint: String, symbol: String = "", source: String = "executor") =
        transition(mode, mint, symbol, source, Occupancy.IN_FLIGHT_ENTRY)
    fun markOpen(mode: String, mint: String, symbol: String = "", source: String = "executor") =
        transition(mode, mint, symbol, source, Occupancy.OPEN)
    fun markPendingExit(mode: String, mint: String, symbol: String = "", source: String = "executor") =
        transition(mode, mint, symbol, source, Occupancy.PENDING_EXIT)
    fun markClosed(mode: String, mint: String) {
        if (mint.isBlank()) return
        val k = key(mode, mint)
        entries.remove(k)
        transitions.incrementAndGet()
    }

    private fun transition(mode: String, mint: String, symbol: String, source: String, occ: Occupancy) {
        if (mint.isBlank()) return
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        val prev = entries[k]
        entries[k] = Entry(
            mode = mode.lowercase(), mint = mint,
            symbol = symbol.ifBlank { prev?.symbol ?: "" },
            source = source, occupancy = occ,
            firstSeenMs = prev?.firstSeenMs ?: now,
            lastMutationMs = now,
        )
        transitions.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MINT_OCCUPANCY_${occ.name}_6464")
        } catch (_: Throwable) {}
    }

    // ─── Read helpers ────────────────────────────────────────────────────

    fun occupancyOf(mode: String, mint: String): Occupancy =
        entries[key(mode, mint)]?.occupancy ?: Occupancy.NONE

    fun isOpen(mode: String, mint: String): Boolean =
        occupancyOf(mode, mint) == Occupancy.OPEN

    /** Kill switch — called by reconciliation replay after a full run rebuild. */
    fun clearAll() {
        entries.clear()
        try { PipelineHealthCollector.labelInc("MINT_OCCUPANCY_CLEARED_6464") } catch (_: Throwable) {}
    }

    private fun logBlock(kind: String, mint: String, symbol: String, source: String, occ: Occupancy) {
        try {
            ForensicLogger.lifecycle(
                "MINT_OCCUPANCY_${kind}_6464",
                "mint=${mint.take(10)} symbol=$symbol source=$source occupancy=$occ",
            )
            PipelineHealthCollector.labelInc("MINT_OCCUPANCY_${kind}_6464")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "tracked=${entries.size} admissions=${admissions.get()} coalesced=${coalesces.get()} " +
            "blocksOpen=${blocksOpen.get()} blocksPending=${blocksPending.get()} " +
            "blocksExiting=${blocksExiting.get()} transitions=${transitions.get()} ttlSweeps=${ttlSweeps.get()}"

    data class SnapshotEntry(val mode: String, val mint: String, val occupancy: Occupancy)

    fun snapshotEntries(): List<SnapshotEntry> = entries.values.map {
        SnapshotEntry(mode = it.mode, mint = it.mint, occupancy = it.occupancy)
    }

    fun snapshotByOccupancy(): Map<Occupancy, Int> {
        val counts = Occupancy.values().associateWith { 0 }.toMutableMap()
        for (e in entries.values) counts[e.occupancy] = (counts[e.occupancy] ?: 0) + 1
        return counts
    }

    internal fun resetForTest() {
        entries.clear()
        admissions.set(0L); coalesces.set(0L)
        blocksOpen.set(0L); blocksPending.set(0L); blocksExiting.set(0L)
        transitions.set(0L); ttlSweeps.set(0L)
    }
}
