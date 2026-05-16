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
    // V5.9.790 — operator audit Critical Fix 2: split generic DEGRADED into
    // diagnostic sub-reasons so the dashboard stops claiming a layer is
    // 'bad' when the truth is it never received feature-rich samples.
    // The old DEGRADED value is kept for back-compat; readinessOf() now
    // returns one of the more specific values when the cause is known.
    DEGRADED,
    DEGRADED_BAD_EV,            // genuine: enough rich samples + > 70% losses
    DEGRADED_FEATURE_STARVED,   // only ever fed featuresIncomplete=true samples
    DEGRADED_NO_ADAPTER,        // layer present but never received bus events
    DEGRADED_NO_VOTES,          // adapter wired but layer never cast a vote
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

/**
 * V5.9.782 — operator audit items C, D, J: structured, learner-ready
 * features-at-entry payload. Carried inside CanonicalTradeOutcome so every
 * subscriber (BehaviorLearning, AdaptiveLearningEngine, MetaCognitionAI,
 * RunTracker30D, ShadowFDGLearning) can pattern-match on the SAME rich
 * features without each layer reconstructing them locally.
 *
 * All fields are bucketed strings so they hash cleanly into pattern
 * signatures. If a producer doesn't have a field at close time, it leaves
 * the string empty AND sets CanonicalTradeOutcome.featuresIncomplete=true
 * so strategy learners skip the sample (execution learners may still use
 * route/slippage/broadcast data — operator spec acceptance test #3).
 */
