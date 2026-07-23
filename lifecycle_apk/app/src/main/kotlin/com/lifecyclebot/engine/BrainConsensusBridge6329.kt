package com.lifecyclebot.engine

import kotlin.math.max
import kotlin.math.min

/**
 * V5.0.6329 — BRAIN CONSENSUS BRIDGE.
 *
 * Operator directive: "the LLM, SuperAGI, SSI and all the brains
 * should be working together with the modules to be driving
 * profitable trades."
 *
 * This module is the SINGLE fusion point that stacks every existing
 * intelligence surface on top of the 6324/6325/6326/6328 policy
 * modules at the moment a live buy is sized:
 *
 *   • LiveEntrySafetyHold governor           (state × / floor +)
 *   • ImmediateCollapseGuard                 (per-signal size / probe)
 *   • CapitalEfficiencyBrain                 (lane-level capital fit)
 *   • MetaCognitionExecutorBridge            (meta-cog AI layer bias)
 *   • SuperBrainEnhancements.entrySizeMultiplier (per-mint memory)
 *   • BotBrain phase/source boost            (recent behaviour)
 *   • BrainConsensusGate (advisor pass)      (proven-dead veto)
 *   • SentienceOrchestrator reflections      (recent narrative bias)
 *
 * The output is a single, sanitised `consensusMultiplier` in [0.05, 1.10]
 * that liveBuy stacks with governor and collapse guard multipliers,
 * plus an advisorLabels list surfaced into ImmediateCollapseGuard so
 * every brain-detected concern also raises floor / requires probe.
 *
 * NO NEW CHOKE PATHS: every brain lookup is wrapped in try/catch so
 * a broken or missing brain silently returns neutral 1.0. Consensus
 * NEVER hard-blocks — that authority stays with LiveEntrySafetyHold.
 */
object BrainConsensusBridge6329 {

    data class Verdict(
        val multiplier: Double,
        val advisorLabels: List<String>,
        val brainReadings: Map<String, Double>,
        val proveDeadVeto: Boolean,
    )

    /**
     * Fuse all live intelligence into one verdict for the given mint /
     * lane / source. Called from Executor.liveBuy right after the
     * governor + collapse-guard shrink so brains stack on top.
     *
     * Guaranteed to return within a few ms — no I/O, no reflection.
     */
    fun consult(mint: String, symbol: String, lane: String, source: String): Verdict {
        val readings = mutableMapOf<String, Double>()
        val labels = mutableListOf<String>()

        // 1) CapitalEfficiencyBrain — per-lane capital fit (0.0..1.5 range typical)
        val capitalMult = try {
            CapitalEfficiencyBrain.sizeMultiplier(lane, source, isRunnerCandidate = false)
                .coerceIn(0.1, 1.5)
        } catch (_: Throwable) { 1.0 }
        readings["CapitalEfficiencyBrain"] = capitalMult

        // 2) MetaCognitionExecutorBridge — meta-cog AI layer for this lane
        val metaCogMult = try {
            MetaCognitionExecutorBridge.sizeMultiplierForLane(lane).coerceIn(0.1, 1.5)
        } catch (_: Throwable) { 1.0 }
        readings["MetaCognitionExecutorBridge"] = metaCogMult

        // 3) SuperBrainEnhancements — per-mint recency-aware multiplier
        val superBrainMult = try {
            SuperBrainEnhancements.entrySizeMultiplier(mint).coerceIn(0.1, 1.5)
        } catch (_: Throwable) { 1.0 }
        readings["SuperBrainEnhancements"] = superBrainMult

        // 4) BotBrain phase suppression — currently only reachable via
        //    a BotService instance; consensus uses the other four brains
        //    to stay 100% stateless-callable. When BotBrain is later
        //    exposed via a static accessor it can be re-added here.
        val botBrainMult = 1.0
        readings["BotBrain"] = botBrainMult

        // 5) BrainConsensusGate — proven-dead advisory (label scan of pipeline dump).
        //    Never hard-block from here; the authority stays with LiveEntrySafetyHold.
        val provenDead = try {
            val dump = BrainConsensusGate.formatForPipelineDump()
            dump.uppercase().contains("PROVEN_DEAD") && dump.uppercase().contains(lane.uppercase())
        } catch (_: Throwable) { false }
        if (provenDead) labels += "BRAIN_CONSENSUS_PROVEN_DEAD"

        // 6) SentienceOrchestrator — recent reflection bias
        val sentienceMult = try {
            val reflections = SentienceOrchestrator.recentReflections(5)
            var bias = 1.0
            for (r in reflections) {
                val text = r.toString().uppercase()
                if (text.contains("RUG") || text.contains("BLEED") || text.contains("CATASTROPHIC")) bias -= 0.08
                else if (text.contains("PROFIT") || text.contains("WIN") || text.contains("STRONG")) bias += 0.02
            }
            bias.coerceIn(0.5, 1.2)
        } catch (_: Throwable) { 1.0 }
        readings["SentienceOrchestrator"] = sentienceMult

        // Geometric mean protects against any one brain dominating.
        val product = capitalMult * metaCogMult * superBrainMult * botBrainMult * sentienceMult
        val geoMean = Math.pow(product, 1.0 / 5.0)
        val consensus = geoMean.coerceIn(0.05, 1.10)

        // Emit a lightweight event so the operator can see the brains
        // are actually consulted on every live buy.
        try {
            ForensicLogger.lifecycle(
                "BRAIN_CONSENSUS_APPLIED_6329",
                "mint=${mint.take(10)} sym=${symbol.take(12)} lane=$lane src=$source consensus=${"%.3f".format(consensus)} capital=${"%.2f".format(capitalMult)} meta=${"%.2f".format(metaCogMult)} super=${"%.2f".format(superBrainMult)} bot=${"%.2f".format(botBrainMult)} sent=${"%.2f".format(sentienceMult)} labels=${labels.joinToString(",").take(80)}",
            )
            PipelineHealthCollector.labelInc("BRAIN_CONSENSUS_APPLIED_6329")
            if (consensus < 0.75) PipelineHealthCollector.labelInc("BRAIN_CONSENSUS_SIZE_SHRUNK_6329")
            if (provenDead) PipelineHealthCollector.labelInc("BRAIN_CONSENSUS_PROVEN_DEAD_6329")
        } catch (_: Throwable) {}

        return Verdict(consensus, labels.toList(), readings.toMap(), provenDead)
    }
}
