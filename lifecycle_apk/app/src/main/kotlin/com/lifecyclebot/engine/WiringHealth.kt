package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.CapitalEfficiencyAI
import com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI
import com.lifecyclebot.v3.scoring.FundingRateAwarenessAI
import com.lifecyclebot.v3.scoring.NewsShockAI
import com.lifecyclebot.v3.scoring.OperatorFingerprintAI
import com.lifecyclebot.v3.scoring.OrderbookImbalancePulseAI
import com.lifecyclebot.v3.scoring.PeerAlphaVerificationAI
import com.lifecyclebot.v3.scoring.StablecoinFlowAI
import com.lifecyclebot.v3.scoring.TokenDNAClusteringAI

/**
 * V5.9.362 — Layer Wiring Health
 *
 * Aggregates the V5.9.357 outer-ring layers' real-data accumulation so the
 * UI can show a "🔧 Wiring 7/9" badge plus a per-layer breakdown. Each
 * layer reports `(samples, threshold, ready)` — when `ready=true`, that
 * layer has crossed its warmup floor and is contributing real votes.
 */
object WiringHealth {

    data class LayerHealth(
        val name: String,
        val samples: Int,
        val threshold: Int,
        val ready: Boolean,
    )

    fun snapshot(): List<LayerHealth> = listOf(
        toLayer("CapitalEfficiency",   CapitalEfficiencyAI.getWiringHealth()),
        toLayer("ExecutionCost",       ExecutionCostPredictorAI.getWiringHealth()),
        toLayer("FundingRate",         FundingRateAwarenessAI.getWiringHealth()),
        toLayer("NewsShock",           NewsShockAI.getWiringHealth()),
        toLayer("OperatorFingerprint", OperatorFingerprintAI.getWiringHealth()),
        toLayer("OBImbalancePulse",    OrderbookImbalancePulseAI.getWiringHealth()),
        toLayer("TokenDNACluster",     TokenDNAClusteringAI.getWiringHealth()),
        toLayer("PeerAlphaVerify",     PeerAlphaVerificationAI.getWiringHealth()),
        toLayer("StablecoinFlow",      StablecoinFlowAI.getWiringHealth()),
    )

    private fun toLayer(name: String, t: Triple<Int, Int, Boolean>): LayerHealth =
        LayerHealth(name, samples = t.first, threshold = t.second, ready = t.third)

    /** Compact one-line summary for badge display. */
    fun summaryLine(): String {
        val all = snapshot()
        val ready = all.count { it.ready }
        return "🔧 LAYER WIRING $ready/${all.size}"
    }

    /** Multi-line breakdown for diagnostics panel. */
    fun detailBlock(): String {
        val all = snapshot()
        val readyCount = all.count { it.ready }
        return buildString {
            append("🔧 LAYER WIRING HEALTH ")
            append("$readyCount/${all.size} READY\n")
            for (l in all) {
                val icon = if (l.ready) "🟢" else if (l.samples > 0) "🟡" else "🔴"
                append("  $icon ${l.name.padEnd(20)} ${l.samples}/${l.threshold}\n")
            }
        }.trimEnd()
    }
}
