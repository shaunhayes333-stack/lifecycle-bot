/*
 * V5.9.495z7 — Canonical Learning Pipeline (operator spec May 2026).
 *
 * One canonical trade outcome model, one normalizer, one publish bus,
 * one router, one diagnostic counter system. All AI layers learn from
 * the SAME event stream — eliminating the 722 vs 5 vs 10 fragmentation.
 *
 * Scope of this file (per operator mandate):
 *   • Canonical data model (CanonicalTradeOutcome + enums)
 *   • Bad-label normalizer (rejects BUY_PHANTOM after TX_PARSE_OK,
 *     SELL_STUCK after rawConsumed>0, etc.)
 *   • CanonicalOutcomeBus pub/sub
 *   • LayerEducationRouter — strategy vs execution separation
 *   • LayerReadiness scaffold
 *   • CrossTalkSignal enum
 *   • Confidence/size cap during SEEDING phase
 *   • Diagnostic counters (canonicalOutcomesTotal, rejectedBadLabels…)
 *
 * Out of scope for this push (will be wired in follow-up commits):
 *   • Per-consumer migration (FluidLearningAI, AdaptiveLearningEngine,
 *     ShadowFDGLearning, RunTracker30D, etc. — they keep their existing
 *     implementations; this file just provides the canonical event they
 *     can subscribe to).
 *   • UI dashboard updates.
 *
 * Hook:
 *   TradeHistoryStore.recordTrade(trade) calls
 *   CanonicalOutcomeBus.publishFromLegacyTrade(trade) on SELL side so
 *   every closed trade now produces a canonical event.
 */
package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────
// Strict enums (operator spec items 1, 4, 5, 10)
// ─────────────────────────────────────────────────────────────────────

enum class AssetClass {
    MEME,
    BLUECHIP,
    CRYPTO_ALT_SPOT,
    STOCK,
    FOREX,
    COMMODITY,
    METAL,
    PERPS_CRYPTOALT,
    UNKNOWN,
}

enum class TradeMode {
    SHITCOIN,
    BLUECHIP,
    EXPRESS,
    MOONSHOT,
    MANIP,
    ALTTRADER,
    PROJECT_SNIPER,
    DIP_HUNTER,
    COPY_TRADE,
    COMMUNITY,
    CYCLIC,
    TREASURY,
    STANDARD,
    UNKNOWN,
}

enum class TradeSource { V3, TREASURY, BLUECHIP, SHITCOIN, MOONSHOT, MANIP, EXPRESS, COPYTRADE, MARKETS, CYCLIC, MANUAL, UNKNOWN }

enum class TradeEnvironment { LIVE, PAPER, SHADOW }

enum class TradeResult { WIN, LOSS, BREAKEVEN, OPEN, CANCELLED, INCONCLUSIVE_PENDING, UNKNOWN }

enum class ExecutionResult {
    EXECUTED,
    FAILED_NO_TX,
    FAILED_REVERTED,
    FAILED_INSUFFICIENT_FUNDS,
    FAILED_ROUTE,
    PHANTOM_UNCONFIRMED,
    STUCK_UNCONFIRMED,
    RECOVERED_FROM_WALLET,
    CLOSED_BY_TX_PARSE,
    CLOSED_BY_WALLET_RECONCILE,
    UNKNOWN,
}

enum class LayerReadiness {
    DISCONNECTED,
    RECEIVING_SIGNALS,
    LEARNING_ONLY,
    PAPER_ELIGIBLE,
    LIVE_ELIGIBLE,
    TRUSTED,
    DEGRADED,
}

enum class CrossTalkSignal {
    PUMP_SIGNAL,
    DUMP_SIGNAL,
    NARRATIVE_SIGNAL,
    LIQUIDITY_COLLAPSE,
    MODE_SWITCH,
    META_LEARNING,
    EXIT_SIGNAL,
    ENTRY_SIGNAL,
    RISK_SIGNAL,
}

// ─────────────────────────────────────────────────────────────────────
// Canonical model (operator spec item 1)
// ─────────────────────────────────────────────────────────────────────

