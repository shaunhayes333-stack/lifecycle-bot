/*
 * V5.9.810 — SymbolicVerdictRegistry (the missing Push 6 wiring)
 * ──────────────────────────────────────────────────────────────
 *
 * V5.9.784 introduced CandidateSymbolicContextBuilder.buildFor() as a
 * per-token symbolic veto layer. The PRD said:
 *
 *   "Used by: FinalDecisionGate (pre-trade vote), Executor close path
 *    (attach to canonical outcome), BehaviorLearning pattern memory,
 *    MetaCognitionAI calibration tracking."
 *
 * Zero of those call sites ever landed (the PRD called it "Push 6"
 * which never shipped). CandidateFeatures.symbolicVerdict has stayed
 * hardcoded to "" since V5.9.785, so BehaviorLearning's
 * predicted-vs-actual failure-mode calibration loop has never received
 * a prediction. This registry closes the loop.
 *
 * Wiring (all fail-soft, additive — NEVER blocks a trade):
 *
 *   1. FDG.evaluate (after candidate constructed, before return) calls
 *      `record(mint, verdict)` with whatever inputs are already in
 *      scope. Costs ~one map insert per evaluate.
 *
 *   2. Executor rich-publish site (line ~1875) reads the verdict via
 *      `consume(mint)` and passes it into CanonicalFeaturesBuilder so
 *      it lands on CanonicalFeatures.symbolicVerdict.
 *
 *   3. BehaviorLearning already reads candidate.symbolicVerdict from
 *      the bus (it has been threaded through since V5.9.785) — the
 *      pattern-memory loop just needed something OTHER than "" to
 *      actually compare against.
 *
 * Safety invariants:
 *   - The registry NEVER blocks anything. It is read-only signal.
 *   - Verdict is a STRING (compact form) so no enum coupling.
 *   - TTL prevents stale verdicts from being attached to a later
 *     trade on the same mint (e.g. re-entry 10 minutes later).
 *   - Bounded LRU keeps memory flat under bursty meme intake.
 *   - All access wrapped in try/catch at the call site — a registry
 *     failure must not regress entry/exit logic.
 *
 * NOT a gate. NOT a veto. NOT a multiplier. Pure observation channel
 * so the learner can finally calibrate.
 */
package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

object SymbolicVerdictRegistry {

    private const val TAG = "SymbolicVerdictRegistry"

    /**
     * Verdict TTL. If a candidate was evaluated > 10 minutes ago and
     * we're only now closing a trade on that mint, the verdict is too
     * old to be the predicted failure mode for THIS trade — drop it.
     */
    private const val TTL_MS = 10L * 60L * 1000L

    /** Bounded cache to keep memory flat. */
    private const val MAX_ENTRIES = 1024

    private data class Entry(
        val verdict: String,            // compact form, e.g. "ALLOW(0.60):"
        val vote: String,               // ALLOW / CAUTION / VETO / NEUTRAL
        val confidence: Double,
        val expectedFailureMode: String, // RUG / DUMP / DEAD_CAT / CHOP / TIMEOUT / ""
        val capturedAtMs: Long,
    )

    private val store = ConcurrentHashMap<String, Entry>()

    /**
     * Record a verdict for a mint. Called from FDG.evaluate at the
     * end of candidate evaluation (after all gates have run so the
     * verdict reflects the FINAL decision context).
     *
     * Idempotent on duplicates — last write wins (a re-evaluate on
     * the same tick should reflect the latest context).
     */
    fun record(
        mint: String,
        verdict: String,
        vote: String = "NEUTRAL",
        confidence: Double = 0.0,
        expectedFailureMode: String = "",
    ) {
        if (mint.isBlank()) return
        try {
            // Bounded LRU eviction (simple — drop oldest if over cap).
            if (store.size >= MAX_ENTRIES) {
                val oldest = store.entries.minByOrNull { it.value.capturedAtMs }?.key
                if (oldest != null) store.remove(oldest)
            }
            store[mint] = Entry(
                verdict = verdict,
                vote = vote,
                confidence = confidence,
                expectedFailureMode = expectedFailureMode,
                capturedAtMs = System.currentTimeMillis(),
            )
        } catch (_: Throwable) {
            // Never crash the caller — this is pure observation.
        }
    }

    /**
     * Read-only peek (does not remove). Used by UI / pipeline dumps
     * that want to inspect the current verdict for a mint without
     * affecting trade-close attribution.
     */
    fun peek(mint: String): String? {
        if (mint.isBlank()) return null
        return try {
            val e = store[mint] ?: return null
            if (System.currentTimeMillis() - e.capturedAtMs > TTL_MS) {
                store.remove(mint)
                null
            } else {
                e.verdict
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Consume the verdict at trade-close time. Returns the compact
     * verdict string if one was recorded within TTL, or "" otherwise.
     * Removes the entry so a re-entry on the same mint gets a fresh
     * verdict (matched to the fresh FDG.evaluate at entry).
     */
    fun consume(mint: String): String {
        if (mint.isBlank()) return ""
        return try {
            val e = store.remove(mint) ?: return ""
            if (System.currentTimeMillis() - e.capturedAtMs > TTL_MS) "" else e.verdict
        } catch (_: Throwable) {
            ""
        }
    }

    /** Diagnostic: current cache size (for pipeline-health dumps). */
    fun size(): Int = store.size

    /** Diagnostic: how many entries have non-NEUTRAL verdicts. */
    fun activeVerdictCount(): Int = try {
        store.values.count { it.vote != "NEUTRAL" && it.confidence > 0.0 }
    } catch (_: Throwable) {
        0
    }

    /** Clear all entries (used by stop / wallet flip). */
    fun clear() {
        try { store.clear() } catch (_: Throwable) {}
    }
}
