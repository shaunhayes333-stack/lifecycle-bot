package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.7654 - bridge from AATE's wider intelligence estate into Super reasoning.
 *
 * Reads only already-computed/cached state. It does not run CrossTalk, scanner
 * models, layer scorers, LLM providers or network refreshes on the oracle path.
 */
object SuperIntelligenceEstate7654 {
    data class Snapshot(
        val crossTalk: AICrossTalk.CrossTalkSignal?,
        val llm: AsyncGeminiNarrativeCache6478.Entry?,
        val arbScore: Int?,
        val arbConfidence: Int?,
        val arbExpectedMovePct: Double?,
        val arbType: String?,
        val layerEstate: LayerBrain.EstateSnapshot7654,
        val smartSystemsTotal: Int,
        val smartSystemsActive: Int,
        val smartSystemsInterfaceUsed: Int,
        val coverageFamilies: Int,
        val coverageDirectional: Int,
    ) {
        fun crossTalkUtility(policy: SuperPolicyTree7638.Policy): Double {
            val s = crossTalk ?: return 0.0
            val directional = (s.entryBoost / 8.0 + s.confidenceBoost / 12.0)
                .coerceIn(-2.0, 2.0)
            return when (policy) {
                SuperPolicyTree7638.Policy.WAIT_REASSESS -> -directional * 0.45
                SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE -> directional * 0.55
                SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD -> directional * 0.75
                SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK -> directional * 0.65
                SuperPolicyTree7638.Policy.CONVICTION_RUNNER -> directional
            }
        }

        fun llmUtility(policy: SuperPolicyTree7638.Policy): Double {
            val e = llm ?: return 0.0
            // Scam is already owned by hard safety/FDG. Do not create a second veto
            // or duplicate the safety vote inside Super planning.
            if (e.quickScam == true || e.analysis?.isScam == true) return 0.0
            val a = e.analysis ?: return 0.0
            val rec = a.recommendation.uppercase()
            val direction = when {
                "STRONG_BUY" in rec || "BUY" in rec -> 1.0
                "AVOID" in rec || "SELL" in rec || "BEAR" in rec -> -1.0
                else -> 0.0
            }
            val viral = ((a.viralPotential - 50.0) / 50.0).coerceIn(-1.0, 1.0)
            val base = (direction * 1.2 + viral * 0.6).coerceIn(-1.5, 1.5)
            return when (policy) {
                SuperPolicyTree7638.Policy.WAIT_REASSESS -> -base * 0.35
                SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE -> base * 0.45
                SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD -> base * 0.70
                SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK -> base * 0.55
                SuperPolicyTree7638.Policy.CONVICTION_RUNNER -> base
            }
        }

        fun scannerUtility(policy: SuperPolicyTree7638.Policy): Double {
            val score = arbScore ?: return 0.0
            val conf = (arbConfidence ?: 0).coerceIn(0, 100) / 100.0
            val move = (arbExpectedMovePct ?: 0.0).coerceIn(-25.0, 25.0)
            if (score < 50 || conf <= 0.0) return 0.0
            val edge = (((score - 50) / 50.0) * 1.2 + move / 20.0)
                .coerceIn(-1.5, 1.5) * conf
            return when (policy) {
                SuperPolicyTree7638.Policy.WAIT_REASSESS -> -edge * 0.30
                SuperPolicyTree7638.Policy.REDUCED_THEN_SCALE -> edge * 0.65
                SuperPolicyTree7638.Policy.BASE_TACTICAL_HOLD -> edge * 0.65
                SuperPolicyTree7638.Policy.BASE_TACTICAL_BANK -> edge * 0.80
                SuperPolicyTree7638.Policy.CONVICTION_RUNNER -> edge * 0.55
            }
        }

        /**
         * Breadth is epistemic context only: it never produces directional utility.
         * It tells telemetry/deliberation whether the wider learned estate exists.
         */
        fun breadthConfidence(): Double {
            if (layerEstate.registered == 0) return 0.0
            val mature = layerEstate.matureRatio
            val calibration = if (layerEstate.registered > 0)
                layerEstate.calibrated.toDouble() / layerEstate.registered.toDouble() else 0.0
            return (mature * 0.65 + calibration * 0.35).coerceIn(0.0, 1.0)
        }

        fun tag(): String = String.format(
            java.util.Locale.US,
            "estate7654(cross=%s,llm=%s,arb=%s/%s@%s,layer=%d mature=%d auth=%d train=%d breadth=%.2f,smart=%d/%d/%d,cov=%d/%d)",
            crossTalk?.signalType?.name ?: "none",
            when {
                llm?.quickScam == true -> "scam"
                llm?.analysis != null -> llm.analysis?.recommendation?.take(18) ?: "none"
                else -> "none"
            },
            arbType ?: "none",
            arbScore?.toString() ?: "-",
            arbConfidence?.toString() ?: "-",
            layerEstate.registered,
            layerEstate.mature,
            layerEstate.authoritative,
            layerEstate.trainedSamples,
            breadthConfidence(),
            smartSystemsTotal,
            smartSystemsActive,
            smartSystemsInterfaceUsed,
            coverageFamilies,
            coverageDirectional,
        )
    }

    fun read(mint: String, symbol: String, lane: String): Snapshot {
        val cross = try {
            AICrossTalk.cachedSignal7654(mint, lane.ifBlank { null }, isOpenPosition = false)
        } catch (_: Throwable) { null }
        val llm = try {
            AsyncGeminiNarrativeCache6478.peekBySymbol7654(symbol)
        } catch (_: Throwable) { null }
        val arb = try {
            com.lifecyclebot.v3.arb.ArbScannerAI.cachedOpportunity(mint)
        } catch (_: Throwable) { null }
        val layers = try {
            LayerBrain.estateSnapshot7654()
        } catch (_: Throwable) {
            LayerBrain.EstateSnapshot7654(0,0,0,0,0,0L,0,0)
        }
        val systems = try { SmartSystemRuntimeRegistry.allSystems() } catch (_: Throwable) { emptyList() }
        val coverage7655 = try { SuperEstateCoverageRegistry7655.coverage() } catch (_: Throwable) {
            SuperEstateCoverageRegistry7655.Coverage(0,0,0,0,0,0,0,0,0)
        }
        return Snapshot(
            crossTalk = cross,
            llm = llm,
            arbScore = arb?.score,
            arbConfidence = arb?.confidence,
            arbExpectedMovePct = arb?.expectedMovePct,
            arbType = arb?.arbType?.name,
            layerEstate = layers,
            smartSystemsTotal = systems.size,
            smartSystemsActive = systems.count { it.runtimeClass == SmartSystemRuntimeRegistry.RuntimeClass.ACTIVE },
            smartSystemsInterfaceUsed = systems.count { it.runtimeClass == SmartSystemRuntimeRegistry.RuntimeClass.INTERFACE_USED },
            coverageFamilies = coverage7655.totalFamilies,
            coverageDirectional = coverage7655.directionalFamilies,
        )
    }
}
