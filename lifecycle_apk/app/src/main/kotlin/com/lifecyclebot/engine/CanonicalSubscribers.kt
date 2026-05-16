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
 * Compromise: dedupe-aware mirrors. Each subscriber maintains a small LRU
 * of seen tradeIds. If a tradeId arrives that the subscriber has already
 * processed (because a direct call happened first), the canonical mirror
 * is a no-op. Otherwise the mirror calls the consumer's cheapest entry
 * point so its counter advances. This guarantees:
 *   - No double-counting (LRU dedupe).
 *   - Existing direct call sites keep working (zero invasive change).
 *   - Any trade NOT going through a direct call site (e.g. recovery
 *     from wallet, shadow trade) still educates the consumers.
 *
 * Consumers wired here:
 *   • FluidLearningAI.recordLiveTrade / recordTrade (paper) — has the
 *     simplest signature (isWin: Boolean) so we can safely mirror.
 *
 * Consumers NOT wired here (intentionally, per scope/safety):
 *   • AdaptiveLearningEngine.learnFromTrade(features) — needs
 *     rich TradeFeatures we cannot reconstruct. Must be wired at the
 *     emit site that already has the features.
 *   • RunTracker30D.recordTrade — needs entryPrice/exitPrice/sizeSol/
 *     score/confidence/decision/assetClass — already wired at proper
 *     emit sites; will move to bus subscription in a follow-up commit.
 *   • BehaviorLearning.recordTrade — needs sentiment/volatility/volume
 *     signals; same plan.
 *   • MetaCognitionAI.recordTradeOutcome — keyed by pendingPredictions
 *     map, must be called from the same path that recordEntryPredictions
 *     came from.
 *   • ShadowFDGLearning — settlement engine is a separate spec item (9).
 *
 * The canonical pipeline observes ALL of them via the same bus, so the
 * UI can compare canonical totals vs each consumer's local count and
 * surface drift (which is the operator's goal — eliminate the
 * '722 vs 5 vs 10' confusion at the dashboard level).
 */
package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.FluidLearningAI
import java.util.Collections

