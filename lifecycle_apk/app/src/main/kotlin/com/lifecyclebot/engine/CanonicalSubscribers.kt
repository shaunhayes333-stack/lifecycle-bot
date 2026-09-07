/*
 * V5.9.495z8 — Canonical Subscribers (operator spec next-step:
 * 'migrate FluidLearningAI / AdaptiveLearningEngine / ShadowFDGLearning /
 *  RunTracker30D / MetaCognitionAI / BehaviorLearning to subscribe to
 *  CanonicalOutcomeBus').
 *
 * SAFE MIGRATION STRATEGY
 * ───────────────────────
 * Each consumer's existing learning method has a different signature,
 * many requiring rich features (entryScore, setupQuality, marketSentiment,
 * volatilityLevel, …) that the canonical bridge does not have when
 * derived from a legacy Trade record. Hard-replacing the existing direct
 * call sites would either drop those features or require touching every
 * call site in the trade-close path.
 *
 * Canonical close outcomes now fan out to the real learner adapters. Each
 * adapter retains its own dedup so direct legacy close sites can coexist
 * while migration completes without double-learning.
 */
package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.FluidLearningAI
import java.util.Collections

object CanonicalSubscribers {
    private const val TAG = "CanonicalSubscribers"

    /**
     * V5.0.6690 — Meme Trader is a desk, not one source enum.
     *
     * A V5.9.792 isolation patch narrowed the accepted cohort to only five
     * sources. That accidentally cut Quality/Lab/Project/Dip/Standard (V3),
     * Treasury/CashGen, CopyTrade and BlueChip specialist outcomes out of the
     * meme feedback loop. Those lanes could execute while the core meme brain
     * never learned from their results.
     *
     * Keep foreign domains isolated, but restore every source that is part of
     * the Meme Trader desk. BLUECHIP is accepted only with the exact BlueChip
     * source+mode tuple so unrelated bluechip-domain events cannot bleed in.
     */
    private val MEME_LEARNING_SOURCES = setOf(
        TradeSource.V3,
        TradeSource.TREASURY,
        TradeSource.BLUECHIP,
        TradeSource.SHITCOIN,
        TradeSource.MOONSHOT,
        TradeSource.MANIP,
        TradeSource.EXPRESS,
        TradeSource.COPYTRADE,
        TradeSource.CYCLIC,
    )

    private fun isMemeLearningOutcome(outcome: CanonicalTradeOutcome): Boolean {
        if (outcome.source !in MEME_LEARNING_SOURCES) return false
        return when (outcome.assetClass) {
            AssetClass.MEME -> true
            AssetClass.BLUECHIP ->
                outcome.source == TradeSource.BLUECHIP && outcome.mode == TradeMode.BLUECHIP
            else -> false
        }
    }