data class LayerVoteSnapshot(
    val score: Double = 0.0,
    val confidence: Double = 0.0,
    val direction: String = "NEUTRAL",   // BULLISH / BEARISH / NEUTRAL
    val veto: Boolean = false,
)

data class CanonicalTradeOutcome(
    val tradeId: String,
    val mint: String,
    val symbol: String,
    val assetClass: AssetClass,
    val mode: TradeMode,
    val source: TradeSource,
    val environment: TradeEnvironment,
    val entryTimeMs: Long,
    val exitTimeMs: Long?,
    val entryPrice: Double?,
    val exitPrice: Double?,
    val entrySol: Double?,
    val exitSol: Double?,
    val realizedPnlSol: Double?,
    val realizedPnlPct: Double?,
    val maxGainPct: Double?,
    val maxDrawdownPct: Double?,
    val holdSeconds: Long?,
    val result: TradeResult,
    val executionResult: ExecutionResult,
    val closeReason: String?,
    val featuresAtEntry: Map<String, Double> = emptyMap(),
    val featuresAtExit: Map<String, Double> = emptyMap(),
    val layerVotesAtEntry: Map<String, LayerVoteSnapshot> = emptyMap(),
    val layerVotesAtExit: Map<String, LayerVoteSnapshot> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
)

// ─────────────────────────────────────────────────────────────────────
// Normalizer (operator spec items 2, 4, 7)
// ─────────────────────────────────────────────────────────────────────

object CanonicalOutcomeNormalizer {

    fun normalizeMode(raw: String?): TradeMode {
        if (raw.isNullOrBlank()) return TradeMode.UNKNOWN
        val k = raw.uppercase().replace("[^A-Z0-9]".toRegex(), "")
        return when {
            k.contains("SHITCOIN") || k == "SHIT" -> TradeMode.SHITCOIN
            k.contains("BLUECHIP") -> TradeMode.BLUECHIP
            k.contains("EXPRESS") -> TradeMode.EXPRESS
            k.contains("MOONSHOT") || k == "MOON" -> TradeMode.MOONSHOT
            k.contains("MANIP") -> TradeMode.MANIP
            k.contains("ALTTRADER") || k == "ALT" -> TradeMode.ALTTRADER
            k.contains("PROJECT") || k.contains("SNIPER") -> TradeMode.PROJECT_SNIPER
            k.contains("DIP") -> TradeMode.DIP_HUNTER
            k.contains("COPYTRADE") || k.contains("COPY") -> TradeMode.COPY_TRADE
            k.contains("COMMUNITY") -> TradeMode.COMMUNITY
            k.contains("CYCLIC") -> TradeMode.CYCLIC
            k.contains("TREASURY") || k.contains("CASH") -> TradeMode.TREASURY
            k.contains("STANDARD") -> TradeMode.STANDARD
            else -> TradeMode.UNKNOWN
        }
    }

    fun normalizeAssetClass(raw: String?): AssetClass {
        if (raw.isNullOrBlank()) return AssetClass.UNKNOWN
        val k = raw.uppercase().replace("[^A-Z0-9]".toRegex(), "")
        return when {
            k.contains("MEME") -> AssetClass.MEME
            k.contains("BLUECHIP") -> AssetClass.BLUECHIP
            k.contains("PERPSCRYPT") || k.contains("PERPS") -> AssetClass.PERPS_CRYPTOALT
            k.contains("CRYPTOALT") || k.contains("ALT") -> AssetClass.CRYPTO_ALT_SPOT
            k.contains("STOCK") || k.contains("EQUITY") -> AssetClass.STOCK
            k.contains("FOREX") || k.contains("FX") -> AssetClass.FOREX
            k.contains("METAL") || k.contains("GOLD") || k.contains("SILVER") -> AssetClass.METAL
            k.contains("COMMOD") -> AssetClass.COMMODITY
            else -> AssetClass.UNKNOWN
        }
    }

