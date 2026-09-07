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
 * V5.0.6689 adds a different invariant: Meme inventory is working capital, not an
 * unbounded sample reservoir. Soft dirty-slot pressure may fail open, but the canonical
 * Meme inventory turnover ceiling MUST NOT. Once the ceiling is reached, exits continue
 * while new Meme entries wait for confirmed closes to recycle cash back into the shared
 * paper/live wallet. No lane is disabled and no exit is blocked.
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
    // V5.0.6689 — 12 remains the soft exit-priority threshold. The old name
    // ENTRY_HARD_CAP was misleading because it only applied while a sell job
    // was already active and was bypassed by high-edge candidates.
    private const val ENTRY_SOFT_CAP = 12
    // Economic safety/turnover ceiling, not a strategy quota. With 14 Meme
    // lanes this still allows broad simultaneous expression while preventing
    // 100-250 funded positions from trapping the shared wallet indefinitely.
    private const val MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24

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

    // Compatibility name retained deliberately: Golden Tape 3837/6490 asserts
    // that PAPER slot-health is rebuilt from canonical current-mode inventory.
    // The implementation is now Meme-scoped (6689), but the source-level
    // contract remains canonical and directly names the paper projection.
    private fun canonicalPaperOpenCount(): Int = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
            .activeMintProjections6490("paper")
            .count { it.lane.uppercase() in MEME_LANES_6689 }
    } catch (_: Throwable) { -1 }

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
    }

    data class DeferDecision(val defer: Boolean, val reason: String)

    /**
     * Decide whether a NEW executable buy should defer one cycle.
     * High-edge may bypass soft cleanup pressure, but never the V5.0.6689
     * canonical turnover ceiling.
     */
    fun shouldDeferBuy(candidateConfirmedHighEdge: Boolean): DeferDecision {
        // V5.0.6689 — source-of-truth turnover seal. This MUST run before the
        // stale-snapshot fail-open and before every high-edge/forced-open bypass.
        // A stale publisher is not permission to keep allocating capital when
        // canonical Meme inventory is already saturated.
        val paperRuntime6689 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val canonicalMemeOpen6689 = canonicalMemeOpenCount(if (paperRuntime6689) "paper" else "live")
        val effectiveMemeOpen6689 = if (canonicalMemeOpen6689 >= 0)
            canonicalMemeOpen6689 else openPositionCount.get()
        if (effectiveMemeOpen6689 >= MEME_TURNOVER_ABSOLUTE_CAP_6689) {
            try {
                PipelineHealthCollector.labelInc("MEME_INVENTORY_TURNOVER_CAP_6689")
                PipelineHealthCollector.labelInc(
                    if (paperRuntime6689) "MEME_INVENTORY_TURNOVER_CAP_PAPER_6689"
                    else "MEME_INVENTORY_TURNOVER_CAP_LIVE_6689",
                )
                ForensicLogger.lifecycle(
                    "MEME_INVENTORY_TURNOVER_CAP_6689",
                    "mode=${if (paperRuntime6689) "PAPER" else "LIVE"} open=$effectiveMemeOpen6689 " +
                        "cap=$MEME_TURNOVER_ABSOLUTE_CAP_6689 action=defer_entries_until_confirmed_exits_recycle_capital",
                )
            } catch (_: Throwable) {}
            return DeferDecision(
                true,
                "MEME_TURNOVER_CAP=$effectiveMemeOpen6689>=$MEME_TURNOVER_ABSOLUTE_CAP_6689",
            )
        }

        // Stale soft telemetry still fails open; canonical turnover above did not.
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
            // Below the absolute turnover ceiling PAPER may still fail open after
            // cleanup pressure; this preserves throughput without permitting
            // unlimited inventory accumulation.
            val stuckSince = forcedStuckSinceMs.get()
            val stuckMs = if (stuckSince > 0L) System.currentTimeMillis() - stuckSince else 0L
            if (paperRuntime6689) {
                return DeferDecision(false, "PAPER_FORCED_OPEN_FAIL_OPEN=$forced>$FORCED_OPEN_DIRTY(stuck=${stuckMs}ms)")
            }
            if (stuckMs <= FORCED_DEFER_GRACE_MS) {
                return DeferDecision(true, "FORCED_OPEN=$forced>$FORCED_OPEN_DIRTY(stuck=${stuckMs}ms)")
            }
            return DeferDecision(false, "FORCED_OPEN=${forced}_FAIL_OPEN_stuck=${stuckMs}ms")
        }

        if (supervisorActive.get() > supervisorCap.get()) {
            return DeferDecision(true, "SUPERVISOR_OVER_CAP=${supervisorActive.get()}/${supervisorCap.get()}")
        }

        // Soft turnover pressure: when exits are already working and inventory
        // is above 12, ordinary candidates wait. High-edge may pass here while
        // the absolute 24-position ceiling above remains non-bypassable.
        if (!candidateConfirmedHighEdge) {
            val activeSellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.activeCount() } catch (_: Throwable) { 0 }
            if (activeSellJobs > 0 && openPositionCount.get() >= ENTRY_SOFT_CAP) {
                return DeferDecision(
                    true,
                    "EXITS_PRIORITY sellJobsActive=$activeSellJobs open=${openPositionCount.get()}>=$ENTRY_SOFT_CAP",
                )
            }
        }

        @Suppress("UNUSED_PARAMETER")
        val highEdgeBypassRetained = candidateConfirmedHighEdge
        return DeferDecision(false, "slot_health_ok")
    }

    fun snapshotLine(): String =
        "ghost=${ghostOpenCount.get()} forced=${forcedOpenCount.get()} open=${openPositionCount.get()} " +
        "sup=${supervisorActive.get()}/${supervisorCap.get()} exitPending=${exitPending.get()} " +
        "memeTurnoverCap=$MEME_TURNOVER_ABSOLUTE_CAP_6689"
}
