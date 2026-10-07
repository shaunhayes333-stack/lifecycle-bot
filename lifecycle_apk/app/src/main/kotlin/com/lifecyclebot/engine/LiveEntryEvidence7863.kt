package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AateDecisionFabric6512

/** Immutable strategy evidence captured before an asynchronous live buy can change the watchlist. */
internal data class LiveEntryEvidence7863(
    val desk: ToolkitSignalSheet.DeskHypothesis?, val source: String, val regime: String,
    val v3: String, val consensusConfidence: Double, val objections: String, val meta: String,
    val contributions: String, val velocity: Double, val buyPressure: Double, val sellPressure: Double,
    val holderPct: Double, val rug: String,
) {
    companion object {
        fun capture(ts: TokenState, intent: ExecutableOpenGate.ExecutionIntent): LiveEntryEvidence7863 {
            val lane = intent.canonicalLane
            val decision = AateDecisionFabric6512.get(intent.mode, ts.mint, intent.candidateVersion, lane)
            val desk = try { ToolkitSignalSheet.snapshot(ts).deskHypotheses[lane] } catch (_: Throwable) { null }
            val objections = ts.lastConsensusObjections.toList()
            return LiveEntryEvidence7863(
                desk, decision?.context?.source ?: ts.source,
                decision?.context?.regime ?: try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" },
                "score=${intent.effectiveEntryScore7256};confidence=${ts.lastV3Confidence ?: 0};phase=${ts.phase}",
                if (objections.isEmpty()) 1.0 else 1.0 / (1.0 + objections.size), objections.joinToString("+").take(240),
                "phase=${ts.phase};mode=$lane;ema=${ts.meta.emafanAlignment}",
                ToolkitSignalSheet.contributionSummary(ts).ifBlank { "lane=$lane;tools=${ts.toolAffinity.joinToString("+")}" },
                ts.meta.volScore, ts.lastBuyPressurePct, ts.lastSellPressurePct,
                ts.topHolderPct ?: ts.safety.topHolderPct,
                "rug=${ts.safety.rugcheckStatus};hard=${ts.safety.hardBlockReasons.joinToString("+").take(120)}",
            )
        }
    }
}
