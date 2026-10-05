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
 *  1. LANE FANOUT — max `LANE_EVAL_CAP` (=2) evaluations PER LANE
 *     per (intake mint, causal root). One lane cannot consume another
 *     lane's allowance. Callers ask `allowLaneEval(...)` before scoring.
 *     This preserves bounded repeated work without first-caller starvation.
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
        val laneEvalSeen: AtomicLong = AtomicLong(0L),
        val fdgSeen: AtomicLong = AtomicLong(0L),
        val laneCapped: AtomicLong = AtomicLong(0L),
        val fdgCapped: AtomicLong = AtomicLong(0L),
        val stampMs: Long = System.currentTimeMillis(),
        val lastFdgMs7304: AtomicLong = AtomicLong(0L),
        val lastLaneEvalMs7610: AtomicLong = AtomicLong(0L),
        val lastLaneAddMs7321: AtomicLong = AtomicLong(0L),
    )

    /**
     * V5.0.7321 — the refill was a fixed 60s, but a candidate version rolls
     * every 30s, so it almost never fired (FDG_SUPPRESSED_FANOUT_CAP_7232 = 464
     * on 5.0.7317; BLUECHIP 204 / QUALITY 145 / MOONSHOT 110). The window is now
     * three bot-loop cycles (15-60s): the same burst attrition is still capped,
     * but a lane gets a fresh look as fast as the loop can produce one.
     */
    private fun refillMs7321(): Long = try {
        (3L * PipelineHealthCollector.rollingAvgCycleMs6626()).coerceIn(15_000L, FDG_REFILL_MS_7304)
    } catch (_: Throwable) { FDG_REFILL_MS_7304 }

    /**
     * V5.0.7304 §A_LONG_LIVED_TOKEN_GOT_TWO_LOOKS_PER_TEN_MINUTES.
     * The FDG budget (2 per mint × candidate version × lane) was sized for
     * fresh launches, where every new sighting is a new version. An established
     * token keeps its version, so BLUECHIP / QUALITY / TREASURY saw it twice
     * and then not again for the whole causal TTL (10 min) however the chart
     * moved: FDG_SUPPRESSED_FANOUT_CAP_7232_BLUECHIP = 499 on 5.0.7301 and 96
     * in the first 5 minutes live. A lane's budget now refills once no FDG
     * evaluation has run for [FDG_REFILL_MS_7304]; bursts inside that window
     * are still capped exactly as before.
     */
    private const val FDG_REFILL_MS_7304 = 60_000L

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
        val lane = laneName.trim().uppercase()
        if (lane.isBlank()) {
            advisoryUngovernedTotal.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FANOUT_ADVISORY_UNGOVERNED_6835") } catch (_: Throwable) {}
            return true
        }

        // V5.0.7610 — a shared "two distinct lanes per candidate" budget is a
        // deterministic lane disable. 7607 proved all twelve native brains can
        // qualify, yet FANOUT_LANE_EVAL_CAPPED_6835 still removed 147 active
        // evaluations and only eight lanes reached LANE_EVAL. The first two
        // callers consumed everybody else's budget.
        //
        // Keep the anti-fanout contract, but scope it to the thing being
        // bounded: repeated evaluations of THIS lane for THIS causal root.
        // Each lane gets at most LANE_EVAL_CAP evaluations per burst; another
        // lane cannot spend its allowance. This mirrors the per-lane FDG repair
        // from 7265 and preserves bounded compute without caller-order bias.
        val key = keyFor(mint, causalRoot) + "::LANE::" + lane.take(20)
        val c = chains.computeIfAbsent(key) { LaneCounters() }
        val now7610 = System.currentTimeMillis()
        val last7610 = c.lastLaneEvalMs7610.get()
        if (c.laneEvalSeen.get() >= LANE_EVAL_CAP && last7610 > 0L &&
            now7610 - last7610 >= refillMs7321()
        ) {
            c.laneEvalSeen.set(0L)
            try { PipelineHealthCollector.labelInc("LANE_FANOUT_BUDGET_REFILLED_7610") } catch (_: Throwable) {}
        }
        val current7610 = c.laneEvalSeen.get()
        if (current7610 >= LANE_EVAL_CAP) {
            c.laneCapped.incrementAndGet()
            laneCappedTotal.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FANOUT_LANE_EVAL_CAPPED_6835")
                PipelineHealthCollector.labelInc("FANOUT_LANE_EVAL_CAPPED_6835_$lane")
                ForensicLogger.lifecycle(
                    "FANOUT_LANE_EVAL_CAPPED_6835",
                    "mint=${mint.take(10)} causalRoot=${causalRoot.take(10)} lane=$lane " +
                        "laneEvalCount=$current7610 cap=$LANE_EVAL_CAP scope=PER_LANE_7610",
                )
            } catch (_: Throwable) {}
            return false
        }
        c.laneEvalSeen.incrementAndGet()
        c.lastLaneEvalMs7610.set(now7610)
        return true
    }

    /**
     * V5.0.7265 §ONE_BUDGET_FOR_TEN_LANES_IS_A_LANE_DISABLE.
     *
     * The FDG cap was keyed by (mint, causalRoot) only. processTokenCycle
     * calls FDG once per lane in a fixed order — TREASURY, QUALITY,
     * BLUECHIP, MOONSHOT, SHITCOIN, MANIPULATED, EXPRESS, DIP_HUNTER, trunk,
     * main — so on any mint the first two lanes to ask spent the whole
     * budget and every later lane was blocked before its verdict cache was
     * even consulted. Operator 5.0.7263, PAPER, 27 min:
     *
     *   FDG/FDG_FANOUT_CAP_7232 = 1143 of 1377 FDG blocks
     *   SHITCOIN  qualified=264 buyIntent=0   (cap is a hard veto there)
     *   EXPRESS   qualified=249 buyIntent=0
     *   MOONSHOT  qualified=398 fdgAllow=5 exec=3
     *
     * A cap that is always consumed by whoever is earlier in the call order
     * is not a fan-out limit on the later lanes; it is a disable of them.
     * The doctrine forbids that. The budget is therefore per (mint,
     * causalRoot, lane): each lane may still evaluate a mint at most
     * FDG_EVAL_CAP times per causal chain — the attrition 7232 exists to
     * stop is still stopped, lane by lane — but no lane can spend another
     * lane's allowance. Callers that pass no lane keep the shared key, so
     * nothing here loosens for them.
     */
    fun allowFdgEval(mint: String, causalRoot: String, laneName: String = ""): Boolean {
        if (mint.isBlank() || causalRoot.isBlank()) {
            advisoryUngovernedTotal.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FANOUT_ADVISORY_UNGOVERNED_6835") } catch (_: Throwable) {}
            return true
        }
        cleanupIfStale()
        val lane7265 = laneName.trim().uppercase()
        val key = if (lane7265.isBlank()) keyFor(mint, causalRoot)
            else keyFor(mint, causalRoot) + "::" + lane7265.take(20)
        val c = chains.computeIfAbsent(key) { LaneCounters() }
        val now7304 = System.currentTimeMillis()
        val last7304 = c.lastFdgMs7304.get()
        if (c.fdgSeen.get() >= FDG_EVAL_CAP && last7304 > 0L && now7304 - last7304 >= refillMs7321()) {
            c.fdgSeen.set(0L)
            try { PipelineHealthCollector.labelInc("FDG_FANOUT_BUDGET_REFILLED_7304") } catch (_: Throwable) {}
        }
        // V5.0.7810 — MOONSHOT launch state can materially change between
        // consecutive sub-minute observations. Give that lane four bounded
        // evaluations per causal burst; every other lane remains at two.
        // This changes compute opportunity only — canonical execution/finality
        // cardinality remains unchanged.
        val fdgCap7810 = if (lane7265.substringBefore(':') == "MOONSHOT") 4 else FDG_EVAL_CAP
        val current = c.fdgSeen.get()
        if (current >= fdgCap7810) {
            c.fdgCapped.incrementAndGet()
            fdgCappedTotal.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("FANOUT_FDG_EVAL_CAPPED_6835")
                ForensicLogger.lifecycle(
                    "FANOUT_FDG_EVAL_CAPPED_6835",
                    "mint=${mint.take(10)} causalRoot=${causalRoot.take(10)} " +
                        "current=$current cap=$fdgCap7810",
                )
            } catch (_: Throwable) {}
            return false
        }
        c.fdgSeen.incrementAndGet()
        c.lastFdgMs7304.set(now7304)
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
