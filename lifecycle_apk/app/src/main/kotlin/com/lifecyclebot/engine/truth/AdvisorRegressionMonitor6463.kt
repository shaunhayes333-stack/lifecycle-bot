package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LlmParameterTuner
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6463 §P1 — REVERT-ON-REGRESSION MONITOR.
 *
 * OPERATOR MANDATE (Feb 2026):
 *   "Revert on Regression: Auto-revert an applied advisor change if
 *    WR or EV drops within 20 minutes of the tune landing"
 *
 * DESIGN
 * ──────
 * When AutoPipelineAdvisor6462 auto-applies a change, it registers a
 * pending audit with:
 *   - key
 *   - deltaApplied (signed)
 *   - baseline WR/realizedPnl at apply time
 *   - deadlineMs (nowMs + REVERT_WINDOW_MS)
 *
 * `checkAll(ctx)` is called from the same botLoop cadence (every 12
 * loops ≈ 2 min). For each pending audit past its deadline it reads
 * the current WR/realizedPnl and compares against baseline. If EITHER
 *   • WR dropped by > WR_REGRESSION_PCT (5 pp), OR
 *   • realizedPnl dropped by > EV_REGRESSION_SOL (0.03 SOL)
 * we synthesize a NEGATED <<TUNE>> block and route it through
 * LlmParameterTuner so the same allowlist / step-cap / phase-gate
 * governs the revert. A successful revert emits ADVISOR_REVERTED_6463
 * and records a REVERTED entry in AdvisorDecisionHistory6463.
 *
 * Sample-size gate: revert is disabled when the number of decisive
 * closed trades in the audit window is < MIN_TRADES_IN_WINDOW. Otherwise
 * a single unlucky big loser could trigger a revert of a genuinely
 * beneficial parameter change.
 */
object AdvisorRegressionMonitor6463 {

    private const val REVERT_WINDOW_MS = 20L * 60_000L      // 20 min
    private const val WR_REGRESSION_PCT = 5.0               // 5 pp WR drop
    private const val EV_REGRESSION_SOL = 0.03              // 0.03 SOL PnL drop
    private const val MIN_TRADES_IN_WINDOW = 3              // don't revert on n<3

    data class PendingAudit(
        val id: String,
        val key: String,
        val deltaApplied: Double,
        val baselineWr: Double,
        val baselineRealizedSol: Double,
        val baselineTrades: Int,
        val appliedAtMs: Long,
        val deadlineMs: Long,
        val reason: String,
    )

    private val pending = ConcurrentHashMap<String, PendingAudit>()
    private val checked = AtomicLong(0L)
    private val reverted = AtomicLong(0L)
    private val skippedSample = AtomicLong(0L)

    /**
     * Register a fresh audit after an auto-apply. `id` should be unique
     * per-apply (e.g. the advisor decision UUID).
     */
    fun registerApply(id: String, key: String, deltaApplied: Double, reason: String) {
        val (wr, sol, n) = readWrEvSnapshot()
        val now = System.currentTimeMillis()
        pending[id] = PendingAudit(
            id = id, key = key, deltaApplied = deltaApplied,
            baselineWr = wr, baselineRealizedSol = sol, baselineTrades = n,
            appliedAtMs = now, deadlineMs = now + REVERT_WINDOW_MS,
            reason = reason.take(160),
        )
        try {
            ForensicLogger.lifecycle(
                "ADVISOR_APPLY_AUDIT_REGISTERED_6463",
                "id=$id key=$key deltaApplied=${"%.4f".format(deltaApplied)} baselineWr=${"%.2f".format(wr)} baselineSol=${"%.4f".format(sol)} n=$n",
            )
            PipelineHealthCollector.labelInc("ADVISOR_APPLY_AUDIT_REGISTERED_6463")
        } catch (_: Throwable) {}
    }

