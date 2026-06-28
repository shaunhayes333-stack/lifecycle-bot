package com.lifecyclebot.engine

/**
 * V5.0.4307 — SmartSystemRuntimeRegistry.
 *
 * Report-only runtime proof for the "smart but dormant" sweep.  This does
 * not trade, size, pause, route, patch source, or mutate wallet state.  It
 * classifies high-value systems that must be proven active, interface-used,
 * dormant, sidecar, report-only, deprecated, or future before we trust them
 * as part of AATE's edge stack.
 */
object SmartSystemRuntimeRegistry {
    enum class RuntimeClass {
        ACTIVE,
        INTERFACE_USED,
        NEEDS_RUNTIME_PROOF,
        DORMANT_CANDIDATE,
        REPORT_ONLY,
        FUTURE,
        SIDECAR,
        DEPRECATED,
    }

    data class SmartSystem(
        val name: String,
        val runtimeClass: RuntimeClass,
        val surface: String,
        val requiredProof: String,
        val noHotPathNetwork: Boolean = true,
        val reportOnly: Boolean = true,
    )

    val routeProviders: List<SmartSystem> = listOf(
        SmartSystem("PumpFunDirectProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("PumpPortalProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("PumpSwapDirectProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("RaydiumDirectProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("MeteoraDirectProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("OrcaDirectProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("JupiterUltraProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("JupiterMetisProvider", RuntimeClass.INTERFACE_USED, "route_provider", "providerOrder includes provider; quote/build adapter proof; terminal route outcome attribution"),
        SmartSystem("StandardRpcSender", RuntimeClass.INTERFACE_USED, "sender_provider", "senderOrder includes sender; broadcast/finality attribution"),
        SmartSystem("HeliusSenderProvider", RuntimeClass.INTERFACE_USED, "sender_provider", "senderOrder includes sender; broadcast/finality attribution"),
        SmartSystem("JitoSenderProvider", RuntimeClass.INTERFACE_USED, "sender_provider", "senderOrder includes sender; broadcast/finality attribution"),
    )

    val dormantCandidates: List<SmartSystem> = listOf(
        SmartSystem("ArbCoordinator", RuntimeClass.INTERFACE_USED, "arb_deck", "V5.0.4334 ArbScannerAI.evaluate reaches ArbCoordinator; cached opportunity consumed by Treasury/Express/ShitCoin"),
        SmartSystem("ArbScannerAI", RuntimeClass.ACTIVE, "arb_deck", "V5.0.4334 UnifiedScorer evaluates it; cachedOpportunity consumed by Treasury/Express/ShitCoin"),
        SmartSystem("ArbLearning", RuntimeClass.NEEDS_RUNTIME_PROOF, "arb_deck", "ArbScanner consults recent losses; terminal arb-specific outcome fanout still needs proof"),
        SmartSystem("VenueLagModel", RuntimeClass.INTERFACE_USED, "arb_deck", "V5.0.4334 ArbCoordinator evaluates venue lag into cached opportunity deck"),
        SmartSystem("FlowImbalanceModel", RuntimeClass.INTERFACE_USED, "arb_deck", "V5.0.4334 ArbCoordinator evaluates flow imbalance into cached opportunity deck"),
        SmartSystem("PanicReversionModel", RuntimeClass.INTERFACE_USED, "arb_deck", "V5.0.4334 ArbCoordinator evaluates panic reversion into cached opportunity deck"),
        SmartSystem("SourceTimingRegistry", RuntimeClass.INTERFACE_USED, "arb_deck", "V5.0.4334 ArbScannerAI.recordSourceSeen feeds VenueLagModel source timing"),
        SmartSystem("CloudLearningSync", RuntimeClass.ACTIVE, "hive_sync", "BotService upload/download plus UnifiedScorer communityPatternMultiplier consumer"),
        SmartSystem("StrategyVariantStore", RuntimeClass.INTERFACE_USED, "strategy_genome", "V5.0.4342 consumed by StrategyHypothesisEngine size bias and terminal outcome fanout"),
        SmartSystem("PatchWriterAI", RuntimeClass.FUTURE, "repair_brain", "operator-only/future bounded repair classification"),
        SmartSystem("HotfixRules", RuntimeClass.FUTURE, "repair_brain", "operator-only/future bounded repair classification"),
        SmartSystem("TelegramBot", RuntimeClass.NEEDS_RUNTIME_PROOF, "operator_channel", "active command channel or superseded marker"),
        SmartSystem("ProviderHealthGate", RuntimeClass.DEPRECATED, "provider_health", "Superseded by ApiHealthMonitor + HealthAwareHttp + ExecutionHealthGuard; do not wire duplicate breaker"),
        SmartSystem("ColdStreakDamper", RuntimeClass.ACTIVE, "runtime_health", "SmartSizer consumes sizeMultiplier; V3JournalRecorder feeds terminal outcomes; LearningPersistence saves state"),
    )

    val sentinels: List<SmartSystem> = listOf(
        SmartSystem("AiStatePersistenceSentinel", RuntimeClass.REPORT_ONLY, "sentinel", "emit called at startup; label visible in PipelineHealthCollector"),
        SmartSystem("HotPathProviderCallSentinel", RuntimeClass.REPORT_ONLY, "sentinel", "emit called at startup; label visible in PipelineHealthCollector"),
    )

    fun allSystems(): List<SmartSystem> = routeProviders + dormantCandidates + sentinels

    fun status(): String {
        val byClass = allSystems().groupingBy { it.runtimeClass.name }.eachCount().toSortedMap()
        return "SMART_SYSTEM_RUNTIME_REGISTRY_4344 total=${allSystems().size} routeProviders=${routeProviders.size} dormantCandidates=${dormantCandidates.size} sentinels=${sentinels.size} classes=$byClass arb_deck_reclassified=true cloud_and_cold_streak_active=true provider_gate_deprecated=true report_only=true no_execution_authority=true"
    }

    fun emitStartupProof() {
        try { AiStatePersistenceSentinel.emit() } catch (_: Throwable) {}
        try { HotPathProviderCallSentinel.emit() } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle("SMART_SYSTEM_RUNTIME_REGISTRY_4344", status().take(900))
            PipelineHealthCollector.labelInc("SMART_SYSTEM_RUNTIME_REGISTRY_4344")
            routeProviders.forEach { PipelineHealthCollector.labelInc("SMART_ROUTE_PROVIDER_DECLARED_4307/${it.name}") }
            dormantCandidates.forEach { PipelineHealthCollector.labelInc("SMART_DORMANT_CANDIDATE_4307/${it.name}") }
            sentinels.forEach { PipelineHealthCollector.labelInc("SMART_SENTINEL_RUNTIME_PROOF_4307/${it.name}") }
        } catch (_: Throwable) {}
    }
}
