package com.lifecyclebot.engine

/**
 * V5.0.7655 - explicit provenance map for AATE's wider intelligence estate.
 *
 * This registry prevents accidental double voting. It records how a family reaches
 * Super reasoning and which upstream aggregator already represents it.
 */
object SuperEstateCoverageRegistry7655 {
    enum class Route {
        DIRECT_CACHE,
        REPRESENTED_UPSTREAM,
        LEARNED_ESTATE,
        SAFETY_SOVEREIGN,
        BACKGROUND_ONLY,
        REPORT_ONLY,
        LEGACY_NO_AUTOWIRE,
    }

    data class Family(
        val name: String,
        val route: Route,
        val bridge: String,
        val ancestry: String,
        val directionalVote: Boolean,
    )

    val families: List<Family> = listOf(
        Family("Native specialist lanes", Route.REPRESENTED_UPSTREAM, "SpecialistBrainBridge7542", "lane trader/scorer brains", true),
        Family("UnifiedScorer inner AI layers", Route.REPRESENTED_UPSTREAM, "native specialist score", "v3/scoring ensemble + LayerBrain bias", false),
        Family("LayerBrain learned heads", Route.LEARNED_ESTATE, "LayerBrain.estateSnapshot7654", "per-layer online learners", false),
        Family("AI CrossTalk", Route.DIRECT_CACHE, "AICrossTalk.cachedSignal7654", "cross-AI correlation hub", true),
        Family("LLM provider council", Route.DIRECT_CACHE, "AsyncGeminiNarrativeCache6478.peekBySymbol7654", "Gemini/Groq/Cerebras/Mistral/OpenRouter/keyless", true),
        Family("Scanner/arb ensemble", Route.DIRECT_CACHE, "ArbScannerAI.cachedOpportunity", "VenueLag+FlowImbalance+PanicReversion", true),
        Family("Scanner source outcome brain", Route.DIRECT_CACHE, "ScannerSourceBrain.sourceSnapshot7658", "per-source terminal WR/EV learning", true),
        Family("MetaCognition/SuperBrain/BotBrain/Sentience aggregate", Route.REPRESENTED_UPSTREAM, "BrainConsensusBridge6329", "legacy executive/meta aggregate", true),
        Family("Ultimate semantic/source/route edge", Route.REPRESENTED_UPSTREAM, "UltimateEdgeEngine", "semantic+source+route edge cards", true),
        Family("Strategy hypothesis + reviewed lab", Route.REPRESENTED_UPSTREAM, "ExistingIntelligenceContext7650", "strategy learning/research", true),
        Family("Counterfactual replay/MCTS", Route.REPRESENTED_UPSTREAM, "ExistingIntelligenceContext7650", "counterfactual terminal replay", true),
        Family("ForwardOutcome/LiveProbability/world predictors", Route.REPRESENTED_UPSTREAM, "SuperWorldModel7634", "predictive probability/world model", true),
        Family("UnifiedPolicyHead/autonomous policy", Route.REPRESENTED_UPSTREAM, "oracle/world/planner inputs", "learned admission/policy", true),
        Family("Collective/hive intelligence", Route.REPRESENTED_UPSTREAM, "oracle brain reads / scorer", "collective learned signals", true),
        Family("Hard rug/scam/freeze/safety authorities", Route.SAFETY_SOVEREIGN, "canonical safety/FDG", "recorded safety facts", false),
        Family("SSI pilot council", Route.BACKGROUND_ONLY, "SsiPilotCouncil", "provider-backed strategic pilot", false),
        Family("Sentience reflection/orchestration", Route.BACKGROUND_ONLY, "SentienceHooks/SentienceOrchestrator", "reflection/personality/research", false),
        Family("SmartSystemRuntimeRegistry sentinels/providers", Route.REPORT_ONLY, "SmartSystemRuntimeRegistry", "runtime proof/route infrastructure", false),
        Family("PatchWriterAI/HotfixRules", Route.LEGACY_NO_AUTOWIRE, "operator/future only", "repair automation", false),
    )

    data class Coverage(
        val totalFamilies: Int,
        val directCache: Int,
        val representedUpstream: Int,
        val learnedEstate: Int,
        val safetySovereign: Int,
        val backgroundOnly: Int,
        val reportOnly: Int,
        val legacyNoAutowire: Int,
        val directionalFamilies: Int,
    )

    fun coverage(): Coverage = Coverage(
        totalFamilies = families.size,
        directCache = families.count { it.route == Route.DIRECT_CACHE },
        representedUpstream = families.count { it.route == Route.REPRESENTED_UPSTREAM },
        learnedEstate = families.count { it.route == Route.LEARNED_ESTATE },
        safetySovereign = families.count { it.route == Route.SAFETY_SOVEREIGN },
        backgroundOnly = families.count { it.route == Route.BACKGROUND_ONLY },
        reportOnly = families.count { it.route == Route.REPORT_ONLY },
        legacyNoAutowire = families.count { it.route == Route.LEGACY_NO_AUTOWIRE },
        directionalFamilies = families.count { it.directionalVote },
    )

    fun status(): String {
        val c = coverage()
        return "SUPER_ESTATE_COVERAGE_7655 families=${c.totalFamilies} direct=${c.directCache} upstream=${c.representedUpstream} learned=${c.learnedEstate} safety=${c.safetySovereign} background=${c.backgroundOnly} report=${c.reportOnly} legacy=${c.legacyNoAutowire} directional=${c.directionalFamilies}"
    }
}
