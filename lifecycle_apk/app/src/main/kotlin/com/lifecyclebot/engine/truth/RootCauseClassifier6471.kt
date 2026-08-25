package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6471 §P1 (items 33-34) — ROOT CAUSE CLASSIFIER PRIORITY.
 *
 * OPERATOR MANDATE (verbatim, 6470 evidence):
 *
 *   "Current root cause says DEGRADED/api/providers despite:
 *      CAPITAL_IDENTITY_BREACH_6470
 *      PAPER_EQUITY_CONSERVATION_VIOLATION_6467
 *      CAPITAL_CONSERVATION_DELTA_6469
 *      active integrity hold
 *      position parity divergence
 *
 *    Correct priority:
 *      ECONOMIC/CANONICAL INTEGRITY
 *        > execution finality
 *        > runtime stall
 *        > provider degradation
 *        > advisory degradation.
 *
 *    Provider errors must not mask a capital-identity breach."
 *
 * DESIGN
 * ──────
 * Classify() walks the PipelineHealthCollector labels in mandated
 * priority order and returns the top classification. Report authors
 * read `classify()` output rather than composing their own root cause.
 */
object RootCauseClassifier6471 {

    enum class Tier {
        ECONOMIC_INTEGRITY,
        EXECUTION_FINALITY,
        ENTRY_FINALITY,          // V5.0.6497 §5 — BUY-approved → open failures
        RUNTIME_STALL,
        PROVIDER_DEGRADATION,
        ADVISORY_DEGRADATION,
        HEALTHY,
    }

    data class Classification(
        val tier: Tier,
        val label: String,
        val supportingCount: Long,
    )

    // Prioritised probe list: (tier, label). First hit wins.
    private val probes: List<Pair<Tier, String>> = listOf(
        Tier.ECONOMIC_INTEGRITY to "CAPITAL_IDENTITY_BREACH_6470",
        Tier.ECONOMIC_INTEGRITY to "PAPER_EQUITY_CONSERVATION_VIOLATION_6467",
        Tier.ECONOMIC_INTEGRITY to "CAPITAL_CONSERVATION_DELTA",
        Tier.ECONOMIC_INTEGRITY to "CANONICAL_LOT_SELL_QUARANTINED_6470",
        Tier.ECONOMIC_INTEGRITY to "POSITION_PARITY_GENUINE_DIVERGENCE_6471",
        Tier.ECONOMIC_INTEGRITY to "LIFECYCLE_PROJECTION_DIVERGED_6470",
        Tier.EXECUTION_FINALITY to "CANONICAL_PAPER_TERMINAL_BRIDGE_FANOUT_THREW_6469",
        Tier.EXECUTION_FINALITY to "MARKET_DATA_EXECUTABLE_BLOCKED_6471",
        // V5.0.6497 §5 — ENTRY_FINALITY tier. Approved BUY candidates that
        // never open belong here. Diagnoses entry-handoff faults instead
        // of misattributing them to MECHANICAL_FAULT/UI.
        Tier.ENTRY_FINALITY to "EXEC_AUTHORITY_STATE_MISMATCH",
        Tier.ENTRY_FINALITY to "PAPER_ENTRY_FINALITY_MISSING_TERMINAL_6497",
        Tier.ENTRY_FINALITY to "EXEC_SIZE_AUTHORITY_MISMATCH_6497",
        Tier.ENTRY_FINALITY to "EXEC_OPEN_DROPPED_SNAPSHOT_DRIFT_6496",
        Tier.ENTRY_FINALITY to "ZOMBIE_CATASTROPHE_PENDING_RETRY",
        // V5.0.6501 §4 §8 — economic-truth and canonical-existence faults.
        Tier.ECONOMIC_INTEGRITY to "ECONOMIC_TRUTH_DIVERGENCE_6501",
        Tier.ENTRY_FINALITY to "EXIT_REJECTED_NO_CANONICAL_POSITION_6501",
        // V5.0.6502 §1 §2 — ledger phantom-realized + journal divergence.
        Tier.ECONOMIC_INTEGRITY to "LEDGER_VS_JOURNAL_DIVERGENCE_6502",
        Tier.ECONOMIC_INTEGRITY to "LEDGER_REJECTED_QUARANTINED_CLOSE_6502",
        Tier.RUNTIME_STALL to "MAINT_GOV_OVERRAN_6469",
        Tier.RUNTIME_STALL to "HEARTBEAT_RESCUE_IDLE_PHASE_TIMEOUT",
        Tier.PROVIDER_DEGRADATION to "DATA_PROVIDER_AUTH_LOCKOUT_6468",
        Tier.PROVIDER_DEGRADATION to "DATA_PROVIDER_429_BACKOFF_6468",
        Tier.PROVIDER_DEGRADATION to "DATA_PROVIDER_404_CACHE_ONLY_6468",
        Tier.PROVIDER_DEGRADATION to "DATA_PROVIDER_TRANSIENT_BACKOFF_6468",
        Tier.ADVISORY_DEGRADATION to "ADVISOR_INTEGRITY_HOLD_ACTIVE_6466",
    )

    private val classifications = AtomicLong(0L)
    private val lastResult = AtomicReference<Classification?>(null)

    fun classify(): Classification {
        classifications.incrementAndGet()
        for ((tier, label) in probes) {
            // V5.0.6496 §3 — consult freshness authority. Historical
            // (lifetime > 0 but no delta within the 60s window) counters
            // MUST NOT surface as active root cause. Only ACTIVE deltas
            // fire the "Root cause likely" bullet.
            val count = try {
                RootCauseFreshnessAuthority6496.activeCount(label)
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelCountSnapshot(label) } catch (_: Throwable) { 0L }
            }
            val lifecycleManaged6510 = label in setOf("PAPER_EQUITY_CONSERVATION_VIOLATION_6467", "EVENT_STREAM_REPLAY_DIVERGED_6467")
            val active6510 = if (lifecycleManaged6510) RootCauseIncidentLifecycle6510.isOpen(label) else count > 0L
            if (active6510) {
                val c = Classification(tier = tier, label = label, supportingCount = count)
                lastResult.set(c)
                try {
                    ForensicLogger.lifecycle(
                        "ROOT_CAUSE_CLASSIFIED_6471",
                        "tier=${tier.name} label=$label count=$count",
                    )
                    PipelineHealthCollector.labelInc("ROOT_CAUSE_${tier.name}_6471")
                } catch (_: Throwable) {}
                return c
            }
        }
        val healthy = Classification(tier = Tier.HEALTHY, label = "-", supportingCount = 0L)
        lastResult.set(healthy)
        return healthy
    }

    fun lastResult(): Classification? = lastResult.get()

    fun statusLine(): String {
        val stamp = CanonicalInstanceIdentity6472.stamp("RootCauseClassifier6471")
        val r = lastResult.get()
        return "classifications=${classifications.get()} lastTier=${r?.tier?.name ?: "-"} " +
            "lastLabel=${r?.label ?: "-"} lastCount=${r?.supportingCount ?: 0L} $stamp"
    }

    internal fun resetForTest() {
        classifications.set(0L); lastResult.set(null)
    }
}
