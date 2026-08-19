package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6469 §P1 — MAINTENANCE BUDGET GOVERNOR.
 *
 * OPERATOR MANDATE (verbatim, 6468 evidence):
 *
 *   "188s, 41s, 64s, 37s, 223s, 174s, 218s, 175s. Sustained starvation.
 *    worstPhase=POST_LEARNING_MAINTENANCE LabUniverseTick worst=8308ms
 *    TOKEN_MAP_PENDING=6430 PROBATION_TIMEOUT_HELD=4320
 *    HOT_WATCHLIST_SOURCE_REBALANCED=4543 WATCHLIST_LRU_EVICT=2989
 *    SUPERVISOR_LEASE_FORCE_RELEASED=2658.
 *    Move/budget: LabUniverseTick, probation expiry processing, token
 *    map hydration, watchlist rebalance/LRU cleanup, paper replay
 *    audits, source maintenance, symbolic/learning fanout onto bounded
 *    side-effect workers. No full-list O(N) sweep on every intake or
 *    bot cycle."
 *
 * SEMANTICS
 * ─────────
 * Every heavy maintenance task calls
 *   `MaintenanceBudgetGovernor6469.tryAcquire(workKey, budgetMs)`
 * before it does any work. If another instance of the same workKey is
 * still holding a lease, or the task has already run within the
 * cooldown window, the call returns `Skip.COALESCED`. Otherwise it
 * returns `Skip.RUN(deadlineMs)` and the caller must respect the
 * deadline (i.e. bail out when `System.currentTimeMillis() > deadlineMs`).
 *
 * The governor:
 *   1. Coalesces duplicate work requests by key (deep dedup).
 *   2. Enforces incremental batches — every work key has a `maxDurationMs`.
 *   3. Enforces cooldown — a workKey can't run more than `minIntervalMs`
 *      apart.
 *   4. Emits `MAINT_GOV_COALESCED_6469_<workKey>` /
 *      `MAINT_GOV_OVERRAN_6469_<workKey>` for observability.
 */
object MaintenanceBudgetGovernor6469 {

    sealed class Decision {
        data class Run(val deadlineMs: Long) : Decision()
        data object Coalesced : Decision()
        data object CoolingDown : Decision()
    }

    private data class Lease(
        val startedMs: Long,
        val deadlineMs: Long,
    )

    private data class Config(
        val minIntervalMs: Long,
        val maxDurationMs: Long,
    )

    private val defaults = mapOf(
        "lab_universe_tick"            to Config(minIntervalMs = 30_000L, maxDurationMs = 2_000L),
        "probation_expiry"             to Config(minIntervalMs = 15_000L, maxDurationMs = 1_500L),
        "token_map_hydration"          to Config(minIntervalMs = 60_000L, maxDurationMs = 3_000L),
        "hot_watchlist_rebalance"      to Config(minIntervalMs = 20_000L, maxDurationMs = 2_000L),
        "watchlist_lru_evict"          to Config(minIntervalMs = 45_000L, maxDurationMs = 1_500L),
        "paper_replay_audit"           to Config(minIntervalMs = 30_000L, maxDurationMs = 3_000L),
        "source_maintenance"           to Config(minIntervalMs = 60_000L, maxDurationMs = 2_500L),
        "symbolic_learning_fanout"     to Config(minIntervalMs = 20_000L, maxDurationMs = 2_000L),
    )
    private val fallback = Config(minIntervalMs = 15_000L, maxDurationMs = 2_000L)

    private val leases = ConcurrentHashMap<String, Lease>()
    private val lastCompletedMs = ConcurrentHashMap<String, Long>()

    private val acquires = AtomicLong(0L)
    private val coalesced = AtomicLong(0L)
    private val coolingDown = AtomicLong(0L)
    private val overruns = AtomicLong(0L)

    /**
     * Attempt to acquire a lease for `workKey`. Callers MUST call
     * `release(workKey)` when they finish or exceed the deadline.
     */
    fun tryAcquire(workKey: String): Decision {
        acquires.incrementAndGet()
        val cfg = defaults[workKey] ?: fallback
        val now = System.currentTimeMillis()
        val existing = leases[workKey]
        if (existing != null) {
            if (now < existing.deadlineMs) {
                coalesced.incrementAndGet()
                try { PipelineHealthCollector.labelInc("MAINT_GOV_COALESCED_6469_$workKey".take(60)) } catch (_: Throwable) {}
                return Decision.Coalesced
            } else {
                // Lease overran — reclaim it and record.
                overruns.incrementAndGet()
                leases.remove(workKey)
                try {
                    ForensicLogger.lifecycle(
                        "MAINT_GOV_OVERRAN_6469",
                        "workKey=$workKey ranMs=${now - existing.startedMs} budgetMs=${cfg.maxDurationMs}",
                    )
                    PipelineHealthCollector.labelInc("MAINT_GOV_OVERRAN_6469_$workKey".take(60))
                } catch (_: Throwable) {}
            }
        }
        val lastMs = lastCompletedMs[workKey] ?: 0L
        if (now - lastMs < cfg.minIntervalMs) {
            coolingDown.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MAINT_GOV_COOLDOWN_6469_$workKey".take(60)) } catch (_: Throwable) {}
            return Decision.CoolingDown
        }
        val deadline = now + cfg.maxDurationMs
        leases[workKey] = Lease(startedMs = now, deadlineMs = deadline)
        return Decision.Run(deadlineMs = deadline)
    }

    /** Called when the caller finishes (or gives up on) the work. */
    fun release(workKey: String) {
        val lease = leases.remove(workKey) ?: return
        val now = System.currentTimeMillis()
        lastCompletedMs[workKey] = now
        if (now > lease.deadlineMs) {
            overruns.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "MAINT_GOV_OVERRAN_6469",
                    "workKey=$workKey ranMs=${now - lease.startedMs}",
                )
                PipelineHealthCollector.labelInc("MAINT_GOV_OVERRAN_6469_$workKey".take(60))
            } catch (_: Throwable) {}
        }
    }

    /** Convenience wrapper: run a block with governance. */
    inline fun withBudget(workKey: String, onCoalesced: () -> Unit = {}, block: (deadlineMs: Long) -> Unit) {
        when (val d = tryAcquire(workKey)) {
            is Decision.Run -> try {
                block(d.deadlineMs)
            } finally {
                release(workKey)
            }
            Decision.Coalesced, Decision.CoolingDown -> onCoalesced()
        }
    }

    fun statusLine(): String =
        "acquires=${acquires.get()} coalesced=${coalesced.get()} " +
            "coolingDown=${coolingDown.get()} overruns=${overruns.get()} " +
            "activeLeases=${leases.size}"

    internal fun resetForTest() {
        leases.clear(); lastCompletedMs.clear()
        acquires.set(0L); coalesced.set(0L); coolingDown.set(0L); overruns.set(0L)
    }
}