    /** Bounded LRU of recently-mirrored tradeIds to prevent double-counting. */
    private const val LRU_MAX = 1024
    private val seenTradeIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(LRU_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > LRU_MAX
            }
        })
    )

    @Volatile private var registered = false

    /**
     * Register all subscribers exactly once. Safe to call from BotService
     * startup or BotApplication.onCreate(). Subsequent calls are no-ops.
     */
    fun registerAll() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true

            // FluidLearningAI mirror — simplest signature.
            CanonicalOutcomeBus.subscribe { outcome ->
                if (!recordOnce(outcome.tradeId, "FluidLearningAI")) return@subscribe
                if (!outcome.isTrainable) {
                    try { PipelineHealthCollector.labelInc("INVALID_ACCOUNTING_NOT_TRAINED") } catch (_: Throwable) {}
                    return@subscribe
                }
                // Only educate on settled outcomes, not OPEN / INCONCLUSIVE.
                if (outcome.result != TradeResult.WIN && outcome.result != TradeResult.LOSS) return@subscribe

                // V5.0.6690 — hard domain gate by canonical Meme Trader desk
                // membership, not a five-source subset. This restores specialist
                // lane education without allowing Markets/Forex/Stocks/etc in.
                if (!isMemeLearningOutcome(outcome)) {
                    try {
                        PipelineHealthCollector.labelInc(
                            "LEARNING_DOMAIN_REJECTED|src=${outcome.assetClass.name}|targetBrain=MEME|reason=CROSS_DOMAIN"
                        )
                    } catch (_: Throwable) {}
                    return@subscribe
                }

                // LABEL INTEGRITY. Never train a declared WIN with negative PnL
                // or a declared LOSS with positive PnL.
                run {
                    val p = outcome.realizedPnlPct
                    if (p != null) {
                        val declaredWin = outcome.result == TradeResult.WIN
                        val mismatch = (declaredWin && p < 0.0) || (!declaredWin && p > 0.0)
                        if (mismatch) {
                            try {
                                PipelineHealthCollector.labelInc(
                                    "LEARNING_LABEL_MISMATCH_REJECTED|pnlPct=${"%.2f".format(p)}|requested=${if (declaredWin) "WIN" else "LOSS"}|corrected=${if (p < 0.0) "LOSS" else "WIN"}"
                                )
                                ForensicLogger.lifecycle(
                                    "LEARNING_LABEL_MISMATCH_REJECTED",
                                    "mint=${outcome.mint} pnlPct=${"%.2f".format(p)} declared=${outcome.result.name}",
                                )
                            } catch (_: Throwable) {}
                            return@subscribe
                        }
                    }
                }

                // Skip phantom / unlanded entries.
                if (!com.lifecyclebot.engine.execution.ExecutionStatusRegistry.shouldTrainStrategy(outcome.mint)) {
                    return@subscribe
                }

                // BC-sim-only outcomes never train production WR.
                if (outcome.bcSimOnly) {
                    try {
                        ForensicLogger.lifecycle(
                            "WR_FILTERED_BC_SIM_ONLY",
                            "mint=${outcome.mint} mode=${outcome.mode.name} env=${outcome.environment.name}",
                        )
                    } catch (_: Throwable) {}
                    return@subscribe
                }

                val isWin = outcome.result == TradeResult.WIN
                try {
                    val fluidDedupKey = "bus_${outcome.tradeId}"
                    when (outcome.environment) {
                        TradeEnvironment.LIVE -> FluidLearningAI.recordLiveTrade(
                            isWin,
                            outcome.realizedPnlPct ?: 0.0,
                        )
                        // V5.0.6690 — the old subscriber omitted pnlPct here.
                        // W/L moved but expectancy stayed pinned at 0%, so the
                        // adaptive loop could not learn magnitude/quality.
                        TradeEnvironment.PAPER -> FluidLearningAI.recordPaperTrade(
                            isWin = isWin,
                            pnlPct = outcome.realizedPnlPct ?: 0.0,
                            dedupKey = fluidDedupKey,
                        )
                        TradeEnvironment.SHADOW -> { /* shadow doesn't affect Fluid trust */ }
                    }
                    LayerReadinessRegistry.recordEducationDetailed(
                        layer = "FluidLearningAI",
                        settledDelta = 1L,
                        positiveEvDelta = if (isWin) 1L else 0L,
                        isRichSample = !outcome.featuresIncomplete,
                    )
                } catch (t: Throwable) {
                    ErrorLogger.debug(TAG, "Fluid mirror threw: ${t.message?.take(80)}")
                }
            }

            // Canonical adapters for rich-feature consumers.
            for (layer in listOf(
                "AdaptiveLearningEngine",
                "RunTracker30D",
                "BehaviorLearning",
                "MetaCognitionAI",
            )) {
                CanonicalOutcomeBus.subscribe { outcome ->
                    if (!recordOnce(outcome.tradeId, layer)) return@subscribe
                    if (!outcome.isTrainable) {
                        try { PipelineHealthCollector.labelInc("INVALID_ACCOUNTING_NOT_TRAINED") } catch (_: Throwable) {}
                        return@subscribe
                    }
                    if (outcome.result != TradeResult.WIN && outcome.result != TradeResult.LOSS) return@subscribe

                    // V5.0.6690 — same canonical Meme Trader desk firewall as
                    // FluidLearningAI. This restores BlueChip/Quality/Lab/
                    // Project/Dip/Treasury/CopyTrade feedback while keeping
                    // foreign markets out of meme strategy memory.
                    if (!isMemeLearningOutcome(outcome)) {
                        try {
                            PipelineHealthCollector.labelInc(
                                "LEARNING_DOMAIN_REJECTED|src=${outcome.assetClass.name}|targetBrain=${layer}|reason=CROSS_DOMAIN"
                            )
                        } catch (_: Throwable) {}
                        return@subscribe
                    }

                    run {
                        val p = outcome.realizedPnlPct
                        if (p != null) {
                            val declaredWin = outcome.result == TradeResult.WIN
                            if ((declaredWin && p < 0.0) || (!declaredWin && p > 0.0)) {
                                try {
                                    PipelineHealthCollector.labelInc(
                                        "LEARNING_LABEL_MISMATCH_REJECTED|pnlPct=${"%.2f".format(p)}|requested=${if (declaredWin) "WIN" else "LOSS"}|corrected=${if (p < 0.0) "LOSS" else "WIN"}"
                                    )
                                } catch (_: Throwable) {}
                                return@subscribe
                            }
                        }
                    }

                    val isWin = outcome.result == TradeResult.WIN
                    LayerReadinessRegistry.recordEducationDetailed(
                        layer = layer,
                        settledDelta = 1L,
                        positiveEvDelta = if (isWin) 1L else 0L,
                        isRichSample = !outcome.featuresIncomplete,
                    )

                    if (layer == "MetaCognitionAI") {
                        try {
                            // V5.0.6690 — use the real canonical adapter, not
                            // the old counter-only settlement shim. The adapter
                            // preserves direct-path dedup internally.
                            com.lifecyclebot.v3.scoring.MetaCognitionAI.onCanonicalOutcome(outcome)
                        } catch (_: Throwable) {}
                    }
                    if (layer == "BehaviorLearning") {
                        try {
                            BehaviorLearning.onCanonicalOutcome(outcome)
                            BehaviorLearning.onCanonicalSettlement(isWin, outcome.mint)
                        } catch (_: Throwable) {}
                    }
                    if (layer == "AdaptiveLearningEngine") {
                        try {
                            AdaptiveLearningEngine.onCanonicalOutcome(outcome)
                        } catch (_: Throwable) {}
                    }
                    if (layer == "RunTracker30D") {
                        try {
                            // V5.0.6690 — this adapter existed but was never
                            // subscribed, so proof-run feedback could drift from
                            // the canonical close stream.
                            RunTracker30D.onCanonicalOutcome(outcome)
                        } catch (_: Throwable) {}
                    }
                }
            }

            // UNIVERSAL MEME LAYER VOTE CLOSEOUT.
            CanonicalOutcomeBus.subscribe { outcome ->
                if (!recordOnce(outcome.tradeId, "LayerVoteStore")) return@subscribe
                if (!isMemeLearningOutcome(outcome)) return@subscribe
                if (outcome.result != TradeResult.WIN && outcome.result != TradeResult.LOSS) return@subscribe
                if (outcome.bcSimOnly) return@subscribe
                try {
                    val isWin = outcome.result == TradeResult.WIN
                    com.lifecyclebot.learning.LayerVoteStore.closeoutMeme(
                        mint = outcome.mint,
                        isWin = isWin,
                        pnlPct = outcome.realizedPnlPct ?: 0.0,
                        symbol = outcome.symbol,
                    )
                } catch (t: Throwable) {
                    ErrorLogger.debug(TAG, "LayerVoteStore closeout threw: ${t.message?.take(80)}")
                }
            }

            // Generic readiness recorder for strategy/execution layers.
            CanonicalOutcomeBus.subscribe { outcome ->
                if (!outcome.isTrainable) return@subscribe
                if (outcome.bcSimOnly) return@subscribe
                val isStrategySettlement = outcome.result == TradeResult.WIN ||
                    outcome.result == TradeResult.LOSS
                if (!isStrategySettlement) return@subscribe
                val isExecOutcome = outcome.executionResult != ExecutionResult.UNKNOWN

                val toUpdate = mutableListOf<String>()
                toUpdate += LayerEducationRouter.STRATEGY_LAYERS
                if (isExecOutcome) toUpdate += LayerEducationRouter.EXECUTION_LAYERS

                val isWin = outcome.result == TradeResult.WIN
                for (layer in toUpdate.distinct()) {
                    LayerReadinessRegistry.recordEducationDetailed(
                        layer = layer,
                        settledDelta = 1L,
                        positiveEvDelta = if (isWin) 1L else 0L,
                        isRichSample = !outcome.featuresIncomplete,
                    )
                }
            }

            ErrorLogger.info(TAG, "✅ Canonical subscribers registered (subscribers=${CanonicalOutcomeBus.subscriberCount()})")
        }
    }

    private fun recordOnce(tradeId: String, layer: String): Boolean {
        val key = "${layer}_$tradeId"
        synchronized(seenTradeIds) {
            return seenTradeIds.add(key)
        }
    }
}
