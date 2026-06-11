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
    QUALITY,        // V5.9.1343 — top live winner (n=152 WR36%); was collapsing to UNKNOWN
    LAB,            // V5.9.1343 — LLM Lab lane (n=36 WR75%); was collapsing to UNKNOWN
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
    // V5.9.793 — operator audit Item 5: marks outcomes where the entry/exit
    // price was sourced ONLY from a pump.fun bonding-curve estimate (no
    // confirmed Jupiter/Raydium/DexScreener executable pool). The
    // production WR aggregator excludes these so paper BC sims can't
    // inflate the real-money expectancy signal.
    val bcSimOnly: Boolean = false,
    // V5.9.1161 — canonical accounting contract. Partials are first-class
    // outcomes and SHOULD train when accounting is valid; invalid accounting
    // stays visible in the canonical stream but cannot train strategy/EV.
    val isPartial: Boolean = false,
    val partialIndex: Int = 0,
    val parentPositionId: String? = null,
    val costBasisSol: Double? = null,
    val proceedsSol: Double? = null,
    val feesSol: Double = 0.0,
    val isTrainable: Boolean = true,
    val invalidReason: String? = null,
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
            k.contains("QUALITY") || k == "QUAL" -> TradeMode.QUALITY
            k.contains("LAB") || k.contains("LLM") -> TradeMode.LAB
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
    // V5.9.1514 — P0 FIX 2: three-class outcome separation. An untrainable row
    // is not necessarily a BAD LABEL. Two populations were conflated into
    // rejectedBadLabels:
    //   • EXECUTION-ONLY: trade really happened but lacks strategy context
    //     (no lane, or a recoverable-but-missing accounting field). May still
    //     train route/slippage learners; MUST NOT inflate the "dirty data"
    //     alarm. → executionOnlyOutcomes.
    //   • BAD-LABEL: contradictory / stale / fabricated rows (invalid partial
    //     accounting, negative proceeds, phantom-after-terminal, unverifiable
    //     extreme). Genuinely corrupt. → rejectedBadLabels.
    // Both stay isTrainable=false (never tune strategy); only the corrupt class
    // trips rejectedBadLabels.
    private val EXECUTION_ONLY_REASONS = setOf(
        "UNKNOWN_LANE", "MISSING_EXIT_PRICE", "MISSING_COST_BASIS", "WIN_WITHOUT_EXIT"
    )
    private fun invalid(raw: CanonicalTradeOutcome, reason: String): CanonicalTradeOutcome {
        // Only the CORRUPT class trips rejectedBadLabels. Execution-only rows
        // (no lane / recoverable-missing accounting) are left to fall through to
        // bumpCounters(), which already routes every isTrainable=false outcome to
        // executionOnlyOutcomes — incrementing it here too would double-count.
        if (reason !in EXECUTION_ONLY_REASONS) {
            CanonicalLearningCounters.rejectedBadLabels.incrementAndGet()
        }
        return raw.copy(isTrainable = false, invalidReason = reason)
    }

    fun normalizeOutcomeBeforeLearning(raw0: CanonicalTradeOutcome): CanonicalTradeOutcome? {
        // V5.9.1428 — RECONSTRUCT-BEFORE-REJECT. Operator: "fix it correctly".
        // A genuinely EXECUTED close must NEVER be dropped from learning just
        // because one redundant field (exitPrice) failed to propagate from the
        // exit path. exitPrice is recoverable from accounting we already hold:
        //     exitPrice = entryPrice * (1 + realizedPnlPct/100)        [exact]
        //  or exitPrice = entryPrice * (proceedsSol / costBasisSol)    [accounting]
        // We only reject a row that is TRULY unverifiable (not executed, or no
        // entryPrice AND no pct AND no proceeds/cost to derive from). This closes
        // the MISSING_EXIT_PRICE + EXTREME_OUTCOME_UNVERIFIED leaks that were
        // silently eating real paper closes and (now that let-run is live) would
        // eat genuine >500% runners whose exitPrice raced.
        val raw = run {
            val isExecuted = raw0.executionResult == ExecutionResult.EXECUTED ||
                raw0.executionResult == ExecutionResult.CLOSED_BY_TX_PARSE ||
                raw0.executionResult == ExecutionResult.CLOSED_BY_WALLET_RECONCILE ||
                raw0.environment == TradeEnvironment.PAPER
            if ((raw0.exitPrice ?: 0.0) <= 0.0 && isExecuted) {
                val ep = raw0.entryPrice ?: 0.0
                val cost = raw0.costBasisSol ?: raw0.entrySol ?: 0.0
                val proceeds = raw0.proceedsSol ?: raw0.exitSol
                // CONSISTENCY GUARD — only reconstruct when the SOL accounting
                // is INTERNALLY COHERENT. A row that claims a non-zero realized
                // P&L while proceedsSol is explicitly 0 is CONTRADICTORY (you
                // cannot realize +0.05 SOL with 0 proceeds). Such rows are bad
                // accounting, not propagation races — they must fall through to
                // the PARTIAL_SELL_INVALID_ACCOUNTING / MISSING_EXIT_PRICE
                // rejection, NOT be reconstructed (reconstructing would train on
                // a fabricated price for a trade whose proceeds say it never
                // settled). Guards the ExecutionAuthorityInvariant.
                val claimsRealizedPnl = kotlin.math.abs(raw0.realizedPnlSol ?: 0.0) > 0.0000001 ||
                    kotlin.math.abs(raw0.realizedPnlPct ?: 0.0) > 0.0000001
                val proceedsExplicitlyZero = (raw0.proceedsSol != null && raw0.proceedsSol <= 0.0) ||
                    (raw0.proceedsSol == null && (raw0.exitSol ?: 0.0) <= 0.0)
                val accountingContradicts = claimsRealizedPnl && proceedsExplicitlyZero
                val reconstructed: Double? = if (accountingContradicts) null else when {
                    // Accounting-derived FIRST (it is the source of truth and
                    // proves the trade actually settled). Only when proceeds AND
                    // cost are real do we trust the pct path.
                    ep > 0.0 && cost > 0.0 && proceeds != null && proceeds > 0.0 ->
                        ep * (proceeds / cost)
                    ep > 0.0 && raw0.realizedPnlPct != null && cost > 0.0 && proceeds != null && proceeds > 0.0 ->
                        ep * (1.0 + (raw0.realizedPnlPct / 100.0))
                    else -> null
                }?.takeIf { it.isFinite() && it > 0.0 }
                if (reconstructed != null) {
                    try {
                        ForensicLogger.lifecycle(
                            "CANON_EXIT_PRICE_RECONSTRUCTED",
                            "mint=${raw0.mint.take(10)} mode=${raw0.mode} pct=${"%.1f".format(raw0.realizedPnlPct ?: 0.0)} px=${"%.8f".format(reconstructed)}"
                        )
                    } catch (_: Throwable) {}
                    raw0.copy(exitPrice = reconstructed)
                } else raw0
            } else raw0
        }

        // Rule: UNKNOWN strategy labels remain audit-visible but cannot train.
        if (raw.mode == TradeMode.UNKNOWN || raw.source == TradeSource.UNKNOWN) {
            return invalid(raw, "UNKNOWN_LANE")
        }
        // Rule: BUY/SELL accounting sanity. Partials are allowed and trainable
        // only if they have real exit price/proceeds/cost basis.
        val isExitLike = raw.result == TradeResult.WIN || raw.result == TradeResult.LOSS || raw.result == TradeResult.BREAKEVEN
        if (isExitLike) {
            val cost = raw.costBasisSol ?: raw.entrySol ?: 0.0
            val proceeds = raw.proceedsSol ?: ((raw.exitSol ?: 0.0) + (raw.realizedPnlSol ?: 0.0))
            // exitPrice may still be missing if it was genuinely unrecoverable
            // above (no entryPrice AND no pct AND no proceeds/cost). In that
            // case the trade is unverifiable and must not train.
            if ((raw.exitPrice ?: 0.0) <= 0.0) return invalid(raw, "MISSING_EXIT_PRICE")
            if (cost <= 0.0) return invalid(raw, "MISSING_COST_BASIS")
            if (proceeds < -0.0000001) return invalid(raw, "NEGATIVE_PROCEEDS")
            if (raw.isPartial && proceeds <= 0.0 && kotlin.math.abs(raw.realizedPnlSol ?: 0.0) > 0.0000001) {
                return invalid(raw, "PARTIAL_SELL_INVALID_ACCOUNTING")
            }
            // EXTREME_OUTCOME check: now that exitPrice is reconstructed/verified
            // above, an extreme pct backed by a real exitPrice + cost is a REAL
            // runner and trains. Only reject if STILL unverifiable.
            val pct = raw.realizedPnlPct ?: 0.0
            if ((pct > 500.0 || pct < -80.0) && ((raw.exitPrice ?: 0.0) <= 0.0 || cost <= 0.0)) {
                return invalid(raw, "EXTREME_OUTCOME_UNVERIFIED")
            }
        }
        // Rule: WIN with no exit → invalid
        if (raw.result == TradeResult.WIN && raw.exitTimeMs == null) {
            return invalid(raw, "WIN_WITHOUT_EXIT")
        }
        // Rule: LOSS with no execution → invalid (execution failure, not strategy loss)
        if (raw.result == TradeResult.LOSS && raw.executionResult != ExecutionResult.EXECUTED &&
            raw.executionResult != ExecutionResult.CLOSED_BY_TX_PARSE &&
            raw.executionResult != ExecutionResult.CLOSED_BY_WALLET_RECONCILE
        ) {
            return invalid(raw, "LOSS_WITHOUT_EXECUTION")
        }
        // Rule: PHANTOM_UNCONFIRMED but a TERMINAL_GOOD on the same mint key
        // already exists in LiveTradeLogStore → execution actually landed.
        if (raw.executionResult == ExecutionResult.PHANTOM_UNCONFIRMED) {
            if (LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.tradeId, sig = null) ||
                LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.mint, sig = null)
            ) {
                return invalid(raw, "PHANTOM_AFTER_TERMINAL")  // do not train from this stale label
            }
        }
        // Rule: STUCK_UNCONFIRMED but TX_PARSE already proved settlement
        if (raw.executionResult == ExecutionResult.STUCK_UNCONFIRMED) {
            if (LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.tradeId, sig = null) ||
                LiveTradeLogStore.isTerminallyResolved(tradeKey = raw.mint, sig = null)
            ) {
                return invalid(raw, "STUCK_AFTER_TERMINAL")
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
    // V5.9.1517 — P1 FIX 5: environment-split WR. settledWins/Losses pool PAPER
    // and LIVE together, so the operator-facing win-rate was polluted by paper
    // churn (and paper dominates volume during bootstrap). Split them so true
    // LIVE WR and PAPER WR can be read independently. Persisted (doctrine: any
    // learnt/aggregated state must survive restart).
    val liveWins = AtomicLong(0)
    val liveLosses = AtomicLong(0)
    val paperWins = AtomicLong(0)
    val paperLosses = AtomicLong(0)
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
    // V5.9.793 — operator audit Item 5: count outcomes that closed against
    // a pump.fun bonding-curve sim (no real pool ever confirmed). These
    // are EXCLUDED from production WR but counted here so the operator
    // can verify the BC-sim path is being correctly isolated.
    val bcSimOnlyOutcomes = AtomicLong(0)

    fun snapshot(): Map<String, Long> = mapOf(
        "canonicalOutcomesTotal" to canonicalOutcomesTotal.get(),
        "liveOutcomesTotal" to liveOutcomesTotal.get(),
        "paperOutcomesTotal" to paperOutcomesTotal.get(),
        "shadowOutcomesTotal" to shadowOutcomesTotal.get(),
        "executedTradesTotal" to executedTradesTotal.get(),
        "failedExecutionsTotal" to failedExecutionsTotal.get(),
        "settledWins" to settledWins.get(),
        "settledLosses" to settledLosses.get(),
        "liveWins" to liveWins.get(),
        "liveLosses" to liveLosses.get(),
        "paperWins" to paperWins.get(),
        "paperLosses" to paperLosses.get(),
        "openTrades" to openTrades.get(),
        "inconclusiveTrades" to inconclusiveTrades.get(),
        "recoveredTrades" to recoveredTrades.get(),
        "rejectedBadLabels" to rejectedBadLabels.get(),
        "incompleteFeatureOutcomes" to incompleteFeatureOutcomes.get(),
        "richFeatureOutcomes" to richFeatureOutcomes.get(),
        "strategyTrainableOutcomes" to strategyTrainableOutcomes.get(),
        "executionOnlyOutcomes" to executionOnlyOutcomes.get(),
        "bcSimOnlyOutcomes" to bcSimOnlyOutcomes.get(),
    )

    /**
     * V5.9.1353 — TRUE RESET. Zeroes every persisted counter in memory. Called
     * by LearningPersistence.resetAll() (which also wipes the disk kv table) so
     * "Reset Learning" actually returns the canonical totals to 0 instead of
     * leaving 1755 to be reloaded on next boot. Single source of truth: mirrors
     * the exportState/importState field list exactly.
     */
    fun reset() {
        canonicalOutcomesTotal.set(0); liveOutcomesTotal.set(0); paperOutcomesTotal.set(0)
        shadowOutcomesTotal.set(0); executedTradesTotal.set(0); failedExecutionsTotal.set(0)
        settledWins.set(0); settledLosses.set(0); openTrades.set(0); inconclusiveTrades.set(0)
        liveWins.set(0); liveLosses.set(0); paperWins.set(0); paperLosses.set(0)
        recoveredTrades.set(0); rejectedBadLabels.set(0); incompleteFeatureOutcomes.set(0)
        richFeatureOutcomes.set(0); strategyTrainableOutcomes.set(0); executionOnlyOutcomes.set(0)
        bcSimOnlyOutcomes.set(0)
    }

    // V5.9.949 — persistence hooks. Counters drive WR + dashboard health
    // and represent the LIFETIME total of every settled outcome the bot
    // has ever produced. Wiping them on restart was equivalent to giving
    // the bot retrograde amnesia. Wired into LearningPersistence.
    fun exportState(): String {
        return org.json.JSONObject().apply {
            put("canonicalOutcomesTotal", canonicalOutcomesTotal.get())
            put("liveOutcomesTotal", liveOutcomesTotal.get())
            put("paperOutcomesTotal", paperOutcomesTotal.get())
            put("shadowOutcomesTotal", shadowOutcomesTotal.get())
            put("executedTradesTotal", executedTradesTotal.get())
            put("failedExecutionsTotal", failedExecutionsTotal.get())
            put("settledWins", settledWins.get())
            put("settledLosses", settledLosses.get())
            put("liveWins", liveWins.get())
            put("liveLosses", liveLosses.get())
            put("paperWins", paperWins.get())
            put("paperLosses", paperLosses.get())
            put("openTrades", openTrades.get())
            put("inconclusiveTrades", inconclusiveTrades.get())
            put("recoveredTrades", recoveredTrades.get())
            put("rejectedBadLabels", rejectedBadLabels.get())
            put("incompleteFeatureOutcomes", incompleteFeatureOutcomes.get())
            put("richFeatureOutcomes", richFeatureOutcomes.get())
            put("strategyTrainableOutcomes", strategyTrainableOutcomes.get())
            put("executionOnlyOutcomes", executionOnlyOutcomes.get())
            put("bcSimOnlyOutcomes", bcSimOnlyOutcomes.get())
        }.toString()
    }

    fun importState(json: String) {
        try {
            val o = org.json.JSONObject(json)
            canonicalOutcomesTotal.set(o.optLong("canonicalOutcomesTotal", 0L))
            liveOutcomesTotal.set(o.optLong("liveOutcomesTotal", 0L))
            paperOutcomesTotal.set(o.optLong("paperOutcomesTotal", 0L))
            shadowOutcomesTotal.set(o.optLong("shadowOutcomesTotal", 0L))
            executedTradesTotal.set(o.optLong("executedTradesTotal", 0L))
            failedExecutionsTotal.set(o.optLong("failedExecutionsTotal", 0L))
            settledWins.set(o.optLong("settledWins", 0L))
            settledLosses.set(o.optLong("settledLosses", 0L))
            liveWins.set(o.optLong("liveWins", 0L))
            liveLosses.set(o.optLong("liveLosses", 0L))
            paperWins.set(o.optLong("paperWins", 0L))
            paperLosses.set(o.optLong("paperLosses", 0L))
            // openTrades intentionally NOT restored — must reconcile from on-chain truth.
            inconclusiveTrades.set(o.optLong("inconclusiveTrades", 0L))
            recoveredTrades.set(o.optLong("recoveredTrades", 0L))
            rejectedBadLabels.set(o.optLong("rejectedBadLabels", 0L))
            incompleteFeatureOutcomes.set(o.optLong("incompleteFeatureOutcomes", 0L))
            richFeatureOutcomes.set(o.optLong("richFeatureOutcomes", 0L))
            strategyTrainableOutcomes.set(o.optLong("strategyTrainableOutcomes", 0L))
            executionOnlyOutcomes.set(o.optLong("executionOnlyOutcomes", 0L))
            bcSimOnlyOutcomes.set(o.optLong("bcSimOnlyOutcomes", 0L))
        } catch (_: Throwable) { /* fail-open */ }
    }
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

    /** V5.9.1353 — TRUE RESET: drop the recent-outcome history ring. Subscribers
     *  are left intact (they are wiring, not learned state). */
    fun reset() { recentEvents.clear() }

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
        // V5.9.791 — operator audit Item 1 + 2: PositionExitArbiter enforces
        // ONE terminal SELL per (canonicalMint, entryTimeMs). Duplicate
        // exit cascades (CASHGEN_STOP_LOSS + STRICT_SL + RAPID_CATASTROPHE_STOP
        // all firing on the same Position) collide here and the loser is
        // logged as EXIT_SUPPRESSED_DUPLICATE — never fanned out to learners,
        // never journalled. Only terminal SELLs are arbitrated; OPEN /
        // INCONCLUSIVE_PENDING / BREAKEVEN-with-no-exit-time pass through.
        // Partial sells (reason starts with "partial") explicitly do NOT
        // lock the slot — they're counted separately and the eventual full
        // close is the actual terminal event.
        val isTerminalResult = normalized.result == TradeResult.WIN || normalized.result == TradeResult.LOSS
        val reasonLower = normalized.closeReason?.lowercase().orEmpty()
        // V5.9.800 — operator audit FIX: broaden partial detection to include
        // fluid profit-lock / capital-recovery / WR-recovery partials whose
        // reason strings don't start with 'partial'. Without this the FIRST
        // profit-lock or capital-recovery sell would lock the positionKey
        // at the bus level and SUPPRESS the subsequent actual terminal sell.
        val isPartial =
            reasonLower.startsWith("partial") ||
            reasonLower.contains("partial_") ||
            reasonLower.contains("partialsell") ||
            reasonLower.startsWith("profit_lock") ||
            reasonLower.startsWith("capital_recovery") ||
            reasonLower.startsWith("wr_recovery") ||
            reasonLower.contains("_partial_") ||
            reasonLower.contains("profit_take_partial") ||
            reasonLower.contains("scale_out")
        if (isTerminalResult && !isPartial) {
            val key = PositionExitArbiter.positionKey(normalized.mint, normalized.entryTimeMs)
            val verdict = PositionExitArbiter.arbitrate(
                positionKey = key,
                reason = normalized.closeReason ?: "UNKNOWN_TERMINAL",
                env = normalized.environment.name,
                sig = null,
            )
            if (verdict.decision == PositionExitArbiter.Decision.SUPPRESS) {
                return
            }
        } else if (isPartial) {
            PositionExitArbiter.recordPartial(normalized.mint)
        }
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

    /**
     * V5.9.791 — operator audit Item 1: bypass-arbiter publish. Used by the
     * meme primary close path (Executor.recordTrade → rich publish at line
     * ~1600) where Executor.recordTrade has ALREADY arbitrated the position
     * key — re-arbitrating in the bus would falsely SUPPRESS the legitimate
     * first publish for this terminal exit. Non-meme lanes / legacy bridge
     * callers stay on the standard publish() with full arbitration.
     */
    fun publishUnchecked(raw: CanonicalTradeOutcome) {
        val normalized = CanonicalOutcomeNormalizer.normalizeOutcomeBeforeLearning(raw) ?: return
        bumpCounters(normalized)
        recentEvents.addFirst(normalized)
        while (recentEvents.size > RECENT_MAX) recentEvents.pollLast()
        try { LayerEducationRouter.dispatch(normalized) } catch (_: Throwable) {}
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
        val rich = !o.featuresIncomplete && o.isTrainable
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
        if (o.isTrainable && rich && executedOk) {
            CanonicalLearningCounters.strategyTrainableOutcomes.incrementAndGet()
        } else {
            CanonicalLearningCounters.executionOnlyOutcomes.incrementAndGet()
        }
        // V5.9.793 — operator audit Item 5: BC-sim-only outcomes counted
        // separately. WR aggregators must subtract this from the strategy
        // sample size (production WR is real-pool only).
        if (o.bcSimOnly) {
            CanonicalLearningCounters.bcSimOnlyOutcomes.incrementAndGet()
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
            TradeResult.WIN -> if (o.isTrainable) {
                CanonicalLearningCounters.settledWins.incrementAndGet()
                when (o.environment) {
                    TradeEnvironment.LIVE -> CanonicalLearningCounters.liveWins.incrementAndGet()
                    TradeEnvironment.PAPER -> CanonicalLearningCounters.paperWins.incrementAndGet()
                    else -> {}
                }
            }
            TradeResult.LOSS -> if (o.isTrainable) {
                CanonicalLearningCounters.settledLosses.incrementAndGet()
                when (o.environment) {
                    TradeEnvironment.LIVE -> CanonicalLearningCounters.liveLosses.incrementAndGet()
                    TradeEnvironment.PAPER -> CanonicalLearningCounters.paperLosses.incrementAndGet()
                    else -> {}
                }
            }
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
        val isPartialSide = trade.side.equals("PARTIAL_SELL", ignoreCase = true)
        if (!trade.side.equals("SELL", ignoreCase = true) && !isPartialSide) return
        val tradeId = "${trade.mint}_${trade.ts}"
        // V5.9.495z9 — skip if a feature-rich publish from the trade-close
        // emit site already covered this tradeId (no double counting).
        if (isRichPublished(tradeId)) return
        // V5.9.1038 — operator V5.9.1037 triage: 64% of canonical outcomes
        // were still featuresIncomplete because some exit paths (notably
        // sweepUniversalExits + rapid-monitor closes) create Trade objects
        // with reason="MOONSHOT_STOP_LOSS" but blank tradingMode. The
        // V5.9.1035 lite-rich bridge requires a non-blank tradingMode to
        // build the rich CandidateFeatures. Infer the mode from the close
        // reason as a fallback so these trades still resolve to a real
        // strategy bin instead of UNKNOWN.
        val effectiveModeName: String = if (trade.tradingMode.isBlank() && trade.reason.isNotBlank()) {
            val r = trade.reason.uppercase()
            when {
                r.contains("MOONSHOT") -> "MOONSHOT"
                r.contains("SHITCOIN") -> "SHITCOIN"
                // V5.9.1300 — CASHGEN == TREASURY (same trader); fold to one bin.
                r.contains("CASHGEN") -> "TREASURY"
                r.contains("TREASURY") -> "TREASURY"
                r.contains("BLUECHIP") -> "BLUECHIP"
                r.contains("BLUE_CHIP") -> "BLUECHIP"
                r.contains("QUALITY") -> "QUALITY"
                r.contains("MANIP") -> "MANIPULATED"
                r.contains("EXPRESS") -> "EXPRESS"
                r.contains("CYCLIC") -> "CYCLIC"
                r.contains("COPY") -> "COPYTRADE"
                r.contains("SNIPER") || r.contains("PROJECT_SNIPER") -> "PROJECT_SNIPER"
                r.contains("DIP_HUNTER") || r.contains("DIP") -> "DIP_HUNTER"
                r.contains("LONG_HOLD") -> "LONG_HOLD"
                r.contains("MOMENTUM") -> "MOMENTUM_SWING"
                r.contains("PRESALE") -> "PRESALE_SNIPE"
                r.contains("RAPID_") || r.contains("FLUID") -> "STANDARD"
                else -> trade.tradingMode
            }
        } else trade.tradingMode
        var mode = CanonicalOutcomeNormalizer.normalizeMode(effectiveModeName)
        val env = if (trade.mode.equals("paper", true)) TradeEnvironment.PAPER else TradeEnvironment.LIVE
        val pnlPct = trade.pnlPct
        val isSyntheticBadPartial = isPartialSide && trade.price <= 0.0 && kotlin.math.abs(trade.pnlSol) > 0.0000001
        // V5.9.1236 — ROOT-CAUSE FIX for the learning-vs-trade-count gap.
        // Real executed closes with an unresolved lane (e.g. blank tradingMode +
        // reason="predictive_hard_floor_stop" / "fluid_stop_loss" — no lane
        // keyword) were normalising to TradeMode.UNKNOWN and getting permanently
        // rejected as UNKNOWN_LANE. Operator forensic: canonicalRaw≈2043,
        // rejectedBadLabels≈1733, settled trainable≈221. Those rejected rows are
        // legitimate settled trades, just lane-untagged at the exit path.
        // A genuinely executed close (paper, or live with a tx sig) that has
        // real accounting is a trainable STANDARD/V3 outcome — STANDARD routes to
        // MEME/V3 in inferAssetClassAndSource and is NOT rejected by the
        // normalizer. Only true non-executed / phantom rows stay UNKNOWN.
        // V5.9.1355 P0.1 — CROSS-DOMAIN CONTAMINATION FIRE-WALL.
        // BEFORE the meme-default below can fire, check whether the raw
        // tradingMode actually belongs to a FOREIGN asset domain (Stocks /
        // Forex / Perps / Metals / Commodities / CryptoAlt). Those traders set
        // tradingMode="Stocks"/"Forex"/"Perps_5x"/"Metals"/"Commodities"/
        // "CryptoAlt_SPOT" — none of which match a meme keyword, so they were
        // normalising to UNKNOWN and then getting force-defaulted to STANDARD,
        // which inferAssetClassAndSource maps to AssetClass.MEME. Result: an
        // AVGO/QCOM/GBPJPY close trained every meme brain. Resolve the foreign
        // domain explicitly so it is published with its TRUE assetClass and the
        // meme-brain subscribers reject it (they hard-gate on assetClass==MEME).
        var foreignAsset: AssetClass? = null
        if (mode == TradeMode.UNKNOWN) {
            val rawAsset = CanonicalOutcomeNormalizer.normalizeAssetClass(trade.tradingMode)
            if (rawAsset != AssetClass.UNKNOWN && rawAsset != AssetClass.MEME) {
                foreignAsset = rawAsset
            }
        }
        if (mode == TradeMode.UNKNOWN && foreignAsset == null) {
            val isExitLike = isPartialSide || trade.side.equals("SELL", ignoreCase = true)
            val executed = trade.sig.isNotBlank() || env == TradeEnvironment.PAPER
            val hasRealPrice = trade.price > 0.0
            if (isExitLike && executed && hasRealPrice && !isSyntheticBadPartial) {
                mode = TradeMode.STANDARD
                try {
                    ForensicLogger.lifecycle(
                        "CANON_UNRESOLVED_LANE_DEFAULTED_STANDARD",
                        "mint=${trade.mint.take(10)} reason=${trade.reason.take(40)} env=${env.name} pnlPct=${"%.2f".format(pnlPct)}",
                    )
                } catch (_: Throwable) {}
            }
        }
        // If a foreign domain was detected, publish it with the correct
        // assetClass + a non-meme source so meme learning never sees it.
        val (assetClass, source) = if (foreignAsset != null) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEARNING_DOMAIN_REJECTED|src=${foreignAsset.name}|targetBrain=MEME|reason=CROSS_DOMAIN")
            } catch (_: Throwable) {}
            foreignAsset to TradeSource.MARKETS
        } else {
            inferAssetClassAndSource(mode)
        }
        val result = when {
            pnlPct > 0.5 -> TradeResult.WIN
            pnlPct < -0.5 -> TradeResult.LOSS
            else -> TradeResult.BREAKEVEN
        }
        val executionResult = if (trade.sig.isNotBlank() || env == TradeEnvironment.PAPER)
            ExecutionResult.EXECUTED else ExecutionResult.UNKNOWN

        // V5.9.1035 — LITE-RICH LEGACY BRIDGE. Pre-V5.9.1035 every legacy
        // bridge publish defaulted featuresIncomplete=true → BehaviorLearning
        // + AdaptiveLearningEngine skipped 97% of outcomes (operator V5.9.1034b
        // dump: rich=27 incomplete=826, strategy-trainable=14 of 514). Strategy
        // learning was effectively useless. The Trade record doesn't carry
        // TokenState, so we can't reproduce the full CanonicalFeaturesBuilder
        // payload — but we DO have authoritative mode + source mapping, which
        // already gives us trader/venue/route/assetClass at lane granularity.
        // For any known mode (non-UNKNOWN), build a lite-rich CandidateFeatures
        // with the inferable fields populated and use "LIQ_UNKNOWN"/"MCAP_UNKNOWN"
        // markers for buckets we lack (the bucket→numeric helpers in
        // AdaptiveLearningEngine already have safe `else ->` defaults). Pattern
        // signatures collide a bit more than the rich Executor.recordTrade path,
        // but the brains actually LEARN from every settled trade now instead of
        // 3% of them. Modes that normalise to UNKNOWN (very rare — only happens
        // when Trade.tradingMode is blank/garbage) stay incomplete.
        val (liteCandidate, liteIncomplete) = buildLiteLegacyCandidate(trade, mode, source, assetClass, env)

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
            // V5.9.1428 — derive entryPrice from the exit/pct relationship so the
            // normalizer's reconstruct-before-reject and the WIN/EXTREME checks
            // always have a price anchor. Legacy Trade carries no entry price;
            // entry = exit / (1 + pct/100) is exact when exit price is real.
            entryPrice = run {
                val ex = trade.price
                val p = pnlPct
                if (ex > 0.0) (ex / (1.0 + p / 100.0)).takeIf { it.isFinite() && it > 0.0 } else null
            },
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
            candidate = liteCandidate,
            featuresIncomplete = liteIncomplete,
            isPartial = isPartialSide,
            partialIndex = if (isPartialSide) trade.reason.filter { it.isDigit() }.toIntOrNull() ?: 1 else 0,
            parentPositionId = trade.mint.takeIf { isPartialSide },
            costBasisSol = trade.sol.takeIf { it > 0.0 },
            proceedsSol = (trade.sol + (trade.netPnlSol.takeIf { it != 0.0 } ?: trade.pnlSol)).coerceAtLeast(0.0),
            feesSol = trade.feeSol,
            isTrainable = !isSyntheticBadPartial,
            invalidReason = if (isSyntheticBadPartial) "PARTIAL_SELL_INVALID_ACCOUNTING" else null,
        )
        publish(outcome)
    }

    /**
     * V5.9.1035 — Build a LITE-RICH CandidateFeatures payload from a Trade
     * record alone (no TokenState available). Populates the fields whose
     * absence triggers the "missing" check in CanonicalFeaturesBuilder
     * (trader, venue, route, assetClass, runtimeMode, liqBucket, mcapBucket).
     * Returns (candidate, isIncomplete) — incomplete only when mode is
     * UNKNOWN (i.e., Trade.tradingMode was blank or unrecognised).
     */
    private fun buildLiteLegacyCandidate(
        trade: Trade,
        mode: TradeMode,
        source: TradeSource,
        assetClass: AssetClass,
        env: TradeEnvironment,
    ): Pair<CandidateFeatures?, Boolean> {
        if (mode == TradeMode.UNKNOWN) return null to true
        val venue: String = when (source) {
            TradeSource.SHITCOIN, TradeSource.MOONSHOT, TradeSource.MANIP,
            TradeSource.EXPRESS, TradeSource.CYCLIC, TradeSource.COPYTRADE -> "PUMP_FUN_BONDING"
            TradeSource.BLUECHIP -> "JUPITER"
            TradeSource.TREASURY -> "JUPITER"
            TradeSource.MARKETS -> "JUPITER"
            TradeSource.V3 -> "PUMP_FUN_BONDING"
            else -> "UNKNOWN"
        }
        if (venue == "UNKNOWN") return null to true
        val route: String = when (venue) {
            "PUMP_FUN_BONDING" -> "PUMP_NATIVE"
            "PUMPSWAP" -> "PUMPPORTAL"
            else -> "JUPITER"
        }
        val cand = CandidateFeatures(
            assetClass = assetClass.name,
            runtimeMode = env.name,
            trader = mode.name,
            venue = venue,
            route = route,
            bondingCurveActive = venue == "PUMP_FUN_BONDING",
            migrated = false,
            ageBucket = "",                 // unknown from Trade record alone
            liqBucket = "LIQ_UNKNOWN",      // marker — bucket→numeric helpers default-safe
            mcapBucket = "MCAP_UNKNOWN",
            volVelocity = "",
            buyPressure = "",
            sellPressure = "",
            holderGrowth = "",
            holderConcentration = "",
            rugTier = "",
            safetyTier = "",
            mintAuthority = "",
            freezeAuthority = "",
            slippageBucket = "",
            entryPattern = "LEGACY_LITE",
            bubbleClusterPattern = "",
            fdgReasonFamily = "",
            symbolicVerdict = "",
            exitReasonFamily = trade.reason.ifBlank { "" },
            holdBucket = "",
            manualOrExternalClose = false,
        )
        return cand to false
    }

    /**
     * V5.9.1236 — canonical inverse of inferAssetClassAndSource. Producers that
     * only know their TradeSource (e.g. CanonicalPublishHelper specialist-lane
     * exits) must resolve a real TradeMode so the outcome is trainable. Without
     * this, source-only publishers stamped mode=UNKNOWN → UNKNOWN_LANE reject →
     * the trade was silently dropped from learning despite being a real close.
     */
    fun modeFromSource(source: TradeSource): TradeMode = when (source) {
        TradeSource.SHITCOIN -> TradeMode.SHITCOIN
        TradeSource.BLUECHIP -> TradeMode.BLUECHIP
        TradeSource.EXPRESS -> TradeMode.EXPRESS
        TradeSource.MOONSHOT -> TradeMode.MOONSHOT
        TradeSource.MANIP -> TradeMode.MANIP
        TradeSource.TREASURY -> TradeMode.TREASURY
        TradeSource.MARKETS -> TradeMode.ALTTRADER
        TradeSource.CYCLIC -> TradeMode.CYCLIC
        TradeSource.COPYTRADE -> TradeMode.COPY_TRADE
        // V3 is the generic meme router → STANDARD (trainable, routes to MEME/V3).
        TradeSource.V3 -> TradeMode.STANDARD
        // MANUAL / UNKNOWN have no strategy lane to learn.
        TradeSource.MANUAL, TradeSource.UNKNOWN -> TradeMode.UNKNOWN
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
        // V5.9.1035 — Trade.tradingMode defaults to "STANDARD" so most
        // meme closes (Quality lane, default ShitCoin sub-lane) used to
        // normalise to TradeMode.STANDARD and then collapse here to
        // (UNKNOWN, UNKNOWN) — collapsing strategy learning. Route
        // STANDARD/PROJECT_SNIPER/DIP_HUNTER/COMMUNITY into the MEME
        // bucket via TradeSource.V3 (which already maps to
        // PUMP_FUN_BONDING in CanonicalFeaturesBuilder).
        TradeMode.STANDARD,
        TradeMode.PROJECT_SNIPER,
        TradeMode.DIP_HUNTER,
        TradeMode.QUALITY,        // V5.9.1343 — Quality lane is a meme-lane V3 strategy
        TradeMode.LAB,            // V5.9.1343 — LLM Lab trades meme tokens
        TradeMode.COMMUNITY -> AssetClass.MEME to TradeSource.V3
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

    /** V5.9.1353 — TRUE RESET: clear all per-layer education state so every
     *  layer drops back to DISCONNECTED until it re-accumulates real samples. */
    fun reset() { states.clear() }

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
    // V5.9.949 — persistence hooks. LayerReadinessRegistry drives the
    // FDG bootstrap gate ("AI degraded" soft-shaper) by aggregating every
    // layer's lifetime education count + positive-EV samples. Losing this
    // on restart re-deadlocked the bot at the 32% FDG floor every time
    // (until V5.9.947's paper-mode learning tier). Persisting it locks
    // in the AGI training that's already happened. Wired into LearningPersistence.
    fun exportState(): String {
        val arr = org.json.JSONArray()
        for ((layer, st) in states) {
            arr.put(org.json.JSONObject().apply {
                put("layer", layer)
                put("settled", st.settledOutcomes)
                put("posEv", st.positiveEvSamples)
                put("lastEdMs", st.lastEducationMs)
                put("rich", st.richEducationCount)
                put("incomplete", st.incompleteEducationCount)
            })
        }
        return arr.toString()
    }

    fun importState(json: String) {
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val layer = o.optString("layer", "")
                if (layer.isBlank()) continue
                val s = states.getOrPut(layer) { State() }
                s.settledOutcomes = o.optLong("settled", 0L)
                s.positiveEvSamples = o.optLong("posEv", 0L)
                s.lastEducationMs = o.optLong("lastEdMs", 0L)
                s.richEducationCount = o.optLong("rich", 0L)
                s.incompleteEducationCount = o.optLong("incomplete", 0L)
            }
        } catch (_: Throwable) { /* fail-open */ }
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
