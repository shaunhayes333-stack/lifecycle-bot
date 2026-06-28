package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.4286 — terminal outcome quality classifier. Report-only: never hides realized PnL. */
object TerminalOutcomeQualityGate {
    enum class Quality { TRAINABLE, SCRATCH, CONTAMINATED }
    data class Verdict(val quality: Quality, val reason: String, val trainable: Boolean)

    private val qualityCounts = ConcurrentHashMap<String, AtomicLong>()
    private val reasonCounts = ConcurrentHashMap<String, AtomicLong>()

    fun classify(trade: Trade, ledgerAllowsClosedLearning: Boolean, accountingTrainable: Boolean): Verdict {
        if (!trade.side.equals("SELL", ignoreCase = true) && !trade.side.equals("PARTIAL_SELL", ignoreCase = true)) {
            return Verdict(Quality.SCRATCH, "non_terminal_side", false)
        }
        val proof = trade.proofState.uppercase()
        val hasPositionId = trade.positionId.isNotBlank()
        val basisOk = trade.entryPriceSnapshot > 0.0 && trade.entryCostSol > 0.0
        val pnlOk = trade.pnlSol.isFinite() && trade.pnlPct.isFinite() && trade.sol.isFinite()
        val feeSlipOk = trade.feeSol.isFinite() && kotlin.math.abs(trade.quoteDivergencePct).isFinite()
        val reason = when {
            !ledgerAllowsClosedLearning -> "ledger_not_closed"
            !accountingTrainable -> "accounting_not_trainable"
            proof.contains("UNKNOWN") || proof.contains("PENDING") -> "weak_proof_state:$proof"
            !hasPositionId -> "missing_position_id"
            !basisOk -> "missing_entry_basis"
            !pnlOk -> "invalid_pnl_numbers"
            !feeSlipOk -> "invalid_fee_slip_numbers"
            else -> "trainable_terminal"
        }
        val quality = when (reason) {
            "trainable_terminal" -> Quality.TRAINABLE
            "ledger_not_closed", "accounting_not_trainable", "weak_proof_state:$proof" -> Quality.SCRATCH
            else -> Quality.CONTAMINATED
        }
        return Verdict(quality, reason, quality == Quality.TRAINABLE)
    }

    fun report(trade: Trade, lane: String, source: String, verdict: Verdict) {
        try {
            bump(qualityCounts, verdict.quality.name)
            bump(reasonCounts, verdict.reason.take(80))
            ForensicLogger.lifecycle("TERMINAL_OUTCOME_QUALITY_4286", "quality=${verdict.quality} trainable=${verdict.trainable} reason=${verdict.reason} mode=${trade.mode} lane=$lane source=$source mint=${trade.mint.take(10)} positionId=${trade.positionId.take(18)} proof=${trade.proofState} pnlSol=${trade.pnlSol.fmtLocal(5)} pnlPct=${trade.pnlPct.fmtLocal(2)}")
            PipelineHealthCollector.labelInc("TERMINAL_OUTCOME_QUALITY_4286_${verdict.quality.name}")
        } catch (_: Throwable) {}
    }

    fun status(limit: Int = 5): String {
        val q = qualityCounts.mapValues { it.value.get() }.toSortedMap()
        val reasons = reasonCounts.mapValues { it.value.get() }.entries.sortedByDescending { it.value }.take(limit.coerceAtLeast(1))
        return "TERMINAL_OUTCOME_QUALITY_STATUS_4359 quality=$q topReasons=${reasons.joinToString(";") { it.key + ":" + it.value }} report_only=true never_hides_realized_pnl=true"
    }

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        map.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    }
}

private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "%.${decimals}f", this) } catch (_: Throwable) { this.toString() }