    /**
     * Walk pending audits — for each past deadline, check regression
     * and revert via LlmParameterTuner. Non-blocking; call from the
     * bot loop maintenance path.
     */
    fun checkAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val ripe = pending.values.filter { now >= it.deadlineMs }
        if (ripe.isEmpty()) return
        for (audit in ripe) {
            checked.incrementAndGet()
            pending.remove(audit.id)
            val (curWr, curSol, curN) = readWrEvSnapshot()
            val decisiveInWindow = curN - audit.baselineTrades
            if (decisiveInWindow < MIN_TRADES_IN_WINDOW) {
                skippedSample.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "ADVISOR_AUDIT_SKIPPED_SMALL_SAMPLE_6463",
                        "id=${audit.id} key=${audit.key} tradesInWindow=$decisiveInWindow min=$MIN_TRADES_IN_WINDOW",
                    )
                    PipelineHealthCollector.labelInc("ADVISOR_AUDIT_SKIPPED_SMALL_SAMPLE_6463")
                } catch (_: Throwable) {}
                continue
            }
            val wrDrop = audit.baselineWr - curWr
            val solDrop = audit.baselineRealizedSol - curSol
            val regressed = wrDrop > WR_REGRESSION_PCT || solDrop > EV_REGRESSION_SOL
            if (!regressed) {
                try {
                    ForensicLogger.lifecycle(
                        "ADVISOR_AUDIT_HEALTHY_6463",
                        "id=${audit.id} key=${audit.key} wrΔ=${"%+.2f".format(-wrDrop)} solΔ=${"%+.4f".format(-solDrop)} n=$decisiveInWindow",
                    )
                    PipelineHealthCollector.labelInc("ADVISOR_AUDIT_HEALTHY_6463")
                } catch (_: Throwable) {}
                continue
            }
            revertOne(ctx, audit, wrDrop = wrDrop, solDrop = solDrop, decisiveInWindow = decisiveInWindow)
        }
    }

    private fun revertOne(ctx: Context, audit: PendingAudit, wrDrop: Double, solDrop: Double, decisiveInWindow: Int) {
        val negatedDelta = -audit.deltaApplied
        val reason = "REVERT_6463 wrDrop=${"%.2f".format(wrDrop)}pp solDrop=${"%.4f".format(solDrop)} n=$decisiveInWindow orig:${audit.reason.take(80)}"
        val block = JSONObject().apply {
            put("adjustments", JSONArray().apply {
                put(JSONObject().apply {
                    put("key", audit.key)
                    put("delta", negatedDelta)
                    put("reason", reason)
                })
            })
        }
        val synthetic = "<<TUNE>>${block}<<ENDTUNE>>"
        val res = try {
            LlmParameterTuner.extractAndApply(ctx, synthetic)
        } catch (t: Throwable) {
            try {
                ForensicLogger.lifecycle(
                    "ADVISOR_REVERT_APPLY_FAILED_6463",
                    "id=${audit.id} key=${audit.key} err=${t.message?.take(100) ?: t.javaClass.simpleName}",
                )
                PipelineHealthCollector.labelInc("ADVISOR_REVERT_APPLY_FAILED_6463")
            } catch (_: Throwable) {}
            return
        }
        if (res.changes.isEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "ADVISOR_REVERT_NOOP_6463",
                    "id=${audit.id} key=${audit.key} rejected=${res.rejected.joinToString(",").take(120)}",
                )
                PipelineHealthCollector.labelInc("ADVISOR_REVERT_NOOP_6463")
            } catch (_: Throwable) {}
            return
        }
        val ch = res.changes.first()
        reverted.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "ADVISOR_REVERTED_6463",
                "id=${audit.id} key=${ch.key} " +
                    "old=${"%.4f".format(ch.oldValue)} new=${"%.4f".format(ch.newValue)} " +
                    "wrDrop=${"%.2f".format(wrDrop)}pp solDrop=${"%.4f".format(solDrop)} n=$decisiveInWindow",
            )
            PipelineHealthCollector.labelInc("ADVISOR_REVERTED_6463")
            ErrorLogger.warn("AdvisorRegressionMonitor6463",
                "🔁 auto-reverted ${ch.key}: ${"%.4f".format(ch.oldValue)}→${"%.4f".format(ch.newValue)} " +
                    "(wrDrop=${"%.2f".format(wrDrop)}pp solDrop=${"%.4f".format(solDrop)} n=$decisiveInWindow)")
            AdvisorDecisionHistory6463.record(
                AdvisorDecisionHistory6463.Decision(
                    atMs = System.currentTimeMillis(),
                    key = ch.key, delta = negatedDelta, severity = "high",
                    source = "regression_monitor",
                    action = AdvisorDecisionHistory6463.Action.REVERTED,
                    brainAgreement = 1.0, votes = emptyList(),
                    reason = reason,
                    oldValue = ch.oldValue, newValue = ch.newValue,
                )
            )
        } catch (_: Throwable) {}
    }

    private fun readWrEvSnapshot(): Triple<Double, Double, Int> {
        return try {
            val s = TradeHistoryStore.getLifetimeStats()
            val decisive = s.totalWins + s.totalLosses
            Triple(s.winRate, s.realizedPnlSol, decisive)
        } catch (_: Throwable) { Triple(0.0, 0.0, 0) }
    }

    fun statusLine(): String =
        "pending=${pending.size} checked=${checked.get()} reverted=${reverted.get()} skippedSmallSample=${skippedSample.get()}"

    fun pendingCount(): Int = pending.size

    internal fun resetForTest() {
        pending.clear(); checked.set(0L); reverted.set(0L); skippedSample.set(0L)
    }
}
