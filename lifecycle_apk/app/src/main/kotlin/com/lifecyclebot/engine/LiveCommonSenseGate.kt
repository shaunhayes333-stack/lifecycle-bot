package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.Locale

/**
 * V5.0.3748 — universal LIVE-only common-sense admission gate.
 *
 * This is deliberately placed below blacklist/finality identity checks and above
 * EXEC_LIVE_ATTEMPT in Executor.liveBuy(). Paper may continue to sample ugly
 * tokens for learning, but live must not spend SOL on candidates that already
 * expose terminal risk or an exit-dominant setup before broadcast.
 */
object LiveCommonSenseGate {
    data class Verdict(
        val allowed: Boolean,
        val reasonCode: String,
        val detail: String,
    )

    fun evaluate(ts: TokenState, lane: String = "UNKNOWN"): Verdict {
        val laneNorm = lane.ifBlank { ts.position.tradingMode.ifBlank { "UNKNOWN" } }.uppercase(Locale.US)
        val reasons = mutableListOf<String>()

        val safety = ts.safety
        val hardReasons = safety.hardBlockReasons.map { it.trim() }.filter { it.isNotBlank() }
        if (safety.isBlocked || hardReasons.isNotEmpty()) {
            reasons += "SAFETY_HARD_BLOCK:${hardReasons.joinToString("|").take(160).ifBlank { safety.tier.name }}"
        }

        val topHolderCandidates = listOf(
            safety.topHolderPct.takeIf { it >= 0.0 },
            ts.topHolderPct,
            ts.meta.holderConcentration.takeIf { it > 0.0 },
        ).filterNotNull().filter { it.isFinite() }
        val topHolderPct = topHolderCandidates.maxOrNull() ?: -1.0
        if (topHolderPct >= 50.0) {
            reasons += "SINGLE_HOLDER_CONCENTRATION:${"%.1f".format(Locale.US, topHolderPct)}"
        }

        val priceKnown = ts.lastPrice.isFinite() && ts.lastPrice > 0.0
        val sourceKnown = ts.lastPriceSource.isNotBlank() || ts.lastPriceDex.isNotBlank() || ts.lastPricePoolAddr.isNotBlank()
        val chartKnown = ts.history.size >= 2 || ts.history5m.size >= 1 || ts.history15m.size >= 1
        if (!priceKnown) {
            reasons += "NO_LIVE_PRICE"
        }
        if (!chartKnown && ts.phase.contains("unknown", ignoreCase = true)) {
            reasons += "NO_CHART_EARLY_UNKNOWN"
        }
        if (!sourceKnown && ts.phase.contains("unknown", ignoreCase = true)) {
            reasons += "NO_SOURCE_EARLY_UNKNOWN"
        }

        val exitLead = ts.exitScore - ts.entryScore
        if (ts.exitScore >= 20.0 && exitLead >= 8.0) {
            reasons += "EXIT_DOMINATES_ENTRY:E=${"%.1f".format(Locale.US, ts.entryScore)} X=${"%.1f".format(Locale.US, ts.exitScore)}"
        }
        if (ts.entryScore < 20.0 && ts.exitScore >= 20.0) {
            reasons += "ENTRY_TOO_WEAK_FOR_LIVE:E=${"%.1f".format(Locale.US, ts.entryScore)} X=${"%.1f".format(Locale.US, ts.exitScore)}"
        }

        val defaultNeutralInternals =
            ts.meta.pressScore == 50.0 && ts.meta.volScore == 50.0 && ts.meta.momScore == 50.0
        if (defaultNeutralInternals && ts.entryScore < 25.0 && ts.phase.contains("unknown", ignoreCase = true)) {
            reasons += "UNINITIALIZED_NEUTRAL_SIGNAL"
        }

        // Shitcoin/Moonshot/DipHunter are allowed to be aggressive, not blind.
        // They still need non-terminal safety + entry-dominant setup in LIVE.
        if (laneNorm.contains("SHIT") && (topHolderPct >= 35.0 && ts.entryScore < 35.0)) {
            reasons += "SHITCOIN_LIVE_RUG_SHAPE"
        }

        return if (reasons.isEmpty()) {
            Verdict(true, "ALLOW", "ok")
        } else {
            Verdict(false, reasons.first().substringBefore(':'), reasons.joinToString("; "))
        }
    }
}