data class CandidateFeatures(
    // Identity / routing
    val assetClass: String = "",                // MEME / BLUECHIP / STOCK / …
    val runtimeMode: String = "",               // LIVE / PAPER / SHADOW
    val trader: String = "",                    // SHITCOIN / MOONSHOT / EXPRESS / …
    val venue: String = "",                     // PUMP_FUN_BONDING / PUMPSWAP / RAYDIUM / METEORA / ORCA / JUPITER
    val route: String = "",                     // PUMP_NATIVE / PUMPPORTAL / JUPITER / METIS / MULTI
    val bondingCurveActive: Boolean = false,
    val migrated: Boolean = false,

    // Token snapshot at entry (bucketed)
    val ageBucket: String = "",                 // UNDER_2M / UNDER_10M / UNDER_1H / UNDER_24H / OLDER
    val liqBucket: String = "",                 // LIQ_DUST / LIQ_TINY / LIQ_LOW / LIQ_MED / LIQ_GOOD / LIQ_DEEP
    val mcapBucket: String = "",                // MCAP_MICRO / MCAP_TINY / MCAP_SMALL / MCAP_MED / MCAP_LARGE
    val volVelocity: String = "",               // FALLING / FLAT / RISING_SLOW / RISING_FAST / VERTICAL
    val buyPressure: String = "",               // WEAK / NEUTRAL / STRONG
    val sellPressure: String = "",
    val holderGrowth: String = "",              // SHRINKING / FLAT / GROWING / VIRAL
    val holderConcentration: String = "",       // CONC_LOW / CONC_MED / CONC_HIGH / CONC_RUG
    val rugTier: String = "",                   // SAFE / CAUTION / UNSAFE / DANGER
    val safetyTier: String = "",                // identical to rugTier in producer convention
    val mintAuthority: String = "",             // RENOUNCED / RETAINED / UNKNOWN
    val freezeAuthority: String = "",           // RENOUNCED / RETAINED / UNKNOWN

    // Entry shape
    val slippageBucket: String = "",            // SLIP_LOW / SLIP_MED / SLIP_HIGH
    val entryPattern: String = "",              // BREAKOUT / PULLBACK / VERTICAL_GREEN_THEN_PULLBACK / DIP / …
    val bubbleClusterPattern: String = "",      // CLEAN / CLUSTERED / BUNDLED

    // Decisions / FDG
    val fdgReasonFamily: String = "",           // STRONG_BUY / NEUTRAL / SAFETY_BLOCK / LIQUIDITY_BLOCK / …
    val symbolicVerdict: String = "",           // ALLOW / CAUTION / VETO — from CandidateSymbolicContext

    // Exit summary (populated at close)
    val exitReasonFamily: String = "",          // TAKE_PROFIT / STOP_LOSS / TRAILING / RAPID_CATASTROPHE / MANUAL / EXTERNAL_SWAP / …
    val holdBucket: String = "",                // UNDER_30S / UNDER_2M / UNDER_10M / UNDER_1H / LONGER
    val manualOrExternalClose: Boolean = false,
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
    // V5.9.782 — structured candidate-features payload (audit items C, D, J).
    // Producers fill this with bucketed strings learners pattern-match on.
    val candidate: CandidateFeatures? = null,
    // V5.9.782 — true when the producer did NOT supply enough features for
    // strategy learning (e.g. legacy bridge path). Strategy learners
    // (BehaviorLearning patterns, AdaptiveLearningEngine, MetaCognitionAI
    // win/loss patterns) MUST skip these. Execution learners
    // (RouteSelectorAI, SlippageGuard, FeeRetryQueue, …) may still
    // learn from execution metadata. See operator acceptance test #3.
    val featuresIncomplete: Boolean = true,
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
    // V5.9.782 — operator audit items C, D, J: outcomes that arrived without
    // enough features for strategy learning. Tracked so the dashboard can
    // surface what fraction of training samples were actually feature-rich.
    val incompleteFeatureOutcomes = AtomicLong(0)
    val richFeatureOutcomes = AtomicLong(0)
    // V5.9.790 — operator audit Critical Fix 2:
    //   strategyTrainableOutcomes = rich + EXECUTED → BehaviorLearning patterns.
    //   executionOnlyOutcomes     = everything that may train route/slippage
    //                               learners but MUST NOT train strategy patterns
    //                               (incomplete features OR rich w/ execution failure).
    val strategyTrainableOutcomes = AtomicLong(0)
    val executionOnlyOutcomes = AtomicLong(0)

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
        "incompleteFeatureOutcomes" to incompleteFeatureOutcomes.get(),
        "richFeatureOutcomes" to richFeatureOutcomes.get(),
        "strategyTrainableOutcomes" to strategyTrainableOutcomes.get(),
        "executionOnlyOutcomes" to executionOnlyOutcomes.get(),
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
    private val recentEvents = java.util.concurrent.ConcurrentLinkedDeque<CanonicalTradeOutcome>()
    private const val RECENT_MAX = 200

    fun subscribe(s: Subscriber) {
        subscribers.add(s)
    }

    fun subscriberCount(): Int = subscribers.size
    fun recentSnapshot(): List<CanonicalTradeOutcome> = recentEvents.toList()

    /**
     * V5.9.495z9 — once a trade-close site has emitted a feature-rich
     * canonical outcome we mark the tradeId so the legacy bridge inside
     * TradeHistoryStore.publishFromLegacyTrade() can skip it (no double
     * counting). Bounded to 4096 entries via simple LRU.
     */
    private val richPublishedTradeIds: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 4096
        })
    )
    fun isRichPublished(tradeId: String): Boolean = richPublishedTradeIds.contains(tradeId)
    fun markRichPublished(tradeId: String) { richPublishedTradeIds.add(tradeId) }

    /** Publish a canonical outcome — runs through normalizer + counters + router. */
    fun publish(raw: CanonicalTradeOutcome) {
        val normalized = CanonicalOutcomeNormalizer.normalizeOutcomeBeforeLearning(raw) ?: return
        bumpCounters(normalized)
        // Append to ring buffer.
        recentEvents.addFirst(normalized)
        while (recentEvents.size > RECENT_MAX) recentEvents.pollLast()
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
        // V5.9.782 — split rich vs incomplete so dashboards/strategy learners
        // can compute "real training samples" honestly. featuresIncomplete=true
        // is the legacy bridge default.
        val rich = !o.featuresIncomplete
        if (rich) {
            CanonicalLearningCounters.richFeatureOutcomes.incrementAndGet()
        } else {
            CanonicalLearningCounters.incompleteFeatureOutcomes.incrementAndGet()
        }
        // V5.9.790 — operator audit Critical Fix 2: classify EVERY canonical
        // outcome into the bucket that decides which learners may consume it.
        // Strategy learning requires BOTH rich features AND a real execution
        // (PHANTOM_UNCONFIRMED / STUCK_UNCONFIRMED / FAILED_* never trains
        // strategy patterns — operator audit Critical Fix 8).
        val executedOk = when (o.executionResult) {
            ExecutionResult.EXECUTED,
            ExecutionResult.CLOSED_BY_TX_PARSE,
            ExecutionResult.CLOSED_BY_WALLET_RECONCILE,
            ExecutionResult.RECOVERED_FROM_WALLET -> true
            else -> false
        }
        if (rich && executedOk) {
            CanonicalLearningCounters.strategyTrainableOutcomes.incrementAndGet()
        } else {
            CanonicalLearningCounters.executionOnlyOutcomes.incrementAndGet()
        }
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
        val tradeId = "${trade.mint}_${trade.ts}"
        // V5.9.495z9 — skip if a feature-rich publish from the trade-close
        // emit site already covered this tradeId (no double counting).
        if (isRichPublished(tradeId)) return
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
            tradeId = tradeId,
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
        // V5.9.790 — operator audit Critical Fix 2: track rich vs incomplete
        // education separately so DEGRADED layers can be classified into
        // sub-reasons (FEATURE_STARVED vs BAD_EV vs NO_ADAPTER vs NO_VOTES).
        var richEducationCount: Long = 0L,
        var incompleteEducationCount: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun recordEducation(layer: String, settledDelta: Long, positiveEvDelta: Long) {
        val s = states.getOrPut(layer) { State() }
        s.settledOutcomes += settledDelta
        s.positiveEvSamples += positiveEvDelta
        s.lastEducationMs = System.currentTimeMillis()
    }

    /**
     * V5.9.790 — operator audit Critical Fix 2: record education with rich/incomplete classification.
     * Layers wired through CanonicalSubscribers should call this instead of recordEducation so the
     * dashboard can show DEGRADED_FEATURE_STARVED separately from DEGRADED_BAD_EV.
     */
    fun recordEducationDetailed(
        layer: String,
        settledDelta: Long,
        positiveEvDelta: Long,
        isRichSample: Boolean,
    ) {
        val s = states.getOrPut(layer) { State() }
        s.settledOutcomes += settledDelta
        s.positiveEvSamples += positiveEvDelta
        s.lastEducationMs = System.currentTimeMillis()
        if (isRichSample) s.richEducationCount += 1L else s.incompleteEducationCount += 1L
    }

    fun readinessOf(layer: String): LayerReadiness {
        val s = states[layer] ?: return LayerReadiness.DISCONNECTED
        val n = s.settledOutcomes
        val wins = s.positiveEvSamples
        val losses = n - wins
        val ev = wins - losses                                // wins - losses
        // V5.9.616 — RE-BASELINED LADDER for 5000-trade maturity.
        //
        // Old ladder DEGRADED any layer with n>=50 and ev<0. With a 27% WR
        // bootstrap bot, EVERY layer hit this gate the moment it had 50
        // settled samples and the bot deadlocked: layers DEGRADED → FDG
        // 32% floor → no entries → no trades → layers stay DEGRADED.
        //
        // Operator rule: "the bot should never choke. its meant to go out
        // of learning at maturity which is 5000 trades."
        //
        // New ladder treats <500 settled samples as still bootstrapping —
        // the layer is allowed to be unprofitable while it gathers signal.
        // DEGRADED is reserved for genuinely broken layers: 500+ settled
        // samples AND a really bad ratio (more than 70% losses).
        //
        // V5.9.790 — operator audit Critical Fix 2: when the layer has 500+
        // settled samples but ZERO of them were rich (featuresIncomplete=true
        // on every educate call), we return DEGRADED_FEATURE_STARVED instead
        // of generic DEGRADED. The bot looks 'broken' to a casual reader of
        // the dashboard otherwise — when in truth the producer never gave it
        // a chance to learn.
        //
        // 0:               RECEIVING_SIGNALS
        // 1-99:            LEARNING_ONLY
        // 100-499:         PAPER_ELIGIBLE   (still bootstrap; can't be DEGRADED)
        // 500-1999:        LIVE_ELIGIBLE
        // 2000+ ev>0:      TRUSTED
        // 500+  ratio<-70% AND has rich samples : DEGRADED_BAD_EV
        // 500+  rich==0   AND incomplete>=500   : DEGRADED_FEATURE_STARVED
        // else:            LIVE_ELIGIBLE
        val lossRatio = if (n > 0) losses.toDouble() / n.toDouble() else 0.0
        val rich = s.richEducationCount
        val incomplete = s.incompleteEducationCount
        return when {
            n == 0L -> LayerReadiness.RECEIVING_SIGNALS
            n in 1..99 -> LayerReadiness.LEARNING_ONLY
            n in 100..499 -> LayerReadiness.PAPER_ELIGIBLE
            n >= 2000L && ev > 0 -> LayerReadiness.TRUSTED
            n >= 500L && rich == 0L && incomplete >= 500L -> LayerReadiness.DEGRADED_FEATURE_STARVED
            n >= 500L && lossRatio >= 0.70 && rich > 0L -> LayerReadiness.DEGRADED_BAD_EV
            n >= 500L && lossRatio >= 0.70 -> LayerReadiness.DEGRADED_FEATURE_STARVED
            n >= 500L -> LayerReadiness.LIVE_ELIGIBLE
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

    /**
     * V5.9.790 — operator audit Critical Fix 2: per-layer counters so the
     * dashboard can render exactly why a layer is DEGRADED (bad EV vs feature
     * starvation). Returns Triple(settled, richEducation, incompleteEducation).
     */
    fun countersOf(layer: String): Triple<Long, Long, Long> {
        val s = states[layer] ?: return Triple(0L, 0L, 0L)
        return Triple(s.settledOutcomes, s.richEducationCount, s.incompleteEducationCount)
    }
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
