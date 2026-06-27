package com.lifecyclebot.engine

import java.io.File

/**
 * V5.0.4257 — Recursive ASI/SSI re-audit sweeper.
 *
 * This is a CI/background source-contract helper, not a runtime trading gate.
 * It audits whether the ASI/SSI pieces we built are both wired and bounded:
 * no hot-path provider calls, no direct GEPA-to-bank bypass, terminal fanout is
 * event-local/bounded, semantic memory is read back at entry, and learned state
 * persists through LearningPersistence.
 */
object AsiSsiReauditSweeper {
    data class Finding(
        val id: String,
        val passed: Boolean,
        val detail: String,
    )

    fun auditSourceTree(root: File): List<Finding> {
        fun src(path: String): String = try { File(root, path).readText() } catch (_: Throwable) { "" }
        val executor = src("src/main/kotlin/com/lifecyclebot/engine/Executor.kt")
        val semantic = src("src/main/kotlin/com/lifecyclebot/engine/SemanticPatternGraph.kt")
        val replay = src("src/main/kotlin/com/lifecyclebot/engine/CounterfactualReplayEngine.kt")
        val lab = src("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt")
        val gepa = src("src/main/kotlin/com/lifecyclebot/engine/ReflectiveOptimizerGEPA.kt")
        val critic = src("src/main/kotlin/com/lifecyclebot/engine/MultiAgentCriticStack.kt")
        val research = src("src/main/kotlin/com/lifecyclebot/engine/ResearchScout.kt")
        val persistence = src("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt")
        val shit = src("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt")
        val prover = src("src/main/kotlin/com/lifecyclebot/engine/SymbolicInvariantProver.kt")
        return listOf(
            Finding(
                id = "ASI_BACKGROUND_ONLY_NO_HOT_PATH_PROVIDER_4257",
                passed = listOf(lab, gepa, critic, research).all { it.contains("BACKGROUND") } &&
                    !executor.contains("GeminiCopilot.rawText") &&
                    !executor.contains("OkHttpClient") &&
                    !semantic.contains("OkHttpClient"),
                detail = "AI/provider work must remain outside scanner/FDG/executor hot paths",
            ),
            Finding(
                id = "GEPA_CRITIC_MEDIATED_NO_DIRECT_BANK_BYPASS_4257",
                passed = gepa.contains("MultiAgentCriticStack.reviewAndSubmit") && !gepa.contains("AsyncStrategyLab.submitBackgroundHypothesis"),
                detail = "GEPA proposals must pass skeptic/symbolic judge before entering AsyncStrategyLab",
            ),
            Finding(
                id = "ASYNC_LAB_REVIEWED_BIAS_O1_4257",
                passed = lab.contains("reviewedBiasByLane") && lab.contains("never synchronize or scan proposal history here") &&
                    !lab.contains("fun reviewedSizeBias(lane: String, score: Int, regime: String): Double = synchronized"),
                detail = "reviewed strategy bias must be O(1) on FDG/Executor sizing path",
            ),
            Finding(
                id = "TERMINAL_FANOUT_EVENT_LOCAL_BOUNDED_4257",
                passed = executor.contains("val graphMint = graphTrade.mint.ifBlank { ts.mint }") &&
                    executor.contains("val graphSymbol = ts.symbol") &&
                    executor.contains("val graphDeployer = ts.tokenMap.creatorOrDevWallet") &&
                    semantic.contains("MAX_PRIOR_EDGE_SCAN") && semantic.contains("MAX_EDGES_PER_NODE") &&
                    gepa.contains("MIN_REFLECTION_INTERVAL_MS"),
                detail = "terminal sell fanout must snapshot mutable state and bound sell-storm work",
            ),
            Finding(
                id = "SEMANTIC_ENTRY_READBACK_WIRED_SOFT_ONLY_4257",
                passed = semantic.contains("fun entryBias") && shit.contains("SemanticPatternGraph.entryBias") &&
                    shit.contains("if (semanticBias.scoreDelta > 0)") && semantic.contains("avgPnl <= -15.0 -> EntryBias(0.94, 0"),
                detail = "semantic memory must be consumed as cached soft-shape, never hard entry rejection",
            ),
            Finding(
                id = "REPLAY_RESEARCH_PERSISTENCE_WIRED_4257",
                passed = replay.contains("exportState") && research.contains("exportState") &&
                    persistence.contains("COUNTERFACTUAL_REPLAY") && persistence.contains("RESEARCH_SCOUT") &&
                    executor.contains("CounterfactualReplayEngine.recordTerminalTrade") && executor.contains("ResearchScout.enqueueBackgroundRequest") &&
                    research.contains("maybeRunPeriodicBackgroundSweep") && research.contains("MIN_PERIODIC_SWEEP_INTERVAL_MS"),
                detail = "counterfactual/research state must persist and be fed from terminal outcomes",
            ),
            Finding(
                id = "COMPOUND_PROVER_AND_REAUDIT_REGISTERED_4257",
                passed = prover.contains("AUTO_COMPOUND_LANE_CONSUMERS_4253") &&
                    prover.contains("AsiSsiReauditSweeper") &&
                    src("audits/asi_ssi_audit_queue_2026-06-27.md").contains("A21 — Recursive ASI/SSI ReAuditSweeper"),
                detail = "recursive reaudit itself must be registered in symbolic/source contracts",
            ),
        )
    }

    fun failed(root: File): List<Finding> = auditSourceTree(root).filter { !it.passed }
}
