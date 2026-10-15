package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1372 — FDG re-evaluation throttle (doctor spec #1/#6).
 *
 * InvariantGuardian trips FDG_FANOUT_EXPLOSION when FDG/intake > 3.0. The
 * 5.0.3357 snapshot showed FDG=6894 vs intake=592 (ratio 11.6). The cause is
 * NOT lane fanout per se — FinalDecisionGate.evaluate is gated to the elected
 * primary lane — it is that the SAME watchlisted mint re-runs the full gate on
 * every tick it re-proposes a BUY signal, even though V3 (not FDG) is the actual
 * execution authority in the meme spine and the gate inputs barely move
 * tick-to-tick.
 *
 * This throttle seals one FDG verdict per canonical candidate version and lane
 * for a short TTL.  BUY is included: the executable decision is immutable for
 * that candidate version and duplicate execution is guarded by the canonical
 * mint/version claim downstream.  Re-running FDG does not make a BUY safer; it
 * creates a second decision for the same causal candidate.
 *
 * IMPORTANT — this does NOT loosen any gate:
 *  - Safety/evidence changes are part of [evidenceVersion] and bust the seal.
 *  - The reused verdict is exactly the verdict the gate itself just produced;
 *    we only skip recomputing an identical answer within the TTL window.
 *  - A score delta >= SCORE_DELTA_BUST busts the cache so improving setups are
 *    re-judged immediately.
 *
 * Throughput-positive (doctrine rule #3): fewer redundant gate computations per
 * tick free the bot loop for more unique candidates without changing a single
 * trade decision.
 */
object FdgReEvalThrottle {

    private const val TTL_MS = 8_000L
    private const val SCORE_DELTA_BUST = 5

    private data class Key(
        val mint: String,
        val candidateVersion: Long,
        val lane: String,
        val evidenceVersion: String,
    )

    private data class Entry(
        val verdict: FinalDecisionGate.FinalDecision,
        val score: Int,
        val atMs: Long,
    )

    private val cache = ConcurrentHashMap<Key, Entry>()

    fun get(
        mint: String,
        candidateVersion: Long,
        lane: String,
        evidenceVersion: String,
        scoreNow: Int,
    ): FinalDecisionGate.FinalDecision? {
        val key = Key(mint, candidateVersion, lane.uppercase(), evidenceVersion)
        // V5.0.8136 — a candidate at the FDG door is a credible admission prospect: ask for its authority proof now
        // (async, single-flight, LIVE only) so the pre-broadcast gate finds it cached instead of asking synchronously.
        try { com.lifecyclebot.engine.truth.AuthorityProofPrefetch8136.prefetchIfLive8136(mint) } catch (_: Throwable) {}
        val e = cache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - e.atMs > TTL_MS) { cache.remove(key); return null }
        if (kotlin.math.abs(scoreNow - e.score) >= SCORE_DELTA_BUST) { cache.remove(key); scoreBusts8087.incrementAndGet(); return null }
        cacheReuse8087.incrementAndGet()
        return e.verdict
    }

    fun put(
        mint: String,
        candidateVersion: Long,
        lane: String,
        evidenceVersion: String,
        score: Int,
        verdict: FinalDecisionGate.FinalDecision,
    ) {
        cache[Key(mint, candidateVersion, lane.uppercase(), evidenceVersion)] =
            Entry(verdict, score, System.currentTimeMillis())
        sealed8136.incrementAndGet()
        // V5.0.8136 — the cache was only pruned by a get() of the same key, so every superseded candidate version or
        // evidence string stayed resident for the session (a FinalDecision per row). Expired rows go when it grows.
        if (cache.size > CACHE_CAP_8136) trim8136()
        noteDecision8087(mint, candidateVersion, lane, evidenceVersion)
    }

    // ── V5.0.8087 — unique causal FDG decisions, separate from repeated evaluations ──
    //
    // The doctor divided every gate outcome (cache reuse, watchlist re-proposals, legacy mirror rows) by intake and
    // raised FDG_FANOUT_EXPLOSION HIGH at 4.05/intake while only 33 sealed allows existed. A decision is unique per
    // (mode, mint, candidateVersion, lane, evidenceVersion); a re-evaluation of the same identity is overhead, not fanout.
    private const val SEEN_CAP_8087 = 20_000
    private val seen8087 = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_CAP_8087
    })
    private val unique8087 = java.util.concurrent.atomic.AtomicLong(0L)
    private val repeatSameEvidence8087 = java.util.concurrent.atomic.AtomicLong(0L)
    private val cacheReuse8087 = java.util.concurrent.atomic.AtomicLong(0L)
    private val scoreBusts8087 = java.util.concurrent.atomic.AtomicLong(0L)
    private val lanesPerCandidate8087 = ConcurrentHashMap<String, MutableSet<String>>()

    internal fun noteDecision8087(mint: String, candidateVersion: Long, lane: String, evidenceVersion: String) {
        val mode = try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "?" }
        val id = "$mode|$mint|$candidateVersion|${lane.uppercase()}|$evidenceVersion"
        val fresh = seen8087.put(id, true) == null
        if (fresh) unique8087.incrementAndGet() else repeatSameEvidence8087.incrementAndGet()
        if (fresh) noteSupersede8136("$mode|$mint|$candidateVersion|${lane.uppercase()}", evidenceVersion)
        if (fresh) {
            val cand = "$mode|$mint|$candidateVersion"
            if (!lanesPerCandidate8087.containsKey(cand)) distinct8136.incrementAndGet()
            lanesPerCandidate8087.computeIfAbsent(cand) { ConcurrentHashMap.newKeySet() }.add(lane.uppercase())
            if (lanesPerCandidate8087.size > SEEN_CAP_8087) lanesPerCandidate8087.keys.take(SEEN_CAP_8087 / 4).forEach { lanesPerCandidate8087.remove(it) }
        }
    }

    /** Unique causal decisions; 0 on builds/paths that never sealed through this throttle. */
    fun uniqueDecisions8087(): Long = unique8087.get()

    /** unique, re-evaluated with unchanged evidence, cache reuse, score busts, mean lanes per candidate. */
    fun fanoutLine8087(): String {
        val cands = lanesPerCandidate8087.size
        val lanes = lanesPerCandidate8087.values.sumOf { it.size }
        val perCand = if (cands > 0) lanes.toDouble() / cands else 0.0
        return "unique=${unique8087.get()} repeatSameEvidence=${repeatSameEvidence8087.get()} cacheReuse=${cacheReuse8087.get()} " +
            "scoreBusts=${scoreBusts8087.get()} candidates=$cands lanesPerCandidate=${"%.2f".format(perCand)} " +
            FdgFanoutContract8136.line8136(contract8136())
    }

    // ── V5.0.8136 — the fanout measurement contract ──
    // distinct candidates, lane decisions per candidate, unique decisions, unchanged-evidence repeats, cache hits,
    // superseded (same candidate+lane, new evidence) and final sealed verdicts are separate counters; the doctor faults
    // only from unique decisions (FdgFanoutContract8136), never from a fallback gate count.
    private const val CACHE_CAP_8136 = 4_000
    private val distinct8136 = java.util.concurrent.atomic.AtomicLong(0L)
    private val superseded8136 = java.util.concurrent.atomic.AtomicLong(0L)
    private val sealed8136 = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastEvidence8136 = ConcurrentHashMap<String, String>()

    private fun noteSupersede8136(slot: String, evidenceVersion: String) {
        val prev = lastEvidence8136.put(slot, evidenceVersion)
        if (prev != null && prev != evidenceVersion) superseded8136.incrementAndGet()
        if (lastEvidence8136.size > SEEN_CAP_8087) lastEvidence8136.clear()
    }

    fun distinctCandidates8136(): Long = distinct8136.get()

    fun contract8136(): FdgFanoutContract8136.Counts {
        val lanes = lanesPerCandidate8087.values.sumOf { it.size }.toLong()
        return FdgFanoutContract8136.Counts(
            distinctCandidates = distinct8136.get(),
            laneDecisions = lanes,
            trackedCandidates = lanesPerCandidate8087.size.toLong(),
            uniqueDecisions = unique8087.get(),
            unchangedRepeats = repeatSameEvidence8087.get(),
            cacheHits = cacheReuse8087.get(),
            superseded = superseded8136.get(),
            finalSealed = sealed8136.get(),
        )
    }

    /** Mints with a sealed verdict still inside its TTL (active admission state for the memory guard). */
    fun sealedMints8136(nowMs: Long = System.currentTimeMillis()): Set<String> =
        cache.entries.asSequence().filter { nowMs - it.value.atMs <= TTL_MS }.map { it.key.mint }.toHashSet()

    /** Drop expired seals; past the cap, the oldest go. Returns rows removed. */
    fun trim8136(nowMs: Long = System.currentTimeMillis()): Int {
        val before = cache.size
        cache.entries.removeIf { nowMs - it.value.atMs > TTL_MS }
        if (cache.size > CACHE_CAP_8136) {
            cache.entries.sortedBy { it.value.atMs }.take(cache.size - CACHE_CAP_8136).forEach { cache.remove(it.key, it.value) }
        }
        return (before - cache.size).coerceAtLeast(0)
    }

    fun invalidate(mint: String) { cache.keys.removeIf { it.mint == mint } }
    fun clear() {
        cache.clear(); seen8087.clear(); lanesPerCandidate8087.clear()
        unique8087.set(0L); repeatSameEvidence8087.set(0L); cacheReuse8087.set(0L); scoreBusts8087.set(0L)
        lastEvidence8136.clear(); distinct8136.set(0L); superseded8136.set(0L); sealed8136.set(0L)
    }
    fun size(): Int = cache.size
}
