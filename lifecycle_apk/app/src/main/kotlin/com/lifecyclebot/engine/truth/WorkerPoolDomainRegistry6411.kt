package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.6411 §7.1 + §22 — WORKER POOL DOMAIN REGISTRY + FAILURE CONTAINMENT.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Execution and exit reconciliation must NEVER compete with
 *  scanner enrichment. Journal write failure blocks new live entries
 *  but preserves exits and reconciliation. Jupiter failed → disable
 *  Jupiter adapter, retain direct adapters."
 *
 * DESIGN
 * ──────
 *   • Named pool domains with priority ordering (lower = higher priority).
 *   • Each domain tracks capacity/in-flight/priority.
 *   • Domains can be independently marked HEALTHY / DEGRADED / DISABLED.
 *   • acquire() returns true when a slot is available; release() frees it.
 *   • Priority (1 = highest, 8 = lowest):
 *       1  EXIT_RECONCILE_POOL
 *       2  TRANSACTION_RECONCILE_POOL
 *       3  EXECUTION_POOL
 *       4  WALLET_STATE_POOL
 *       5  SAFETY_REFRESH_POOL
 *       6  STRATEGY_EVAL_POOL
 *       7  SCANNER_IO_POOL / TOKEN_MAP_POOL
 *       8  UI_REPORT_POOL / JOURNAL_IO_POOL
 *
 * Advisory scaffolding — the concrete pool acquisition logic in
 * BotService.supervisor keeps its current implementation for build
 * 6411. This registry is the surface the follow-up commit will
 * migrate call sites into.
 */
object WorkerPoolDomainRegistry6411 {

    enum class Domain(val priority: Int, val defaultCapacity: Int) {
        EXIT_RECONCILE_POOL(1, 8),
        TRANSACTION_RECONCILE_POOL(2, 8),
        EXECUTION_POOL(3, 8),
        WALLET_STATE_POOL(4, 4),
        SAFETY_REFRESH_POOL(5, 6),
        STRATEGY_EVAL_POOL(6, 8),
        SCANNER_IO_POOL(7, 16),
        TOKEN_MAP_POOL(7, 8),
        JOURNAL_IO_POOL(8, 4),
        UI_REPORT_POOL(8, 2),
    }

    enum class DomainStatus { HEALTHY, DEGRADED, DISABLED }

    private data class Pool(
        val domain: Domain,
        val capacity: Int,
        val inFlight: AtomicInteger = AtomicInteger(0),
        var status: DomainStatus = DomainStatus.HEALTHY,
    )

    private val pools: MutableMap<Domain, Pool> = ConcurrentHashMap<Domain, Pool>().apply {
        Domain.values().forEach { put(it, Pool(it, it.defaultCapacity)) }
    }

    /**
     * Try to reserve a slot in [domain]. Returns true on success.
     * When [domain] is DISABLED, always returns false.
     */
    fun acquire(domain: Domain): Boolean {
        val p = pools[domain] ?: return false
        if (p.status == DomainStatus.DISABLED) {
            try { PipelineHealthCollector.labelInc("POOL_ACQUIRE_REJECTED_DISABLED_6411") } catch (_: Throwable) {}
            return false
        }
        while (true) {
            val cur = p.inFlight.get()
            if (cur >= p.capacity) {
                try { PipelineHealthCollector.labelInc("POOL_SATURATED_${domain.name}_6411") } catch (_: Throwable) {}
                return false
            }
            if (p.inFlight.compareAndSet(cur, cur + 1)) {
                try { PipelineHealthCollector.labelInc("POOL_ACQUIRED_${domain.name}_6411") } catch (_: Throwable) {}
                return true
            }
        }
    }

    fun release(domain: Domain) {
        val p = pools[domain] ?: return
        while (true) {
            val cur = p.inFlight.get()
            if (cur <= 0) return
            if (p.inFlight.compareAndSet(cur, cur - 1)) return
        }
    }

    fun markDegraded(domain: Domain, reason: String) {
        val p = pools[domain] ?: return
        if (p.status != DomainStatus.DEGRADED) {
            p.status = DomainStatus.DEGRADED
            try {
                ForensicLogger.lifecycle(
                    "POOL_DEGRADED_6411",
                    "domain=${domain.name} priority=${domain.priority} reason=${reason.take(80)}",
                )
                PipelineHealthCollector.labelInc("POOL_DEGRADED_${domain.name}_6411")
            } catch (_: Throwable) {}
        }
    }

    fun markDisabled(domain: Domain, reason: String) {
        val p = pools[domain] ?: return
        if (p.status != DomainStatus.DISABLED) {
            p.status = DomainStatus.DISABLED
            try {
                ForensicLogger.lifecycle(
                    "POOL_DISABLED_6411",
                    "domain=${domain.name} priority=${domain.priority} reason=${reason.take(80)}",
                )
                PipelineHealthCollector.labelInc("POOL_DISABLED_${domain.name}_6411")
            } catch (_: Throwable) {}
        }
    }

    fun markHealthy(domain: Domain) {
        val p = pools[domain] ?: return
        if (p.status != DomainStatus.HEALTHY) {
            p.status = DomainStatus.HEALTHY
            try {
                ForensicLogger.lifecycle("POOL_HEALTHY_6411", "domain=${domain.name}")
                PipelineHealthCollector.labelInc("POOL_HEALTHY_${domain.name}_6411")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Priority veto (§22 failure containment) — never disable exit
     * or reconciliation because entry is unhealthy. Callers ask this
     * before starting a new BUY / SELL flow.
     */
    fun canDegradedEntry(domain: Domain): Boolean = when (domain) {
        Domain.EXIT_RECONCILE_POOL, Domain.TRANSACTION_RECONCILE_POOL -> true
        else -> pools[domain]?.status != DomainStatus.DISABLED
    }

    fun statusLine(): String {
        val parts = Domain.values().map { d ->
            val p = pools[d]!!
            "${d.name}(p${d.priority}/${p.inFlight.get()}/${p.capacity}/${p.status.name.first()})"
        }
        return parts.joinToString(" ")
    }

    internal fun resetForTest() {
        pools.forEach { (_, p) ->
            p.inFlight.set(0)
            p.status = DomainStatus.HEALTHY
        }
    }
}
