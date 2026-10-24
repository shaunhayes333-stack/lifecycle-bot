package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8366 §LANE_BRAIN_VOTES_COVER_THE_ASSESSMENT.
 *
 * The lane brains' votes (CortexVoters7885.laneBrainVotes8003) come from the native specialist snapshot, which no reader
 * trusts after 2 x 2.5 s (SpecialistBrainBridge7542.snapshotReadable8169). An assessment runs on the Cortex pools, queued
 * behind the Cortex lock (assessLockWait8324 max ~12-95 s in the reports) and the capture queue, so by the time the voters
 * were read the snapshot had expired: 0 lane-brain votes, cold reads, everything NEUTRAL.
 *
 * Now the lane-brain votes are STAMPED with the time they were read whenever a fresh snapshot is seen (at the decision:
 * Cortex7885.capture / schedule, and at every read that still finds one). An assessment that finds no live snapshot uses
 * the stamped votes of the same coin while they are younger than [ttlMs8366] — the longest observed lock wait plus the
 * longest observed assessment compute plus a pad, never under [TTL_FLOOR_MS_8366]. A vote is the brain's own read of the
 * coin at its stamp: nothing is invented, and an older vote is never relabelled as a newer one.
 * Counted: used (live or stamped votes reached the fusion), expired (a stamp existed but was too old), cold (none).
 */
object LaneBrainVotes8366 {
    /** The TTL never drops below this (covers the capture queue's measured lag as well as the lock wait). */
    const val TTL_FLOOR_MS_8366 = 120_000L
    /** ... and never exceeds this (a vote this old describes a different market). */
    const val TTL_CAP_MS_8366 = 600_000L
    /** Headroom above (max lock wait + max compute). */
    const val PAD_MS_8366 = 5_000L
    private const val MAX_MINTS_8366 = 4_096

    private class Stamped(val votes: List<CortexLedger7885.Vote>, val atMs: Long)

    private val byMint = ConcurrentHashMap<String, Stamped>()
    private val used = AtomicLong(0)
    private val usedStamped = AtomicLong(0)
    private val expired = AtomicLong(0)
    private val cold = AtomicLong(0)
    private val maxWaitMs = AtomicLong(0)
    private val maxComputeMs = AtomicLong(0)

    /** Pure: the vote TTL covering an assessment that waited [maxLockWaitMs] for the lock and computed for [maxComputeMs]. */
    fun ttlMs8366(maxLockWaitMs: Long, maxComputeMs: Long): Long =
        (maxLockWaitMs.coerceAtLeast(0L) + maxComputeMs.coerceAtLeast(0L) + PAD_MS_8366).coerceIn(TTL_FLOOR_MS_8366, TTL_CAP_MS_8366)

    /** Pure: may votes stamped at [stampedAtMs] be used by a read at [nowMs] under [ttlMs]? (A future stamp is refused.) */
    fun accepts8366(stampedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
        stampedAtMs > 0L && nowMs - stampedAtMs in 0L..ttlMs

    /** The live TTL from the measured waits. */
    fun ttlNow8366(): Long = ttlMs8366(maxWaitMs.get(), maxComputeMs.get())

    /** Cortex7885: one assessment's lock wait (ms). */
    fun noteWaitMs8366(ms: Long) { raise(maxWaitMs, ms) }

    /** Cortex7885: one assessment's compute time (ms). */
    fun noteComputeMs8366(ms: Long) { raise(maxComputeMs, ms) }

    private fun raise(a: AtomicLong, v: Long) {
        if (v <= 0L) return
        while (true) { val m = a.get(); if (v <= m || a.compareAndSet(m, v)) return }
    }

    /** Stamp [votes] of [mint] read at [atMs] (empty reads stamp nothing). */
    fun stamp8366(mint: String, votes: List<CortexLedger7885.Vote>, atMs: Long) {
        if (mint.isBlank() || votes.isEmpty() || atMs <= 0L) return
        if (byMint.size >= MAX_MINTS_8366) {
            val ttl = ttlNow8366()
            byMint.entries.removeIf { atMs - it.value.atMs > ttl }
            if (byMint.size >= MAX_MINTS_8366) return
        }
        byMint[mint] = Stamped(votes, atMs)
    }

    /**
     * The lane-brain votes an assessment of [mint] at [nowMs] reads: the [live] votes when the snapshot is still readable
     * (and they are stamped), else the stamped votes inside the TTL, else none. Counts the outcome.
     */
    fun resolve8366(mint: String, live: List<CortexLedger7885.Vote>, nowMs: Long): List<CortexLedger7885.Vote> {
        if (live.isNotEmpty()) {
            stamp8366(mint, live, nowMs)
            used.incrementAndGet()
            return live
        }
        val s = byMint[mint]
        if (s == null) { cold.incrementAndGet(); return emptyList() }
        if (!accepts8366(s.atMs, nowMs, ttlNow8366())) { expired.incrementAndGet(); return emptyList() }
        used.incrementAndGet(); usedStamped.incrementAndGet()
        return s.votes
    }

    fun line8366(): String =
        "laneBrainVotes8366[used=${used.get()} (stamped=${usedStamped.get()}) expired=${expired.get()} cold=${cold.get()} " +
            "ttl=${ttlNow8366() / 1000}s maxWait=${maxWaitMs.get() / 1000}s maxCompute=${maxComputeMs.get()}ms]"
}
