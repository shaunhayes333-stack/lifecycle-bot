package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6835 §INTAKE_FANOUT_GOVERNOR — operator diagnosis (Feb 2026):
 *
 *   "FDG_FANOUT_EXPLOSION HIGH: 4.34 FDG decisions per intake, 838
 *    active FDG outcomes from only 187 intake events, 3,972 raw FDG
 *    forensic rows. Lane layer amplifying: 2,390 active lane
 *    evaluations = ~12.8 lane evaluations per intake event.
 *
 *    The authority invariants are clean — EXECUTABLE_FANOUT_PER_
 *    CANDIDATE_GT_2 = 0. So this is decision/evaluation fanout, not
 *    duplicate economic buys."
 *
 * PURPOSE — cap the number of DECISION evaluations spawned by any one
 * intake event so weak/probe candidates cannot survive by attrition.
 * The economic ledger is already single-execution correct; this
 * authority stops the intelligence layer from wasting compute (and
 * inflating the effective admission rate) on setups the learners
 * already know are poor.
 *
 * TWO CAPS, DIFFERENT DEDUPE KEYS:
 *
 *  1. LANE FANOUT — max `LANE_EVAL_CAP` (=2) distinct lane evaluations
 *     per (intake mint, causal root). Callers ask
 *     `allowLaneEval(mint, causalRoot, laneName)` before scoring a
 *     candidate on a lane. Returns false once cap exceeded; caller
 *     emits FANOUT_LANE_EVAL_CAPPED_6835 and moves on.
 *
 *  2. FDG FANOUT — max `FDG_EVAL_CAP` (=2) distinct FDG evaluations per
 *     (intake mint, causal root). Same shape.
 *
 * IMPORTANT — the cap is by (mint, causalRoot). Different causal roots
 * (a genuinely new intake for the same mint) are counted separately, so
 * this cannot suppress a truly new opportunity. Selection is
 * first-N-win — callers should already be feeding candidates in
 * expectancy order (§4 HIGH_EDGE composite).
 *
 * ADVISORY DEGRADATION — if the caller has no causalRoot the governor
 * returns true (no cap) but records ADVISORY_UNGOVERNED_6835 so the
 * operator can see the coverage gap.
 */
object IntakeFanoutGovernor6835 {

    private const val LANE_EVAL_CAP = 2
    private const val FDG_EVAL_CAP = 2

    /** How long a single intake causal chain is tracked before its
     *  fanout counters expire. Longer than the deepest FDG causal loop
     *  we currently observe, short enough that memory is bounded. */
    private const val CAUSAL_TTL_MS = 10L * 60L * 1000L

    private data class LaneCounters(
        val lanesSeen: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()),
        val fdgSeen: AtomicLong = AtomicLong(0L),
        val laneCapped: AtomicLong = AtomicLong(0L),
        val fdgCapped: AtomicLong = AtomicLong(0L),
        val stampMs: Long = System.currentTimeMillis(),
    )

    private val chains = ConcurrentHashMap<String, LaneCounters>()

    private val laneCappedTotal = AtomicLong(0L)
    private val fdgCappedTotal = AtomicLong(0L)
    private val advisoryUngovernedTotal = AtomicLong(0L)

    private fun keyFor(mint: String, causalRoot: String): String =
        "${mint.trim().take(24)}::${causalRoot.trim().take(24)}"

    private fun cleanupIfStale() {
        val now = System.currentTimeMillis()
        val keysToDrop = chains.entries
            .asSequence()
            .filter { now - it.value.stampMs > CAUSAL_TTL_MS }
            .map { it.key }
            .toList()
        keysToDrop.forEach { chains.remove(it) }
    }

    fun allowLaneEval(mint: String, causalRoot: String, laneName: String): Boolean {
        if (mint.isBlank() || causalRoot.isBlank()) {
            advisoryUngovernedTotal.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FANOUT_ADVISORY_UNGOVERNED_6835") } catch (_: Throwable) {}
            return true
        }
        cleanupIfStale()
        val key = keyFor(mint, causalRoot)
        val c = chains.computeIfAbsent(key) { LaneCounters() }
        val lane = laneName.trim().uppercase()
        val alreadyPresent = c.lanesSeen.contains(lane)
        if (alreadyPresent) return true
        if (c.lanesSeen.size >= LANE_EVAL_CAP) {
            c.laneCapped.incrementAndGet()
            laneCappedTotal.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FANOUT_LANE_EVAL_CAPPED_6835")
                ForensicLogger.lifecycle(
                    "FANOUT_LANE_EVAL_CAPPED_6835",
                    "mint=${mint.take(10)} causalRoot=${causalRoot.take(10)} lane=$lane " +
                        "already=${c.lanesSeen.size} cap=$LANE_EVAL_CAP",
                )
            } catch (_: Throwable) {}
            return false
        }
        c.lanesSeen.add(lane)
        return true
    }

    fun allowFdgEval(mint: String, causalRoot: String): Boolean {
        if (mint.isBlank() || causalRoot.isBlank()) {
            advisoryUngovernedTotal.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FANOUT_ADVISORY_UNGOVERNED_6835") } catch (_: Throwable) {}
            return true
        }
        cleanupIfStale()
        val key = keyFor(mint, causalRoot)
        val c = chains.computeIfAbsent(key) { LaneCounters() }
        val current = c.fdgSeen.get()
        if (current >= FDG_EVAL_CAP) {
            c.fdgCapped.incrementAndGet()
            fdgCappedTotal.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FANOUT_FDG_EVAL_CAPPED_6835")
                ForensicLogger.lifecycle(
                    "FANOUT_FDG_EVAL_CAPPED_6835",
                    "mint=${mint.take(10)} causalRoot=${causalRoot.take(10)} " +
                        "current=$current cap=$FDG_EVAL_CAP",
                )
            } catch (_: Throwable) {}
            return false
        }
        c.fdgSeen.incrementAndGet()
        return true
    }

    data class Summary(
        val activeCausalChains: Int,
        val laneCappedEvents: Long,
        val fdgCappedEvents: Long,
        val advisoryUngoverned: Long,
    )

    fun summary(): Summary = Summary(
        activeCausalChains = chains.size,
        laneCappedEvents = laneCappedTotal.get(),
        fdgCappedEvents = fdgCappedTotal.get(),
        advisoryUngoverned = advisoryUngovernedTotal.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "IntakeFanoutGovernor6835 chains=${s.activeCausalChains} " +
            "laneCapped=${s.laneCappedEvents} fdgCapped=${s.fdgCappedEvents} " +
            "advisoryUngoverned=${s.advisoryUngoverned}"
    }

    internal fun clearForTest() {
        chains.clear()
        laneCappedTotal.set(0L)
        fdgCappedTotal.set(0L)
        advisoryUngovernedTotal.set(0L)
    }
}
