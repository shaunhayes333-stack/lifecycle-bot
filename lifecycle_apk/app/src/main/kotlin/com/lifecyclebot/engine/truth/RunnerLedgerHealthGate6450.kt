package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P1 — RUNNER LEDGER HEALTH GATE (CAPITAL SIZING).
 *
 * OPERATOR MANDATE:
 *   Runner recommends 0.200 SOL but OrderSizeResolver ends at 0.050.
 *   "Leave capital sizing conservative until:
 *     - capital invariant passes
 *     - exit scheduler is healthy
 *     - reward stream agrees
 *     - canonical expectancy is positive
 *    After correctness is restored, compounding should reference
 *    canonical TOTAL EQUITY, not UI cash and not stale runner balance."
 *
 * DESIGN
 * ──────
 * A single readiness gate that returns whether the compounding path is
 * allowed to grow sizing beyond the base lane cap. Callers ask
 * `allowExpansion()` before applying any runner uplift; if not allowed,
 * sizing stays at the base lane cap. `reason()` explains why.
 */
object RunnerLedgerHealthGate6450 {

    private val allowedChecks = AtomicLong(0L)
    private val deniedChecks = AtomicLong(0L)

    data class Assessment(val allowExpansion: Boolean, val reason: String, val canonicalEquitySol: Double)

    fun assess(): Assessment {
        val snap = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        val schedulerHealthy = try { ProtectiveExitScheduler6450.heartbeatAgeMs() < 15_000L } catch (_: Throwable) { false }
        val invariantOK = snap != null && kotlin.math.abs(snap.conservationDeltaSol) < 1e-3
        val expectancyPositive = (snap?.realizedPnlSol ?: 0.0) >= 0.0
        val allow = invariantOK && schedulerHealthy && expectancyPositive
        if (allow) allowedChecks.incrementAndGet() else deniedChecks.incrementAndGet()
        val reason = when {
            snap == null -> "no_snapshot"
            !invariantOK -> "conservation_delta=${"%.6f".format(snap.conservationDeltaSol)}"
            !schedulerHealthy -> "scheduler_starved"
            !expectancyPositive -> "expectancy_negative realized=${"%.4f".format(snap.realizedPnlSol)}"
            else -> "healthy"
        }
        return Assessment(allow, reason, snap?.totalEquitySol ?: 0.0)
    }

    fun allowExpansion(): Boolean = assess().allowExpansion

    fun statusLine(): String {
        val a = assess()
        return "allow=${a.allowExpansion} equity=${"%.4f".format(a.canonicalEquitySol)} " +
            "reason=${a.reason} allows=${allowedChecks.get()} denies=${deniedChecks.get()}"
    }
}