    /**
     * Operator spec item 2 — block bad labels before learning.
     * Returns null to REJECT the outcome (counted in rejectedBadLabels).
     */
    fun normalizeOutcomeBeforeLearning(raw: CanonicalTradeOutcome): CanonicalTradeOutcome? {
        // Rule: WIN with no exit → invalid
        if (raw.result == TradeResult.WIN && raw.exitTimeMs == null) {
            CanonicalLearningCounters.rejectedBadLabels.incrementAndGet()
            return null
        }
        // Rule: LOSS with no execution → invalid (execution failure, not strategy loss)
        if (raw.result == TradeResult.LOSS && raw.executionResult != ExecutionResult.EXECUTED &&
            raw.executionResult != ExecutionResult.CLOSED_BY_TX_PARSE &&
            raw.executionResult != ExecutionResult.CLOSED_BY_WALLET_RECONCILE
        ) {
            CanonicalLearningCounters.rejectedBadLabels.incrementAndGet()
            return null
        }
        // Rule: PHANTOM_UNCONFIRMED but a TERMINAL_GOOD on the same mint key
        // already exists in LiveTradeLogStore → execution actually landed.
        if (raw.executionResult == ExecutionResult.PHANTOM_UNCONFIRMED) {
            if (LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.tradeId, sig = null) ||
                LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.mint, sig = null)
            ) {
                CanonicalLearningCounters.rejectedBadLabels.incrementAndGet()
                return null  // do not learn from this stale label
            }
        }
        // Rule: STUCK_UNCONFIRMED but TX_PARSE already proved settlement
        if (raw.executionResult == ExecutionResult.STUCK_UNCONFIRMED) {
            if (LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.tradeId, sig = null) ||
                LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.mint, sig = null)
            ) {
                CanonicalLearningCounters.rejectedBadLabels.incrementAndGet()
                return null
            }
        }
        return raw
    }
}

// ─────────────────────────────────────────────────────────────────────
// Counters (operator spec item 3)
// ─────────────────────────────────────────────────────────────────────

object CanonicalLearningCounters {
    val canonicalOutcomesTotal = AtomicLong(0)
    val liveOutcomesTotal = AtomicLong(0)
    val paperOutcomesTotal = AtomicLong(0)
    val shadowOutcomesTotal = AtomicLong(0)
    val executedTradesTotal = AtomicLong(0)
    val failedExecutionsTotal = AtomicLong(0)
    val settledWins = AtomicLong(0)
    val settledLosses = AtomicLong(0)
    val openTrades = AtomicLong(0)
    val inconclusiveTrades = AtomicLong(0)
    val recoveredTrades = AtomicLong(0)
    val rejectedBadLabels = AtomicLong(0)

    fun snapshot(): Map<String, Long> = mapOf(
        "canonicalOutcomesTotal" to canonicalOutcomesTotal.get(),
        "liveOutcomesTotal" to liveOutcomesTotal.get(),
        "paperOutcomesTotal" to paperOutcomesTotal.get(),
        "shadowOutcomesTotal" to shadowOutcomesTotal.get(),
        "executedTradesTotal" to executedTradesTotal.get(),
        "failedExecutionsTotal" to failedExecutionsTotal.get(),
        "settledWins" to settledWins.get(),
        "settledLosses" to settledLosses.get(),
        "openTrades" to openTrades.get(),
        "inconclusiveTrades" to inconclusiveTrades.get(),
        "recoveredTrades" to recoveredTrades.get(),
        "rejectedBadLabels" to rejectedBadLabels.get(),
    )
}

// ─────────────────────────────────────────────────────────────────────
// Pub/Sub bus (operator spec item 3)
// ─────────────────────────────────────────────────────────────────────

object CanonicalOutcomeBus {
    private const val TAG = "CanonicalOutcomeBus"

    fun interface Subscriber {
        fun onOutcome(outcome: CanonicalTradeOutcome)
    }

    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    fun subscribe(s: Subscriber) {
        subscribers.add(s)
    }

    /** Publish a canonical outcome — runs through normalizer + counters + router. */
    fun publish(raw: CanonicalTradeOutcome) {
        val normalized = CanonicalOutcomeNormalizer.normalizeOutcomeBeforeLearning(raw) ?: return
        bumpCounters(normalized)
        // Route to layers — strategy vs execution separation.
        try { LayerEducationRouter.dispatch(normalized) } catch (_: Throwable) {}
        // Fan out to subscribers.
        for (s in subscribers) {
            try { s.onOutcome(normalized) } catch (t: Throwable) {
                ErrorLogger.debug(TAG, "subscriber threw: ${t.message?.take(80)}")
            }
        }
    }

