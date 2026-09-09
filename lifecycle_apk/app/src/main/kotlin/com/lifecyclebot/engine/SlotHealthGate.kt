package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1470 (spec item 7) — SLOT-HEALTH ENTRY ADMISSION.
 *
 * The bot idles because dirty/ghost slots and wedged leases occupy capacity. We must
 * NOT disable trading, but we should DEFER (not permanently block) new executable buys
 * for one cycle while slots are dirty, so cleanup can catch up. The operator's explicit
 * rule: "log EXEC_DEFERRED_SLOT_HEALTH, not block permanently".
 *
 * BotService publishes live slot-health each cycle via publish(); TradeAuthorizer reads
 * shouldDeferBuy() at the top of authorize() and returns a SOFT, retryable reject when
 * dirty. PROBE_ONLY / already-confirmed-high-edge candidates bypass the soft pressure.
 *
 * V5.0.6692 retires the V5.0.6689 global Meme position-count ceiling. Canonical sizing,
 * shared cash reservation, same-mint occupancy, liquidity/sellability and real risk
 * remain the economic authorities. Slot health may briefly defer genuine dirty/ghost
 * state, but a fixed count and ordinary exit pressure are advisory only and may not
 * globally amputate the specialist desks.
 *
 * V5.0.6709 restores the missing half of that doctrine: removing the static 24-position
 * ceiling must NOT mean unbounded entry velocity. A PAPER book whose OPEN inventory is
 * growing materially faster than terminal SELLs now gets a progressive admission cadence.
 * This is not a hard cap: 1/N ordinary executable opportunities are still admitted and
 * confirmed-high-edge entries always bypass the cadence. As the book drains, cadence
 * automatically relaxes back to 1/1. The objective is round-trip throughput, not entry
 * suppression: buys and exits must converge instead of OPEN inventory growing forever.
 */
object SlotHealthGate {

    private val ghostOpenCount = AtomicInteger(0)
    private val forcedOpenCount = AtomicInteger(0)
    private val openPositionCount = AtomicInteger(0)
    private val supervisorActive = AtomicInteger(0)
    private val supervisorCap = AtomicInteger(48)
    private val exitPending = AtomicBoolean(false)
    private val lastPublishMs = AtomicLong(0L)

    private val ghostStuckSinceMs = AtomicLong(0L)
    private const val GHOST_DEFER_GRACE_MS = 60_000L
    private val forcedStuckSinceMs = AtomicLong(0L)
    private const val FORCED_DEFER_GRACE_MS = 60_000L

    private const val FORCED_OPEN_DIRTY = 20
    // 12 is telemetry/priority context only; it is not an entry cap.
    private const val ENTRY_SOFT_CAP = 12

    // V5.0.6709 — adaptive PAPER turnover pressure. These are cadence bands,
    // never hard inventory ceilings. At 111 open positions (operator 6708 dump)
    // ordinary entry cadence becomes 1/5 while an exit sweep is outstanding;
    // at <=47 open positions it is exactly 1/1. Confirmed-high-edge candidates
    // bypass. This lets terminal SELL throughput catch entry throughput without
    // reverting to the obsolete 24-position global choke.
    private const val TURNOVER_SOFT_START_6709 = 48
    private const val TURNOVER_MEDIUM_START_6709 = 72
    private const val TURNOVER_HIGH_START_6709 = 96
    private const val TURNOVER_SEVERE_START_6709 = 120
    private const val TURNOVER_MAX_CADENCE_6709 = 6
    private val turnoverAdmissionSeq6709 = AtomicLong(0L)
    private val turnoverCadenceNow6709 = AtomicInteger(1)
    private val turnoverDeferred6709 = AtomicLong(0L)
    private val turnoverAdmitted6709 = AtomicLong(0L)
    private val turnoverHighEdgeBypass6709 = AtomicLong(0L)

    // V5.0.6692 — STATIC MEME POSITION CAP RETIRED. A fixed inventory
    // count is not an economic authority: position sizes, liquidity and shared
    // account cash vary by orders of magnitude. Canonical sizing, same-mint
    // occupancy, capital reservation and exit sellability now bound exposure.
    // The compatibility accessor below stays for older writer callsites but is
    // deliberately unbounded so it cannot become a second stacked admission gate.