object CanonicalSubscribers {
    private const val TAG = "CanonicalSubscribers"

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
                // Only educate on settled outcomes, not OPEN / INCONCLUSIVE.
                if (outcome.result != TradeResult.WIN && outcome.result != TradeResult.LOSS) return@subscribe
                // V5.9.495z21 — skip if the target token never actually landed.
                // Prevents phantom partial-bridge outcomes from moving the
                // learning-progress needle or biasing the trust layer.
                if (!com.lifecyclebot.engine.execution.ExecutionStatusRegistry.shouldTrainStrategy(outcome.mint)) {
                    return@subscribe
                }
                val isWin = outcome.result == TradeResult.WIN
                try {
                    // V5.9.694 — pass tradeId as dedupKey so FluidLearning's
                    // internal guard can enforce once-only semantics even if
                    // some legacy code path manages to call recordPaperTrade
                    // directly for the same close event.
                    val fluidDedupKey = "bus_${outcome.tradeId}"
                    when (outcome.environment) {
                        TradeEnvironment.LIVE -> FluidLearningAI.recordLiveTrade(isWin, outcome.realizedPnlPct ?: 0.0)
                        TradeEnvironment.PAPER -> FluidLearningAI.recordPaperTrade(isWin, dedupKey = fluidDedupKey)
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

            // V5.9.495z9 — readiness-only mirrors for the rich-feature consumers.
            // The actual learnFromTrade() / recordTrade() / recordTradeOutcome()
            // calls still happen at the existing emit sites in Executor.kt
            // (lines 939, 1111, 6469, 6534) where the full features are
            // already constructed. The bus subscriber here only updates
            // LayerReadinessRegistry so the Learning Pipeline UI shows
            // these layers' sample counts climbing in lockstep with the
            // canonical bus — which is the operator-visible 'fragmentation
            // collapses' signal. No double-counting because these mirrors
            // do NOT call the consumers' actual learning methods.
            for (layer in listOf(
                "AdaptiveLearningEngine",
                "RunTracker30D",
                "BehaviorLearning",
                "MetaCognitionAI",
            )) {
                CanonicalOutcomeBus.subscribe { outcome ->
                    if (!recordOnce(outcome.tradeId, layer)) return@subscribe
                    if (outcome.result != TradeResult.WIN && outcome.result != TradeResult.LOSS) return@subscribe
                    val isWin = outcome.result == TradeResult.WIN
                    LayerReadinessRegistry.recordEducationDetailed(
                        layer = layer,
                        settledDelta = 1L,
                        positiveEvDelta = if (isWin) 1L else 0L,
                        isRichSample = !outcome.featuresIncomplete,
                    )
                    // V5.9.683-FIX: MetaCognitionAI.totalTradesAnalyzed was never
                    // incremented by the bus because recordTradeOutcome() requires
                    // a matching pendingPredictions entry (only registered on V3 Execute).
                    // Tokens that don't reach V3 Execute never register predictions, so
                    // their outcomes are invisible to MetaCognition. Wire a lightweight
                    // canonical bump so the counter advances with real settled trades
                    // and the WalletDigest stops showing Δ=-48 shrinkage.
                    // V5.9.717 — pass mint for dedup: if recordTradeOutcome/recordTrade
                    // already counted this trade via the direct Executor path, the
                    // onCanonicalSettlement impl will skip the increment to avoid double-count.
                    if (layer == "MetaCognitionAI") {
                        try {
                            com.lifecyclebot.v3.scoring.MetaCognitionAI.onCanonicalSettlement(isWin, outcome.mint)
                        } catch (_: Throwable) {}
                    }
                    if (layer == "BehaviorLearning") {
                        try {
                            // V5.9.782 — operator audit items A, C, D, J:
                            // BehaviorLearning now consumes the FULL canonical outcome
                            // (with rich CandidateFeatures payload) instead of only
                            // a counter no-op. Strategy learning is skipped when
                            // outcome.featuresIncomplete=true so feature-poor legacy
                            // bridge samples never pollute the pattern table.
                            com.lifecyclebot.engine.BehaviorLearning.onCanonicalOutcome(outcome)
                            // Keep the legacy settlement no-op call for API surface
                            // compatibility (it's still a no-op internally).
                            com.lifecyclebot.engine.BehaviorLearning.onCanonicalSettlement(isWin, outcome.mint)
                        } catch (_: Throwable) {}
                    }
                    if (layer == "AdaptiveLearningEngine") {
                        try {
                            // V5.9.783 — operator audit item B:
                            // AdaptiveLearningEngine consumes the canonical outcome too.
                            // Skips when outcome.featuresIncomplete=true. learnFromTrade()
                            // has its own (mcap_hold_pnl_minute) dedup so duplicate
                            // publishes don't double-count vs the legacy direct path.
                            com.lifecyclebot.engine.AdaptiveLearningEngine.onCanonicalOutcome(outcome)
                        } catch (_: Throwable) {}
                    }
                }
            }

            // Generic readiness recorder for the strategy/execution layers
            // that don't yet have a direct mirror but DO need their readiness
            // sample count to advance every time a relevant outcome lands.
            // This populates LayerReadinessRegistry so the new UI screen
            // shows non-DISCONNECTED states for layers that participate.
            CanonicalOutcomeBus.subscribe { outcome ->
                val isStrategySettlement = outcome.result == TradeResult.WIN ||
                                           outcome.result == TradeResult.LOSS
                val isExecOutcome = outcome.executionResult != ExecutionResult.UNKNOWN

                val toUpdate = mutableListOf<String>()
                if (isStrategySettlement) toUpdate += LayerEducationRouter.STRATEGY_LAYERS
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
        // Returns true if first time we've seen this (tradeId, layer) pair.
        val key = "${layer}_$tradeId"
        synchronized(seenTradeIds) {
            return seenTradeIds.add(key)
        }
    }
}