    private fun bumpCounters(o: CanonicalTradeOutcome) {
        CanonicalLearningCounters.canonicalOutcomesTotal.incrementAndGet()
        when (o.environment) {
            TradeEnvironment.LIVE -> CanonicalLearningCounters.liveOutcomesTotal.incrementAndGet()
            TradeEnvironment.PAPER -> CanonicalLearningCounters.paperOutcomesTotal.incrementAndGet()
            TradeEnvironment.SHADOW -> CanonicalLearningCounters.shadowOutcomesTotal.incrementAndGet()
        }
        when (o.executionResult) {
            ExecutionResult.EXECUTED, ExecutionResult.CLOSED_BY_TX_PARSE -> CanonicalLearningCounters.executedTradesTotal.incrementAndGet()
            ExecutionResult.RECOVERED_FROM_WALLET -> CanonicalLearningCounters.recoveredTrades.incrementAndGet()
            ExecutionResult.CLOSED_BY_WALLET_RECONCILE -> CanonicalLearningCounters.executedTradesTotal.incrementAndGet()
            ExecutionResult.FAILED_NO_TX, ExecutionResult.FAILED_REVERTED,
            ExecutionResult.FAILED_INSUFFICIENT_FUNDS, ExecutionResult.FAILED_ROUTE,
            ExecutionResult.PHANTOM_UNCONFIRMED, ExecutionResult.STUCK_UNCONFIRMED -> CanonicalLearningCounters.failedExecutionsTotal.incrementAndGet()
            ExecutionResult.UNKNOWN -> {}
        }
        when (o.result) {
            TradeResult.WIN -> CanonicalLearningCounters.settledWins.incrementAndGet()
            TradeResult.LOSS -> CanonicalLearningCounters.settledLosses.incrementAndGet()
            TradeResult.OPEN -> CanonicalLearningCounters.openTrades.incrementAndGet()
            TradeResult.INCONCLUSIVE_PENDING -> CanonicalLearningCounters.inconclusiveTrades.incrementAndGet()
            TradeResult.BREAKEVEN, TradeResult.CANCELLED, TradeResult.UNKNOWN -> {}
        }
    }

    /**
     * Bridge from the legacy Trade record (TradeHistoryStore.recordTrade) to
     * a normalized canonical outcome. Called from TradeHistoryStore for every
     * SELL — buys produce OPEN events that get superseded by the SELL event
     * later in the trade lifecycle.
     */
    fun publishFromLegacyTrade(trade: Trade) {
        if (!trade.side.equals("SELL", ignoreCase = true)) return
        val mode = CanonicalOutcomeNormalizer.normalizeMode(trade.tradingMode)
        val (assetClass, source) = inferAssetClassAndSource(mode)
        val env = if (trade.mode.equals("paper", true)) TradeEnvironment.PAPER else TradeEnvironment.LIVE
        val pnlPct = trade.pnlPct
        val result = when {
            pnlPct > 0.5 -> TradeResult.WIN
            pnlPct < -0.5 -> TradeResult.LOSS
            else -> TradeResult.BREAKEVEN
        }
        val executionResult = if (trade.sig.isNotBlank() || env == TradeEnvironment.PAPER)
            ExecutionResult.EXECUTED else ExecutionResult.UNKNOWN

        val outcome = CanonicalTradeOutcome(
            tradeId = "${trade.mint}_${trade.ts}",
            mint = trade.mint,
            symbol = "",  // legacy Trade lacks symbol; downstream consumers handle blanks
            assetClass = assetClass,
            mode = mode,
            source = source,
            environment = env,
            entryTimeMs = trade.ts - 1L,
            exitTimeMs = trade.ts,
            entryPrice = null,
            exitPrice = trade.price,
            entrySol = null,
            exitSol = trade.sol,
            realizedPnlSol = trade.netPnlSol.takeIf { it != 0.0 } ?: trade.pnlSol,
            realizedPnlPct = pnlPct,
            maxGainPct = null,
            maxDrawdownPct = null,
            holdSeconds = null,
            result = result,
            executionResult = executionResult,
            closeReason = trade.reason.ifBlank { null },
        )
        publish(outcome)
    }