    private val MEME_LANES_6689 = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS",
        "CORE", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
        "MANIPULATED", "TREASURY", "CASHGEN", "STANDARD", "V3_CORE",
        "REPLAY_6486", "SNIPER", "CASH", "BLUE", "FAST", "MANIP", "MOON",
    )

    private fun canonicalMemeOpenCount(mode: String): Int = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
            .activeMintProjections6490(mode)
            .count { it.lane.uppercase() in MEME_LANES_6689 }
    } catch (_: Throwable) { -1 }

    // Compatibility surface retained so older writer callsites compile against one
    // shared lane definition. The cap accessor is intentionally unbounded in 6692.
    fun isMemeLane6689(lane: String): Boolean = lane.trim().uppercase() in MEME_LANES_6689
    fun memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE
    fun canonicalMemeOpenCount6689(mode: String): Int = canonicalMemeOpenCount(mode)

    // Compatibility name retained deliberately: Golden Tape 3837/6490 asserts
    // that PAPER slot-health is rebuilt from canonical current-mode inventory.
    private fun canonicalPaperOpenCount(): Int = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
            .activeMintProjections6490("paper")
            .count { it.lane.uppercase() in MEME_LANES_6689 }
    } catch (_: Throwable) { -1 }

    /**
     * V5.0.6709 progressive PAPER admission cadence.
     *  open <48       -> 1/1 ordinary entries
     *  48..71         -> 1/2
     *  72..95         -> 1/3
     *  96..119        -> 1/4
     *  >=120          -> 1/5
     * If a canonical exit sweep is already pending, pressure rises one bounded step.
     * This remains non-zero at every level; it can never become a global no-buy gate.
     */
    private fun adaptiveTurnoverCadence6709(open: Int, exitsInFlight: Boolean): Int {
        val base = when {
            open < TURNOVER_SOFT_START_6709 -> 1
            open < TURNOVER_MEDIUM_START_6709 -> 2
            open < TURNOVER_HIGH_START_6709 -> 3
            open < TURNOVER_SEVERE_START_6709 -> 4
            else -> 5
        }
        return if (exitsInFlight && base > 1) (base + 1).coerceAtMost(TURNOVER_MAX_CADENCE_6709) else base
    }

    fun publish(
        ghostOpen: Int,
        forcedOpen: Int,
        openPositions: Int,
        supActive: Int,
        supCap: Int,
        exitInFlight: Boolean,
    ) {
        val paperRuntime = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val canonicalPaperOpen = if (paperRuntime) canonicalPaperOpenCount() else -1
        val effectiveOpen = if (paperRuntime && canonicalPaperOpen >= 0) canonicalPaperOpen else openPositions.coerceAtLeast(0)
        val effectiveForced = if (paperRuntime && canonicalPaperOpen >= 0)
            forcedOpen.coerceAtLeast(0).coerceAtMost(canonicalPaperOpen)
        else forcedOpen.coerceAtLeast(0)
        if (paperRuntime && canonicalPaperOpen >= 0 &&
            (effectiveForced != forcedOpen.coerceAtLeast(0) || effectiveOpen != openPositions.coerceAtLeast(0))) {
            try { PipelineHealthCollector.labelInc("PAPER_SLOT_HEALTH_REBUILT_FROM_LEDGER") } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle(
                    "PAPER_SLOT_HEALTH_REBUILT_FROM_LEDGER",
                    "rawForced=$forcedOpen rawOpen=$openPositions canonicalPaperOpen=$canonicalPaperOpen scope=MEME",
                )
            } catch (_: Throwable) {}
        }
        ghostOpenCount.set(ghostOpen.coerceAtLeast(0))
        forcedOpenCount.set(effectiveForced)
        openPositionCount.set(effectiveOpen)
        supervisorActive.set(supActive.coerceAtLeast(0))
        supervisorCap.set(supCap.coerceAtLeast(1))
        exitPending.set(exitInFlight)
        lastPublishMs.set(System.currentTimeMillis())
        if (ghostOpen > 0) ghostStuckSinceMs.compareAndSet(0L, System.currentTimeMillis())
        else ghostStuckSinceMs.set(0L)
        if (effectiveForced > FORCED_OPEN_DIRTY) forcedStuckSinceMs.compareAndSet(0L, System.currentTimeMillis())
        else forcedStuckSinceMs.set(0L)

        val cadence6709 = if (paperRuntime) adaptiveTurnoverCadence6709(effectiveOpen, exitInFlight) else 1
        val priorCadence6709 = turnoverCadenceNow6709.getAndSet(cadence6709)
        if (cadence6709 != priorCadence6709) {
            try {
                PipelineHealthCollector.labelInc("MEME_TURNOVER_CADENCE_CHANGED_6709")
                ForensicLogger.lifecycle(
                    "MEME_TURNOVER_CADENCE_CHANGED_6709",
                    "paper=$paperRuntime open=$effectiveOpen exitPending=$exitInFlight cadence=$priorCadence6709->$cadence6709 action=adaptive_round_trip_pressure",
                )
            } catch (_: Throwable) {}
        }
    }

    data class DeferDecision(val defer: Boolean, val reason: String)

    /** Decide whether a NEW executable buy should defer one cycle for real runtime dirt. */
    fun shouldDeferBuy(candidateConfirmedHighEdge: Boolean): DeferDecision {
        // V5.0.6692 — no global position-count gate. Retain the runtime mode for
        // forced-slot cleanup semantics and the V5.0.6709 PAPER turnover cadence.
        val paperRuntime6692 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }

        if (System.currentTimeMillis() - lastPublishMs.get() > 15_000L) {
            return DeferDecision(false, "stale_snapshot_fail_open")
        }

        val ghosts = ghostOpenCount.get()
        if (ghosts > 0) {
            val stuckSince = ghostStuckSinceMs.get()
            val stuckMs = if (stuckSince > 0L) System.currentTimeMillis() - stuckSince else 0L
            if (stuckMs <= GHOST_DEFER_GRACE_MS) {
                return DeferDecision(true, "GHOST_OPEN=$ghosts(stuck=${stuckMs}ms)")
            }
            return DeferDecision(false, "GHOST_OPEN=${ghosts}_FAIL_OPEN_stuck=${stuckMs}ms")
        }

        val forced = forcedOpenCount.get()
        if (forced > FORCED_OPEN_DIRTY) {
            val stuckSince = forcedStuckSinceMs.get()
            val stuckMs = if (stuckSince > 0L) System.currentTimeMillis() - stuckSince else 0L
            if (paperRuntime6692) {
                // 6692 correctly stopped FORCED count being a hard PAPER gate. Do not
                // return here: 6709's independent adaptive cadence below must still run
                // when the book is large, otherwise PAPER_FORCED_OPEN_FAIL_OPEN turns
                // into an accidental bypass of all turnover control (operator 6708:
                // forced=108, open=111, 322 BUY vs 131 SELL).
                try {
                    PipelineHealthCollector.labelInc("PAPER_FORCED_OPEN_ADVISORY_6709")
                } catch (_: Throwable) {}
            } else {
                if (stuckMs <= FORCED_DEFER_GRACE_MS) {
                    return DeferDecision(true, "FORCED_OPEN=$forced>$FORCED_OPEN_DIRTY(stuck=${stuckMs}ms)")
                }
                return DeferDecision(false, "FORCED_OPEN=${forced}_FAIL_OPEN_stuck=${stuckMs}ms")
            }
        }

        if (supervisorActive.get() > supervisorCap.get()) {
            return DeferDecision(true, "SUPERVISOR_OVER_CAP=${supervisorActive.get()}/${supervisorCap.get()}")
        }

        val openNow6709 = openPositionCount.get().coerceAtLeast(0)
        val exitsNow6709 = exitPending.get()
        val cadence6709 = if (paperRuntime6692) adaptiveTurnoverCadence6709(openNow6709, exitsNow6709) else 1
        turnoverCadenceNow6709.set(cadence6709)

        if (cadence6709 > 1) {
            if (candidateConfirmedHighEdge) {
                turnoverHighEdgeBypass6709.incrementAndGet()
                try { PipelineHealthCollector.labelInc("MEME_TURNOVER_HIGH_EDGE_BYPASS_6709") } catch (_: Throwable) {}
            } else {
                val seq6709 = turnoverAdmissionSeq6709.incrementAndGet()
                val admit6709 = (seq6709 % cadence6709.toLong()) == 0L
                try { PipelineHealthCollector.labelInc("MEME_TURNOVER_PRESSURE_BAND_${cadence6709}_6709") } catch (_: Throwable) {}
                if (!admit6709) {
                    turnoverDeferred6709.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("MEME_TURNOVER_PRESSURE_DEFER_6709")
                        ForensicLogger.lifecycle(
                            "MEME_TURNOVER_PRESSURE_DEFER_6709",
                            "open=$openNow6709 cadence=1/$cadence6709 seq=$seq6709 exitPending=$exitsNow6709 action=defer_one_candidate_not_global_block",
                        )
                    } catch (_: Throwable) {}
                    return DeferDecision(true, "MEME_TURNOVER_PRESSURE_6709 open=$openNow6709 cadence=1/$cadence6709")
                }
                turnoverAdmitted6709.incrementAndGet()
                try { PipelineHealthCollector.labelInc("MEME_TURNOVER_PRESSURE_ADMIT_6709") } catch (_: Throwable) {}
            }
        }

        if (!candidateConfirmedHighEdge) {
            val activeSellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.activeCount() } catch (_: Throwable) { 0 }
            if (activeSellJobs > 0 && openNow6709 >= ENTRY_SOFT_CAP) {
                // Advisory only. Exits can influence prioritisation/sizing but do not
                // globally reject fresh executable specialist opportunities.
                try {
                    PipelineHealthCollector.labelInc("MEME_EXIT_PRIORITY_ADVISORY_6692")
                    ForensicLogger.lifecycle(
                        "MEME_EXIT_PRIORITY_ADVISORY_6692",
                        "sellJobsActive=$activeSellJobs open=$openNow6709 soft=$ENTRY_SOFT_CAP action=advisory_only_no_entry_block",
                    )
                } catch (_: Throwable) {}
            }
        }

        return DeferDecision(false, if (cadence6709 > 1) "slot_health_ok_turnover_1/$cadence6709" else "slot_health_ok")
    }

    fun snapshotLine(): String =
        "ghost=${ghostOpenCount.get()} forced=${forcedOpenCount.get()} open=${openPositionCount.get()} " +
        "sup=${supervisorActive.get()}/${supervisorCap.get()} exitPending=${exitPending.get()} " +
        "memeTurnoverCap=SHARED_CAPITAL_6692 cadence=1/${turnoverCadenceNow6709.get()} " +
        "turnover6709[admit=${turnoverAdmitted6709.get()} defer=${turnoverDeferred6709.get()} highEdge=${turnoverHighEdgeBypass6709.get()}]"
}
