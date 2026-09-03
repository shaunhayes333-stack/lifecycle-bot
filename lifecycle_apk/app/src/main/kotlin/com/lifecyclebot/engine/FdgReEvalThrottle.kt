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
        val e = cache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - e.atMs > TTL_MS) { cache.remove(key); return null }
        if (kotlin.math.abs(scoreNow - e.score) >= SCORE_DELTA_BUST) { cache.remove(key); return null }
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
    }

    fun invalidate(mint: String) { cache.keys.removeIf { it.mint == mint } }
    fun clear() { cache.clear() }
    fun size(): Int = cache.size
}