    private fun inferAssetClassAndSource(mode: TradeMode): Pair<AssetClass, TradeSource> = when (mode) {
        TradeMode.SHITCOIN -> AssetClass.MEME to TradeSource.SHITCOIN
        TradeMode.BLUECHIP -> AssetClass.BLUECHIP to TradeSource.BLUECHIP
        TradeMode.EXPRESS -> AssetClass.MEME to TradeSource.EXPRESS
        TradeMode.MOONSHOT -> AssetClass.MEME to TradeSource.MOONSHOT
        TradeMode.MANIP -> AssetClass.MEME to TradeSource.MANIP
        TradeMode.TREASURY -> AssetClass.MEME to TradeSource.TREASURY
        TradeMode.ALTTRADER -> AssetClass.CRYPTO_ALT_SPOT to TradeSource.MARKETS
        TradeMode.CYCLIC -> AssetClass.MEME to TradeSource.CYCLIC
        TradeMode.COPY_TRADE -> AssetClass.MEME to TradeSource.COPYTRADE
        else -> AssetClass.UNKNOWN to TradeSource.UNKNOWN
    }
}

// ─────────────────────────────────────────────────────────────────────
// Layer Education Router (operator spec items 6 & 7)
// ─────────────────────────────────────────────────────────────────────

data class LayerEducationEvent(
    val layerName: String,
    val assetClass: AssetClass,
    val mode: TradeMode,
    val signalStrength: Double = 0.0,
    val signalDirection: String = "NEUTRAL",
    val confidenceAtDecision: Double = 0.0,
    val tradeResult: TradeResult,
    val realizedPnlPct: Double?,
    val realizedPnlSol: Double?,
    val maxGainPct: Double?,
    val maxDrawdownPct: Double?,
    val holdSeconds: Long?,
    val closeReason: String?,
    val executionResult: ExecutionResult,
    val environment: TradeEnvironment,
)

object LayerEducationRouter {
    /** Layers that learn ONLY from execution outcomes (route/cost/slippage). */
    val EXECUTION_LAYERS: Set<String> = setOf(
        "ExecutionCostPredictorAI",
        "UniversalWalletBridge",
        "RouteSelectorAI",
        "FeeRetryQueue",
        "SlippageGuard",
        "LiquidityExitPathAI",
    )

    /** Layers that learn from STRATEGY outcomes (entry / market prediction). */
    val STRATEGY_LAYERS: Set<String> = setOf(
        "EntryAI",
        "NarrativeDetectorAI",
        "MomentumPredictorAI",
        "BlueChipTraderAI",
        "ShitCoinTraderAI",
        "MoonshotTraderAI",
        "FluidLearningAI",
        "AdaptiveLearningEngine",
        "BehaviorLearning",
        "MetaCognitionAI",
    )

