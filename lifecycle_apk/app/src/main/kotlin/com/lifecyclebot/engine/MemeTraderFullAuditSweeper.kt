package com.lifecyclebot.engine

import java.io.File

/**
 * V5.0.4261 — Full Meme Trader audit sweeper for the original A-J audit plan.
 *
 * This is a source-contract/audit helper. It keeps the entire meme-trader surface
 * in scope: compounding, cross-talk, canonical outcomes, regime/volatility,
 * SuperBrain, Express, ShadowLearning, exit manager duplication, hold-time, and
 * MetaCognition consumption. It never gates trading.
 */
object MemeTraderFullAuditSweeper {
    data class AuditItem(
        val id: String,
        val status: String,
        val detail: String,
    )

    fun auditSourceTree(root: File): List<AuditItem> {
        fun src(path: String): String = try { File(root, path).readText() } catch (_: Throwable) { "" }
        val bot = src("src/main/kotlin/com/lifecyclebot/engine/BotService.kt")
        val executor = src("src/main/kotlin/com/lifecyclebot/engine/Executor.kt")
        val fdg = src("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")
        val aiCrossTalk = src("src/main/kotlin/com/lifecyclebot/engine/AICrossTalk.kt")
        val compound = src("src/main/kotlin/com/lifecyclebot/engine/AutoCompoundEngine.kt")
        val prover = src("src/main/kotlin/com/lifecyclebot/engine/SymbolicInvariantProver.kt")
        val persistence = src("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt")
        val express = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt")
        val shadow = src("src/main/kotlin/com/lifecyclebot/engine/ShadowLearningEngine.kt")
        val sellOpt = src("src/main/kotlin/com/lifecyclebot/v3/scoring/SellOptimizationAI.kt")
        val hold = src("src/main/kotlin/com/lifecyclebot/v3/scoring/HoldTimeOptimizerAI.kt")
        val meta = src("src/main/kotlin/com/lifecyclebot/engine/MetaCognitionAI.kt") + src("src/main/kotlin/com/lifecyclebot/v3/scoring/MetaCognitionAI.kt")
        val regime = src("src/main/kotlin/com/lifecyclebot/v3/scoring/RegimeTransitionAI.kt") + src("src/main/kotlin/com/lifecyclebot/v3/scoring/VolatilityRegimeAI.kt")
        val superBrain = src("src/main/kotlin/com/lifecyclebot/engine/SuperBrainEnhancements.kt")
        return listOf(
            item("PASS_A_COMPOUNDING_4261", prover.contains("AUTO_COMPOUND_LANE_CONSUMERS_4253") && compound.contains("getSizeMultiplier"), "AutoCompound must be consumed/proven across meme lanes"),
            item("PASS_B_CROSSTALK_ENTRY_CONSUMPTION_4261", aiCrossTalk.contains("analyzeCrossTalk") && (bot + executor + fdg).contains("analyzeCrossTalk") && (bot + executor + fdg).contains("MemeCrossTalkEntryBridge"), "AICrossTalk must shape entry decisions, not only reports/final gate"),
            item("PASS_C_CANONICAL_OUTCOME_BUS_4261", executor.contains("CanonicalOutcomeBus") || executor.contains("recordTrade") && executor.contains("SemanticPatternGraph.recordOutcome"), "Terminal outcomes must fan out through canonical learning/memory"),
            item("PAPER_LIVE_INTELLIGENCE_ALIGNMENT_4262", executor.contains("PaperLiveIntelligenceBridge.liveSizeMultiplier") && src("src/main/kotlin/com/lifecyclebot/engine/PaperLiveIntelligenceBridge.kt").contains("TradeHistoryStore.getRecentValidClosedForMode"), "Paper learning must align softly into live sizing without fabricating live PnL"),
            item("PASS_D_REGIME_VOLATILITY_4261", persistence.contains("REGIME_TRANSITION") && persistence.contains("VOLATILITY_REGIME") && regime.contains("exportState"), "Regime/volatility state must persist and feed consumers"),
            item("PASS_E_SUPERBRAIN_CONSUMPTION_4261", superBrain.contains("entrySizeMultiplier") && executor.contains("SuperBrainEnhancements.recordSignal") && executor.contains("SuperBrainEnhancements.entrySizeMultiplier") && executor.contains("SUPERBRAIN_ENTRY_SIZE_SHAPED_4265"), "SuperBrainEnhancements must influence actual trade surfaces, not sit as theatre"),
            item("PASS_F_EXPRESS_PARITY_4261", express.contains("restoreRide") && bot.contains("EXPRESS_DAILY_LOSS_RECOVERY_PROBE_4223"), "ShitCoinExpress must have live/paper state, restore, and probe parity"),
            item("PASS_G_SHADOW_LEARNING_4261", shadow.contains("addVariant") && shadow.contains("bestVariantSizeBias") && executor.contains("ShadowLearningEngine.onTradeOpportunity") && executor.contains("ShadowLearningEngine.bestVariantSizeBias"), "ShadowLearningEngine variants must be registered/consumed"),
            item("PASS_H_EXIT_MANAGER_DEDUP_4261", sellOpt.contains("recordExitOutcome") && executor.contains("SellOptimizationAI.recordExitOutcome") && src("src/main/kotlin/com/lifecyclebot/engine/HoldingLogicLayer.kt").contains("AEM_HOLDING_ADVISORY_ONLY_4264") && !executor.contains("ExitManager.execute"), "Exit authority must avoid duplicate manager firing and feed sell optimization"),
            item("PASS_I_HOLD_TIME_OPTIMIZER_4261", hold.contains("exportState") && persistence.contains("HOLD_TIME_OPTIMIZER") && (bot + executor).contains("holdTime"), "Hold-time intelligence must persist and affect entry/exit learning"),
            item("PASS_J_METACOGNITION_CONSUMPTION_4261", meta.isNotBlank() && executor.contains("MetaCognitionExecutorBridge.sizeMultiplierForLane") && src("src/main/kotlin/com/lifecyclebot/engine/MetaCognitionExecutorBridge.kt").contains("MetaCognitionAI.getTrustMultiplier"), "MetaCognition grade must be consumed by real decision/sizing surfaces"),
        )
    }

    fun openItems(root: File): List<AuditItem> = auditSourceTree(root).filter { it.status != "complete" }

    private fun item(id: String, passed: Boolean, detail: String): AuditItem = AuditItem(id, if (passed) "complete" else "open", detail)
}
