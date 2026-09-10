package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6504 §10 — ECONOMIC PURITY GATE.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Any position with RUNNER_EXIT_BASIS_UNTRUSTED or quantity repair
 *    pending is excluded from:
 *      realized PnL
 *      WR/PF/EV
 *      tactic training
 *      reward shaper
 *      governor
 *      hypothesis engine
 *    until reconciled.
 *    Rebuild contaminated PAPER performance from immutable fills after
 *    repair.
 *    Do not treat TRUMP +7133.8% or derived BLUECHIP +1300% EV as
 *    canonical while basis is untrusted."
 *
 * DESIGN
 * ──────
 * Single read surface consulted by every analytics / learner ingress:
 *   `shouldExcludeFromAnalytics(mint, reason)` — boolean gate
 *
 * A mint enters the untrusted set on any of the following signals:
 *   • RUNNER_EXIT_BASIS_UNTRUSTED tag (marked by exit path)
 *   • QuantityInvariantAuthority6500.isQuarantined(mint) == true
 *   • LearningQuarantineGate6470.isQuarantined(mint) == true
 *   • FillLotLedger6504 assertMatches() fails (repair pending)
 *
 * V5.0.6712 SOURCE AUTHORITY REPAIR:
 * Account-wide JournalEconomicReplay divergence is diagnostic/rebuild state,
 * not evidence that an unrelated exact terminal event is impure. It is
 * therefore telemetry only here. Per-mint / per-position purity remains hard.
 * This prevents one historical replay mismatch from starving every learner.
 */
object EconomicPurityGate6504 {

    private val untrusted = ConcurrentHashMap<String, UntrustedRecord>()
    private val queries = AtomicLong(0L)
    private val exclusions = AtomicLong(0L)
    private val globalPaperExclusions6692 = AtomicLong(0L)

    data class UntrustedRecord(
        val mint: String,
        val reason: String,
        val markedAtMs: Long,
    )

    fun markUntrusted(mint: String, reason: String) {
        if (mint.isBlank()) return
        val trimmed = reason.take(64)
        val prev = untrusted.putIfAbsent(
            mint, UntrustedRecord(mint, trimmed, System.currentTimeMillis()),
        )
        if (prev == null) {
            try {
                ForensicLogger.lifecycle(
                    "ECONOMIC_PURITY_MARK_UNTRUSTED_6504",
                    "mint=${mint.take(10)} reason=$trimmed",
                )
                PipelineHealthCollector.labelInc("ECONOMIC_PURITY_MARK_UNTRUSTED_6504")
            } catch (_: Throwable) {}
        }
    }

    fun clearUntrusted(mint: String) {
        if (mint.isBlank()) return
        if (untrusted.remove(mint) != null) {
            try {
                ForensicLogger.lifecycle(
                    "ECONOMIC_PURITY_CLEAR_UNTRUSTED_6504",
                    "mint=${mint.take(10)}",
                )
                PipelineHealthCollector.labelInc("ECONOMIC_PURITY_CLEAR_UNTRUSTED_6504")
            } catch (_: Throwable) {}
        }
    }

    fun shouldExcludeFromAnalytics(mint: String, emit: Boolean = false): Boolean {
        queries.incrementAndGet()
        if (mint.isBlank()) return false

        val local = untrusted.containsKey(mint)
        val invariantBroken = try {
            QuantityInvariantAuthority6500.isQuarantined(mint)
        } catch (_: Throwable) { false }
        val historical = try {
            LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
        } catch (_: Throwable) { false }

        // V5.0.6712 — diagnostic only. Never convert an account-level replay
        // mismatch into a blanket terminal-learning veto for every mint.
        val unreconciledPaperAccount = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
                kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol()) > 0.001
        } catch (_: Throwable) { false }
        if (unreconciledPaperAccount) {
            globalPaperExclusions6692.incrementAndGet()
            if (emit) {
                try {
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712")
                    ForensicLogger.lifecycle(
                        "ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712",
                        "mint=${mint.take(10)} action=diagnostic_only_per_terminal_purity_authoritative",
                    )
                } catch (_: Throwable) {}
            }
        }

        val excluded = local || invariantBroken || historical
        if (excluded) {
            exclusions.incrementAndGet()
            if (emit) {
                try {
                    ForensicLogger.lifecycle(
                        "ECONOMIC_PURITY_EXCLUSION_6504",
                        "mint=${mint.take(10)} local=$local invariant=$invariantBroken historical=$historical accountReplayDiagnostic=$unreconciledPaperAccount",
                    )
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUSION_6504")
                } catch (_: Throwable) {}
            }
        }
        return excluded
    }

    fun size(): Int = untrusted.size

    fun statusLine(): String =
        "untrustedMints=${untrusted.size} queries=${queries.get()} exclusions=${exclusions.get()} globalPaperTelemetry=${globalPaperExclusions6692.get()}"

    internal fun clearForTest() {
        untrusted.clear()
        queries.set(0L)
        exclusions.set(0L)
        globalPaperExclusions6692.set(0L)
    }
}