    fun dispatch(outcome: CanonicalTradeOutcome) {
        val isExecutionFailure = when (outcome.executionResult) {
            ExecutionResult.FAILED_NO_TX,
            ExecutionResult.FAILED_REVERTED,
            ExecutionResult.FAILED_INSUFFICIENT_FUNDS,
            ExecutionResult.FAILED_ROUTE,
            ExecutionResult.PHANTOM_UNCONFIRMED,
            ExecutionResult.STUCK_UNCONFIRMED -> true
            else -> false
        }
        // The strategy layers are NEVER punished for execution failures (operator spec item 7).
        val targetLayers = if (isExecutionFailure) EXECUTION_LAYERS else (STRATEGY_LAYERS + EXECUTION_LAYERS)
        for (layer in targetLayers) {
            // Only educate layers that actually voted at entry/exit.
            // If we don't have vote snapshots (legacy bridge case), educate
            // the canonical set anyway so existing learning systems still see
            // the event — they have their own internal "did I vote?" guards.
            val voted = outcome.layerVotesAtEntry.containsKey(layer) ||
                        outcome.layerVotesAtExit.containsKey(layer)
            if (outcome.layerVotesAtEntry.isNotEmpty() && !voted) continue
            // The hooks below intentionally do nothing in this push — consumer
            // migration is the next commit. The router is wired and the events
            // are well-formed; once each layer's update() is migrated to
            // accept LayerEducationEvent, just register them here.
            // Diagnostic-only emit:
            ErrorLogger.debug(
                "LayerEducationRouter",
                "→ $layer | result=${outcome.result} | exec=${outcome.executionResult} | env=${outcome.environment} | mode=${outcome.mode}"
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Layer Readiness scaffold (operator spec items 5 & 14)
// ─────────────────────────────────────────────────────────────────────

object LayerReadinessRegistry {
    private data class State(
        var settledOutcomes: Long = 0L,
        var positiveEvSamples: Long = 0L,
        var lastEducationMs: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun recordEducation(layer: String, settledDelta: Long, positiveEvDelta: Long) {
        val s = states.getOrPut(layer) { State() }
        s.settledOutcomes += settledDelta
        s.positiveEvSamples += positiveEvDelta
        s.lastEducationMs = System.currentTimeMillis()
    }

    fun readinessOf(layer: String): LayerReadiness {
        val s = states[layer] ?: return LayerReadiness.DISCONNECTED
        val n = s.settledOutcomes
        val ev = s.positiveEvSamples - (n - s.positiveEvSamples)  // wins - losses
        return when {
            n == 0L -> LayerReadiness.RECEIVING_SIGNALS
            n in 1..19 -> LayerReadiness.LEARNING_ONLY
            n in 20..49 -> LayerReadiness.PAPER_ELIGIBLE
            n in 50..99 -> LayerReadiness.LIVE_ELIGIBLE
            n >= 100 && ev > 0 -> LayerReadiness.TRUSTED
            n >= 50 && ev < 0 -> LayerReadiness.DEGRADED
            else -> LayerReadiness.LIVE_ELIGIBLE
        }
    }

    /**
     * Untested layers must vote neutral (operator spec item 5).
     * Returns a LayerVoteSnapshot that produces zero score/conf modification
     * and no veto when the layer's readiness is below PAPER_ELIGIBLE.
     */
    fun neutralVote() = LayerVoteSnapshot(score = 0.0, confidence = 0.0, direction = "NEUTRAL", veto = false)

    fun snapshot(): Map<String, LayerReadiness> =
        states.keys.associateWith { readinessOf(it) }
}

// ─────────────────────────────────────────────────────────────────────
// Seeding-phase confidence/size cap (operator spec item 8)
// ─────────────────────────────────────────────────────────────────────

object SeedingPhaseGuard {
    /**
     * Operator's screenshot showed FluidLearning during SEEDING phase reporting
     * "Conf Boost: +2500%" which is either a formatting bug (25.00 displayed
     * as 2500%) or an actually catastrophic boost. This guard caps any conf
     * boost during SEEDING to +5% and any size mult to 0.60x — and offers a
     * formatPct helper that the UI can use to render percentages safely.
     */
    fun capConfBoostForPhase(rawBoost: Double, phase: String): Double {
        return when (phase.uppercase()) {
            "SEEDING" -> rawBoost.coerceIn(-5.0, 5.0)      // +/- 5%
            "LEARNING" -> rawBoost.coerceIn(-15.0, 15.0)   // +/- 15%
            else -> rawBoost
        }
    }

    fun capSizeMultForPhase(rawMult: Double, phase: String): Double {
        return when (phase.uppercase()) {
            "SEEDING" -> rawMult.coerceIn(0.10, 0.60)
            "LEARNING" -> rawMult.coerceIn(0.10, 1.00)
            else -> rawMult
        }
    }

    /**
     * Render a "raw" percentage value safely. If the value looks like it's
     * already in 0..100 range, format it as-is. If it looks like a fraction
     * (0..1) multiply by 100. Anything beyond 1000 is clamped to "+999%" /
     * "-999%" so the UI never shows the +2500% bug again.
     */
    fun formatPct(raw: Double): String {
        val pct = if (kotlin.math.abs(raw) <= 1.0) raw * 100.0 else raw
        val capped = pct.coerceIn(-999.0, 999.0)
        return "%+.1f%%".format(capped)
    }
}
